/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.wm.shell.pinnedlayer.phone

import android.app.ActivityManager.AppTask.WINDOWING_LAYER_PINNED
import android.app.TaskInfo
import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.content.pm.UserInfo
import android.content.res.Configuration
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.sysui.UserChangeListener
import android.app.TaskWindowingLayerRequestHandler.REMOTE_CALLBACK_RESULT_KEY
import android.app.TaskWindowingLayerRequestHandler.RESULT_APPROVED
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.graphics.Rect
import android.os.Bundle
import android.os.IBinder
import android.os.IRemoteCallback
import android.os.RemoteException
import android.view.Display.DEFAULT_DISPLAY
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_CLOSE
import android.view.WindowManager.TRANSIT_TO_BACK
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerTransaction
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.MultiDisplayDragMoveIndicatorController
import com.android.wm.shell.desktopmode.ShellDesktopState
import com.android.wm.shell.desktopmode.WindowDragTransitionHandler
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerLogs.logD
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerLogs.logV
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerLogs.logW
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerUtils.getLayerPinnedWct
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerUtils.getLayerUnpinnedWct
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerUtils.getRemovedFromLayerWct
import com.android.wm.shell.recents.RecentsTransitionHandler
import com.android.wm.shell.recents.RecentsTransitionStateListener
import com.android.wm.shell.shared.TransactionPool
import com.android.wm.shell.shared.TransitionUtil
import com.android.wm.shell.sysui.KeyguardChangeListener
import com.android.wm.shell.sysui.OverviewVisibilityChangeListener
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.windowdecor.OnTaskRepositionAnimationListener

/**
 * Manages up to five visible freeform tasks in the always-on-top [WINDOWING_LAYER_PINNED] layer.
 *
 * The controller is the main decider when the task is pinned and it's responsible for dispatching
 * callbacks.
 */
