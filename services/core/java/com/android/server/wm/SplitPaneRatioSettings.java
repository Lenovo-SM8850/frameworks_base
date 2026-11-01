/* SPDX-License-Identifier: Apache-2.0 */

package com.android.server.wm;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.os.BackgroundThread;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/** Per-user split-pane aspect-ratio choices, read outside the window manager lock. */
final class SplitPaneRatioSettings {
    private static final String SETTING_NAME = "game_split_keep_ratio_packages";

    private final ActivityTaskManagerService mService;
    private final ContentResolver mResolver;
    private final Handler mHandler = BackgroundThread.getHandler();
    // Published arrays and their sets are never modified after publication.
    private volatile SparseArray<Set<String>> mPackages = new SparseArray<>();
    private boolean mStarted;
    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            reloadKnownUsers();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (userId >= 0) {
                reloadUser(userId);
            } else {
                reloadKnownUsers();
            }
        }

        @Override
        public void onChange(boolean selfChange, Collection<Uri> uris, int flags, int userId) {
            onChange(selfChange, (Uri) null, userId);
        }
    };

    SplitPaneRatioSettings(ActivityTaskManagerService service) {
        mService = service;
        mResolver = service.mContext.getContentResolver();
    }

    void start() {
        mHandler.post(() -> {
            if (mStarted) {
                return;
            }
            mStarted = true;
            mResolver.registerContentObserver(Settings.Secure.getUriFor(SETTING_NAME), false,
                    mObserver, UserHandle.USER_ALL);
            reloadKnownUsers();
        });
    }

    boolean keepsRatioInSplit(String packageName, int userId) {
        final Set<String> packages = mPackages.get(userId);
        if (packages == null) {
            mHandler.post(() -> {
                if (mPackages.get(userId) == null) {
                    reloadUser(userId);
                }
            });
            return false;
        }
        return packages.contains(packageName);
    }

    private void reloadKnownUsers() {
        final ArraySet<Integer> users = new ArraySet<>();
        final SparseArray<Set<String>> snapshot = mPackages;
        for (int i = 0; i < snapshot.size(); i++) {
            users.add(snapshot.keyAt(i));
        }
        synchronized (mService.mGlobalLock) {
            if (mService.mRootWindowContainer != null) {
                mService.mRootWindowContainer.forAllActivities(r -> { users.add(r.mUserId); });
            }
        }
        for (int i = 0; i < users.size(); i++) {
            reloadUser(users.valueAt(i));
        }
    }

    private void reloadUser(int userId) {
        final String value = Settings.Secure.getStringForUser(mResolver, SETTING_NAME, userId);
        final ArraySet<String> packages = new ArraySet<>();
        if (value != null) {
            for (String entry : value.split(",")) {
                final String packageName = entry.trim();
                if (!packageName.isEmpty()) {
                    packages.add(packageName);
                }
            }
        }
        final Set<String> previous = mPackages.get(userId);
        if (packages.equals(previous)) {
            return;
        }
        final ArraySet<String> changed = new ArraySet<>(packages);
        if (previous != null) {
            for (String packageName : previous) {
                if (!changed.remove(packageName)) {
                    changed.add(packageName);
                }
            }
        }
        final SparseArray<Set<String>> snapshot = mPackages.clone();
        snapshot.put(userId, Collections.unmodifiableSet(packages));
        mPackages = snapshot;
        if (changed.isEmpty()) {
            return;
        }
        synchronized (mService.mGlobalLock) {
            if (mService.mRootWindowContainer == null) {
                return;
            }
            mService.mRootWindowContainer.forAllActivities(r -> {
                if (r.mUserId == userId && changed.contains(r.packageName) && r.isInSplitPane()) {
                    r.recomputeConfiguration();
                    r.ensureActivityConfiguration(/* ignoreVisibility= */ true);
                }
            });
            mService.mRootWindowContainer.ensureActivitiesVisible();
        }
    }
}
