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

    @Test fun taskInfo_positionOnlyOverwriteRestoresStashedPresentation() {
        val h = Harness()
        val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
        entry.bounds.offsetTo(-332, 108)
        entry.edge = FloatingMiniState.Edge.LEFT
        h.task.floatingWindowPosition.set(420, 600)
        h.task.floatingWindowScale = .75f

        h.controller.onTaskInfoChanged(h.task)

        val wct = argumentCaptor<android.window.WindowContainerTransaction>()
        verify(h.organizer).applyTransaction(wct.capture())
        val change = requireNotNull(wct.firstValue.changes[h.task.token.asBinder()])
        assertThat(change.floatingWindowPosition).isEqualTo(Point(-332, 108))
        assertThat(change.floatingWindowScale).isEqualTo(.45f)
        assertThat(entry.edge).isEqualTo(FloatingMiniState.Edge.LEFT)
    }

    @Test fun launcherTransition_keepsSavedMiniTransformInBothTransactions() {
        val h = Harness()
        val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
        entry.bounds.offsetTo(-332, 108)
        entry.edge = FloatingMiniState.Edge.LEFT
        h.task.floatingWindowPosition.set(420, 600)
        h.task.positionInParent = Point(420, 600)

        h.interrupt()

        assertThat(h.task.floatingWindowPosition).isEqualTo(Point(-332, 108))
        assertThat(h.task.positionInParent).isEqualTo(Point(-332, 108))
        verify(h.transaction, times(2)).setPosition(h.leash, -332f, 108f)
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

    @Test fun interruptedRestore_commitsTargetAndRemovesMiniOwnership() {
        val h = Harness()
        h.controller.restore(h.task)
        whenever(h.animator.animatedValue).thenReturn(.5f)
        h.updateListener.onAnimationUpdate(h.animator)
        h.interrupt()
        assertThat(h.state.get(h.task.userId, h.task.taskId)).isNull()
        assertThat(h.task.presentationBounds).isEqualTo(Rect(0, 100, 600, 1000))
        assertThat(h.task.floatingWindowScale).isEqualTo(.75f)
        assertThat(h.controller.isAnimating(h.task)).isFalse()
        verify(h.transaction).close()
    }

    @Test fun closeDuringRestore_keepsVisualStartAndCompletesOwnershipCleanup() {
        val h = Harness()
        h.controller.restore(h.task)
        whenever(h.animator.animatedValue).thenReturn(.5f)
        h.updateListener.onAnimationUpdate(h.animator)
        val current = Rect(requireNotNull(h.state.get(h.task.userId, h.task.taskId)).bounds)
        val change = h.interrupt(android.view.WindowManager.TRANSIT_CLOSE)
        assertThat(h.state.get(h.task.userId, h.task.taskId)).isNull()
        assertThat(change.startFloatingWindowBounds).isEqualTo(current)
        assertThat(h.controller.isAnimating(h.task)).isFalse()
    }

    @Test fun interruptedEntry_reachesMiniTarget() {
        val h = Harness()
        h.state.remove(h.task.userId, h.task.taskId)
        h.task.floatingWindowScale = .75f
        h.task.positionInParent = Point(100, 200)
        h.task.floatingWindowPosition.set(100, 200)
        h.controller.onDragEnded(h.task, h.task.presentationBounds, Rect(0, 100, 600, 1000))
        whenever(h.animator.animatedValue).thenReturn(.5f)
        h.updateListener.onAnimationUpdate(h.animator)
        h.interrupt()
        val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
        assertThat(entry.bounds).isEqualTo(Rect(8, 108, 368, 648))
        assertThat(h.task.presentationBounds).isEqualTo(entry.bounds)
        assertThat(h.task.floatingWindowScale).isEqualTo(.45f)
    }

    @Test fun drag_coalescesMovesAndReusesTransactionAndLeash() {
        val h = Harness()
        h.controller.beginDrag(h.task)
        clearInvocations(h.organizer, h.transaction, h.choreographer)
        repeat(20) { h.controller.move(h.task, Rect(it, 200, it + 360, 740)) }
        val callback = argumentCaptor<Choreographer.FrameCallback>()
        verify(h.choreographer).postFrameCallback(callback.capture())
        verify(h.transaction, never()).apply()
        callback.firstValue.doFrame(0)
        verify(h.transaction).setPosition(h.leash, 19f, 200f)
        verify(h.transaction).apply()
        h.controller.move(h.task, Rect(20, 200, 380, 740))
        callback.firstValue.doFrame(6_060_606)
        assertThat(h.allocations).isEqualTo(1)
        verify(h.transaction, times(2)).apply()
        verify(h.transaction, times(2)).setFrameTimeline(h.choreographer.vsyncId)
        verify(h.organizer, never()).initialTasks
    }
    @Test fun normalReleaseBeyondTopRight_entersMiniOnRealReleaseGeometry() {
        val h = Harness()
        h.state.remove(h.task.userId, h.task.taskId)
        h.task.floatingWindowScale = .75f
        h.task.positionInParent = Point(100, 200)
        h.task.floatingWindowPosition.set(100, 200)
        assertThat(h.controller.onDragEnded(h.task, h.task.presentationBounds,
            Rect(1100, 73, 1700, 973))).isTrue()
        val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
        assertThat(entry.bounds).isEqualTo(Rect(832, 108, 1192, 648))
    }

    @Test fun previewAndRelease_shareExactCornerTargets() {
        val corners = listOf(Rect(-20, 70, 340, 610), Rect(880, 70, 1240, 610),
            Rect(-20, 1400, 340, 1940), Rect(880, 1400, 1240, 1940))
        val outside = listOf(Rect(49, 149, 409, 689), Rect(791, 149, 1151, 689),
            Rect(49, 1311, 409, 1851), Rect(791, 1311, 1151, 1851))
        val boundary = listOf(Rect(48, 148, 408, 688), Rect(792, 148, 1152, 688),
            Rect(48, 1312, 408, 1852), Rect(792, 1312, 1152, 1852))
        corners.forEachIndexed { index, end ->
            val h = Harness()
            val start = Rect(300, 500, 660, 1040)
            assertThat(h.controller.getDragTarget(h.task, start, boundary[index])).isNotNull()
            assertThat(h.controller.getDragTarget(h.task, start, start)).isNull()
            assertThat(h.controller.getDragTarget(h.task, start, outside[index])).isNull()
            val target = requireNotNull(h.controller.getDragTarget(h.task, start, end))
            assertThat(h.controller.onDragEnded(h.task, start, end)).isTrue()
            val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
            assertThat(entry.bounds).isEqualTo(target.bounds)
            assertThat(entry.scale).isEqualTo(target.scale)
        }
    }

    @Test fun tapWithoutDrag_releasesInteractionOwner() {
        val h = Harness()
        h.controller.beginDrag(h.task)
        h.controller.endTap(h.task)
        assertThat(com.android.wm.shell.common.SurfaceFrameRate.isInteracting).isFalse()
        verify(h.transaction).close()
    }

    @Test fun removedMini_pendingFrameCannotUseClosedTransaction() {
        val h = Harness()
        h.controller.beginDrag(h.task)
        h.controller.move(h.task, Rect(20, 200, 380, 740))
        val callback = argumentCaptor<Choreographer.FrameCallback>()
        verify(h.choreographer).postFrameCallback(callback.capture())
        h.controller.remove(h.task)
        callback.firstValue.doFrame(6_060_606)
        verify(h.transaction).close()
        verify(h.transaction, never()).apply()
    }

    private fun Harness.occupy(bounds: Rect, id: Int = 43, user: Int = task.userId,
        display: Int = 0, visible: Boolean = true,
        edge: FloatingMiniState.Edge = FloatingMiniState.Edge.NONE): android.app.ActivityManager.RunningTaskInfo {
        val other = TestRunningTaskInfoBuilder().setTaskId(id).setDisplayId(display).build().apply {
            userId = user
            isFloatingWindow = true
            isVisible = visible
        }
        state.enter(user, id, FloatingMiniState.Restore(normal, Rect(0, 0, 800, 1200), .75f),
            bounds, .45f).edge = edge
        return other
    }

    @Test fun occupiedCorner_selectsNearestFreeSlotAtRequestedSizeAndDropMatchesPreview() {
        val h = Harness()
        h.state.remove(h.task.userId, h.task.taskId)
        h.task.floatingWindowScale = .75f
        val occupied = Rect(8, 108, 368, 648)
        val other = h.occupy(occupied)
        whenever(h.organizer.getRunningTasks(0)).thenReturn(arrayListOf(h.task, other))
        val start = Rect(300, 500, 900, 1400)
        val end = Rect(0, 100, 600, 1000)
        val target = requireNotNull(h.controller.getDragTarget(h.task, start, end))
        assertThat(target.bounds).isEqualTo(Rect(376, 108, 736, 648))
        assertThat(target.scale).isEqualTo(.45f)
        assertThat(h.state.get(h.task.userId, h.task.taskId)).isNull()
        assertThat(h.changes).isEmpty()
        h.controller.onDragEnded(h.task, start, end)
        assertThat(h.state.get(h.task.userId, h.task.taskId)!!.bounds).isEqualTo(target.bounds)
        assertThat(h.state.get(other.userId, other.taskId)!!.bounds).isEqualTo(occupied)
    }

    @Test fun miniCornerDrop_excludesItselfAndKeepsStationaryMinisAndSize() {
        val h = Harness()
        val occupied = Rect(8, 108, 508, 408)
        val other = h.occupy(occupied)
        whenever(h.organizer.getRunningTasks(0)).thenReturn(arrayListOf(h.task, other))
        val start = Rect(300, 500, 660, 1040)
        val end = Rect(0, 100, 360, 640)
        val target = requireNotNull(h.controller.getDragTarget(h.task, start, end))
        assertThat(target.bounds).isEqualTo(Rect(8, 416, 368, 956))
        h.controller.onDragEnded(h.task, start, end)
        h.interrupt()
        assertThat(h.state.get(h.task.userId, h.task.taskId)!!.bounds).isEqualTo(target.bounds)
        assertThat(h.state.get(other.userId, other.taskId)!!.bounds).isEqualTo(occupied)
        assertThat(target.scale).isEqualTo(.45f)
    }

    @Test fun crowdedEdges_noFitUsesDeterministicSnapWithoutShrinkingOrMovingOthers() {
        val h = Harness()
        val occupied = Rect(8, 108, 1192, 1892)
        val other = h.occupy(occupied)
        whenever(h.organizer.getRunningTasks(0)).thenReturn(arrayListOf(h.task, other))
        val start = Rect(300, 500, 660, 1040)
        val end = Rect(0, 100, 360, 640)
        val target = requireNotNull(h.controller.getDragTarget(h.task, start, end))
        assertThat(target.bounds).isEqualTo(Rect(8, 108, 368, 648))
        assertThat(h.controller.getDragTarget(h.task, start, end)).isEqualTo(target)
        h.controller.onDragEnded(h.task, start, end)
        h.interrupt()
        assertThat(h.state.get(h.task.userId, h.task.taskId)!!.scale).isEqualTo(.45f)
        assertThat(h.state.get(other.userId, other.taskId)!!.bounds).isEqualTo(occupied)
    }

    @Test fun drop_requeriesOccupancyAfterTaskVanishAndUsesCurrentDisplayGeometry() {
        val h = Harness()
        val other = h.occupy(Rect(8, 108, 368, 648))
        whenever(h.organizer.getRunningTasks(0)).thenReturn(arrayListOf(h.task, other))
        val start = Rect(300, 500, 660, 1040)
        val end = Rect(0, 100, 360, 640)
        val hovered = requireNotNull(h.controller.getDragTarget(h.task, start, end))
        whenever(h.organizer.getRunningTasks(0)).thenReturn(arrayListOf(h.task))
        h.controller.onDragEnded(h.task, start, end)
        h.interrupt()
        val entry = h.state.get(h.task.userId, h.task.taskId)!!
        assertThat(entry.bounds).isEqualTo(Rect(8, 108, 368, 648))
        assertThat(entry.bounds).isNotEqualTo(hovered.bounds)
        doAnswer { it.getArgument<Rect>(0).set(0, 100, 1920, 1200); null }
            .whenever(h.layout).getStableBounds(any())
        val rotatedEnd = Rect(1600, 800, 1960, 1340)
        val rotated = requireNotNull(h.controller.getDragTarget(h.task, start, rotatedEnd))
        h.controller.onDragEnded(h.task, start, rotatedEnd)
        h.interrupt()
        assertThat(entry.bounds).isEqualTo(rotated.bounds)
        assertThat(Rect(0, 100, 1920, 1200).contains(entry.bounds)).isTrue()
    }

    @Test fun vanishedMovingTask_cannotAcquirePreviewOrCommitPlacement() {
        val h = Harness()
        val listener = argumentCaptor<ShellTaskOrganizer.TaskVanishedListener>()
        verify(h.organizer).addTaskVanishedListener(listener.capture())
        listener.firstValue.onTaskVanished(h.task)
        val start = Rect(300, 500, 660, 1040)
        val end = Rect(0, 100, 360, 640)
        assertThat(h.controller.getDragTarget(h.task, start, end)).isNull()
        assertThat(h.controller.onDragEnded(h.task, start, end)).isFalse()
        assertThat(h.changes).isEmpty()
    }
    @Test fun floatingCornerAvoidsVisibleMiniFromAnotherProfileUsingItsUserKey() {
        val h = Harness()
        h.state.remove(h.task.userId, h.task.taskId)
        h.task.floatingWindowScale = .75f
        val occupied = Rect(8, 108, 368, 648)
        val other = h.occupy(occupied, user = h.task.userId + 10)
        whenever(h.organizer.getRunningTasks(0)).thenReturn(arrayListOf(h.task, other))
        val start = Rect(300, 500, 900, 1400)
        val end = Rect(0, 100, 600, 1000)
        val target = requireNotNull(h.controller.getDragTarget(h.task, start, end))
        assertThat(target.bounds).isEqualTo(Rect(376, 108, 736, 648))
        h.controller.onDragEnded(h.task, start, end)
        assertThat(h.state.get(h.task.userId, h.task.taskId)!!.bounds).isEqualTo(target.bounds)
        assertThat(h.state.get(other.userId, other.taskId)!!.bounds).isEqualTo(occupied)
    }

    @Test fun existingMiniAvoidsVisibleMiniFromAnotherProfileWithoutMovingIt() {
        val h = Harness()
        val occupied = Rect(8, 108, 508, 408)
        val other = h.occupy(occupied, user = h.task.userId + 10)
        whenever(h.organizer.getRunningTasks(0)).thenReturn(arrayListOf(h.task, other))
        val start = Rect(300, 500, 660, 1040)
        val end = Rect(0, 100, 360, 640)
        val target = requireNotNull(h.controller.getDragTarget(h.task, start, end))
        assertThat(target.bounds).isEqualTo(Rect(8, 416, 368, 956))
        h.controller.onDragEnded(h.task, start, end)
        h.interrupt()
        assertThat(h.state.get(h.task.userId, h.task.taskId)!!.bounds).isEqualTo(target.bounds)
        assertThat(h.state.get(other.userId, other.taskId)!!.bounds).isEqualTo(occupied)
    }

    @Test fun customRatio_miniRoundTripKeepsLogicalGeometry() {
        val h = Harness()
        h.controller.remove(h.task)
        val logical = Rect(100, 200, 1300, 1100)
        val normal = Rect(100, 200, 1000, 875)
        h.task.configuration.windowConfiguration.setBounds(logical)
        h.task.floatingWindowScale = .75f
        h.task.floatingWindowPosition.set(normal.left, normal.top)
        val mini = FloatingMiniState.sized(logical, .3f, 8, 108)
        h.controller.enterAfterResize(h.task, normal, logical, .75f, mini, .3f)
        val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
        assertThat(entry.bounds.width().toFloat() / entry.bounds.height())
            .isWithin(.005f).of(4f / 3f)
        assertThat(entry.restore.logical).isEqualTo(logical)
        h.task.floatingWindowScale = .3f
        h.task.floatingWindowPosition.set(mini.left, mini.top)
        h.controller.restore(h.task)
        h.animatorListener.onAnimationEnd(h.animator)
        assertThat(h.state.get(h.task.userId, h.task.taskId)).isNull()
        assertThat(h.changes.last().configuration.windowConfiguration.bounds).isEqualTo(logical)
        assertThat(h.changes.last().floatingWindowScale).isEqualTo(.75f)
    }

    @Test fun customRatio_roundedMiniCommitDoesNotBlockLaterTaskChanges() {
        val h = Harness()
        h.controller.remove(h.task)
        val logical = Rect(100, 200, 1299, 1099)
        val normal = FloatingMiniState.sized(logical, .75f, 100, 200)
        val mini = FloatingMiniState.sized(logical, .3f, 8, 108)
        h.controller.enterAfterResize(h.task, normal, logical, .75f, mini, .3f)
        h.task.configuration.windowConfiguration.setBounds(logical)
        h.task.floatingWindowScale = .3f
        h.task.floatingWindowPosition.set(mini.left, mini.top)
        h.controller.onTaskInfoChanged(h.task)
        h.task.configuration.windowConfiguration.setBounds(Rect(100, 200, 1400, 1000))
        clearInvocations(h.organizer)
        h.controller.onTaskInfoChanged(h.task)
        verify(h.organizer).applyTransaction(any())
        val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
        assertThat(entry.bounds.width().toFloat() / entry.bounds.height())
            .isWithin(.005f).of(1300f / 800f)
    }
}
