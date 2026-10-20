// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.draganddrop

import android.app.ActivityTaskManager
import android.app.ActivityManager.RunningTaskInfo
import android.app.PendingIntent
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.Context
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Rect
import android.os.PersistableBundle
import android.os.UserHandle
import android.os.Process
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.shared.draganddrop.DragAndDropConstants.EXTRA_SIDEBAR_APP_DRAG
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.splitscreen.SplitScreenController.AppDragSlot
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidTestingRunner::class)
class SidebarDragPolicyTest : ShellTestCase() {
    private val first = AppDragSlot(0, 0, Rect(0, 0, 495, 1000), Rect(0, 0, 500, 1000), true)
    private val second = AppDragSlot(1, 1, Rect(505, 0, 1000, 1000), Rect(500, 0, 1000, 1000), true)
    private val slots = listOf(first, second)
    private fun task(type: Int = ACTIVITY_TYPE_STANDARD) = RunningTaskInfo().apply {
        configuration.windowConfiguration.activityType = type
        configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN
        supportsMultiWindow = true
    }

    @Test fun homeWithoutSplitUsesFloatingFallback() {
        assertThat(SidebarDragPolicy.resolveSplitSlot(task(ACTIVITY_TYPE_HOME), true, true,
            false, 0, 2, slots, 100, 500)).isNull()
    }

    @Test fun fullscreenAppSelectsPointerSideWithExactBounds() {
        val target = SidebarDragPolicy.resolveSplitSlot(task(), true, true,
            false, 0, 2, slots, 800, 500)
        assertThat(target).isSameInstanceAs(second)
        assertThat(target!!.bounds).isEqualTo(Rect(505, 0, 1000, 1000))
    }

    @Test fun fullSplitReplacesPointedSlotIncludingDividerHitArea() {
        assertThat(SidebarDragPolicy.resolveSplitSlot(task(), true, true,
            true, 2, 2, slots, 499, 500)).isSameInstanceAs(first)
        assertThat(SidebarDragPolicy.resolveSplitSlot(task(), true, true,
            true, 2, 2, slots, 500, 500)).isSameInstanceAs(second)
    }

    @Test fun belowCapacityInsertsIntoFreeSlotEvenWhenPointerIsOverOccupiedApp() {
        val free = AppDragSlot(1, 1, second.bounds, second.hitBounds, false)
        assertThat(SidebarDragPolicy.resolveSplitSlot(task(), true, true,
            true, 1, 2, listOf(first, free), 100, 500)).isSameInstanceAs(free)
    }

    @Test fun futureCapacityUsesFreeSlotRatherThanReplacing() {
        val free = AppDragSlot(2, 2, Rect(0, 500, 495, 1000), Rect(0, 500, 500, 1000), false)
        assertThat(SidebarDragPolicy.resolveSplitSlot(task(), true, true,
            true, 2, 4, slots + free, 800, 100)).isSameInstanceAs(free)
        assertThat(SidebarDragPolicy.resolveSplitSlot(task(), true, true,
            true, 4, 4, slots + free, 800, 100)).isSameInstanceAs(second)
    }

    @Test fun rotationOrKeyguardAndUnsupportedAppsHaveNoSplitTarget() {
        assertThat(SidebarDragPolicy.resolveSplitSlot(task(), true, false,
            true, 2, 2, slots, 100, 500)).isNull()
        assertThat(SidebarDragPolicy.resolveSplitSlot(task(), false, true,
            false, 0, 2, slots, 100, 500)).isNull()
        assertThat(SidebarDragPolicy.resolveSplitSlot(null, true, true,
            false, 0, 2, slots, 100, 500)).isNull()
    }

    @Test fun noLegalGeometryHasNoSplitTarget() {
        assertThat(SidebarDragPolicy.resolveSplitSlot(task(), true, true,
            true, 2, 2, emptyList(), 100, 500)).isNull()
    }

    @Test fun untrustedMarkerUsesOrdinaryDragPolicy() {
        val pending = mock<PendingIntent> { on { creatorUid } doReturn 10123 }
        val data = clip(pending, true)
        assertThat(SidebarDragPolicy.isSidebarDrag(data)).isFalse()
        val system = mock<PendingIntent> { on { creatorUid } doReturn Process.SYSTEM_UID }
        assertThat(SidebarDragPolicy.isSidebarDrag(clip(system, false))).isFalse()
        assertThat(SidebarDragPolicy.isSidebarDrag(clip(system, true))).isTrue()
    }

