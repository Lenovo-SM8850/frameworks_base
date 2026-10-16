// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.os.PowerManager
import android.view.Display.DEFAULT_DISPLAY
import android.view.SurfaceControl
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SurfaceFrameRate

/** Frame-rate preferences on task containers, using the standard child override strategy. */
class FloatingFrameRateController(
    context: Context,
    private val organizer: ShellTaskOrganizer,
    private val executor: ShellExecutor,
    private val miniState: FloatingMiniState,
    private val transactions: () -> SurfaceControl.Transaction = { SurfaceControl.Transaction() },
) {
    private data class Task(val info: RunningTaskInfo, val leash: SurfaceControl)
    private val tasks = mutableMapOf<Int, Task>()
    private val owned = mutableSetOf<SurfaceControl>()
    private val votes = mutableMapOf<SurfaceControl, Float>()
    private var focusedTaskId: Int? = null
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE

    init {
        context.getSystemService(PowerManager::class.java)?.let { power ->
            thermalStatus = power.currentThermalStatus
            power.addThermalStatusListener(executor) { status ->
                thermalStatus = status
                update()
            }
        }
        organizer.addFocusListener(object : ShellTaskOrganizer.FocusListener {
            override fun onFocusTaskChanged(taskInfo: RunningTaskInfo) {
                if (taskInfo.displayId == DEFAULT_DISPLAY) {
                    focusedTaskId = taskInfo.taskId
                    update()
                }
            }
        })
        organizer.addTaskAppearedListener(object : ShellTaskOrganizer.TaskAppearedListener {
            override fun onTaskAppeared(taskInfo: RunningTaskInfo, leash: SurfaceControl) {
                executor.execute { attach(taskInfo, leash) }
            }
        })
        organizer.addTaskInfoChangedListener(object : ShellTaskOrganizer.TaskInfoChangedListener {
            override fun onTaskInfoChanged(taskInfo: RunningTaskInfo) {
                tasks[taskInfo.taskId]?.let { previous ->
                    if (previous.info.displayId != taskInfo.displayId) clear(previous.leash)
                    tasks[taskInfo.taskId] = Task(taskInfo, previous.leash)
                }
                update()
            }
        })
        organizer.addTaskVanishedListener(object : ShellTaskOrganizer.TaskVanishedListener {
            override fun onTaskVanished(taskInfo: RunningTaskInfo) {
                tasks.remove(taskInfo.taskId)?.let { clear(it.leash) }
                update()
            }
        })
        organizer.initialTasks.forEach { attach(it.first, it.second) }
    }

    private fun attach(info: RunningTaskInfo, leash: SurfaceControl) {
        val current = organizer.getRunningTaskInfo(info.taskId) ?: return
        tasks.put(info.taskId, Task(current, leash))?.let { previous ->
            if (previous.leash != leash) clear(previous.leash)
        }
        update()
    }

    fun clearDisplay(displayId: Int) {
        tasks.values.filter { it.info.displayId == displayId }.forEach { clear(it.leash) }
    }

    fun removeDisplay(displayId: Int) {
        tasks.values.filter { it.info.displayId == displayId }.toList().forEach {
            clear(it.leash)
            tasks.remove(it.info.taskId)
        }
    }

    private fun clear(leash: SurfaceControl) {
        if (!owned.remove(leash)) return
        votes.remove(leash)
        if (!leash.isValid) return
        transactions().use { t -> applyVote(t, leash, 0f); t.apply() }
    }

    fun update() {
        transactions().use { t ->
            tasks.values.forEach { task ->
                if (!task.leash.isValid) return@forEach
                val info = task.info
                val floating = info.isFloatingWindow && info.displayId == DEFAULT_DISPLAY
                if (!floating && !owned.contains(task.leash)) return@forEach
                if (floating) owned += task.leash else owned -= task.leash
                val count = tasks.values.count { it.info.isFloatingWindow &&
                    it.info.displayId == info.displayId }
                val entry = miniState.get(info.userId, info.taskId)
                val fps = vote(floating, focusedTaskId?.let { it == info.taskId } ?: info.isFocused,
                    count, entry != null, entry?.edge != null &&
                        entry.edge != FloatingMiniState.Edge.NONE, thermalStatus)
                if (votes[task.leash] != fps) {
                    applyVote(t, task.leash, fps)
                    if (floating) votes[task.leash] = fps
                }
                if (!floating) votes.remove(task.leash)
            }
            t.apply()
        }
    }

    companion object {
        fun vote(floating: Boolean, focused: Boolean, count: Int, mini: Boolean,
            stashed: Boolean, thermalStatus: Int): Float = when {
            !floating -> 0f
            stashed || thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> 30f
            mini || !focused || count >= 2 -> 60f
            else -> 0f
        }

        fun applyVote(t: SurfaceControl.Transaction, leash: SurfaceControl, fps: Float) {
            SurfaceFrameRate.applyVote(t, leash, fps)
        }
    }
}
