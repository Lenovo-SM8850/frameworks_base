// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.graphics.Rect
import android.os.SystemClock
import android.testing.TestableLooper
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerController
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidJUnit4::class)
class FloatingMiniInputTest : ShellTestCase() {
    private val controller = mock<PinnedLayerController>()
    private val task = TestRunningTaskInfoBuilder().build()
    private val input = FloatingMiniInput({ mContext }, controller)
    private fun view() = input.createInputView(mContext, task).also {
        whenever(controller.beginMiniDrag(task)).thenReturn(Rect(100, 100, 460, 640))
    }
    private fun tap(view: android.view.View, time: Long, x: Float = 20f) {
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            MotionEvent.obtain(time, time + index * 20, action, x, 20f, 0).also {
                view.dispatchTouchEvent(it)
                it.recycle()
            }
        }
    }

    @Test fun singleTap_doesNotRestoreButDoubleTapDoes() {
        val view = view()
        val time = SystemClock.uptimeMillis()
        tap(view, time)
        verify(controller, never()).restoreMini(task)
        tap(view, time + ViewConfiguration.getDoubleTapMinTime() + 25)
        verify(controller).restoreMini(task)
        verify(controller, times(2)).endMiniTap(task)
    }

    @Test fun outsideDoubleTapTimeoutOrSlop_doesNotRestore() {
        val view = view()
        val time = SystemClock.uptimeMillis()
        tap(view, time)
        tap(view, time + ViewConfiguration.getDoubleTapTimeout() + 30)
        tap(view, time + ViewConfiguration.getDoubleTapTimeout() + 100,
            20f + ViewConfiguration.get(mContext).scaledDoubleTapSlop + 10)
        verify(controller, never()).restoreMini(task)
    }

    @Test fun stashedSingleTap_restoresOnlyThroughUnstashAction() {
        val view = view()
        whenever(controller.isMiniStashed(task)).thenReturn(true)
        tap(view, SystemClock.uptimeMillis())
        verify(controller).restoreMini(task)
    }
    @Test fun inputHost_neverBecomesFocusableOnResize() {
        val view = view()
        org.junit.Assert.assertFalse(view.isFocusable)
        val host = mock<android.view.SurfaceControlViewHost>()
        input.relayoutInputHost(host, 360, 540)
        input.relayoutInputHost(host, 540, 360)
        val params = org.mockito.kotlin.argumentCaptor<android.view.WindowManager.LayoutParams>()
        verify(host, times(2)).relayout(params.capture())
        params.allValues.forEach {
            org.junit.Assert.assertTrue(it.flags and
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            org.junit.Assert.assertTrue(it.flags and
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED != 0)
        }
    }

    @Test fun closeDuringInput_releasesDragOnceAndIgnoresStaleRelease() {
        val view = view()
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 20f, 20f, 0).also {
            view.dispatchTouchEvent(it)
            it.recycle()
        }
        input.close()
        input.close()
        MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_UP, 20f, 20f, 0).also {
            view.dispatchTouchEvent(it)
            it.recycle()
        }
        verify(controller).endMiniTap(task)
        verify(controller, never()).restoreMini(task)
    }

    @Test fun miniTouch_pilfersBeforeMotionAndExcludesItsWholeInputArea() {
        var pilfered = 0
        val input = FloatingMiniInput({ mContext }, controller, { pilfered++ })
        val view = input.createInputView(mContext, task)
        view.layout(0, 0, 360, 540)
        org.junit.Assert.assertEquals(listOf(Rect(0, 0, 360, 540)), view.systemGestureExclusionRects)
        whenever(controller.beginMiniDrag(task)).thenReturn(Rect(0, 100, 360, 640))
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 1f, 20f, 0).also {
            view.dispatchTouchEvent(it)
            it.recycle()
        }
        org.junit.Assert.assertEquals(1, pilfered)
        input.close()
    }

    @Test fun dragMovesMiniWithoutRestoring() {
        val view = view()
        val time = SystemClock.uptimeMillis()
        val end = 20f + ViewConfiguration.get(mContext).scaledTouchSlop + 30
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)
            .forEachIndexed { index, action ->
                val x = if (index == 0) 20f else end
                MotionEvent.obtain(time, time + index * 20, action, x, 20f, 0).also {
                    view.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
        verify(controller).moveMini(org.mockito.kotlin.eq(task), org.mockito.kotlin.any())
        verify(controller).endMiniDrag(org.mockito.kotlin.eq(task),
            org.mockito.kotlin.any(), org.mockito.kotlin.any())
        verify(controller, never()).restoreMini(task)
    }


    @Test fun stashFeedback_rearmsAndResetsOnTransitionCancelAndClose() {
        val view = org.mockito.kotlin.spy(view())
        whenever(view.isAttachedToWindow).thenReturn(true)
        org.mockito.kotlin.doReturn(true).whenever(view).performHapticFeedback(
            android.view.HapticFeedbackConstants.SEGMENT_TICK)
        val edge = com.android.wm.shell.pinnedlayer.phone.FloatingMiniState.Edge.LEFT
        val none = com.android.wm.shell.pinnedlayer.phone.FloatingMiniState.Edge.NONE
        whenever(controller.getMiniStashTarget(org.mockito.kotlin.eq(task), org.mockito.kotlin.any()))
            .thenReturn(none, edge, edge, none, edge, edge, none, edge, none, edge)
        val time = SystemClock.uptimeMillis()
        var tick = 0L
        fun event(action: Int, x: Float, source: Int = android.view.InputDevice.SOURCE_TOUCHSCREEN) {
            val pointer = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = MotionEvent.PointerCoords().apply { this.x = x; y = 20f }
            MotionEvent.obtain(time, time + tick++, action, 1, arrayOf(pointer), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, source, 0).also {
                view.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN, 20f)
        repeat(4) { event(MotionEvent.ACTION_MOVE, -400f - it) }
        verify(view, times(2)).performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK)
        input.cancelTargetFeedback()
        event(MotionEvent.ACTION_MOVE, -410f)
        verify(view, times(2)).performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK)
        event(MotionEvent.ACTION_CANCEL, -410f)
        event(MotionEvent.ACTION_DOWN, 20f)
        event(MotionEvent.ACTION_MOVE, -420f)
        verify(view, times(3)).performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK)
        event(MotionEvent.ACTION_CANCEL, -420f)
        event(MotionEvent.ACTION_DOWN, 20f, android.view.InputDevice.SOURCE_MOUSE)
        event(MotionEvent.ACTION_MOVE, -420f, android.view.InputDevice.SOURCE_MOUSE)
        verify(view, times(3)).performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK)
        input.close()
        event(MotionEvent.ACTION_MOVE, -450f)
        verify(view, times(3)).performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK)
    }

    @Test fun alreadyStashedFeedback_isSilentUntilTheDragLeavesAndReenters() {
        val view = org.mockito.kotlin.spy(view())
        whenever(view.isAttachedToWindow).thenReturn(true)
        org.mockito.kotlin.doReturn(true).whenever(view).performHapticFeedback(
            android.view.HapticFeedbackConstants.SEGMENT_TICK)
        val edge = com.android.wm.shell.pinnedlayer.phone.FloatingMiniState.Edge.LEFT
        val none = com.android.wm.shell.pinnedlayer.phone.FloatingMiniState.Edge.NONE
        whenever(controller.getMiniStashTarget(org.mockito.kotlin.eq(task), org.mockito.kotlin.any()))
            .thenReturn(edge, edge, none, edge)
        val time = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float) {
            val pointer = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = MotionEvent.PointerCoords().apply { this.x = x; y = 20f }
            MotionEvent.obtain(time, time + 20, action, 1, arrayOf(pointer), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).also {
                view.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN, 20f)
        event(MotionEvent.ACTION_MOVE, -400f)
        verify(view, never()).performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK)
        event(MotionEvent.ACTION_MOVE, -300f)
        event(MotionEvent.ACTION_MOVE, -400f)
        verify(view).performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK)
        input.close()
    }
}
