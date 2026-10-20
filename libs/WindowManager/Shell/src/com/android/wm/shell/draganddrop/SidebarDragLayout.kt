// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.draganddrop

import android.app.ActivityManager.RunningTaskInfo
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.view.DragEvent
import android.view.HapticFeedbackConstants
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

/** Routes only trusted sidebar drags to the exact outline; all other drags use the existing layout. */
class SidebarDragLayout(
    private val context: Context,
    private val ordinary: DragLayoutProvider,
    private val split: SplitScreenController?,
    private val floating: Supplier<PinnedLayerController?>,
    private val organizer: ShellTaskOrganizer,
    private val taskStackListener: TaskStackListenerImpl?,
) : DragLayoutProvider {
    private val preview = object : View(context) {
        override fun onDetachedFromWindow() {
            finishSession()
            super.onDetachedFromWindow()
        }
    }.apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }
    private val outline = WindowPreviewOutline(preview)
    private var ordinaryView: View? = null
    private var policy: SidebarDragPolicy? = null
    private var sidebar = false
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
    }

    override fun prepare(session: DragSession, loggerSessionId: InstanceId?) {
        finishSession()
        sidebar = session.isSidebarAppDrag
        ordinaryView?.visibility = if (sidebar) View.INVISIBLE else View.VISIBLE
        if (!sidebar) {
            ordinary.prepare(session, loggerSessionId)
            return
        }
        cancelled = false
        dropped = false
        destination = null
        policy = SidebarDragPolicy(context, split, floating.get())
        watchTasks()
        policy?.start(session, context.displayId)
    }

    override fun updateSession(session: DragSession) {
        if (!sidebar) ordinary.updateSession(session) else requestSnapshotRefresh()
    }

    override fun show() {
        if (!sidebar) { ordinary.show(); return }
        showing = true
    }

    override fun update(event: DragEvent?) {
        if (!sidebar) { ordinary.update(event); return }
        if (event == null || dropped || cancelled) return
        x = event.x.toInt()
        y = event.y.toInt()
        resolvePreview()
    }

    private fun resolvePreview(): SidebarDragPolicy.Destination? {
        if (cancelled) return null
        val next = policy?.resolve(x, y)
        val changed = next?.bounds != destination?.bounds || next?.radius != destination?.radius ||
            next?.slot?.position != destination?.slot?.position ||
            next?.slot?.index != destination?.slot?.index
        if (changed) {
            // Geometry follows the pointer; feedback is only for destination-kind or slot changes.
            if (next != null && (destination == null ||
                    next.slot?.position != destination?.slot?.position ||
                    next.slot?.index != destination?.slot?.index)) {
                preview.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            outline.update(next?.bounds, next?.radius ?: 0f)
        }
        destination = next
        return next
    }

    private fun stopPreview() {
        showing = false
        outline.clear()
        destination = null
    }

    override fun hide(event: DragEvent?, hideCompleteCallback: Runnable?) {
        if (!sidebar) { ordinary.hide(event, hideCompleteCallback); return }
        stopPreview()
        hideCompleteCallback?.run()
        if (event?.action == DragEvent.ACTION_DRAG_ENDED ||
            event?.action == DragEvent.ACTION_DROP) finishSession()
    }

    override fun hasDropped(): Boolean = if (sidebar) dropped else ordinary.hasDropped()

    override fun drop(event: DragEvent?, dragSurface: SurfaceControl,
        hideTaskToken: WindowContainerToken?, dropCompleteCallback: Runnable?): Boolean {
        if (!sidebar) return ordinary.drop(event, dragSurface, hideTaskToken, dropCompleteCallback)
        if (event != null) { x = event.x.toInt(); y = event.y.toInt() }
        // Refresh task and split state synchronously before resolving DROP.
        if (!cancelled) policy?.refreshSnapshot()
        val target = if (cancelled) null else resolvePreview()
        val handled = target != null && policy?.launch(target) == true
        dropped = true
        hide(event, dropCompleteCallback)
        policy = null
        if (handled) SurfaceControl.Transaction().use { it.remove(dragSurface).apply() }
        return handled
    }

    override fun onConfigChanged(newConfig: Configuration?) {
        ordinary.onConfigChanged(newConfig)
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
