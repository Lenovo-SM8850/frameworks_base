// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.windowdecor

import android.app.ActivityTaskManager
import android.app.KeyguardManager
import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.util.SparseArray
import android.view.BatchedInputEventReceiver
import android.view.Choreographer
import android.view.Display.DEFAULT_DISPLAY
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.SurfaceControl
import com.android.wm.shell.pinnedlayer.phone.FloatingTidyGestureMonitor
import com.android.wm.shell.windowdecor.FloatingHandlePinch
import com.android.wm.shell.windowdecor.WindowDecorationWrapper

/** PiP-style gesture monitor: single-finger content input remains with the app. */
class FloatingWindowPinchInput(context: Context, handler: Handler,
    private val decorations: SparseArray<WindowDecorationWrapper>) {
    private val inputManager = context.getSystemService(InputManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val monitor = inputManager.monitorGestureInput("floating-pinch", DEFAULT_DISPLAY)
    private val pinch = FloatingHandlePinch(false)
    private var decoration: WindowDecorationWrapper? = null

    init {
        // A caption may already have pilfered the first finger. Keep observing its stream.
        val handle = FloatingTidyGestureMonitor.spyHandle(monitor.inputChannel.token).apply {
            name = "floating-pinch"
        }
        SurfaceControl.Transaction().use {
            it.setTrustedOverlay(monitor.surface, true)
                .setInputWindowInfo(monitor.surface, handle).apply()
        }
    }

    // Like PipResizeGestureHandler, both receiving and resizing run on the Shell main looper.
    private val receiver = object : BatchedInputEventReceiver(monitor.inputChannel,
        handler.looper, Choreographer.getInstance()) {
        override fun onInputEvent(event: InputEvent) {
            try {
                if (event is MotionEvent) onMotionEvent(event)
            } finally {
                finishInputEvent(event, decoration != null)
            }
        }
    }

    private fun onMotionEvent(event: MotionEvent) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) ||
            event.displayId != DEFAULT_DISPLAY) return
        decoration?.takeUnless { it.canPinchFloatingWindow() && !keyguard.isKeyguardLocked }?.let {
            it.endFloatingPinch(true)
            decoration = null
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && decoration == null) return
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            decoration?.endFloatingPinch(true)
            decoration = null
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 2 &&
            decoration == null && !keyguard.isKeyguardLocked) {
            if ((0 until decorations.size()).none {
                    decorations.valueAt(it).canPinchFloatingWindow()
                }) return
            // TaskOrganizer's sparse array is not in stacking order. Hit-test the real top task,
            // including fullscreen and PiP tasks that can cover a floating window.
            val tasks = ActivityTaskManager.getInstance().getTasks(100, false, false, DEFAULT_DISPLAY)
            fun hitTask(x: Float, y: Float) = tasks.firstOrNull {
                val bounds = decorations[it.taskId]?.floatingInteractionBounds ?: it.presentationBounds
                it.isVisible && bounds.contains(x.toInt(), y.toInt())
            }
            val task = hitTask(event.getRawX(0), event.getRawY(0))
            val candidate = task?.takeIf { it.isFloatingWindow }?.let { decorations[it.taskId] }
            if (candidate?.canPinchFloatingWindow() == true &&
                (0 until event.pointerCount).all {
                    event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER &&
                        hitTask(event.getRawX(it), event.getRawY(it))?.taskId == task?.taskId
                }) {
                decoration = candidate
            }
        }
        val pointers = (0 until event.pointerCount).map { i ->
            FloatingHandlePinch.Pointer(event.getPointerId(i), event.getRawX(i), event.getRawY(i),
                if (event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER) 1 else 0)
        }
        val change = pinch.onEvent(event.actionMasked, event.getPointerId(event.actionIndex), pointers)
            ?: return
        val target = decoration ?: return
        when (change.kind) {
            FloatingHandlePinch.Kind.START -> {
                if (target.startFloatingPinch(event, change.span, change.x, change.y)) {
                    // Cancel app and caption input as soon as the second finger lands inside.
                    inputManager.pilferPointers(receiver.token)
                } else {
                    decoration = null
                    pinch.cancel()
                }
            }
            FloatingHandlePinch.Kind.MOVE -> target.moveFloatingPinch(change.span, change.x, change.y, change.angle)
            FloatingHandlePinch.Kind.FINISH -> {
                target.moveFloatingPinch(change.span, change.x, change.y, change.angle)
                target.endFloatingPinch(false)
            }
            FloatingHandlePinch.Kind.CANCEL -> target.endFloatingPinch(true)
            FloatingHandlePinch.Kind.CONSUME -> Unit
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            decoration = null
        }
    }
}
