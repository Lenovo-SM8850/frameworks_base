// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.draganddrop

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.ActivityManager.RunningTaskInfo
import android.app.StatusBarManager
import android.view.WindowInsets
import android.graphics.Color
import android.graphics.Rect
import android.widget.FrameLayout
import com.android.launcher3.icons.IconProvider
import com.android.wm.shell.R
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.view.DragEvent
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.window.WindowContainerToken
import com.android.internal.logging.InstanceId
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.TaskStackListenerCallback
import com.android.wm.shell.common.TaskStackListenerImpl
import com.android.wm.shell.desktopmode.WindowPreviewOutline
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerController
import com.android.wm.shell.splitscreen.SplitScreenController
import java.io.PrintWriter
import java.util.function.Supplier

/** Uses one placement policy and taskbar drop-zone renderer for app drags from every source. */
class SidebarDragLayout(
    private val context: Context,
    private val ordinary: DragLayoutProvider,
    private val split: SplitScreenController?,
    private val floating: Supplier<PinnedLayerController?>,
    private val organizer: ShellTaskOrganizer,
    private val taskStackListener: TaskStackListenerImpl?,
    private val icons: IconProvider,
) : DragLayoutProvider {
    private val preview = object : FrameLayout(context) {
        override fun onDetachedFromWindow() {
            finishSession()
            super.onDetachedFromWindow()
        }
    }.apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }
    // Floating destinations get the thin, window-radius outline used by mini previews, not split zone margins
    private val floatingView = View(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val floatingOutline = WindowPreviewOutline(floatingView)
    private val floatingRadius = context.resources.getDimension(
        com.android.wm.shell.shared.R.dimen.desktop_windowing_freeform_rounded_corner_radius)
    private val statusBar = context.getSystemService(StatusBarManager::class.java)
    private val zones = mutableListOf<Pair<Rect, DropZoneView>>()
    private var zoneTasks = emptyList<Int?>()
    private var highlighted: DropZoneView? = null
    private var ordinaryView: View? = null
    private var policy: SidebarDragPolicy? = null
    private var sidebar = false
    private var sidebarSource = false
    private var cancelled = false
    private var dropped = false
    private var showing = false
    private var x = 0
    private var y = 0
    private var destination: SidebarDragPolicy.Destination? = null
    private var watchingTasks = false
    private val refresh = Runnable {
        if (sidebar && !cancelled && !dropped) {
            policy?.refreshSnapshot()
            if (showing) resolvePreview()
        }
    }
    private val stackCallback = object : TaskStackListenerCallback {
        override fun onTaskStackChanged() = requestSnapshotRefresh()
        override fun onTaskMovedToFront(taskInfo: RunningTaskInfo) = requestSnapshotRefresh()
        override fun onTaskCreated(taskId: Int, component: ComponentName?) = requestSnapshotRefresh()
        override fun onTaskRemoved(taskId: Int) = requestSnapshotRefresh()
        override fun onTaskDisplayChanged(taskId: Int, newDisplayId: Int) = requestSnapshotRefresh()
    }
    private val taskCallback = object : ShellTaskOrganizer.TaskAppearedListener,
        ShellTaskOrganizer.TaskInfoChangedListener {
        override fun onTaskAppeared(taskInfo: RunningTaskInfo, leash: SurfaceControl) =
            requestSnapshotRefresh()
        override fun onTaskInfoChanged(taskInfo: RunningTaskInfo) = requestSnapshotRefresh()
    }

    private fun requestSnapshotRefresh() {
        if (!watchingTasks || cancelled || dropped) return
        // Coalesce task callbacks into one snapshot update, including while the pointer is still.
        preview.removeCallbacks(refresh)
        preview.postOnAnimation(refresh)
    }

    private fun watchTasks() {
        watchingTasks = true
        taskStackListener?.addListener(stackCallback)
        organizer.addTaskAppearedListener(taskCallback)
        organizer.addTaskInfoChangedListener(taskCallback)
    }

    private fun finishSession() {
        if (watchingTasks) {
            watchingTasks = false
            taskStackListener?.removeListener(stackCallback)
            organizer.removeTaskAppearedListener(taskCallback)
            organizer.removeTaskInfoChangedListener(taskCallback)
        }
        preview.removeCallbacks(refresh)
        stopPreview()
        policy = null
    }

    override fun addDraggingView(viewGroup: ViewGroup) {
        val childIndex = viewGroup.childCount
        ordinary.addDraggingView(viewGroup)
        ordinaryView = viewGroup.getChildAt(childIndex)
        viewGroup.addView(preview, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        preview.addView(floatingView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun clearZones() {
        zones.forEach { preview.removeView(it.second) }
        zones.clear()
        zoneTasks = emptyList()
        highlighted = null
    }

    override fun prepare(session: DragSession, loggerSessionId: InstanceId?) {
        finishSession()
        clearZones()
        floatingOutline.clear()
        policy = SidebarDragPolicy(context, split, floating.get()).also {
            it.start(session, context.displayId)
        }
        sidebarSource = session.isSidebarAppDrag
        sidebar = sidebarSource || policy?.handlesAppDrag == true
        preview.visibility = if (sidebar) View.VISIBLE else View.INVISIBLE
        preview.animate().cancel()
        preview.alpha = 1f
        ordinaryView?.visibility = if (sidebar) View.INVISIBLE else View.VISIBLE
        if (!sidebar) {
            ordinary.prepare(session, loggerSessionId)
            return
        }
        if (!sidebarSource) ordinary.prepare(session, loggerSessionId)
        cancelled = false
        dropped = false
        destination = null
        watchTasks()
    }

    override fun updateSession(session: DragSession) {
        if (!sidebar) ordinary.updateSession(session) else requestSnapshotRefresh()
    }

    override fun show() {
        if (!sidebar) { ordinary.show(); return }
        showing = true
        if (!sidebarSource) ordinary.show()
    }

    override fun update(event: DragEvent?) {
        if (!sidebar) { ordinary.update(event); return }
        if (event == null || dropped || cancelled) return
        x = event.x.toInt()
        y = event.y.toInt()
        if (!sidebarSource) {
            // Keep the taskbar's existing bubble hover and launch behavior independently of our zones.
            ordinary.update(event)
            if ((ordinary as? DragLayout)?.isOverBubblesDropZone == true) {
                zones.forEach { it.second.setShowingMargin(false) }
                highlighted = null
                destination = null
                return
            }
        }
        resolvePreview()
    }

    /** A persistent taskbar sends no disallowed region; dropping back onto it must cancel like the transient one. */
    private fun isOverTaskbar(): Boolean {
        val bottom = preview.rootWindowInsets?.getInsets(WindowInsets.Type.tappableElement())?.bottom ?: 0
        return bottom > 0 && y >= preview.height - bottom
    }

    private fun resolvePreview(): SidebarDragPolicy.Destination? {
        if (cancelled) return null
        val overTaskbar = isOverTaskbar()
        // Fade the whole preview over the cancel region so the app underneath shows the drop will do nothing
        preview.animate().alpha(if (overTaskbar) 0f else 1f).setDuration(
            context.resources.getInteger(android.R.integer.config_shortAnimTime).toLong()).start()
        val next = if (overTaskbar) null else policy?.resolve(x, y)
        if (next == null) {
            zones.forEach { it.second.setShowingMargin(false) }
            highlighted = null
            floatingOutline.update(null, floatingRadius)
        } else if (next.slot == null) {
            // The floating window opens exactly at these visible bounds, so outline them like the window
            clearZones()
            floatingOutline.update(next.bounds, floatingRadius)
        } else {
            floatingOutline.update(null, floatingRadius)
            val geometry = policy?.previewZones(next).orEmpty()
            val tasks = geometry.map { it.second?.taskId }
            // Rebuild only on topology/task changes, never for pointer motion within a split slot.
            if (geometry.map { it.first } != zones.map { it.first } || tasks != zoneTasks) {
                clearZones()
                zoneTasks = tasks
                val margin = context.resources.getDimension(R.dimen.drop_layout_display_margin)
                val extent = Rect().apply { geometry.forEach { union(it.first) } }
                geometry.forEach { (bounds, task) ->
                    val zone = DropZoneView(context).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        setContainerMargin(
                            if (bounds.left > extent.left) margin / 2 else margin,
                            if (bounds.top > extent.top) margin / 2 else margin,
                            if (bounds.right < extent.right) margin / 2 else margin,
                            if (bounds.bottom < extent.bottom) margin / 2 else margin)
                        val bottom = preview.rootWindowInsets?.getInsets(
                            WindowInsets.Type.tappableElement() or WindowInsets.Type.displayCutout())?.bottom ?: 0
                        setBottomInset((bounds.bottom - (preview.height - bottom)).coerceAtLeast(0).toFloat())
                        task?.topActivityInfo?.let { info ->
                            val color = task.taskDescription?.backgroundColor ?: Color.WHITE
                            setAppInfo(if (color == -1) Color.WHITE else color, icons.getIcon(info))
                        }
                    }
                    preview.addView(zone, FrameLayout.LayoutParams(bounds.width(), bounds.height()).apply {
                        leftMargin = bounds.left
                        topMargin = bounds.top
                    })
                    zones += Rect(bounds) to zone
                    zone.setShowingMargin(true)
                }
            }
            zones.forEach { it.second.setShowingMargin(true) }
            // Zones are tiled over divider gaps, so match the destination by its centre
            val selected = zones.firstOrNull {
                it.first.contains(next.bounds.centerX(), next.bounds.centerY())
            }?.second
            if (selected != highlighted) {
                zones.forEach { it.second.setShowingHighlight(it.second === selected) }
                highlighted = selected
            }
        }
        statusBar.disable(if (next != null) StatusBarManager.DISABLE_NOTIFICATION_ICONS or
            StatusBarManager.DISABLE_NOTIFICATION_ALERTS or StatusBarManager.DISABLE_CLOCK or
            StatusBarManager.DISABLE_SYSTEM_INFO else StatusBarManager.DISABLE_NONE)
        destination = next
        return next
    }

    private fun stopPreview() {
        showing = false
        statusBar.disable(StatusBarManager.DISABLE_NONE)
        zones.forEach { it.second.setShowingMargin(false) }
        floatingOutline.update(null, floatingRadius)
        highlighted = null
        destination = null
    }

    override fun hide(event: DragEvent?, hideCompleteCallback: Runnable?) {
        if (!sidebar) { ordinary.hide(event, hideCompleteCallback); return }
        if (!sidebarSource) ordinary.hide(event, null)
        stopPreview()
        val animator = zones.firstOrNull()?.second?.animator
        if (hideCompleteCallback != null) {
            if (animator == null) hideCompleteCallback.run()
            else animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { hideCompleteCallback.run() }
            })
        }
        if (event?.action == DragEvent.ACTION_DRAG_ENDED ||
            event?.action == DragEvent.ACTION_DROP) finishSession()
    }

    override fun hasDropped(): Boolean = if (sidebar) dropped else ordinary.hasDropped()

    override fun drop(event: DragEvent?, dragSurface: SurfaceControl,
        hideTaskToken: WindowContainerToken?, dropCompleteCallback: Runnable?): Boolean {
        if (!sidebar) return ordinary.drop(event, dragSurface, hideTaskToken, dropCompleteCallback)
        if (!sidebarSource && (ordinary as? DragLayout)?.isOverBubblesDropZone == true) {
            dropped = true
            val handled = ordinary.drop(event, dragSurface, hideTaskToken, dropCompleteCallback)
            finishSession()
            return handled
        }
        if (event != null) { x = event.x.toInt(); y = event.y.toInt() }
        // Refresh task and split state synchronously before resolving DROP.
        if (!cancelled) policy?.refreshSnapshot()
        val target = if (cancelled) null else resolvePreview()
        val handled = target != null && policy?.launch(target) == true
        dropped = true
        hide(event, dropCompleteCallback)
        policy = null
        if (handled) DragLayout.hideDragSurface(dragSurface)
        return handled
    }

    override fun onConfigChanged(newConfig: Configuration?) {
        ordinary.onConfigChanged(newConfig)
        zones.forEach { it.second.onThemeChange() }
        if (sidebar) {
            policy?.refreshSnapshot()
            cancelled = true
            finishSession()
        }
    }

    override fun dump(pw: PrintWriter, prefix: String?) {
        ordinary.dump(pw, prefix)
        pw.println("${prefix}sidebar=$sidebar cancelled=$cancelled destination=$destination")
    }
}
