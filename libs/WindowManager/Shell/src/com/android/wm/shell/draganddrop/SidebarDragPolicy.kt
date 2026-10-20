// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.draganddrop

import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.app.PendingIntent
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_PINNED
import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Display.DEFAULT_DISPLAY
import com.android.internal.policy.DesktopModeCompatPolicy
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerController
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerPresentationController.EntryDestination
import com.android.wm.shell.shared.draganddrop.DragAndDropConstants.EXTRA_SIDEBAR_APP_DRAG
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.splitscreen.SplitScreenController.AppDragSlot

/** Sidebar-only placement and launch. Ordinary app drags retain SplitDragPolicy. */
class SidebarDragPolicy(
    private val context: Context,
    private val split: SplitScreenController?,
    private val floating: PinnedLayerController?,
    private val tasks: ActivityTaskManager = ActivityTaskManager.getInstance(),
) {
    data class Destination(val bounds: Rect, val radius: Float, val slot: AppDragSlot? = null,
        val entry: EntryDestination? = null)

    private data class AppDrag(val pending: PendingIntent, val user: UserHandle,
        val component: ComponentName, val info: ActivityInfo, val supportsSplit: Boolean,
        val canFloat: Boolean, val floatingRadius: Float, val splitRadius: Float)

    private data class TaskSnapshot(val front: RunningTaskInfo?, val existing: RunningTaskInfo?,
        val activeSplit: Boolean, val capacity: Int, val occupied: Int,
        val slots: List<AppDragSlot>, val allowedSlots: Set<AppDragSlot>,
        val systemAllowsSplit: Boolean, val floatingEntry: EntryDestination?,
        val stableBounds: Rect, val preserveFloatingPosition: Boolean)

    private var app: AppDrag? = null
    private var snapshot: TaskSnapshot? = null
    private var displayId = DEFAULT_DISPLAY
    private var displayLayout: DisplayLayout? = null

    /** Resolves app identity, metadata, and eligibility once for this drag. */
    fun start(session: DragSession, displayId: Int) {
        app = null
        snapshot = null
        this.displayId = displayId
        displayLayout = session.displayLayout
        if (displayId != DEFAULT_DISPLAY) return
        val appData = session.appData ?: return
        val pending = appData.getParcelableExtra(ClipDescription.EXTRA_PENDING_INTENT,
            PendingIntent::class.java) ?: return
        val user = appData.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java) ?: return
        if (pending.creatorUid != Process.SYSTEM_UID) return
        val component = pending.intent?.component ?: return
        val userContext = try {
            context.createContextAsUser(user, 0)
        } catch (e: SecurityException) {
            return
        }
        val info = try {
            userContext.packageManager.getActivityInfo(component, 0)
        } catch (e: Exception) {
            return
        }
        val forceResizable = Settings.Global.getInt(context.contentResolver,
            Settings.Global.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES, 0) != 0
        app = AppDrag(pending, user, component, info,
            ActivityInfo.isResizeableMode(info.resizeMode) || forceResizable,
            canFloat(userContext, component, user.identifier, info, forceResizable),
            context.resources.getDimension(
                com.android.wm.shell.shared.R.dimen.desktop_windowing_freeform_rounded_corner_radius),
            com.android.internal.policy.ScreenDecorationsUtils.getWindowCornerRadius(context))
        refreshSnapshot()
    }

    /** Refreshes task and split state on task events, session/configuration updates, and DROP. */
    fun refreshSnapshot() {
        val app = app ?: return
        val running = tasks.getTasks(100, false)
        val front = running.firstOrNull { it.displayId == displayId && it.isVisible &&
            it.windowingMode != WINDOWING_MODE_PINNED && !it.isFloatingWindow &&
            !it.configuration.windowConfiguration.isAlwaysOnTop }
        val existing = running.firstOrNull { it.displayId == displayId &&
            it.userId == app.user.identifier && (it.baseActivity == app.component ||
                it.realActivity == app.component || it.baseIntent?.component == app.component) }
        val activeSplit = split?.isSplitScreenVisible == true
        val capacity = split?.getSplitCapacity(displayId) ?: 0
        val occupied = split?.getOccupiedSplitSlots(displayId) ?: 0
        val slots = split?.getAppDragSlots(displayId).orEmpty()
        val allowed = slots.filter { slot ->
            val companion = if (activeSplit) running.firstOrNull {
                it.displayId == displayId && it.isVisible && it.userId == app.user.identifier &&
                    split?.isTaskInSplitScreen(it.taskId) == true &&
                    split.getSplitPosition(it.taskId) != slot.position
            } else front
            split?.canStartSidebarSplit(app.component, app.user.identifier, companion) == true
        }.toSet()
        val stable = Rect().also { displayLayout?.getStableBounds(it) }
        val entry = if (app.canFloat && !stable.isEmpty) floating?.calculateSidebarEntry(
            app.component, app.user.identifier, displayId, app.info, existing,
            stable.centerX(), stable.centerY()) else null
        val restored = existing?.takeIf { it.isFloatingWindow }?.floatingWindowRestoreBounds
        val preservePosition = restored != null && !restored.isEmpty &&
            restored.width() <= stable.width() && restored.height() <= stable.height()
        snapshot = TaskSnapshot(front, existing, activeSplit, capacity, occupied, slots, allowed,
            floating?.canResolveSidebarSplit() == true, entry, stable, preservePosition)
    }

    /** Hit-tests cached state and calculates floating geometry without querying task/app services. */
    fun resolve(x: Int, y: Int): Destination? {
        val app = app ?: return null
        val state = snapshot ?: return null
        val slot = resolveSplitSlot(state.front, app.supportsSplit, state.systemAllowsSplit,
            state.activeSplit, state.occupied, state.capacity, state.slots, x, y)
        if (slot != null && slot in state.allowedSlots) {
            return Destination(Rect(slot.bounds), app.splitRadius, slot = slot)
        }
        if (!app.canFloat) return null
        val entry = state.floatingEntry ?: return null
        val stable = state.stableBounds
        val bounds = Rect(entry.bounds)
        if (bounds.isEmpty || stable.isEmpty || bounds.width() > stable.width() ||
            bounds.height() > stable.height()) return null
        if (!state.preserveFloatingPosition) {
            bounds.offsetTo((x - bounds.width() / 2).coerceIn(
                stable.left, stable.right - bounds.width()),
                (y - bounds.height() / 2).coerceIn(stable.top, stable.bottom - bounds.height()))
        }
        return Destination(bounds, app.floatingRadius, entry = entry.copy(bounds = bounds))
    }

    fun launch(destination: Destination): Boolean {
        val app = app ?: return false
        val pending = app.pending
        val user = app.user
        val component = app.component
        val options = ActivityOptions.makeBasic().apply {
            setLaunchDisplayId(displayId)
            setDisallowEnterPictureInPictureWhileLaunching(true)
            setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
        }
        destination.slot?.let { slot ->
            val controller = split ?: return false
            // Use a fresh options bundle: floating flags never enter a split transaction.
            controller.startIntent(pending, user.identifier, null, slot.position,
                options.toBundle(), null, slot.index)
            return true
        }
        val controller = floating ?: return false
        val entry = destination.entry ?: return false
        options.setLaunchFloatingWindow(true)
        val cookie = controller.setSidebarLaunchOptions(options, component, user.identifier,
            displayId, entry) ?: return false
        return try {
            pending.send(context, 0, null, null, null, null, options.toBundle())
            true
        } catch (e: PendingIntent.CanceledException) {
            controller.clearSidebarLaunch(cookie)
            Log.w(TAG, "Unable to launch sidebar app", e)
            false
        } catch (e: RuntimeException) {
            controller.clearSidebarLaunch(cookie)
            Log.w(TAG, "Unable to launch sidebar app", e)
            false
        }
    }

    private fun canFloat(context: Context, component: ComponentName, userId: Int,
        info: ActivityInfo, forceResizable: Boolean): Boolean {
        if (floating == null) return false
        if (!ActivityInfo.isResizeableMode(info.resizeMode) && !info.supportsPictureInPicture() &&
            !forceResizable) return false
        return try {
            val resources = context.packageManager.getResourcesForApplication(info.applicationInfo)
            val theme = resources.newTheme().apply {
                applyStyle(if (info.themeResource != 0) info.themeResource else
                    if (info.applicationInfo.targetSdkVersion < android.os.Build.VERSION_CODES.HONEYCOMB)
                        android.R.style.Theme else android.R.style.Theme_Holo, true)
            }
            val attrs = theme.obtainStyledAttributes(com.android.internal.R.styleable.Window)
            val noDisplay: Boolean
            val transparent: Boolean
            try {
                noDisplay = attrs.getBoolean(com.android.internal.R.styleable.Window_windowNoDisplay,
                    false)
                transparent = ActivityInfo.isTranslucentOrFloating(attrs) &&
                    !attrs.getBoolean(com.android.internal.R.styleable.Window_windowShowWallpaper,
                        false)
            } finally {
                attrs.recycle()
            }
            val target = info.targetActivity?.let { ComponentName(info.packageName, it) } ?: component
            val policy = DesktopModeCompatPolicy(context)
            !noDisplay && !policy.isTopActivityExemptFromDesktopWindowing(target, noDisplay,
                transparent, 1, userId, info, ACTIVITY_TYPE_STANDARD) &&
                !policy.isPackageLaunchInFullscreen(target)
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "SidebarDragPolicy"

        @JvmStatic
        fun isSidebarDrag(data: ClipData?): Boolean {
            if (data == null || data.itemCount != 1 ||
                !data.description.hasMimeType(ClipDescription.MIMETYPE_APPLICATION_ACTIVITY) ||
                data.description.extras?.getBoolean(EXTRA_SIDEBAR_APP_DRAG, false) != true) return false
            return data.getItemAt(0).intent?.getParcelableExtra(
                ClipDescription.EXTRA_PENDING_INTENT, PendingIntent::class.java)?.creatorUid ==
                Process.SYSTEM_UID
        }

        /** Full splits replace the pointed slot; partial splits always insert into a free slot. */
        @com.android.internal.annotations.VisibleForTesting
        fun resolveSplitSlot(front: RunningTaskInfo?, incomingSupportsSplit: Boolean,
            systemAllowsSplit: Boolean, active: Boolean, occupied: Int, capacity: Int,
            slots: List<AppDragSlot>, x: Int, y: Int): AppDragSlot? {
            if (!systemAllowsSplit || !incomingSupportsSplit || capacity <= 0 || slots.isEmpty())
                return null
            if (!active && (front?.activityType != ACTIVITY_TYPE_STANDARD ||
                    front.windowingMode != WINDOWING_MODE_FULLSCREEN || !front.supportsMultiWindow))
                return null
            val valid = slots.filter { !it.bounds.isEmpty }
            if (!active || occupied >= capacity) return valid.firstOrNull { it.hitBounds.contains(x, y) }
            val free = valid.filter { !it.occupied }
            return free.firstOrNull { it.hitBounds.contains(x, y) } ?: free.minByOrNull {
                val dx = it.bounds.centerX().toLong() - x
                val dy = it.bounds.centerY().toLong() - y
                dx * dx + dy * dy
            }
        }
    }
}
