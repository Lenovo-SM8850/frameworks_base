// SPDX-License-Identifier: Apache-2.0

package com.android.server.pm;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfoInternal;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/** Hides selected packages from the default home's activity lists. */
final class LauncherHiddenAppsFilter {
    private static final String SETTING = "gamespace_launcher_hidden_packages";
    private static final String GAME_SPACE = "io.chaldeaprjkt.gamespace";

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final SparseArray<Set<String>> mHiddenPackages = new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<List<String>> mHomePackages = new SparseArray<>();
    private final Context mContext;
    private final Handler mHandler;
    private final RoleManager mRoleManager;
    private final PackageManagerInternal mPackageManager;
    private final UserManagerInternal mUserManager;
    private final BiConsumer<String, UserHandle> mOnPackageChanged;

    LauncherHiddenAppsFilter(Context context, Handler handler, RoleManager roleManager,
            PackageManagerInternal packageManager, UserManagerInternal userManager,
            BiConsumer<String, UserHandle> onPackageChanged) {
        mContext = context;
        mHandler = handler;
        mRoleManager = roleManager;
        mPackageManager = packageManager;
        mUserManager = userManager;
        mOnPackageChanged = onPackageChanged;
    }

    void register() {
        mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(SETTING),
                false, new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        reloadUsers();
                    }

                    @Override
                    public void onChange(boolean selfChange, Collection<Uri> uris, int flags,
                            int userId) {
                        if (userId >= 0) {
                            reloadUser(userId);
                        } else {
                            reloadUsers();
                        }
                    }
                }, UserHandle.USER_ALL);
        mRoleManager.addOnRoleHoldersChangedListenerAsUser(Runnable::run, (role, user) -> {
            if (RoleManager.ROLE_HOME.equals(role)) {
                final int userId = user.getIdentifier();
                // Invalidate before posting so a racing query cannot retain the old home.
                synchronized (mLock) {
                    mHomePackages.remove(userId);
                }
                mHandler.post(() -> {
                    for (UserInfo affectedUser : mUserManager.getUserInfos()) {
                        if (affectedUser.id != userId
                                && mUserManager.getProfileParentId(affectedUser.id) != userId) {
                            continue;
                        }
                        final Set<String> packages;
                        synchronized (mLock) {
                            packages = getPackagesLocked(affectedUser.id);
                        }
                        for (String packageName : packages) {
                            mOnPackageChanged.accept(packageName, UserHandle.of(affectedUser.id));
                        }
                    }
                });
            }
        }, UserHandle.ALL);
        mUserManager.addUserLifecycleListener(new UserManagerInternal.UserLifecycleListener() {
            @Override
            public void onUserRemoved(UserInfo user) {
                synchronized (mLock) {
                    mHiddenPackages.remove(user.id);
                    mHomePackages.remove(user.id);
                }
            }
        });
        // Load existing values before the first change so removals also trigger callbacks.
        synchronized (mLock) {
            for (UserInfo user : mUserManager.getUserInfos()) {
                mHiddenPackages.put(user.id, readPackages(user.id));
            }
        }
    }

    ParceledListSlice<LauncherActivityInfoInternal> filter(
            ParceledListSlice<LauncherActivityInfoInternal> activities, String callingPackage,
            int callingUid, int userId) {
        final Set<String> hidden;
        final int homeUserId;
        synchronized (mLock) {
            hidden = getPackagesLocked(userId);
            if (hidden.isEmpty()) {
                return activities;
            }
            // A profile uses its parent's home, just as DefaultAppProvider does.
            homeUserId = mUserManager.getProfileParentId(userId);
            List<String> homes = mHomePackages.get(homeUserId);
            if (homes == null) {
                homes = new ArrayList<>(mRoleManager.getRoleHoldersAsUser(
                        RoleManager.ROLE_HOME, UserHandle.of(homeUserId)));
                mHomePackages.put(homeUserId, homes);
            }
            if (!homes.contains(callingPackage)) {
                return activities;
            }
        }
        if (UserHandle.getUserId(callingUid) != homeUserId
                || !mPackageManager.isSameApp(callingPackage, 0, callingUid, homeUserId)) {
            return activities;
        }
        final ApplicationInfo gameSpace = mPackageManager.getApplicationInfo(GAME_SPACE,
                PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                Process.SYSTEM_UID, userId);
        if (gameSpace == null || !gameSpace.enabled
                || (gameSpace.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
            return activities;
        }
        final List<LauncherActivityInfoInternal> result = new ArrayList<>(activities.getList());
        result.removeIf(info -> hidden.contains(info.getActivityInfo().packageName));
        return new ParceledListSlice<>(result);
    }

    private Set<String> readPackages(int userId) {
        final String value = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), SETTING, userId);
        if (value == null || value.isEmpty()) {
            return Collections.emptySet();
        }
        final Set<String> packages = new HashSet<>();
        for (String entry : value.split(",")) {
            final String packageName = entry.trim();
            if (!packageName.isEmpty()) {
                packages.add(packageName);
            }
        }
        return Collections.unmodifiableSet(packages);
    }

    @GuardedBy("mLock")
    private Set<String> getPackagesLocked(int userId) {
        Set<String> packages = mHiddenPackages.get(userId);
        if (packages == null) {
            packages = readPackages(userId);
            mHiddenPackages.put(userId, packages);
        }
        return packages;
    }

    private void reloadUser(int userId) {
        final Set<String> changed = new HashSet<>();
        synchronized (mLock) {
            final Set<String> previous = mHiddenPackages.get(userId, Collections.emptySet());
            final Set<String> current = readPackages(userId);
            mHiddenPackages.put(userId, current);
            changed.addAll(previous);
            changed.addAll(current);
            changed.removeIf(name -> previous.contains(name) == current.contains(name));
        }
        for (String packageName : changed) {
            mOnPackageChanged.accept(packageName, UserHandle.of(userId));
        }
    }

    private void reloadUsers() {
        for (UserInfo user : mUserManager.getUserInfos()) {
            reloadUser(user.id);
        }
    }

    void onPackageChanged(String packageName, int userId) {
        if (!GAME_SPACE.equals(packageName)) {
            return;
        }
        // Restore or hide cached launcher icons when the controlling app changes availability.
        mHandler.post(() -> {
            final Set<String> packages;
            synchronized (mLock) {
                packages = getPackagesLocked(userId);
            }
            for (String hiddenPackage : packages) {
                mOnPackageChanged.accept(hiddenPackage, UserHandle.of(userId));
            }
        });
    }
}
