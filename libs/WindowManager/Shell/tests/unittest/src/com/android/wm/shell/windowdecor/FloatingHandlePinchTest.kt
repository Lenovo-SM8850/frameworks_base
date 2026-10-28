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
}
