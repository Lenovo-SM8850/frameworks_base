// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package com.android.server;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.HandlerThread;

import com.android.server.am.HostingRecord;

/** Forwards process lifecycle records to the graphics profile handler. */
public final class ActivityTriggerService extends SystemService {
    public static final int PROC_ADDED_NOTIFICATION = 1;
    public static final int PROC_REMOVED_NOTIFICATION = 0;

    private final HandlerThread mHandlerThread = new HandlerThread("ActivityTriggerService");
    private Handler mHandler;

    public ActivityTriggerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        publishLocalService(ActivityTriggerService.class, this);
    }

    public void updateRecord(HostingRecord hostingRecord, ApplicationInfo info, int pid, int event) {
        if (hostingRecord == null) {
            return;
        }
        final String packageName = info.packageName;
        final long versionCode = info.longVersionCode;
        final String processName = info.processName;
        mHandler.post(() -> notifyAction_native(packageName, versionCode, processName, pid, event));
    }

    private static native void notifyAction_native(String packageName, long versionCode,
            String processName, int pid, int event);
}
