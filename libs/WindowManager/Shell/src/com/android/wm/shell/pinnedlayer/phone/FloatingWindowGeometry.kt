// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.content.pm.ActivityInfo
import android.graphics.Rect
import com.android.wm.shell.windowdecor.DragPositioningCallback.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Geometry shared by floating resize, rotation and persistence. */
object FloatingWindowGeometry {
    const val DEFAULT_SCALE = 1f / 1.3f
    const val MIN_SCALE = .5f

    fun movedBaseBounds(base: Rect, start: Rect, end: Rect): Rect? {
        val dx = end.left - start.left
        val dy = end.top - start.top
        if (dx == 0 && dy == 0) return null
        return Rect(base).apply { offset(dx, dy) }
    }

    fun entryScale(visible: Rect, stable: Rect): Float {
        if (visible.isEmpty || stable.isEmpty) return 1f
        return max(DEFAULT_SCALE, max(visible.width().toFloat() / stable.width(),
            visible.height().toFloat() / stable.height())).coerceAtMost(1f)
    }

    /** Fits logical geometry while preserving presentation size and aspect. */
    fun logicalBounds(visible: Rect, stable: Rect, scale: Float, out: Rect = Rect()): Rect {
        val width = (visible.width() / scale).roundToInt().coerceIn(1, stable.width())
        val height = (visible.height() / scale).roundToInt().coerceIn(1, stable.height())
        val left = visible.left.coerceIn(stable.left, stable.right - width)
        val top = visible.top.coerceIn(stable.top, stable.bottom - height)
        return out.apply { set(left, top, left + width, top + height) }
    }

    data class ScaledResize(val visible: Rect, val logical: Rect, var scale: Float,
        var commitsBounds: Boolean)

    fun scaledCorner(start: Rect, logical: Rect, stable: Rect, ctrl: Int,
        dx: Float, dy: Float, minWidth: Int, minHeight: Int,
        out: ScaledResize = ScaledResize(Rect(), Rect(), 1f, false)): ScaledResize {
        val visible = corner(start, stable, ctrl, dx, dy,
            (minWidth * MIN_SCALE).roundToInt(), (minHeight * MIN_SCALE).roundToInt(), out.visible)
        val requested = visible.width().toFloat() / logical.width()
        val scale = requested.coerceIn(MIN_SCALE, 1f)
        val commit = requested < MIN_SCALE - .001f || requested > 1.001f
        val bounds = if (commit) logicalBounds(visible, stable, scale, out.logical)
            else out.logical.apply { set(logical) }
        val actualScale = if (commit) max(visible.width().toFloat() / bounds.width(),
            visible.height().toFloat() / bounds.height()).coerceIn(MIN_SCALE, 1f) else scale
        // Keep the opposite corner fixed when display or app constraints clamp the result.
        val width = (bounds.width() * actualScale).roundToInt()
        val height = (bounds.height() * actualScale).roundToInt()
        val left = if (ctrl and CTRL_TYPE_LEFT != 0) start.right - width else start.left
        val top = if (ctrl and CTRL_TYPE_TOP != 0) start.bottom - height else start.top
        out.visible.set(left, top, left + width, top + height)
        out.scale = actualScale
        out.commitsBounds = commit
        return out
    }

    fun fixedLandscape(requested: Int, current: Rect): Boolean? = when {
        ActivityInfo.isFixedOrientationLandscape(requested) -> true
        ActivityInfo.isFixedOrientationPortrait(requested) -> false
        requested == ActivityInfo.SCREEN_ORIENTATION_LOCKED -> current.width() > current.height()
        else -> null
    }

    fun selectLandscape(landscape: Boolean, ratio: Float, fixed: Boolean): Boolean = when {
        fixed -> landscape
        !landscape && ratio > 1.08f -> true
        landscape && ratio < .92f -> false
        else -> landscape
    }

    fun isCorner(ctrl: Int): Boolean =
        ctrl and (CTRL_TYPE_LEFT or CTRL_TYPE_RIGHT) != 0 &&
            ctrl and (CTRL_TYPE_TOP or CTRL_TYPE_BOTTOM) != 0

