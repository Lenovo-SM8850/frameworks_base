// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.common

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW

/** One budget for split panes and visible floating/mini tasks; fullscreen apps are excluded. */
object MultitaskingWindowLimit {
    const val MAX_WINDOWS = 5

    fun splitLeaves(tasks: Collection<RunningTaskInfo>): Set<Int> {
        val byId = tasks.associateBy { it.taskId }
        val parents = tasks.filter { it.hasParentTask() }.map { it.parentTaskId }.toSet()
        fun visibleWithParents(task: RunningTaskInfo): Boolean {
            var current = task
            val seen = mutableSetOf<Int>()
            while (seen.add(current.taskId)) {
                if (!current.isVisible && !current.isVisibleRequested) return false
                current = byId[current.parentTaskId] ?: return true
            }
            return false
        }
        return tasks.filter { it.taskId !in parents && it.hasParentTask() &&
            it.activityType == ACTIVITY_TYPE_STANDARD &&
            it.windowingMode == WINDOWING_MODE_MULTI_WINDOW && !it.isFloatingWindow &&
            visibleWithParents(it) }.map { it.taskId }.toSet()
    }

    /** Floating iteration order is the existing oldest-first minimization order. */
    fun overflow(split: Set<Int>, floating: Collection<Int>, incoming: Int? = null): List<Int> {
        val floats = (floating + listOfNotNull(incoming)).distinct()
        val total = (split + floats).size
        return floats.filter { it != incoming }.take((total - MAX_WINDOWS).coerceAtLeast(0))
    }
}
