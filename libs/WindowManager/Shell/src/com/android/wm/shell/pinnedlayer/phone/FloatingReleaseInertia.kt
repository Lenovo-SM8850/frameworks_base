// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.graphics.PointF
import android.view.VelocityTracker
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringForce
import com.android.wm.shell.shared.animation.PhysicsAnimator
import kotlin.math.abs
import kotlin.math.max

/**
 * Release inertia of a dragged floating window: a short, heavily damped fling of its top-left
 * corner that settles inside the movement limits. UI-agnostic; the caller draws every frame and
 * commits the final position. Main thread only.
 */
object FloatingReleaseInertia {
    /** Fling friction; travel is about velocity / (3 * 4.2), so 2000 px/s drifts about 160 px. */
    const val FRICTION = 3.0f

    /** Slower releases commit immediately. */
    const val MIN_RELEASE_VELOCITY_DP = 250f

    /** Spring used after the fling reaches a movement limit. */
    const val SPRING_STIFFNESS = 700f
    @JvmField val SPRING_DAMPING_RATIO = SpringForce.DAMPING_RATIO_NO_BOUNCY

    /** Mutable top-left corner driven by the animation. */
    class Position(@JvmField var left: Float = 0f, @JvmField var top: Float = 0f)

    /** Movement limits of the top-left corner. */
    class Limits(val minLeft: Float, val minTop: Float, val maxLeft: Float, val maxTop: Float)

    /** A running inertia animation. */
    class Run(
        val position: Position,
        private val animator: PhysicsAnimator<Position>? = null,
    ) {
        val isRunning: Boolean get() = animator?.isRunning() == true

        /** Stops the animation without calling the end callback. */
        fun cancel() { animator?.cancel() }
    }

    private val LEFT = object : FloatPropertyCompat<Position>("left") {
        override fun getValue(position: Position) = position.left
        override fun setValue(position: Position, value: Float) { position.left = value }
    }
    private val TOP = object : FloatPropertyCompat<Position>("top") {
        override fun getValue(position: Position) = position.top
        override fun setValue(position: Position, value: Float) { position.top = value }
    }

    @JvmStatic
    fun minVelocityPx(density: Float) = MIN_RELEASE_VELOCITY_DP * density

    /** Release velocity of [pointerId] in px/s, in the coordinates the tracker was fed. */
    @JvmStatic
    @JvmOverloads
    fun velocityOf(tracker: VelocityTracker, pointerId: Int, maxVelocity: Float,
        out: PointF = PointF()): PointF {
        tracker.computeCurrentVelocity(1000, maxVelocity)
        return out.apply { set(tracker.getXVelocity(pointerId), tracker.getYVelocity(pointerId)) }
    }

    /** True when either axis reaches the minimum release velocity. */
    @JvmStatic
    fun isFast(velocityX: Float, velocityY: Float, minVelocity: Float): Boolean =
        velocityX.isFinite() && velocityY.isFinite() &&
            max(abs(velocityX), abs(velocityY)) >= minVelocity

    /** Where a fling from [start] with [velocity] stops on one axis, within [min]..[max]. */
    @JvmStatic
    fun estimateEnd(start: Float, velocity: Float, min: Float, max: Float): Float =
        PhysicsAnimator.estimateFlingEndValue(start, velocity,
            PhysicsAnimator.FlingConfig(FRICTION, min, max))

    /**
     * Flings [position] with the release velocity, then springs it to a limit it reached. Returns
     * null, without animating, when the release is slower than [minVelocity].
     *
     * [onFrame] runs after every update with [position] already moved; [onEnd] runs once when
     * both axes have settled, and not after [Run.cancel].
     */
    @JvmStatic
    fun start(
        position: Position,
        velocityX: Float,
        velocityY: Float,
        minVelocity: Float,
        limits: Limits,
        onFrame: (Position) -> Unit,
        onEnd: (Position) -> Unit,
    ): Run? {
        if (!isFast(velocityX, velocityY, minVelocity)) return null
        val spring = PhysicsAnimator.SpringConfig(SPRING_STIFFNESS, SPRING_DAMPING_RATIO)
        val animator = PhysicsAnimator.getInstance(position)
        animator
            .flingThenSpring(LEFT, velocityX,
                PhysicsAnimator.FlingConfig(FRICTION, limits.minLeft, limits.maxLeft), spring)
            .flingThenSpring(TOP, velocityY,
                PhysicsAnimator.FlingConfig(FRICTION, limits.minTop, limits.maxTop), spring)
            .addUpdateListener(PhysicsAnimator.UpdateListener<Position> { target, _ -> onFrame(target) })
            .withEndActions(Runnable { onEnd(position) })
            .start()
        return Run(position, animator)
    }
}
