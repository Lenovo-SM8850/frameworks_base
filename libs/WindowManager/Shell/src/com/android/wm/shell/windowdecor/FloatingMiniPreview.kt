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
    private val feedback = FloatingTargetFeedback()
    private data class Target(val userId: Int, val taskId: Int, val displayId: Int,
        val corner: com.android.wm.shell.pinnedlayer.phone.FloatingMiniState.Corner)

    fun updateFeedback(task: RunningTaskInfo, target: DragTarget?, touch: Boolean,
        cue: () -> Unit) {
        feedback.update(target?.corner?.let { Target(task.userId, task.taskId, task.displayId, it) },
            touch, cue)
    }

    private var indicator: VisualIndicatorViewContainer? = null
    private var bounds: Rect? = null
    private var radius = 0f

    fun update(task: RunningTaskInfo, leash: SurfaceControl, target: DragTarget?) =
        show(task, leash, target?.bounds, target?.radius ?: 0f)

    /** Outlines a drop zone instead; the same outline moves between zones and minis. */
    fun updateZone(task: RunningTaskInfo, leash: SurfaceControl, zone: Rect, radius: Float) =
        show(task, leash, zone, radius)

    private fun show(task: RunningTaskInfo, leash: SurfaceControl, target: Rect?, targetRadius: Float) {
        if (bounds == target && (target == null || radius == targetRadius)) return
        if (target != null && indicator == null) indicator = create(task, leash)
        if (target != null && indicator == null) return
        bounds = target?.let(::Rect)
        radius = if (target != null) targetRadius else 0f
        indicator?.updateMiniPreview(bounds, radius)
    }

    override fun close() {
        feedback.reset()
        indicator?.releaseVisualIndicator()
        indicator = null
        bounds = null
    }
}

/** Tracks semantic entry independently of changes to destination geometry. */
@com.android.internal.annotations.VisibleForTesting
class FloatingTargetFeedback {
    private var target: Any? = null

    fun update(next: Any?, touch: Boolean, cue: () -> Unit) {
        if (target == next) return
        target = next
        if (touch && next != null) cue()
    }

    fun reset() { target = null }
}
