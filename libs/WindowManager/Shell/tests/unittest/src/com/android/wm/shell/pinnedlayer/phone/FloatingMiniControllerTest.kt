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

    @Test fun captionDrag_shortLargeWindowNudgeDoesNotEnterMini() {
        val h = Harness()
        h.state.remove(h.task.userId, h.task.taskId)
        h.task.configuration.windowConfiguration.setBounds(Rect(0, 0, 2242, 1401))
        h.task.floatingWindowScale = 1f
        h.task.floatingWindowPosition.set(179, 73)
        whenever(h.layout.width()).thenReturn(2560)
        whenever(h.layout.height()).thenReturn(1600)
        doAnswer { it.getArgument<Rect>(0).set(0, 0, 2560, 1600); null }
            .whenever(h.layout).getStableBounds(any())
        val start = Rect(179, 73, 2421, 1474)
        val end = Rect(329, 183, 2571, 1584)
        assertThat(h.controller.getDragTarget(h.task, start, end)).isNull()

        assertThat(h.controller.onDragEnded(h.task, start, end,
            android.graphics.PointF(150f, 110f))).isFalse()

        assertThat(h.state.get(h.task.userId, h.task.taskId)).isNull()
        verify(h.animator, never()).start()
    }

    @Test fun captionDrag_mediumWindowEntersBottomCornersIncludingClampedTravel() {
        for (right in listOf(false, true)) {
            for (clamped in listOf(false, true)) {
                val h = Harness()
                h.state.remove(h.task.userId, h.task.taskId)
                h.task.floatingWindowScale = .75f
                h.task.floatingWindowPosition.set(100, 200)
                val end = if (right) Rect(600, 1000, 1200, 1900)
                    else Rect(0, 1000, 600, 1900)
                val start = if (clamped) Rect(end) else Rect(100, 200, 700, 1100)
                h.task.floatingWindowPosition.set(start.left, start.top)
                val travel = android.graphics.PointF(if (right) 500f else -100f, 800f)

                assertThat(h.controller.onDragEnded(h.task, start, end, travel)).isTrue()

                assertThat(h.state.get(h.task.userId, h.task.taskId)).isNotNull()
                verify(h.animator).start()
                h.animatorListener.onAnimationEnd(h.animator)
            }
        }
    }

    @Test fun flingRestore_leavesExpandedGeometryAvailableForCornerResize() {
        val h = Harness()
        h.state.remove(h.task.userId, h.task.taskId)
        h.task.floatingWindowScale = .75f
        h.task.floatingWindowPosition.set(100, 200)
        val start = Rect(h.task.presentationBounds)
        assertThat(h.controller.flingToCorner(h.task, start, start,
            400f, 650f, 0f, -2000f)).isFalse()
        assertThat(h.controller.flingToCorner(h.task, start, start,
            1100f, 1800f, -1000f, -1000f)).isFalse()
        assertThat(h.controller.flingToCorner(h.task, start, start,
            400f, 650f, -2000f, -2000f)).isTrue()
        h.animatorListener.onAnimationEnd(h.animator)
        h.controller.restore(h.task)
        h.animatorListener.onAnimationEnd(h.animator)
        val restored = h.changes.last()
        assertThat(h.state.get(h.task.userId, h.task.taskId)).isNull()
        assertThat(restored.floatingWindowScale).isEqualTo(.75f)
        val resized = FloatingWindowGeometry.presetCorner(restored.presentationBounds,
            restored.configuration.windowConfiguration.bounds, Rect(0, 100, 1200, 1900),
            com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_RIGHT or
                com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_BOTTOM,
            100f, 150f, 100, 100)
        assertThat(resized.visible).isNotEqualTo(restored.presentationBounds)
    }

    @Test fun largeCaptionDrag_previewAndReleaseUseTheSameInwardReference() {
        val h = Harness()
        h.state.remove(h.task.userId, h.task.taskId)
        h.task.configuration.windowConfiguration.setBounds(Rect(0, 0, 1000, 1200))
        h.task.floatingWindowScale = 1f
        h.task.floatingWindowPosition.set(100, 300)
        val start = Rect(h.task.presentationBounds)
        val early = Rect(200, 700, 1200, 1900)
        assertThat(h.controller.getDragTarget(h.task, start, early)).isNull()
        assertThat(h.controller.onDragEnded(h.task, start, early,
            android.graphics.PointF(100f, 400f))).isFalse()
        val end = Rect(500, 1100, 1500, 2300)
        val preview = requireNotNull(h.controller.getDragTarget(h.task, start, end))
        assertThat(h.controller.onDragEnded(h.task, start, end,
            android.graphics.PointF(400f, 800f))).isTrue()
        assertThat(requireNotNull(h.state.get(h.task.userId, h.task.taskId)).bounds)
            .isEqualTo(preview.bounds)
    }

    @Test fun miniFling_eachCornerPreservesMiniSizeScaleAndRestoreState() {
        for (corner in FloatingMiniState.Corner.values()) {
            val h = Harness()
            val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
            val restore = entry.restore
            val end = Rect(400, 700, 760, 1240)
            val left = corner == FloatingMiniState.Corner.TOP_LEFT ||
                corner == FloatingMiniState.Corner.BOTTOM_LEFT
            val top = corner == FloatingMiniState.Corner.TOP_LEFT ||
                corner == FloatingMiniState.Corner.TOP_RIGHT
            h.controller.beginDrag(h.task)
            h.controller.move(h.task, end)
            assertThat(h.controller.flingToCorner(h.task, h.task.presentationBounds, end,
                600f, 1000f, if (left) -10000f else 10000f,
                if (top) -10000f else 10000f)).isTrue()
            h.animatorListener.onAnimationEnd(h.animator)
            assertThat(h.state.get(h.task.userId, h.task.taskId)).isSameInstanceAs(entry)
            assertThat(entry.restore).isSameInstanceAs(restore)
            assertThat(entry.restore.bounds).isEqualTo(h.normal)
            assertThat(entry.scale).isEqualTo(.45f)
            assertThat(entry.bounds).isEqualTo(FloatingMiniState.snap(end,
                Rect(0, 100, 1200, 1900), corner, 8))
            assertThat(entry.bounds.width()).isEqualTo(end.width())
            assertThat(entry.bounds.height()).isEqualTo(end.height())
            assertThat(entry.edge).isEqualTo(FloatingMiniState.Edge.NONE)
            assertThat(h.changes.last().configuration.windowConfiguration.bounds)
                .isEqualTo(Rect(0, 0, 800, 1200))
        }
    }

    @Test fun miniFling_slowReleaseFallsBackToExistingStashHandling() {
        val h = Harness()
        val end = Rect(-217, 200, 143, 740)
        assertThat(h.controller.flingToCorner(h.task, h.task.presentationBounds, end,
            0f, 200f, -100f, -100f)).isFalse()
        verify(h.animator, never()).start()
        h.controller.onDragEnded(h.task, h.task.presentationBounds, end)
        h.animatorListener.onAnimationEnd(h.animator)
        assertThat(requireNotNull(h.state.get(h.task.userId, h.task.taskId)).edge)
            .isEqualTo(FloatingMiniState.Edge.LEFT)
    }

    @Test fun miniFling_reservesCornerAndCascadesVerticallyLeavingStashedMiniUntouched() {
        for (top in listOf(true, false)) {
            val h = Harness()
            val entries = (0..2).map { index ->
                val bounds = if (top) Rect(8, 108 + index * 548, 368, 648 + index * 548)
                    else Rect(8, 1352 - index * 548, 368, 1892 - index * 548)
                val other = TestRunningTaskInfoBuilder().setTaskId(100 + index)
                    .setDisplayId(0).build().apply { isFloatingWindow = true; isVisible = true }
                val entry = h.state.enter(other.userId, other.taskId,
                    FloatingMiniState.Restore(h.normal, Rect(0, 0, 800, 1200), .75f),
                    bounds, .45f)
                if (index == 2) entry.edge = FloatingMiniState.Edge.LEFT
                other to entry
            }
            whenever(h.organizer.getRunningTasks(0)).thenReturn(
                arrayListOf(h.task, *entries.map { it.first }.toTypedArray()))
            doAnswer { h.animatorListener.onAnimationEnd(h.animator); null }
                .whenever(h.animator).start()
            val stashed = Rect(entries[2].second.bounds)
            val end = Rect(400, 700, 760, 1240)
            assertThat(h.controller.flingToCorner(h.task, h.task.presentationBounds, end,
                600f, 1000f, -10000f, if (top) -10000f else 10000f)).isTrue()
            assertThat(entries[0].second.bounds.top).isEqualTo(if (top) 656 else 804)
            assertThat(entries[1].second.bounds.top).isEqualTo(if (top) 1204 else 256)
            assertThat(entries[2].second.bounds).isEqualTo(stashed)
            assertThat(entries[2].second.edge).isEqualTo(FloatingMiniState.Edge.LEFT)
            entries.forEach { h.state.remove(it.first.userId, it.first.taskId) }
        }
    }

    @Test fun prepareRestore_expandsMiniInCallerTransactionWithoutStartingAnimation() {
        val h = Harness()
        val wct = android.window.WindowContainerTransaction()
        clearInvocations(h.organizer, h.animator)
        assertThat(h.controller.prepareRestore(h.task, wct)).isTrue()
        val change = requireNotNull(wct.changes[h.task.token.asBinder()])
        assertThat(change.configuration.windowConfiguration.bounds)
            .isEqualTo(Rect(0, 0, 800, 1200))
        assertThat(change.floatingWindowScale).isEqualTo(.75f)
        assertThat(h.state.get(h.task.userId, h.task.taskId)).isNull()
        verify(h.organizer, never()).applyTransaction(any())
        verify(h.animator, never()).start()
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
        val store = FloatingWindowGeometryStore({ null }, java.util.concurrent.Executor { it.run() },
            java.util.concurrent.Executor { it.run() })
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


    @Test fun stashTargetQuery_usesExistingThresholdWithoutMutatingEntry() {
        val h = Harness()
        val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
        val before = Rect(entry.bounds)
        assertThat(h.controller.getStashTarget(h.task, Rect(-216, 200, 144, 740)))
            .isEqualTo(FloatingMiniState.Edge.NONE)
        assertThat(h.controller.getStashTarget(h.task, Rect(-217, 200, 143, 740)))
            .isEqualTo(FloatingMiniState.Edge.LEFT)
        assertThat(entry.bounds).isEqualTo(before)
        assertThat(entry.edge).isEqualTo(FloatingMiniState.Edge.NONE)
        assertThat(h.changes).isEmpty()
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

    @Test fun placement_filtersDisplaysHiddenTasksAndStashedStrips() {
        val h = Harness()
        val bounds = Rect(8, 108, 368, 648)
        val others = listOf(h.occupy(bounds, id = 44, display = 2),
            h.occupy(bounds, id = 45, visible = false),
            h.occupy(bounds, id = 46, edge = FloatingMiniState.Edge.LEFT))
        whenever(h.organizer.getRunningTasks(0)).thenReturn(ArrayList(listOf(h.task) + others))
        val target = requireNotNull(h.controller.getDragTarget(h.task,
            Rect(300, 500, 660, 1040), Rect(0, 100, 360, 640)))
        assertThat(target.bounds).isEqualTo(bounds)
        assertThat(h.changes).isEmpty()
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


    private fun occupiedMini(h: Harness, user: Int = h.task.userId,
        display: Int = 0, visible: Boolean = true): FloatingMiniState.Entry {
        val other = TestRunningTaskInfoBuilder().setTaskId(43).setDisplayId(display).build().apply {
            userId = user
            isFloatingWindow = true
            isVisible = visible
        }
        whenever(h.organizer.getRunningTasks(0)).thenReturn(arrayListOf(h.task, other))
        val restore = requireNotNull(h.state.get(h.task.userId, h.task.taskId)).restore
        return h.state.enter(other.userId, other.taskId, restore, Rect(8, 300, 368, 840), .45f)
    }

    @Test fun tapStashedStrip_avoidsVisibleMiniFromAnotherProfile() {
        val h = Harness()
        val other = occupiedMini(h, user = h.task.userId + 10)
        val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
        entry.edge = FloatingMiniState.Edge.LEFT
        entry.bounds.set(-332, 300, 28, 840)
        h.controller.unstash(h.task)
        h.interrupt()
        assertThat(Rect.intersects(entry.bounds, other.bounds)).isFalse()
        assertThat(Rect(8, 108, 1192, 1892).contains(entry.bounds)).isTrue()
        assertThat(entry.edge).isEqualTo(FloatingMiniState.Edge.NONE)
        assertThat(entry.scale).isEqualTo(.45f)
        assertThat(entry.restore.bounds).isEqualTo(h.normal)
        assertThat(other.bounds).isEqualTo(Rect(8, 300, 368, 840))
    }

    @Test fun miniReleaseAwayFromCorner_avoidsOccupiedMini() {
        val h = Harness()
        val other = occupiedMini(h)
        other.bounds.set(420, 730, 780, 1270)
        h.controller.onDragEnded(h.task, h.task.presentationBounds, Rect(other.bounds))
        h.interrupt()
        val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
        assertThat(Rect.intersects(entry.bounds, other.bounds)).isFalse()
        assertThat(entry.scale).isEqualTo(.45f)
        assertThat(entry.restore.bounds).isEqualTo(h.normal)
        assertThat(other.bounds).isEqualTo(Rect(420, 730, 780, 1270))
    }

    @Test fun dragStashedMiniOnscreen_avoidsOccupiedMini() {
        val h = Harness()
        val other = occupiedMini(h)
        val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
        entry.edge = FloatingMiniState.Edge.LEFT
        entry.bounds.set(-332, 300, 28, 840)
        h.controller.onDragEnded(h.task, Rect(entry.bounds), Rect(other.bounds))
        h.interrupt()
        assertThat(entry.edge).isEqualTo(FloatingMiniState.Edge.NONE)
        assertThat(Rect.intersects(entry.bounds, other.bounds)).isFalse()
        assertThat(entry.scale).isEqualTo(.45f)
    }

    @Test fun tapStashedStrip_ignoresHiddenAndOtherDisplayMinis() {
        for ((display, visible) in listOf(0 to false, 1 to true)) {
            val h = Harness()
            occupiedMini(h, display = display, visible = visible)
            val entry = requireNotNull(h.state.get(h.task.userId, h.task.taskId))
            entry.edge = FloatingMiniState.Edge.LEFT
            entry.bounds.set(-332, 300, 28, 840)
            h.controller.unstash(h.task)
            h.interrupt()
            assertThat(entry.bounds).isEqualTo(Rect(8, 300, 368, 840))
        }
    }
}
