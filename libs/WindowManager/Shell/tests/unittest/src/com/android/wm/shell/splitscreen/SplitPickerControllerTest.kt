// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.UserInfo
import android.os.UserHandle
import android.os.UserManager
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class SplitPickerControllerTest : ShellTestCase() {
    private val launcher = mock<LauncherApps>()
    private val users = mock<UserManager>()
    private val secondary = UserHandle.of(10)
    private val work = UserHandle.of(11)
    private val owner = UserHandle.of(0)
    private val component = ComponentName("test.app", "test.app.Main")
    private lateinit var controller: SplitPickerController

    @Before fun setup() {
        mContext.addMockSystemService(Context.LAUNCHER_APPS_SERVICE, launcher)
        mContext.addMockSystemService(Context.USER_SERVICE, users)
        whenever(users.getProfiles(10)).thenReturn(listOf(
            UserInfo(10, "Secondary", 0), UserInfo(11, "Work", UserInfo.FLAG_MANAGED_PROFILE)))
        whenever(users.getProfiles(0)).thenReturn(listOf(UserInfo(0, "Owner", 0)))
        controller = SplitPickerController(mContext, mock(), mock(), mock(), mock(), mock(), mock(),
            Executor { it.run() })
    }

    private fun activity(user: UserHandle) = mock<LauncherActivityInfo>().also {
        whenever(it.user).thenReturn(user)
        whenever(it.componentName).thenReturn(component)
    }

    @Test fun enumerationUsesForegroundProfileGroupAndPerUserQueries() {
        val personal = activity(secondary)
        val managed = activity(work)
        whenever(launcher.getActivityList(isNull(), eq(secondary))).thenReturn(listOf(personal))
        whenever(launcher.getActivityList(isNull(), eq(work))).thenReturn(listOf(managed))
        assertThat(controller.profileActivities(10)).containsExactly(personal, managed).inOrder()
        verify(users).getProfiles(10)
        verify(launcher, never()).getActivityList(isNull(), eq(owner))
        verify(launcher, never()).profiles
    }

    @Test fun ownerSelectionIsRejectedEvenWhenOwnerIsRunningAndUnlocked() {
        whenever(users.isUserUnlocked(owner)).thenReturn(true)
        assertThat(controller.resolveProfileActivity(component, owner, 10)).isNull()
        verify(launcher, never()).resolveActivity(any(), any())
    }

    @Test fun foregroundWorkSelectionIsAllowedAndRemovedProfileIsRevalidated() {
        val managed = activity(work)
        whenever(launcher.resolveActivity(any(), eq(work))).thenReturn(managed)
        assertThat(controller.resolveProfileActivity(component, work, 10)).isSameInstanceAs(managed)
        whenever(users.getProfiles(10)).thenReturn(listOf(UserInfo(10, "Secondary", 0)))
        assertThat(controller.resolveProfileActivity(component, work, 10)).isNull()
    }

    @Test fun activityFromAnotherUserIsNotEnumeratedOrResolved() {
        val wrong = activity(owner)
        whenever(launcher.getActivityList(isNull(), eq(secondary))).thenReturn(listOf(wrong))
        whenever(launcher.getActivityList(isNull(), eq(work))).thenReturn(emptyList())
        whenever(launcher.resolveActivity(any(), eq(secondary))).thenReturn(wrong)
        assertThat(controller.profileActivities(10)).isEmpty()
        assertThat(controller.resolveProfileActivity(component, secondary, 10)).isNull()
    }
}
