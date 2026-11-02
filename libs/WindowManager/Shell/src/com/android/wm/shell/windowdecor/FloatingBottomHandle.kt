// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.WindowManager
import android.view.WindowlessWindowManager
import android.widget.FrameLayout
import com.android.wm.shell.R
import com.android.wm.shell.windowdecor.common.DecorThemeUtil
import com.android.wm.shell.windowdecor.common.Theme
import com.android.wm.shell.windowdecor.common.NavigationHandleColor
import com.android.wm.shell.windowdecor.common.HandleRegionSampler

/** Slim top handle; app content is observed separately for two-finger resizing. */
class FloatingBottomHandle(private val contextProvider: () -> Context?,
    private val listener: DesktopModeTouchEventListener) : AutoCloseable {
    private var host: SurfaceControlViewHost? = null
    private var surface: SurfaceControl? = null
    private var parent: SurfaceControl? = null
    private var hostWidth = 0
    private var hostHeight = 0
    private var touchHeight = 0
    private var hostParams: WindowManager.LayoutParams? = null
    private var lastScale = Float.NaN
    private var topTarget: View? = null
    private var topPill: View? = null
    private var topSampler: HandleRegionSampler? = null

    private fun onTouch(view: View, event: MotionEvent): Boolean {
        val handled = listener.onTouch(view, event)
        if (!handled && event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
        return true
    }

    fun update(task: RunningTaskInfo, taskSurface: SurfaceControl,
        transaction: SurfaceControl.Transaction? = null) {
        if (!task.isFloatingWindow || !task.isVisible ||
            listener.pinnedLayerController?.isMini(task) == true) {
            close()
            return
        }
        val context = contextProvider() ?: return
        val display = context.display ?: return
        val bounds = task.presentationBounds
        if (bounds.isEmpty) return
        val resources = context.resources
        val pillWidth = resources.getDimensionPixelSize(R.dimen.floating_handle_pill_width)
        val pillHeight = resources.getDimensionPixelSize(R.dimen.floating_handle_pill_height)
        val width = minOf(bounds.width(),
            resources.getDimensionPixelSize(R.dimen.floating_handle_touch_width))
        val height = minOf(bounds.height(),
            resources.getDimensionPixelSize(R.dimen.floating_handle_touch_height))
        val handleHeight = minOf(height,
            resources.getDimensionPixelSize(R.dimen.floating_handle_touch_height))
        if (parent != taskSurface) close()
        val unchanged = width == hostWidth && height == hostHeight &&
            handleHeight == touchHeight && lastScale == task.floatingWindowScale
        hostWidth = width
        hostHeight = height
        touchHeight = handleHeight
        if (host == null) {
            val leash = SurfaceControl.Builder().setContainerLayer()
                .setName("Floating handles ${task.taskId}").setParent(taskSurface)
                .setCallsite("FloatingBottomHandle.update").build()
            surface = leash
            parent = taskSurface
            val manager = WindowlessWindowManager(task.configuration, leash, null)
            val viewHost = SurfaceControlViewHost(context, display, manager, "FloatingBottomHandle")
            val root = object : FrameLayout(context) {
                override fun onInterceptTouchEvent(event: MotionEvent) = true
            }.apply {
                id = R.id.caption_handle
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isClickable = true
                isMotionEventSplittingEnabled = false
                setOnClickListener { topTarget?.performClick() }
                setOnTouchListener { v, e -> onTouch(v, e) }
                val target = FrameLayout(context).apply {
                    id = R.id.caption_handle
                    contentDescription = context.getString(R.string.floating_window_handle)
                    setOnClickListener(listener)
                }
                topTarget = target
                target.addView(View(context).apply {
                    topSampler = HandleRegionSampler(this) { light ->
                        background?.setTint(NavigationHandleColor.get(context, light))
                    }
                    topPill = this
                    background = context.getDrawable(R.drawable.decor_handle_dark)?.mutate()
                }, FrameLayout.LayoutParams(pillWidth, pillHeight, Gravity.CENTER))
                addView(target, FrameLayout.LayoutParams(width, handleHeight, Gravity.TOP))
            }

            val params = WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT).apply {
                    setTrustedOverlay()
                    setFitInsetsTypes(0)
                    setTitle("Floating handles ${task.taskId}")
                }
            hostParams = params
            viewHost.setView(root, params)
            host = viewHost
        } else if (!unchanged) {
            (topTarget?.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.width = width
                params.height = handleHeight
                topTarget?.layoutParams = params
            }
            hostParams?.let {
                it.width = width
                it.height = height
                host?.relayout(it)
            }
        }
        val appearance = task.taskDescription?.systemBarsAppearance
        val light = if (appearance != null) {
            appearance and android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS == 0
        } else DecorThemeUtil(context).getAppTheme(task) == Theme.DARK
        val x = bounds.centerX() - pillWidth / 2
        val top = bounds.top + (handleHeight - pillHeight) / 2
        topSampler?.updateTask(task)
        topSampler?.update(Rect(x, top, x + pillWidth, top + pillHeight), true, light)
        lastScale = task.floatingWindowScale
        if (unchanged && transaction == null) return
        val t = transaction ?: SurfaceControl.Transaction()
        try {
            val inverse = 1f / task.floatingWindowScale
            t.setMatrix(surface!!, inverse, 0f, 0f, inverse)
                .setPosition(surface!!, (bounds.width() - width) / 2f * inverse, 0f)
                .setLayer(surface!!, Int.MAX_VALUE).show(surface!!)
            if (transaction == null) t.apply()
        } finally {
            if (transaction == null) t.close()
        }
    }

    fun getSurface(): SurfaceControl? = surface

    fun getWidth(): Int = hostWidth

    fun getTargetFeedbackView(): View? = topPill?.takeIf { it.isAttachedToWindow }

    fun setFocusAlpha(alpha: Float) {
        topPill?.alpha = alpha
    }

    override fun close() {
        topSampler?.close()
        topSampler = null
        val oldHost = host
        val oldSurface = surface
        host = null
        hostParams = null
        surface = null
        parent = null
        topTarget = null
        topPill = null
        oldHost?.release()
        oldSurface?.let { leash ->
            if (leash.isValid) SurfaceControl.Transaction().use { it.remove(leash).apply() }
            leash.release()
        }
        lastScale = Float.NaN
    }
}
