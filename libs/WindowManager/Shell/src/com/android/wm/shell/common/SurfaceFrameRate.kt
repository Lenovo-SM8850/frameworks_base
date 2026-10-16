// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.common

import android.os.SystemProperties
import android.view.Surface
import android.view.SurfaceControl

/** Task-container votes shared by Shell windowing modes. */
object SurfaceFrameRate {
    private val interactions = mutableSetOf<Any>()
    private val listeners = mutableSetOf<() -> Unit>()
    val isInteracting: Boolean get() = interactions.isNotEmpty()

    fun isOverrideSupported(): Boolean = SystemProperties.getBoolean(
        "ro.surface_flinger.enable_frame_rate_override", true)

    fun addInteractionListener(listener: () -> Unit) { listeners += listener }

    fun setInteracting(owner: Any, active: Boolean) {
        val wasActive = isInteracting
        if (active) interactions += owner else interactions -= owner
        if (wasActive != isInteracting) listeners.toList().forEach { it() }
    }

    @JvmStatic
    fun applyVote(t: SurfaceControl.Transaction, leash: SurfaceControl, fps: Float) {
        t.setFrameRate(leash, fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            .setFrameRateSelectionStrategy(leash, if (fps == 0f)
                SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_PROPAGATE else
                SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN)
    }
}
