// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.splitscreen

import android.app.ActivityManager.RunningTaskInfo
import android.app.TaskInfo
import android.content.Context
import android.content.res.Configuration
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.IBinder
import android.view.SurfaceControl
import android.window.TransitionInfo
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SurfaceFrameRate
import com.android.wm.shell.shared.split.SplitScreenConstants.SNAP_TO_2_10_90
import com.android.wm.shell.shared.split.SplitScreenConstants.SNAP_TO_2_90_10
import com.android.wm.shell.transition.Transitions

/** Owns votes only on stage roots published by the split coordinator. */
class SplitScreenFrameRateController @JvmOverloads constructor(
    context: Context,
    private val executor: ShellExecutor,
    private val handler: Handler,
    organizer: ShellTaskOrganizer,
    transitions: Transitions,
    displays: DisplayController,
    private val refresh: Runnable,
    playback: MediaPlayback? = null,
    private val supportsOverride: () -> Boolean = SurfaceFrameRate::isOverrideSupported,
    private val transactions: () -> SurfaceControl.Transaction = { SurfaceControl.Transaction() },
) : Transitions.TransitionObserver {
    interface MediaPlayback {
        val available: Boolean
        fun start(listener: Runnable)
        fun isPlaying(task: TaskInfo): Boolean
    }
    data class Stage(val index: Int, val root: RunningTaskInfo, val leash: SurfaceControl,
        val tasks: List<RunningTaskInfo>)
    private data class TaskKey(val id: Int, val user: Int, val display: Int, val packages: Set<String>)
    private data class StageKey(val index: Int, val root: Int, val display: Int,
        val tasks: List<TaskKey>)
    private var stages = emptyList<Stage>()
    private var keys = emptyMap<SurfaceControl, StageKey>()
    private val owned = mutableSetOf<SurfaceControl>()
    private val inFlight = mutableSetOf<IBinder>()
    private val removedDisplays = mutableSetOf<Int>()
    private var snapPosition = -1
    private var settled = false
    private var dividerMoving = false
    private var animating = false
    private var resizing = false
    private val media = playback ?: ActiveSplitMediaSessions(
        context.getSystemService(MediaSessionManager::class.java), executor, handler)

    init {
        SurfaceFrameRate.addInteractionListener { update() }
        transitions.registerObserver(this)
        organizer.addTaskVanishedListener(object : ShellTaskOrganizer.TaskVanishedListener {
            override fun onTaskVanished(info: RunningTaskInfo) {
                if (stages.none { it.root.taskId == info.taskId ||
                    it.tasks.any { task -> task.taskId == info.taskId } }) return
                settled = false
                resetMotion()
                clear()
                stages = stages.filter { it.root.taskId != info.taskId }.map {
                    it.copy(tasks = it.tasks.filter { task -> task.taskId != info.taskId })
                }
                update()
                handler.post(refresh)
            }
        })
        organizer.addTaskInfoChangedListener(object : ShellTaskOrganizer.TaskInfoChangedListener {
            override fun onTaskInfoChanged(info: RunningTaskInfo) {
                val stage = stages.firstOrNull { it.root.taskId == info.taskId ||
                    it.tasks.any { task -> task.taskId == info.taskId } } ?: return
                if (stage.root.displayId != info.displayId ||
                    (stage.root.taskId != info.taskId && info.parentTaskId != stage.root.taskId)) {
                    settled = false
                    resetMotion()
                    clear()
                    stages = emptyList()
                    update()
                }
                // Read stage membership after its task listener handles this update.
                handler.post(refresh)
            }
        })
        displays.addDisplayWindowListener(object : DisplayController.OnDisplaysChangedListener {
            override fun onDisplayConfigurationChanged(displayId: Int, configuration: Configuration) {
                if (stages.any { it.root.displayId == displayId }) {
                    settled = false
                    resetMotion()
                    clear()
                    update()
                    handler.post(refresh)
                }
            }
            override fun onDisplayRemoved(displayId: Int) {
                removedDisplays += displayId
                if (stages.any { it.root.displayId == displayId }) {
                    settled = false
                    resetMotion()
                    clear()
                    stages = stages.filter { it.root.displayId != displayId }
                    update()
                }
            }
            override fun onDisplayAdded(displayId: Int) { removedDisplays -= displayId }
        })
        media.start(Runnable { update() })
    }

    fun updateStages(current: List<Stage>, position: Int, displayId: Int, atRest: Boolean) {
        val next = current.filter { it.root.displayId == displayId &&
            it.tasks.all { task -> task.displayId == displayId } &&
            it.root.displayId !in removedDisplays }
        val nextKeys = next.associate { stage -> stage.leash to StageKey(stage.index,
            stage.root.taskId, stage.root.displayId, stage.tasks.map {
                TaskKey(it.taskId, it.userId, it.displayId, packages(it))
            }) }
        val changed = owned.filter { keys[it] != nextKeys[it] }.toSet()
        stages = next
        if (next.isEmpty()) resetMotion()
        keys = nextKeys
        snapPosition = position
        settled = atRest
        update(changed)
    }

    fun setDividerMoving(moving: Boolean) {
        dividerMoving = moving && stages.isNotEmpty()
        update()
        if (!moving) handler.post(refresh)
    }

    fun setResizing(active: Boolean) {
        resizing = active && stages.isNotEmpty()
        update()
        if (!active) handler.post(refresh)
    }

    fun setAnimating(active: Boolean) {
        animating = active && stages.isNotEmpty()
        update()
        if (!active) handler.post(refresh)
    }

    fun clear() = updateVotes(emptySet())

    private fun resetMotion() {
        dividerMoving = false
        resizing = false
        animating = false
        SurfaceFrameRate.setInteracting(this, false)
    }

    private fun update(forceClear: Set<SurfaceControl> = emptySet()) {
        SurfaceFrameRate.setInteracting(this,
            stages.isNotEmpty() &&
                (dividerMoving || animating || resizing || inFlight.isNotEmpty()))
        val index = when (snapPosition) {
            SNAP_TO_2_10_90 -> 0
            SNAP_TO_2_90_10 -> 1
            else -> -1
        }
        val desired = if (settled && !dividerMoving && !animating && !resizing && inFlight.isEmpty() &&
            media.available && supportsOverride() && !SurfaceFrameRate.isInteracting) stages.filter { it.index == index && it.tasks.isNotEmpty() &&
                it.leash.isValid && it.tasks.none(media::isPlaying) }.map { it.leash }.toSet()
            else emptySet()
        updateVotes(desired, forceClear)
    }

    private fun updateVotes(desired: Set<SurfaceControl>, forceClear: Set<SurfaceControl> = emptySet()) {
        val clearing = owned.filter { it !in desired || it in forceClear }.toSet()
        val adding = desired.filter { it !in owned || it in clearing }
        if (clearing.isEmpty() && adding.isEmpty()) return
        transactions().use { t ->
            clearing.forEach {
                if (it.isValid) SurfaceFrameRate.applyVote(t, it, 0f)
                owned -= it
            }
            adding.forEach {
                SurfaceFrameRate.applyVote(t, it, 30f)
                owned += it
            }
            t.apply()
        }
    }

    override fun onTransitionReady(transition: IBinder, info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction, finishTransaction: SurfaceControl.Transaction) {
        inFlight += transition
        settled = false
        update()
    }

    override fun onTransitionMerged(merged: IBinder, playing: IBinder) {
        if (inFlight.remove(merged)) inFlight += playing
    }

    override fun onTransitionFinished(transition: IBinder, aborted: Boolean) {
        inFlight -= transition
        update()
        handler.post(refresh)
    }

    companion object {
        fun packages(task: TaskInfo): Set<String> = setOfNotNull(task.topActivity?.packageName,
            task.baseActivity?.packageName, task.realActivity?.packageName,
            task.baseIntent?.component?.packageName, task.baseIntent?.`package`)
    }
}
