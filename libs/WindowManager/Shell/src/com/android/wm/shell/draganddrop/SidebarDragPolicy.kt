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
import android.content.pm.LauncherApps
import android.graphics.RectF
import android.graphics.Rect
import com.android.wm.shell.shared.draganddrop.DragAndDropConstants.EXTRA_DISALLOW_HIT_REGION
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
import kotlin.math.abs

/** Shared app-drag placement: feasible split insertion, otherwise an eligible floating window. */
class SidebarDragPolicy(
    private val context: Context,
    private val split: SplitScreenController?,
    private val floating: PinnedLayerController?,
    private val tasks: ActivityTaskManager = ActivityTaskManager.getInstance(),
) {
    data class Destination(val bounds: Rect, val slot: AppDragSlot? = null,
        val entry: EntryDestination? = null, val pendingSplit: Boolean = false)

    private data class AppDrag(val pending: PendingIntent?, val taskId: Int, val user: UserHandle,
        val component: ComponentName, val info: ActivityInfo, val supportsSplit: Boolean,
        val canFloat: Boolean)

    private data class TaskSnapshot(val front: RunningTaskInfo?, val existing: RunningTaskInfo?,
        val activeSplit: Boolean, val capacity: Int, val occupied: Int,
        val slots: List<AppDragSlot>, val allowedSlots: Set<AppDragSlot>,
        val systemAllowsSplit: Boolean, val floatingEntry: EntryDestination?,
        val stableBounds: Rect, val preserveFloatingPosition: Boolean,
        val running: List<RunningTaskInfo>, val pendingSlot: AppDragSlot?)

    private var sidebarSource = false
    private val disallowHitRegion = RectF()
    private var app: AppDrag? = null
    val handlesAppDrag: Boolean get() = app != null
    private var snapshot: TaskSnapshot? = null
    private var displayId = DEFAULT_DISPLAY
    private var displayLayout: DisplayLayout? = null

    /** Resolves app identity, metadata, and eligibility once for this drag. */
    fun start(session: DragSession, displayId: Int) {
        app = null
        sidebarSource = session.isSidebarAppDrag
        snapshot = null
        this.displayId = displayId
        displayLayout = session.displayLayout
        if (displayId != DEFAULT_DISPLAY) return
        val appData = session.appData ?: return
        disallowHitRegion.set(appData.getParcelableExtra(EXTRA_DISALLOW_HIT_REGION, RectF::class.java) ?: RectF())
        val resolved = session.activityInfo
        val user = appData.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)
            ?: resolved?.applicationInfo?.uid?.let { UserHandle.of(UserHandle.getUserId(it)) }
            ?: return
        val taskId = appData.getIntExtra(Intent.EXTRA_TASK_ID, -1)
        val pending = if (session.clipDescription.hasMimeType(
                ClipDescription.MIMETYPE_APPLICATION_SHORTCUT)) {
            val pkg = appData.getStringExtra(Intent.EXTRA_PACKAGE_NAME) ?: return
            val id = appData.getStringExtra(Intent.EXTRA_SHORTCUT_ID) ?: return
            appData.getParcelableExtra(ClipDescription.EXTRA_PENDING_INTENT, PendingIntent::class.java)
                ?: try { context.getSystemService(LauncherApps::class.java)
                .getShortcutIntent(pkg, id, null, user) } catch (e: RuntimeException) { null }
        } else appData.getParcelableExtra(ClipDescription.EXTRA_PENDING_INTENT, PendingIntent::class.java)
        if (pending == null && taskId < 0) return
        if (session.isSidebarAppDrag && pending?.creatorUid != Process.SYSTEM_UID) return
        val component = pending?.intent?.component ?: resolved?.let { ComponentName(it.packageName, it.name) }
            ?: return
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
        app = AppDrag(pending, taskId, user, component, info,
            ActivityInfo.isResizeableMode(info.resizeMode) || forceResizable,
            canFloat(userContext, component, user.identifier, info, forceResizable))
        refreshSnapshot()
    }

    /** Refreshes task and split state on task events, session/configuration updates, and DROP. */
    fun refreshSnapshot() {
        val app = app ?: return
        val running = tasks.getTasks(100, false)
        val front = running.firstOrNull { it.displayId == displayId && it.isVisible &&
            it.windowingMode != WINDOWING_MODE_PINNED && !it.isFloatingWindow &&
            !it.configuration.windowConfiguration.isAlwaysOnTop }
        val existing = running.firstOrNull { it.taskId == app.taskId } ?: running.firstOrNull { it.displayId == displayId &&
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
            floating?.canResolveSidebarSplit() == true, entry, stable, preservePosition, running,
            if (sidebarSource) split?.getPendingSidebarSplitSlot(displayId) else null)
    }

    /** Hit-tests cached state and calculates floating geometry without querying task/app services. */
    fun resolve(x: Int, y: Int): Destination? {
        if (disallowHitRegion.contains(x.toFloat(), y.toFloat())) return null
        val app = app ?: return null
        val state = snapshot ?: return null
        state.pendingSlot?.takeIf { app.supportsSplit && it.hitBounds.contains(x, y) }?.let {
            return Destination(Rect(it.bounds), slot = it, pendingSplit = true)
        }
        val slot = resolveSplitSlot(state.front, app.supportsSplit, state.systemAllowsSplit,
            state.activeSplit, state.occupied, state.capacity, state.slots, x, y)
        if (slot != null && slot in state.allowedSlots) {
            return Destination(Rect(slot.bounds), slot = slot)
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
        return Destination(bounds, entry = entry.copy(bounds = bounds))
    }

    /** Reuses the taskbar drop-zone renderer with the sidebar's exact destination geometry. */
    fun previewZones(destination: Destination): List<Pair<Rect, RunningTaskInfo?>> {
        val state = snapshot ?: return emptyList()
        if (destination.slot == null) return listOf(Rect(destination.bounds) to state.existing)
        if (destination.pendingSplit) return listOf(Rect(destination.bounds) to null)
        val selected = destination.slot
        resultLayout(state, selected)?.let { return it }
        // Alternatives of other layouts (three columns, splits below) overlap; drawing them all stacks margins.
        val family = state.slots.filter {
            it.threeColumns == selected.threeColumns && it.splitBelow == selected.splitBelow
        }
        val disjoint = mutableListOf<AppDragSlot>()
        (listOf(selected) + family).forEach { slot ->
            if (disjoint.none { it == slot || Rect.intersects(it.bounds, slot.bounds) }) disjoint += slot
        }
        val kept = family.filter { it in disjoint }.ifEmpty { listOf(selected) }
        columnInsertion(state, selected, kept)?.let { return it }
        return tile(kept.map { Rect(it.bounds) }).zip(kept).map { (tiled, slot) ->
            val task = if (!state.activeSplit) state.front else state.running.firstOrNull {
                it.displayId == displayId && it.isVisible && !it.isFloatingWindow &&
                    split?.isTaskInSplitScreen(it.taskId) == true &&
                    it.configuration.windowConfiguration.bounds.contains(
                        slot.bounds.centerX(), slot.bounds.centerY())
            }
            tiled to task
        }.distinctBy { it.first }
    }

    /**
     * Previews a new column: the existing apps move aside in their order, so a column centre, such as
     * the middle one over the old divider, need not lie on the app that ends up there.
     */
    private fun columnInsertion(
        state: TaskSnapshot,
        selected: AppDragSlot,
        columns: List<AppDragSlot>,
    ): List<Pair<Rect, RunningTaskInfo?>>? {
        if (!state.activeSplit || selected.occupied || !selected.threeColumns) return null
        val existing = state.running.filter {
            it.displayId == displayId && it.isVisible && !it.isFloatingWindow &&
                split?.isTaskInSplitScreen(it.taskId) == true
        }.distinctBy { it.taskId }.sortedBy { it.configuration.windowConfiguration.bounds.left }
        val ordered = tile(columns.map { Rect(it.bounds) }).zip(columns).sortedBy { it.first.left }
        if (ordered.none { it.second == selected } || existing.size != ordered.size - 1) return null
        val apps = existing.iterator()
        return ordered.map { (tiled, slot) -> tiled to if (slot == selected) null else apps.next() }
    }

    /**
     * Previews the layout a drop would produce when it splits one existing leaf: the new pane, the rest of
     * that leaf, and every other leaf unchanged. Column insertions and replacements keep the slot family view.
     */
    private fun resultLayout(state: TaskSnapshot, selected: AppDragSlot): List<Pair<Rect, RunningTaskInfo?>>? {
        if (!state.activeSplit || selected.occupied || selected.threeColumns) return null
        val leaves = state.running.filter {
            it.displayId == displayId && it.isVisible && !it.isFloatingWindow &&
                split?.isTaskInSplitScreen(it.taskId) == true
        }.distinctBy { it.taskId }
        val host = leaves.firstOrNull {
            it.configuration.windowConfiguration.bounds.contains(
                selected.bounds.centerX(), selected.bounds.centerY())
        } ?: return null
        val rest = remainder(host.configuration.windowConfiguration.bounds, selected.bounds) ?: return null
        val zones = listOf(Rect(selected.bounds) to null, rest to host) +
            leaves.filter { it !== host }.map { Rect(it.configuration.windowConfiguration.bounds) to it }
        return tile(zones.map { it.first }).zip(zones.map { it.second })
    }

    /** The part of [host] left after [taken] claims one full-width or full-height end of it. */
    private fun remainder(host: Rect, taken: Rect): Rect? {
        val tolerance = (context.resources.displayMetrics.density * 64).toInt()
        val fullWidth = abs(taken.left - host.left) <= tolerance && abs(taken.right - host.right) <= tolerance
        val fullHeight = abs(taken.top - host.top) <= tolerance && abs(taken.bottom - host.bottom) <= tolerance
        val rest = when {
            fullWidth && taken.top - host.top > host.bottom - taken.bottom ->
                Rect(host.left, host.top, host.right, taken.top)
            fullWidth -> Rect(host.left, taken.bottom, host.right, host.bottom)
            fullHeight && taken.left - host.left > host.right - taken.right ->
                Rect(host.left, host.top, taken.left, host.bottom)
            fullHeight -> Rect(taken.right, host.top, host.right, host.bottom)
            else -> return null
        }
        return rest.takeIf { it.width() > 0 && it.height() > 0 }
    }

    /** Closes divider gaps between neighbouring zones at their midpoint, like the taskbar's display halves. */
    private fun tile(rects: List<Rect>): List<Rect> {
        val maxGap = (context.resources.displayMetrics.density * 64).toInt()
        return rects.map { r ->
            Rect(r).apply {
                rects.filter { it !== r }.forEach { o ->
                    val rows = r.top < o.bottom && o.top < r.bottom
                    val columns = r.left < o.right && o.left < r.right
                    if (rows && o.left >= r.right && o.left - r.right <= maxGap) right = (r.right + o.left) / 2
                    if (rows && o.right <= r.left && r.left - o.right <= maxGap) left = (o.right + r.left) / 2
                    if (columns && o.top >= r.bottom && o.top - r.bottom <= maxGap) bottom = (r.bottom + o.top) / 2
                    if (columns && o.bottom <= r.top && r.top - o.bottom <= maxGap) top = (o.bottom + r.top) / 2
                }
            }
        }
    }

    fun launch(destination: Destination): Boolean {
        val app = app ?: return false
        if (destination.pendingSplit) return split?.launchSidebarIntoPendingSplit(
            app.component, app.user, displayId) == true
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
            if (app.taskId >= 0) {
                val task = tasks.getTasks(100, false).firstOrNull { it.taskId == app.taskId } ?: return false
                if (slot.leafId >= 0) return controller.startTaskInSplitSlot(task, slot)
                controller.startTask(app.taskId, slot.position, options.toBundle(), null)
                return true
            }
            if (pending == null) return false
            if (slot.leafId >= 0) return controller.startSidebarIntent(pending, user.identifier,
                null, options.toBundle(), slot)
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
            if (app.taskId >= 0) ActivityTaskManager.getService()
                .startActivityFromRecents(app.taskId, options.toBundle())
            else pending!!.send(context, 0, null, null, null, null, options.toBundle())
            true
        } catch (e: PendingIntent.CanceledException) {
            controller.clearSidebarLaunch(cookie)
            Log.w(TAG, "Unable to launch sidebar app", e)
            false
        } catch (e: android.os.RemoteException) {
            controller.clearSidebarLaunch(cookie)
            Log.w(TAG, "Unable to launch dragged app", e)
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

        /** A drop can split only at a feasible insertion candidate under the pointer. */
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
            if (!active) return valid.firstOrNull { it.hitBounds.contains(x, y) }
            // The coordinator supplies only feasible insertion candidates for this exact layout.
            // Occupied leaves are replacement targets, never evidence of spare capacity.
            return valid.firstOrNull { !it.occupied && it.hitBounds.contains(x, y) }
        }
    }
}
