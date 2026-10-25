// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.IBinder
import com.android.wm.shell.shared.search.AppLabelSearch
import com.android.wm.shell.splitscreen.SplitScreenController.AppDragSlot
import java.util.Locale

/** Shared eligibility, session identity and physical placement rules. */
object SplitPickerPolicy {
    enum class Availability { AVAILABLE, ALREADY_OPEN, UNAVAILABLE, INCOMPATIBLE }

    @JvmStatic
    fun availability(info: ActivityInfo, bounds: Rect, enabled: Boolean, suspended: Boolean,
        quiet: Boolean, unlocked: Boolean, visible: Boolean, forceResizable: Boolean,
        allowNonResizable: Boolean, minimumWidth: Int, minimumHeight: Int): Availability {
        if (visible) return Availability.ALREADY_OPEN
        if (!enabled || suspended || quiet || !unlocked) return Availability.UNAVAILABLE
        if ((!ActivityInfo.isResizeableMode(info.resizeMode) && !forceResizable &&
                !allowNonResizable) || bounds.isEmpty || bounds.width() < minimumWidth ||
                bounds.height() < minimumHeight) return Availability.INCOMPATIBLE
        return Availability.AVAILABLE
    }

    /** A visible app may be selected only as another pane of this exact replacement session. */
    fun replacementMember(selection: SplitPickerSession, component: android.content.ComponentName,
        userId: Int): Int? = if (!selection.replace) null else selection.members.entries.firstOrNull {
            it.key != selection.sourceTaskId && it.value.userId == userId &&
                it.value.component?.packageName == component.packageName
        }?.key

    /** Prefers the pointed half; an infeasible branch falls back to the nearest free half. */
    @JvmStatic
    fun chooseFloatingSlot(slots: List<AppDragSlot>, window: Rect, minWidth: Int,
        minHeight: Int): AppDragSlot? {
        if (window.isEmpty) return null
        val x = window.centerX()
        val y = window.centerY()
        return slots.filter { !it.occupied && !it.bounds.isEmpty &&
            it.bounds.width() >= minWidth && it.bounds.height() >= minHeight }
            .minWithOrNull(compareBy<AppDragSlot> { !it.hitBounds.contains(x, y) }
                .thenBy { squaredDistance(it.bounds, x, y) })
    }

    private fun squaredDistance(bounds: Rect, x: Int, y: Int): Long {
        val dx = (bounds.centerX() - x).toLong()
        val dy = (bounds.centerY() - y).toLong()
        return dx * dx + dy * dy
    }

    fun matchesQuery(label: String, query: String, locale: Locale = Locale.getDefault()): Boolean =
        AppLabelSearch(locale).matches(label, query)

    fun panelBounds(stable: Rect, target: Rect, width: Int, height: Int): Rect {
        val w = width.coerceAtMost(stable.width()).coerceAtLeast(0)
        val h = height.coerceAtMost(stable.height()).coerceAtLeast(0)
        val x = (target.centerX() - w / 2).coerceIn(stable.left, stable.right - w)
        val y = target.top.coerceIn(stable.top, stable.bottom - h)
        return Rect(x, y, x + w, y + h)
    }
}

/** A session cannot outlive its exact group, destination, or controller generation. */
data class SplitPickerSession(
    val displayId: Int,
    val sourceTaskId: Int,
    val members: Map<Int, Member>,
    val slot: AppDragSlot,
    val generation: Long,
    val currentUser: Int,
    val replace: Boolean = false,
) {
    data class Member(val userId: Int, val token: IBinder, val component: android.content.ComponentName? = null)

    fun matches(destination: AppDragSlot?, group: Map<Int, Member>, serial: Long,
        user: Int): Boolean = destination != null && generation == serial && currentUser == user &&
        members == group && slot.occupied == destination.occupied &&
        replace == destination.occupied && slot.leafId == destination.leafId &&
        slot.layoutVersion == destination.layoutVersion && slot.splitBelow == destination.splitBelow &&
        slot.threeColumns == destination.threeColumns &&
        slot.newTaskFirst == destination.newTaskFirst && slot.bounds == destination.bounds
}
