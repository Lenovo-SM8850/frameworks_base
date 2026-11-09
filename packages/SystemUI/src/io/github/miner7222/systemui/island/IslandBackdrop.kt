// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.systemui.island

import android.graphics.Rect
import android.view.CompositionSamplingListener
import android.view.Display
import android.view.SurfaceControl
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Whether the screen behind a region of the popup window is dark, from the luminance the
 * compositor reports for that region. Only the layers below the popup window are sampled, so the
 * popup itself does not count.
 *
 * The verdict changes only when the luminance leaves a dead band around the middle and stays
 * outside it for two samples in a row, so a backdrop near the middle does not make it flicker.
 */
internal class IslandBackdrop(
    private val sampledView: View,
    private val mainExecutor: Executor,
    private val backgroundExecutor: Executor,
) {
    /** True for a dark backdrop, false for a light one, null until the first verdict. */
    var isDark by mutableStateOf<Boolean?>(null)
        private set

    private val listener =
        object : CompositionSamplingListener(mainExecutor) {
            override fun onSampleCollected(medianLuma: Float) {
                onLuma(medianLuma)
            }
        }
    private val requested = Rect()
    private val registered = Rect()
    private var registeredStop: SurfaceControl? = null
    private var wrappedStop: SurfaceControl? = null
    private var isRegistered = false
    private var active = false
    private var released = false
    private var retries = 0
    private var opposite = 0

    private val retry = Runnable {
        if (!released && active) update()
    }

    /** The region to sample, in screen coordinates. */
    fun setBounds(bounds: Rect) {
        if (bounds == requested) return
        requested.set(bounds)
        update()
    }

    /** Samples while [enabled], and stops when it is not. */
    fun setActive(enabled: Boolean) {
        if (active == enabled) return
        active = enabled
        if (enabled) {
            retries = 0
            update()
        } else {
            unregister()
        }
    }

    fun release() {
        released = true
        active = false
        sampledView.removeCallbacks(retry)
        unregister()
        backgroundExecutor.execute { listener.destroy() }
    }

    private fun update() {
        if (released || !active || requested.isEmpty) {
            unregister()
            return
        }
        // The layer of the popup window is the stop layer. Without a drawn window there is none,
        // and sampling then would include the popup itself, so wait for the next frame.
        val stop = sampledView.viewRootImpl?.surfaceControl?.takeIf { it.isValid }
        if (stop == null) {
            if (retries++ < MAX_RETRIES) sampledView.postOnAnimation(retry)
            return
        }
        if (isRegistered && requested == registered && stop === registeredStop) return
        unregister()
        val wrapped = SurfaceControl(stop, "islandBackdrop")
        val area = Rect(requested)
        isRegistered = true
        registered.set(area)
        registeredStop = stop
        wrappedStop = wrapped
        // The executor runs one task at a time in order, so a register never overtakes the
        // unregister before it.
        backgroundExecutor.execute {
            if (wrapped.isValid) {
                CompositionSamplingListener.register(
                    listener,
                    Display.DEFAULT_DISPLAY,
                    wrapped,
                    area,
                )
            }
        }
    }

    private fun unregister() {
        if (!isRegistered) return
        isRegistered = false
        val wrapped = wrappedStop
        wrappedStop = null
        registeredStop = null
        registered.setEmpty()
        backgroundExecutor.execute {
            CompositionSamplingListener.unregister(listener)
            if (wrapped != null && wrapped.isValid) wrapped.release()
        }
    }

    private fun onLuma(luma: Float) {
        val current = isDark
        val verdict =
            when {
                current == null -> luma < MIDDLE
                current && luma > MIDDLE + DEAD_BAND -> false
                !current && luma < MIDDLE - DEAD_BAND -> true
                else -> current
            }
        if (verdict == current) {
            opposite = 0
        } else if (current == null || ++opposite >= CONFIRMATIONS) {
            opposite = 0
            isDark = verdict
        }
    }

    private companion object {
        const val MIDDLE = 0.5f
        // Half of the width of the band in which the verdict is kept.
        const val DEAD_BAND = 0.08f
        const val CONFIRMATIONS = 2
        const val MAX_RETRIES = 30
    }
}

/**
 * An [IslandBackdrop] for the window of [view], sampling while [enabled].
 */
@Composable
internal fun rememberIslandBackdrop(view: View, enabled: Boolean): IslandBackdrop {
    val context = LocalContext.current
    val backgroundExecutor = remember(view) { Executors.newSingleThreadExecutor() }
    val backdrop =
        remember(backgroundExecutor) {
            IslandBackdrop(view, context.mainExecutor, backgroundExecutor)
        }
    DisposableEffect(backdrop) {
        onDispose {
            backdrop.release()
            // Queued after the last unregister, so it ends the thread once that has run.
            backgroundExecutor.shutdown()
        }
    }
    LaunchedEffect(backdrop, enabled) { backdrop.setActive(enabled) }
    return backdrop
}
