// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.splitscreen

import android.app.TaskInfo
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.view.Surface
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.window.TransitionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.shared.split.SplitScreenConstants.SNAP_TO_2_10_90
import com.android.wm.shell.shared.split.SplitScreenConstants.SNAP_TO_2_33_66
import com.android.wm.shell.shared.split.SplitScreenConstants.SNAP_TO_2_50_50
import com.android.wm.shell.shared.split.SplitScreenConstants.SNAP_TO_2_66_33
import com.android.wm.shell.shared.split.SplitScreenConstants.SNAP_TO_2_90_10
import com.android.wm.shell.transition.Transitions
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class SplitScreenFrameRateControllerTest : ShellTestCase() {
    private class Playback : SplitScreenFrameRateController.MediaPlayback {
        override var available = true
        var playingTask = -1
        private var listener = Runnable {}
        override fun start(listener: Runnable) { this.listener = listener }
        override fun isPlaying(task: TaskInfo) = task.taskId == playingTask
        fun changed() = listener.run()
    }

    private class Harness {
        val organizer = mock<ShellTaskOrganizer>()
        val displays = mock<DisplayController>()
        val transitions = mock<Transitions>()
        val t = mock<SurfaceControl.Transaction>()
        val left = mock<SurfaceControl>()
        val right = mock<SurfaceControl>()
        val playback = Playback()
        val refresh = mock<Runnable>()
        val child = TestRunningTaskInfoBuilder().setTaskId(11).setDisplayId(0).build().apply {
            parentTaskId = 100
            baseActivity = ComponentName("split.media", "Main")
        }
        val other = TestRunningTaskInfoBuilder().setTaskId(21).setDisplayId(0).build().apply {
            parentTaskId = 200
        }
        val first = SplitScreenFrameRateController.Stage(0,
            TestRunningTaskInfoBuilder().setTaskId(100).setDisplayId(0).build(), left, listOf(child))
        val second = SplitScreenFrameRateController.Stage(1,
            TestRunningTaskInfoBuilder().setTaskId(200).setDisplayId(0).build(), right, listOf(other))
        val controller: SplitScreenFrameRateController
        init {
            whenever(left.isValid).thenReturn(true)
            whenever(right.isValid).thenReturn(true)
            whenever(t.setFrameRate(any(), any(), any())).thenReturn(t)
            controller = SplitScreenFrameRateController(mock<Context>(), mock<ShellExecutor>(),
                mock<Handler>(), organizer, transitions, displays, refresh, playback, { t })
        }
        fun publish(position: Int = SNAP_TO_2_10_90, atRest: Boolean = true) =
            controller.updateStages(listOf(first, second), position, 0, atRest)
    }

    @Test fun offscreenTargets_voteOnCorrectRootAndClearPreviousRoot() {
        val h = Harness()
        h.publish()
        verify(h.t).setFrameRate(h.left, 30f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        verify(h.t).setFrameRateSelectionStrategy(h.left,
            SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN)
        verify(h.t, never()).setFrameRate(eq(h.right), any(), any())
        clearInvocations(h.t)
        h.publish(SNAP_TO_2_90_10)
        verify(h.t).setFrameRate(h.left, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        verify(h.t).setFrameRateSelectionStrategy(h.left,
            SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_PROPAGATE)
        verify(h.t).setFrameRate(h.right, 30f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }

    @Test fun normalRatioAndDrag_doNotVote() {
        val h = Harness()
        for (position in listOf(SNAP_TO_2_33_66, SNAP_TO_2_50_50, SNAP_TO_2_66_33)) {
            h.publish(position)
        }
        h.controller.setDividerMoving(true)
        h.publish()
        verify(h.t, never()).setFrameRate(any(), any(), any())
        h.controller.setDividerMoving(false)
        verify(h.t).setFrameRate(h.left, 30f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        clearInvocations(h.t)
        h.controller.setDividerMoving(true)
        verify(h.t).setFrameRate(h.left, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }

    @Test fun unsettledLayoutResizeAndSwap_doNotVote() {
        val h = Harness()
        h.publish(atRest = false)
        h.controller.setResizing(true)
        h.publish()
        h.controller.setAnimating(true)
        h.controller.setResizing(false)
        verify(h.t, never()).setFrameRate(any(), any(), any())
        h.controller.setAnimating(false)
        verify(h.t).setFrameRate(h.left, 30f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }

    @Test fun transitionsAndMerge_holdVotesUntilFinished() {
        val h = Harness()
        val first = mock<IBinder>()
        val second = mock<IBinder>()
        h.publish()
        h.controller.onTransitionReady(first, TransitionInfo(TRANSIT_CHANGE, 0), h.t, h.t)
        h.controller.onTransitionReady(second, TransitionInfo(TRANSIT_CHANGE, 0), h.t, h.t)
        verify(h.t).setFrameRate(h.left, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        clearInvocations(h.t)
        h.publish(SNAP_TO_2_90_10)
        h.controller.onTransitionMerged(first, second)
        h.publish(SNAP_TO_2_90_10)
        verify(h.t, never()).setFrameRate(any(), any(), any())
        h.controller.onTransitionFinished(second, false)
        h.publish(SNAP_TO_2_90_10)
        verify(h.t).setFrameRate(h.right, 30f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }

    @Test fun ratioChangeAndExit_clearVotesAndPlaybackCannotRestoreExitedStage() {
        val h = Harness()
        h.publish()
        h.publish(SNAP_TO_2_50_50)
        verify(h.t).setFrameRate(h.left, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        h.publish()
        clearInvocations(h.t)
        h.controller.updateStages(emptyList(), SNAP_TO_2_50_50, 0, false)
        verify(h.t).setFrameRate(h.left, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        clearInvocations(h.t)
        h.playback.changed()
        verify(h.t, never()).setFrameRate(any(), any(), any())
    }

    @Test fun mediaStartsAndStops_clearAndRestoreOnlyNarrowStageVote() {
        val h = Harness()
        h.publish()
        clearInvocations(h.t)
        h.playback.playingTask = h.child.taskId
        h.playback.changed()
        verify(h.t).setFrameRate(h.left, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        clearInvocations(h.t)
        h.playback.playingTask = h.other.taskId
        h.playback.changed()
        verify(h.t).setFrameRate(h.left, 30f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        verify(h.t, never()).setFrameRate(eq(h.right), any(), any())
        clearInvocations(h.t)
        h.playback.available = false
        h.playback.changed()
        verify(h.t).setFrameRate(h.left, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }

    @Test fun taskReplacement_clearsOwnedVoteBeforeReevaluating() {
        val h = Harness()
        h.publish()
        clearInvocations(h.t)
        val replacement = TestRunningTaskInfoBuilder().setTaskId(12).setDisplayId(0).build().apply {
            parentTaskId = 100
        }
        h.controller.updateStages(listOf(h.first.copy(tasks = listOf(replacement)), h.second),
            SNAP_TO_2_10_90, 0, true)
        verify(h.t).setFrameRate(h.left, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        verify(h.t).setFrameRate(h.left, 30f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }

    @Test fun rootVanish_clearsAndForgetsVote() {
        val h = Harness()
        h.publish()
        val vanished = argumentCaptor<ShellTaskOrganizer.TaskVanishedListener>()
        verify(h.organizer).addTaskVanishedListener(vanished.capture())
        clearInvocations(h.t)
        vanished.firstValue.onTaskVanished(h.first.root)
        verify(h.t).setFrameRate(h.left, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        clearInvocations(h.t)
        h.playback.changed()
        verify(h.t, never()).setFrameRate(any(), any(), any())
    }

    @Test fun nonSplitTaskEvents_doNotTouchAnySurfaceVote() {
        val h = Harness()
        h.publish()
        val info = argumentCaptor<ShellTaskOrganizer.TaskInfoChangedListener>()
        val vanished = argumentCaptor<ShellTaskOrganizer.TaskVanishedListener>()
        verify(h.organizer).addTaskInfoChangedListener(info.capture())
        verify(h.organizer).addTaskVanishedListener(vanished.capture())
        clearInvocations(h.t)
        val unrelated = TestRunningTaskInfoBuilder().setTaskId(999).build()
        info.firstValue.onTaskInfoChanged(unrelated)
        vanished.firstValue.onTaskVanished(unrelated)
        verify(h.t, never()).setFrameRate(any(), any(), any())
    }

    @Test fun displayChangeAndRemoval_clearAndRejectRemovedDisplaySnapshots() {
        val h = Harness()
        h.publish()
        val listener = argumentCaptor<DisplayController.OnDisplaysChangedListener>()
        verify(h.displays).addDisplayWindowListener(listener.capture())
        clearInvocations(h.t)
        listener.firstValue.onDisplayConfigurationChanged(0, Configuration())
        verify(h.t).setFrameRate(h.left, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        h.publish()
        clearInvocations(h.t)
        listener.firstValue.onDisplayRemoved(0)
        verify(h.t).setFrameRate(h.left, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        clearInvocations(h.t)
        h.publish()
        verify(h.t, never()).setFrameRate(any(), any(), any())
    }
}
