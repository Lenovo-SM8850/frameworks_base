// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen;

import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.SystemProperties;

import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.split.SplitScreenUtils;

/** Display-scoped eligibility for multi-app split, independent of active split-cell bounds. */
public final class SplitCapacityPolicy {
    private SplitCapacityPolicy() {}

    /** Reads the unsplit display context on every query, including after configuration changes. */
    public static int getSplitCapacity(DisplayController displays, int displayId) {
        final Context displayContext = displays.getDisplayContext(displayId);
        if (displayContext == null) {
            return 2;
        }
        final Resources resources = displayContext.getResources();
        return getSplitCapacity(resources.getConfiguration(),
                resources.getBoolean(R.bool.config_enableMultiAppSplit)
                        || (Build.IS_DEBUGGABLE && SystemProperties.getBoolean(
                                "persist.wm.debug.multi_split", false)),
                ActivityTaskManager.supportsSplitScreenMultiWindow(displayContext));
    }

    /** The size gate is mandatory even when the multi-app capability is enabled. */
    public static int getSplitCapacity(Configuration unsplitConfiguration,
            boolean multiAppSplitEnabled, boolean splitSupported) {
        return multiAppSplitEnabled && splitSupported
                && SplitScreenUtils.isLargeScreen(unsplitConfiguration) ? 4 : 2;
    }
}
