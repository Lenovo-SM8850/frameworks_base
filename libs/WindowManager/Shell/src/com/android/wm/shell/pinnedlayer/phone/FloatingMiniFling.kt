// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.graphics.Rect
import com.android.wm.shell.shared.animation.PhysicsAnimator
import kotlin.math.abs
import kotlin.math.hypot

/** Projects the display-coordinate release pointer without considering window size. */
object FloatingMiniFling {
    private const val MIN_VELOCITY_DP = 1000f
    private const val FRICTION = 1.9f

    fun corner(stable: Rect, density: Float, releaseX: Float, releaseY: Float,
        velocityX: Float, velocityY: Float): FloatingMiniState.Corner? {
        val speed = hypot(velocityX.toDouble(), velocityY.toDouble())
        if (stable.isEmpty || !releaseX.isFinite() || !releaseY.isFinite() ||
            !velocityX.isFinite() || !velocityY.isFinite() ||
            speed < MIN_VELOCITY_DP * density ||
            abs(velocityX) < speed / 3 || abs(velocityY) < speed / 3) return null
        val config = PhysicsAnimator.FlingConfig(FRICTION, -Float.MAX_VALUE, Float.MAX_VALUE)
        val projectedX = PhysicsAnimator.estimateFlingEndValue(releaseX, velocityX, config)
        val projectedY = PhysicsAnimator.estimateFlingEndValue(releaseY, velocityY, config)
        val left = velocityX < 0f
        val top = velocityY < 0f
        if (if (left) projectedX > stable.left + stable.width() / 4f
            else projectedX < stable.right - stable.width() / 4f) return null
        if (if (top) projectedY > stable.top + stable.height() / 4f
            else projectedY < stable.bottom - stable.height() / 4f) return null
        return when {
            left && top -> FloatingMiniState.Corner.TOP_LEFT
            left -> FloatingMiniState.Corner.BOTTOM_LEFT
            top -> FloatingMiniState.Corner.TOP_RIGHT
            else -> FloatingMiniState.Corner.BOTTOM_RIGHT
        }
    }
}
