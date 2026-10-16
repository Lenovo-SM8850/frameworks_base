// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityTaskManager
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_PINNED
import android.os.IBinder
import android.view.Display.DEFAULT_DISPLAY
import android.view.SurfaceControl
import android.window.TransitionInfo
import com.android.wm.shell.common.TaskStackListenerCallback
import com.android.wm.shell.common.TaskStackListenerImpl
import com.android.wm.shell.recents.RecentsTransitionHandler
import com.android.wm.shell.recents.RecentsTransitionStateListener
import com.android.wm.shell.shared.TransitionUtil
import com.android.wm.shell.sysui.KeyguardChangeListener
import com.android.wm.shell.sysui.OverviewVisibilityChangeListener
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.transition.Transitions

/** Handles explicit Home repeats without treating visibility changes as user actions. */
class FloatingTidyController(
    taskStackListener: TaskStackListenerImpl,
    recents: RecentsTransitionHandler,
    private val shellController: ShellController,
    private val transitions: Transitions,
    private val isInteractive: () -> Boolean,
    private val tidy: (List<RunningTaskInfo>) -> Unit,
    private val tasks: () -> List<RunningTaskInfo> = {
        ActivityTaskManager.getInstance().getTasks(10, false, false, DEFAULT_DISPLAY)
    },
) {
    private var keyguardShowing = false
    private var overviewShowing = false
    private var launcherStateUncertain = false
    private var recentsRunning = false
    private var homeAtRecentsStart = false
    private var generation = 0
    private var pendingGeneration: Int? = null
    private val appTransitions = mutableSetOf<IBinder>()

    init {
        taskStackListener.addListener(object : TaskStackListenerCallback {
            override fun onActivityRestartAttempt(task: RunningTaskInfo, homeTaskVisible: Boolean,
                clearedTask: Boolean, wasVisible: Boolean) {
                if (task.displayId != DEFAULT_DISPLAY || task.activityType != ACTIVITY_TYPE_HOME ||
                    !homeTaskVisible || !wasVisible) return
                if (launcherStateUncertain) {
                    if (!recentsRunning && isInteractive()) {
                        launcherStateUncertain = false
                        invalidate()
                    }
                    return
                }
                requestTidy()
            }

            override fun onTaskMovedToFront(task: RunningTaskInfo) {
                if (task.displayId == DEFAULT_DISPLAY && !task.isFloatingWindow &&
                    task.activityType == ACTIVITY_TYPE_STANDARD) {
                    launcherStateUncertain = false
                    invalidate()
                }
            }
        })
        shellController.addKeyguardChangeListener(object : KeyguardChangeListener {
            override fun onKeyguardVisibilityChanged(visible: Boolean, occluded: Boolean,
                animatingDismiss: Boolean) {
                keyguardShowing = visible
                invalidate()
            }
        })
        shellController.addOverviewVisibilityChangeListener(object : OverviewVisibilityChangeListener {
            override fun onOverviewShown(displayId: Int) {
                if (displayId != DEFAULT_DISPLAY) return
                overviewShowing = true
                launcherStateUncertain = true
                invalidate()
            }
            override fun onOverviewHidden(displayId: Int) {
                if (displayId == DEFAULT_DISPLAY) overviewShowing = false
            }
        })
        recents.addTransitionStateListener(object : RecentsTransitionStateListener {
            override fun onTransitionStateChanged(state: Int, displayId: Int) {
                if (displayId != DEFAULT_DISPLAY) return
                if (state == RecentsTransitionStateListener.TRANSITION_STATE_REQUESTED) {
                    val alreadyHome = canTidy()
                    invalidate()
                    homeAtRecentsStart = alreadyHome
                }
                recentsRunning = RecentsTransitionStateListener.isRunning(state)
            }

            override fun onTransitionFinishedToHome(displayId: Int) {
                if (displayId == DEFAULT_DISPLAY) launcherStateUncertain = true
            }

            override fun onHomeGestureFinished(displayId: Int) {
                if (displayId != DEFAULT_DISPLAY) return
                launcherStateUncertain = false
                val alreadyHome = homeAtRecentsStart
                homeAtRecentsStart = false
                recentsRunning = false
                if (alreadyHome) requestTidy()
            }
        })
        transitions.registerObserver(object : Transitions.TransitionObserver {
            override fun onTransitionReady(transition: IBinder, info: TransitionInfo,
                startTransaction: SurfaceControl.Transaction, finishTransaction: SurfaceControl.Transaction) {
                if (info.changes.any { change ->
                    val task = change.taskInfo
                    task != null && task.displayId == DEFAULT_DISPLAY && !task.isFloatingWindow &&
                        task.activityType == ACTIVITY_TYPE_STANDARD &&
                        (TransitionUtil.isOpeningType(change.mode) || TransitionUtil.isClosingType(change.mode))
                }) {
                    appTransitions += transition
                    invalidate()
                }
            }
            override fun onTransitionMerged(merged: IBinder, playing: IBinder) {
                if (appTransitions.remove(merged)) appTransitions += playing
            }
            override fun onTransitionFinished(transition: IBinder, aborted: Boolean) {
                appTransitions -= transition
            }
        })
    }

    private fun invalidate() {
        generation++
        homeAtRecentsStart = false
    }

    private fun canTidy(): Boolean = !keyguardShowing && !overviewShowing && !launcherStateUncertain &&
        !recentsRunning &&
        appTransitions.isEmpty() && isInteractive() &&
        !shellController.isOverviewVisible(DEFAULT_DISPLAY) &&
        tasks().firstOrNull { it.displayId == DEFAULT_DISPLAY && it.isVisible &&
            !it.isFloatingWindow && it.windowingMode != WINDOWING_MODE_PINNED }
            ?.activityType == ACTIVITY_TYPE_HOME

    private fun requestTidy() {
        if (!canTidy() || pendingGeneration == generation) return
        val request = generation
        pendingGeneration = request
        transitions.runOnIdle {
            if (pendingGeneration == request) pendingGeneration = null
            if (request == generation && canTidy()) tidy(tasks())
        }
    }
}
