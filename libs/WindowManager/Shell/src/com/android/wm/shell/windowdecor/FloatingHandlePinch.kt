// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.graphics.Rect
import android.view.MotionEvent
import android.view.SurfaceControl
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringForce
import com.android.wm.shell.shared.animation.PhysicsAnimator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Tracks two fingers and drains the stream after either tracked pointer lifts. */
class FloatingHandlePinch(private val oppositeHandlesOnly: Boolean = true) {
    data class Pointer(val id: Int, val x: Float, val y: Float, val handle: Int)
    enum class Kind { START, MOVE, FINISH, CANCEL, CONSUME }
    data class Change(val kind: Kind, val span: Float = 0f, val x: Float = 0f, val y: Float = 0f)

    private var firstId = -1
    private var secondId = -1
    private var firstHandle = 0
    private var draining = false
    val active: Boolean get() = secondId != -1

    fun cancel(): Boolean {
        val wasActive = active
        firstId = -1
        secondId = -1
        firstHandle = 0
        draining = false
        return wasActive
    }

    fun onEvent(action: Int, actionId: Int, pointers: List<Pointer>): Change? {
        if (action == MotionEvent.ACTION_DOWN) {
            cancel()
            pointers.singleOrNull()?.takeIf { it.handle != 0 }?.let {
                firstId = it.id
                firstHandle = it.handle
            }
            return null
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            val consumed = draining
            return if (cancel()) Change(Kind.CANCEL)
                else if (consumed) Change(Kind.CONSUME) else null
        }
        if (draining && !active) {
            if (action == MotionEvent.ACTION_UP) cancel()
            return Change(Kind.CONSUME)
        }
        val first = pointers.firstOrNull { it.id == firstId }
        if (!active) {
            if (action == MotionEvent.ACTION_UP ||
                action == MotionEvent.ACTION_POINTER_UP && actionId == firstId) {
                cancel()
                return null
            }
            if (action != MotionEvent.ACTION_POINTER_DOWN || pointers.size != 2 ||
                first == null || first.handle != firstHandle) return null
            val second = pointers.firstOrNull { it.id == actionId } ?: return null
            if (second.id == firstId || second.handle == 0 || oppositeHandlesOnly && second.handle == firstHandle ||
                hypot(second.x - first.x, second.y - first.y) <= 0f) return null
            secondId = second.id
            draining = true
            return sample(Kind.START, first, second)
        }
        val second = pointers.firstOrNull { it.id == secondId }
        if (first == null || second == null) {
            secondId = -1
            return Change(Kind.CANCEL)
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP &&
            (actionId == firstId || actionId == secondId)) {
            secondId = -1
            return sample(Kind.FINISH, first, second)
        }
        return if (action == MotionEvent.ACTION_MOVE) sample(Kind.MOVE, first, second)
            else Change(Kind.CONSUME)
    }

    private fun sample(kind: Kind, first: Pointer, second: Pointer) = Change(kind,
        hypot(second.x - first.x, second.y - first.y),
        (first.x + second.x) / 2f, (first.y + second.y) / 2f)
}

class FloatingPinchRotation(private val onUpdate: () -> Unit) {
    // Hundredths of a degree keep the spring's threshold below a visible tilt.
    private var units = 0f
    val angle: Float get() = units / 100f
    private val animator = PhysicsAnimator.getInstance(this)

    fun update(angle: Float) {
        animator.cancel()
        units = angle * 100f
        onUpdate()
    }

    fun reset(finish: () -> Unit) {
        animator.cancel()
        if (units == 0f) { finish(); return }
        animator.spring(ROTATION, 0f,
            PhysicsAnimator.SpringConfig(700f, SpringForce.DAMPING_RATIO_NO_BOUNCY))
            .addUpdateListener { _, _ -> onUpdate() }
            .withEndActions(Runnable { units = 0f; onUpdate(); finish() })
            .start()
    }

    fun cancel() {
        animator.cancel()
        units = 0f
    }

    companion object {
        private val ROTATION = object : FloatPropertyCompat<FloatingPinchRotation>("pinchRotation") {
            override fun getValue(target: FloatingPinchRotation) = target.units
            override fun setValue(target: FloatingPinchRotation, value: Float) { target.units = value }
        }

        fun apply(t: SurfaceControl.Transaction, leash: SurfaceControl, bounds: Rect,
            sx: Float, sy: Float, angle: Float) {
            if (angle == 0f) {
                t.setMatrix(leash, sx, 0f, 0f, sy)
                    .setPosition(leash, bounds.left.toFloat(), bounds.top.toFloat())
                return
            }
            val radians = Math.toRadians(angle.toDouble())
            val c = cos(radians).toFloat()
            val s = sin(radians).toFloat()
            val halfWidth = bounds.width() / 2f
            val halfHeight = bounds.height() / 2f
            t.setMatrix(leash, sx * c, sx * s, -sy * s, sy * c)
                .setPosition(leash, bounds.left + halfWidth - c * halfWidth + s * halfHeight,
                    bounds.top + halfHeight - s * halfWidth - c * halfHeight)
        }
    }
}
