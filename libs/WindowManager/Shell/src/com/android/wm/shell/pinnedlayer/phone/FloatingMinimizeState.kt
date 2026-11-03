// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.graphics.Rect
import kotlin.math.roundToInt

/** Minimized task geometry and normalized handle positions, independent of overlay views. */
class FloatingMinimizeState {
    data class Entry(val taskId: Int, val userId: Int, val bounds: Rect,
        var right: Boolean, var fractionY: Float, var fractionX: Float? = null)
    val entries = linkedMapOf<Int, Entry>()
    fun minimize(taskId: Int, userId: Int, bounds: Rect, right: Boolean, fractionY: Float) {
        val previous = entries[taskId]
        entries[taskId] = Entry(taskId, userId, Rect(bounds), right,
            (previous?.fractionY ?: fractionY).coerceIn(0f, 1f), previous?.fractionX)
    }
    fun move(taskId: Int, right: Boolean, fractionY: Float) {
        entries[taskId]?.apply {
            this.right = right
            this.fractionY = fractionY.coerceIn(0f, 1f)
            fractionX = null
        }
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

    fun handleBounds(entry: Entry, stable: Rect, displayWidth: Int, size: Int, peek: Int): Rect {
        val x = entry.fractionX?.let {
            stable.left + ((stable.width() - size).coerceAtLeast(0) * it).roundToInt()
        } ?: if (entry.right) displayWidth - peek else peek - size
        val y = stable.top + ((stable.height() - size).coerceAtLeast(0) * entry.fractionY).roundToInt()
        return Rect(x, y, x + size, y + size)
    }

    fun positions(ordered: List<Entry>, stable: Rect, displayWidth: Int, size: Int,
        peek: Int, gap: Int): Map<Int, Rect> {
        val result = linkedMapOf<Int, Rect>()
        for (right in listOf(false, true)) {
            val edge = ordered.filter { it.fractionX == null && it.right == right }
                .sortedBy { it.fractionY }
            val ys = stackPositions(edge, stable.top, stable.bottom, size, size + gap)
            edge.forEachIndexed { index, entry ->
                result[entry.taskId] = handleBounds(entry, stable, displayWidth, size, peek).apply {
                    offsetTo(left, ys[index])
                }
            }
        }
        ordered.filter { it.fractionX != null }.forEach { entry ->
            val preferred = handleBounds(entry, stable, displayWidth, size, peek)
            result[entry.taskId] = freeBounds(preferred, stable, result.values, gap)
        }
        return result
    }

    fun drop(taskId: Int, requested: Rect, ordered: List<Entry>, stable: Rect,
        displayWidth: Int, size: Int, peek: Int, gap: Int, snapDistance: Int) {
        val entry = entries[taskId] ?: return
        val y = (requested.top - stable.top).toFloat() / (stable.height() - size).coerceAtLeast(1)
        if (requested.exactCenterX() <= snapDistance ||
            requested.exactCenterX() >= displayWidth - snapDistance) {
            move(taskId, requested.exactCenterX() >= displayWidth / 2f, y)
            return
        }
        val occupied = positions(ordered.filter { it.taskId != taskId }, stable,
            displayWidth, size, peek, gap).values
        val bounds = freeBounds(requested, stable, occupied, gap)
        entry.fractionX = ((bounds.left - stable.left).toFloat() /
            (stable.width() - size).coerceAtLeast(1)).coerceIn(0f, 1f)
        entry.fractionY = ((bounds.top - stable.top).toFloat() /
            (stable.height() - size).coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    private fun freeBounds(requested: Rect, stable: Rect, occupied: Collection<Rect>, gap: Int): Rect {
        val maxX = (stable.right - requested.width()).coerceAtLeast(stable.left)
        val maxY = (stable.bottom - requested.height()).coerceAtLeast(stable.top)
        val preferred = Rect(requested).apply {
            offsetTo(left.coerceIn(stable.left, maxX), top.coerceIn(stable.top, maxY))
        }
        val padded = occupied.map { Rect(it).apply { inset(-gap, -gap) } }
        if (padded.none { Rect.intersects(preferred, it) }) return preferred
        val xs = linkedSetOf(preferred.left, stable.left, maxX)
        val ys = linkedSetOf(preferred.top, stable.top, maxY)
        occupied.forEach {
            xs.add((it.left - requested.width() - gap).coerceIn(stable.left, maxX))
            xs.add((it.right + gap).coerceIn(stable.left, maxX))
            ys.add((it.top - requested.height() - gap).coerceIn(stable.top, maxY))
            ys.add((it.bottom + gap).coerceIn(stable.top, maxY))
        }
        var best: Rect? = null
        var distance = Double.POSITIVE_INFINITY
        for (x in xs) for (y in ys) {
            val candidate = Rect(x, y, x + requested.width(), y + requested.height())
            if (padded.any { Rect.intersects(candidate, it) }) continue
            val dx = (x - preferred.left).toDouble()
            val dy = (y - preferred.top).toDouble()
            val squared = dx * dx + dy * dy
            if (squared < distance) {
                best = candidate
                distance = squared
            }
        }
        return best ?: if (gap > 0) freeBounds(preferred, stable, occupied, 0) else preferred
    }

    fun remove(taskId: Int): Entry? = entries.remove(taskId)
}