    @Test fun hoverUsesCachedAppAndTaskStateUntilExplicitRefresh() {
        mContext.getOrCreateTestableResources().addOverride(
            com.android.internal.R.bool.config_supportsRoundedCornersOnWindows, false)
        val component = ComponentName("test.sidebar", "Main")
        val user = UserHandle.SYSTEM
        val host = spy(context)
        val userContext = mock<Context>()
        val pm = mock<PackageManager>()
        doReturn(userContext).whenever(host).createContextAsUser(user, 0)
        whenever(userContext.packageManager).thenReturn(pm)
        val info = ActivityInfo().apply {
            packageName = component.packageName
            name = component.className
            resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE
            applicationInfo = ApplicationInfo()
        }
        whenever(pm.getActivityInfo(component, 0)).thenReturn(info)
        val pending = mock<PendingIntent> {
            on { creatorUid } doReturn Process.SYSTEM_UID
            on { intent } doReturn Intent().setComponent(component)
        }
        val data = clip(pending, true)
        val tasks = mock<ActivityTaskManager>()
        whenever(tasks.getTasks(100, false)).thenReturn(emptyList())
        val split = mock<SplitScreenController>()
        whenever(split.isSplitScreenVisible).thenReturn(true)
        whenever(split.getSplitCapacity(0)).thenReturn(2)
        whenever(split.getOccupiedSplitSlots(0)).thenReturn(2)
        whenever(split.getAppDragSlots(0)).thenReturn(slots)
        whenever(split.canStartSidebarSplit(component, 0, null)).thenReturn(true)
        val floating = mock<com.android.wm.shell.pinnedlayer.phone.PinnedLayerController>()
        whenever(floating.canResolveSidebarSplit()).thenReturn(true)
        val policy = SidebarDragPolicy(host, split, floating, tasks)
        val session = DragSession(tasks, mock(), data, 0).apply {
            appData = data.getItemAt(0).intent
        }
        policy.start(session, 0)
        repeat(20) {
            assertThat(policy.resolve(100, 500)?.bounds).isEqualTo(first.bounds)
            assertThat(policy.resolve(800, 500)?.bounds).isEqualTo(second.bounds)
        }
        verify(tasks, times(1)).getTasks(100, false)
        verify(host, times(1)).createContextAsUser(user, 0)
        verify(pm, times(1)).getActivityInfo(component, 0)
        verify(pm, times(1)).getResourcesForApplication(info.applicationInfo)
        verify(split, times(1)).getAppDragSlots(0)
        val free = AppDragSlot(1, 1, second.bounds, second.hitBounds, false)
        whenever(split.getOccupiedSplitSlots(0)).thenReturn(1)
        whenever(split.getAppDragSlots(0)).thenReturn(listOf(first, free))
        assertThat(policy.resolve(100, 500)?.bounds).isEqualTo(first.bounds)
        policy.refreshSnapshot()
        assertThat(policy.resolve(100, 500)?.bounds).isEqualTo(second.bounds)
        verify(tasks, times(2)).getTasks(100, false)
        verify(pm, times(1)).getActivityInfo(component, 0)
        verify(pm, times(1)).getResourcesForApplication(info.applicationInfo)
    }

    private fun clip(pending: PendingIntent, marked: Boolean): ClipData = ClipData(
        ClipDescription("app", arrayOf(ClipDescription.MIMETYPE_APPLICATION_ACTIVITY)).apply {
            extras = PersistableBundle().apply { putBoolean(EXTRA_SIDEBAR_APP_DRAG, marked) }
        }, ClipData.Item(Intent().putExtra(ClipDescription.EXTRA_PENDING_INTENT, pending)
            .putExtra(Intent.EXTRA_USER, UserHandle.SYSTEM)))
    @Test fun multiAppInsertionUsesHoveredHalfAndNeverAnUnrelatedLeaf() {
        val upper = AppDragSlot(0, 2, Rect(0, 0, 495, 495), Rect(0, 0, 495, 500),
            false, 0, true, 1)
        val lower = AppDragSlot(0, 2, Rect(0, 505, 495, 1000), Rect(0, 500, 495, 1000),
            false, 0, false, 1)
        assertThat(SidebarDragPolicy.resolveSplitSlot(task(), true, true,
            true, 2, 4, listOf(upper, lower), 100, 100)).isSameInstanceAs(upper)
        assertThat(SidebarDragPolicy.resolveSplitSlot(task(), true, true,
            true, 2, 4, listOf(upper, lower), 100, 900)).isSameInstanceAs(lower)
        assertThat(SidebarDragPolicy.resolveSplitSlot(task(), true, true,
            true, 3, 4, listOf(upper, lower), 900, 100)).isNull()
    }

}
