package dev.kifranei.ampp.media

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.WeakHashMap

/** Shared Kyant-style liquid popup motion: spring-like scale/translation, gentle deceleration, and blur-first reveal. */
internal object LiquidPopupMotion {
    private val main = Handler(Looper.getMainLooper())
    private val running = WeakHashMap<View, MutableList<SpringAnimation>>()

    private fun spring(view: View, property: Int, final: Float, damping: Float, stiffness: Float,
        minimum: Float = .001f): SpringAnimation =
        SpringAnimation(view, property, final).apply {
            spring = SpringForce(final).setDampingRatio(damping).setStiffness(stiffness)
            minimumVisibleChange = minimum
            running.getOrPut(view, ::mutableListOf).add(this)
        }

    fun cancel(view: View) {
        running.remove(view)?.forEach { animation -> runCatching { animation.cancel() } }
    }

    fun enter(view: View, originX: Float = view.width / 2f, originY: Float = view.height / 2f,
        direction: PlayerMenuOpenDirection = PlayerMenuOpenDirection.CENTER) {
        cancel(view)
        view.pivotX = originX.coerceIn(0f, view.width.toFloat().coerceAtLeast(1f))
        view.pivotY = originY.coerceIn(0f, view.height.toFloat().coerceAtLeast(1f))
        view.alpha = 0f
        view.scaleX = .94f
        view.scaleY = .94f
        view.translationY = enterOffset(view, direction)
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "alpha", 0f, 1f),
            )
            duration = 180
            interpolator = LinearInterpolator()
            start()
        }
        spring(view, SpringAnimation.SCALE_X, 1f, damping = .60f, stiffness = 250f)
        spring(view, SpringAnimation.SCALE_Y, 1f, damping = .70f, stiffness = 250f)
        spring(view, SpringAnimation.TRANSLATION_Y, 0f, damping = .50f, stiffness = 300f, minimum = .5f)
    }

    fun exit(view: View, originX: Float = view.width / 2f, originY: Float = view.height / 2f,
        direction: PlayerMenuOpenDirection = PlayerMenuOpenDirection.CENTER, end: () -> Unit) {
        cancel(view)
        view.pivotX = originX.coerceIn(0f, view.width.toFloat().coerceAtLeast(1f))
        view.pivotY = originY.coerceIn(0f, view.height.toFloat().coerceAtLeast(1f))
        ObjectAnimator.ofFloat(view, "alpha", view.alpha, 0f).apply {
            duration = 180
            interpolator = LinearInterpolator()
            start()
        }
        spring(view, SpringAnimation.SCALE_X, .94f, damping = .60f, stiffness = 250f)
        spring(view, SpringAnimation.SCALE_Y, .94f, damping = .70f, stiffness = 250f)
        spring(view, SpringAnimation.TRANSLATION_Y, enterOffset(view, direction), damping = .50f,
            stiffness = 300f, minimum = .5f)
        main.postDelayed({
            cancel(view)
            end()
        }, 240)
    }

    /** Expand toward the free side: down-opening menus start slightly above their anchor, up-opening below. */
    private fun enterOffset(view: View, direction: PlayerMenuOpenDirection): Float = when (direction) {
        PlayerMenuOpenDirection.DOWN -> -14f * view.resources.displayMetrics.density
        PlayerMenuOpenDirection.UP -> 14f * view.resources.displayMetrics.density
        PlayerMenuOpenDirection.CENTER -> 14f * view.resources.displayMetrics.density
    }
}
