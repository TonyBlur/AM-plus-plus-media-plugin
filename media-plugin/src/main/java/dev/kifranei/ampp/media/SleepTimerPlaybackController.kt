package dev.kifranei.ampp.media

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.app.Dialog
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/** State machine for Apple Music's native sleep-timer dialog. */
internal object SleepTimerDurationParser {
    fun parse(text: String): Long? {
        val normalized = text.replace(Regex("\\s+"), " ").trim().lowercase()
        val off = normalized in setOf("off", "关闭", "取消", "none", "disable")
        if (off) return 0L
        Regex("(\\d+)\\s*(分钟|min(?:ute)?s?)").find(normalized)?.groupValues?.get(1)?.toLongOrNull()?.let {
            return it.coerceAtLeast(1) * 60_000L
        }
        Regex("(\\d+)\\s*(小时|hrs?|hours?)").find(normalized)?.groupValues?.get(1)?.toLongOrNull()?.let {
            return it.coerceAtLeast(1) * 3_600_000L
        }
        Regex("(\\d+)\\s*时").find(normalized)?.groupValues?.get(1)?.toLongOrNull()?.let {
            return it.coerceAtLeast(1) * 3_600_000L
        }
        return null
    }

    fun textOf(view: View): String {
        if (view is TextView) view.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (view is ViewGroup) {
            val parts = (0 until view.childCount).mapNotNull { child ->
                textOf(view.getChildAt(child)).takeIf { it.isNotBlank() }
            }
            if (parts.isNotEmpty()) return parts.joinToString(" ")
        }
        return ""
    }

    fun rowAt(view: ViewGroup, x: Float, y: Float): View? {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val localX = x - location[0]
        val localY = y - location[1]
        for (index in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(index)
            val hit = child.left <= localX && localX <= child.right &&
                child.top <= localY && localY <= child.bottom
            if (hit) return child
        }
        return null
    }
}

internal object SleepTimerSelectionPolicy {
    /** Consume only a positive timer selection when the plugin replaces the native stop action. */
    fun interceptNativeSelection(durationMs: Long?, stopAfterTrack: Boolean): Boolean =
        stopAfterTrack && durationMs != null && durationMs > 0L
}

internal object SleepTimerFinishPolicy {
    /**
     * Stop when the current track finishes: either its queue index arguments changed, or the
     * playback reached ExoPlayer STATE_ENDED (4). Queue-index argument order differs across
     * Apple Music builds, so compare whole signatures instead of one "index" value.
     */
    fun shouldStopOnIndexChange(waiting: Boolean, expiry: List<Int>, current: List<Int>): Boolean =
        waiting && current.isNotEmpty() && current != expiry

    fun shouldStopOnPlaybackEnd(waiting: Boolean, states: List<Int>): Boolean =
        waiting && states.any { it == 4 }
}