    fun corner(start: Rect, stable: Rect, ctrl: Int, dx: Float, dy: Float,
        minWidth: Int, minHeight: Int, out: Rect = Rect()): Rect {
        val w = start.width().toFloat()
        val h = start.height().toFloat()
        if (w <= 0 || h <= 0) return out.apply { set(start) }
        val left = ctrl and CTRL_TYPE_LEFT != 0
        val top = ctrl and CTRL_TYPE_TOP != 0
        val dw = if (left) -dx else dx
        val dh = if (top) -dy else dy
        val scale = 1f + (dw * w + dh * h) / (w * w + h * h)
        val maxWidth = if (left) start.right - stable.left else stable.right - start.left
        val maxHeight = if (top) start.bottom - stable.top else stable.bottom - start.top
        val maxScale = min(maxWidth / w, maxHeight / h).coerceAtLeast(0.01f)
        val minScale = max(minWidth / w, minHeight / h).coerceAtMost(maxScale)
        val s = scale.coerceIn(minScale, maxScale)
        val width = (w * s).roundToInt().coerceAtLeast(1)
        val height = (h * s).roundToInt().coerceAtLeast(1)
        val x = if (left) start.right - width else start.left
        val y = if (top) start.bottom - height else start.top
        return out.apply { set(x, y, x + width, y + height) }
    }

    fun outline(start: Rect, ctrl: Int, dx: Float, dy: Float, out: Rect = Rect()): Rect =
        out.apply {
            set(start)
            if (ctrl and CTRL_TYPE_LEFT != 0) left = (start.left + dx).roundToInt()
                .coerceAtMost(right - 1)
            if (ctrl and CTRL_TYPE_RIGHT != 0) right = (start.right + dx).roundToInt()
                .coerceAtLeast(left + 1)
            if (ctrl and CTRL_TYPE_TOP != 0) top = (start.top + dy).roundToInt()
                .coerceAtMost(bottom - 1)
            if (ctrl and CTRL_TYPE_BOTTOM != 0) bottom = (start.bottom + dy).roundToInt()
                .coerceAtLeast(top + 1)
        }

    fun aspectBounds(start: Rect, stable: Rect, aspect: Float, minWidth: Int,
        minHeight: Int, centerX: Float = start.exactCenterX(),
        centerY: Float = start.exactCenterY()): Rect? {
        if (start.isEmpty || stable.isEmpty || !aspect.isFinite() || aspect <= 0f) return null
        val width = sqrt(start.width().toFloat() * start.height() * aspect).coerceAtLeast(1f)
        val height = width / aspect
        val maxScale = min(stable.width() / width, stable.height() / height)
        val minScale = max(minWidth / width, minHeight / height)
        if (minScale > maxScale) return null
        val scale = 1f.coerceIn(minScale, maxScale)
        val w = (width * scale).roundToInt().coerceIn(1, stable.width())
        val h = (height * scale).roundToInt().coerceIn(1, stable.height())
        if (w < minWidth || h < minHeight) return null
        val x = (centerX - w / 2f).roundToInt().coerceIn(stable.left, stable.right - w)
        val y = (centerY - h / 2f).roundToInt().coerceIn(stable.top, stable.bottom - h)
        return Rect(x, y, x + w, y + h)
    }

    fun scaledAspectBounds(start: Rect, logical: Rect, stable: Rect, aspect: Float,
        scale: Float, minWidth: Int, minHeight: Int, centerX: Float = start.exactCenterX(),
        centerY: Float = start.exactCenterY()): Rect? {
        val fitted = aspectBounds(logical, stable, aspect, minWidth, minHeight) ?: return null
        val width = (fitted.width() * scale).roundToInt().coerceAtLeast(1)
        val height = (fitted.height() * scale).roundToInt().coerceAtLeast(1)
        val left = (centerX - width / 2f).roundToInt().coerceIn(stable.left, stable.right - width)
        val top = (centerY - height / 2f).roundToInt().coerceIn(stable.top, stable.bottom - height)
        return Rect(left, top, left + width, top + height)
    }

    fun displayAspect(width: Int, height: Int, landscape: Boolean): Float {
        val short = min(width, height).coerceAtLeast(1).toFloat()
        val long = max(width, height).coerceAtLeast(1).toFloat()
        return if (landscape) long / short else short / long
    }
}
