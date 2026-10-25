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
    private data class Handle(val view: ImageView, val params: WindowManager.LayoutParams) {
        // A view only reports attachment after its next traversal, so track it ourselves.
        var added = false
    }
    private val windowContext = context.createWindowContext(
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    private val wm = windowContext.getSystemService(WindowManager::class.java)
    private val handles = mutableMapOf<Int, Handle>()
    private var users = setOf(UserHandle.myUserId())
    private var visible = true

    fun update() {
        val layout = displayController.getDisplayLayout(DEFAULT_DISPLAY) ?: return
        val stable = Rect().also { layout.getStableBounds(it) }
        if (stable.isEmpty) return
        val density = windowContext.resources.displayMetrics.density
        val size = (48 * density).roundToInt()
        val peek = (24 * density).roundToInt()
        val wanted = state.entries.values.filter { visible && it.userId in users && taskForId(it.taskId) != null }
        handles.keys.toList().filter { id -> wanted.none { it.taskId == id } }.forEach { id ->
            handles.remove(id)?.let { if (it.added) wm.removeViewImmediate(it.view) }
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
                if (handle.added) {
                    wm.updateViewLayout(handle.view, handle.params)
                } else {
                    wm.addView(handle.view, handle.params)
                    handle.added = true
                }
            }
        }
    }

}
