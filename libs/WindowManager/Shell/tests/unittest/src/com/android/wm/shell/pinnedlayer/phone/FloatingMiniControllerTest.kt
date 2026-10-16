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

    @Test fun interruptedStash_reachesTargetStrip() {
        val h = Harness()
        h.controller.onDragEnded(h.task, h.task.presentationBounds, Rect(-217, 200, 143, 740))
        whenever(h.animator.animatedValue).thenReturn(.5f)
        h.updateListener.onAnimationUpdate(h.animator)
        h.interrupt()
        val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
        assertThat(entry.bounds).isEqualTo(Rect(-332, 200, 28, 740))
        assertThat(entry.edge).isEqualTo(FloatingMiniState.Edge.LEFT)
        assertThat(h.task.presentationBounds).isEqualTo(entry.bounds)
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

    @Test fun miniReleasedPastTopOrBottom_returnsFullyInsideWithoutStash() {
        listOf(Rect(300, -400, 660, 140), Rect(300, 1800, 660, 2340)).forEach { end ->
            val h = Harness()
            h.controller.onDragEnded(h.task, h.task.presentationBounds, end)
            h.interrupt()
            val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
            assertThat(entry.edge).isEqualTo(FloatingMiniState.Edge.NONE)
            assertThat(Rect(0, 100, 1200, 1900).contains(entry.bounds)).isTrue()
        }
    }

    @Test fun tapStashedStrip_unstashesAtMiniSizeAndKeepsNormalRestoreGeometry() {
        val h = Harness()
        val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
        entry.edge = FloatingMiniState.Edge.LEFT
        entry.bounds.set(-332, 300, 28, 840)
        assertThat(h.controller.isStashed(h.task)).isTrue()
        h.controller.unstash(h.task)
        h.interrupt()
        assertThat(entry.edge).isEqualTo(FloatingMiniState.Edge.NONE)
        assertThat(entry.bounds).isEqualTo(Rect(8, 300, 368, 840))
        assertThat(entry.scale).isEqualTo(.45f)
        assertThat(entry.restore.bounds).isEqualTo(h.normal)
        assertThat(h.state.get(h.task.userId, h.task.taskId)).isSameInstanceAs(entry)
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

    @Test fun restoreAtEachCorner_centersOnCurrentMiniThenClamps() {
        val minis = listOf(Rect(8, 108, 368, 648), Rect(832, 108, 1192, 648),
            Rect(8, 1352, 368, 1892), Rect(832, 1352, 1192, 1892))
        val restored = listOf(Rect(0, 100, 600, 1000), Rect(600, 100, 1200, 1000),
            Rect(0, 1000, 600, 1900), Rect(600, 1000, 1200, 1900))
        minis.zip(restored).forEach { (mini, expected) -> assertCenteredRestore(mini, expected) }
    }

    @Test fun restoreAtSideMidpoint_shiftsOnlyEnoughToFit() {
        assertCenteredRestore(Rect(8, 730, 368, 1270), Rect(0, 550, 600, 1450))
        assertCenteredRestore(Rect(832, 730, 1192, 1270), Rect(600, 550, 1200, 1450))
    }

    @Test fun restoreInMiddle_keepsCurrentCenterAndRemembersNewPosition() {
        val h = assertCenteredRestore(Rect(420, 730, 780, 1270), Rect(300, 550, 900, 1450))
        val restored = h.changes.last()
        restored.baseActivity = android.content.ComponentName("test.restore", "Main")
        val store = FloatingWindowGeometryStore({ null }, java.util.concurrent.Executor { it.run() })
        val presentation = PinnedLayerPresentationController(context, h.displays, mock(), store)
        presentation.rememberBounds(restored)
        val remembered = requireNotNull(store.restore("test.restore", restored.userId,
            android.content.res.Configuration.ORIENTATION_PORTRAIT, Rect(0, 100, 1200, 1900),
            1200, 1920, 0))
        assertThat(remembered.bounds).isEqualTo(Rect(300, 550, 900, 1450))
        assertThat(remembered.scale).isEqualTo(.75f)
    }

    @Test fun restoreAfterUnstash_usesUnstashedMiniCenter() {
        val h = Harness()
        val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
        entry.edge = FloatingMiniState.Edge.LEFT
        entry.bounds.set(-332, 730, 28, 1270)
        h.controller.unstash(h.task)
        h.interrupt()
        assertThat(entry.bounds).isEqualTo(Rect(8, 730, 368, 1270))
        h.controller.restore(h.task)
        h.animatorListener.onAnimationEnd(h.animator)
        assertThat(h.changes.last().presentationBounds).isEqualTo(Rect(0, 550, 600, 1450))
        assertThat(h.changes.last().floatingWindowScale).isEqualTo(.75f)
    }

    private fun assertCenteredRestore(mini: Rect, expected: Rect): Harness {
        val h = Harness()
        h.controller.onDragEnded(h.task, h.task.presentationBounds, mini)
        h.interrupt()
        assertThat(h.task.presentationBounds).isEqualTo(mini)
        clearInvocations(h.transaction)
        h.controller.restore(h.task)
        whenever(h.animator.animatedValue).thenReturn(0f)
        h.updateListener.onAnimationUpdate(h.animator)
        verify(h.transaction).setPosition(h.leash, mini.left.toFloat(), mini.top.toFloat())
        whenever(h.animator.animatedValue).thenReturn(.5f)
        h.updateListener.onAnimationUpdate(h.animator)
        verify(h.transaction).setPosition(h.leash,
            (mini.left + expected.left) / 2f, (mini.top + expected.top) / 2f)
        h.animatorListener.onAnimationEnd(h.animator)
        val restored = h.changes.last()
        assertThat(restored.presentationBounds).isEqualTo(expected)
        assertThat(restored.floatingWindowScale).isEqualTo(.75f)
        assertThat(restored.configuration.windowConfiguration.bounds)
            .isEqualTo(Rect(0, 0, 800, 1200))
        assertThat(h.state.canRemember(h.task.userId, h.task.taskId)).isTrue()
        assertThat(Rect(0, 100, 1200, 1900).contains(expected)).isTrue()
        return h
    }

    @Test fun tidy_oneExpandedWindowUsesNearestCornerAndNormalEntryAnimation() {
        val h = Harness()
        h.state.remove(h.task.userId, h.task.taskId)
        h.task.isVisible = true
        h.task.floatingWindowScale = .75f
        h.task.floatingWindowPosition.set(50, 950)
        h.task.positionInParent = Point(50, 950)
        h.controller.tidy(listOf(h.task))
        val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
        assertThat(entry.bounds).isEqualTo(Rect(8, 1352, 368, 1892))
        assertThat(entry.restore.bounds).isEqualTo(Rect(50, 950, 650, 1850))
        assertThat(entry.restore.scale).isEqualTo(.75f)
        verify(h.animator).setDuration(300)
        whenever(h.animator.animatedValue).thenReturn(0f)
        h.updateListener.onAnimationUpdate(h.animator)
        verify(h.transaction).setPosition(h.leash, 50f, 950f)
    }

    @Test fun tidy_threeExpandedWindowsAreMiniWithoutOverlap() {
        val h = Harness()
        h.state.remove(h.task.userId, h.task.taskId)
        doAnswer {
            h.animatorListener.onAnimationEnd(h.animator)
            null
        }.whenever(h.animator).start()
        val tasks = (0..2).map { index ->
            TestRunningTaskInfoBuilder().setTaskId(42 + index).setDisplayId(0)
                .setBounds(Rect(0, 0, 800, 1200)).build().apply {
                    isFloatingWindow = true
                    isVisible = true
                    floatingWindowScale = .75f
                    floatingWindowPosition.set(600, 120)
                    positionInParent = Point(600, 120)
                }
        }
        h.controller.tidy(tasks)
        val bounds = tasks.map { requireNotNull(h.state.get(it.userId, it.taskId)).bounds }
        assertThat(bounds).containsExactly(Rect(832, 108, 1192, 648),
            Rect(832, 656, 1192, 1196), Rect(832, 1204, 1192, 1744)).inOrder()
        bounds.forEachIndexed { i, a ->
            assertThat(Rect(0, 100, 1200, 1900).contains(a)).isTrue()
            bounds.drop(i + 1).forEach { b -> assertThat(Rect.intersects(a, b)).isFalse() }
        }
    }

    @Test fun tidy_existingMiniStashAndHiddenMinimizedWindowAreUntouched() {
        val h = Harness()
        h.task.isVisible = true
        val mini = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
        val miniBounds = Rect(mini.bounds)
        val stash = TestRunningTaskInfoBuilder().setTaskId(43).setDisplayId(0)
            .setBounds(Rect(0, 0, 800, 1200)).build().apply {
                isFloatingWindow = true
                isVisible = true
            }
        val stashed = h.state.enter(stash.userId, stash.taskId, mini.restore,
            Rect(-332, 730, 28, 1270), .45f).apply { edge = FloatingMiniState.Edge.LEFT }
        val minimized = TestRunningTaskInfoBuilder().setTaskId(44).setDisplayId(0).build().apply {
            isFloatingWindow = true
            isVisible = false
        }
        h.controller.tidy(listOf(h.task, stash, minimized))
        assertThat(mini.bounds).isEqualTo(miniBounds)
        assertThat(stashed.bounds).isEqualTo(Rect(-332, 730, 28, 1270))
        assertThat(stashed.edge).isEqualTo(FloatingMiniState.Edge.LEFT)
        assertThat(h.state.get(minimized.userId, minimized.taskId)).isNull()
        assertThat(h.allocations).isEqualTo(0)
        verify(h.animator, never()).start()
    }

    @Test fun tidy_withoutExpandedWindowsDoesNothing() {
        val h = Harness()
        h.controller.tidy(emptyList())
        assertThat(h.allocations).isEqualTo(0)
        verify(h.organizer, never()).applyTransaction(any())
        verify(h.animator, never()).start()
    }

    @Test fun tidy_crowdedEdgesFitAllFiveWithoutOverlapping() {
        val h = Harness()
        h.state.remove(h.task.userId, h.task.taskId)
        doAnswer {
            h.animatorListener.onAnimationEnd(h.animator)
            null
        }.whenever(h.animator).start()
        val tasks = (0..4).map { index ->
            TestRunningTaskInfoBuilder().setTaskId(42 + index).setDisplayId(0)
                .setBounds(Rect(0, 0, 1200, 400)).build().apply {
                    isFloatingWindow = true
                    isVisible = true
                    floatingWindowScale = .95f
                    floatingWindowPosition.set(50, 120)
                    positionInParent = Point(50, 120)
                }
        }
        h.controller.tidy(tasks)
        val entries = tasks.map { requireNotNull(h.state.get(it.userId, it.taskId)) }
        assertThat(entries.last().scale).isLessThan(.9f)
        entries.forEachIndexed { i, a ->
            assertThat(Rect(8, 108, 1192, 1892).contains(a.bounds)).isTrue()
            entries.drop(i + 1).forEach { b ->
                assertThat(Rect.intersects(a.bounds, b.bounds)).isFalse()
            }
        }
    }

}
