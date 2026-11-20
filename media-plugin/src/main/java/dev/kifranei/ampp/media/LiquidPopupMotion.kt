package dev.kifranei.ampp.media

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

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
            )
            duration = 180
            interpolator = LinearInterpolator()
            start()
        }
        SpringAnimation(view, SpringAnimation.SCALE_X, 1f).apply {
            spring = SpringForce(1f).setDampingRatio(.60f).setStiffness(250f)
            minimumVisibleChange = .001f
            start()
        }
        SpringAnimation(view, SpringAnimation.SCALE_Y, 1f).apply {
            spring = SpringForce(1f).setDampingRatio(.70f).setStiffness(250f)
            minimumVisibleChange = .001f
            start()
        }
        SpringAnimation(view, SpringAnimation.TRANSLATION_Y, 0f).apply {
            spring = SpringForce(0f).setDampingRatio(.50f).setStiffness(300f)
            minimumVisibleChange = .5f
            start()
        }
    }

    fun exit(view: View, originX: Float = view.width / 2f, originY: Float = view.height / 2f, end: () -> Unit) {
        view.pivotX = originX.coerceIn(0f, view.width.toFloat().coerceAtLeast(1f))
        view.pivotY = originY.coerceIn(0f, view.height.toFloat().coerceAtLeast(1f))
        val alpha = ObjectAnimator.ofFloat(view, "alpha", view.alpha, 0f)
        alpha.apply {
            duration = 180
            interpolator = LinearInterpolator()
            start()
        }
        SpringAnimation(view, SpringAnimation.SCALE_X, .94f).apply {
            spring = SpringForce(.94f).setDampingRatio(.60f).setStiffness(250f)
            minimumVisibleChange = .001f
            start()
        }
        SpringAnimation(view, SpringAnimation.SCALE_Y, .94f).apply {
            spring = SpringForce(.94f).setDampingRatio(.70f).setStiffness(250f)
            minimumVisibleChange = .001f
            start()
        }
        SpringAnimation(view, SpringAnimation.TRANSLATION_Y, 10f * view.resources.displayMetrics.density).apply {
            spring = SpringForce(10f * view.resources.displayMetrics.density)
                .setDampingRatio(.50f).setStiffness(300f)
            minimumVisibleChange = .5f
            start()
        }
        main.postDelayed({
            end()
        }, 240)
    }
}
