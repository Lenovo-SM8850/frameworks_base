// SPDX-License-Identifier: Apache-2.0
package com.android.systemui.statusbar.window

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.wm.shell.shared.handles.DynamicIslandTouchRegion
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class StatusBarWindowViewTest : SysuiTestCase() {
    @Test fun overflowPill_routesReportedLandscapeTapsAndDragToComposeOwner() {
        val root = StatusBarWindowView(context, null)
        val host = mock<ViewGroup>()
        val owner = mock<View>()
        val received = mutableListOf<Pair<Float, Float>>()
        whenever(host.isShown).thenReturn(true)
        whenever(host.getChildAt(0)).thenReturn(owner)
        doAnswer { call ->
            val event = call.getArgument<MotionEvent>(0)
            received += event.x to event.y
            true
        }.whenever(owner).dispatchTouchEvent(any())
        root.setIslandTouchTarget(host)
        DynamicIslandTouchRegion.update(context.displayId, Rect(1055, 0, 1505, 110))
        try {
            for (x in listOf(1270f, 1100f)) {
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(0, 0, action, x, 55f, 0)
                    assertThat(root.dispatchTouchEvent(event)).isTrue()
                    event.recycle()
                }
            }
            for ((action, point) in listOf(
                MotionEvent.ACTION_DOWN to (1270f to 55f),
                MotionEvent.ACTION_MOVE to (900f to 150f),
                MotionEvent.ACTION_UP to (900f to 150f),
            )) {
                val event = MotionEvent.obtain(0, 0, action, point.first, point.second, 0)
                assertThat(root.dispatchTouchEvent(event)).isTrue()
                event.recycle()
            }
            assertThat(received).containsExactly(1270f to 55f, 1270f to 55f,
                1100f to 55f, 1100f to 55f, 1270f to 55f, 900f to 150f,
                900f to 150f).inOrder()
        } finally {
            root.clearIslandTouchTarget(host)
            DynamicIslandTouchRegion.update(context.displayId, Rect())
        }
    }
}
