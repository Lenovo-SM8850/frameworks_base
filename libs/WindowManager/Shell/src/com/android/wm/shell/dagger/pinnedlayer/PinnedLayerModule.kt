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

package com.android.wm.shell.dagger.pinnedlayer

import android.app.ActivityManager.RunningTaskInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Handler
import android.os.PowerManager
import android.view.Display.DEFAULT_DISPLAY
import android.view.SurfaceControl
import android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.MultiDisplayDragMoveIndicatorController
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.TaskStackListenerImpl
import com.android.wm.shell.dagger.WMShellBaseModule
import com.android.wm.shell.dagger.WMSingleton
import com.android.wm.shell.desktopmode.DesktopTasksController
import com.android.wm.shell.desktopmode.DesktopUserRepositories
import com.android.wm.shell.desktopmode.NormalAppLayerController
import com.android.wm.shell.desktopmode.ShellDesktopState
import com.android.wm.shell.desktopmode.WindowDragTransitionHandler
import com.android.wm.shell.pinnedlayer.phone.FloatingTidyController
import com.android.wm.shell.pinnedlayer.phone.FloatingTidyGestureMonitor
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerController
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerFlags
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerHandler
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerPermissionObserver
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerPresentationController
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerUiState
import com.android.wm.shell.pinnedlayer.phone.PinnedWindowRepositionAnimationHandler
import com.android.wm.shell.pinnedlayer.phone.PinnedWindowRepositionAnimator
import com.android.wm.shell.recents.RecentsTransitionHandler
import com.android.wm.shell.shared.TransactionPool
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.sysui.ConfigurationChangeListener
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import dagger.Module
import dagger.Provides
import java.util.Optional

@Module(includes = [WMShellBaseModule::class])
object PinnedLayerModule {

