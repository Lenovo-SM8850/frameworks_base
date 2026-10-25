// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.content.ContentResolver
import android.content.Context
import android.os.PowerManager
import android.util.Pair
import android.view.Surface
import android.view.SurfaceControl
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.common.ShellExecutor
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class FloatingFrameRateControllerTest : ShellTestCase() {
    private class Harness(mini: Boolean = false, supported: Boolean = true) {
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        val organizer = mock<ShellTaskOrganizer>()
        val executor = mock<ShellExecutor>()
        val transaction = mock<SurfaceControl.Transaction>()
        val leash = mock<SurfaceControl>()
        val state = FloatingMiniState()
        val task = TestRunningTaskInfoBuilder().setTaskId(42).setDisplayId(0).build().apply {
            isFloatingWindow = true
            isFocused = true
        }
        val tasks = mutableMapOf(task.taskId to task)
        val controller: FloatingFrameRateController
        init {
            whenever(context.contentResolver).thenReturn(resolver)
            whenever(leash.isValid).thenReturn(true)
            whenever(transaction.setFrameRate(any(), any(), any())).thenReturn(transaction)
            whenever(organizer.initialTasks).thenReturn(listOf(Pair(task, leash)))
            whenever(organizer.getRunningTaskInfo(any())).thenAnswer { tasks[it.getArgument<Int>(0)] }
            doAnswer { (it.getArgument<Runnable>(0)).run(); null }
                .whenever(executor).execute(any())
            if (mini) state.enter(task.userId, task.taskId,
                FloatingMiniState.Restore(android.graphics.Rect(100, 100, 700, 1000),
                    android.graphics.Rect(0, 0, 800, 1200), .75f),
                android.graphics.Rect(8, 8, 368, 548), .45f)
            controller = FloatingFrameRateController(context, organizer, executor, state,
                supportsOverride = { supported }) { transaction }
        }
    }

    @Test fun exitAndVanish_clearOnlyOwnedVotes() {
        val h = Harness(mini = true)
        verify(h.transaction).setFrameRate(h.leash, 60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        val listener = argumentCaptor<ShellTaskOrganizer.TaskInfoChangedListener>()
        verify(h.organizer).addTaskInfoChangedListener(listener.capture())
        clearInvocations(h.transaction)
        val exited = android.app.ActivityManager.RunningTaskInfo(h.task).apply {
            isFloatingWindow = false
        }
        listener.firstValue.onTaskInfoChanged(exited)
        verify(h.transaction).setFrameRate(h.leash, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        verify(h.transaction).setFrameRateSelectionStrategy(h.leash,
            SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_PROPAGATE)
        clearInvocations(h.transaction)
        listener.firstValue.onTaskInfoChanged(exited)
        verify(h.transaction, never()).setFrameRate(any(), any(), any())

        val vanished = Harness(mini = true)
        val vanish = argumentCaptor<ShellTaskOrganizer.TaskVanishedListener>()
        verify(vanished.organizer).addTaskVanishedListener(vanish.capture())
        clearInvocations(vanished.transaction)
        vanish.firstValue.onTaskVanished(vanished.task)
        verify(vanished.transaction).setFrameRate(vanished.leash, 0f,
            Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }

    @Test fun displayChange_clearsAndRecalculatesVote() {
        val h = Harness(mini = true)
        clearInvocations(h.transaction)
        h.controller.clearDisplay(0)
        verify(h.transaction).setFrameRate(h.leash, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        clearInvocations(h.transaction)
        h.controller.update()
        verify(h.transaction).setFrameRate(h.leash, 60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }

    @Test fun focusAndWindowCountChanges_recalculatePreferences() {
        val h = Harness()
        val focus = argumentCaptor<ShellTaskOrganizer.FocusListener>()
        verify(h.organizer).addFocusListener(focus.capture())
        clearInvocations(h.transaction)
        focus.firstValue.onFocusTaskChanged(TestRunningTaskInfoBuilder()
            .setTaskId(99).setDisplayId(0).build())
        verify(h.transaction).setFrameRate(h.leash, 60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        focus.firstValue.onFocusTaskChanged(h.task)
        val second = TestRunningTaskInfoBuilder().setTaskId(43).setDisplayId(0).build().apply {
            isFloatingWindow = true
        }
        h.tasks[43] = second
        val secondLeash = mock<SurfaceControl>()
        whenever(secondLeash.isValid).thenReturn(true)
        val appeared = argumentCaptor<ShellTaskOrganizer.TaskAppearedListener>()
        verify(h.organizer).addTaskAppearedListener(appeared.capture())
        clearInvocations(h.transaction)
        appeared.firstValue.onTaskAppeared(second, secondLeash)
        verify(h.transaction).setFrameRate(h.leash, 60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        verify(h.transaction).setFrameRate(secondLeash, 60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }

    @Test fun vote_lowestApplicablePreferenceWins() {
        fun vote(focused: Boolean = true, count: Int = 1, mini: Boolean = false,
            stashed: Boolean = false, thermal: Int = PowerManager.THERMAL_STATUS_NONE,
            floating: Boolean = true, fullscreenGame: Boolean = false) = FloatingFrameRateController.vote(
                floating, focused, count, mini, stashed, thermal, fullscreenGame)
        assertThat(vote()).isEqualTo(0f)
        assertThat(vote(fullscreenGame = true)).isEqualTo(60f)
        assertThat(vote(fullscreenGame = true, mini = true)).isEqualTo(60f)
        assertThat(vote(fullscreenGame = true, stashed = true)).isEqualTo(30f)
        assertThat(vote(fullscreenGame = true,
            thermal = PowerManager.THERMAL_STATUS_MODERATE)).isEqualTo(30f)
        assertThat(vote(fullscreenGame = true, floating = false)).isEqualTo(0f)
        assertThat(vote(mini = true)).isEqualTo(60f)
        assertThat(vote(focused = false)).isEqualTo(60f)
        assertThat(vote(count = 2)).isEqualTo(60f)
        assertThat(vote(stashed = true)).isEqualTo(30f)
        assertThat(vote(thermal = PowerManager.THERMAL_STATUS_LIGHT)).isEqualTo(0f)
        assertThat(vote(thermal = PowerManager.THERMAL_STATUS_MODERATE)).isEqualTo(30f)
        assertThat(vote(mini = true, thermal = PowerManager.THERMAL_STATUS_SEVERE)).isEqualTo(30f)
        assertThat(vote(floating = false, mini = true, stashed = true,
            thermal = PowerManager.THERMAL_STATUS_SEVERE)).isEqualTo(0f)
    }

    @Test fun clearVote_resetsRateAndChildSelectionStrategy() {
        val transaction = mock<SurfaceControl.Transaction>()
        val leash = mock<SurfaceControl>()
        whenever(transaction.setFrameRate(any(), any(), any())).thenReturn(transaction)
        FloatingFrameRateController.applyVote(transaction, leash, 0f)
        verify(transaction).setFrameRate(leash, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        verify(transaction).setFrameRateSelectionStrategy(leash,
            SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_PROPAGATE)
    }

    @Test fun votedFloatingTask_overridesChildPreferences() {
        val transaction = mock<SurfaceControl.Transaction>()
        val leash = mock<SurfaceControl>()
        whenever(transaction.setFrameRate(any(), any(), any())).thenReturn(transaction)
        FloatingFrameRateController.applyVote(transaction, leash, 30f)
        verify(transaction).setFrameRateSelectionStrategy(leash,
            SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN)
    }
    @Test fun unsupportedOverride_neverVotesEvenForMini() {
        val h = Harness(mini = true, supported = false)
        h.controller.update()
        verify(h.transaction, never()).setFrameRate(any(), any(), any())
    }

    @Test fun anyMotion_clearsAndResumesFloatingVotes() {
        val h = Harness(mini = true)
        val owner = Any()
        clearInvocations(h.transaction)
        com.android.wm.shell.common.SurfaceFrameRate.setInteracting(owner, true)
        verify(h.transaction).setFrameRate(h.leash, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        clearInvocations(h.transaction)
        com.android.wm.shell.common.SurfaceFrameRate.setInteracting(owner, false)
        verify(h.transaction).setFrameRate(h.leash, 60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }

}
