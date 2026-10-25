// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.desktopmode.animation

import android.animation.AnimatorTestRule
import android.graphics.Rect
import android.testing.TestableLooper
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.window.TransitionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.windowdecor.OnTaskResizeAnimationListener
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidJUnit4::class)
class EnterDesktopTaskAnimatorTest : ShellTestCase() {
    @JvmField @Rule val animatorRule = AnimatorTestRule(this)

    @Test fun floatingHandoff_veilCropRemainsLogicalThroughFirstAndLastFrame() {
        val leash = mock<SurfaceControl>()
        val t = mock<SurfaceControl.Transaction>()
        whenever(t.setPosition(any(), any(), any())).thenReturn(t)
        whenever(t.setWindowCrop(any(), any<Int>(), any<Int>())).thenReturn(t)
        whenever(t.setMatrix(any(), any(), any(), any(), any())).thenReturn(t)
        var crop = 0
        var scale = 1f
        whenever(t.setWindowCrop(any(), any<Int>(), any<Int>())).thenAnswer {
            crop = it.getArgument(1); t
        }
        whenever(t.setMatrix(any(), any(), any(), any(), any())).thenAnswer {
            scale = it.getArgument(1); t
        }
        val visibleWidths = mutableListOf<Float>()
        val listener = object : OnTaskResizeAnimationListener {
            fun relayout(bounds: Rect): Boolean {
                t.setWindowCrop(leash, bounds.width(), bounds.height())
                visibleWidths += crop * scale
                t.apply()
                return true
            }
            override fun onAnimationStart(taskId: Int, t: SurfaceControl.Transaction, bounds: Rect) =
                relayout(bounds)
            override fun onBoundsChange(taskId: Int, t: SurfaceControl.Transaction, bounds: Rect) =
                relayout(bounds)
            override fun onAnimationEnd(taskId: Int) {}
        }
        val task = TestRunningTaskInfoBuilder().setTaskId(42).build()
        val change = TransitionInfo.Change(task.token, leash).apply {
            taskInfo = task
            mode = TRANSIT_CHANGE
            setStartAbsBounds(Rect(0, 0, 800, 1200))
            setStartFloatingWindowBounds(Rect(100, 200, 700, 1100))
            setEndAbsBounds(Rect(0, 0, 800, 1200))
        }
        EnterDesktopTaskAnimator(context, { t }, listener).animate(change, t, t, mock())
        animatorRule.advanceTimeBy(300)
        assertThat(visibleWidths.first()).isEqualTo(600f)
        assertThat(visibleWidths.last()).isEqualTo(800f)
        assertThat(crop * scale).isEqualTo(800f)
    }
}
