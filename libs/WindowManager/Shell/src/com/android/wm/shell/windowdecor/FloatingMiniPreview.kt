// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo
import android.graphics.Rect
import android.view.SurfaceControl
import com.android.wm.shell.desktopmode.VisualIndicatorViewContainer
import com.android.wm.shell.pinnedlayer.phone.FloatingMiniController.DragTarget

/** Retains one indicator during a drag and updates only when its destination changes. */
class FloatingMiniPreview(
    private val create: (RunningTaskInfo, SurfaceControl) -> VisualIndicatorViewContainer?,
) : AutoCloseable {
    private var indicator: VisualIndicatorViewContainer? = null
    private var bounds: Rect? = null
    private var radius = 0f

    fun update(task: RunningTaskInfo, leash: SurfaceControl, target: DragTarget?) {
        if (bounds == target?.bounds && (target == null || radius == target.radius)) return
        if (target != null && indicator == null) indicator = create(task, leash)
        if (target != null && indicator == null) return
        bounds = target?.bounds?.let(::Rect)
        radius = target?.radius ?: 0f
        indicator?.updateMiniPreview(bounds, radius)
    }

    override fun close() {
        indicator?.releaseVisualIndicator()
        indicator = null
        bounds = null
    }
}