internal class SleepTimerPlaybackController(
    private val build: TargetBuild,
    private val enabled: () -> Boolean,
    private val scope: PluginScope,
) {
    private val main = Handler(Looper.getMainLooper())
    private val armed = AtomicBoolean(false)
    private val runtimeEnabled = AtomicBoolean(enabled())
    @Volatile private var deadlineMs = 0L
    @Volatile private var waitingForTrackEnd = false
    @Volatile private var exoPlayer: Any? = null
    @Volatile private var indexSignature: List<Int> = emptyList()
    @Volatile private var expirySignature: List<Int> = emptyList()

    fun install() {
        val loader = MediaRuntime.context.hostClassLoader
        val buildProfile = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)
        val playerDialogs = buildProfile?.document?.optJSONObject("playerDialogs")
        if (playerDialogs != null) {
            runCatching {
                val sleepType = PluginProfiles.type(playerDialogs.getString("sleepFragmentClass"))
                val create = PluginProfiles.method("more-dialog-create")
                MediaRuntime.observeMethod(create, object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val dialog = param.result as? Dialog ?: return
                        if (param.throwable != null) return
                        val ownerMatches = runCatching { sleepType.isInstance(param.thisObject) }.getOrDefault(false)
                        val textMatches = looksLikeSleepTimerDialog(dialog)
                        if (!ownerMatches && !textMatches) return
                        val activity = dialog.context as? android.app.Activity ?: return
                        val listId = activity.resources.getIdentifier(
                            playerDialogs.getString("sleepListId"), "id", build.packageName)
                        val list = dialog.findViewById<ViewGroup>(listId) ?: return
                        attachNativeTimerSelection(dialog, list)
                    }
                }, scope)
            }.onFailure {
                MediaRuntime.log("sleep_timer: native dialog selection hook unavailable", it)
            }
        }
        val exoClass = runCatching { loader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer") }.getOrNull()
        if (exoClass != null) {
            exoClass.declaredConstructors.forEach { constructor ->
                MediaRuntime.observeMethod(constructor, object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable == null) exoPlayer = param.thisObject
                    }
                }, scope)
            }
        }

        runCatching {
            val controllerClass = loader.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController")
            val indexMethod = controllerClass.declaredMethods.firstOrNull {
                it.name == "onPlaybackIndexChanged" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            }
            if (indexMethod != null) {
                MediaRuntime.observeMethod(indexMethod, object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val playerArg = param.args.firstOrNull()
                        if (playerArg != null && exoClass?.isInstance(playerArg) == true) exoPlayer = playerArg
                        val ints = param.args.filterIsInstance<Int>()
                        if (SleepTimerFinishPolicy.shouldStopOnIndexChange(waitingForTrackEnd, expirySignature, ints)) {
                            indexSignature = ints
                            MediaRuntime.log("sleep_timer: track changed after the timer expired", null)
                            stopAfterTrack()
                        } else if (ints.isNotEmpty()) {
                            indexSignature = ints
                        }
                    }
                }, scope)
            }
            val stateMethod = controllerClass.declaredMethods.firstOrNull {
                it.name == "onPlaybackStateChanged" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            }
            if (stateMethod != null) {
                MediaRuntime.observeMethod(stateMethod, object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val playerArg = param.args.firstOrNull()
                        if (playerArg != null && exoClass?.isInstance(playerArg) == true) exoPlayer = playerArg
                        val states = param.args.filterIsInstance<Int>()
                        if (SleepTimerFinishPolicy.shouldStopOnPlaybackEnd(waitingForTrackEnd, states)) {
                            MediaRuntime.log("sleep_timer: playback ended after the timer expired", null)
                            stopAfterTrack()
                        }
                    }
                }, scope)
            }
        }.onFailure {
            MediaRuntime.log("sleep_timer: playback-state callback unavailable", it)
        }

        scope.activate()
    }

    fun arm(durationMs: Long) {
        main.removeCallbacksAndMessages(TIMER_TOKEN)
        if (!runtimeEnabled.get()) {
            cancel()
            return
        }
        if (durationMs <= 0L) {
            cancel()
            return
        }
        deadlineMs = SystemClock.uptimeMillis() + durationMs
        armed.set(true)
        waitingForTrackEnd = false
        expirySignature = indexSignature
        main.postAtTime({
            if (!armed.get()) return@postAtTime
            waitingForTrackEnd = true
            expirySignature = indexSignature
            MediaRuntime.log("sleep_timer: countdown elapsed; waiting for the current track to finish", null)
        }, TIMER_TOKEN, deadlineMs)
    }

    fun cancel() {
        main.removeCallbacksAndMessages(TIMER_TOKEN)
        deadlineMs = 0L
        armed.set(false)
        waitingForTrackEnd = false
    }

    fun setEnabled(enabled: Boolean) {
        runtimeEnabled.set(enabled)
        if (!enabled) cancel()
    }

    private fun attachNativeTimerSelection(dialog: Dialog, list: ViewGroup) {
        main.post {
            list.setOnTouchListener { _, event ->
                var intercept = false
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val row = SleepTimerDurationParser.rowAt(list, event.rawX, event.rawY)
                    val duration = row?.let(SleepTimerDurationParser::textOf)?.let(SleepTimerDurationParser::parse)
                    if (duration != null) {
                        if (duration == 0L) cancel() else arm(duration)
                        intercept = SleepTimerSelectionPolicy.interceptNativeSelection(duration, runtimeEnabled.get())
                        if (intercept) dialog.dismiss()
                    }
                }
                intercept
            }
        }
    }

    private fun looksLikeSleepTimerDialog(dialog: Dialog): Boolean {
        val text = buildString {
            fun visit(view: View) {
                if (view is TextView) append(view.text?.toString().orEmpty()).append('\n')
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            dialog.window?.decorView?.let(::visit)
        }.lowercase()
        return text.contains("sleep timer") ||
            text.contains("睡眠定时") ||
            Regex("\\d+\\s*(分钟|小时|min(?:ute)?s?|hrs?|hours?)").containsMatchIn(text)
    }

    private fun stopAfterTrack() {
        if (!waitingForTrackEnd) return
        waitingForTrackEnd = false
        armed.set(false)
        deadlineMs = 0L
        val holder = exoPlayer
        if (holder == null) {
            MediaRuntime.log("sleep_timer: playback finished but ExoMediaPlayer was not captured; cannot stop", null)
            return
        }
        runCatching {
            val stop = holder.javaClass.getDeclaredMethod("stop").apply { isAccessible = true }
            stop.invoke(holder)
            MediaRuntime.log("sleep_timer: stopped after the current track finished", null)
        }.recoverCatching {
            val pause = holder.javaClass.getDeclaredMethod("pause").apply { isAccessible = true }
            pause.invoke(holder)
            MediaRuntime.log("sleep_timer: paused after the current track finished (stop unavailable)", null)
        }.onFailure {
            MediaRuntime.log("sleep_timer: failed to stop after final track", it)
        }
    }

    companion object {
        private val TIMER_TOKEN = Any()
    }
}
