// SPDX-License-Identifier: Apache-2.0

package com.android.server.pm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IncrementalStatesInfo;
import android.content.pm.LauncherActivityInfoInternal;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.provider.Settings;

import com.android.modules.utils.testing.ExtendedMockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class LauncherHiddenAppsFilterTest {
    private static final String SETTING = "gamespace_launcher_hidden_packages";
    private static final Uri SETTING_URI = Uri.parse("content://settings/secure/" + SETTING);
    private static final String HOME = "test.home";
    private static final String GAME = "test.game";
    private static final String OTHER = "test.other";
    private static final String GAME_SPACE = "io.chaldeaprjkt.gamespace";
    private static final int USER_ID = 10;
    private static final int PROFILE_ID = 11;
    private static final int CALLING_UID = UserHandle.getUid(USER_ID, 12345);

    @Rule
    public final ExtendedMockitoRule mMockitoRule = new ExtendedMockitoRule.Builder(this)
            .mockStatic(Settings.Secure.class)
            .build();

    @Mock private Context mContext;
    @Mock private ContentResolver mResolver;
    @Mock private RoleManager mRoles;
    @Mock private PackageManagerInternal mPackages;
    @Mock private UserManagerInternal mUsers;

    private final TestLooper mLooper = new TestLooper();
    private final List<String> mChanges = new ArrayList<>();
    private final List<UserHandle> mChangedUsers = new ArrayList<>();
    private LauncherHiddenAppsFilter mFilter;
    private ContentObserver mObserver;
    private Executor mRoleExecutor;
    private OnRoleHoldersChangedListener mRoleListener;
    private UserManagerInternal.UserLifecycleListener mUserLifecycleListener;
    private ApplicationInfo mGameSpace;
    private ParceledListSlice<LauncherActivityInfoInternal> mActivities;

    @Before
    public void setUp() {
        when(mContext.getContentResolver()).thenReturn(mResolver);
        doReturn(SETTING_URI).when(() -> Settings.Secure.getUriFor(SETTING));
        setPackages(USER_ID, GAME);
        when(mUsers.getUserInfos()).thenReturn(new UserInfo[] {
                new UserInfo(USER_ID, "User", 0), new UserInfo(PROFILE_ID, "Profile", 0)});
        when(mUsers.getProfileParentId(USER_ID)).thenReturn(USER_ID);
        when(mUsers.getProfileParentId(PROFILE_ID)).thenReturn(USER_ID);
        when(mRoles.getRoleHoldersAsUser(RoleManager.ROLE_HOME, UserHandle.of(USER_ID)))
                .thenReturn(List.of(HOME));
        when(mPackages.isSameApp(eq(HOME), anyLong(), eq(CALLING_UID), eq(USER_ID)))
                .thenReturn(true);
        mGameSpace = new ApplicationInfo();
        mGameSpace.enabled = true;
        mGameSpace.flags = ApplicationInfo.FLAG_INSTALLED;
        when(mPackages.getApplicationInfo(eq(GAME_SPACE), anyLong(), eq(Process.SYSTEM_UID),
                anyInt())).thenReturn(mGameSpace);
        mActivities = new ParceledListSlice<>(List.of(activity(GAME), activity(OTHER)));
        mFilter = new LauncherHiddenAppsFilter(mContext, new Handler(mLooper.getLooper()), mRoles,
                mPackages, mUsers, (name, user) -> {
                    mChanges.add(name);
                    mChangedUsers.add(user);
                });
        mFilter.register();
        ArgumentCaptor<ContentObserver> observer = ArgumentCaptor.forClass(ContentObserver.class);
        verify(mResolver).registerContentObserver(eq(SETTING_URI), eq(false), observer.capture(),
                eq(UserHandle.USER_ALL));
        mObserver = observer.getValue();
        ArgumentCaptor<Executor> executor = ArgumentCaptor.forClass(Executor.class);
        ArgumentCaptor<OnRoleHoldersChangedListener> roleListener =
                ArgumentCaptor.forClass(OnRoleHoldersChangedListener.class);
        verify(mRoles).addOnRoleHoldersChangedListenerAsUser(executor.capture(),
                roleListener.capture(), eq(UserHandle.ALL));
        mRoleExecutor = executor.getValue();
        mRoleListener = roleListener.getValue();
        ArgumentCaptor<UserManagerInternal.UserLifecycleListener> userListener =
                ArgumentCaptor.forClass(UserManagerInternal.UserLifecycleListener.class);
        verify(mUsers).addUserLifecycleListener(userListener.capture());
        mUserLifecycleListener = userListener.getValue();
    }

    @Test
    public void homeCallerFiltersAllActivitiesForHiddenPackages() {
        assertThat(packages(filter(HOME, USER_ID))).containsExactly(OTHER);
        assertThat(packages(mActivities)).containsExactly(GAME, OTHER).inOrder();
    }

    @Test
    public void perPackageListIsEmptyForHiddenPackage() {
        mActivities = new ParceledListSlice<>(List.of(activity(GAME), activity(GAME)));
        assertThat(filter(HOME, USER_ID).getList()).isEmpty();
    }

    @Test
    public void otherCallerSeesEverything() {
        assertThat(filter(OTHER, USER_ID)).isSameInstanceAs(mActivities);
    }

    @Test
    public void spoofedHomeCallerSeesEverything() {
        when(mPackages.isSameApp(eq(HOME), anyLong(), eq(CALLING_UID), eq(USER_ID)))
                .thenReturn(false);
        assertThat(filter(HOME, USER_ID)).isSameInstanceAs(mActivities);
    }

    @Test
    public void absentDisabledOrUninstalledGameSpaceDoesNotFilter() {
        mGameSpace.enabled = false;
        assertThat(filter(HOME, USER_ID)).isSameInstanceAs(mActivities);
        mGameSpace.enabled = true;
        mGameSpace.flags = 0;
        assertThat(filter(HOME, USER_ID)).isSameInstanceAs(mActivities);
        when(mPackages.getApplicationInfo(eq(GAME_SPACE), anyLong(), eq(Process.SYSTEM_UID),
                anyInt())).thenReturn(null);
        assertThat(filter(HOME, USER_ID)).isSameInstanceAs(mActivities);
    }

    @Test
    public void settingChangeNotifiesAddedAndRemovedPackagesForAffectedUserOnly() {
        setPackages(USER_ID, " , " + OTHER + "," + OTHER + ", ");
        notifySettingChanged(USER_ID);
        assertThat(mChanges).containsExactly(GAME, OTHER);
        assertThat(mChangedUsers).containsExactly(UserHandle.of(USER_ID), UserHandle.of(USER_ID));
        assertThat(packages(filter(HOME, USER_ID))).containsExactly(GAME);
        mChanges.clear();
        notifySettingChanged(USER_ID);
        assertThat(mChanges).isEmpty();
    }

    @Test
    public void clearingSettingRestoresPackagesAndNotifiesRemovals() {
        setPackages(USER_ID, null);
        notifySettingChanged(USER_ID);
        assertThat(mChanges).containsExactly(GAME);
        assertThat(filter(HOME, USER_ID)).isSameInstanceAs(mActivities);
        setPackages(USER_ID, "");
        notifySettingChanged(USER_ID);
        assertThat(mChanges).containsExactly(GAME);
    }

    @Test
    public void profileUsesOwnSettingAndParentHome() {
        assertThat(filter(HOME, PROFILE_ID)).isSameInstanceAs(mActivities);
        setPackages(PROFILE_ID, OTHER);
        notifySettingChanged(PROFILE_ID);
        assertThat(packages(filter(HOME, PROFILE_ID))).containsExactly(GAME);
        assertThat(packages(filter(HOME, USER_ID))).containsExactly(OTHER);
        assertThat(mChangedUsers).containsExactly(UserHandle.of(PROFILE_ID));
    }

    @Test
    public void profileCopyOfHomeCallerSeesEverything() {
        setPackages(PROFILE_ID, GAME);
        notifySettingChanged(PROFILE_ID);
        final int profileUid = UserHandle.getUid(PROFILE_ID, 12345);
        when(mPackages.isSameApp(eq(HOME), anyLong(), eq(profileUid), eq(PROFILE_ID)))
                .thenReturn(true);
        when(mPackages.isSameApp(eq(HOME), anyLong(), eq(profileUid), eq(USER_ID)))
                .thenReturn(true);
        assertThat(mFilter.filter(mActivities, HOME, profileUid, USER_ID))
                .isSameInstanceAs(mActivities);
        assertThat(mFilter.filter(mActivities, HOME, profileUid, PROFILE_ID))
                .isSameInstanceAs(mActivities);
    }

    @Test
    public void homeRoleChangeInvalidatesCache() {
        assertThat(packages(filter(HOME, USER_ID))).containsExactly(OTHER);
        when(mRoles.getRoleHoldersAsUser(RoleManager.ROLE_HOME, UserHandle.of(USER_ID)))
                .thenReturn(List.of(OTHER));
        mRoleExecutor.execute(() -> mRoleListener.onRoleHoldersChanged(
                RoleManager.ROLE_HOME, UserHandle.of(USER_ID)));
        assertThat(filter(HOME, USER_ID)).isSameInstanceAs(mActivities);
    }

    @Test
    public void homeRoleChangeRefreshesUserAndProfilesOnHandler() {
        final int otherUserId = 12;
        when(mUsers.getUserInfos()).thenReturn(new UserInfo[] {
                new UserInfo(USER_ID, "User", 0), new UserInfo(PROFILE_ID, "Profile", 0),
                new UserInfo(otherUserId, "Other user", 0)});
        when(mUsers.getProfileParentId(otherUserId)).thenReturn(otherUserId);
        setPackages(USER_ID, GAME + "," + OTHER);
        setPackages(PROFILE_ID, OTHER);
        setPackages(otherUserId, GAME);
        notifySettingChanged(USER_ID);
        notifySettingChanged(PROFILE_ID);
        mChanges.clear();
        mChangedUsers.clear();
        mRoleExecutor.execute(() -> mRoleListener.onRoleHoldersChanged(
                RoleManager.ROLE_HOME, UserHandle.of(USER_ID)));
        assertThat(mChanges).isEmpty();
        mLooper.dispatchAll();
        assertThat(mChanges).containsExactly(GAME, OTHER, OTHER);
        assertThat(mChangedUsers).containsExactly(
                UserHandle.of(USER_ID), UserHandle.of(USER_ID), UserHandle.of(PROFILE_ID))
                .inOrder();
        assertThat(mChanges.get(2)).isEqualTo(OTHER);
    }

    @Test
    public void otherRoleChangeDoesNotRefreshPackagesOrInvalidateHome() {
        assertThat(packages(filter(HOME, USER_ID))).containsExactly(OTHER);
        when(mRoles.getRoleHoldersAsUser(RoleManager.ROLE_HOME, UserHandle.of(USER_ID)))
                .thenReturn(List.of(OTHER));
        mRoleExecutor.execute(() -> mRoleListener.onRoleHoldersChanged(
                RoleManager.ROLE_BROWSER, UserHandle.of(USER_ID)));
        mLooper.dispatchAll();
        assertThat(mChanges).isEmpty();
        assertThat(packages(filter(HOME, USER_ID))).containsExactly(OTHER);
    }

    @Test
    public void removedUserDoesNotRetainHiddenPackagesOrHome() {
        assertThat(packages(filter(HOME, USER_ID))).containsExactly(OTHER);
        setPackages(USER_ID, OTHER);
        when(mRoles.getRoleHoldersAsUser(RoleManager.ROLE_HOME, UserHandle.of(USER_ID)))
                .thenReturn(List.of(OTHER));
        when(mPackages.isSameApp(eq(OTHER), anyLong(), eq(CALLING_UID), eq(USER_ID)))
                .thenReturn(true);
        mUserLifecycleListener.onUserRemoved(new UserInfo(USER_ID, "User", 0));
        assertThat(filter(HOME, USER_ID)).isSameInstanceAs(mActivities);
        assertThat(packages(filter(OTHER, USER_ID))).containsExactly(GAME);
        assertThat(mChanges).isEmpty();
    }

    @Test
    public void gameSpaceAvailabilityChangeRefreshesConfiguredPackages() {
        mFilter.onPackageChanged(GAME_SPACE, USER_ID);
        mLooper.dispatchAll();
        assertThat(mChanges).containsExactly(GAME);
        assertThat(mChangedUsers).containsExactly(UserHandle.of(USER_ID));
        mChanges.clear();
        mFilter.onPackageChanged(OTHER, USER_ID);
        mLooper.dispatchAll();
        assertThat(mChanges).isEmpty();
    }

    private void notifySettingChanged(int userId) {
        mObserver.dispatchChange(false, List.of(SETTING_URI), 0, userId);
        mLooper.dispatchAll();
    }

    private void setPackages(int userId, String value) {
        doReturn(value).when(() -> Settings.Secure.getStringForUser(mResolver, SETTING, userId));
    }

    private ParceledListSlice<LauncherActivityInfoInternal> filter(String caller, int userId) {
        return mFilter.filter(mActivities, caller, CALLING_UID, userId);
    }

    private static LauncherActivityInfoInternal activity(String packageName) {
        ActivityInfo info = new ActivityInfo();
        info.packageName = packageName;
        info.name = packageName + ".MainActivity";
        return new LauncherActivityInfoInternal(info, new IncrementalStatesInfo(false, 0, 0),
                UserHandle.of(USER_ID), false);
    }

    private static List<String> packages(ParceledListSlice<LauncherActivityInfoInternal> activities) {
        List<String> result = new ArrayList<>();
        for (LauncherActivityInfoInternal info : activities.getList()) {
            result.add(info.getActivityInfo().packageName);
        }
        return result;
    }
}
