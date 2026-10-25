// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Handler
import android.os.IBinder
import android.os.InputConfig
import android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS
import android.os.Process
import android.view.Display.DEFAULT_DISPLAY
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputWindowHandle
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.WindowManager
import android.view.VelocityTracker
import android.view.ViewConfiguration
import com.android.internal.annotations.VisibleForTesting
import com.android.wm.shell.common.DisplayController
import kotlin.math.abs

/** Observes completed Home flings without intercepting the launcher's input. */
class FloatingTidyGestureMonitor(
    private val openSession: () -> AutoCloseable,
) {
    private var session: AutoCloseable? = null

    fun setEnabled(enabled: Boolean) {
        if (enabled == (session != null)) return
        if (enabled) {
            session = openSession()
        } else {
            val previous = session
            session = null
            previous?.close()
        }
    }

    companion object {
        private const val MONITOR_NAME = "floating-tidy"

        @VisibleForTesting
        fun spyHandle(channelToken: IBinder): InputWindowHandle =
            InputWindowHandle(null, DEFAULT_DISPLAY).apply {
                name = MONITOR_NAME
                token = channelToken
                layoutParamsType = WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY
                ownerPid = Process.myPid()
                ownerUid = Process.myUid()
                dispatchingTimeoutMillis = DEFAULT_DISPATCHING_TIMEOUT_MILLIS.toLong()
                scaleFactor = 1f
                replaceTouchableRegionWithCrop(null)
                inputConfig = InputConfig.SPY or InputConfig.NOT_FOCUSABLE or InputConfig.DO_NOT_PILFER
            }

        fun create(context: Context, displays: DisplayController, handler: Handler,
            onSwipeUp: () -> Unit): FloatingTidyGestureMonitor = FloatingTidyGestureMonitor {
            val displayContext = displays.getDisplayContext(DEFAULT_DISPLAY) ?: context
            val configuration = ViewConfiguration.get(displayContext)
            val gesture = FloatingTidySwipe(
                configuration.scaledTouchSlop.toFloat(),
                homeFlingVelocity(displayContext),
            )
            val layout = checkNotNull(displays.getDisplayLayout(DEFAULT_DISPLAY))
            val height = displayContext.resources.getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_gesture_height)
            val bottomRegion = Rect(0, layout.height() - height, layout.width(), layout.height())
            val monitor = context.getSystemService(InputManager::class.java)
                .monitorGestureInput(MONITOR_NAME, DEFAULT_DISPLAY)
            // Keep observing if the launcher pilfers its gesture from application windows.
            SurfaceControl.Transaction().use { transaction ->
                transaction.setTrustedOverlay(monitor.surface, true)
                transaction.setInputWindowInfo(monitor.surface, spyHandle(monitor.inputChannel.token))
                transaction.apply()
            }
            val notifySwipe = Runnable { onSwipeUp() }
            val receiver = object : InputEventReceiver(monitor.inputChannel, handler.looper) {
                override fun onInputEvent(event: InputEvent) {
                    var detected = false
                    try {
                        if (event is MotionEvent) {
                            detected = gesture.onMotionEvent(event, bottomRegion)
                        }
                    } finally {
                        finishInputEvent(event, false)
                    }
                    if (detected) handler.post(notifySwipe)
                }
            }
            AutoCloseable {
                handler.removeCallbacks(notifySwipe)
                gesture.cancel()
                receiver.dispose()
                monitor.dispose()
            }
        }

        private fun homeFlingVelocity(context: Context): Float {
            // Quickstep expresses its Home fling threshold in pixels per millisecond.
            val home = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY)
            if (home != null) {
                try {
                    val resources = context.packageManager.getResourcesForApplication(
                        home.activityInfo.applicationInfo)
                    val id = resources.getIdentifier("quickstep_fling_threshold_speed", "dimen",
                        home.activityInfo.packageName)
                    if (id != 0) return resources.getDimension(id) * 1000
                } catch (_: PackageManager.NameNotFoundException) {
                    // Fall back if the Home package changed while enabling the monitor.
                }
            }
            return ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
        }
    }
}

/** Tracks only upward flings; slow releases belong to Overview. */
class FloatingTidySwipe(
    private val touchSlop: Float,
    private val minimumFlingVelocity: Float,
) {
    private var velocity: VelocityTracker? = null
    private var startX = 0f
    private var startY = 0f

    fun onMotionEvent(event: MotionEvent, bottomRegion: Rect): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            if (!event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN) ||
                !bottomRegion.contains(event.x.toInt(), event.y.toInt())) return false
            startX = event.x
            startY = event.y
            velocity = VelocityTracker.obtain()
        }
        val tracker = velocity ?: return false
        tracker.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancel()
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - startX) > touchSlop &&
                    abs(event.x - startX) > abs(event.y - startY)) cancel()
            }
            MotionEvent.ACTION_UP -> {
                tracker.computeCurrentVelocity(1000)
                val dy = startY - event.y
                val detected = dy > touchSlop && dy > abs(event.x - startX) &&
                    -tracker.yVelocity > minimumFlingVelocity &&
                    -tracker.yVelocity > abs(tracker.xVelocity)
                cancel()
                return detected
            }
        }
        return false
    }

    fun cancel() {
        velocity?.recycle()
        velocity = null
    }
}
