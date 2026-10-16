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
    transitions: com.android.wm.shell.transition.Transitions? = null,
    private val supportsOverride: () -> Boolean = SurfaceFrameRate::isOverrideSupported,
    private val transactions: () -> SurfaceControl.Transaction = { SurfaceControl.Transaction() },
) {
    private data class Task(val info: RunningTaskInfo, val leash: SurfaceControl)
    private val tasks = mutableMapOf<Int, Task>()
    private val owned = mutableSetOf<SurfaceControl>()
    private val votes = mutableMapOf<SurfaceControl, Float>()
    private var focusedTaskId: Int? = null
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE

    init {
        SurfaceFrameRate.addInteractionListener { update() }
        transitions?.registerObserver(object : com.android.wm.shell.transition.Transitions.TransitionObserver {
            private val active = mutableSetOf<android.os.IBinder>()
            override fun onTransitionReady(transition: android.os.IBinder,
                info: android.window.TransitionInfo, startT: SurfaceControl.Transaction,
                finishT: SurfaceControl.Transaction) {
                active += transition
                SurfaceFrameRate.setInteracting(this, true)
            }
            override fun onTransitionMerged(merged: android.os.IBinder, playing: android.os.IBinder) {
                if (active.remove(merged)) active += playing
            }
            override fun onTransitionFinished(transition: android.os.IBinder, aborted: Boolean) {
                active -= transition
                SurfaceFrameRate.setInteracting(this, active.isNotEmpty())
            }
        })
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
        val enabled = supportsOverride() && !SurfaceFrameRate.isInteracting
        if (!enabled && owned.isEmpty()) return
        val count = tasks.values.count { it.info.isFloatingWindow && it.info.displayId == DEFAULT_DISPLAY }
        val changes = mutableMapOf<SurfaceControl, Float>()
        tasks.values.forEach { task ->
            val info = task.info
            val floating = info.isFloatingWindow && info.displayId == DEFAULT_DISPLAY
            if (!floating && task.leash !in owned) return@forEach
            val entry = miniState.get(info.userId, info.taskId)
            val fps = if (enabled) vote(floating,
                focusedTaskId?.let { it == info.taskId } ?: info.isFocused, count,
                entry != null, entry?.edge != null && entry.edge != FloatingMiniState.Edge.NONE,
                thermalStatus) else 0f
            if ((votes[task.leash] ?: 0f) != fps && task.leash.isValid) changes[task.leash] = fps
            if (fps > 0f) {
                owned += task.leash
                votes[task.leash] = fps
            } else {
                owned -= task.leash
                votes.remove(task.leash)
            }
        }
        if (changes.isEmpty()) return
        transactions().use { t ->
            changes.forEach { (leash, fps) -> applyVote(t, leash, fps) }
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
