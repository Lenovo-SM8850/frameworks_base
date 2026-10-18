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
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowlessWindowManager
import android.widget.FrameLayout
import com.android.wm.shell.R
import com.android.wm.shell.windowdecor.common.DecorThemeUtil
import com.android.wm.shell.windowdecor.common.Theme
import com.android.wm.shell.windowdecor.common.NavigationHandleColor
import com.android.wm.shell.windowdecor.common.HandleRegionSampler
import kotlin.math.roundToInt

/**
 * A task-attached slim handle using the same touch pipeline as the app header. The decoration
 * creates its window context on the first relayout and recreates it on configuration changes, so
 * read the current one on each update.
 */
class FloatingBottomHandle(private val contextProvider: () -> Context?,
    private val listener: DesktopModeTouchEventListener,
    private val atBottom: Boolean = true) : AutoCloseable {
    private var host: SurfaceControlViewHost? = null
    private var surface: SurfaceControl? = null
    private var parent: SurfaceControl? = null
    private var hostWidth = 0
    private var hostHeight = 0
    private var hostParams: WindowManager.LayoutParams? = null
    private var lastScale = Float.NaN
    private var visibleWidth = 0
    private var visibleHeight = 0
    private var pill: View? = null
    private var sampler: HandleRegionSampler? = null
    private val sampleBounds = Rect()

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
        val density = context.resources.displayMetrics.density
        val width = minOf(bounds.width(), (56 * density).roundToInt())
        val height = minOf(bounds.height(), (24 * density).roundToInt())
        if (parent != taskSurface) close()
        if (host == null) {
            val leash = SurfaceControl.Builder().setContainerLayer()
                .setName("Floating ${if (atBottom) "bottom" else "top"} handle ${task.taskId}").setParent(taskSurface)
                .setCallsite("FloatingBottomHandle.update").build()
            surface = leash
            parent = taskSurface
            val manager = WindowlessWindowManager(task.configuration, leash, null)
            val viewHost = SurfaceControlViewHost(context, display, manager, "FloatingBottomHandle")
            val root = FrameLayout(context).apply {
                id = if (atBottom) R.id.floating_bottom_handle else R.id.caption_handle
                contentDescription = context.getString(R.string.floating_window_handle)
                isClickable = true
                viewTreeObserver.addOnComputeInternalInsetsListener { insets ->
                    // Limit hit testing to the pill host, including its touch padding.
                    insets.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
                    insets.touchableRegion.set(0, 0, this.width, this.height)
                }
                setOnClickListener(listener)
                setOnTouchListener { v, e ->
                    val handled = listener.onTouch(v, e)
                    if (!handled && e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    true
                }
                addView(View(context).apply {
                    pill = this
                    sampler = HandleRegionSampler(this) { light ->
                        background?.setTint(NavigationHandleColor.get(context, light))
                    }
                    background = context.getDrawable(R.drawable.decor_handle_dark)?.mutate()
                }, FrameLayout.LayoutParams((32 * density).roundToInt(),
                    (4 * density).roundToInt(), Gravity.CENTER))
            }
            val params = WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT).apply {
                    setTrustedOverlay()
                    setFitInsetsTypes(0)
                    setTitle("Floating ${if (atBottom) "bottom" else "top"} handle ${task.taskId}")
                }
            hostParams = params
            viewHost.setView(root, params)
            host = viewHost
        } else if (width != hostWidth || height != hostHeight) {
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
        val pillWidth = (32 * density).roundToInt()
        val pillHeight = (4 * density).roundToInt()
        val x = bounds.centerX() - pillWidth / 2
        val y = (if (atBottom) bounds.bottom - height else bounds.top) + (height - pillHeight) / 2
        sampleBounds.set(x, y, x + pillWidth, y + pillHeight)
        sampler?.update(sampleBounds, true, light)
        val unchanged = width == hostWidth && height == hostHeight &&
            lastScale == task.floatingWindowScale && visibleWidth == bounds.width() &&
            visibleHeight == bounds.height()
        visibleWidth = bounds.width()
        visibleHeight = bounds.height()
        hostWidth = width
        hostHeight = height
        lastScale = task.floatingWindowScale
        if (unchanged && transaction == null) return
        val t = transaction ?: SurfaceControl.Transaction()
        try {
            val inverse = 1f / task.floatingWindowScale
            t.setMatrix(surface!!, inverse, 0f, 0f, inverse)
                .setPosition(surface!!, (bounds.width() - width) / 2f * inverse,
                (if (atBottom) bounds.height() - height else 0) * inverse).setLayer(surface!!, Int.MAX_VALUE).show(surface!!)
            if (transaction == null) t.apply()
        } finally {
            if (transaction == null) t.close()
        }
    }

    fun setFocusAlpha(alpha: Float) { pill?.alpha = alpha }

    override fun close() {
        sampler?.close()
        sampler = null
        val oldHost = host
        val oldSurface = surface
        host = null
        hostParams = null
        surface = null
        parent = null
        pill = null
        oldHost?.release()
        oldSurface?.let { leash ->
            if (leash.isValid) SurfaceControl.Transaction().use { it.remove(leash).apply() }
            leash.release()
        }
        surface = null
        parent = null
        lastScale = Float.NaN
    }
}
