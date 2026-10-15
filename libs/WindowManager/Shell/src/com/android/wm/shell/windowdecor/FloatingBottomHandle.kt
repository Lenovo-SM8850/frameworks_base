// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.WindowManager
import android.view.WindowlessWindowManager
import android.widget.FrameLayout
import com.android.wm.shell.R
import kotlin.math.roundToInt

/** A task-attached caption handle using the same touch pipeline as the app header. */
class FloatingBottomHandle(private val context: Context,
    private val listener: DesktopModeTouchEventListener) : AutoCloseable {
    private var host: SurfaceControlViewHost? = null
    private var surface: SurfaceControl? = null
    private var parent: SurfaceControl? = null

    fun update(task: RunningTaskInfo, taskSurface: SurfaceControl) {
        if (!task.isFloatingWindow) { close(); return }
        val display = context.display ?: return
        val bounds = task.configuration.windowConfiguration.bounds
        if (bounds.isEmpty) return
        val density = context.resources.displayMetrics.density
        val width = minOf(bounds.width(), (72 * density).roundToInt())
        val height = minOf(bounds.height(), (28 * density).roundToInt())
        if (parent != taskSurface) close()
        if (host == null) {
            val leash = SurfaceControl.Builder().setContainerLayer()
                .setName("Floating bottom handle ${task.taskId}").setParent(taskSurface)
                .setCallsite("FloatingBottomHandle.update").build()
            surface = leash
            parent = taskSurface
            val manager = WindowlessWindowManager(task.configuration, leash, null)
            val viewHost = SurfaceControlViewHost(context, display, manager, "FloatingBottomHandle")
            val root = FrameLayout(context).apply {
                id = R.id.floating_bottom_handle
                contentDescription = context.getString(R.string.floating_window_handle)
                isClickable = true
                setOnClickListener(listener)
                setOnTouchListener { v, e ->
                    val handled = listener.onTouch(v, e)
                    if (!handled && e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    true
                }
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        setColor(context.getColor(android.R.color.system_neutral1_100))
                        cornerRadius = 3 * density
                        setStroke(density.roundToInt().coerceAtLeast(1),
                            context.getColor(android.R.color.system_neutral1_700))
                    }
                }, FrameLayout.LayoutParams((40 * density).roundToInt(),
                    (5 * density).roundToInt(), Gravity.CENTER))
            }
            viewHost.setView(root, WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT).apply {
                    setTrustedOverlay()
                    setFitInsetsTypes(0)
                    setTitle("Floating bottom handle ${task.taskId}")
                })
            host = viewHost
        } else host?.relayout(width, height)
        SurfaceControl.Transaction().use { t ->
            t.setPosition(surface!!, (bounds.width() - width) / 2f,
                (bounds.height() - height).toFloat()).setLayer(surface!!, Int.MAX_VALUE).show(surface!!).apply()
        }
    }

    override fun close() {
        host?.release()
        host = null
        surface?.let { leash ->
            if (leash.isValid) SurfaceControl.Transaction().use { it.remove(leash).apply() }
            leash.release()
        }
        surface = null
        parent = null
    }
}
