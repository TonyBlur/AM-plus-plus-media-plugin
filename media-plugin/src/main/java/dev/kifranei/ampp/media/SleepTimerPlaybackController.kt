package dev.kifranei.ampp.media

import android.os.Handler
import android.os.Looper
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

    fun install() {
        val loader = MediaRuntime.context.hostClassLoader
        val exoClass = runCatching { loader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer") }.getOrNull()
        if (exoClass != null) {
            exoClass.declaredConstructors.forEach { constructor ->
                MediaRuntime.observeMethod(constructor, object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable == null) exoPlayer = param.thisObject
                    }
                }, scope)
            }
            for (name in listOf("pause", "stop")) {
                exoClass.declaredMethods.filter { it.name == name && it.parameterTypes.isEmpty() }.forEach { method ->
                    MediaRuntime.hookMethod(method, object : PluginMethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (shouldSuppressNativeStop()) param.result = null
                        }
                    }, scope)
                }
            }
        }

        runCatching {
            val controllerClass = loader.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController")
            val stateMethod = controllerClass.declaredMethods.firstOrNull {
                it.name == "onPlaybackStateChanged" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            }
            if (stateMethod != null) {
                MediaRuntime.observeMethod(stateMethod, object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val state = param.args.lastOrNull() as? Int ?: return
                        if (waitingForTrackEnd && state == 4) stopAfterTrack()
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
        deadlineMs = System.currentTimeMillis() + durationMs
        armed.set(true)
        waitingForTrackEnd = false
        main.postAtTime({
            if (!armed.get()) return@postAtTime
            waitingForTrackEnd = true
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

    private fun shouldSuppressNativeStop(): Boolean {
        if (!runtimeEnabled.get()) return false
        if (waitingForTrackEnd) return true
        if (!armed.get()) return false
        val now = System.currentTimeMillis()
        return now >= deadlineMs - 500L
    }

    private fun stopAfterTrack() {
        if (!waitingForTrackEnd) return
        waitingForTrackEnd = false
        armed.set(false)
        deadlineMs = 0L
        val holder = exoPlayer ?: return
        runCatching {
            val stop = holder.javaClass.getDeclaredMethod("stop").apply { isAccessible = true }
            stop.invoke(holder)
        }.recoverCatching {
            val pause = holder.javaClass.getDeclaredMethod("pause").apply { isAccessible = true }
            pause.invoke(holder)
        }.onFailure {
            MediaRuntime.log("sleep_timer: failed to stop after final track", it)
        }
    }

    companion object {
        private val TIMER_TOKEN = Any()
    }
}
