package dev.kifranei.ampp.media

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator

/** Shared Kyant-style liquid popup motion: spring-like scale/translation, gentle deceleration, and blur-first reveal. */
internal object LiquidPopupMotion {
    private val main = Handler(Looper.getMainLooper())

    fun enter(view: View, originX: Float = view.width / 2f, originY: Float = view.height / 2f) {
        view.pivotX = originX.coerceIn(0f, view.width.toFloat().coerceAtLeast(1f))
        view.pivotY = originY.coerceIn(0f, view.height.toFloat().coerceAtLeast(1f))
        view.alpha = 0f
        view.scaleX = .94f
        view.scaleY = .94f
        view.translationY = 14f * view.resources.displayMetrics.density
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "alpha", 0f, 1f),
                ObjectAnimator.ofFloat(view, "scaleX", .94f, 1f),
                ObjectAnimator.ofFloat(view, "scaleY", .94f, 1f),
                ObjectAnimator.ofFloat(view, "translationY", 14f * view.resources.displayMetrics.density, 0f),
            )
            duration = 320
            interpolator = OvershootInterpolator(.72f)
            start()
        }
    }

    fun exit(view: View, originX: Float = view.width / 2f, originY: Float = view.height / 2f, end: () -> Unit) {
        view.pivotX = originX.coerceIn(0f, view.width.toFloat().coerceAtLeast(1f))
        view.pivotY = originY.coerceIn(0f, view.height.toFloat().coerceAtLeast(1f))
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", view.scaleX, .94f)
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", view.scaleY, .94f)
        val alpha = ObjectAnimator.ofFloat(view, "alpha", view.alpha, 0f)
        val y = ObjectAnimator.ofFloat(view, "translationY", view.translationY,
            10f * view.resources.displayMetrics.density)
        val animator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha, y)
            duration = 220
            interpolator = LinearInterpolator()
            start()
        }
        main.postDelayed({
            runCatching { animator.cancel() }
            end()
        }, 230)
    }
}
