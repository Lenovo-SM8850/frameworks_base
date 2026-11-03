// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.app.TaskInfo
import android.content.Context
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Binder
import android.os.UserHandle
import android.view.Display.DEFAULT_DISPLAY
import android.view.Gravity
import android.view.DragEvent
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import com.android.wm.shell.R
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.draganddrop.SidebarDragPolicy
import kotlin.math.abs
import kotlin.math.roundToInt

/** Small trusted Shell windows whose touch regions are limited to their visible handles. */
class FloatingMinimizeHandles(
    private val context: Context,
    private val displayController: DisplayController,
    private val state: FloatingMinimizeState,
    private val taskForId: (Int) -> TaskInfo?,
    private val onRestore: (Int) -> Unit,
    private val canDragToSplit: (TaskInfo) -> Boolean = { false },
) {
    private data class Handle(val view: ImageView, val params: WindowManager.LayoutParams) {
        // A view only reports attachment after its next traversal, so track it ourselves.
        var added = false
        var dragBounds: Rect? = null
    }
    private val windowContext = context.createWindowContext(
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    private val wm = windowContext.getSystemService(WindowManager::class.java)
    private val handles = mutableMapOf<Int, Handle>()
    private var users = setOf(UserHandle.myUserId())
    private var visible = true
    private val stableBounds = Rect()
    private var displayWidth = 0
    private var layoutGeneration = 0

    private fun wanted() = state.entries.values.filter {
        visible && it.userId in users && taskForId(it.taskId) != null
    }

    fun setUsers(userIds: Set<Int>) { users = userIds; update() }
    fun setVisible(value: Boolean) { visible = value; update() }

    fun update() {
        val layout = displayController.getDisplayLayout(DEFAULT_DISPLAY) ?: return
        val stable = Rect().also { layout.getStableBounds(it) }
        if (stable.isEmpty) return
        if (stable != stableBounds || displayWidth != layout.width()) {
            stableBounds.set(stable)
            displayWidth = layout.width()
            layoutGeneration++
            handles.values.forEach { it.dragBounds = null }
        }
        val resources = windowContext.resources
        val size = resources.getDimensionPixelSize(R.dimen.floating_minimize_handle_size)
        val peek = resources.getDimensionPixelSize(R.dimen.floating_minimize_handle_peek)
        val wanted = wanted()
        handles.keys.toList().filter { id -> wanted.none { it.taskId == id } }.forEach { id ->
            handles.remove(id)?.let { if (it.added) wm.removeViewImmediate(it.view) }
        }
        val positions = state.positions(wanted, stable, layout.width(), size, peek,
            resources.getDimensionPixelSize(R.dimen.floating_minimize_handle_gap))
        wanted.forEach { entry ->
            val task = taskForId(entry.taskId) ?: return@forEach
            val handle = handles.getOrPut(entry.taskId) { create(task, size) }
            val bounds = handle.dragBounds?.let { Rect(it).apply {
                offsetTo(left.coerceIn(stable.left, (stable.right - size).coerceAtLeast(stable.left)),
                    top.coerceIn(stable.top, (stable.bottom - size).coerceAtLeast(stable.top)))
            } } ?: positions.getValue(entry.taskId)
            handle.params.apply {
                width = size
                height = size
                x = bounds.left
                y = bounds.top
            }
            if (handle.added) {
                wm.updateViewLayout(handle.view, handle.params)
            } else {
                wm.addView(handle.view, handle.params)
                handle.added = true
            }
        }
    }

    private fun create(task: TaskInfo, size: Int): Handle {
        val view = ImageView(windowContext)
        val params = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            token = Binder()
            setTitle("Floating window handle ${task.taskId}")
            packageName = context.packageName
            setTrustedOverlay()
            privateFlags = privateFlags or WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val userContext = context.createContextAsUser(UserHandle.of(task.userId), 0)
        val info = task.topActivityInfo?.applicationInfo
        val icon = try { info?.loadIcon(userContext.packageManager) } catch (e: Exception) { null }
        view.setImageDrawable(icon ?: context.getDrawable(android.R.drawable.sym_def_app_icon))
        val label = info?.loadLabel(userContext.packageManager) ?: task.baseActivity?.packageName.orEmpty()
        view.contentDescription = context.getString(R.string.floating_window_restore, label)
        view.setBackgroundResource(R.drawable.floating_minimize_handle_background)
        view.elevation = windowContext.resources.getDimension(R.dimen.floating_minimize_handle_elevation)
        val padding = windowContext.resources.getDimensionPixelSize(
            R.dimen.floating_minimize_handle_padding)
        view.setPadding(padding, padding, padding, padding)
        view.systemGestureExclusionRects = listOf(Rect(0, 0, size, size))
        view.isClickable = true
        view.setOnClickListener { onRestore(task.taskId) }
        var startX = 0f
        var startY = 0f
        val handle = Handle(view, params)
        var startLeft = 0
        var startTop = 0
        var startGeneration = 0
        var dragging = false
        var splitDragging = false
        view.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> splitDragging
                DragEvent.ACTION_DRAG_ENDED -> {
                    if (splitDragging) {
                        splitDragging = false
                        view.alpha = 1f
                        handle.dragBounds = null
                        update()
                    }
                    true
                }
                else -> false
            }
        }
        val slop = ViewConfiguration.get(windowContext).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            if (task.taskId !in state.entries) return@setOnTouchListener false
            if (event.actionMasked != MotionEvent.ACTION_DOWN && startGeneration != layoutGeneration) {
                handle.dragBounds = null
                return@setOnTouchListener true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    startLeft = params.x; startTop = params.y
                    startGeneration = layoutGeneration; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val wasDragging = dragging
                    dragging = dragging || abs(event.rawX - startX) > slop || abs(event.rawY - startY) > slop
                    if (dragging && !wasDragging && canDragToSplit(task)) {
                        val intent = Intent().putExtra(Intent.EXTRA_TASK_ID, task.taskId)
                            .putExtra(Intent.EXTRA_USER, UserHandle.of(task.userId))
                            .putExtra(SidebarDragPolicy.EXTRA_MINIMIZED_TASK_DRAG, true)
                        val data = ClipData(label, arrayOf(ClipDescription.MIMETYPE_APPLICATION_TASK),
                            ClipData.Item(intent))
                        splitDragging = true
                        val started = try {
                            view.startDragAndDrop(data, View.DragShadowBuilder(view), null,
                                View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_OPAQUE)
                        } catch (e: RuntimeException) { false }
                        if (started) view.alpha = 0f else splitDragging = false
                    }
                    if (dragging && !splitDragging) {
                        handle.dragBounds = Rect(startLeft, startTop, startLeft + size, startTop + size)
                            .apply { offset((event.rawX - startX).roundToInt(),
                                (event.rawY - startY).roundToInt()) }
                        update()
                    }
                }
                MotionEvent.ACTION_UP -> if (!splitDragging) {
                    if (dragging) {
                        val requested = Rect(startLeft, startTop, startLeft + size, startTop + size)
                            .apply { offset((event.rawX - startX).roundToInt(),
                                (event.rawY - startY).roundToInt()) }
                        val resources = windowContext.resources
                        handle.dragBounds = null
                        state.drop(task.taskId, requested, wanted(), stableBounds, displayWidth, size,
                            resources.getDimensionPixelSize(R.dimen.floating_minimize_handle_peek),
                            resources.getDimensionPixelSize(R.dimen.floating_minimize_handle_gap),
                            resources.getDimensionPixelSize(R.dimen.floating_minimize_handle_snap_distance))
                        update()
                    } else view.performClick()
                }
                MotionEvent.ACTION_CANCEL -> if (!splitDragging) {
                    handle.dragBounds = null
                    update()
                }
            }
            true
        }
        return handle
    }
}
