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

package com.android.wm.shell.windowdecor.common

import android.app.ActivityManager
import android.app.WindowConfiguration
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import android.window.DesktopExperienceFlags
import android.window.DesktopExperienceFlags.ENABLE_PROJECTED_DISPLAY_DESKTOP_MODE
import com.android.internal.policy.DesktopModeCompatPolicy
import com.android.wm.shell.bubbles.BubbleController
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.LockTaskChangeListener
import com.android.wm.shell.desktopmode.DesktopWallpaperActivity.Companion.isWallpaperTask
import com.android.wm.shell.shared.bubbles.BubbleFlagHelper
import com.android.wm.shell.shared.desktopmode.DesktopState
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.UserChangeListener
import java.util.Optional

/**
 * Resolves whether, given a task and its associated display that it is currently on, to create the
 * app's caption or not.
 */
class CaptionVisibilityHelper(
    private val displayController: DisplayController,
    private val desktopModeCompatPolicy: DesktopModeCompatPolicy,
    private val desktopState: DesktopState,
    private val bubbleController: Optional<BubbleController>,
    private val lockTaskChangeListener: LockTaskChangeListener,
) {
    var splitScreenController: SplitScreenController? = null
    var appHandleMode: Int = APP_HANDLE_MODE_SHOW
        private set

    /** Observes the foreground user's handle preference on the Shell main thread. */
    fun observeAppHandleMode(
        context: Context,
        handler: Handler,
        shellController: ShellController,
        onModeChanged: Runnable,
    ) {
        val resolver = context.contentResolver
        var userId = shellController.currentUserId
        fun updateMode() {
            val value = Settings.System.getIntForUser(
                resolver, Settings.System.APP_HANDLE_MODE, APP_HANDLE_MODE_SHOW, userId)
            val mode = if (value in APP_HANDLE_MODE_SHOW..APP_HANDLE_MODE_OFF) {
                value
            } else {
                APP_HANDLE_MODE_SHOW
            }
            if (appHandleMode == mode) return
            appHandleMode = mode
            onModeChanged.run()
        }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                updateMode()
            }
        }
        shellController.addUserChangeListener(object : UserChangeListener {
            override fun onUserChanged(newUserId: Int, userContext: Context) {
                resolver.unregisterContentObserver(observer)
                userId = newUserId
                resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.APP_HANDLE_MODE),
                    false, observer, userId)
                updateMode()
            }
        })
    }

    /**
     * Returns, given a task's attribute and its display attribute, whether the app caption should
     * be created or not for this task.
     */
    @JvmOverloads
    fun shouldCreateCaption(
        taskInfo: ActivityManager.RunningTaskInfo,
        isKeyguardVisAndOccluded: Boolean,
        isAppHandle: Boolean = taskInfo.windowingMode != WINDOWING_MODE_FREEFORM &&
            !taskInfo.isFloatingWindow,
    ): Boolean {
        if (isAppHandle && appHandleMode == APP_HANDLE_MODE_OFF) return false

        // If DisplayController doesn't have it tracked, it could be a private/managed display, so
        // return false if display is null
        val display = displayController.getDisplay(taskInfo.displayId) ?: return false

        if (!ENABLE_PROJECTED_DISPLAY_DESKTOP_MODE.isTrue) {
            return allowedForTask(taskInfo, display, isKeyguardVisAndOccluded)
        }
        return allowedForTask(taskInfo, display, isKeyguardVisAndOccluded) &&
            allowedForDisplay(display)
    }

    private fun allowedForTask(
        taskInfo: ActivityManager.RunningTaskInfo,
        display: Display,
        isKeyguardVisibleAndOccluded: Boolean,
    ): Boolean {
        if (taskInfo.windowingMode == WINDOWING_MODE_FREEFORM) {
            return true
        }

        if (
            DesktopExperienceFlags.ENABLE_ADD_WINDOW_DECORATION_TO_ALL_TASKS.isTrue &&
                lockTaskChangeListener.isTaskLocked
        ) {
            // Return false if task is in kiosk mode
            return false
        }

        if (
            DesktopExperienceFlags.ENABLE_ADD_WINDOW_DECORATION_TO_ALL_TASKS.isTrue &&
                isKeyguardVisibleAndOccluded
        ) {
            // Return false if task is showing on top of keyguard
            return false
        }

        if (
            !DesktopExperienceFlags.ENABLE_ADD_WINDOW_DECORATION_TO_ALL_TASKS.isTrue &&
                splitScreenController?.isTaskRootOrStageRoot(taskInfo.taskId) == true
        ) {
            return false
        }

        if (desktopModeCompatPolicy.shouldDisableDesktopEntryPoints(taskInfo)) {
            return false
        }

        // TODO (b/382023296): Remove once we no longer rely on
        //  DesktopModeFlags.ENABLE_PROJECTED_DISPLAY_DESKTOP_MODE as it is taken care of in
        //  #allowedForDisplay
        val isOnLargeScreen =
            display.minSizeDimensionDp >= WindowManager.LARGE_SCREEN_SMALLEST_SCREEN_WIDTH_DP
        if (
            !desktopState.canEnterDesktopMode &&
                desktopState.overridesShowAppHandle &&
                !isOnLargeScreen
        ) {
            // Devices with multiple screens may enable the app handle but it should not show on
            // small screens
            return false
        }
        if (
            BubbleFlagHelper.enableBubbleToFullscreen() &&
                !desktopState.isDesktopModeSupportedOnDisplay(display)
        ) {
            // TODO(b/388853233): enable handles for split tasks once drag to bubble is enabled
            if (taskInfo.windowingMode != WindowConfiguration.WINDOWING_MODE_FULLSCREEN) {
                return false
            }
        }

        // The app bubbles that are organized by the bubble root task are not alwaysOnTop, and
        // the other bubble tasks (e.g. chat bubbles) reset alwaysOnTop when reordering its task
        // to the bottom to hide its task view. So, we need to explicitly check here to prevent
        // showing app handles for bubbles.
        fun ActivityManager.RunningTaskInfo.isBubble(): Boolean =
            if (BubbleFlagHelper.enableCreateAnyBubble()) {
                bubbleController
                    .map { controller ->
                        controller.hasStableBubbleForTask(taskId) ||
                            (BubbleFlagHelper.enableRootTaskForBubble() &&
                                controller.bubbleHelper.isAppBubbleTask(this))
                    }
                    .orElse(false)
            } else {
                false
            }

        // When window decoration for all tasks is enabled, tasks without standard activity
        // types and wallpaper tasks should already be filtered out, so these do not need to
        // be checked again.
        if (
            !DesktopExperienceFlags.ENABLE_ADD_WINDOW_DECORATION_TO_ALL_TASKS.isTrue &&
                (taskInfo.activityType != WindowConfiguration.ACTIVITY_TYPE_STANDARD ||
                    isWallpaperTask(taskInfo))
        ) {
            return false
        }

        return desktopState.canEnterDesktopModeOrShowAppHandle &&
            taskInfo.windowingMode != WindowConfiguration.WINDOWING_MODE_PINNED &&
            !taskInfo.configuration.windowConfiguration.isAlwaysOnTop &&
            !taskInfo.isBubble()
    }

    companion object {
        const val APP_HANDLE_MODE_SHOW = 0
        const val APP_HANDLE_MODE_TRANSPARENT = 1
        const val APP_HANDLE_MODE_OFF = 2
    }

    private fun allowedForDisplay(display: Display): Boolean {
        if (
            display.type != Display.TYPE_INTERNAL &&
                !displayController.isDisplayInTopology(display.displayId)
        ) {
            return false
        }

        if (desktopState.isDesktopModeSupportedOnDisplay(display)) {
            return true
        }
        // If on default display and on Large Screen (unfolded), show app handle
        return desktopState.overridesShowAppHandle &&
            display.minSizeDimensionDp >= WindowManager.LARGE_SCREEN_SMALLEST_SCREEN_WIDTH_DP
    }
}
