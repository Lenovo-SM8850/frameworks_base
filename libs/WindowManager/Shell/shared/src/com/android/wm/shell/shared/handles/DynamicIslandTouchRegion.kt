// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.shared.handles

import android.graphics.Rect
import android.util.SparseArray

/**
 * Screen bounds of the status bar Dynamic Island on each display, published by SystemUI so that
 * Shell input layers drawn over the status bar (such as the app handle) leave touches on the
 * island to it. SystemUI and Shell share a process, so a process-wide holder is enough.
 */
object DynamicIslandTouchRegion {
    private val bounds = SparseArray<Rect>()

    /**
     * Replaces the island bounds of a display; an empty rect means no island is shown. Returns
     * whether they changed.
     */
    @JvmStatic
    fun update(displayId: Int, screenBounds: Rect): Boolean = synchronized(bounds) {
        val old = bounds.get(displayId)
        if (screenBounds.isEmpty) {
            bounds.remove(displayId)
            old != null
        } else {
            bounds.put(displayId, Rect(screenBounds))
            old != screenBounds
        }
    }

    /** Copies the island bounds of a display into [out]; returns false when it has no island. */
    @JvmStatic
    fun getBounds(displayId: Int, out: Rect): Boolean =
        synchronized(bounds) { bounds.get(displayId)?.let { out.set(it); true } ?: false }

    /** Whether the screen point of a display lies on its island. */
    @JvmStatic
    fun contains(displayId: Int, x: Int, y: Int): Boolean =
        synchronized(bounds) { bounds.get(displayId)?.contains(x, y) == true }
}
