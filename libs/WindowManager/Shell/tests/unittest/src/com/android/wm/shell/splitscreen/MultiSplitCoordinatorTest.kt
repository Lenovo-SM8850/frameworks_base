// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import android.app.ActivityManager.RunningTaskInfo
import android.app.PendingIntent
import android.app.ActivityOptions
import android.app.Activity
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.IRemoteTransitionFinishedCallback
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
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
    private var originalSmallestWidth = 0

    @After fun restoreResourceConfiguration() {
        mContext.resources.configuration.smallestScreenWidthDp = originalSmallestWidth
    }

    @Test fun rotateColumnsGuardsGrowthAndRestoresOnlyColumnGroups() {
        mContext.resources.configuration.smallestScreenWidthDp = 600
        add()
        assertThat(coordinator.rotateThreeAppShape()).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(groups.get(10)!!.layout.isThreeColumns).isTrue()
        val columns = groups.get(10)!!.layout
        assertThat(coordinator.getSlots(0).all { it.occupied }).isTrue()
        assertThat(coordinator.getBelowSlot(10)).isNull()
        val occupied = coordinator.getSlots(0).first()
        val fabricated = AppDragSlot(occupied.position, 9, occupied.bounds, occupied.bounds,
            false, occupied.leafId, false, occupied.layoutVersion)
        assertThat(coordinator.startIntent(pendingIntent, null, null, fabricated)).isFalse()
        whenever(split.rootBounds).thenReturn(Rect(0, 0, 2400, 3200))
        whenever(split.isLeftRightSplit).thenReturn(false)
        coordinator.onConfigurationChanged(Configuration().apply { smallestScreenWidthDp = 600 },
            WindowContainerTransaction())
        assertThat(groups.get(10)!!.layout.isThreeColumns).isFalse()
        assertThat(groups.get(10)!!.layout.threeColumnGroup).isTrue()
        assertThat(coordinator.getSlots(0).all { it.occupied }).isTrue()
        whenever(split.rootBounds).thenReturn(Rect(0, 0, 3200, 2400))
        whenever(split.isLeftRightSplit).thenReturn(true)
        coordinator.onConfigurationChanged(Configuration().apply { smallestScreenWidthDp = 600 },
            WindowContainerTransaction())
        assertThat(groups.get(10)!!.layout.root).isEqualTo(columns.root)
        assertThat(coordinator.rotateThreeAppShape()).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(groups.get(10)!!.layout.threeColumnGroup).isFalse()
        whenever(split.isLeftRightSplit).thenReturn(false)
        coordinator.onConfigurationChanged(Configuration().apply { smallestScreenWidthDp = 600 },
            WindowContainerTransaction())
        whenever(split.isLeftRightSplit).thenReturn(true)
        coordinator.onConfigurationChanged(Configuration().apply { smallestScreenWidthDp = 600 },
            WindowContainerTransaction())
        assertThat(groups.get(10)!!.layout.isThreeColumns).isFalse()
    }

    @Test fun columnShapeIsUnavailableOnSmallScreensOrPortraitOrFourApps() {
        add()
        mContext.resources.configuration.smallestScreenWidthDp = 599
        assertThat(coordinator.rotateThreeAppShape()).isFalse()
        mContext.resources.configuration.smallestScreenWidthDp = 600
        whenever(split.rootBounds).thenReturn(Rect(0, 0, 2400, 3200))
        assertThat(coordinator.rotateThreeAppShape()).isFalse()
        whenever(split.rootBounds).thenReturn(Rect(0, 0, 3200, 2400))
        add()
        assertThat(coordinator.rotateThreeAppShape()).isFalse()
    }

    @Test fun rootHandleMirrorsThreeAppsAndInnerHandleSwapsPair() {
        mContext.resources.configuration.smallestScreenWidthDp = 600
        add()
        val before = groups.get(10)!!.layout
        val rootHandler = dividerHandlers.last()
        rootHandler.onDividerHandleTapped(0)
        coordinator.onTransitionFinished(token, false)
        val mirrored = groups.get(10)!!.layout
        assertThat(mirrored.root.first).isEqualTo(before.root.second)
        assertThat(mirrored.root.second).isEqualTo(before.root.first)
        assertThat(coordinator.activeStages).containsExactlyElementsIn(pair + spare.take(1))
        assertThat(coordinator.mirror()).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(groups.get(10)!!.layout.root).isEqualTo(before.root)
        val stagesBeforeSwap = coordinator.activeStages
        dividerHandlers.last { it.canRotateThreeAppShape() }.onDividerHandleTapped(0)
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.activeStages.zip(stagesBeforeSwap).count { (a, b) -> a !== b })
            .isEqualTo(2)
    }

    @Test fun replaceVisibleMemberSwapsStagesWithNoEvictionAndRejectsStaleSlot() {
        add()
        val slot = coordinator.getReplacementSlot(10)!!
        val task = organizer.getRunningTaskInfo(20)!!.apply { supportsMultiWindow = true }
        val before = groups.get(10)!!.layout
        org.mockito.Mockito.clearInvocations(transitions)
        assertThat(coordinator.startTask(task, slot)).isTrue()
        val wct = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(any(), wct.capture(), any())
        assertThat(wct.firstValue.hierarchyOps.none {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_LAUNCH_TASK ||
                it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REMOVE_TASK
        }).isTrue()
        coordinator.onTransitionFinished(token, false)
        val after = groups.get(10)!!.layout
        assertThat(after.getLeaves().first { it.taskId == 20 }.id)
            .isEqualTo(before.getLeaves().first { it.taskId == 10 }.id)
        assertThat(coordinator.startTask(task, slot)).isFalse()
        assertThat(coordinator.activeStages).hasSize(3)
    }

    @Test fun captionCollapseReturnsToOrdinaryPairWithTheCallersTransaction() {
        add()
        whenever(host.returnToPair(any(), any(), any())).thenReturn(true)
        assertThat(coordinator.closeTask(30)).isTrue()
        val wct = argumentCaptor<WindowContainerTransaction>()
        verify(host).returnToPair(eq(pair), eq(0.5f), wct.capture())
        assertThat(coordinator.isActive).isFalse()
        assertThat(wct.firstValue.hierarchyOps.any {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REMOVE_TASK
        }).isTrue()
    }

    @Test fun restoringAnOlderTwoAppGroupReturnsItToOrdinaryPairMode() {
        add()
        assertThat(coordinator.closeTask(30)).isTrue()
        coordinator.onTransitionFinished(token, false)
        val saved = groups.get(10)!!
        assertThat(saved.members).hasSize(2)
        coordinator.exit(-1, WindowContainerTransaction())
        whenever(host.returnToPair(any(), any(), any())).thenReturn(true)
        org.mockito.Mockito.clearInvocations(host)
        assertThat(coordinator.restorePair(saved.firstRepresentative, saved.secondRepresentative,
            null, null, null)).isTrue()
        verify(host).returnToPair(eq(pair), eq(0.5f), any())
        assertThat(coordinator.isActive).isFalse()
        assertThat(groups.peek(10)).isNull()
        coordinator.onTransitionFinished(token, false)
    }

    @Test fun dividerDismissCallbackCannotCloseAnyBranch() {
        add()
        add()
        val before = coordinator.activeStages
        org.mockito.Mockito.clearInvocations(transitions)
        repeat(3) { index ->
            val handler = dividerHandlers.takeLast(3)[index]
            handler.onDividerInteractionChanged(true)
            handler.onSnappedToDismiss(index % 2 == 0, SplitScreenController.EXIT_REASON_DRAG_DIVIDER,
                WindowContainerTransaction())
        }
        assertThat(coordinator.activeStages).containsExactlyElementsIn(before)
        verify(transitions, never()).startTransition(any(), any(), any())
    }

    @Test fun replacementSlotUsesTheWholeSingleHalfAndEveryQuarter() {
        add()
        for (taskId in listOf(10, 20, 30)) {
            val destination = coordinator.getReplacementSlot(taskId)!!
            val before = coordinator.activeStages
            assertThat(destination.occupied).isTrue()
            assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), destination)).isTrue()
            coordinator.onTransitionFinished(token, false)
            assertThat(coordinator.activeStages).containsExactlyElementsIn(before)
        }
        assertThat(coordinator.getBelowSlot(20)).isNotNull()
    }

    @Test fun rootAndInnerHandleCallbacksSwapOnlyTheirExistingNeighbourApps() {
        add()
        add()
        for (handler in dividerHandlers.toList()) {
            val before = coordinator.activeStages
            handler.onDoubleTappedDivider()
            coordinator.onTransitionFinished(token, false)
            val after = coordinator.activeStages
            assertThat(after).containsExactlyElementsIn(before)
            assertThat(after.zip(before).count { (a, b) -> a !== b }).isEqualTo(2)
        }
    }

    @Test
    @android.platform.test.annotations.EnableFlags(com.android.wm.shell.Flags.FLAG_ENABLE_FLEXIBLE_TWO_APP_SPLIT)
    fun addingToExpandedHalfRetainsStripAndStripTapKeepsDoubleHalfOnscreen() {
        whenever(split.dividerPositionAsFraction).thenReturn(0.1f)
        val slot = coordinator.getBelowSlot(20)!!
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
        coordinator.onTransitionFinished(token, false)
        val narrow = groups.get(10)!!.layout
        assertThat(narrow.strip).isEqualTo(MultiSplitLayout.LeafId(0))
        assertThat(narrow.root.ratio).isEqualTo(0.1f)
        coordinator.setDividerVisible(false, null)
        assertThat(coordinator.expandStrip()).isFalse()
        coordinator.setDividerVisible(true, null)
        assertThat(coordinator.expandStrip()).isTrue()
        coordinator.onTransitionFinished(token, false)
        val expanded = groups.get(10)!!.layout
        assertThat(expanded.strip).isNull()
        assertThat(expanded.root.ratio).isEqualTo(0.7f)
        assertThat(expanded.getLeaves().map { it.taskId }).containsExactly(10, 20, 30)
    }

    @Test
    @android.platform.test.annotations.EnableFlags(com.android.wm.shell.Flags.FLAG_ENABLE_FLEXIBLE_TWO_APP_SPLIT)
    fun addingToStripNormalizesBeforePreparingTheNewStage() {
        whenever(split.dividerPositionAsFraction).thenReturn(0.1f)
        val slot = coordinator.getBelowSlot(10)!!
        assertThat(slot.bounds.width()).isEqualTo(1596)
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(groups.get(10)!!.layout.strip).isNull()
        assertThat(groups.get(10)!!.layout.root.ratio).isEqualTo(0.5f)
    }

    @Test
    @android.platform.test.annotations.EnableFlags(com.android.wm.shell.Flags.FLAG_ENABLE_FLEXIBLE_TWO_APP_SPLIT)
    fun growingStripThreeToFourNormalizesTheRoot() {
        whenever(split.dividerPositionAsFraction).thenReturn(0.1f)
        val first = coordinator.getBelowSlot(20)!!
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), first)).isTrue()
        coordinator.onTransitionFinished(token, false)
        val second = coordinator.getBelowSlot(10)!!
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), second)).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(groups.get(10)!!.layout.strip).isNull()
        assertThat(groups.get(10)!!.layout.root.ratio).isEqualTo(0.5f)
    }

    @Test fun legacyInvalidSnapshotIsRefusedBeforeAnyRestoreTransaction() {
        add()
        val saved = groups.get(10)!!
        coordinator.exit(-1, WindowContainerTransaction())
        val invalid = mock<MultiSplitLayout>()
        val child = saved.layout.root.first as MultiSplitLayout.Split
        whenever(invalid.root).thenReturn(saved.layout.root.copy(orientation = child.orientation))
        val recordsField = MultiSplitGroupRepository::class.java.getDeclaredField("records")
        recordsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val records = recordsField.get(groups) as MutableMap<Int, MultiSplitGroupRepository.Group>
        saved.members.forEach { records[it.taskId] = saved.copy(layout = invalid) }
        org.mockito.Mockito.clearInvocations(transitions)
        assertThat(coordinator.restorePair(saved.firstRepresentative, saved.secondRepresentative,
            null, null, null)).isFalse()
        assertThat(groups.peek(10)).isNull()
        verify(transitions, never()).startTransition(any(), any(), any())
    }

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
        originalSmallestWidth = mContext.resources.configuration.smallestScreenWidthDp
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
                whenever(divider.firstNonDismissRatio).thenReturn(0.3f)
                whenever(divider.lastNonDismissRatio).thenReturn(0.7f)
                createdDividers.add(divider)
            }
        }
        coordinator = MultiSplitCoordinator(host, organizer, sync, transitions,
            displays, ime, insets, executor)
    }

    private fun add(): AppDragSlot {
        val slot = coordinator.getSlots(0).filter { !it.occupied }
            .maxBy { it.bounds.width().toLong() * it.bounds.height() }
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

    private fun closeOriginalColumn(): WindowContainerTransaction {
        whenever(split.rootBounds).thenReturn(Rect(0, 0, 1600, 2560))
        whenever(split.topLeftBounds).thenReturn(Rect(0, 0, 793, 2560))
        whenever(split.dividerSize).thenReturn(14)
        repeat(2) {
            val slot = coordinator.getSlots(0).filter { !it.occupied && !it.newTaskFirst }
                .maxBy { it.bounds.width().toLong() * it.bounds.height() }
            assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
            coordinator.onTransitionFinished(token, false)
        }
        // Put the original pair in the left column and the two spare roots in the right.
        assertThat(coordinator.swapLeaves(1, 2)).isTrue()
        coordinator.onTransitionFinished(token, false)
        // Simulate the production host moving both surviving spare tasks to canonical pair roots.
        whenever(host.returnToPair(any(), any(), any())).thenAnswer {
            val survivors = it.arguments[0] as List<StageTaskListener>
            survivors.forEachIndexed { index, source ->
                val id = source.topChildTaskId
                whenever(pair[index].topChildTaskId).thenReturn(id)
                whenever(pair[index].containsTask(id)).thenReturn(true)
            }
            true
        }
        // Initial spare attachment is expected; only subsequent stranded sync work is forbidden.
        org.mockito.Mockito.clearInvocations(sync)
        assertThat(coordinator.closeTask(10)).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.closeTask(20)).isTrue()
        val transactions = argumentCaptor<WindowContainerTransaction>()
        verify(transitions, times(5)).startTransition(any(), transactions.capture(), any())
        val wct = transactions.lastValue
        assertThat(coordinator.isActive).isFalse()
        assertThat(coordinator.activeStages).isEmpty()
        verify(host).returnToPair(eq(spare), eq(0.5f), eq(wct))
        coordinator.onTransitionFinished(token, false)
        return wct
    }

    private fun assertSaneStageBounds(wct: WindowContainerTransaction) {
        val root = split.rootBounds
        coordinator.activeStages.forEach { stage ->
            val bounds = wct.changes[stage.mRootTaskInfo.token.asBinder()]!!
                .configuration.windowConfiguration.bounds
            assertThat(bounds.width()).isGreaterThan(1)
            assertThat(bounds.height()).isGreaterThan(1)
            assertThat(root.contains(bounds)).isTrue()
        }
    }

    @Test fun captionCloseReturnsSurvivingSpareAppsToNormalPairWithoutStrandedSync() {
        val collapse = closeOriginalColumn()
        assertThat(coordinator.isActive).isFalse()
        assertThat(coordinator.activeStages).isEmpty()
        verify(host).returnToPair(eq(spare), eq(0.5f), eq(collapse))
        assertThat(collapse.hierarchyOps.count {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REMOVE_TASK
        }).isEqualTo(1)
        createdDividers.forEach { divider ->
            assertThat(org.mockito.Mockito.mockingDetails(divider).invocations.any {
                it.method.name == "release"
            }).isTrue()
        }
        verify(host, never()).ended(any())
        verify(sync, never()).queue(any<WindowContainerTransaction>())
    }

    @Test fun sidebarDropAfterCaptionCloseInsertsInOneOpenTransitionWithoutStrandedSync() {
        closeOriginalColumn()
        val slot = coordinator.getSlots(0).first { !it.occupied }
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
        assertThat(coordinator.activeStages).hasSize(3)
        val transactions = argumentCaptor<WindowContainerTransaction>()
        val types = argumentCaptor<Int>()
        verify(transitions, times(6)).startTransition(types.capture(), transactions.capture(), any())
        assertThat(types.lastValue).isEqualTo(android.view.WindowManager.TRANSIT_OPEN)
        assertSaneStageBounds(transactions.lastValue)
        assertThat(transactions.lastValue.hierarchyOps.count {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_PENDING_INTENT
        }).isEqualTo(1)
        verify(host, never()).ended(any())
        verify(split, never()).setDividerAtBorder(any())
        verify(sync, never()).queue(any<WindowContainerTransaction>())
    }

    @Test fun backgroundPairLaunchAfterCaptionCloseUsesTheCallersTransaction() {
        closeOriginalColumn()
        coordinator.setDividerVisible(false, null)
        assertThat(coordinator.isVisible).isFalse()
        val wct = WindowContainerTransaction()
        assertThat(coordinator.preparePairLaunch(wct, null)).isTrue()
        assertThat(coordinator.isActive).isFalse()
        assertThat(wct.isEmpty).isTrue()
        verify(host, never()).ended(any())
        createdDividers.forEach { divider ->
            assertThat(org.mockito.Mockito.mockingDetails(divider).invocations.any {
                it.method.name == "release"
            }).isTrue()
        }
        verify(sync, never()).queue(any<WindowContainerTransaction>())
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
        verify(createdDividers[0]).setMultiSplitDivider(Rect(0, 0, 1596, 2400), false, 0.5f, -1)
        add()
        assertThat(createdDividers).hasSize(3)
        verify(createdDividers[2]).setMultiSplitDivider(Rect(1604, 0, 3200, 2400), false, 0.5f, -1)
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
        verify(createdDividers[3]).setMultiSplitDivider(Rect(0, 0, 2400, 1596), true, 0.5f, -1)
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

    @Test fun pairCardLaunchRestoresButPlainTaskRequestDoesNot() {
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
        assertThat(restore).isNull()
        assertThat(coordinator.isActive).isFalse()
        assertThat(groups.peek(task.taskId)).isNull()
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

    @Test fun restoreAnimationStartsRemoteAndFinishesExactlyOnce() {
        add()
        val group = groups.get(10)!!
        val remote = mock<IRemoteTransition>()
        assertThat(coordinator.restorePair(group.firstRepresentative, group.secondRepresentative,
            null, null, RemoteTransition(remote))).isTrue()
        val callback = mock<Transitions.TransitionFinishCallback>()
        assertThat(coordinator.startAnimation(token, android.window.TransitionInfo(0, 0),
            mock(), mock(), callback)).isTrue()
        val remoteFinish = argumentCaptor<IRemoteTransitionFinishedCallback>()
        verify(remote).startAnimation(eq(token), any(), any(), remoteFinish.capture())
        verify(callback, never()).onTransitionFinished(anyOrNull())
        remoteFinish.firstValue.onTransitionFinished(null, null)
        val callbacks = argumentCaptor<Runnable>()
        verify(executor, atLeastOnce()).execute(callbacks.capture())
        callbacks.allValues.last().run()
        coordinator.onTransitionConsumed(token, false, null)
        coordinator.onTransitionFinished(token, false)
        verify(remote, never()).onTransitionConsumed(any(), any())
        verify(callback).onTransitionFinished(null)
    }

    @Test fun missingSavedStageRootInvalidatesTheRecentsProjection() {
        add()
        spare[0].mHasRootTask = false
        assertThat(groups.get(10)).isNull()
        assertThat(groups.peek(20)).isNull()
    }

    @Test fun addBelowKeepsCurrentTaskAboveAndInsertsInOneTransaction() {
        val slot = coordinator.getBelowSlot(20)!!
        assertThat(slot.splitBelow).isTrue()
        assertThat(slot.bounds).isEqualTo(Rect(1604, 1204, 3200, 2400))
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
        val transaction = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(any(), transaction.capture(), any())
        val wct = transaction.firstValue
        assertThat(wct.changes[pair[1].mRootTaskInfo.token.asBinder()]!!
            .configuration.windowConfiguration.bounds).isEqualTo(Rect(1604, 0, 3200, 1196))
        assertThat(wct.changes[pair[0].mRootTaskInfo.token.asBinder()]!!
            .configuration.windowConfiguration.bounds).isEqualTo(Rect(0, 0, 1596, 2400))
        assertThat(wct.changes[spare[0].mRootTaskInfo.token.asBinder()]!!
            .configuration.windowConfiguration.bounds).isEqualTo(slot.bounds)
    }

    @Test fun addActionRejectsQuartersAndHidesAtCapacity() {
        val right = coordinator.getBelowSlot(20)!!
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), right)).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.getBelowSlot(20)).isNull()
        val left = coordinator.getBelowSlot(10)!!
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), left)).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.getBelowSlot(10)).isNull()
        assertThat(coordinator.getBelowSlot(20)).isNull()
    }

    @Test fun stackedPairAddsOnEndSideThenRestoresFourLeavesInOneTransaction() {
        whenever(split.isLeftRightSplit).thenReturn(false)
        whenever(split.topLeftBounds).thenReturn(Rect(0, 0, 3200, 1196))
        val top = coordinator.getBelowSlot(10)!!
        assertThat(top.splitBelow).isFalse()
        assertThat(top.bounds).isEqualTo(Rect(1604, 0, 3200, 1196))
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), top)).isTrue()
        coordinator.onTransitionFinished(token, false)
        val bottom = coordinator.getBelowSlot(20)!!
        assertThat(bottom.bounds).isEqualTo(Rect(1604, 1204, 3200, 2400))
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), bottom)).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.getBelowSlot(20)).isNull()
        val group = groups.get(10)!!
        val before = group.layout.calculateBounds(Rect(0, 0, 3200, 2400), android.graphics.Insets.NONE, 8)
        val wct = WindowContainerTransaction()
        coordinator.exit(-1, wct)
        assertThat(coordinator.restorePair(group.firstRepresentative, group.secondRepresentative,
            null, null, null)).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.activeStages).hasSize(4)
        assertThat(groups.get(10)!!.layout.calculateBounds(Rect(0, 0, 3200, 2400),
            android.graphics.Insets.NONE, 8)).isEqualTo(before)
    }

    @Test fun addBelowRejectsInfeasibleExistingTaskAndCapabilityLoss() {
        organizer.getRunningTaskInfo(10)!!.minHeight = 1500
        assertThat(coordinator.getBelowSlot(10)).isNull()
        assertThat(coordinator.getBelowSlot(20)).isNotNull()
        whenever(host.capacity()).thenReturn(2)
        assertThat(coordinator.getBelowSlot(20)).isNull()
    }

    @Test fun floatingAndBackgroundReuseShareInsertionAndClearFloatingProperties() {
        val slot = coordinator.getBelowSlot(10)!!
        val floating = RunningTaskInfo().apply {
            taskId = 50
            displayId = 0
            token = taskToken()
            supportsMultiWindow = true
            isFloatingWindow = true
        }
        assertThat(coordinator.startTask(floating, slot)).isTrue()
        val transaction = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(any(), transaction.capture(), any())
        val wct = transaction.firstValue
        val launch = wct.hierarchyOps.single {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_LAUNCH_TASK
        }
        assertThat(ActivityOptions.fromBundle(launch.launchOptions).launchBounds).isNull()
        val change = wct.changes[floating.token.asBinder()]!!
        assertThat(change.configuration.windowConfiguration.windowingMode)
            .isEqualTo(android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED)
        assertThat(change.changeMask and WindowContainerTransaction.Change.CHANGE_FLOATING_WINDOW)
            .isNotEqualTo(0)
        assertThat(wct.hierarchyOps.any { it.container == floating.token.asBinder() &&
            it.newParent == spare[0].mRootTaskInfo.token.asBinder() }).isTrue()
    }

    @Test fun taskInsertionRejectsVisibleMemberMinimumsAndStaleSelection() {
        val slot = coordinator.getBelowSlot(10)!!
        val visible = organizer.getRunningTaskInfo(10)!!.apply { supportsMultiWindow = true }
        assertThat(coordinator.startTask(visible, slot)).isFalse()
        val incoming = RunningTaskInfo().apply {
            taskId = 50; displayId = 0; minHeight = 5000; supportsMultiWindow = true
        }
        assertThat(coordinator.startTask(incoming, slot)).isFalse()
        incoming.minHeight = 0
        incoming.supportsMultiWindow = false
        assertThat(coordinator.startTask(incoming, slot)).isFalse()
        incoming.supportsMultiWindow = true
        assertThat(coordinator.startTask(incoming, slot)).isTrue()
        assertThat(coordinator.startTask(incoming, slot)).isFalse()
    }

    @Test fun visibleGroupInsetsDoNotReorderTasksOrChangeVisibility() {
        assertInsetsOnlyUpdateBounds(true)
    }

    @Test fun hiddenGroupInsetsDoNotReorderTasksOrChangeVisibility() {
        assertInsetsOnlyUpdateBounds(false)
    }

    private fun assertInsetsOnlyUpdateBounds(visible: Boolean) {
        add()
        coordinator.setDividerVisible(visible, null)
        org.mockito.kotlin.clearInvocations(sync)
        coordinator.insetsChanged(android.view.InsetsState())
        runLastIdle()
        val wct = argumentCaptor<WindowContainerTransaction>()
        verify(sync).queue(wct.capture())
        assertGeometryOnly(wct.firstValue)
    }

    @Test fun memberKeyboardUpdatesBoundsWithoutReorderingTasks() {
        add()
        val processor = argumentCaptor<DisplayImeController.ImePositionProcessor>()
        verify(ime).addPositionProcessor(processor.capture())
        val target = organizer.getRunningTaskInfo(10)!!.token
        whenever(organizer.getImeLayeringTarget(0)).thenReturn(target)
        whenever(pair[0].containsToken(target)).thenReturn(true)
        val before = coordinator.getSlots(0).map { Rect(it.bounds) }
        processor.firstValue.onImeStartPositioning(0, 2400, 1800, true, false, mock())
        val wct = argumentCaptor<WindowContainerTransaction>()
        verify(organizer).applyTransaction(wct.capture())
        assertGeometryOnly(wct.firstValue)
        assertThat(coordinator.getSlots(0).map { it.bounds }).isNotEqualTo(before)
    }

    private fun assertGeometryOnly(wct: WindowContainerTransaction) {
        assertThat(wct.hierarchyOps).isEmpty()
        assertThat(wct.changes.keys).containsExactlyElementsIn(
            coordinator.activeStages.map { it.mRootTaskInfo.token.asBinder() })
        wct.changes.values.forEach { change ->
            assertThat(change.configuration.windowConfiguration.bounds.isEmpty).isFalse()
            assertThat(change.changeMask).isEqualTo(0)
        }
    }

    @Test fun pickerKeyboardDoesNotResizeGroupButMemberKeyboardStillDoes() {
        val slot = coordinator.getBelowSlot(20)!!
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
        coordinator.onTransitionFinished(token, false)
        val processor = argumentCaptor<DisplayImeController.ImePositionProcessor>()
        verify(ime).addPositionProcessor(processor.capture())
        processor.firstValue.onImeStartPositioning(0, 2400, 1800, true, false, mock())
        assertThat(coordinator.getBelowSlot(10)).isNotNull()
        verify(organizer, never()).applyTransaction(any())
        val target = organizer.getRunningTaskInfo(10)!!.token
        whenever(organizer.getImeLayeringTarget(0)).thenReturn(target)
        whenever(pair[0].containsToken(target)).thenReturn(true)
        processor.firstValue.onImeStartPositioning(0, 2400, 1800, true, false, mock())
        assertThat(coordinator.getBelowSlot(10)).isNull()
        verify(organizer).applyTransaction(any())
    }

    private fun dumpState(): String {
        val text = java.io.StringWriter()
        coordinator.dump(java.io.PrintWriter(text), "  ")
        return text.toString()
    }

    @Test fun firstMenuPreviewDoesNotWaitForAsynchronousSpareRoots() {
        spare.forEach { it.mHasRootTask = false }
        val slot = coordinator.getBelowSlot(20)!!
        assertThat(slot.bounds).isEqualTo(Rect(1604, 1204, 3200, 2400))
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isFalse()
        assertThat(dumpState()).contains("insertion=Spare roots not ready")
        spare.forEach { it.mHasRootTask = true }
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
    }

    @Test fun landscapeDevicePairOffersChromeLeafOnFirstQuery() {
        whenever(split.rootBounds).thenReturn(Rect(0, 0, 2560, 1600))
        whenever(split.topLeftBounds).thenReturn(Rect(0, 0, 1313, 1600))
        spare.forEach { it.mHasRootTask = false }
        val slot = coordinator.getBelowSlot(20)!!
        assertThat(slot.bounds).isEqualTo(Rect(1321, 804, 2560, 1600))
        assertThat(slot.splitBelow).isTrue()
        assertThat(dumpState()).contains("belowSlot=task=20 None")
    }

    @Test fun quarterDropReplacesWhileOtherHalfRemainsAvailableForInsertion() {
        val first = coordinator.getBelowSlot(10)!!
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), first)).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.getBelowSlot(10)).isNull()
        val replacement = coordinator.getSlots(0).first { it.occupied && it.leafId == 0 }
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), replacement)).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.activeStages).hasSize(3)
        assertThat(coordinator.getBelowSlot(20)).isNotNull()
        val fresh = coordinator.getSlots(0).first { it.occupied && it.leafId == 0 }
        val fabricated = AppDragSlot(fresh.position, fresh.index,
            fresh.bounds, fresh.hitBounds, false, fresh.leafId, false, fresh.layoutVersion)
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), fabricated)).isFalse()
    }

    @Test fun wideLeafInsertionUsesEndSideInRtl() {
        val config = Configuration(context.resources.configuration).apply {
            setLayoutDirection(java.util.Locale("ar"))
        }
        whenever(displays.getDisplayContext(0)).thenReturn(context.createConfigurationContext(config))
        whenever(split.isLeftRightSplit).thenReturn(false)
        whenever(split.topLeftBounds).thenReturn(Rect(0, 0, 3200, 1196))
        val slot = coordinator.getBelowSlot(10)!!
        assertThat(slot.newTaskFirst).isTrue()
        assertThat(slot.splitBelow).isFalse()
        assertThat(slot.bounds).isEqualTo(Rect(0, 0, 1596, 1196))
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
    }

    @Test fun dumpExplainsBelowAndInsertionRejectionsAndReportsLiveLeaves() {
        assertThat(coordinator.getBelowSlot(20, false)).isNull()
        assertThat(dumpState()).contains("task=20 Pair unavailable or busy")
        organizer.getRunningTaskInfo(10)!!.minHeight = 1500
        assertThat(coordinator.getBelowSlot(10)).isNull()
        assertThat(dumpState()).contains("Task 10 min=0x1500")
        organizer.getRunningTaskInfo(10)!!.minHeight = 0
        val slot = coordinator.getBelowSlot(10)!!
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
        assertThat(coordinator.getBelowSlot(20)).isNull()
        assertThat(dumpState()).contains("Busy")
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isFalse()
        val state = dumpState()
        assertThat(state).contains("active=true capacity=4 busy=false imeTop=null keyguard=false")
        assertThat(state).contains("insertion=Stale revision")
        assertThat(state).contains("leaf=0 task=10 bounds=Rect")
        assertThat(state).contains("leaf=2 task=30 bounds=Rect")
        coordinator.setKeyguard(true)
        assertThat(coordinator.getBelowSlot(20)).isNull()
        assertThat(dumpState()).contains("keyguard=true")
        coordinator.setKeyguard(false)
        whenever(host.capacity()).thenReturn(2)
        assertThat(coordinator.getBelowSlot(20)).isNull()
        assertThat(dumpState()).contains("belowSlot=task=20 Capacity")
    }

    private fun verifyColdInsertion(stacked: Boolean) {
        if (stacked) {
            whenever(split.isLeftRightSplit).thenReturn(false)
            whenever(split.rootBounds).thenReturn(Rect(0, 0, 2400, 3200))
            whenever(split.topLeftBounds).thenReturn(Rect(0, 0, 2400, 1596))
        }
        val appeared = organizer.getRunningTaskInfo(30)!!
        whenever(organizer.getRunningTaskInfo(30)).thenReturn(null)
        whenever(spare[0].childCount).thenReturn(0)
        val slot = coordinator.getBelowSlot(20)!!
        assertThat(slot.splitBelow).isEqualTo(!stacked)
        // A caller's stale bounds must not divert a nested launch into WM's bounds-reparent path.
        val supplied = ActivityOptions.makeBasic().apply { launchBounds = Rect(5, 5, 100, 100) }
        assertThat(coordinator.startIntent(pendingIntent, null, supplied.toBundle(), slot)).isTrue()
        val transaction = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(eq(android.view.WindowManager.TRANSIT_OPEN),
            transaction.capture(), eq(coordinator))
        val wct = transaction.firstValue
        val ops = wct.hierarchyOps
        val launch = ops.single { it.type ==
            WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_PENDING_INTENT }
        val launchIndex = ops.indexOf(launch)
        val adjacent = ops.single { it.type ==
            WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_ADJACENT_ROOTS }
        val preserve = ops.single { it.type ==
            WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_REPARENT_LEAF_TASK_IF_RELAUNCH }
        assertThat(ops.indexOf(adjacent)).isLessThan(launchIndex)
        assertThat(ops.indexOf(preserve)).isLessThan(launchIndex)
        assertThat(ops.last()).isSameInstanceAs(launch)
        assertThat(launch.pendingIntent).isSameInstanceAs(pendingIntent)
        val options = ActivityOptions.fromBundle(launch.launchOptions)
        assertThat(options.launchRootTask).isEqualTo(spare[0].mRootTaskInfo.token)
        assertThat(options.launchDisplayId).isEqualTo(0)
        assertThat(options.launchBounds).isNull()
        assertThat(wct.changes[spare[0].mRootTaskInfo.token.asBinder()]!!
            .configuration.windowConfiguration.bounds).isEqualTo(slot.bounds)
        whenever(organizer.getRunningTaskInfo(30)).thenReturn(appeared)
        whenever(spare[0].childCount).thenReturn(1)
        coordinator.onTaskChanged(spare[0], 30, true)
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.activeStages).hasSize(3)
        assertThat(groups.get(30)!!.members.map { it.taskId }).containsExactly(10, 20, 30)
        val actual = Rect()
        assertThat(coordinator.getTaskBounds(30, actual)).isTrue()
        assertThat(actual).isEqualTo(slot.bounds)
    }

    @Test fun coldIntentCreatesLeafBelowWithStagePreparedBeforeLaunch() {
        verifyColdInsertion(false)
    }

    @Test fun coldIntentCreatesLeafToSideOfStackedPairWithSameLaunchContract() {
        verifyColdInsertion(true)
    }

    @Test fun taskLaunchHasNoBoundsOverrideAndOnlyOneExplicitReparent() {
        val slot = coordinator.getBelowSlot(20)!!
        val task = RunningTaskInfo().apply {
            taskId = 50; displayId = 0; supportsMultiWindow = true; token = taskToken()
        }
        assertThat(coordinator.startTask(task, slot)).isTrue()
        val transaction = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(any(), transaction.capture(), any())
        val ops = transaction.firstValue.hierarchyOps
        val launch = ops.single { it.type ==
            WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_LAUNCH_TASK }
        val options = ActivityOptions.fromBundle(launch.launchOptions)
        assertThat(options.launchBounds).isNull()
        assertThat(options.launchRootTask).isEqualTo(spare[0].mRootTaskInfo.token)
        val reparents = ops.filter { it.type ==
            WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REPARENT &&
                it.container == task.token.asBinder() }
        assertThat(reparents).hasSize(1)
        assertThat(reparents.single().newParent).isEqualTo(spare[0].mRootTaskInfo.token.asBinder())
        assertThat(ops.indexOf(reparents.single())).isLessThan(ops.indexOf(launch))
        assertThat(ops.indexOf(ops.single { it.type ==
            WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_ADJACENT_ROOTS }))
            .isLessThan(ops.indexOf(launch))
    }

    private fun delayedStage(): StageTaskListener = spare[0].also { stage ->
        whenever(stage.childCount).thenAnswer { stage.mChildrenTaskInfo.size() }
        whenever(stage.topChildTaskId).thenAnswer {
            if (stage.mChildrenTaskInfo.size() == 0) -1 else stage.mChildrenTaskInfo.valueAt(0).taskId
        }
        whenever(stage.containsTask(any())).thenAnswer {
            stage.mChildrenTaskInfo[it.getArgument<Int>(0)] != null
        }
    }

    private fun translucentTask(stage: StageTaskListener, id: Int = 69) = RunningTaskInfo().apply {
        taskId = id
        parentTaskId = stage.mRootTaskInfo.taskId
        displayId = 0
        token = taskToken()
        supportsMultiWindow = true
        topActivityInfo = android.content.pm.ActivityInfo()
        isVisible = false
        isVisibleRequested = false
        isTopActivityTransparent = true
        isActivityStackTransparent = true
    }

    private fun delayedCallback(delay: Long): Runnable {
        val callbacks = argumentCaptor<Runnable>()
        val delays = argumentCaptor<Long>()
        verify(executor, atLeastOnce()).executeDelayed(callbacks.capture(), delays.capture())
        return callbacks.allValues.zip(delays.allValues).last { it.second == delay }.first
    }

    private fun runLastIdle() {
        val idle = argumentCaptor<Runnable>()
        verify(transitions, atLeastOnce()).runOnIdle(idle.capture())
        idle.lastValue.run()
    }

    @Test fun translucentChildAfterOpenFinishesIsAdoptedFromPreparedParent() {
        val stage = delayedStage()
        val slot = coordinator.getBelowSlot(20)!!
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
        coordinator.onTransitionReady(token, android.window.TransitionInfo(0, 0), mock(), mock())
        coordinator.onTransitionFinished(token, false)
        val queued = argumentCaptor<Runnable>()
        verify(executor, atLeastOnce()).execute(queued.capture())
        queued.lastValue.run()
        runLastIdle()
        assertThat(coordinator.activeStages).hasSize(3)
        assertThat(coordinator.isBusy).isTrue()
        assertThat(dumpState()).contains("awaitingStage=130")
        assertThat(dumpState()).contains("transitionFinished=true")
        assertThat(coordinator.getBelowSlot(10)).isNull()
        val task = translucentTask(stage)
        whenever(organizer.getChildTasks(eq(stage.mRootTaskInfo.token), any())).thenReturn(listOf(task))
        delayedCallback(100L).run()
        assertThat(coordinator.isBusy).isFalse()
        assertThat(coordinator.activeStages).hasSize(3)
        val actual = Rect()
        assertThat(coordinator.getTaskBounds(task.taskId, actual)).isTrue()
        assertThat(actual).isEqualTo(slot.bounds)
        assertThat(stage.mChildrenTaskInfo[task.taskId]).isSameInstanceAs(task)
        assertThat(task.isTopActivityTransparent).isTrue()
        assertThat(groups.get(task.taskId)!!.members.map { it.taskId }).containsExactly(10, 20, 69)
        val wcts = argumentCaptor<WindowContainerTransaction>()
        verify(organizer).applyTransaction(wcts.capture())
        val change = wcts.firstValue.changes[stage.mRootTaskInfo.token.asBinder()]!!
        assertThat(change.hidden).isFalse()
        assertThat(change.configuration.windowConfiguration.bounds).isEqualTo(slot.bounds)
        assertThat(wcts.firstValue.hierarchyOps.any {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REORDER &&
                it.container == task.token.asBinder() && it.toTop
        }).isTrue()
        val resume = wcts.firstValue.hierarchyOps.single {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_LAUNCH_TASK
        }
        val options = ActivityOptions.fromBundle(resume.launchOptions)
        assertThat(options.launchRootTask).isEqualTo(stage.mRootTaskInfo.token)
        assertThat(options.launchBounds).isNull()

    }

    @Test fun translucentTaskInTransitionBindsBeforeItsOrganizerCallback() {
        val stage = delayedStage()
        val slot = coordinator.getBelowSlot(20)!!
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
        val task = translucentTask(stage)
        val info = android.window.TransitionInfo(0, 0)
        info.addChange(android.window.TransitionInfo.Change(task.token, mock()).apply { setTaskInfo(task) })
        coordinator.onTransitionReady(token, info, mock(), mock())
        assertThat(coordinator.activeStages).hasSize(3)
        assertThat(dumpState()).contains("task=69 transitionFinished=false")
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.isBusy).isFalse()
        assertThat(groups.get(69)!!.members).hasSize(3)
    }

    @Test fun callbackAfterTransitionBindsInvisibleChildWithoutWaitingForOpacity() {
        val stage = delayedStage()
        val slot = coordinator.getBelowSlot(20)!!
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
        coordinator.onTransitionFinished(token, false)
        val task = translucentTask(stage)
        stage.mChildrenTaskInfo.put(task.taskId, task)
        whenever(organizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        coordinator.onTaskChanged(stage, task.taskId, true)
        assertThat(coordinator.isBusy).isFalse()
        assertThat(groups.get(69)!!.members.map { it.taskId }).containsExactly(10, 20, 69)
    }

    @Test fun timeoutRestoresOriginalTreeAndDrainsLateChildrenFromRetiredStage() {
        val stage = delayedStage()
        val before = coordinator.getBelowSlot(20)!!.bounds
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), coordinator.getBelowSlot(20)!!)).isTrue()
        coordinator.onTransitionFinished(token, false)
        val oldProbe = delayedCallback(100L)
        delayedCallback(5_000L).run()
        runLastIdle()
        assertThat(coordinator.activeStages).hasSize(2)
        assertThat(coordinator.isBusy).isFalse()
        assertThat(dumpState()).contains("Rolled back: No task arrived")
        val rollback = argumentCaptor<WindowContainerTransaction>()
        verify(organizer).applyTransaction(rollback.capture())
        val reset = rollback.firstValue.changes[stage.mRootTaskInfo.token.asBinder()]!!
        assertThat(reset.configuration.windowConfiguration.bounds).isEqualTo(Rect())
        assertThat(reset.hidden).isTrue()
        assertThat(rollback.firstValue.hierarchyOps.any {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_CHILDREN_TASKS_REPARENT &&
                it.container == stage.mRootTaskInfo.token.asBinder() && it.newParent == null
        }).isTrue()
        val late = translucentTask(stage)
        whenever(organizer.getChildTasks(eq(stage.mRootTaskInfo.token), any())).thenReturn(listOf(late))
        oldProbe.run()
        coordinator.onStageChanged()
        assertThat(coordinator.activeStages).hasSize(2)
        assertThat(groups.get(late.taskId)).isNull()
        assertThat(stage.mChildrenTaskInfo.size()).isEqualTo(0)
        whenever(organizer.getChildTasks(eq(stage.mRootTaskInfo.token), any())).thenReturn(emptyList())
        assertThat(coordinator.getBelowSlot(20)!!.bounds).isEqualTo(before)
    }

    @Test fun abortedColdInsertionResetsStageAndRejectsStalePreview() {
        delayedStage()
        val slot = coordinator.getBelowSlot(20)!!
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
        coordinator.onTransitionConsumed(token, true, null)
        runLastIdle()
        assertThat(coordinator.activeStages).hasSize(2)
        assertThat(coordinator.isBusy).isFalse()
        assertThat(dumpState()).contains("Rolled back: Insertion transition aborted")
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isFalse()
    }

    @Test fun coldLaunchExceptionRollsBackPreparedStageImmediately() {
        delayedStage()
        val slot = coordinator.getBelowSlot(20)!!
        whenever(transitions.startTransition(any(), any(), any())).thenThrow(IllegalStateException())
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isFalse()
        assertThat(coordinator.activeStages).hasSize(2)
        assertThat(coordinator.isBusy).isFalse()
        assertThat(dumpState()).contains("Launch failed: IllegalStateException")
        verify(organizer).applyTransaction(any())
    }

    @Test fun configurationChangeCancelsPendingInsertionAndOldPollCannotAdopt() {
        val stage = delayedStage()
        val slot = coordinator.getBelowSlot(20)!!
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
        coordinator.onTransitionFinished(token, false)
        val oldProbe = delayedCallback(100L)
        val wct = WindowContainerTransaction()
        coordinator.onConfigurationChanged(Configuration().apply { smallestScreenWidthDp = 600 }, wct)
        assertThat(coordinator.activeStages).hasSize(2)
        assertThat(dumpState()).contains("Rolled back: Configuration changed")
        val late = translucentTask(stage)
        whenever(organizer.getChildTasks(eq(stage.mRootTaskInfo.token), any())).thenReturn(listOf(late))
        oldProbe.run()
        assertThat(groups.get(69)).isNull()
    }

    @Test fun unreportedTaskDeathDoesNotLeaveSyntheticMembershipBehind() {
        val stage = delayedStage()
        val slot = coordinator.getBelowSlot(20)!!
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slot)).isTrue()
        coordinator.onTransitionFinished(token, false)
        val task = translucentTask(stage)
        whenever(organizer.getChildTasks(eq(stage.mRootTaskInfo.token), any())).thenReturn(listOf(task))
        delayedCallback(100L).run()
        assertThat(groups.get(69)).isNotNull()
        whenever(organizer.getChildTasks(eq(stage.mRootTaskInfo.token), any())).thenReturn(emptyList())
        coordinator.onStageChanged()
        assertThat(stage.mChildrenTaskInfo.size()).isEqualTo(0)
        assertThat(groups.get(69)).isNull()
        val queued = argumentCaptor<Runnable>()
        verify(executor, atLeastOnce()).execute(queued.capture())
        queued.lastValue.run()
        runLastIdle()
        assertThat(coordinator.activeStages).hasSize(2)
    }

    @Test fun downgradedSessionAllowsOccupiedReplacementsButNoAdditions() {
        add()
        add()
        whenever(host.capacity()).thenReturn(2)
        coordinator.onConfigurationChanged(Configuration().apply { smallestScreenWidthDp = 599 },
            WindowContainerTransaction())
        val slots = coordinator.getSlots(0)
        assertThat(slots).hasSize(2)
        assertThat(slots.all { it.occupied }).isTrue()
        assertThat(coordinator.getBelowSlot(10)).isNull()
        assertThat(coordinator.startIntent(pendingIntent, null, Bundle(), slots.first())).isTrue()
        coordinator.onTransitionFinished(token, false)
        val incoming = RunningTaskInfo().apply {
            taskId = 80; displayId = 0; supportsMultiWindow = true; token = taskToken()
        }
        assertThat(coordinator.startTask(incoming, coordinator.getSlots(0).last())).isTrue()
        assertThat(coordinator.activeStages).hasSize(2)
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

    @Test fun stoppedRecentTaskKeepsReservedLeafUntilTranslucentChildAppears() {
        val stage = delayedStage()
        val recent = android.app.ActivityManager.RecentTaskInfo().apply {
            taskId = 69; displayId = 0; supportsMultiWindow = true
        }
        val slot = coordinator.getBelowSlot(20)!!
        assertThat(coordinator.startTask(recent, slot)).isTrue()
        coordinator.onTransitionReady(token, android.window.TransitionInfo(0, 0), mock(), mock())
        coordinator.onTransitionFinished(token, false)
        val queued = argumentCaptor<Runnable>()
        verify(executor, atLeastOnce()).execute(queued.capture())
        queued.lastValue.run()
        runLastIdle()
        assertThat(coordinator.activeStages).hasSize(3)
        assertThat(coordinator.isBusy).isTrue()
        val child = translucentTask(stage, recent.taskId)
        whenever(organizer.getChildTasks(eq(stage.mRootTaskInfo.token), any())).thenReturn(listOf(child))
        delayedCallback(100L).run()
        assertThat(coordinator.isBusy).isFalse()
        assertThat(groups.get(recent.taskId)!!.members.map { it.taskId }).containsExactly(10, 20, 69)
        val actual = Rect()
        assertThat(coordinator.getTaskBounds(recent.taskId, actual)).isTrue()
        assertThat(actual).isEqualTo(slot.bounds)
    }

    @Test fun stoppedRecentTaskWithoutAppearanceRollsBackAfterGrace() {
        val stage = delayedStage()
        val recent = android.app.ActivityManager.RecentTaskInfo().apply {
            taskId = 69; displayId = 0; supportsMultiWindow = true
        }
        assertThat(coordinator.startTask(recent, coordinator.getBelowSlot(20)!!)).isTrue()
        coordinator.onTransitionFinished(token, false)
        delayedCallback(5_000L).run()
        runLastIdle()
        assertThat(coordinator.isBusy).isFalse()
        assertThat(coordinator.activeStages).hasSize(2)
        val rollback = argumentCaptor<WindowContainerTransaction>()
        verify(organizer).applyTransaction(rollback.capture())
        assertThat(rollback.firstValue.changes[stage.mRootTaskInfo.token.asBinder()]!!.hidden).isTrue()
        assertThat(groups.peek(recent.taskId)).isNull()
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
        whenever(divider.branchRatio).thenReturn(0.75f)
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

    @Test fun floatingLeafCollapsesFourToThreeAndRemovesItFromRecents() {
        add()
        add()
        val before = groups.get(10)!!.layout
        val task = before.getLeaves().last().taskId
        val wct = WindowContainerTransaction()
        assertThat(coordinator.prepareLeafToFloating(task, wct)).isTrue()
        assertThat(coordinator.activeStages).hasSize(3)
        assertThat(groups.peek(task)).isNull()
        assertThat(wct.hierarchyOps.none {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REMOVE_TASK
        }).isTrue()
        coordinator.onTransitionFinished(token, false)
        val after = groups.get(10)!!
        assertThat(after.members.map { it.taskId }).doesNotContain(task)
        assertThat(after.members).hasSize(3)
        assertThat(after.layout.root.first is MultiSplitLayout.Leaf ||
            after.layout.root.second is MultiSplitLayout.Leaf).isTrue()
        val survivingBranch = listOf(after.layout.root.first, after.layout.root.second)
            .filterIsInstance<MultiSplitLayout.Split>().single()
        val oldBranch = listOf(before.root.first, before.root.second)
            .filterIsInstance<MultiSplitLayout.Split>().first { it.id == survivingBranch.id }
        assertThat(survivingBranch).isEqualTo(oldBranch)
        assertThat(wct.changes.keys).containsAtLeastElementsIn(coordinator.activeStages.map {
            it.mRootTaskInfo.token.asBinder()
        })
    }

    @Test fun floatingFromThreeReturnsSurvivorsToPairInTheFloatingTransaction() {
        add()
        whenever(host.returnToPair(any(), any(), any())).thenReturn(true)
        val wct = WindowContainerTransaction()
        assertThat(coordinator.prepareLeafToFloating(30, wct)).isTrue()
        verify(host).returnToPair(eq(pair), eq(0.5f), eq(wct))
        assertThat(coordinator.isActive).isFalse()
        assertThat(groups.peek(30)).isNull()
        verify(host, never()).ended(any())
    }

    @Test fun floatingFromTwoExitsSplitAfterTheLastCollapse() {
        add()
        assertThat(coordinator.prepareLeafToFloating(30, WindowContainerTransaction())).isTrue()
        coordinator.onTransitionFinished(token, false)
        assertThat(coordinator.activeStages).hasSize(2)
        assertThat(coordinator.prepareLeafToFloating(10, WindowContainerTransaction())).isTrue()
        assertThat(coordinator.isActive).isFalse()
        verify(host).ended(any())
    }

    @Test fun rootSegmentCallbacksSwapTheirExactFacingPairsAndRespectMutationGuards() {
        add()
        add()
        val model = groups.get(10)!!.layout
        val handles = model.dividerHandles(model.calculateBounds(Rect(0, 0, 3200, 2400),
            android.graphics.Insets.NONE, 8)!!).getValue(model.root.id)
        // The root divider is allocated after the first short divider.
        val handler = dividerHandlers[1]
        for (index in handles.indices) {
            val before = groups.get(10)!!.layout.getLeaves().associate { it.id to it.taskId }
            handler.onDividerHandleTapped(index)
            org.mockito.Mockito.clearInvocations(transitions)
            handler.onDividerHandleTapped(1 - index)
            verify(transitions, never()).startTransition(any(), any(), any())
            coordinator.onTransitionFinished(token, false)
            val after = groups.get(10)!!.layout.getLeaves().associate { it.id to it.taskId }
            val handle = handles[index]
            assertThat(after[handle.first]).isEqualTo(before[handle.second])
            assertThat(after[handle.second]).isEqualTo(before[handle.first])
            assertThat(after.filterKeys { it != handle.first && it != handle.second })
                .isEqualTo(before.filterKeys { it != handle.first && it != handle.second })
        }
    }

    @Test fun menuOwnerIsLayeredAboveEveryOtherDividerAndResetsOnDismiss() {
        add()
        add()
        val transaction = mock<SurfaceControl.Transaction>(defaultAnswer = org.mockito.Mockito.RETURNS_SELF)
        val leashes = createdDividers.map { divider ->
            mock<SurfaceControl>().also {
                whenever(it.isValid).thenReturn(true)
                whenever(divider.dividerLeash).thenReturn(it)
            }
        }
        dividerHandlers[1].onDividerHandleMenuChanged(true)
        coordinator.applySurfaceChanges(transaction)
        leashes.forEachIndexed { index, leash ->
            verify(transaction).setLayer(leash, if (index == 1) Int.MAX_VALUE else Int.MAX_VALUE - 1)
        }
        dividerHandlers[1].onDividerHandleMenuChanged(false)
        org.mockito.Mockito.clearInvocations(transaction)
        coordinator.applySurfaceChanges(transaction)
        leashes.forEach { verify(transaction).setLayer(it, Int.MAX_VALUE - 1) }
    }

    @Test fun frameworkPinRequestKeepsTheGroupForTheFloatingRemovalHandler() {
        add()
        add()
        val task = organizer.getRunningTaskInfo(10)!!
        val request = TransitionRequestInfo(android.view.WindowManager.TRANSIT_CHANGE, task, null)
        request.setWindowingLayerChange(TransitionRequestInfo.WindowingLayerChange(
            android.app.ActivityManager.AppTask.WINDOWING_LAYER_PINNED, null))
        assertThat(coordinator.restoreRequest(Binder(), request)).isNull()
        assertThat(coordinator.handleRequest(Binder(), request)).isNull()
        assertThat(coordinator.activeStages).hasSize(4)
        val wct = WindowContainerTransaction()
        assertThat(coordinator.prepareLeafToFloating(10, wct)).isTrue()
        assertThat(coordinator.activeStages).hasSize(3)
    }


    private fun withDividerSurfaces(block: (List<SurfaceControl>, SurfaceControl) -> Unit) {
        val root = SurfaceControl.Builder().setName("multiSplitTestRoot").setContainerLayer().build()
        val leashes = createdDividers.mapIndexed { index, divider ->
            val leash = SurfaceControl.Builder().setName("multiSplitTestDivider$index")
                .setContainerLayer().build()
            whenever(divider.dividerLeash).thenReturn(leash)
            whenever(divider.rootBounds).thenReturn(Rect(index * 100, index * 200, 3200, 2400))
            org.mockito.kotlin.doAnswer {
                (it.arguments[0] as Rect).set(4, 5, 16, 96)
                null
            }.whenever(divider).getDividerLeashBounds(any())
            leash
        }
        whenever(host.rootLeash()).thenReturn(root)
        try { block(leashes, root) } finally {
            leashes.forEach { it.release() }
            root.release()
        }
    }

    private fun assertRecentsDividerTargets(additionalApps: Int) {
        repeat(additionalApps) { add() }
        withDividerSurfaces { leashes, _ ->
            val info = android.window.TransitionInfo(TRANSIT_TO_FRONT, 0)
            try {
                coordinator.startRecents(info)
                assertThat(info.changes).hasSize(additionalApps + 1)
                info.changes.forEachIndexed { index, change ->
                    assertThat(change.leash.isSameSurface(leashes[index])).isTrue()
                    assertThat(change.parent).isEqualTo(host.rootInfo()!!.token)
                    assertThat(change.mode).isEqualTo(android.view.WindowManager.TRANSIT_TO_BACK)
                    assertThat(change.flags).isEqualTo(
                        com.android.wm.shell.shared.split.SplitScreenConstants.FLAG_IS_DIVIDER_BAR)
                    val expected = Rect(4 + index * 100, 5 + index * 200,
                        16 + index * 100, 96 + index * 200)
                    assertThat(change.startAbsBounds).isEqualTo(expected)
                    assertThat(change.endAbsBounds).isEqualTo(expected)
                }
                val transaction = mock<SurfaceControl.Transaction>(
                    defaultAnswer = org.mockito.Mockito.RETURNS_SELF)
                coordinator.setDividerVisible(false, transaction)
                coordinator.applySurfaceChanges(transaction)
                leashes.forEach {
                    verify(transaction, never()).hide(it)
                    verify(transaction, never()).show(it)
                    verify(transaction, never()).setAlpha(eq(it), any())
                }
            } finally { info.changes.forEach { it.leash.release() } }
        }
    }

    @Test fun threeAppRecentsHandsBothDividersToTheRemoteAnimation() {
        assertRecentsDividerTargets(1)
    }

    @Test fun fourAppRecentsHandsEveryDividerToTheRemoteAnimation() {
        assertRecentsDividerTargets(2)
    }

    @Test fun homeFinishPreservesTheGroupAndArmsThePairRelaunchPolicy() {
        add()
        add()
        val group = groups.get(10)!!
        val home = WindowContainerTransaction()
        coordinator.finishRecents(false, home, mock())
        assertThat(home.hierarchyOps.any {
            it.container == host.rootInfo()!!.token.asBinder() &&
                it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_REPARENT_LEAF_TASK_IF_RELAUNCH &&
                it.isReparentLeafTaskIfRelaunch
        }).isTrue()
        assertThat(groups.get(10)).isEqualTo(group)
        assertThat(coordinator.restorePair(group.firstRepresentative, group.secondRepresentative,
            null, null, null)).isTrue()
        val wct = argumentCaptor<WindowContainerTransaction>()
        verify(transitions, times(3)).startTransition(any(), wct.capture(), any())
        assertThat(wct.lastValue.hierarchyOps.any {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_REPARENT_LEAF_TASK_IF_RELAUNCH &&
                !it.isReparentLeafTaskIfRelaunch
        }).isTrue()
    }

    @Test fun plainMemberLaunchFromHomeOpensAloneAndClearsTheGroupLikeThePair() {
        add()
        add()
        coordinator.activeStages.forEach { stage ->
            org.mockito.kotlin.doAnswer {
                val consumer = it.arguments[0] as java.util.function.Consumer<RunningTaskInfo>
                consumer.accept(organizer.getRunningTaskInfo(stage.topChildTaskId)!!)
                null
            }.whenever(stage).doForAllChildTaskInfos(any())
        }
        coordinator.finishRecents(false, WindowContainerTransaction(), mock())
        val task = organizer.getRunningTaskInfo(30)!!
        task.configuration.windowConfiguration.windowingMode =
            android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
        task.parentTaskId = -1
        val request = TransitionRequestInfo(TRANSIT_TO_FRONT, task, null)
        val transition = Binder()
        assertThat(coordinator.restoreRequest(transition, request)).isNull()
        val wct = coordinator.handleRequest(transition, request)!!
        assertThat(coordinator.isActive).isFalse()
        assertThat(groups.peek(30)).isNull()
        assertThat(wct.hierarchyOps.any {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REPARENT &&
                it.container == task.token.asBinder() && it.toTop && it.newParent == null
        }).isTrue()
    }

    @Test fun returningFromRecentsReparentsAllDividerSurfacesAndDisarmsRelaunch() {
        add()
        add()
        withDividerSurfaces { leashes, root ->
            val info = android.window.TransitionInfo(TRANSIT_TO_FRONT, 0)
            try {
                coordinator.startRecents(info)
                coordinator.setDividerVisible(false, mock())
                val transaction = mock<SurfaceControl.Transaction>(
                    defaultAnswer = org.mockito.Mockito.RETURNS_SELF)
                val wct = WindowContainerTransaction()
                coordinator.finishRecents(true, wct, transaction)
                leashes.forEach {
                    verify(transaction).reparent(it, root)
                    verify(transaction, org.mockito.Mockito.atLeastOnce()).show(it)
                    verify(transaction, org.mockito.Mockito.atLeastOnce()).setAlpha(it, 1f)
                }
                createdDividers.forEach { verify(it).setRecentsAnimating(false) }
                assertThat(wct.hierarchyOps.any {
                    it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_REPARENT_LEAF_TASK_IF_RELAUNCH &&
                        !it.isReparentLeafTaskIfRelaunch
                }).isTrue()
            } finally { info.changes.forEach { it.leash.release() } }
        }
    }

    @Test fun homeTransitionUsesThePairRootPolicyWithoutHidingDividersEarly() {
        add()
        withDividerSurfaces { leashes, _ ->
            val info = android.window.TransitionInfo(TRANSIT_TO_FRONT, 0)
            val root = android.window.TransitionInfo.Change(host.rootInfo()!!.token, mock())
            root.setTaskInfo(host.rootInfo())
            root.setMode(android.view.WindowManager.TRANSIT_TO_BACK)
            info.addChange(root)
            val homeTask = RunningTaskInfo().apply {
                configuration.windowConfiguration.activityType =
                    android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
            }
            val home = android.window.TransitionInfo.Change(null, mock())
            home.setTaskInfo(homeTask)
            home.setDisplayId(0, 0)
            info.addChange(home)
            org.mockito.Mockito.clearInvocations(organizer)
            val start = mock<SurfaceControl.Transaction>(
                defaultAnswer = org.mockito.Mockito.RETURNS_SELF)
            coordinator.onTransitionReady(Binder(), info, start,
                mock<SurfaceControl.Transaction>(defaultAnswer = org.mockito.Mockito.RETURNS_SELF))
            leashes.forEach { verify(start, never()).hide(it) }
            val wct = argumentCaptor<WindowContainerTransaction>()
            verify(organizer).applyTransaction(wct.capture())
            assertThat(wct.firstValue.hierarchyOps.single().isReparentLeafTaskIfRelaunch).isTrue()
        }
    }

    @Test fun canceledRecentsReleasesSurfaceOwnershipAndUsesThePairRelaunchPolicy() {
        add()
        val info = android.window.TransitionInfo(TRANSIT_TO_FRONT, 0)
        coordinator.startRecents(info)
        org.mockito.Mockito.clearInvocations(organizer)
        coordinator.cancelRecents()
        createdDividers.forEach { verify(it).setRecentsAnimating(false) }
        val wct = argumentCaptor<WindowContainerTransaction>()
        verify(organizer).applyTransaction(wct.capture())
        assertThat(wct.firstValue.hierarchyOps.single().isReparentLeafTaskIfRelaunch).isTrue()
    }

    @Test fun backgroundRootChangeDoesNotDisarmTheHomeRelaunchPolicy() {
        add()
        coordinator.finishRecents(false, WindowContainerTransaction(), mock())
        val info = android.window.TransitionInfo(android.view.WindowManager.TRANSIT_CHANGE, 0)
        val root = android.window.TransitionInfo.Change(host.rootInfo()!!.token, mock())
        root.setTaskInfo(host.rootInfo())
        root.setMode(android.view.WindowManager.TRANSIT_CHANGE)
        info.addChange(root)
        org.mockito.Mockito.clearInvocations(organizer)
        coordinator.onTransitionReady(Binder(), info, mock(), mock())
        verify(organizer, never()).applyTransaction(any())
        assertThat(coordinator.isVisible).isFalse()
    }
}
