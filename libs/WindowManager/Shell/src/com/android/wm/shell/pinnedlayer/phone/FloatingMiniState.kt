// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.graphics.Rect
import kotlin.math.min
import kotlin.math.roundToInt

/** Transient presentation geometry, isolated from remembered normal window geometry. */
class FloatingMiniState {
    data class Key(val userId: Int, val taskId: Int)
    data class Restore(val bounds: Rect, val logical: Rect, val scale: Float)
    data class Entry(val restore: Restore, val bounds: Rect, var scale: Float,
        var edge: Edge = Edge.NONE)
    enum class Edge { NONE, LEFT, RIGHT }
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private val entries = mutableMapOf<Key, Entry>()

    fun get(userId: Int, taskId: Int): Entry? = entries[Key(userId, taskId)]
    fun enter(userId: Int, taskId: Int, restore: Restore, bounds: Rect, scale: Float): Entry =
        entries.getOrPut(Key(userId, taskId)) {
            Entry(Restore(Rect(restore.bounds), Rect(restore.logical), restore.scale), Rect(bounds), scale)
        }
    fun remove(userId: Int, taskId: Int) { entries.remove(Key(userId, taskId)) }
    fun removeUser(userId: Int) { entries.keys.removeAll { it.userId == userId } }
    fun canRemember(userId: Int, taskId: Int) = get(userId, taskId) == null

