// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo

/** Organizer bookkeeping alone does not require rebuilding floating decoration geometry. */
object FloatingDecorInfo {
    @JvmStatic
    fun canSkipRelayout(old: RunningTaskInfo, next: RunningTaskInfo): Boolean =
        old.isFloatingWindow && next.isFloatingWindow && old.displayId == next.displayId &&
            old.userId == next.userId && old.taskId == next.taskId &&
            old.configuration == next.configuration && old.equalsForTaskOrganizer(next)
}
