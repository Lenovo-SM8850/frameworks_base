// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.animation.Animator
import android.animation.ValueAnimator
import android.graphics.Point
import android.graphics.Rect
import android.testing.TestableLooper
import android.util.Pair
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.window.TransitionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidJUnit4::class)
class FloatingMiniControllerTest : ShellTestCase() {
    private val active = mutableListOf<Harness>()

    @org.junit.After
    fun clearInteractions() { active.forEach { it.controller.remove(it.task) } }
    private inner class Harness {
        val organizer = mock<ShellTaskOrganizer>()
        val displays = mock<DisplayController>()
        val layout = mock<DisplayLayout>()
        val choreographer = mock<Choreographer>()
        val transaction = mock<SurfaceControl.Transaction>()
        val animator = mock<ValueAnimator>()
        val leash = mock<SurfaceControl>()
        val state = FloatingMiniState()
        val normal = Rect(100, 200, 700, 1100)
        val task = TestRunningTaskInfoBuilder().setTaskId(42).setDisplayId(0)
            .setBounds(Rect(0, 0, 800, 1200)).build().apply {
                isFloatingWindow = true
                floatingWindowScale = .45f
                floatingWindowPosition.set(8, 108)
                positionInParent = Point(8, 108)
            }
        val changes = mutableListOf<android.app.TaskInfo>()
        var allocations = 0
        lateinit var animatorListener: Animator.AnimatorListener
        lateinit var updateListener: ValueAnimator.AnimatorUpdateListener
        val controller: FloatingMiniController

        init {
            active += this
            whenever(organizer.initialTasks).thenReturn(listOf(Pair(task, leash)))
            whenever(organizer.getRunningTasks(0)).thenReturn(arrayListOf(task))
            whenever(displays.getDisplayLayout(0)).thenReturn(layout)
            val displayContext = mock<android.content.Context>()
            val resources = mock<android.content.res.Resources>()
            whenever(displayContext.resources).thenReturn(resources)
            whenever(resources.displayMetrics).thenReturn(android.util.DisplayMetrics().apply { density = 1f })
            whenever(displays.getDisplayContext(0)).thenReturn(displayContext)
            whenever(layout.width()).thenReturn(1200)
            whenever(layout.height()).thenReturn(1920)
            doAnswer { it.getArgument<Rect>(0).set(0, 100, 1200, 1900); null }
                .whenever(layout).getStableBounds(any())
            whenever(leash.isValid).thenReturn(true)
            whenever(transaction.setMatrix(any(), any(), any(), any(), any())).thenReturn(transaction)
            whenever(transaction.setPosition(any(), any(), any())).thenReturn(transaction)
            whenever(transaction.setWindowCrop(any(), any<Int>(), any<Int>())).thenReturn(transaction)
            whenever(transaction.setCornerRadius(any(), any())).thenReturn(transaction)
            whenever(transaction.setFrameTimeline(any())).thenReturn(transaction)
            doAnswer { animatorListener = it.getArgument(0); null }.whenever(animator).addListener(any())
            doAnswer { updateListener = it.getArgument(0); null }.whenever(animator).addUpdateListener(any())
            doAnswer {
                animatorListener.onAnimationCancel(animator)
                animatorListener.onAnimationEnd(animator)
                null
            }.whenever(animator).cancel()
            state.enter(task.userId, task.taskId,
                FloatingMiniState.Restore(normal, Rect(0, 0, 800, 1200), .75f),
                task.presentationBounds, .45f)
            controller = FloatingMiniController(organizer, displays, state, choreographer,
                { allocations++; transaction }, { animator }) { changes += it }
        }

        fun interrupt(mode: Int = TRANSIT_CHANGE): TransitionInfo.Change {
            val change = TransitionInfo.Change(task.token, leash).apply {
                taskInfo = task
                this.mode = mode
                setStartFloatingWindowBounds(task.presentationBounds)
            }
            val info = TransitionInfo(mode, 0).apply { addChange(change) }
            controller.onTransitionReady(info, transaction, transaction)
            return change
        }
    }

    @Test fun restore_usesCurrentMiniAspectWhenItChangedSinceEntry() {
        val h = Harness()
        h.task.configuration.windowConfiguration.setBounds(Rect(0, 0, 1200, 800))
        h.controller.restore(h.task)
        h.animatorListener.onAnimationEnd(h.animator)
        val restored = h.changes.last()
        val logical = restored.configuration.windowConfiguration.bounds
        assertThat(logical.width().toFloat() / logical.height()).isWithin(.002f).of(1.5f)
        assertThat(restored.presentationBounds.width())
            .isGreaterThan(restored.presentationBounds.height())
        assertThat(h.state.get(h.task.userId, h.task.taskId)).isNull()
    }

}