    companion object {
        fun corner(bounds: Rect, stable: Rect, threshold: Int): Corner? = corner(
            bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
            bounds.bottom.toFloat(), stable, threshold)

        /** Large expanded windows must carry an inward reference into the corner zone. */
        fun dragCorner(bounds: Rect, stable: Rect, threshold: Int): Corner? {
            if (bounds.isEmpty || stable.isEmpty) return null
            val coverage = bounds.width().toFloat() * bounds.height() /
                stable.width() / stable.height()
            // Keep small windows unchanged; half-display windows use the halfway point.
            val inward = ((coverage - .2f) / .6f).coerceIn(0f, 1f)
            val dx = bounds.width() * inward / 2f
            val dy = bounds.height() * inward / 2f
            return corner(bounds.left + dx, bounds.top + dy, bounds.right - dx,
                bounds.bottom - dy, stable, threshold)
        }

        private fun corner(left: Float, top: Float, right: Float, bottom: Float,
            stable: Rect, threshold: Int): Corner? {
            val atLeft = left <= stable.left + threshold
            val atRight = right >= stable.right - threshold
            val atTop = top <= stable.top + threshold
            val atBottom = bottom >= stable.bottom - threshold
            return when {
                atLeft && atTop -> Corner.TOP_LEFT
                atRight && atTop -> Corner.TOP_RIGHT
                atLeft && atBottom -> Corner.BOTTOM_LEFT
                atRight && atBottom -> Corner.BOTTOM_RIGHT
                else -> null
            }
        }

        fun scale(logical: Rect, stable: Rect, displayShortSide: Int, margin: Int): Float {
            if (logical.isEmpty || stable.isEmpty) return 1f
            val target = .3f * displayShortSide / min(logical.width(), logical.height())
            val fit = min((stable.width() - 2 * margin).coerceAtLeast(1).toFloat() / logical.width(),
                (stable.height() - 2 * margin).coerceAtLeast(1).toFloat() / logical.height())
            return min(target, fit).coerceIn(.001f, 1f)
        }

        fun sized(logical: Rect, scale: Float, left: Int, top: Int): Rect = Rect(left, top,
            left + (logical.width() * scale).roundToInt().coerceAtLeast(1),
            top + (logical.height() * scale).roundToInt().coerceAtLeast(1))

        fun clamp(bounds: Rect, stable: Rect, margin: Int = 0): Rect = Rect(bounds).apply {
            offsetTo(left.coerceIn(stable.left + margin,
                maxOf(stable.left + margin, stable.right - margin - width())),
                top.coerceIn(stable.top + margin,
                maxOf(stable.top + margin, stable.bottom - margin - height())))
        }

        fun snap(bounds: Rect, stable: Rect, corner: Corner, margin: Int): Rect = Rect(bounds).apply {
            offsetTo(if (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT)
                stable.left + margin else stable.right - margin - width(),
                if (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT)
                stable.top + margin else stable.bottom - margin - height())
        }

        fun tidyBounds(window: Rect, mini: Rect, stable: Rect, margin: Int,
            occupied: List<Rect>, nearest: Boolean = false): Rect? {
            val area = Rect(stable).apply { inset(margin, margin) }
            if (area.isEmpty || mini.width() > area.width() || mini.height() > area.height()) return null
            val corners = Corner.entries.sortedBy { corner ->
                val x = if (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT)
                    stable.left else stable.right
                val y = if (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT)
                    stable.top else stable.bottom
                val dx = window.exactCenterX().toDouble() - x
                val dy = window.exactCenterY().toDouble() - y
                dx * dx + dy * dy
            }
            val available = mutableListOf<Rect>()
            for (corner in corners) {
                val anchor = snap(mini, stable, corner, margin)
                val top = corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT
                val left = corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT
                val vertical = (listOf(anchor.top) + occupied.map {
                    if (top) it.bottom + margin else it.top - margin - mini.height()
                }).sortedBy { if (top) it else -it }
                val horizontal = (listOf(anchor.left) + occupied.map {
                    if (left) it.right + margin else it.left - margin - mini.width()
                }).sortedBy { if (left) it else -it }
                val candidates = vertical.map { Rect(anchor).apply { offsetTo(anchor.left, it) } } +
                    horizontal.map { Rect(anchor).apply { offsetTo(it, anchor.top) } }
                for (candidate in candidates) {
                    if (!area.contains(candidate)) continue
                    val spaced = Rect(candidate).apply { inset(-margin, -margin) }
                    if (occupied.none { Rect.intersects(spaced, it) }) {
                        if (!nearest) return candidate
                        available += candidate
                    }
                }
            }
            return available.minByOrNull {
                val dx = it.exactCenterX().toDouble() - window.exactCenterX()
                val dy = it.exactCenterY().toDouble() - window.exactCenterY()
                dx * dx + dy * dy
            }
        }

        fun stashEdge(bounds: Rect, stable: Rect): Edge = when {
            stable.left - bounds.left > bounds.width() * .6f -> Edge.LEFT
            bounds.right - stable.right > bounds.width() * .6f -> Edge.RIGHT
            else -> Edge.NONE
        }

        fun canUnstash(bounds: Rect, stable: Rect, edge: Edge): Boolean = when (edge) {
            Edge.LEFT -> bounds.right - stable.left > bounds.width() * .5f
            Edge.RIGHT -> stable.right - bounds.left > bounds.width() * .5f
            Edge.NONE -> true
        }

        fun stash(bounds: Rect, stable: Rect, edge: Edge, strip: Int): Rect = clamp(bounds, stable).apply {
            when (edge) {
                Edge.LEFT -> offsetTo(stable.left - width() + min(strip, width()), top)
                Edge.RIGHT -> offsetTo(stable.right - min(strip, width()), top)
                Edge.NONE -> Unit
            }
        }

        fun refit(bounds: Rect, oldStable: Rect, stable: Rect): Rect {
            val x = (bounds.exactCenterX() - oldStable.left) / oldStable.width().coerceAtLeast(1)
            val y = (bounds.exactCenterY() - oldStable.top) / oldStable.height().coerceAtLeast(1)
            return clamp(Rect(bounds).apply { offsetTo(
                (stable.left + stable.width() * x - width() / 2f).roundToInt(),
                (stable.top + stable.height() * y - height() / 2f).roundToInt()) }, stable)
        }
    }
}
