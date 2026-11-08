// SPDX-License-Identifier: Apache-2.0
package com.android.systemui.statusbar.window

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.wm.shell.shared.handles.DynamicIslandTouchRegion
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class StatusBarWindowViewIslandTouchTest : SysuiTestCase() {
    private val barHeight = 40
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    /** The window, the bar with the island host and its owner, and a view behind the bar. */
    private inner class Window(ownerTakesDown: Boolean = true) {
        val ownerEvents = mutableListOf<Pair<Float, Float>>()
        val barIntercepts = mutableListOf<Int>()
        val behindEvents = mutableListOf<Pair<Float, Float>>()
        val root = StatusBarWindowView(context, null)
        val host = object : FrameLayout(context) {
            override fun isShown() = true
        }

        init {
            val behind = object : View(context) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    behindEvents += event.x to event.y
                    return true
                }
            }
            val bar = object : FrameLayout(context) {
                override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                    barIntercepts += event.actionMasked
                    return false
                }
            }
            val owner = object : View(context) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    ownerEvents += event.x to event.y
                    return ownerTakesDown || event.actionMasked != MotionEvent.ACTION_DOWN
                }
            }
            host.addView(owner, FrameLayout.LayoutParams(MATCH, MATCH))
            bar.addView(host, FrameLayout.LayoutParams(MATCH, MATCH))
            root.addView(behind, FrameLayout.LayoutParams(2000, 400))
            root.addView(bar, FrameLayout.LayoutParams(2000, barHeight))
            root.measure(
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 2000, 400)
            root.setIslandTouchTarget(host)
            DynamicIslandTouchRegion.update(context.displayId, Rect(1055, 0, 1505, 110))
        }

        fun send(action: Int, x: Float, y: Float): Boolean {
            val event = MotionEvent.obtain(0, 0, action, x, y, 0)
            try {
                return root.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }

        fun close() {
            root.clearIslandTouchTarget(host)
            DynamicIslandTouchRegion.update(context.displayId, Rect())
        }
    }

    @Test fun overflowPill_routesReportedLandscapeTapsAndDragToComposeOwner() {
        val window = Window()
        try {
            for (x in listOf(1270f, 1100f)) {
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    assertThat(window.send(action, x, 55f)).isTrue()
                }
            }
            for ((action, point) in listOf(
                MotionEvent.ACTION_DOWN to (1270f to 55f),
                MotionEvent.ACTION_MOVE to (900f to 150f),
                MotionEvent.ACTION_UP to (900f to 150f),
            )) {
                assertThat(window.send(action, point.first, point.second)).isTrue()
            }
            assertThat(window.ownerEvents).containsExactly(1270f to 55f, 1270f to 55f,
                1100f to 55f, 1100f to 55f, 1270f to 55f, 900f to 150f,
                900f to 150f).inOrder()
            assertThat(window.barIntercepts).isEmpty()
        } finally {
            window.close()
        }
    }

    @Test fun overflowPill_ownerThatDeclinesDown_leavesTheGestureToTheNormalDispatch() {
        val window = Window(ownerTakesDown = false)
        try {
            assertThat(window.send(MotionEvent.ACTION_DOWN, 1270f, 55f)).isTrue()
            assertThat(window.send(MotionEvent.ACTION_UP, 1270f, 55f)).isTrue()
            assertThat(window.ownerEvents).containsExactly(1270f to 55f)
            assertThat(window.behindEvents).containsExactly(1270f to 55f, 1270f to 55f).inOrder()
        } finally {
            window.close()
        }
    }

    @Test fun pillInsideTheBar_isNotRoutedDirectly() {
        val window = Window()
        try {
            window.send(MotionEvent.ACTION_DOWN, 1270f, 20f)
            window.send(MotionEvent.ACTION_UP, 1270f, 20f)
            assertThat(window.barIntercepts).contains(MotionEvent.ACTION_DOWN)
            assertThat(window.ownerEvents).containsExactly(1270f to 20f, 1270f to 20f)
            assertThat(window.behindEvents).isEmpty()
        } finally {
            window.close()
        }
    }
}
