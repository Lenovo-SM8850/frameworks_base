// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.app.TaskInfo
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Binder
import android.os.UserHandle
import android.view.Display.DEFAULT_DISPLAY
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import com.android.wm.shell.R
import com.android.wm.shell.common.DisplayController
import kotlin.math.abs
import kotlin.math.roundToInt

/** Small trusted Shell windows whose touch regions are limited to their visible handles. */
class FloatingMinimizeHandles(
    private val context: Context,
    private val displayController: DisplayController,
    private val state: FloatingMinimizeState,
    private val taskForId: (Int) -> TaskInfo?,
    private val onRestore: (Int) -> Unit,
) {
    private data class Handle(val view: ImageView, val params: WindowManager.LayoutParams)
    private val windowContext = context.createWindowContext(
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    private val wm = windowContext.getSystemService(WindowManager::class.java)
    private val handles = mutableMapOf<Int, Handle>()
    private var users = setOf(UserHandle.myUserId())
    private var visible = true

    fun setUsers(userIds: Set<Int>) { users = userIds; update() }
    fun setVisible(value: Boolean) { visible = value; update() }

    fun update() {
        val layout = displayController.getDisplayLayout(DEFAULT_DISPLAY) ?: return
        val stable = Rect().also { layout.getStableBounds(it) }
        if (stable.isEmpty) return
        val density = windowContext.resources.displayMetrics.density
        val size = (48 * density).roundToInt()
        val peek = (24 * density).roundToInt()
        val wanted = state.entries.values.filter { visible && it.userId in users && taskForId(it.taskId) != null }
        handles.keys.toList().filter { id -> wanted.none { it.taskId == id } }.forEach { id ->
            handles.remove(id)?.let { wm.removeViewImmediate(it.view) }
        }
        for (right in listOf(false, true)) {
            val ordered = wanted.filter { it.right == right }.sortedBy { it.fractionY }
            val positions = state.stackPositions(ordered, stable.top, stable.bottom,
                size, size + (8 * density).roundToInt())
            ordered.forEachIndexed { index, entry ->
                val task = taskForId(entry.taskId) ?: return@forEachIndexed
                val handle = handles.getOrPut(entry.taskId) { create(task, size) }
                handle.params.apply {
                    width = size
                    height = size
                    x = if (right) layout.width() - peek else peek - size
                    y = positions[index]
                }
                if (handle.view.isAttachedToWindow) wm.updateViewLayout(handle.view, handle.params)
                else wm.addView(handle.view, handle.params)
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
        view.background = GradientDrawable().apply {
            setColor(context.getColor(android.R.color.system_neutral1_800))
            cornerRadius = size / 2f
        }
        view.elevation = size / 8f
        view.setPadding(size / 6, size / 6, size / 6, size / 6)
        view.systemGestureExclusionRects = listOf(Rect(0, 0, size, size))
        view.isClickable = true
        view.setOnClickListener { onRestore(task.taskId) }
        var startX = 0f
        var startY = 0f
        var startRight = true
        var startFraction = .5f
        var dragging = false
        val slop = ViewConfiguration.get(windowContext).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            val entry = state.entries[task.taskId] ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    startRight = entry.right; startFraction = entry.fractionY; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    dragging = dragging || abs(event.rawX - startX) > slop || abs(event.rawY - startY) > slop
                    if (dragging) {
                        val layout = displayController.getDisplayLayout(DEFAULT_DISPLAY)
                        val stable = Rect().also { layout?.getStableBounds(it) }
                        if (!stable.isEmpty && layout != null) {
                            state.move(task.taskId, event.rawX >= layout.width() / 2f,
                                startFraction + (event.rawY - startY) / (stable.height() - size).coerceAtLeast(1))
                            update()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) view.performClick()
                MotionEvent.ACTION_CANCEL -> { state.move(task.taskId, startRight, startFraction); update() }
            }
            true
        }
        return Handle(view, params)
    }
}
