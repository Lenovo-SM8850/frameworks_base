// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.view.MotionEvent.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.windowdecor.FloatingHandlePinch.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FloatingHandlePinchTest {
    private val pinch = FloatingHandlePinch()
    private val top = Pointer(7, 100f, 100f, 1)
    private val bottom = Pointer(12, 100f, 900f, 2)

    private fun start(first: Pointer = top, second: Pointer = bottom): Change? {
        assertThat(pinch.onEvent(ACTION_DOWN, first.id, listOf(first))).isNull()
        return pinch.onEvent(ACTION_POINTER_DOWN, second.id, listOf(first, second))
    }

    @Test fun oppositeHandles_startInEitherOrder() {
        assertThat(start()).isEqualTo(Change(Kind.START, 800f, 100f, 500f))
        assertThat(start(bottom, top)).isEqualTo(Change(Kind.START, 800f, 100f, 500f))
    }

    @Test fun sameHandleOrContent_doesNotStart() {
        assertThat(start(second = bottom.copy(handle = 1))).isNull()
        assertThat(start(second = bottom.copy(handle = 0))).isNull()
        assertThat(start(first = top.copy(handle = 0))).isNull()
    }

    @Test fun firstMustStillBeOnItsHandle() {
        pinch.onEvent(ACTION_DOWN, top.id, listOf(top))
        assertThat(pinch.onEvent(ACTION_POINTER_DOWN, bottom.id,
            listOf(top.copy(handle = 0), bottom))).isNull()
    }

    @Test fun pointerIdsSurviveIndexReordering() {
        start()
        val moved = pinch.onEvent(ACTION_MOVE, bottom.id,
            listOf(bottom.copy(y = 1100f), top.copy(y = 50f)))
        assertThat(moved).isEqualTo(Change(Kind.MOVE, 1050f, 100f, 575f))
    }

    @Test fun eitherFingerUp_commitsAndDrainsWithoutDragOrClick() {
        for (pointer in listOf(top, bottom)) {
            start()
            assertThat(pinch.onEvent(ACTION_POINTER_UP, pointer.id, listOf(top, bottom))?.kind)
                .isEqualTo(Kind.FINISH)
            val remaining = if (pointer.id == top.id) bottom else top
            assertThat(pinch.onEvent(ACTION_MOVE, remaining.id, listOf(remaining))?.kind)
                .isEqualTo(Kind.CONSUME)
            assertThat(pinch.onEvent(ACTION_UP, remaining.id, listOf(remaining))?.kind)
                .isEqualTo(Kind.CONSUME)
            assertThat(pinch.active).isFalse()
        }
        assertThat(start()?.kind).isEqualTo(Kind.START)
    }

    @Test fun extraFingerDoesNotReplaceTrackedPointersOrFinishPinch() {
        start()
        val extra = Pointer(20, 100f, 500f, 0)
        assertThat(pinch.onEvent(ACTION_POINTER_DOWN, extra.id, listOf(top, bottom, extra))?.kind)
            .isEqualTo(Kind.CONSUME)
        assertThat(pinch.onEvent(ACTION_POINTER_UP, extra.id, listOf(top, bottom, extra))?.kind)
            .isEqualTo(Kind.CONSUME)
        assertThat(pinch.active).isTrue()
    }

    @Test fun tilt_usesPipDampingAndClampsWithStablePointerIds() {
        start()
        val right = top.copy(x = 500f, y = 500f)
        val left = bottom.copy(x = -300f, y = 500f)
        val rotated = pinch.onEvent(ACTION_MOVE, bottom.id, listOf(left, right))!!
        assertThat(rotated.angle).isEqualTo(13f)
        assertThat(rotated.span).isEqualTo(800f)
        assertThat(pinch.onEvent(ACTION_POINTER_UP, top.id, listOf(right, left))!!.angle)
            .isEqualTo(13f)
        assertThat(start()!!.angle).isEqualTo(0f)
        val reversed = pinch.onEvent(ACTION_MOVE, bottom.id,
            listOf(top.copy(x = -300f, y = 500f), bottom.copy(x = 500f, y = 500f)))!!
        assertThat(reversed.angle).isEqualTo(-13f)
        start()
        assertThat(pinch.onEvent(ACTION_MOVE, bottom.id,
            listOf(top.copy(x = 101f), bottom))!!.angle).isEqualTo(0f)
    }

    @Test fun rotationTransform_preservesCenterAndExactUprightMatrix() {
        val t = org.mockito.kotlin.mock<android.view.SurfaceControl.Transaction>()
        val leash = org.mockito.kotlin.mock<android.view.SurfaceControl>()
        org.mockito.kotlin.whenever(t.setMatrix(org.mockito.kotlin.any(),
            org.mockito.kotlin.any<Float>(), org.mockito.kotlin.any(), org.mockito.kotlin.any(),
            org.mockito.kotlin.any())).thenReturn(t)
        val bounds = android.graphics.Rect(100, 200, 700, 1100)
        val c = kotlin.math.cos(Math.toRadians(13.0)).toFloat()
        val s = kotlin.math.sin(Math.toRadians(13.0)).toFloat()
        FloatingPinchRotation.apply(t, leash, bounds, .75f, .75f, 13f)
        org.mockito.kotlin.verify(t).setMatrix(leash, .75f * c, .75f * s, -.75f * s, .75f * c)
        org.mockito.kotlin.verify(t).setPosition(leash,
            400f - c * 300f + s * 450f, 650f - s * 300f - c * 450f)
        FloatingPinchRotation.apply(t, leash, bounds, .75f, .75f, 0f)
        org.mockito.kotlin.verify(t).setMatrix(leash, .75f, 0f, 0f, .75f)
        org.mockito.kotlin.verify(t).setPosition(leash, 100f, 200f)
    }

    @Test fun cancelResetsAndAllowsFreshGesture() {
        start()
        assertThat(pinch.onEvent(ACTION_CANCEL, top.id, listOf(top, bottom))?.kind)
            .isEqualTo(Kind.CANCEL)
        assertThat(pinch.active).isFalse()
        assertThat(start()?.kind).isEqualTo(Kind.START)
    }

    @Test fun missingPointerCancelsInsteadOfJumpingToAnotherId() {
        start()
        assertThat(pinch.onEvent(ACTION_MOVE, bottom.id, listOf(bottom))?.kind)
            .isEqualTo(Kind.CANCEL)
        assertThat(pinch.onEvent(ACTION_MOVE, bottom.id, listOf(bottom))?.kind)
            .isEqualTo(Kind.CONSUME)
    }

    @Test fun singleFingerEventsRemainWithDragHandler() {
        assertThat(pinch.onEvent(ACTION_DOWN, top.id, listOf(top))).isNull()
        assertThat(pinch.onEvent(ACTION_MOVE, top.id, listOf(top.copy(y = 200f)))).isNull()
        assertThat(pinch.onEvent(ACTION_UP, top.id, listOf(top))).isNull()
    }
    @Suppress("UNCHECKED_CAST")
    private fun resetEndAction(rotation: FloatingPinchRotation, finish: () -> Unit): () -> Unit {
        val animator = com.android.wm.shell.shared.animation.PhysicsAnimator.getInstance(rotation)
        val start: () -> Unit = {}
        animator.javaClass.getDeclaredField("startAction").apply { isAccessible = true }
            .set(animator, start)
        rotation.reset(finish)
        return (animator.javaClass.getDeclaredField("endActions").apply { isAccessible = true }
            .get(animator) as List<() -> Unit>).last()
    }

    @Test fun rotationReset_cancelDropsLateEndAction() {
        var updates = 0
        var finishes = 0
        val rotation = FloatingPinchRotation { updates++ }
        rotation.update(13f)
        val end = resetEndAction(rotation) { finishes++ }
        rotation.cancel()
        rotation.update(-13f)
        end()
        assertThat(rotation.angle).isEqualTo(-13f)
        assertThat(updates).isEqualTo(2)
        assertThat(finishes).isEqualTo(0)
        rotation.cancel()
    }

    @Test fun rotationReset_ownerChangeDuringFinalUpdateDropsFinish() {
        var finishes = 0
        var cancelOnUpdate = false
        lateinit var rotation: FloatingPinchRotation
        rotation = FloatingPinchRotation { if (cancelOnUpdate) rotation.cancel() }
        rotation.update(13f)
        val end = resetEndAction(rotation) { finishes++ }
        cancelOnUpdate = true
        end()
        assertThat(rotation.angle).isEqualTo(0f)
        assertThat(finishes).isEqualTo(0)
    }

    @Test fun rotationReset_settlesUprightAndFinishesOnlyOnce() {
        val angles = mutableListOf<Float>()
        var finishes = 0
        lateinit var rotation: FloatingPinchRotation
        rotation = FloatingPinchRotation { angles += rotation.angle }
        rotation.update(13f)
        val end = resetEndAction(rotation) { finishes++ }
        end()
        end()
        assertThat(angles).containsExactly(13f, 0f).inOrder()
        assertThat(finishes).isEqualTo(1)
        rotation.cancel()
    }

}
