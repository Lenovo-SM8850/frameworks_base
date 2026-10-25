// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor.common

import android.app.TaskInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import com.android.internal.os.BackgroundThread
import com.android.wm.shell.common.SurfaceFrameRate
import com.android.wm.shell.shared.handles.RegionSamplingHelper
import java.util.concurrent.Executor

/** Reuses navigation-handle sampling and its luminance hysteresis behind one visible handle. */
class HandleRegionSampler(private val view: View, private val tint: (Boolean) -> Unit) : AutoCloseable {
    private val handler = Handler(Looper.myLooper() ?: Looper.getMainLooper())
    private val region = Rect()
    private var visible = false
    private var fallbackLight = false
    private var unavailable = false
    private var closed = false
    var sampledLight: Boolean? = null
        private set
    private var helper: RegionSamplingHelper? = null
    private var samplingContext: List<Int>? = null
    private var generation = 0
    private val main = Executor { handler.post(it) }
    private val background = Executor { command ->
        val commandGeneration = generation
        BackgroundThread.getExecutor().execute {
            try { command.run() } catch (e: SecurityException) {
                handler.post { if (!closed && generation == commandGeneration) {
                    unavailable = true; stop(); sampledLight = null; tint(fallbackLight)
                } }
            } catch (e: UnsupportedOperationException) {
                handler.post { if (!closed && generation == commandGeneration) {
                    unavailable = true; stop(); sampledLight = null; tint(fallbackLight)
                } }
            }
        }
    }
    private val motionListener: () -> Unit = {
        if (!closed) {
            if (SurfaceFrameRate.isInteracting) {
                invalidate()
            } else schedule()
        }
    }
    private val start = Runnable {
        if (closed || !visible || unavailable || SurfaceFrameRate.isInteracting ||
            region.isEmpty || view.display?.displayId != 0 || !view.isAttachedToWindow) {
            if (!closed) tint(sampledLight ?: fallbackLight)
            return@Runnable
        }
        // A missing stop layer would include the handle itself in the sample.
        if (view.viewRootImpl?.surfaceControl?.isValid != true) {
            schedule()
            return@Runnable
        }
        val sampleGeneration = generation
        val sampler = helper ?: object : RegionSamplingHelper(view,
            object : RegionSamplingHelper.SamplingCallback {
                override fun onRegionDarknessChanged(isRegionDark: Boolean) {
                    if (!closed && visible && generation == sampleGeneration) {
                        sampledLight = isRegionDark
                        tint(isRegionDark)
                    }
                }
                override fun getSampledRegion(sampledView: View) = region
                override fun isSamplingEnabled() = visible && !closed && !unavailable
            }, main, background) {
            override fun onViewDetachedFromWindow(view: View) { stop() }
        }.also { helper = it }
        sampler.setWindowVisible(true)
        sampler.start(region)
    }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { schedule() }
        override fun onViewDetachedFromWindow(view: View) { invalidate() }
    }
    init {
        view.addOnAttachStateChangeListener(attachment)
        SurfaceFrameRate.addInteractionListener(motionListener)
    }

    fun update(bounds: Rect, visible: Boolean, fallbackLight: Boolean) {
        val changed = region != bounds || this.visible != visible
        this.fallbackLight = fallbackLight
        this.visible = visible
        if (changed) {
            invalidate()
            region.set(bounds)
            if (visible) schedule()
        } else if (sampledLight == null) tint(fallbackLight)
    }

    /** Samples belong to one task and display geometry, even when screen bounds are unchanged. */
    fun updateTask(task: TaskInfo) {
        val next = listOf(task.taskId, task.userId, task.displayId,
            task.configuration.windowConfiguration.rotation,
            view.display?.displayId ?: -1, view.display?.rotation ?: -1)
        if (samplingContext == next) return
        samplingContext = next
        unavailable = false
        invalidate()
        schedule()
    }

    private fun invalidate() {
        generation++
        stop()
        helper?.let {
            view.removeOnAttachStateChangeListener(it)
            view.removeOnLayoutChangeListener(it)
            it.stopAndDestroy()
        }
        helper = null
        sampledLight = null
        if (!closed) tint(fallbackLight)
    }

    fun setVisible(visible: Boolean) { update(region, visible, fallbackLight) }

    private fun schedule() {
        handler.removeCallbacks(start)
        if (visible && !closed && !unavailable && !SurfaceFrameRate.isInteracting) {
            handler.postDelayed(start, 80)
        }
    }

    private fun stop() {
        handler.removeCallbacks(start)
        helper?.setWindowVisible(false)
        helper?.stop()
    }

    override fun close() {
        if (closed) return
        closed = true
        SurfaceFrameRate.removeInteractionListener(motionListener)
        invalidate()
        view.removeOnAttachStateChangeListener(attachment)
    }
}