    @WMSingleton
    @Provides
    fun providePinnedLayerHandler(
        context: Context,
        taskStackListener: TaskStackListenerImpl,
        displayController: DisplayController,
        shellTaskOrganizer: ShellTaskOrganizer,
        @ShellMainThread mainHandler: Handler,
        shellInit: ShellInit,
        transitions: Transitions,
        shellController: ShellController,
        recentsTransitionHandler: RecentsTransitionHandler,
        pinnedLayerController: Optional<PinnedLayerController>,
        pinnedLayerUiState: Optional<PinnedLayerUiState>,
        // observer is unused here, but required to inject to make sure it is created so it can
        // register itself as a listener.
        pinnedLayerPermissionObserver: Optional<PinnedLayerPermissionObserver>,
        normalAppLayerController: Optional<NormalAppLayerController>,
        desktopTasksController: Optional<DesktopTasksController>,
        desktopUserRepositories: Optional<DesktopUserRepositories>,
    ): Optional<PinnedLayerHandler> {
        if (PinnedLayerFlags.isPinnedLayerEnabled()) {
            pinnedLayerController.get().configureVisibility(shellController, recentsTransitionHandler)
            val powerManager = context.getSystemService(PowerManager::class.java)
            val keyguardManager = context.getSystemService(KeyguardManager::class.java)
            val pinned = pinnedLayerController.get()
            shellInit.addInitCallback({
                lateinit var tidy: FloatingTidyController
                val monitor = FloatingTidyGestureMonitor.create(context, displayController, mainHandler) {
                    tidy.onHomeSwipeUp()
                }
                tidy = FloatingTidyController(taskStackListener, recentsTransitionHandler, shellController,
                    transitions, { powerManager.isInteractive }, pinned::tidyFloatingWindows,
                    canMonitorGestures = {
                        displayController.getDisplayContext(DEFAULT_DISPLAY)?.resources?.getInteger(
                            com.android.internal.R.integer.config_navBarInteractionMode) ==
                            NAV_BAR_MODE_GESTURAL && pinned.hasExpandedFloatingWindows() &&
                            !keyguardManager.isKeyguardLocked
                    },
                    gestureMonitoringChanged = monitor::setEnabled)
                // Ignore position-only task updates during floating drags.
                data class Visibility(val display: Int, val visible: Boolean, val floating: Boolean)
                val visibility = mutableMapOf<Int, Visibility>()
                fun updateTask(task: RunningTaskInfo) {
                    val previous = visibility[task.taskId]
                    if (previous != null && previous.display == task.displayId && previous.visible == task.isVisible &&
                        previous.floating == task.isFloatingWindow) return
                    visibility[task.taskId] = Visibility(task.displayId, task.isVisible, task.isFloatingWindow)
                    tidy.updateGestureMonitoring()
                }
                shellTaskOrganizer.initialTasks.forEach { updateTask(it.first) }
                shellTaskOrganizer.addTaskAppearedListener(object : ShellTaskOrganizer.TaskAppearedListener {
                    override fun onTaskAppeared(task: RunningTaskInfo, leash: SurfaceControl) {
                        mainHandler.post {
                            shellTaskOrganizer.getRunningTaskInfo(task.taskId)?.let(::updateTask)
                        }
                    }
                })
                shellTaskOrganizer.addTaskInfoChangedListener(object : ShellTaskOrganizer.TaskInfoChangedListener {
                    override fun onTaskInfoChanged(task: RunningTaskInfo) { updateTask(task) }
                })
                shellTaskOrganizer.addTaskVanishedListener(object : ShellTaskOrganizer.TaskVanishedListener {
                    override fun onTaskVanished(task: RunningTaskInfo) {
                        visibility.remove(task.taskId)
                        tidy.updateGestureMonitoring()
                    }
                })
                pinned.addMiniListener { tidy.updateGestureMonitoring() }
                displayController.addDisplayWindowListener(object : DisplayController.OnDisplaysChangedListener {
                    override fun onDisplayAdded(displayId: Int) {
                        if (displayId == DEFAULT_DISPLAY) tidy.updateGestureMonitoring()
                    }
                    override fun onDisplayConfigurationChanged(displayId: Int, config: Configuration) {
                        if (displayId == DEFAULT_DISPLAY) {
                            monitor.setEnabled(false)
                            tidy.updateGestureMonitoring()
                        }
                    }
                    override fun onDisplayRemoved(displayId: Int) {
                        if (displayId == DEFAULT_DISPLAY) monitor.setEnabled(false)
                    }
                })
                shellController.addConfigurationChangeListener(object : ConfigurationChangeListener {
                    override fun onConfigurationChanged(newConfig: Configuration) {
                        monitor.setEnabled(false)
                        tidy.updateGestureMonitoring()
                    }
                })
                context.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        tidy.updateGestureMonitoring()
                    }
                }, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }, null, mainHandler)
                tidy.updateGestureMonitoring()
            }, pinned)
            return Optional.of(
                PinnedLayerHandler(
                    shellInit = shellInit,
                    transitions = transitions,
                    pinnedLayerController = pinnedLayerController.get(),
                    pinnedLayerUiState = pinnedLayerUiState.get(),
                    normalLayerController = normalAppLayerController.get(),
                    desktopUserRepositories = desktopUserRepositories.orElse(null),
                    desktopTasksController = desktopTasksController.orElse(null),
                )
            )
        }
        return Optional.empty()
    }

    @WMSingleton
    @Provides
    fun providePinnedLayerController(
        context: Context,
        shellInit: ShellInit,
        transitions: Transitions,
        displayController: DisplayController,
        @com.android.wm.shell.shared.annotations.ShellBackgroundThread backgroundExecutor: ShellExecutor,
        @ShellMainThread mainExecutor: ShellExecutor,
        shellTaskOrganizer: ShellTaskOrganizer,
        desktopState: ShellDesktopState,
        windowDragTransitionHandler: WindowDragTransitionHandler,
        windowRepositionAnimationHandler: PinnedWindowRepositionAnimationHandler,
        windowRepositionAnimator: PinnedWindowRepositionAnimator,
        transactionPool: TransactionPool,
        rootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer,
        multiDisplayDragMoveIndicatorController: MultiDisplayDragMoveIndicatorController,
    ): Optional<PinnedLayerController> {
        if (PinnedLayerFlags.isPinnedLayerEnabled()) {
            return Optional.of(
                PinnedLayerController(
                    shellInit = shellInit,
                    transitions = transitions,
                    taskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer,
                    shellTaskOrganizer = shellTaskOrganizer,
                    presentationController =
                        PinnedLayerPresentationController(context, displayController, desktopState,
                            com.android.wm.shell.pinnedlayer.phone.FloatingWindowGeometryStore(
                                context.filesDir, backgroundExecutor)),
                    windowDragTransitionHandler = windowDragTransitionHandler,
                    windowRepositionAnimationHandler = windowRepositionAnimationHandler,
                    windowRepositionAnimator = windowRepositionAnimator,
                    transactionPool = transactionPool,
                    desktopState = desktopState,
                    multiDisplayDragMoveIndicatorController =
                        multiDisplayDragMoveIndicatorController,
                ).also { it.configureInteractions(context, displayController, mainExecutor) }
            )
        }
        return Optional.empty()
    }

    @WMSingleton
    @Provides
    fun providePinnedLayerUiState(): Optional<PinnedLayerUiState> {
        if (PinnedLayerFlags.isPinnedLayerEnabled()) {
            return Optional.of(PinnedLayerUiState())
        }
        return Optional.empty()
    }

    @WMSingleton
    @Provides
    fun providePinnedLayerPermissionObserver(
        context: Context,
        @ShellMainThread mainShellExecutor: ShellExecutor,
        pinnedLayerController: Optional<PinnedLayerController>,
    ): Optional<PinnedLayerPermissionObserver> =
        pinnedLayerController.map { controller ->
            PinnedLayerPermissionObserver(
                context = context,
                mainShellExecutor = mainShellExecutor,
                pinnedLayerController = controller,
            )
        }

    @WMSingleton
    @Provides
    fun providePinnedWindowRepositionAnimationHandler(
        transitions: Transitions,
        windowRepositionAnimator: PinnedWindowRepositionAnimator,
    ): PinnedWindowRepositionAnimationHandler {
        return PinnedWindowRepositionAnimationHandler(
            transitions = transitions,
            windowRepositionAnimator = windowRepositionAnimator,
        )
    }

    @WMSingleton
    @Provides
    fun providePinnedWindowRepositionAnimator(): PinnedWindowRepositionAnimator {
        return PinnedWindowRepositionAnimator { SurfaceControl.Transaction() }
    }
}
