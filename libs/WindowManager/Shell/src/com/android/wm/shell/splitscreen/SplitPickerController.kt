// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import android.app.ActivityManager
import android.app.ActivityManager.RecentTaskInfo
import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.IBinder
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import android.view.ContextThemeWrapper
import android.view.SurfaceControl
import android.view.WindowManager
import android.window.TransitionInfo
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.android.wm.shell.R
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.splitscreen.SplitPickerPolicy.Availability
import com.android.wm.shell.splitscreen.SplitScreenController.AppDragSlot
import com.android.wm.shell.transition.Transitions
import java.util.concurrent.Executor

/** Owns one transient selection. Package/icon work never runs on Shell main. */
class SplitPickerController(
    private val context: Context,
    private val split: SplitScreenController,
    private val organizer: ShellTaskOrganizer,
    private val displays: DisplayController,
    private val transitions: Transitions,
    private val main: ShellExecutor,
    private val handler: Handler,
    private val background: Executor,
) : ShellTaskOrganizer.TaskInfoChangedListener, ShellTaskOrganizer.TaskVanishedListener,
    DisplayController.OnDisplaysChangedListener, Transitions.TransitionObserver {
    data class App(val component: ComponentName, val user: UserHandle, val label: String,
        val badgedLabel: String, val icon: Drawable, val availability: Availability,
        val recentTaskId: Int?, val recentOrder: Int)

    private val launcher = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val icons = LruCache<String, Drawable>(100)
    private var session: SplitPickerSession? = null
    private var panel: SplitAppPicker? = null
    private var generation = 0L
    private var loading = 0L
    private var committing = false
    private var observing = false
    private val splitListener = object : SplitScreen.SplitScreenListener {
        override fun onTaskStageChanged(taskId: Int, stage: Int, visible: Boolean) { validate() }
        override fun onSplitBoundsChanged(root: Rect, first: Rect, second: Rect) { validate() }
        override fun onSplitVisibilityChanged(visible: Boolean) { validate() }
    }
    private val packages = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) { refresh() }
        override fun onPackageAdded(packageName: String, user: UserHandle) { refresh() }
        override fun onPackageChanged(packageName: String, user: UserHandle) { refresh() }
        override fun onPackagesAvailable(names: Array<String>, user: UserHandle, replacing: Boolean) {
            refresh()
        }
        override fun onPackagesUnavailable(names: Array<String>, user: UserHandle, replacing: Boolean) {
            refresh()
        }
        override fun onPackagesSuspended(names: Array<String>, user: UserHandle) { refresh() }
        override fun onPackagesUnsuspended(names: Array<String>, user: UserHandle) { refresh() }
        private fun refresh() {
            icons.evictAll()
            load()
        }
    }

    fun show(taskId: Int) {
        dismiss()
        val task = organizer.getRunningTaskInfo(taskId) ?: return
        val slot = split.getAddBelowSlot(taskId) ?: return
        val stable = Rect().also {
            displays.getDisplayLayout(task.displayId)?.getStableBounds(it)
        }
        if (stable.isEmpty) return
        val group = members()
        if (taskId !in group) return
        icons.evictAll()
        val selection = SplitPickerSession(task.displayId, taskId, group, slot,
            ++generation, ActivityManager.getCurrentUser())
        session = selection
        try {
            val displayContext = displays.getDisplayContext(task.displayId) ?: run { dismiss(); return }
            val windowContext = displayContext.createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            panel = SplitAppPicker(ContextThemeWrapper(windowContext,
                android.R.style.Theme_DeviceDefault_DayNight), stable, slot.bounds,
                { dismiss() }, { pick(it) })
            panel!!.show(slot.bounds)
            observing = true
            organizer.addTaskInfoChangedListener(this)
            organizer.addTaskVanishedListener(this)
            displays.addDisplayWindowListener(this)
            transitions.registerObserver(this)
            split.registerSplitScreenListener(splitListener)
            launcher.registerCallback(packages, handler)
            load()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to show split app picker", e)
            dismiss()
        }
    }

    fun dismiss() {
        session = null
        generation++
        loading++
        committing = false
        if (observing) {
            observing = false
            organizer.removeTaskInfoChangedListener(this)
            organizer.removeTaskVanishedListener(this)
            displays.removeDisplayWindowListener(this)
            transitions.unregisterObserver(this)
            split.unregisterSplitScreenListener(splitListener)
            launcher.unregisterCallback(packages)
        }
        panel?.close()
        panel = null
    }

    private fun members(): Map<Int, SplitPickerSession.Member> = split.splitLeafTaskIds
        .mapNotNull { organizer.getRunningTaskInfo(it) }
        .associate { it.taskId to SplitPickerSession.Member(it.userId, it.token.asBinder(), it.baseActivity) }

    private fun valid(selection: SplitPickerSession): Boolean = session === selection &&
        selection.matches(split.getAddBelowSlot(selection.sourceTaskId), members(), generation,
            ActivityManager.getCurrentUser()) &&
        organizer.getRunningTaskInfo(selection.sourceTaskId)?.displayId == selection.displayId

    private fun validate() { session?.let { if (!valid(it)) dismiss() } }

    override fun onTaskInfoChanged(task: RunningTaskInfo) { validate() }
    override fun onTaskVanished(task: RunningTaskInfo) {
        if (session?.members?.containsKey(task.taskId) == true) dismiss() else validate()
    }
    override fun onDisplayConfigurationChanged(displayId: Int, config: Configuration) {
        if (session?.displayId == displayId) dismiss()
    }
    override fun onStableInsetsChanging(displayId: Int,
        oldLayout: com.android.wm.shell.common.DisplayLayout) {
        if (session?.displayId == displayId) dismiss()
    }
    override fun onDisplayRemoved(displayId: Int) {
        if (session?.displayId == displayId) dismiss()
    }
    override fun onFixedRotationStarted(displayId: Int, rotation: Int) {
        if (session?.displayId == displayId) dismiss()
    }
    override fun onTransitionReady(token: IBinder, info: TransitionInfo,
        start: SurfaceControl.Transaction, finish: SurfaceControl.Transaction) { dismiss() }

    private fun recents(selection: SplitPickerSession): List<RecentTaskInfo> =
        ActivityTaskManager.getInstance().getRecentTasks(100,
            ActivityManager.RECENT_IGNORE_UNAVAILABLE, selection.currentUser)

    private fun load() {
        val selection = session ?: return
        if (!valid(selection)) { dismiss(); return }
        val loadId = ++loading
        background.execute {
            val apps = mutableListOf<App>()
            try {
                val recent = recents(selection)
                for (activity in profileActivities(selection.currentUser)) {
                    val app = app(activity, selection, recent) ?: continue
                    if (app.availability != Availability.INCOMPATIBLE) apps += app
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to load split apps", e)
            }
            main.execute {
                if (loadId != loading || session !== selection) return@execute
                if (!valid(selection)) { dismiss(); return@execute }
                panel?.bind(apps.map { currentAvailability(it) })
            }
        }
    }

    private fun profileUsers(currentUser: Int): List<UserHandle> =
        users.getProfiles(currentUser).map { UserHandle.of(it.id) }

    /** Shell's calling user may differ from the foreground user's profile group. */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun profileActivities(currentUser: Int): List<LauncherActivityInfo> =
        profileUsers(currentUser).flatMap { user ->
            try { launcher.getActivityList(null, user).filter { it.user == user } }
            catch (e: RuntimeException) { emptyList() }
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun resolveProfileActivity(component: ComponentName, user: UserHandle,
        currentUser: Int): LauncherActivityInfo? {
        if (user !in profileUsers(currentUser)) return null
        return launcher.resolveActivity(Intent.makeMainActivity(component), user)?.takeIf {
            it.user == user && it.componentName == component
        }
    }

    private fun app(activity: LauncherActivityInfo, selection: SplitPickerSession,
        recent: List<RecentTaskInfo>): App? {
        val info = activity.activityInfo
        if (!info.enabled || !info.applicationInfo.enabled ||
            info.applicationInfo.flags and ApplicationInfo.FLAG_INSTALLED == 0) return null
        val component = activity.componentName
        val user = activity.user
        val recentTask = recent.firstOrNull { it.userId == user.identifier &&
            it.baseIntent.component == component && it.displayId == selection.displayId &&
                (!it.isVisible || it.isFloatingWindow) }
        val resources = context.resources
        val force = Settings.Global.getInt(context.contentResolver,
            Settings.Global.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES, 0) != 0
        val compat = resources.getInteger(com.android.internal.R.integer.config_supportsNonResizableMultiWindow)
        val allowNonResizable = Settings.Global.getInt(context.contentResolver,
            Settings.Global.DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW, 0) != 0 ||
            compat == 1 || compat == 0 && resources.configuration.smallestScreenWidthDp >= 600
        val minimumWidth = maxOf(recentTask?.minWidth ?: 0,
            info.windowLayout?.getMinWidth(resources.displayMetrics) ?: 0)
        val minimumHeight = maxOf(recentTask?.minHeight ?: 0,
            info.windowLayout?.getMinHeight(resources.displayMetrics) ?: 0)
        val availability = SplitPickerPolicy.availability(info, selection.slot.bounds,
            launcher.isActivityEnabled(component, user),
            info.applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED != 0,
            users.isQuietModeEnabled(user), users.isUserUnlocked(user),
            selection.members.values.any { it.userId == user.identifier && it.component?.packageName == component.packageName }, force,
            allowNonResizable, minimumWidth, minimumHeight)
        val key = "${component.flattenToString()}:${user.identifier}:${resources.displayMetrics.densityDpi}:${info.applicationInfo.sourceDir}"
        val icon = icons.get(key) ?: activity.getBadgedIcon(resources.displayMetrics.densityDpi)
            .also { icons.put(key, it) }
        val label = activity.label.toString()
        return App(component, user, label,
            context.packageManager.getUserBadgedLabel(label, user).toString(), icon, availability,
            recentTask?.taskId, if (recentTask == null) Int.MAX_VALUE else recent.indexOf(recentTask))
    }

    /** Visible members are disabled even when that package permits multiple instances. */
    private fun currentAvailability(app: App): App = if (
        members().values.any { it.userId == app.user.identifier &&
            it.component?.packageName == app.component.packageName } ||
            !split.canLaunchPickerApp(app.component, app.user.identifier)) {
        app.copy(availability = Availability.ALREADY_OPEN)
    } else app

    private fun pick(chosen: App) {
        val selection = session ?: return
        if (committing || !valid(selection)) { if (!committing) dismiss(); return }
        committing = true
        panel?.launching()
        background.execute {
            var candidate: App? = null
            var reusable: RecentTaskInfo? = null
            try {
                if (chosen.user in profileUsers(selection.currentUser)) {
                    val activity = resolveProfileActivity(chosen.component, chosen.user,
                        selection.currentUser)
                    val recent = recents(selection)
                    candidate = activity?.let { app(it, selection, recent) }
                    reusable = recent.firstOrNull { it.taskId == candidate?.recentTaskId &&
                        (!it.isVisible || it.isFloatingWindow) && it.userId == chosen.user.identifier &&
                        it.baseIntent.component == chosen.component }
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to validate split selection", e)
            }
            val fresh = candidate
            val task = reusable
            main.execute {
                if (session !== selection) return@execute
                if (!valid(selection)) { dismiss(); return@execute }
                val checked = fresh?.let { currentAvailability(it) }
                if (checked == null || checked.availability != Availability.AVAILABLE ||
                    checked.user !in profileUsers(selection.currentUser)) {
                    committing = false
                    Toast.makeText(context, R.string.split_picker_unavailable, Toast.LENGTH_SHORT).show()
                    load()
                    return@execute
                }
                // Close the panel and unregister observers before starting the one insertion transition.
                dismiss()
                val launched = try {
                    if (task != null) {
                        val running = organizer.getRunningTaskInfo(task.taskId)
                        val incoming = running ?: task
                        if (incoming.userId != checked.user.identifier ||
                            incoming.displayId != selection.displayId ||
                            incoming.baseIntent.component != checked.component ||
                            !incoming.supportsMultiWindow ||
                            (incoming.isVisible && !incoming.isFloatingWindow)) false
                        else split.startTaskInSplitSlot(incoming, selection.slot)
                    } else {
                        val intent = Intent.makeMainActivity(checked.component).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK)
                        val pending = PendingIntent.getActivityAsUser(context, 0, intent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                            null, checked.user)
                        split.startSidebarIntent(pending, checked.user.identifier, null,
                            ActivityOptions.makeBasic().toBundle(), selection.slot)
                    }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Unable to insert selected split app", e)
                    false
                }
                if (!launched) Toast.makeText(context, R.string.split_picker_unavailable,
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object { const val TAG = "SplitPickerController" }
}
