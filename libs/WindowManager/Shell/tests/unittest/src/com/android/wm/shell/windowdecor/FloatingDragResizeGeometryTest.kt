// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.graphics.Point
import android.util.Size
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.windowdecor.DragPositioningCallback.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FloatingDragResizeGeometryTest {
    private fun geometry(floating: Boolean) = DragResizeWindowGeometry(12,
        Size(400, 800), 16, 8, 24, 48, DragResizeWindowGeometry.DisabledEdge.NONE, floating)

    @Test fun floatingWindow_allowsTouchOnSidesAndBottom() {
        val geometry = geometry(true)
        for ((x, y, ctrl) in listOf(Triple(1f, 400f, CTRL_TYPE_LEFT),
            Triple(399f, 400f, CTRL_TYPE_RIGHT), Triple(200f, 799f, CTRL_TYPE_BOTTOM))) {
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertThat(geometry.shouldHandleEvent(event, Point())).isTrue()
                assertThat(geometry.calculateCtrlType(true, false, x, y)).isEqualTo(ctrl)
            } finally { event.recycle() }
        }
    }

    @Test fun desktopWindow_keepsTouchEdgesDisabled() {
        val geometry = geometry(false)
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 399f, 400f, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertThat(geometry.shouldHandleEvent(event, Point())).isFalse()
            assertThat(geometry.calculateCtrlType(true, false, 399f, 400f))
                .isEqualTo(CTRL_TYPE_UNDEFINED)
            assertThat(geometry.calculateCtrlType(false, true, 399f, 400f))
                .isEqualTo(CTRL_TYPE_RIGHT)
        } finally { event.recycle() }
    }

    @Test fun geometryEquality_includesFloatingTouchPolicy() {
        assertThat(geometry(true)).isNotEqualTo(geometry(false))
        assertThat(geometry(false)).isEqualTo(geometry(false))
    }
}
