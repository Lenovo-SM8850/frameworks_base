// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import com.android.wm.shell.R
import com.android.wm.shell.shared.animation.Interpolators
import java.util.concurrent.Executor

/** Decoration-owned focus presentation, independent of transition geometry. */
class FloatingFocusIndicator(private val onFrame: (Float, Float) -> Unit) : AutoCloseable {
    data class State(val focused: Boolean, val bounds: Rect, val logicalBounds: Rect,
        val scale: Float, val density: Int)

    companion object {
        @JvmStatic fun select(task: RunningTaskInfo, mini: Boolean): State? =
            if (task.isFloatingWindow && task.isVisible && !mini)
                State(task.isFocused, Rect(task.presentationBounds),
                    Rect(task.configuration.windowConfiguration.bounds), task.floatingWindowScale,
                    task.configuration.densityDpi) else null

        @JvmStatic fun shadowRadiusId(focused: Boolean): Int =
            if (focused) R.dimen.floating_decor_shadow_focused_radius
            else R.dimen.floating_decor_shadow_unfocused_radius

        @JvmStatic fun handleAlpha(focused: Boolean): Float = if (focused) 1f else .4f

        @JvmStatic fun canAnimate(previous: State?, next: State): Boolean =
            previous != null && previous.focused != next.focused &&
                previous.bounds == next.bounds && previous.logicalBounds == next.logicalBounds &&
                previous.scale == next.scale &&
                previous.density == next.density
    }

    // Keep surface and pill updates on the decoration's owning Shell looper.
    private val handler = Handler(Looper.myLooper() ?: Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private var state: State? = null
    private var animator: ValueAnimator? = null
    private var generation = 0
    private var pending = false
    private var shadow = 0f
    private var targetShadow = 0f
    var alpha = 1f
        private set
    val initialized: Boolean get() = state != null

    fun update(task: RunningTaskInfo, context: Context,
        finishT: SurfaceControl.Transaction? = null) {
        val next = checkNotNull(select(task, false))
        val target = context.resources.getDimension(shadowRadiusId(next.focused))
        val unchanged = state == next && targetShadow == target
        if (unchanged && (finishT == null || animator == null && !pending)) return
        val animate = canAnimate(state, next) || unchanged && (animator != null || pending)
        generation++
        val token = generation
        animator?.cancel()
        animator = null
        pending = false
        state = next
        targetShadow = target
        val targetAlpha = handleAlpha(next.focused)
        if (!animate) {
            shadow = target
            alpha = targetAlpha
            onFrame(shadow, alpha)
            return
        }
        val startShadow = shadow
        val startAlpha = alpha
        val start = {
            if (generation == token) {
                pending = false
                val animation = ValueAnimator.ofFloat(0f, 1f).apply {
                    // Platform timing, scaled with the animator duration scale.
                    duration = context.resources.getInteger(
                        android.R.integer.config_shortAnimTime).toLong()
                    interpolator = Interpolators.FAST_OUT_SLOW_IN
                    addUpdateListener {
                        val progress = it.animatedValue as Float
                        shadow = startShadow + (target - startShadow) * progress
                        alpha = startAlpha + (targetAlpha - startAlpha) * progress
                        onFrame(shadow, alpha)
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (generation == token) animator = null
                        }
                    })
                }
                animator = animation
                animation.start()
            }
        }
        if (finishT != null) {
            // Start after Core's reset and the decoration's finish restore have committed.
            pending = true
            finishT.addTransactionCommittedListener(executor) { start() }
        } else start()
    }

    override fun close() {
        generation++
        animator?.cancel()
        animator = null
        pending = false
        state = null
        alpha = 1f
    }
}
