// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.common

import android.view.Surface
import android.view.SurfaceControl

/** Task-container votes shared by Shell windowing modes. */
object SurfaceFrameRate {
    @JvmStatic
    fun applyVote(t: SurfaceControl.Transaction, leash: SurfaceControl, fps: Float) {
        t.setFrameRate(leash, fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            .setFrameRateSelectionStrategy(leash, if (fps == 0f)
                SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_PROPAGATE else
                SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN)
    }
}
