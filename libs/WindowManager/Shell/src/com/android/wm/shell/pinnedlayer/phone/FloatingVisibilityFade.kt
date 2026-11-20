// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.Choreographer
import android.view.SurfaceControl
import com.android.wm.shell.shared.animation.Interpolators
import kotlin.math.abs

/**
 * Fades a set of floating task surfaces with one alpha animator. Main thread only.
 *
 * A new request starts from the current alpha and scales its duration by the remaining distance.
 * [cancel] and [reset] never run the end callback.
 */
class FloatingVisibilityFade(
    private val animators: () -> ValueAnimator = { ValueAnimator.ofFloat(0f, 1f) },
    private val transactions: () -> SurfaceControl.Transaction = { SurfaceControl.Transaction() },
    private val vsyncId: () -> Long = { Choreographer.getInstance().vsyncId },
) {
    private class Run(val from: Float, val to: Float)

    private var run: Run? = null
    private var animator: ValueAnimator? = null
    private var surfaces: List<SurfaceControl> = emptyList()

    /** Alpha last applied to the faded surfaces. */
    var alpha = 1f
        private set

    /** Alpha the running fade moves toward, or the current alpha when idle. */
    val target: Float get() = run?.to ?: alpha
    val isRunning: Boolean get() = run != null

    /** Fades to 0, then calls [onEnd] once with the surfaces still at alpha 0. */
    fun fadeOut(surfaces: List<SurfaceControl>, onEnd: () -> Unit) =
        start(surfaces, 0f, FADE_OUT_MS, Interpolators.ALPHA_OUT, onEnd)

    fun fadeIn(surfaces: List<SurfaceControl>, onEnd: () -> Unit = {}) =
        start(surfaces, 1f, FADE_IN_MS, Interpolators.ALPHA_IN, onEnd)

    /** Stops the fade and leaves the surfaces at their current alpha. */
    fun cancel() {
        val running = animator
        run = null
        animator = null
        running?.cancel()
    }

    /** Applies [value] at once to [surfaces], replacing any running fade. */
    fun snap(surfaces: List<SurfaceControl>, value: Float) {
        cancel()
        this.surfaces = surfaces.toList()
        alpha = value
        applyTo(this.surfaces, value)
    }

    /** Stops the fade and puts the faded surfaces back to alpha 1. */
    fun reset() {
        cancel()
        if (alpha == 1f) return
        alpha = 1f
        applyTo(surfaces, 1f)
        surfaces = emptyList()
    }

    private fun start(next: List<SurfaceControl>, to: Float, duration: Long,
        interpolator: TimeInterpolator, onEnd: () -> Unit) {
        cancel()
        // Surfaces dropped from the new set must not stay translucent.
        if (alpha != 1f) {
            applyTo(surfaces.filter { old -> next.none { it === old } }, 1f)
        }
        surfaces = next.toList()
        val from = alpha
        val distance = abs(to - from)
        if (distance < EPSILON) {
            alpha = to
            applyTo(surfaces, to)
            onEnd()
            return
        }
        val thisRun = Run(from, to)
        run = thisRun
        val fade = animators()
        animator = fade
        fade.setDuration(Math.round(duration * distance).toLong().coerceAtLeast(1L))
        fade.setInterpolator(interpolator)
        fade.addUpdateListener {
            if (run !== thisRun) return@addUpdateListener
            val fraction = it.animatedValue as Float
            alpha = from + (to - from) * fraction
            applyTo(surfaces, alpha)
        }
        fade.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (run !== thisRun) return
                run = null
                animator = null
                alpha = to
                applyTo(surfaces, to)
                onEnd()
            }
        })
        fade.start()
    }

    private fun applyTo(targets: List<SurfaceControl>, value: Float) {
        val live = targets.filter { it.isValid }
        if (live.isEmpty()) return
        transactions().use { t ->
            live.forEach { t.setAlpha(it, value) }
            val vsync = runCatching { vsyncId() }.getOrDefault(-1L)
            if (vsync > 0) t.setFrameTimeline(vsync)
            t.apply()
        }
    }

    private companion object {
        const val FADE_OUT_MS = 150L
        const val FADE_IN_MS = 200L
        const val EPSILON = 0.001f
    }
}
