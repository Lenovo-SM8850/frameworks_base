// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowlessWindowManager
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerController
import kotlin.math.abs
import kotlin.math.roundToInt

/** Transparent task child that keeps mini content live while owning tap and drag input. */
class FloatingMiniInput(private val contextProvider: () -> Context?,
    private val controller: PinnedLayerController) : AutoCloseable {
    private var host: SurfaceControlViewHost? = null
    private var surface: SurfaceControl? = null
    private var parent: SurfaceControl? = null
    private var task: RunningTaskInfo? = null
    private var start = Rect()
    private var moved = Rect()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var blockedGesture = false

    fun update(info: RunningTaskInfo, taskSurface: SurfaceControl,
        transaction: SurfaceControl.Transaction? = null) {
        if (!info.isFloatingWindow || !controller.isMini(info)) { close(); return }
        task = info
        val context = contextProvider() ?: return
        val display = context.display ?: return
        val bounds = info.presentationBounds
        if (bounds.isEmpty) return
        if (parent != taskSurface) close()
        task = info
        if (host == null) {
            val leash = SurfaceControl.Builder().setContainerLayer().setParent(taskSurface)
                .setName("Floating mini input ${info.taskId}")
                .setCallsite("FloatingMiniInput.update").build()
            surface = leash
            parent = taskSurface
            val manager = WindowlessWindowManager(info.configuration, leash, null)
            val root = View(context).apply {
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(com.android.wm.shell.R.string.floating_window_restore,
                    info.taskDescription?.label ?: info.baseActivity?.packageName.orEmpty())
                setOnClickListener { task?.let(controller::restoreMini) }
                setOnTouchListener { v, event ->
                    val current = task ?: return@setOnTouchListener false
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        blockedGesture = controller.isMiniAnimating(current)
                    }
                    if (blockedGesture) {
                        if (event.actionMasked == MotionEvent.ACTION_UP ||
                            event.actionMasked == MotionEvent.ACTION_CANCEL) blockedGesture = false
                        return@setOnTouchListener true
                    }
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            start = controller.beginMiniDrag(current)
                            moved.set(start)
                            downX = event.rawX
                            downY = event.rawY
                            dragging = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - downX
                            val dy = event.rawY - downY
                            val slop = ViewConfiguration.get(context).scaledTouchSlop
                            if (dragging || abs(dx) > slop || abs(dy) > slop) {
                                dragging = true
                                moved.set(start)
                                moved.offset(dx.roundToInt(), dy.roundToInt())
                                controller.moveMini(current, moved)
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (dragging) controller.endMiniDrag(current, start, moved)
                            else v.performClick()
                        }
                        MotionEvent.ACTION_CANCEL -> controller.endMiniDrag(current, start, start)
                    }
                    true
                }
            }
            host = SurfaceControlViewHost(context, display, manager, "FloatingMiniInput").apply {
                setView(root, WindowManager.LayoutParams(bounds.width(), bounds.height(),
                    WindowManager.LayoutParams.TYPE_APPLICATION,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
                        setTrustedOverlay()
                        setFitInsetsTypes(0)
                        setTitle("Floating mini input ${info.taskId}")
                    })
            }
        } else host?.relayout(bounds.width(), bounds.height())
        val t = transaction ?: SurfaceControl.Transaction()
        try {
            val inverse = 1f / info.floatingWindowScale
            t.setMatrix(surface!!, inverse, 0f, 0f, inverse)
                .setPosition(surface!!, 0f, 0f).setLayer(surface!!, Int.MAX_VALUE).show(surface!!)
            if (transaction == null) t.apply()
        } finally {
            if (transaction == null) t.close()
        }
    }

    override fun close() {
        host?.release()
        host = null
        surface?.let {
            if (it.isValid) SurfaceControl.Transaction().use { t -> t.remove(it).apply() }
            it.release()
        }
        surface = null
        parent = null
        task = null
    }
}
