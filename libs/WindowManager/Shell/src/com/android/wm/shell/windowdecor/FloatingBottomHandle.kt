// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowlessWindowManager
import android.widget.FrameLayout
import com.android.wm.shell.R
import com.android.wm.shell.windowdecor.common.DecorThemeUtil
import com.android.wm.shell.windowdecor.common.Theme
import com.android.wm.shell.windowdecor.common.NavigationHandleColor
import com.android.wm.shell.windowdecor.common.HandleRegionSampler

/** Both slim handles share a window so their pointers arrive in one unsplit stream. */
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
    private var bottomTarget: View? = null
    private var topPill: View? = null
    private var bottomPill: View? = null
    private var topSampler: HandleRegionSampler? = null
    private var bottomSampler: HandleRegionSampler? = null
    private val pinch = FloatingHandlePinch()

    private fun handleAt(y: Float): Int = when {
        y >= 0 && y < touchHeight -> 1
        y >= hostHeight - touchHeight && y < hostHeight -> 2
        else -> 0
    }

    private fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            view.id = if (handleAt(event.y) == 2) R.id.floating_bottom_handle else R.id.caption_handle
        }
        val pointers = if (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            (0 until event.pointerCount).map { i ->
                val handle = if (event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER &&
                    event.getX(i) >= 0 && event.getX(i) < hostWidth) handleAt(event.getY(i)) else 0
                FloatingHandlePinch.Pointer(event.getPointerId(i), event.getRawX(i),
                    event.getRawY(i), handle)
            }
        } else emptyList()
        val change = pinch.onEvent(event.actionMasked, event.getPointerId(event.actionIndex), pointers)
        if (change != null) {
            when (change.kind) {
                FloatingHandlePinch.Kind.START -> {
                    if (!listener.startFloatingHandlePinch(view, event, change.span,
                            change.x, change.y)) pinch.cancel()
                }
                FloatingHandlePinch.Kind.MOVE ->
                    listener.moveFloatingHandlePinch(change.span, change.x, change.y)
                FloatingHandlePinch.Kind.FINISH -> {
                    listener.moveFloatingHandlePinch(change.span, change.x, change.y)
                    listener.endFloatingHandlePinch(false)
                }
                FloatingHandlePinch.Kind.CANCEL -> listener.endFloatingHandlePinch(true)
                FloatingHandlePinch.Kind.CONSUME -> Unit
            }
            return true
        }
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
        val height = bounds.height()
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
                viewTreeObserver.addOnComputeInternalInsetsListener { insets ->
                    // The app receives all touches between the two handle regions.
                    insets.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
                    insets.touchableRegion.set(0, 0, this.width, touchHeight)
                    insets.touchableRegion.union(Rect(0, this.height - touchHeight, this.width, this.height))
                }
                setOnClickListener {
                    val target = if (id == R.id.floating_bottom_handle) bottomTarget else topTarget
                    target?.performClick()
                }
                setOnTouchListener { v, e -> onTouch(v, e) }
                for (bottom in listOf(false, true)) {
                    val target = FrameLayout(context).apply {
                        id = if (bottom) R.id.floating_bottom_handle else R.id.caption_handle
                        contentDescription = context.getString(R.string.floating_window_handle)
                        setOnClickListener(listener)
                    }
                    if (bottom) bottomTarget = target else topTarget = target
                    target.addView(View(context).apply {
                        val sampler = HandleRegionSampler(this) { light ->
                            background?.setTint(NavigationHandleColor.get(context, light))
                        }
                        if (bottom) {
                            bottomPill = this
                            bottomSampler = sampler
                        } else {
                            topPill = this
                            topSampler = sampler
                        }
                        background = context.getDrawable(R.drawable.decor_handle_dark)?.mutate()
                    }, FrameLayout.LayoutParams(pillWidth, pillHeight, Gravity.CENTER))
                    addView(target, FrameLayout.LayoutParams(width, handleHeight,
                        if (bottom) Gravity.BOTTOM else Gravity.TOP))
                }
            }
            val params = WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT).apply {
                    // Keep both pointers in this window, including across its two hit regions.
                    flags = flags and WindowManager.LayoutParams.FLAG_SPLIT_TOUCH.inv()
                    setTrustedOverlay()
                    setFitInsetsTypes(0)
                    setTitle("Floating handles ${task.taskId}")
                }
            hostParams = params
            viewHost.setView(root, params)
            host = viewHost
        } else if (!unchanged) {
            for (target in listOf(topTarget, bottomTarget)) {
                val params = target?.layoutParams as? FrameLayout.LayoutParams ?: continue
                params.width = width
                params.height = handleHeight
                target.layoutParams = params
            }
            hostParams?.let {
                it.width = width
                it.height = height
                host?.relayout(it)
            }
        }
        val appearance = task.taskDescription?.systemBarsAppearance
        val light = if (appearance != null && appearance != 0) {
            appearance and android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS == 0
        } else DecorThemeUtil(context).getAppTheme(task) == Theme.DARK
        val x = bounds.centerX() - pillWidth / 2
        val top = bounds.top + (handleHeight - pillHeight) / 2
        val bottom = bounds.bottom - handleHeight + (handleHeight - pillHeight) / 2
        topSampler?.update(Rect(x, top, x + pillWidth, top + pillHeight), true, light)
        bottomSampler?.update(Rect(x, bottom, x + pillWidth, bottom + pillHeight), true, light)
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

    fun getTargetFeedbackView(): View? = topPill?.takeIf { it.isAttachedToWindow }

    fun setFocusAlpha(alpha: Float) {
        topPill?.alpha = alpha
        bottomPill?.alpha = alpha
    }

    override fun close() {
        if (pinch.cancel()) listener.endFloatingHandlePinch(true)
        topSampler?.close()
        bottomSampler?.close()
        topSampler = null
        bottomSampler = null
        val oldHost = host
        val oldSurface = surface
        host = null
        hostParams = null
        surface = null
        parent = null
        topTarget = null
        bottomTarget = null
        topPill = null
        bottomPill = null
        oldHost?.release()
        oldSurface?.let { leash ->
            if (leash.isValid) SurfaceControl.Transaction().use { it.remove(leash).apply() }
            leash.release()
        }
        lastScale = Float.NaN
    }
}
