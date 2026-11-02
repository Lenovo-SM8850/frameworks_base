// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringForce
import com.android.wm.shell.R
import com.android.wm.shell.animation.FloatProperties
import com.android.wm.shell.bubbles.setup
import com.android.wm.shell.shared.animation.PhysicsAnimator
import com.android.wm.shell.shared.bubbles.DismissView
import com.android.wm.shell.shared.magnetictarget.MagnetizedObject
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Shares PiP's dismiss view, magnetic spring, velocity thresholds and haptics. */
class FloatingDismissTarget(context: Context) : AutoCloseable {
    private val context = context.display?.let(context::createDisplayContext) ?: context
    private val wm = this.context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.myLooper() ?: Looper.getMainLooper())
    private val bounds = Rect()
    private val originalBounds = Rect()
    private val touchBounds = Rect()
    private val down = PointF()
    private val boundsAnimator = PhysicsAnimator.getInstance(this)
    private val dismissSpring = PhysicsAnimator.SpringConfig(
        SpringForce.STIFFNESS_MEDIUM, SpringForce.DAMPING_RATIO_NO_BOUNCY)
    private val catchUpSpring = PhysicsAnimator.SpringConfig(
        5000f, SpringForce.DAMPING_RATIO_NO_BOUNCY)
    private var springingToTouch = false
    private var previewing = false
    private var dismissing = false
    private var move: ((Rect) -> Unit)? = null
    private var dismiss: (() -> Unit)? = null
    private var pendingDismiss: Runnable? = null
    private var view: DismissView? = null
    private var visible = false
    private var flungOut = false
    private var leash: SurfaceControl? = null
    private var magneticTarget: MagnetizedObject.MagneticTarget? = null

    private val magnet = object : MagnetizedObject<FloatingDismissTarget>(this@FloatingDismissTarget.context,
        this@FloatingDismissTarget, X, Y) {
        override fun getWidth(underlyingObject: FloatingDismissTarget) =
            underlyingObject.bounds.width().toFloat()
        override fun getHeight(underlyingObject: FloatingDismissTarget) =
            underlyingObject.bounds.height().toFloat()
        override fun getLocationOnScreen(underlyingObject: FloatingDismissTarget, loc: IntArray) {
            loc[0] = underlyingObject.bounds.left
            loc[1] = underlyingObject.bounds.top
        }
    }.apply {
        animateStuckToTarget = { target, velX, velY, _, after ->
            boundsAnimator.cancel()
            springingToTouch = false
            previewing = true
            val destination = dismissBounds(originalBounds, target.centerOnScreen,
                context.resources.getDimensionPixelSize(R.dimen.dismiss_circle_size))
            springTo(destination, dismissSpring, velX, velY, after)
        }
        magnetListener = object : MagnetizedObject.MagnetListener {
            override fun onStuckToTarget(target: MagnetizedObject.MagneticTarget,
                draggedObject: MagnetizedObject<*>) { show() }
            override fun onUnstuckFromTarget(target: MagnetizedObject.MagneticTarget,
                draggedObject: MagnetizedObject<*>, velX: Float, velY: Float, wasFlungOut: Boolean) {
                boundsAnimator.cancel()
                flungOut = wasFlungOut
                springingToTouch = true
            }
            override fun onReleasedInTarget(target: MagnetizedObject.MagneticTarget,
                draggedObject: MagnetizedObject<*>) {
                boundsAnimator.cancel()
                dismissing = true
                hide()
                // Closing can relayout and release the input host. Finish its dispatch first.
                val action = dismiss ?: return
                pendingDismiss = Runnable {
                    pendingDismiss = null
                    action()
                }.also(handler::post)
            }
        }
    }

    fun begin(event: MotionEvent, initialBounds: Rect, taskLeash: SurfaceControl,
        onMove: (Rect) -> Unit, onDismiss: () -> Unit) {
        cancel()
        bounds.set(initialBounds)
        originalBounds.set(initialBounds)
        touchBounds.set(initialBounds)
        down.set(event.rawX, event.rawY)
        leash = taskLeash
        move = onMove
        dismiss = onDismiss
        val target = view ?: DismissView(context).apply { setup() }.also { view = it }
        target.updateResources()
        run {
            val size = Point().also { wm.defaultDisplay.getRealSize(it) }
            val height = minOf(size.y, context.resources.getDimensionPixelSize(
                R.dimen.floating_dismiss_gradient_height))
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                height, 0, size.y - height, WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
                setTitle("floating-dismiss-overlay")
                privateFlags = privateFlags or WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            }
            if (target.parent == null) {
                target.visibility = View.INVISIBLE
                wm.addView(target, params)
            } else wm.updateViewLayout(target, params)
        }
        magnet.clearAllTargets()
        magneticTarget = magnet.addTarget(target.circle, (context.resources.getDimensionPixelSize(
            R.dimen.dismiss_circle_size) * 1.25f).toInt())
        magnet.maybeConsumeMotionEvent(event)
    }

    private fun show() {
        val target = view ?: return
        if (visible) return
        visible = true
        target.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                target.viewTreeObserver.removeOnPreDrawListener(this)
                magneticTarget?.updateLocationOnScreen()
                val surface = target.viewRootImpl?.surfaceControl
                val task = leash
                if (surface?.isValid == true && task?.isValid == true) {
                    SurfaceControl.Transaction().use { it.setRelativeLayer(surface, task, -1).apply() }
                }
                return true
            }
        })
        target.show()
    }

    private fun springTo(destination: Rect, spring: PhysicsAnimator.SpringConfig,
        velX: Float = 0f, velY: Float = 0f, after: (() -> Unit)? = null) {
        boundsAnimator
            .spring(X, destination.left.toFloat(), velX, spring)
            .spring(Y, destination.top.toFloat(), velY, spring)
            .spring(WIDTH, destination.width().toFloat(), spring)
            .spring(HEIGHT, destination.height().toFloat(), spring)
            .addUpdateListener { _, _ -> move?.invoke(Rect(bounds)) }
            .withEndActions(after)
            .start()
    }

    fun onMotionEvent(event: MotionEvent, currentBounds: Rect): Boolean {
        if (move == null) return false
        if (dismissing) return true
        touchBounds.set(originalBounds)
        touchBounds.offset((event.rawX - down.x).roundToInt(), (event.rawY - down.y).roundToInt())
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancel()
            return false
        }
        if (!magnet.objectStuckToTarget && !springingToTouch) bounds.set(currentBounds)
        if (event.actionMasked == MotionEvent.ACTION_MOVE) show()
        flungOut = false
        val consumed = magnet.maybeConsumeMotionEvent(event) && !flungOut
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (!consumed) cancel()
            return consumed
        }
        if (!consumed && springingToTouch) {
            springTo(touchBounds, catchUpSpring)
            return true
        }
        return consumed
    }

    private fun hide() {
        visible = false
        view?.hide()
    }

    fun cancel() {
        pendingDismiss?.let(handler::removeCallbacks)
        pendingDismiss = null
        boundsAnimator.cancel()
        if (previewing && !dismissing) {
            bounds.set(touchBounds)
            move?.invoke(Rect(bounds))
        }
        springingToTouch = false
        previewing = false
        dismissing = false
        magnet.clearAllTargets()
        magneticTarget = null
        move = null
        dismiss = null
        hide()
    }

    companion object {
        private fun property(source: FloatPropertyCompat<Rect>) =
            object : FloatPropertyCompat<FloatingDismissTarget>(source.toString()) {
                override fun getValue(target: FloatingDismissTarget) = source.getValue(target.bounds)
                override fun setValue(target: FloatingDismissTarget, value: Float) {
                    source.setValue(target.bounds, value)
                }
            }
        private val X = property(FloatProperties.RECT_X)
        private val Y = property(FloatProperties.RECT_Y)
        private val WIDTH = property(FloatProperties.RECT_WIDTH)
        private val HEIGHT = property(FloatProperties.RECT_HEIGHT)

        fun dismissBounds(original: Rect, center: PointF, diameter: Int): Rect {
            val scale = minOf(1f, diameter * .85f /
                hypot(original.width().toFloat(), original.height().toFloat()).coerceAtLeast(1f))
            val width = max(1, (original.width() * scale).roundToInt())
            val height = max(1, (original.height() * scale).roundToInt())
            val left = (center.x - width / 2f).roundToInt()
            val top = (center.y - height / 2f).roundToInt()
            return Rect(left, top, left + width, top + height)
        }
    }

    override fun close() {
        cancel()
        view?.let { if (it.parent != null) wm.removeViewImmediate(it) }
        view = null
        leash = null
    }
}
