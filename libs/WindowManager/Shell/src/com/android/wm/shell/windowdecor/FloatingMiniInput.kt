// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.hardware.input.InputManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowlessWindowManager
import com.android.internal.annotations.VisibleForTesting
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerController
import kotlin.math.abs
import kotlin.math.roundToInt

/** Transparent task child that keeps mini content live while owning tap and drag input. */
class FloatingMiniInput(private val contextProvider: () -> Context?,
    private val controller: PinnedLayerController,
    private val pilferPointers: (View) -> Unit = { view ->
        view.viewRootImpl?.inputToken?.let { token ->
            view.context.getSystemService(InputManager::class.java).pilferPointers(token)
        }
    }) : AutoCloseable {
    private var host: SurfaceControlViewHost? = null
    private var surface: SurfaceControl? = null
    private var parent: SurfaceControl? = null
    private var task: RunningTaskInfo? = null
    private var start = Rect()
    private var moved = Rect()
    private var downX = 0f
    private var downY = 0f
    private var velocityTracker: VelocityTracker? = null
    private var dragging = false
    private var blockedGesture = false
    private var touchGesture = false
    private val stashFeedback = FloatingTargetFeedback()

    fun cancelTargetFeedback() {
        stashFeedback.reset()
        touchGesture = false
    }
    private val handler = Handler(Looper.myLooper() ?: Looper.getMainLooper())
    private var inputDispatchDepth = 0
    private var gestureActive = false
    private var hostWidth = 0
    private var hostHeight = 0
    private var hostParams: WindowManager.LayoutParams? = null

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
            val root = createInputView(context, info)
            host = SurfaceControlViewHost(context, display, manager, "FloatingMiniInput").apply {
                setView(root, inputLayoutParams(bounds.width(), bounds.height()))
            }
        } else if (hostWidth != bounds.width() || hostHeight != bounds.height()) {
            host?.let { relayoutInputHost(it, bounds.width(), bounds.height()) }
        }
        hostWidth = bounds.width()
        hostHeight = bounds.height()
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

    private fun inputLayoutParams(width: Int, height: Int): WindowManager.LayoutParams {
        val params = hostParams ?: WindowManager.LayoutParams(width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT).apply {
                setTrustedOverlay()
                setFitInsetsTypes(0)
                setTitle("Floating mini input ${task?.taskId}")
                hostParams = this
            }
        params.width = width
        params.height = height
        return params
    }

    @VisibleForTesting
    fun relayoutInputHost(viewHost: SurfaceControlViewHost, width: Int, height: Int) {
        // The size-only overload creates focusable parameters.
        viewHost.relayout(inputLayoutParams(width, height))
    }

    @VisibleForTesting
    fun createInputView(context: Context, info: RunningTaskInfo): View {
        task = info
        val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                task?.takeIf(controller::isMiniStashed)?.let(controller::restoreMini)
                return true
            }
            override fun onDoubleTap(event: MotionEvent) = true
            override fun onDoubleTapEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_UP) task?.let(controller::restoreMini)
                return true
            }
        }, handler).apply { setIsLongpressEnabled(false) }
        return object : View(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                inputDispatchDepth++
                return try { super.dispatchTouchEvent(event) }
                finally { inputDispatchDepth-- }
            }
        }.apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                isFocusable = false
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    view.systemGestureExclusionRects = listOf(Rect(0, 0, view.width, view.height))
                }
                contentDescription = context.getString(com.android.wm.shell.R.string.floating_window_restore,
                    info.taskDescription?.label ?: info.baseActivity?.packageName.orEmpty())
                setOnClickListener { task?.let(controller::restoreMini) }
                setOnTouchListener { v, event ->
                    val current = task ?: return@setOnTouchListener false
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        v.requestUnbufferedDispatch(event)
                        // Windowless hosts do not forward gesture exclusions to WM.
                        pilferPointers(v)
                        cancelTargetFeedback()
                        touchGesture = event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN) &&
                            event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                        blockedGesture = controller.isMiniAnimating(current)
                    }
                    if (blockedGesture) {
                        if (event.actionMasked == MotionEvent.ACTION_UP ||
                            event.actionMasked == MotionEvent.ACTION_CANCEL) blockedGesture = false
                        return@setOnTouchListener true
                    }
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) taps.onTouchEvent(event)
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            velocityTracker?.recycle()
                            velocityTracker = VelocityTracker.obtain()
                            trackVelocity(event)
                            gestureActive = true
                            start = controller.beginMiniDrag(current)
                            stashFeedback.update(controller.getMiniStashTarget(current, start).takeUnless {
                                it == com.android.wm.shell.pinnedlayer.phone.FloatingMiniState.Edge.NONE
                            }, false) {}
                            moved.set(start)
                            downX = event.rawX
                            downY = event.rawY
                            dragging = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!gestureActive) return@setOnTouchListener true
                            trackVelocity(event)
                            val dx = event.rawX - downX
                            val dy = event.rawY - downY
                            val slop = ViewConfiguration.get(context).scaledTouchSlop
                            if (dragging || abs(dx) > slop || abs(dy) > slop) {
                                if (!dragging) {
                                    val cancel = MotionEvent.obtain(event)
                                    cancel.action = MotionEvent.ACTION_CANCEL
                                    taps.onTouchEvent(cancel)
                                    cancel.recycle()
                                }
                                dragging = true
                                moved.set(start)
                                moved.offset(dx.roundToInt(), dy.roundToInt())
                                val edge = controller.getMiniStashTarget(current, moved)
                                stashFeedback.update(edge.takeUnless {
                                    it == com.android.wm.shell.pinnedlayer.phone.FloatingMiniState.Edge.NONE
                                }, touchGesture) {
                                    if (v.isAttachedToWindow) v.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.SEGMENT_TICK)
                                }
                                controller.moveMini(current, moved)
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            cancelTargetFeedback()
                            if (!gestureActive) return@setOnTouchListener true
                            trackVelocity(event)
                            val velocity = PointF()
                            velocityTracker?.let {
                                it.computeCurrentVelocity(1000,
                                    ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
                                val pointerId = event.getPointerId(0)
                                velocity.set(it.getXVelocity(pointerId), it.getYVelocity(pointerId))
                            }
                            velocityTracker?.recycle()
                            velocityTracker = null
                            // Finish input ownership before docking can synchronously relayout.
                            gestureActive = false
                            val wasDragging = dragging
                            dragging = false
                            if (wasDragging) {
                                if (!controller.flingMiniToCorner(current, start, moved, event.displayId,
                                        PointF(event.rawX, event.rawY), velocity)) {
                                    controller.endMiniDrag(current, start, moved)
                                }
                            } else {
                                controller.endMiniTap(current)
                                taps.onTouchEvent(event)
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            cancelTargetFeedback()
                            if (!gestureActive) return@setOnTouchListener true
                            gestureActive = false
                            dragging = false
                            velocityTracker?.recycle()
                            velocityTracker = null
                            taps.onTouchEvent(event)
                            controller.endMiniDrag(current, start, start)
                        }
                    }
                    true
                }
            }
    }

    private fun trackVelocity(event: MotionEvent) {
        val raw = MotionEvent.obtain(event)
        raw.offsetLocation(event.rawX - event.x, event.rawY - event.y)
        velocityTracker?.addMovement(raw)
        raw.recycle()
    }

    override fun close() {
        cancelTargetFeedback()
        velocityTracker?.recycle()
        velocityTracker = null
        dragging = false
        val oldHost = host
        val oldSurface = surface
        val oldTask = task
        host = null
        surface = null
        parent = null
        task = null
        hostWidth = 0
        hostHeight = 0
        hostParams = null
        blockedGesture = false
        if (gestureActive) {
            gestureActive = false
            oldTask?.let(controller::endMiniTap)
        }
        val release = Runnable {
            oldHost?.release()
            oldSurface?.let {
                if (it.isValid) SurfaceControl.Transaction().use { t -> t.remove(it).apply() }
                it.release()
            }
        }
        // Finish the input callback before disposing its receiver and rendering surface.
        if (inputDispatchDepth != 0) handler.post(release) else release.run()
    }
}
