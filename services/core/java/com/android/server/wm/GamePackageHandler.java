/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.wm;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.List;

class GamePackageHandler {
    private static final String AUTO_DETECT_KEY = "gamespace_auto_game_detect";

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final GameListManager mGameListManager;
    private final Handler mHandler;

    GamePackageHandler(Context context, GameListManager manager, Handler handler) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mGameListManager = manager;
        mHandler = handler;
    }

    private boolean isAutoDetectEnabled() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                AUTO_DETECT_KEY, 1,
                UserHandle.USER_CURRENT) != 0;
    }

    void registerPackageReceiver() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiver(new PackageReceiver(), filter, Context.RECEIVER_NOT_EXPORTED);

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(AUTO_DETECT_KEY),
                false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        // Listed games are skipped, so a repeated scan changes nothing.
                        if (isAutoDetectEnabled()) {
                            scanInstalledGames();
                        }
                    }
                },
                UserHandle.USER_ALL);
    }

    private void handlePackageChanged(String action, String packageName) {
        if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            if (!isAutoDetectEnabled()) return;
            addIfGame(packageName);
        } else if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
            mGameListManager.removeGame(packageName);
        }
    }

    private void addIfGame(String packageName) {
        if (mGameListManager.isDenied(packageName)) return;
        if (mGameListManager.isGame(packageName)) return;
        if (isGame(packageName)) {
            mGameListManager.addGame(packageName);
        }
    }

    // Adds the launchable games that were installed while detection was off.
    private void scanInstalledGames() {
        final Intent launcher = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        final List<ResolveInfo> apps = mPackageManager.queryIntentActivitiesAsUser(launcher,
                PackageManager.ResolveInfoFlags.of(0), ActivityManager.getCurrentUser());
        for (ResolveInfo app : apps) {
            addIfGame(app.activityInfo.packageName);
        }
    }

    private boolean isGame(String packageName) {
        try {
            final ApplicationInfo info = mPackageManager.getApplicationInfo(packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA));
            return info.category == ApplicationInfo.CATEGORY_GAME;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private final class PackageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getData() == null) {
                return;
            }
            final String packageName = intent.getData().getSchemeSpecificPart();
            final String action = intent.getAction();
            if (packageName == null || action == null) {
                return;
            }
            mHandler.post(() -> handlePackageChanged(action, packageName));
        }
    }
}
