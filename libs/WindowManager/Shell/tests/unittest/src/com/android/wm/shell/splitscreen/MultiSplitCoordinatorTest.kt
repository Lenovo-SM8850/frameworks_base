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
import org.junit.After
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
    private var originalSmallestWidth = 0

    @After fun restoreResourceConfiguration() {
        mContext.resources.configuration.smallestScreenWidthDp = originalSmallestWidth
    }

    private fun taskToken(): WindowContainerToken {
        val container = mock<IWindowContainerToken>()
        whenever(container.asBinder()).thenReturn(Binder())
        return WindowContainerToken(container)
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

    @Test fun capacityAndDisplayGatesRejectInsertion() {
        whenever(host.capacity()).thenReturn(2)
        assertThat(coordinator.getSlots(0)).isEmpty()
        assertThat(coordinator.getSlots(1)).isEmpty()
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

    private fun assertGeometryOnly(wct: WindowContainerTransaction) {
        assertThat(wct.hierarchyOps).isEmpty()
        assertThat(wct.changes.keys).containsExactlyElementsIn(
            coordinator.activeStages.map { it.mRootTaskInfo.token.asBinder() })
        wct.changes.values.forEach { change ->
            assertThat(change.configuration.windowConfiguration.bounds.isEmpty).isFalse()
            assertThat(change.changeMask).isEqualTo(0)
        }
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
}
