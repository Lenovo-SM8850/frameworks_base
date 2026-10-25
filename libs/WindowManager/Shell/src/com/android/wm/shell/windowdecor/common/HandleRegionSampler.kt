// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor.common

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
    private val main = Executor { handler.post(it) }
    private val background = Executor { command ->
        BackgroundThread.getExecutor().execute {
            try { command.run() } catch (e: SecurityException) {
                handler.post { if (!closed) { unavailable = true; stop(); sampledLight = null; tint(fallbackLight) } }
            } catch (e: UnsupportedOperationException) {
                handler.post { if (!closed) { unavailable = true; stop(); sampledLight = null; tint(fallbackLight) } }
            }
        }
    }
    private val motionListener: () -> Unit = {
        if (!closed) {
            if (SurfaceFrameRate.isInteracting) stop() else schedule()
        }
    }
    private val start = Runnable {
        if (closed || !visible || unavailable || SurfaceFrameRate.isInteracting ||
            region.isEmpty || view.display?.displayId != 0) {
            if (!closed) tint(sampledLight ?: fallbackLight)
            return@Runnable
        }
        val sampler = helper ?: object : RegionSamplingHelper(view,
            object : RegionSamplingHelper.SamplingCallback {
                override fun onRegionDarknessChanged(isRegionDark: Boolean) {
                    if (!closed && visible) { sampledLight = isRegionDark; tint(isRegionDark) }
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
        override fun onViewDetachedFromWindow(view: View) { stop() }
    }
    init {
        view.addOnAttachStateChangeListener(attachment)
        SurfaceFrameRate.addInteractionListener(motionListener)
    }

    fun update(bounds: Rect, visible: Boolean, fallbackLight: Boolean) {
        val changed = region != bounds || this.visible != visible
        this.fallbackLight = fallbackLight
        this.visible = visible
        if (sampledLight == null) tint(fallbackLight)
        if (!changed) return
        stop()
        region.set(bounds)
        if (visible) schedule()
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
        stop()
        view.removeOnAttachStateChangeListener(attachment)
        helper?.let {
            view.removeOnAttachStateChangeListener(it)
            view.removeOnLayoutChangeListener(it)
            it.stopAndDestroy()
        }
        helper = null
    }
}
