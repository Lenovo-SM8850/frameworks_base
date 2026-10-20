// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import android.app.ActivityManager.RunningTaskInfo
import android.app.PendingIntent
import android.app.ActivityOptions
import android.app.Activity
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.TransitionRequestInfo
import android.window.RemoteTransition
import android.window.IRemoteTransition
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.never
import org.mockito.kotlin.atLeastOnce
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Binder
import android.os.Bundle
import android.testing.AndroidTestingRunner
import android.util.SparseArray
import android.window.IWindowContainerToken
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayImeController
import com.android.wm.shell.common.DisplayInsetsController
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.common.split.SplitLayout
import com.android.wm.shell.splitscreen.SplitScreenController.AppDragSlot
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class MultiSplitCoordinatorTest : ShellTestCase() {
    private val groups = MultiSplitGroupRepository(Runnable {})
    private val host = mock<MultiSplitCoordinator.Host>()
    private val organizer = mock<ShellTaskOrganizer>()
    private val sync = mock<SyncTransactionQueue>()
    private val transitions = mock<Transitions>()
    private val displays = mock<DisplayController>()
    private val ime = mock<DisplayImeController>()
    private val insets = mock<DisplayInsetsController>()
    private val executor = mock<ShellExecutor>()
    private val split = mock<SplitLayout>()
    private val pendingIntent = mock<PendingIntent>()
    private val createdDividers = mutableListOf<SplitLayout>()
    private val dividerHandlers = mutableListOf<SplitLayout.SplitLayoutHandler>()
    private val token = Binder()
    private lateinit var coordinator: MultiSplitCoordinator
    private lateinit var pair: List<StageTaskListener>
    private lateinit var spare: List<StageTaskListener>

    private fun taskToken(): WindowContainerToken {
        val container = mock<IWindowContainerToken>()
        whenever(container.asBinder()).thenReturn(Binder())
        return WindowContainerToken(container)
    }

    private fun stage(id: Int): StageTaskListener = mock<StageTaskListener>().also { stage ->
        stage.mHasRootTask = true
        stage.mRootTaskInfo = RunningTaskInfo().apply {
            taskId = 100 + id
            displayId = 0
            isVisible = true
            token = taskToken()
        }
        stage.mChildrenTaskInfo = SparseArray()
        val task = RunningTaskInfo().apply {
            taskId = id
            token = taskToken()
        }
        whenever(stage.topChildTaskId).thenReturn(id)
        whenever(stage.childCount).thenReturn(1)
        whenever(stage.containsTask(id)).thenReturn(true)
        whenever(organizer.getRunningTaskInfo(id)).thenReturn(task)
    }

    @Before fun setup() {
        mContext.getOrCreateTestableResources().addOverride(
            com.android.internal.R.dimen.default_minimal_size_resizable_task, 100)
        whenever(host.groups()).thenReturn(groups)
        pair = listOf(stage(10), stage(20))
        spare = listOf(stage(30), stage(40))
        val rootInfo = RunningTaskInfo().apply {
            taskId = 1
            displayId = 0
            isVisible = true
            token = taskToken()
        }
        whenever(host.rootInfo()).thenReturn(rootInfo)
        whenever(host.pairStages()).thenReturn(pair)
        whenever(host.spareStages(any())).thenReturn(spare)
        whenever(host.capacity()).thenReturn(4)
        whenever(host.canEnter()).thenReturn(true)
        whenever(host.pairLayout()).thenReturn(split)
        whenever(split.rootBounds).thenReturn(Rect(0, 0, 3200, 2400))
        whenever(split.topLeftBounds).thenReturn(Rect(0, 0, 1596, 2400))
        whenever(split.dividerSize).thenReturn(8)
        whenever(split.isLeftRightSplit).thenReturn(true)
        whenever(displays.getDisplayContext(0)).thenReturn(context)
        whenever(transitions.startTransition(any(), any(), any())).thenReturn(token)
        whenever(host.newDivider(any(), any())).thenAnswer {
            dividerHandlers.add(it.arguments[0] as SplitLayout.SplitLayoutHandler)
            mock<SplitLayout>().also { divider ->
                whenever(divider.rootBounds).thenReturn(Rect())
                createdDividers.add(divider)
            }
        }
        coordinator = MultiSplitCoordinator(host, organizer, sync, transitions,
            displays, ime, insets, executor)
    }

    private fun add(): AppDragSlot {
        val slot = coordinator.getSlots(0).first { !it.occupied }
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
        coordinator.onTransitionFinished(token, false)
        return slot
    }

    @Test fun insertTwoToThreeToFourThenReplaceMaintainsFourRoots() {
        add()
        assertThat(coordinator.activeStages).hasSize(3)
        add()
        assertThat(coordinator.activeStages).hasSize(4)
        val replacement = coordinator.getSlots(0).first()
        assertThat(replacement.occupied).isTrue()
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), replacement)).isTrue()
        assertThat(coordinator.activeStages).hasSize(4)
    }

    @Test fun insertionBoundsAndAllAdjacencyAreInLaunchTransaction() {
        val slot = coordinator.getSlots(0).first()
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
        val transaction = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(any(), transaction.capture(), any())
        val wct = transaction.firstValue
        val change = wct.changes[spare[0].mRootTaskInfo.token.asBinder()]!!
        assertThat(change.configuration.windowConfiguration.bounds).isEqualTo(slot.bounds)
        val adjacent = wct.hierarchyOps.single {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_ADJACENT_ROOTS
        }
        assertThat(adjacent.containers).asList().containsExactly(
            pair[0].mRootTaskInfo.token.asBinder(), pair[1].mRootTaskInfo.token.asBinder(),
            spare[0].mRootTaskInfo.token.asBinder())
        assertThat(wct.changes).containsKey(pair[0].mRootTaskInfo.token.asBinder())
        assertThat(wct.changes).containsKey(pair[1].mRootTaskInfo.token.asBinder())
        verify(host).started()
    }

    @Test fun stalePreviewAndOverlappingOperationsAreRejected() {
        val stale = coordinator.getSlots(0).first()
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), stale)).isTrue()
        assertThat(coordinator.getSlots(0)).isEmpty()
        assertThat(coordinator.closeTask(10)).isFalse()
        assertThat(coordinator.swapLeaves(0, 1)).isFalse()
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), stale)).isFalse()
    }

    @Test fun closeCollapsesFourToThreeToTwoThenFullscreen() {
        add()
        add()
        assertThat(coordinator.closeTask(40)).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.activeStages).hasSize(3)
        assertThat(coordinator.closeTask(30)).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.activeStages).hasSize(2)
        assertThat(coordinator.closeTask(10)).isTrue()
        assertThat(coordinator.isActive).isFalse()
        verify(host).ended(any())
    }

    @Test fun swapMovesStagesBetweenStableSlotsAndKeepsTreeBounds() {
        add()
        add()
        val before = coordinator.getSlots(0).associate { it.leafId to it.bounds }
        assertThat(coordinator.swapLeaves(0, 2)).isTrue()
        coordinator.onTransitionFinished(token, false)
        val after = coordinator.getSlots(0).associate { it.leafId to it.bounds }
        assertThat(after).isEqualTo(before)
        assertThat(coordinator.activeStages[0]).isSameInstanceAs(spare[0])
    }

    @Test fun smallerDisplayReducesToTwoAndClearsRemovedAdjacency() {
        add()
        add()
        val wct = WindowContainerTransaction()
        coordinator.onConfigurationChanged(Configuration().apply { smallestScreenWidthDp = 599 }, wct)
        assertThat(coordinator.activeStages).hasSize(2)
        assertThat(wct.hierarchyOps.count {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_CLEAR_ADJACENT_ROOTS
        }).isEqualTo(2)
    }

    @Test fun capacityAndDisplayGatesRejectInsertion() {
        whenever(host.capacity()).thenReturn(2)
        assertThat(coordinator.getSlots(0)).isEmpty()
        assertThat(coordinator.getSlots(1)).isEmpty()
    }
    @Test fun taskDeathCollapsesOnlyItsLeafAfterTheTransitionIsIdle() {
        add()
        whenever(spare[0].childCount).thenReturn(0)
        coordinator.onTaskChanged(spare[0], 30, false)
        val scheduled = argumentCaptor<Runnable>()
        verify(executor).execute(scheduled.capture())
        scheduled.firstValue.run()
        val idle = argumentCaptor<Runnable>()
        verify(transitions).runOnIdle(idle.capture())
        idle.firstValue.run()
        assertThat(coordinator.activeStages).hasSize(2)
        assertThat(coordinator.isActive).isTrue()
    }

    @Test fun closedOriginalStageCanBeReusedForLaterInsertions() {
        add()
        assertThat(coordinator.closeTask(10)).isTrue()
        coordinator.onTransitionFinished(token, false)
        add()
        add()
        assertThat(coordinator.activeStages).hasSize(4)
    }

    @Test fun appRootsAndPreviewsReachDisplayEdgesDespiteSystemBarInsets() {
        val display = mock<DisplayLayout>()
        whenever(display.stableInsets()).thenReturn(Rect(0, 88, 0, 160))
        whenever(displays.getDisplayLayout(0)).thenReturn(display)
        val slot = coordinator.getSlots(0).first()
        assertThat(slot.bounds.top).isEqualTo(0)
        assertThat(slot.bounds.left).isEqualTo(0)
        add()
        add()
        val leaves = coordinator.getSlots(0).map { it.bounds }
        assertThat(leaves.minOf { it.top }).isEqualTo(0)
        assertThat(leaves.maxOf { it.bottom }).isEqualTo(2400)
        assertThat(leaves.maxOf { it.right }).isEqualTo(3200)
    }

    @Test fun directionalSwapTargetsFollowRowsAndColumnsAndKeepGeometry() {
        add()
        val unsplit = coordinator.getSwapTargets(20)!!
        assertThat(unsplit.horizontal).isAtLeast(0)
        assertThat(unsplit.vertical).isEqualTo(-1)
        add()
        val source = coordinator.getSwapTargets(20)!!
        assertThat(source.source).isEqualTo(1)
        assertThat(source.horizontal).isEqualTo(0)
        assertThat(source.vertical).isEqualTo(3)
        val before = coordinator.getSlots(0).associate { it.leafId to it.bounds }
        assertThat(coordinator.swapLeaves(source.source, source.horizontal)).isTrue()
        coordinator.onTransitionFinished(token, false)
        val moved = coordinator.getSwapTargets(20)!!
        assertThat(moved.source).isEqualTo(0)
        assertThat(moved.horizontal).isEqualTo(1)
        assertThat(moved.vertical).isEqualTo(2)
        assertThat(coordinator.swapLeaves(moved.source, moved.vertical)).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.getSlots(0).associate { it.leafId to it.bounds }).isEqualTo(before)
    }

    @Test fun topBottomRootExposesBothAxesAndTheOwningLeafBounds() {
        whenever(split.rootBounds).thenReturn(Rect(0, 0, 2400, 3200))
        whenever(split.topLeftBounds).thenReturn(Rect(0, 0, 2400, 1596))
        whenever(split.isLeftRightSplit).thenReturn(false)
        add()
        val unsplit = coordinator.getSwapTargets(20)!!
        assertThat(unsplit.horizontal).isEqualTo(-1)
        assertThat(unsplit.vertical).isAtLeast(0)
        add()
        val source = coordinator.getSwapTargets(20)!!
        assertThat(source.horizontal).isEqualTo(3)
        assertThat(source.vertical).isEqualTo(0)
        val rect = Rect()
        assertThat(coordinator.getTaskBounds(20, rect)).isTrue()
        assertThat(rect).isEqualTo(Rect(1204, 1604, 2400, 3200))
        assertThat(coordinator.getTaskBounds(-1, rect)).isFalse()
    }

    @Test fun dividerWindowsFollowBranchesAndRetireOnCollapseAndRotation() {
        add()
        assertThat(createdDividers).hasSize(2)
        verify(createdDividers[0]).setMultiSplitDivider(Rect(0, 0, 1596, 2400), false, 0.5f)
        add()
        assertThat(createdDividers).hasSize(3)
        verify(createdDividers[2]).setMultiSplitDivider(Rect(1604, 0, 3200, 2400), false, 0.5f)
        assertThat(coordinator.closeTask(40)).isTrue()
        coordinator.onTransitionFinished(token, false)
        verify(createdDividers[2]).release(null)
        whenever(split.rootBounds).thenReturn(Rect(0, 0, 2400, 3200))
        whenever(split.isLeftRightSplit).thenReturn(false)
        coordinator.onConfigurationChanged(Configuration().apply { smallestScreenWidthDp = 600 },
            WindowContainerTransaction())
        verify(createdDividers[0]).release()
        verify(createdDividers[1]).release()
        assertThat(createdDividers).hasSize(5)
        verify(createdDividers[3]).setMultiSplitDivider(Rect(0, 0, 2400, 1596), true, 0.5f)
    }

    @Test fun directionalSwapKeepsCorrespondingCellsWithIndependentBranchRatios() {
        add()
        add()
        whenever(createdDividers[0].branchRatio).thenReturn(0.85f)
        dividerHandlers[0].onLayoutSizeChanged(createdDividers[0])
        coordinator.onTransitionFinished(token, false)
        whenever(createdDividers[2].branchRatio).thenReturn(0.15f)
        dividerHandlers[2].onLayoutSizeChanged(createdDividers[2])
        coordinator.onTransitionFinished(token, false)
        val rightBottom = coordinator.getSwapTargets(20)!!
        assertThat(rightBottom.horizontal).isEqualTo(0)
        assertThat(rightBottom.vertical).isEqualTo(3)
        val leftTop = coordinator.getSwapTargets(30)!!
        assertThat(leftTop.horizontal).isEqualTo(3)
        assertThat(leftTop.vertical).isEqualTo(0)
    }

    @Test fun pairLaunchRestoresAllMembersFromAnInactiveSnapshotInOneTransaction() {
        add()
        add()
        val group = groups.get(10)!!
        val before = group.layout.root
        coordinator.exit(-1, WindowContainerTransaction())
        assertThat(coordinator.isActive).isFalse()
        val remote = mock<IRemoteTransition>()
        assertThat(coordinator.restorePair(group.secondRepresentative, group.firstRepresentative,
            null, null, RemoteTransition(remote))).isTrue()
        assertThat(coordinator.activeStages).hasSize(4)
        val wcts = argumentCaptor<WindowContainerTransaction>()
        verify(transitions, times(3)).startTransition(any(), wcts.capture(), any())
        val restore = wcts.lastValue
        val reparented = restore.hierarchyOps.filter {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REPARENT
        }.map { it.container }
        listOf(10, 20, 30, 40).forEach {
            assertThat(reparented).contains(organizer.getRunningTaskInfo(it)!!.token.asBinder())
        }
        assertThat(groups.get(10)!!.layout.root).isEqualTo(before)
        coordinator.onTransitionConsumed(token, false, null)
        verify(remote).onTransitionConsumed(token, false)
    }

    @Test fun pairLaunchWithoutRemoteAndSingleCardLaunchRestoreTheSameGroup() {
        add()
        val group = groups.get(10)!!
        assertThat(coordinator.restorePair(group.firstRepresentative, group.secondRepresentative,
            null, null, null)).isTrue()
        coordinator.onTransitionFinished(token, false)
        coordinator.exit(-1, WindowContainerTransaction())
        val task = organizer.getRunningTaskInfo(30)!!
        task.configuration.windowConfiguration.windowingMode =
            android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
        task.parentTaskId = -1
        val request = TransitionRequestInfo(TRANSIT_TO_FRONT, task, null)
        val restore = coordinator.restoreRequest(Binder(), request)
        assertThat(restore).isNotNull()
        assertThat(coordinator.activeStages).hasSize(3)
        assertThat(restore!!.hierarchyOps.any {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_ADJACENT_ROOTS &&
                it.containers.size == 3
        }).isTrue()
    }

    @Test fun staleGroupWithLaunchableRepresentativesReturnsToOrdinaryPairStarter() {
        add()
        val group = groups.get(10)!!
        whenever(organizer.getRunningTaskInfo(30)).thenReturn(null)
        val remote = mock<IRemoteTransition>()
        assertThat(coordinator.restorePair(group.firstRepresentative, group.secondRepresentative,
            null, null, RemoteTransition(remote))).isFalse()
        val wct = WindowContainerTransaction()
        assertThat(coordinator.preparePairLaunch(wct, RemoteTransition(remote))).isTrue()
        assertThat(coordinator.isActive).isFalse()
        assertThat(wct.hierarchyOps.none {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_LAUNCH_TASK
        }).isTrue()
        assertThat(groups.peek(10)).isNull()
        verify(remote, never()).onTransitionConsumed(any(), any())
    }

    @Test fun explicitFullscreenRequestDoesNotRestoreAndClearsMembership() {
        add()
        val fullscreen = TransitionRequestInfo.FullscreenRequestChange(
            Activity.FULLSCREEN_MODE_REQUEST_ENTER, null)
        val request = TransitionRequestInfo(TRANSIT_TO_FRONT, organizer.getRunningTaskInfo(10),
            null, null, null, null, null, null, fullscreen, 0, -1)
        assertThat(coordinator.restoreRequest(Binder(), request)).isNotNull()
        assertThat(coordinator.isActive).isFalse()
        assertThat(groups.peek(10)).isNull()
    }

    @Test fun listenerReportsOnlyRepresentativeMainAndSideIdentities() {
        add()
        add()
        val group = groups.get(10)!!
        val listener = mock<SplitScreen.SplitScreenListener>()
        coordinator.sendStatus(listener)
        val stages = argumentCaptor<Int>()
        val ids = argumentCaptor<Int>()
        verify(listener, times(2)).onTaskStageChanged(ids.capture(), stages.capture(), eq(true))
        assertThat(stages.allValues).containsExactly(SplitScreen.STAGE_TYPE_MAIN,
            SplitScreen.STAGE_TYPE_SIDE)
        assertThat(ids.allValues).containsExactly(group.firstRepresentative, group.secondRepresentative)
    }

    @Test fun returnToAppsRetainsAllRootsInFinishTransaction() {
        add()
        add()
        val wct = WindowContainerTransaction()
        coordinator.finishRecents(true, wct, mock<SurfaceControl.Transaction>())
        assertThat(coordinator.activeStages).hasSize(4)
        assertThat(wct.hierarchyOps.any {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_ADJACENT_ROOTS &&
                it.containers.size == 4
        }).isTrue()
        assertThat(coordinator.isReorderingToTop(wct)).isTrue()
    }

    @Test fun pairRestoreDuringOverviewRunsButAnOverlappingMutationIsRejected() {
        add()
        add()
        val group = groups.get(10)!!
        val recents = Binder()
        val info = android.window.TransitionInfo(0, 0)
        coordinator.onTransitionReady(recents, info, mock(), mock())
        coordinator.startRecents(info)
        assertThat(coordinator.restorePair(group.firstRepresentative, group.secondRepresentative,
            null, null, null)).isTrue()
        val rejected = mock<IRemoteTransition>()
        assertThat(coordinator.restorePair(group.firstRepresentative, group.secondRepresentative,
            null, null, RemoteTransition(rejected))).isTrue()
        verify(rejected).onTransitionConsumed(any(), eq(true))
    }

    @Test fun overviewSingleAndPairDismissalsCollapseOnlyRemovedMembers() {
        add()
        add()
        assertThat(groups.get(10)!!.members).hasSize(4)
        assertThat(coordinator.closeTask(30)).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.activeStages).hasSize(3)
        assertThat(groups.get(10)!!.members.map { it.taskId }).containsExactly(10, 20, 40)
        assertThat(coordinator.closeTask(10)).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.closeTask(20)).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.isActive).isFalse()
    }

    @Test fun losingTheOrganizerRootInvalidatesSavedGroupsAndRetiresTheSession() {
        add()
        coordinator.onRootVanished()
        assertThat(coordinator.isActive).isFalse()
        assertThat(groups.peek(10)).isNull()
    }

    @Test fun invalidatedPairCleansUpWithoutLaunchingFullscreenTasks() {
        add()
        val group = groups.get(10)!!
        groups.remove(10)
        assertThat(coordinator.restorePair(group.firstRepresentative, group.secondRepresentative,
            null, null, null)).isFalse()
        val wct = WindowContainerTransaction()
        assertThat(coordinator.preparePairLaunch(wct, null)).isTrue()
        assertThat(coordinator.isActive).isFalse()
        assertThat(wct.hierarchyOps.none {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_LAUNCH_TASK
        }).isTrue()
        verify(transitions).startTransition(any(), any(), any())
    }

    @Test fun shellRestoreAnimationConsumesRemoteAndFinishesExactlyOnce() {
        add()
        val group = groups.get(10)!!
        val remote = mock<IRemoteTransition>()
        assertThat(coordinator.restorePair(group.firstRepresentative, group.secondRepresentative,
            null, null, RemoteTransition(remote))).isTrue()
        val callback = mock<Transitions.TransitionFinishCallback>()
        assertThat(coordinator.startAnimation(token, android.window.TransitionInfo(0, 0),
            mock(), mock(), callback)).isTrue()
        val callbacks = argumentCaptor<Runnable>()
        verify(executor, atLeastOnce()).execute(callbacks.capture())
        callbacks.allValues.last().run()
        coordinator.onTransitionConsumed(token, false, null)
        coordinator.onTransitionFinished(token, false)
        verify(remote).onTransitionConsumed(token, false)
        verify(callback).onTransitionFinished(null)
    }

    @Test fun missingSavedStageRootInvalidatesTheRecentsProjection() {
        add()
        spare[0].mHasRootTask = false
        assertThat(groups.get(10)).isNull()
        assertThat(groups.peek(20)).isNull()
    }
    @Test fun downgradePublishesRemainingPairWithCapacityTwo() {
        add()
        add()
        whenever(host.capacity()).thenReturn(2)
        coordinator.onConfigurationChanged(Configuration().apply { smallestScreenWidthDp = 599 },
            WindowContainerTransaction())
        val group = coordinator.publishGroup()!!
        assertThat(group.members).hasSize(2)
        assertThat(groups.get(group.firstRepresentative)).isSameInstanceAs(group)
        assertThat(groups.get(group.secondRepresentative)).isSameInstanceAs(group)
        assertThat(group.stageIds.values).containsExactlyElementsIn(
            coordinator.activeStages.map { it.mRootTaskInfo.taskId })
    }

    @Test fun swapRejectsDestinationBelowTaskMinimumWithoutChangingStagesOrRatios() {
        add()
        organizer.getRunningTaskInfo(20)!!.minHeight = 1500
        val before = Rect().also { assertThat(coordinator.getTaskBounds(20, it)).isTrue() }
        val stages = coordinator.activeStages.toList()
        assertThat(coordinator.swapLeaves(1, 0)).isFalse()
        assertThat(coordinator.activeStages).containsExactlyElementsIn(stages).inOrder()
        val after = Rect().also { assertThat(coordinator.getTaskBounds(20, it)).isTrue() }
        assertThat(after).isEqualTo(before)
        verify(transitions).startTransition(any(), any(), any())
    }

    @Test fun rootDividerRejectsTaskWidthViolationButAcceptsFeasibleRatio() {
        add()
        organizer.getRunningTaskInfo(20)!!.minWidth = 1000
        val before = Rect().also { coordinator.getTaskBounds(20, it) }
        val divider = createdDividers.last()
        val handler = dividerHandlers.last()
        whenever(divider.branchRatio).thenReturn(0.9f)
        handler.onDividerInteractionChanged(true)
        handler.onLayoutSizeChanging(divider, 0, 0, false)
        val preview = Rect().also { coordinator.getTaskBounds(20, it) }
        assertThat(preview).isEqualTo(before)
        handler.onLayoutSizeChanged(divider)
        val rejected = Rect().also { coordinator.getTaskBounds(20, it) }
        assertThat(rejected).isEqualTo(before)
        verify(transitions).startTransition(any(), any(), any())
        whenever(divider.branchRatio).thenReturn(0.6f)
        handler.onDividerInteractionChanged(true)
        handler.onLayoutSizeChanged(divider)
        val accepted = Rect().also { coordinator.getTaskBounds(20, it) }
        assertThat(accepted.width()).isAtLeast(1000)
        assertThat(accepted.width()).isLessThan(before.width())
        verify(transitions, times(2)).startTransition(any(), any(), any())
    }

    @Test fun nestedDividerRejectsTaskHeightViolationDuringPreviewAndCommit() {
        add()
        organizer.getRunningTaskInfo(10)!!.minHeight = 900
        val before = Rect().also { coordinator.getTaskBounds(10, it) }
        val divider = createdDividers.first()
        val handler = dividerHandlers.first()
        whenever(divider.branchRatio).thenReturn(0.9f)
        handler.onDividerInteractionChanged(true)
        handler.onLayoutSizeChanging(divider, 0, 0, false)
        handler.onLayoutSizeChanged(divider)
        val after = Rect().also { coordinator.getTaskBounds(10, it) }
        assertThat(after).isEqualTo(before)
        verify(transitions).startTransition(any(), any(), any())
    }


}
