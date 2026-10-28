// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.view.MotionEvent
import kotlin.math.hypot

/** Recognizes only a second finger landing on the opposite handle. */
class FloatingHandlePinch {
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
            if (second.id == firstId || second.handle == 0 || second.handle == firstHandle ||
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
