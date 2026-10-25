// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.graphics.Rect
import android.os.Binder
import android.os.InputConfig
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FloatingTidyGestureMonitorTest : ShellTestCase() {
    private val region = Rect(0, 960, 1000, 1000)
    private val swipe = FloatingTidySwipe(10f, 500f)

    private fun event(action: Int, time: Long, x: Float, y: Float): Boolean =
        MotionEvent.obtain(0, time, action, x, y, 0).let {
            it.source = InputDevice.SOURCE_TOUCHSCREEN
            try { swipe.onMotionEvent(it, region) } finally { it.recycle() }
        }

    private fun fling(startY: Float = 990f): Boolean {
        event(MotionEvent.ACTION_DOWN, 0, 500f, startY)
        event(MotionEvent.ACTION_MOVE, 20, 500f, startY - 40)
        event(MotionEvent.ACTION_MOVE, 40, 500f, startY - 80)
        return event(MotionEvent.ACTION_UP, 60, 500f, startY - 120)
    }

    @Test fun bottomSwipeUpDetectedOnceOnRelease() {
        assertThat(fling()).isTrue()
        assertThat(event(MotionEvent.ACTION_UP, 80, 500f, 840f)).isFalse()
    }

    @Test fun outsideBottomRegionIgnored() {
        assertThat(fling(959f)).isFalse()
        assertThat(fling(1000f)).isFalse()
    }

    @Test fun shortSwipeIgnoredEvenWithHighVelocity() {
        event(MotionEvent.ACTION_DOWN, 0, 500f, 990f)
        event(MotionEvent.ACTION_MOVE, 1, 500f, 986f)
        assertThat(event(MotionEvent.ACTION_UP, 2, 500f, 982f)).isFalse()
    }

    @Test fun slowSwipeIgnored() {
        event(MotionEvent.ACTION_DOWN, 0, 500f, 990f)
        event(MotionEvent.ACTION_MOVE, 500, 500f, 950f)
        event(MotionEvent.ACTION_MOVE, 1000, 500f, 910f)
        assertThat(event(MotionEvent.ACTION_UP, 1500, 500f, 870f)).isFalse()
    }

    @Test fun swipeAndHoldForOverviewIgnored() {
        event(MotionEvent.ACTION_DOWN, 0, 500f, 990f)
        event(MotionEvent.ACTION_MOVE, 20, 500f, 900f)
        event(MotionEvent.ACTION_MOVE, 40, 500f, 810f)
        event(MotionEvent.ACTION_MOVE, 500, 500f, 810f)
        event(MotionEvent.ACTION_MOVE, 520, 500f, 810f)
        assertThat(event(MotionEvent.ACTION_UP, 540, 500f, 810f)).isFalse()
    }

    @Test fun horizontalSwipeAndMultitouchIgnored() {
        event(MotionEvent.ACTION_DOWN, 0, 500f, 990f)
        event(MotionEvent.ACTION_MOVE, 20, 600f, 985f)
        assertThat(event(MotionEvent.ACTION_UP, 40, 700f, 980f)).isFalse()
        event(MotionEvent.ACTION_DOWN, 0, 500f, 990f)
        event(MotionEvent.ACTION_POINTER_DOWN, 10, 500f, 980f)
        assertThat(event(MotionEvent.ACTION_UP, 40, 500f, 800f)).isFalse()
    }

    @Test fun cancelOrUnregisterMidGestureCannotTidy() {
        event(MotionEvent.ACTION_DOWN, 0, 500f, 990f)
        event(MotionEvent.ACTION_MOVE, 20, 500f, 950f)
        event(MotionEvent.ACTION_CANCEL, 30, 500f, 900f)
        assertThat(event(MotionEvent.ACTION_UP, 40, 500f, 850f)).isFalse()
        event(MotionEvent.ACTION_DOWN, 0, 500f, 990f)
        event(MotionEvent.ACTION_MOVE, 20, 500f, 950f)
        val monitor = FloatingTidyGestureMonitor { AutoCloseable { swipe.cancel() } }
        monitor.setEnabled(true)
        monitor.setEnabled(false)
        assertThat(event(MotionEvent.ACTION_UP, 40, 500f, 850f)).isFalse()
    }

    @Test fun spyCannotTakeFocusOrBeCancelledByLauncherPilfer() {
        val handle = FloatingTidyGestureMonitor.spyHandle(Binder())
        assertThat(handle.inputConfig and InputConfig.SPY).isNotEqualTo(0)
        assertThat(handle.inputConfig and InputConfig.NOT_FOCUSABLE).isNotEqualTo(0)
        assertThat(handle.inputConfig and InputConfig.DO_NOT_PILFER).isNotEqualTo(0)
    }

    @Test fun repeatedEnableAndDisableOwnExactlyOneSession() {
        var opened = 0
        var closed = 0
        val monitor = FloatingTidyGestureMonitor {
            opened++
            AutoCloseable { closed++ }
        }
        monitor.setEnabled(false)
        monitor.setEnabled(true)
        monitor.setEnabled(true)
        assertThat(opened).isEqualTo(1)
        monitor.setEnabled(false)
        monitor.setEnabled(false)
        assertThat(closed).isEqualTo(1)
        monitor.setEnabled(true)
        assertThat(opened).isEqualTo(2)
        monitor.setEnabled(false)
        assertThat(closed).isEqualTo(2)
    }
}
