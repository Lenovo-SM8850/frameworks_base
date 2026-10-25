// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.graphics.Rect
import kotlin.math.roundToInt

/** Minimized task geometry and normalized handle positions, independent of overlay views. */
class FloatingMinimizeState {
    data class Entry(val taskId: Int, val userId: Int, val bounds: Rect,
        var right: Boolean, var fractionY: Float)
    val entries = linkedMapOf<Int, Entry>()
    fun minimize(taskId: Int, userId: Int, bounds: Rect, right: Boolean, fractionY: Float) {
        val previous = entries[taskId]
        entries[taskId] = Entry(taskId, userId, Rect(bounds), right,
            (previous?.fractionY ?: fractionY).coerceIn(0f, 1f))
    }
    fun move(taskId: Int, right: Boolean, fractionY: Float) {
        entries[taskId]?.apply { this.right = right; this.fractionY = fractionY.coerceIn(0f, 1f) }
    }
    /** Packs adjacent handles while retaining their requested relative positions. */
    fun stackPositions(ordered: List<Entry>, top: Int, bottom: Int, size: Int,
        preferredGap: Int): List<Int> {
        if (ordered.isEmpty()) return emptyList()
        val available = (bottom - top - size).coerceAtLeast(0)
        val gap = if (ordered.size == 1) 0 else
            preferredGap.coerceAtMost(available / (ordered.size - 1))
        val positions = ordered.map { top + (available * it.fractionY).roundToInt() }.toMutableList()
        for (index in positions.indices) {
            val minimum = if (index == 0) top else positions[index - 1] + gap
            positions[index] = maxOf(positions[index], minimum)
        }
        positions[positions.lastIndex] = minOf(positions.last(), top + available)
        for (index in positions.lastIndex - 1 downTo 0) {
            positions[index] = minOf(positions[index], positions[index + 1] - gap)
        }
        return positions
    }

    fun remove(taskId: Int): Entry? = entries.remove(taskId)
}
