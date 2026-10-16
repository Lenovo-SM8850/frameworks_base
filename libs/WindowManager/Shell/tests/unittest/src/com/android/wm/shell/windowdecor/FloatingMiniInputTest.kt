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

}
