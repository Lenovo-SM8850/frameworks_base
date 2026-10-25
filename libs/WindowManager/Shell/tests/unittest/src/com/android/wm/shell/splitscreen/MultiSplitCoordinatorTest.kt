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

}