class PinnedLayerController(
    shellInit: ShellInit,
    private val transitions: Transitions,
    private val desktopState: ShellDesktopState,
    private val taskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer,
    private val shellTaskOrganizer: ShellTaskOrganizer,
    private val presentationController: PinnedLayerPresentationController,
    private val windowDragTransitionHandler: WindowDragTransitionHandler,
    private val windowRepositionAnimationHandler: PinnedWindowRepositionAnimationHandler,
    private val windowRepositionAnimator: PinnedWindowRepositionAnimator,
    private val transactionPool: TransactionPool,
    private val multiDisplayDragMoveIndicatorController: MultiDisplayDragMoveIndicatorController,
) : Transitions.TransitionObserver {

    // Stores ids of pinned TaskInfo.
    private val pinnedTasks = mutableSetOf<Int>()
    private var currentPinnedTaskId: Int? = null
    private val visiblePinnedTasks = linkedSetOf<Int>()
    private val minimizedTasks = mutableSetOf<Int>()
    private val minimizeState = FloatingMinimizeState()
    private var minimizeHandles: FloatingMinimizeHandles? = null
    private var miniInteractions: FloatingMiniController? = null
    private var frameRates: FloatingFrameRateController? = null
    private val miniListeners = mutableSetOf<(TaskInfo) -> Unit>()

    fun isMini(task: TaskInfo): Boolean =
        presentationController.miniState.get(task.userId, task.taskId) != null

    fun addMiniListener(listener: (TaskInfo) -> Unit) { miniListeners += listener }
    fun removeMiniListener(listener: (TaskInfo) -> Unit) { miniListeners -= listener }
    fun isMiniAnimating(task: TaskInfo): Boolean = miniInteractions?.isAnimating(task) == true
    fun isMiniStashed(task: TaskInfo): Boolean = miniInteractions?.isStashed(task) == true
    fun endMiniTap(task: TaskInfo) { miniInteractions?.endTap(task) }
    fun restoreMini(task: TaskInfo) {
        if (isMiniStashed(task)) miniInteractions?.unstash(task) else miniInteractions?.restore(task)
    }
    fun beginMiniDrag(task: TaskInfo): Rect = miniInteractions?.beginDrag(task) ?: task.presentationBounds
    fun moveMini(task: TaskInfo, bounds: Rect) { miniInteractions?.move(task, bounds) }
    fun endMiniDrag(task: TaskInfo, start: Rect, end: Rect) {
        miniInteractions?.onDragEnded(task, start, end)
    }
    fun enterMiniAfterResize(task: TaskInfo, restore: Rect, logical: Rect, restoreScale: Float,
        bounds: Rect, scale: Float) {
        miniInteractions?.enterAfterResize(task, restore, logical, restoreScale, bounds, scale)
    }


    fun hasExpandedFloatingWindows(): Boolean = getPinnedTasks().any {
        it.displayId == DEFAULT_DISPLAY && it.isFloatingWindow && it.isVisible &&
            it.taskId in visiblePinnedTasks && it.taskId !in minimizedTasks && !isMini(it)
    }

    fun tidyFloatingWindows(tasks: List<TaskInfo>) {
        if (keyguardShowing || recentsRunning || overviewShowing) return
        miniInteractions?.tidy(tasks.filter {
            it.displayId == DEFAULT_DISPLAY && it.isFloatingWindow && it.isVisible &&
                it.taskId in visiblePinnedTasks && it.taskId !in minimizedTasks
        })
    }

    fun getMiniDragTarget(task: TaskInfo, start: Rect, end: Rect) =
        miniInteractions?.getDragTarget(task, start, end)

    fun rememberManualOrientation(task: TaskInfo, landscape: Boolean, bounds: Rect) {
        if (task.isFloatingWindow) presentationController.setManualOrientation(task, landscape, bounds)
    }

    fun configureInteractions(context: Context, displayController: DisplayController,
        mainExecutor: com.android.wm.shell.common.ShellExecutor) {
        frameRates = FloatingFrameRateController(context, shellTaskOrganizer, mainExecutor,
            presentationController.miniState, transitions = transitions)
        miniInteractions = FloatingMiniController(shellTaskOrganizer, displayController,
            presentationController.miniState) { task ->
            frameRates?.update()
            mainExecutor.execute { miniListeners.toList().forEach { it(task) } }
        }
        fun recover(task: RunningTaskInfo) {
            if (!task.isFloatingWindow || task.displayId != DEFAULT_DISPLAY) return
            if (task.taskId !in pinnedTasks) pin(task)
            val entry = presentationController.recoverMini(task)
                ?: presentationController.miniState.get(task.userId, task.taskId) ?: return
            miniInteractions?.recover(task, entry)
        }
        shellTaskOrganizer.addTaskAppearedListener(object : ShellTaskOrganizer.TaskAppearedListener {
            override fun onTaskAppeared(taskInfo: RunningTaskInfo, leash: SurfaceControl) {
                mainExecutor.execute {
                    shellTaskOrganizer.getRunningTaskInfo(taskInfo.taskId)?.let(::recover)
                }
            }
        })
        shellTaskOrganizer.initialTasks.forEach { recover(it.first) }
        context.registerReceiverForAllUsers(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                val userId = intent.getIntExtra(android.content.Intent.EXTRA_USER_HANDLE,
                    android.os.UserHandle.USER_NULL)
                if (intent.action == android.content.Intent.ACTION_USER_REMOVED &&
                    userId != android.os.UserHandle.USER_NULL) {
                    mainExecutor.execute { presentationController.removeUser(userId) }
                }
            }
        }, android.content.IntentFilter(android.content.Intent.ACTION_USER_REMOVED), null, null)
        minimizeHandles = FloatingMinimizeHandles(context, displayController, minimizeState,
            { shellTaskOrganizer.getRunningTaskInfo(it) }, ::restoreMinimizedTask)
        displayController.addDisplayChangingController { displayId, from, to, _, wct ->
            if (displayId == DEFAULT_DISPLAY && from != to) {
                getPinnedTasks().forEach { task ->
                    if (miniInteractions?.rotate(task, from, to, wct) == true) return@forEach
                    presentationController.getRotationBounds(task, from, to)?.let { bounds ->
                        presentationController.applyPresentation(task, bounds, wct, task.floatingWindowScale)
                        minimizeState.entries[task.taskId]?.bounds?.set(bounds)
                    }
                }
            }
        }
        displayController.addDisplayWindowListener(object : DisplayController.OnDisplaysChangedListener {
            override fun onDisplayConfigurationChanged(displayId: Int, configuration: Configuration) {
                frameRates?.clearDisplay(displayId)
                mainExecutor.execute { frameRates?.update() }
                if (displayId == DEFAULT_DISPLAY) minimizeHandles?.update()
            }
            override fun onDisplayRemoved(displayId: Int) {
                frameRates?.removeDisplay(displayId)
            }
        })
    }

    private fun addMinimizeHandle(task: TaskInfo, right: Boolean = true) {
        val bounds = task.floatingWindowRestoreBounds
        minimizeState.minimize(task.taskId, task.userId, bounds, right, .5f)
        minimizeHandles?.update()
    }

    private fun removeMinimizeHandle(taskId: Int) {
        minimizeState.remove(taskId)
        minimizeHandles?.update()
    }

    private fun restoreMinimizedTask(taskId: Int) {
        if (keyguardShowing || recentsRunning || overviewShowing) return
        val task = shellTaskOrganizer.getRunningTaskInfo(taskId) ?: run {
            removeMinimizeHandle(taskId)
            return
        }
        val entry = minimizeState.entries[taskId] ?: return
        if (!task.isFloatingWindow) { removeMinimizeHandle(taskId); return }
        val mini = presentationController.miniState.get(task.userId, task.taskId)
        val bounds = mini?.let { Rect(it.bounds) }
            ?: presentationController.clampToDisplay(task, entry.bounds) ?: return
        val wct = WindowContainerTransaction()
        minimizeOldestIfNeeded(task, wct)
        minimizedTasks -= taskId
        visiblePinnedTasks += taskId
        currentPinnedTaskId = taskId
        removeMinimizeHandle(taskId)
        wct.merge(getLayerPinnedWct(task.token), true)
        if (mini != null) {
            wct.setFloatingWindowPresentation(task.token,
                android.graphics.Point(bounds.left, bounds.top), mini.scale)
        } else presentationController.applyPresentation(task, bounds, wct, task.floatingWindowScale)
        wct.setHidden(task.token, false).reorder(task.token, true)
        transitions.startTransition(TRANSIT_CHANGE, wct, null)
    }
    private var keyguardShowing = false
    private var recentsRunning = false
    private var overviewShowing = false

    private val pinnedTasksListeners = mutableSetOf<PinnedTasksListener>()

    // Stores pin layer transitions that are in progress.
    private val activeTransitions = mutableMapOf<IBinder, MutableSet<ActiveTransition>>()

    init {
        shellInit.addInitCallback(this::onInit, this)
    }

    private fun onInit() {
        transitions.registerObserver(this)
        shellTaskOrganizer.addTaskVanishedListener(object : ShellTaskOrganizer.TaskVanishedListener {
            override fun onTaskVanished(taskInfo: RunningTaskInfo) {
                if (taskInfo.isFloatingWindow || taskInfo.taskId in pinnedTasks) {
                    unpin(taskInfo)
                    presentationController.forgetTask(taskInfo.taskId)
                }
            }
        })
        shellTaskOrganizer.addTaskInfoChangedListener(object : ShellTaskOrganizer.TaskInfoChangedListener {
            override fun onTaskInfoChanged(taskInfo: RunningTaskInfo) {
                if (taskInfo.isFloatingWindow && taskInfo.taskId in pinnedTasks) {
                    presentationController.recoverMini(taskInfo)?.let {
                        miniInteractions?.recover(taskInfo, it)
                    }
                    miniInteractions?.onTaskInfoChanged(taskInfo)
                    presentationController.rememberBounds(taskInfo)
                } else if (!taskInfo.isFloatingWindow && taskInfo.taskId in pinnedTasks) {
                    unpin(taskInfo)
                }
            }
        })
    }

    /**
     * Provides transitions that are currently being processed by this controller.
     *
     * @param transition an [IBinder] token for the given transitions.
     * @return a [Set] of [ActiveTransition], never returns `null`.
     */
    fun getActiveTransitions(transition: IBinder): Set<ActiveTransition> =
        activeTransitions.getOrDefault(transition, emptySet())

    /**
     * Provides currently pinned task.
     *
     * @return a pinned [TaskInfo].
     */
    fun getCurrentPinnedTask(): TaskInfo? {
        return (currentPinnedTaskId?.takeIf { it in visiblePinnedTasks }
                ?: visiblePinnedTasks.lastOrNull())
            ?.let { shellTaskOrganizer.getRunningTaskInfo(it) }
    }

    /**
     * Checks whether a task with [taskId] is pinned.
     *
     * The task is considered to be pinned when the API call has triggered a transition and it
     * reported to be ready, marking that the Core has applied changes to the task.
     *
     * @param taskId an id of task to check for pinned state.
     * @return `true` when the task is pinned, `false` otherwise.
     * @see Transitions.TransitionObserver.onTransitionReady
     */
    fun isPinned(taskId: Int): Boolean =
        shellTaskOrganizer.getRunningTaskInfo(taskId)?.isFloatingWindow == true ||
            pinnedTasks.contains(taskId) ||
            activeTransitions.values.any { pending ->
                pending.any { it is ActiveTransition.Pin && it.taskInfo.taskId == taskId }
            }

    /** Returns all live floating tasks, including minimized tasks. */
    fun getPinnedTasks(): List<TaskInfo> =
        pinnedTasks.mapNotNull { shellTaskOrganizer.getRunningTaskInfo(it) }

    /** Registers visibility policy after Shell dependencies have been constructed. */
    fun configureVisibility(
        shellController: ShellController,
        recentsTransitionHandler: RecentsTransitionHandler,
    ) {
        shellController.addUserChangeListener(object : UserChangeListener {
            override fun onUserChanged(newUserId: Int, userContext: Context) {
                minimizeHandles?.setUsers(setOf(newUserId))
            }
            override fun onUserProfilesChanged(profiles: List<UserInfo>) {
                minimizeHandles?.setUsers(profiles.map { it.id }.toSet())
            }
        })
        shellController.addKeyguardChangeListener(object : KeyguardChangeListener {
            override fun onKeyguardVisibilityChanged(
                visible: Boolean, occluded: Boolean, animatingDismiss: Boolean,
            ) {
                keyguardShowing = visible
                updateVisibility()
            }
        })
        shellController.addOverviewVisibilityChangeListener(
            object : OverviewVisibilityChangeListener {
                override fun onOverviewShown(displayId: Int) {
                    if (displayId != DEFAULT_DISPLAY) return
                    overviewShowing = true
                    updateVisibility()
                }

                override fun onOverviewHidden(displayId: Int) {
                    if (displayId != DEFAULT_DISPLAY) return
                    overviewShowing = false
                    updateVisibility()
                }
            }
        )
        recentsTransitionHandler.addTransitionStateListener(
            object : RecentsTransitionStateListener {
                override fun onTransitionFinishedToHome(displayId: Int) {
                    if (displayId != DEFAULT_DISPLAY) return
                    recentsRunning = false
                    overviewShowing = false
                    transitions.runOnIdle { updateVisibility() }
                }

                override fun onTransitionStateChanged(state: Int, displayId: Int) {
                    if (displayId != DEFAULT_DISPLAY) return
                    recentsRunning = RecentsTransitionStateListener.isRunning(state)
                    overviewShowing = shellController.isOverviewVisible(displayId)
                    if (recentsRunning) {
                        updateVisibility()
                    } else {
                        // Restore after Recents has applied its final surface transaction.
                        transitions.runOnIdle { updateVisibility() }
                    }
                }
            }
        )
    }

    private fun updateVisibility() {
        val hidden = keyguardShowing || recentsRunning || overviewShowing
        minimizeHandles?.setVisible(!hidden)
        val wct = WindowContainerTransaction()
        getPinnedTasks().filter { it.taskId !in minimizedTasks }.forEach {
            wct.setHidden(it.token, hidden)
        }
        if (!wct.isEmpty) {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    fun getFloatingLaunchBounds(task: TaskInfo): Rect? =
        presentationController.getPinEntryDestinationBounds(task)

    /** Adds entry properties before starting a Shell-owned transition. */
    fun prepareFloatingTask(task: TaskInfo, wct: WindowContainerTransaction): Boolean =
        prepareFloatingTask(task, wct, null, false)

    fun prepareFloatingTask(task: TaskInfo, wct: WindowContainerTransaction,
        visibleBounds: Rect?, minimized: Boolean): Boolean {
        if (!isPinningSupported(task)) return false
        val initial = presentationController.getPinEntryDestinationBounds(task) ?: return false
        val bounds = visibleBounds?.let { presentationController.getPreservedEntryBounds(task, it) }
            ?: initial
        if (!minimized) minimizeOldestIfNeeded(task, wct)
        pinnedTasks += task.taskId
        visiblePinnedTasks += task.taskId
        minimizedTasks -= task.taskId
        removeMinimizeHandle(task.taskId)
        wct.merge(getLayerPinnedWct(task.token), true)
        val scale = presentationController.entryScale(task)
        if (visibleBounds != null) {
            presentationController.setManualOrientation(task, bounds.width() > bounds.height(), bounds)
        }
        presentationController.applyPresentation(task, bounds, wct, scale)
        wct.setHidden(task.token, keyguardShowing || recentsRunning || overviewShowing)
        if (minimized) {
            visiblePinnedTasks -= task.taskId
            minimizedTasks += task.taskId
            minimizeState.minimize(task.taskId, task.userId, bounds, true,
                scale)
            minimizeHandles?.update()
            wct.merge(getLayerUnpinnedWct(task.token, isMinimizing = true), true)
        }
        return true
    }

    companion object {
        const val MAX_VISIBLE_FLOATING_TASKS = 5
    }

    private fun minimizeOldestIfNeeded(task: TaskInfo, wct: WindowContainerTransaction) {
        if (task.taskId in visiblePinnedTasks || visiblePinnedTasks.size < MAX_VISIBLE_FLOATING_TASKS) return
        val oldestId = visiblePinnedTasks.first()
        visiblePinnedTasks -= oldestId
        minimizedTasks += oldestId
        shellTaskOrganizer.getRunningTaskInfo(oldestId)?.let { oldest ->
            presentationController.rememberBounds(oldest)
            addMinimizeHandle(oldest)
            wct.merge(getLayerUnpinnedWct(oldest.token, isMinimizing = true), true)
            wct.setHidden(oldest.token, true)
        }
    }

    fun rotateTask(task: TaskInfo): Boolean {
        val bounds = presentationController.getManualRotationBounds(task) ?: return false
        val landscape = bounds.width() > bounds.height()
        presentationController.setManualOrientation(task, landscape, bounds)
        val wct = WindowContainerTransaction()
        presentationController.applyPresentation(task, bounds, wct, task.floatingWindowScale)
        transitions.startTransition(TRANSIT_CHANGE, wct, null)
        return true
    }

    /** Minimizes a floating task while retaining its identity for Recents. */
    @JvmOverloads
    fun minimizeTask(task: TaskInfo, right: Boolean = true): Boolean {
        if (!isPinned(task.taskId)) return false
        presentationController.rememberBounds(task)
        unpin(task, rememberAsPinned = true)
        addMinimizeHandle(task, right)
        val wct = getLayerUnpinnedWct(task.token, isMinimizing = true).setHidden(task.token, true)
        transitions.startTransition(TRANSIT_TO_BACK, wct, null)
        return true
    }

    /** Leaves the floating layer when maximizing, without replacing the task. */
    fun maximizeTask(task: TaskInfo): Boolean {
        if (!isPinned(task.taskId)) return false
        presentationController.rememberBounds(task)
        unpin(task)
        val wct = getRemovedFromLayerWct(task.token)
            .setHidden(task.token, false)
            .setWindowingMode(task.token, WINDOWING_MODE_FULLSCREEN)
            .setBounds(task.token, Rect())
            .reorder(task.token, true)
        transitions.startTransition(TRANSIT_CHANGE, wct, null)
        return true
    }

    /**
     * Pins a task and adds it to the active transitions for the given transition token.
     *
     * @param transition a transition token.
     * @param task a task to pin.
     * @param remoteCallback a callback to notify about the result of the operation.
     * @return a [WindowContainerTransaction] that contains pinned properties or `null` if the
     *   pinning is not supported for given task.
     */
    fun pinTask(
        transition: IBinder,
        task: TaskInfo,
        remoteCallback: IRemoteCallback?,
    ): WindowContainerTransaction? {
        if (!isPinningSupported(task)) {
            return null
        }
        logV(
            "pinTask: Added pending pin transition=%s taskId=%d, callback=%s",
            transition,
            task.taskId,
            remoteCallback,
        )
        return WindowContainerTransaction().apply {
            val transitions = activeTransitions.getOrPut(transition) { mutableSetOf() }
            val bounds =
                if (task.taskId !in pinnedTasks) {
                    presentationController.getPinEntryDestinationBounds(task)
                } else {
                    presentationController.clampToDisplay(
                        task,
                        task.floatingWindowRestoreBounds,
                    )
                }
            transitions += ActiveTransition.Pin(task, remoteCallback)
            minimizeOldestIfNeeded(task, this)
            visiblePinnedTasks += task.taskId
            minimizedTasks -= task.taskId
            removeMinimizeHandle(task.taskId)
            merge(getLayerPinnedWct(task.token), /* transfer= */ true)
            bounds?.let { presentationController.applyPresentation(task, it, this) }
            setHidden(task.token, keyguardShowing || recentsRunning || overviewShowing)

        }
    }

    /**
     * Unpins a specific task and adds it to the active transitions for the given transition token.
     *
     * @param transition a transition token.
     * @param task a task to unpin.
     * @param unpinStrategy a strategy for unpinning.
     * @return a [WindowContainerTransaction] that performs unpinning.
     */
    fun unpinTask(
        transition: IBinder,
        task: TaskInfo,
        unpinStrategy: UnpinStrategy,
    ): WindowContainerTransaction {
        logV(
            "unpinTask: Added pending unpin transition=%s taskId=%d, unpinStrategy=%s",
            transition,
            task.taskId,
            unpinStrategy,
        )
        val transitions = activeTransitions.getOrPut(transition) { mutableSetOf() }
        transitions += ActiveTransition.Unpin(task, unpinStrategy == UnpinStrategy.HIDE)

        return when (unpinStrategy) {
            UnpinStrategy.HIDE,
            UnpinStrategy.CLOSE ->
                getLayerUnpinnedWct(task.token, isMinimizing = unpinStrategy == UnpinStrategy.HIDE)
            UnpinStrategy.CLEAN -> getRemovedFromLayerWct(task.token)
                .setBounds(task.token, task.presentationBounds)
        }
    }

    /**
     * Starts a transition to close a pinned task.
     *
     * @param task a [TaskInfo] of the task to close.
     * @return `true` if the task closing transition has been started, `false` otherwise.
     */
    fun closeTask(task: TaskInfo): Boolean {
        if (isNotPinned(task.taskId)) {
            logV("closeTask: the task=%s is not pinned. Skipping.", task)
            return false
        }

        logV("closeTask: starting unpin transition task=%s", task)
        val wct = WindowContainerTransaction()
        wct.removeTask(task.token)
        val transition = transitions.startTransition(TRANSIT_CLOSE, wct, /* handler= */ null)
        activeTransitions[transition] = mutableSetOf(ActiveTransition.Unpin(task))
        return true
    }

    /**
     * Starts a transition to move a task to a display with a given display id.
     *
     * Current method will set bounds considering constraints and valid drag area on display.
     *
     * @param task a [TaskInfo] that should be moved to another display.
     * @param displayId a new display id to move task to.
     * @param bounds a [Rect] representing new bounds the task wants to be placed at relative to the
     *   [displayId] coordinate system.
     * @return `true` when started a transition to move task to a new display, `false` otherwise.
     */
    fun moveToDisplay(
        task: TaskInfo,
        displayId: Int,
        bounds: Rect? = null,
        handler: Transitions.TransitionHandler? = null,
    ): Boolean {
        val wct = getMoveToDisplayChanges(task, displayId, bounds) ?: return false
        transitions.startTransition(TRANSIT_CHANGE, wct, handler)
        return true
    }

    /**
     * Provides [WindowContainerTransaction] changes to be added on display disconnection.
     *
     * If a destination display is not eligible to host pinned tasks the task will be closed by
     * default.
     *
     * @param transition a running display disconnect transition.
     * @param disconnectedDisplayId a display id that was disconnected.
     * @param destinationDisplayId a display id that should host a pinned task.
     * @return a [WindowContainerTransaction] that stores operations to move a task to a display or
     *   close it.
     */
    fun getDisplayDisconnectChanges(
        transition: IBinder,
        disconnectedDisplayId: Int,
        destinationDisplayId: Int,
    ): WindowContainerTransaction? {
        val task = getCurrentPinnedTask() ?: return null

        // This method can be called for any disconnected display and pinned task may not be on it,
        // so we filter such displays out.
        if (task.displayId != disconnectedDisplayId) {
            return null
        }

        logD(
            "onDisplayDisconnect: disconnectedDisplayId=%d, destinationDisplayId=%d, task=%d",
            disconnectedDisplayId,
            destinationDisplayId,
            task.taskId,
        )

        val finalBounds =
            presentationController.getPinEntryDestinationBounds(task, destinationDisplayId)
        val moveWct = getMoveToDisplayChanges(task, destinationDisplayId, finalBounds)
        if (moveWct != null && !moveWct.isEmpty) {
            return moveWct
        }

        return unpinTask(transition, task, UnpinStrategy.CLOSE)
    }

    private fun getMoveToDisplayChanges(
        task: TaskInfo,
        displayId: Int,
        bounds: Rect? = null,
    ): WindowContainerTransaction? {
        val displayAreaInfo = taskDisplayAreaOrganizer.getDisplayAreaInfo(displayId)
        val isPinned = isPinned(task.taskId)
        val isSameDisplay = task.displayId == displayId
        val isDisplayUnavailable = displayAreaInfo == null
        val isDisplayEligibleTarget = displayId == DEFAULT_DISPLAY &&
            desktopState.isEligibleWindowDropTarget(displayId)
        logD(
            "moveToDisplay: task=%d, displayId=%d, isPinned=%b, isSameDisplayRequest=%b, " +
                "isDisplayUnavailable=%b, isDisplayEligibleDropTarget=%b",
            task.taskId,
            displayId,
            isPinned,
            isSameDisplay,
            isDisplayUnavailable,
            isDisplayEligibleTarget,
        )

        if (!isPinned || isSameDisplay || isDisplayUnavailable || !isDisplayEligibleTarget) {
            logV("moveToDisplay: skipping for the task=%s and display=%s.", task, displayAreaInfo)
            return null
        }

        val finalBounds =
            bounds?.let { newBounds ->
                presentationController.clampToDisplay(task, newBounds, displayId)
            }
        logV(
            "moveToDisplay: moving a task=%s to display=%s.\n Original bounds=%s, " +
                "clamped bounds=%s",
            task,
            displayAreaInfo,
            bounds,
            finalBounds,
        )
        val wct = WindowContainerTransaction()
        if (finalBounds != null) {
            wct.setBounds(task.token, finalBounds)
        }

        wct.reparent(task.token, displayAreaInfo.token, /* onTop= */ true)
        return wct
    }

    /**
     * Starts a focus change transitions.
     *
     * This function will also move the task to front, but since always-on-top task are effectively
     * in front, that should only cause a focus change.
     *
     * @param task a [TaskInfo] that should be focused.
     */
    fun requestFocus(task: TaskInfo) {
        if (isNotPinned(task.taskId)) {
            logV("requestFocus: the task=%s is not pinned. Skipping.", task)
            return
        }

        logV("requestFocus: starting focus transition task=%s", task)
        val wct = WindowContainerTransaction()
        wct.reorder(task.token, /* onTop= */ true, /* includingParents= */ true)
        transitions.startTransition(TRANSIT_CHANGE, wct, null)
    }

    /**
     * Handles drag end event for the given [TaskInfo].
     *
     * Visual indicators and mirroring surfaces sometimes should be disposed by the API consumer,
     * for example, when a [SurfaceControl] is snapped back to original position without any
     * following bounds changes, therefore, a caller should rely on the returned value that
     * indicates whether the controller committed state changes or not.
     *
     * @param leash a [SurfaceControl] of the given task.
     * @param taskInfo the task to update.
     * @param dragStartBounds the bounds of the task when the drag was started.
     * @param dragEndBounds the bounds of the task relative to the display on which drag has
     *   stopped.
     * @return `true` when the API caller should clear visual indicators itself, `false` otherwise.
     */
    fun onDragEnded(
        leash: SurfaceControl,
        taskInfo: TaskInfo,
        dragStartBounds: Rect,
        dragEndBounds: Rect,
        displayId: Int = taskInfo.displayId,
    ): Boolean {
        if (!taskInfo.isFloatingWindow && isNotPinned(taskInfo.taskId)) return false

        if (taskInfo.isFloatingWindow) {
            if (miniInteractions?.onDragEnded(taskInfo, dragStartBounds, dragEndBounds) == true) {
                return true
            }
            val base = taskInfo.floatingWindowRestoreBounds
            val moved = FloatingWindowGeometry.movedBaseBounds(base, dragStartBounds, dragEndBounds)
                ?: return true
            val destination = presentationController.clampToDisplay(taskInfo, moved) ?: base
            // Clamp the visible leash immediately, as well as its committed presentation.
            val visibleTop = destination.top + taskInfo.presentationBounds.top - base.top
            val t = transactionPool.acquire()
            t.setPosition(leash, destination.left.toFloat(), visibleTop.toFloat()).apply()
            transactionPool.release(t)
            dragEndBounds.offsetTo(destination.left, visibleTop)
            shellTaskOrganizer.applyTransaction(WindowContainerTransaction()
                .setFloatingWindowPresentation(taskInfo.token,
                    android.graphics.Point(destination.left, destination.top), taskInfo.floatingWindowScale))
            return true
        }
        val isCrossDisplayDrag = taskInfo.displayId != displayId
        if (
            isCrossDisplayDrag &&
                moveToDisplay(taskInfo, displayId, dragEndBounds, windowDragTransitionHandler)
        ) {
            // Mirroring surfaces will be cleared by the window drag handler, until then we keep
            // them to prevent flickering.
            return false
        }

        // Post-process final drag bounds to keep them inside the valid drag area.
        val destinationBounds =
            presentationController.clampToDisplay(taskInfo, dragEndBounds) ?: dragStartBounds
        val isTargetDisplayAvailable =
            taskDisplayAreaOrganizer.getDisplayAreaInfo(displayId) != null
        if (!isTargetDisplayAvailable || destinationBounds == dragStartBounds) {
            // The task was dragged back to original position or there's no longer a valid display
            // to drag to, so calculating final bounds in case display is not available.
            if (destinationBounds != dragEndBounds) {
                // No transition needed because the task is moved back to the start position, but
                // the user moved the surface, and we should animate it.
                windowRepositionAnimator.start(leash, dragEndBounds, destinationBounds)
            } else {
                // Make the surface visible just in case it was moved off-screen.
                val t = transactionPool.acquire()
                t.setPosition(leash, dragStartBounds.left.toFloat(), dragStartBounds.top.toFloat())
                t.apply()
                transactionPool.release(t)
            }
            return true
        }

        if (destinationBounds != dragEndBounds) {
            // Drag bounds were snapped and we want to animate that.
            val onTransitionStateChange: (SurfaceControl.Transaction) -> Unit = { transition ->
                multiDisplayDragMoveIndicatorController.onDragEnd(taskInfo.taskId, transition)
            }
            windowRepositionAnimationHandler.startTransition(
                taskInfo,
                dragEndBounds,
                destinationBounds,
                onAnimationStart = onTransitionStateChange,
                onAnimationCanceled = onTransitionStateChange,
            )
            return false
        }

        // That's a simple user drag, just match task bounds to leash bounds.
        startBoundsChangeTransition(taskInfo, destinationBounds, windowDragTransitionHandler)
        return false
    }

    /** @see PinnedWindowRepositionAnimationHandler.setOnTaskRepositionAnimationListener */
    fun setOnTaskRepositionAnimationListener(listener: OnTaskRepositionAnimationListener?) {
        windowRepositionAnimationHandler.setOnTaskRepositionAnimationListener(listener)
    }

    private fun startBoundsChangeTransition(
        taskInfo: TaskInfo,
        bounds: Rect,
        handler: Transitions.TransitionHandler? = null,
    ) {
        val wct = WindowContainerTransaction()
        wct.setBounds(taskInfo.token, bounds)
        transitions.startTransition(TRANSIT_CHANGE, wct, handler)
    }

    fun requestTaskLocationChange(
        triggerTask: TaskInfo,
        requestedLocation: TransitionRequestInfo.RequestedLocation,
        outWct: WindowContainerTransaction,
    ) {
        val newBounds =
            presentationController.calculateNewTaskBounds(triggerTask, requestedLocation) ?: return
        if (triggerTask.isFloatingWindow) {
            presentationController.applyPresentation(triggerTask, newBounds, outWct,
                triggerTask.floatingWindowScale)
        } else outWct.setBounds(triggerTask.token, newBounds)
    }

    // TODO(b/449681882): Remove when Handler introduces its own state management for animations.
    // Use PinnedLayerUiState instead.
    fun cleanup(transition: IBinder) {
        activeTransitions.remove(transition)
    }

    fun addPinnedTasksListener(listener: PinnedTasksListener) {
        pinnedTasksListeners += listener
    }

    fun removePinnedTasksListener(listener: PinnedTasksListener) {
        pinnedTasksListeners -= listener
    }

    override fun onTransitionReady(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
    ) {
        miniInteractions?.onTransitionReady(info, startTransaction, finishTransaction)
        // TODO(b/449681882): Clean transitions here. Handler should track animation data
        // separately. Use PinnedLayerUiState instead.
        val transitions = activeTransitions.getOrDefault(transition, emptySet())
        transitions.forEach { transition ->
            logV("onTransitionReady: Pin layer transition ready: %s", transition)
            when (transition) {
                is ActiveTransition.Pin -> {
                    val updatedTask = info.changes
                        .firstOrNull { it.taskInfo?.taskId == transition.taskInfo.taskId }?.taskInfo
                        ?: transition.taskInfo
                    pin(updatedTask)

                    // Only send result if the transition caused by an API request.
                    if (transition.resultCallback != null) {
                        sendWindowingLayerResult(RESULT_APPROVED, transition.resultCallback)
                    }
                }
                is ActiveTransition.Unpin -> {
                    val isMovingToBack =
                        info.changes.any {
                            it.taskInfo?.token == transition.taskInfo.token &&
                                it.mode == TRANSIT_TO_BACK
                        }
                    unpin(
                        transition.taskInfo,
                        rememberAsPinned = transition.minimized || isMovingToBack,
                    )
                }
            }
        }
        info.changes.forEach { change ->
            val task = change.taskInfo ?: return@forEach
            if (task.isFloatingWindow && change.mode != TRANSIT_CLOSE) {
                val restoring = task.taskId in minimizedTasks &&
                    (TransitionUtil.isOpeningType(change.mode) ||
                        (change.mode == TRANSIT_CHANGE &&
                            task.configuration.windowConfiguration.isAlwaysOnTop))
                if (task.taskId !in pinnedTasks || restoring) {
                    val wct = WindowContainerTransaction()
                    minimizeOldestIfNeeded(task, wct)
                    minimizedTasks -= task.taskId
                    removeMinimizeHandle(task.taskId)
                    pin(task)
                    if (restoring) {
                        wct.merge(getLayerPinnedWct(task.token), true)
                        wct.setHidden(task.token, keyguardShowing || recentsRunning || overviewShowing)
                        wct.reorder(task.token, true)
                    }
                    if (!wct.isEmpty) shellTaskOrganizer.applyTransaction(wct)
                }
                if (!restoring && !task.configuration.windowConfiguration.isAlwaysOnTop &&
                    task.taskId in visiblePinnedTasks &&
                    !keyguardShowing && !recentsRunning && !overviewShowing) {
                    visiblePinnedTasks -= task.taskId
                    minimizedTasks += task.taskId
                    addMinimizeHandle(task)
                }
                presentationController.rememberBounds(task)
            } else if (task.taskId in pinnedTasks &&
                (change.mode == TRANSIT_CLOSE || task.windowingMode != WINDOWING_MODE_FREEFORM ||
                    task.hasParentTask())) {
                unpin(task)
            }
        }
        if (keyguardShowing || recentsRunning || overviewShowing) updateVisibility()
    }

    override fun onTransitionFinished(transition: IBinder, aborted: Boolean) {
        miniInteractions?.onTransitionFinished(aborted)
        val pending = activeTransitions.remove(transition).orEmpty()
        if (aborted) {
            val liveFloatingIds = shellTaskOrganizer.getRunningTasks(DEFAULT_DISPLAY)
                .filter { it.isFloatingWindow }.map { it.taskId }
            (pinnedTasks + visiblePinnedTasks + liveFloatingIds).toList().forEach { id ->
                val task = shellTaskOrganizer.getRunningTaskInfo(id)
                if (task == null || !task.isFloatingWindow) {
                    task?.let { unpin(it) }
                    pinnedTasks -= id
                    visiblePinnedTasks -= id
                    minimizedTasks -= id
                    removeMinimizeHandle(id)
                } else if (task.configuration.windowConfiguration.isAlwaysOnTop) {
                    pinnedTasks += id
                    minimizedTasks -= id
                    removeMinimizeHandle(id)
                    visiblePinnedTasks += id
                } else {
                    pinnedTasks += id
                    visiblePinnedTasks -= id
                    minimizedTasks += id
                    if (id !in minimizeState.entries) addMinimizeHandle(task)
                }
            }

            pending.filterIsInstance<ActiveTransition.Pin>().forEach {
                val task = shellTaskOrganizer.getRunningTaskInfo(it.taskInfo.taskId)
                if (task?.isFloatingWindow != true) unpin(it.taskInfo)
            }
        }
    }

    private fun pin(taskInfo: TaskInfo) {
        pinnedTasks += taskInfo.taskId
        if (taskInfo.taskId !in minimizedTasks) visiblePinnedTasks += taskInfo.taskId
        currentPinnedTaskId = taskInfo.taskId
        pinnedTasksListeners.forEach { it.onPinnedTasksAdded(taskInfo) }
    }

    private fun unpin(taskInfo: TaskInfo, rememberAsPinned: Boolean = false) {
        if (!rememberAsPinned) miniInteractions?.remove(taskInfo)
        frameRates?.update()
        visiblePinnedTasks -= taskInfo.taskId
        if (rememberAsPinned) {
            minimizedTasks += taskInfo.taskId
            addMinimizeHandle(taskInfo)
        }
        if (!rememberAsPinned) {
            minimizedTasks -= taskInfo.taskId
            removeMinimizeHandle(taskInfo.taskId)
            presentationController.forgetTask(taskInfo.taskId)
            pinnedTasks -= taskInfo.taskId
            pinnedTasksListeners.forEach { it.onPinnedTasksRemoved(taskInfo) }
        }

        if (currentPinnedTaskId == taskInfo.taskId) {
            currentPinnedTaskId = null
        }
    }

    private fun isPinningSupported(task: TaskInfo): Boolean =
        // check support only for tasks that are not pinned yet.
        isPinned(task.taskId) || presentationController.isTaskSupportedForPinning(task)

    fun sendWindowingLayerResult(result: Int, callback: IRemoteCallback) {
        val bundle = Bundle()
        bundle.putInt(REMOTE_CALLBACK_RESULT_KEY, result)
        try {
            callback.sendResult(bundle)
        } catch (e: RemoteException) {
            logW("Failed to invoke callback, error=%s", e)
        }
    }

    /** A set of strategies that changes how the task is unpinned. */
    enum class UnpinStrategy {
        /**
         * The task should be unpinned visually and hidden from the display. The controller keeps
         * the task as pinned.
         */
        HIDE,

        /** The task should be unpinned and closed. The task is not kept as pinned. */
        CLOSE,

        /**
         * The task should be unpinned and kept visible on the display. This is useful when another
         * handler wants to decide where to put the task.
         */
        CLEAN,
    }

    interface PinnedTasksListener {
        fun onPinnedTasksAdded(taskInfo: TaskInfo)

        fun onPinnedTasksRemoved(taskInfo: TaskInfo)
    }
}
