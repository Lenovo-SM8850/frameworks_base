// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.content.pm.ActivityInfo
import android.graphics.Rect
import com.android.wm.shell.windowdecor.DragPositioningCallback.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Geometry shared by floating resize, rotation and persistence. */
object FloatingWindowGeometry {
    const val DEFAULT_SCALE = 1f / 1.3f
    const val MIN_SCALE = .5f
    // tan(22.5°): drags closer to an axis than to a diagonal change the ratio
    private const val AXIS_DRAG_TANGENT = .4142f

    fun movedBaseBounds(base: Rect, start: Rect, end: Rect): Rect? {
        val dx = end.left - start.left
        val dy = end.top - start.top
        if (dx == 0 && dy == 0) return null
        return Rect(base).apply { offset(dx, dy) }
    }

    fun scaledCorner(start: Rect, logical: Rect, stable: Rect, ctrl: Int,
        dx: Float, dy: Float, minWidth: Int, minHeight: Int,
        out: ScaledResize = ScaledResize(Rect(), Rect(), 1f, false),
        minimumScale: Float = MIN_SCALE, minimumVisibleShortSide: Int = 0): ScaledResize {
        val visible = corner(start, stable, ctrl, dx, dy,
            (minWidth * minimumScale).roundToInt(), (minHeight * minimumScale).roundToInt(), out.visible)
        val requested = visible.width().toFloat() / logical.width()
        val scale = requested.coerceIn(minimumScale, 1f)
        val commit = requested < minimumScale - .001f || requested > 1.001f
        val bounds = if (commit) logicalBounds(visible, stable, scale, out.logical)
            else out.logical.apply { set(logical) }
        if (commit && requested < minimumScale && minimumVisibleShortSide > 0) {
            val shortest = min(bounds.width(), bounds.height())
            if (shortest < minimumVisibleShortSide) {
                val factor = minimumVisibleShortSide.toFloat() / shortest.coerceAtLeast(1)
                val grown = Rect(bounds.left, bounds.top,
                    bounds.left + (bounds.width() * factor).roundToInt(),
                    bounds.top + (bounds.height() * factor).roundToInt())
                logicalBounds(grown, stable, 1f, bounds)
            }
        }
        var actualScale = if (commit) max(visible.width().toFloat() / bounds.width(),
            visible.height().toFloat() / bounds.height()).coerceIn(minimumScale, 1f) else scale
        if (commit && requested < minimumScale && minimumVisibleShortSide > 0) {
            actualScale = max(actualScale, minimumVisibleShortSide.toFloat() /
                min(bounds.width(), bounds.height())).coerceAtMost(1f)
        }
        out.atMinimum = requested <= minimumScale + .001f
        // Integer visible widths can otherwise round the other axis past a stable edge.
        val availableWidth = if (ctrl and CTRL_TYPE_LEFT != 0) start.right - stable.left
            else stable.right - start.left
        val availableHeight = if (ctrl and CTRL_TYPE_TOP != 0) start.bottom - stable.top
            else stable.bottom - start.top
        actualScale = minOf(actualScale, availableWidth.toFloat() / bounds.width(),
            availableHeight.toFloat() / bounds.height())
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

    /**
     * Selection belongs to one gesture; the original ratio belongs to the floating session.
     * [reshaping] is decided by the gesture's first movement: along one axis it changes the
     * ratio, diagonally it only resizes.
     */
    data class AspectSnapState(val originalAspect: Float, var selectedAspect: Float,
        var reshaping: Boolean? = null)

    /**
     * Prefer the natural display ratio, then 4:3 and 1:1, without offering near duplicates.
     * A square keeps the window's orientation by the orientation margin of [presetCorner].
     */
    fun aspectPresets(displayWidth: Int, displayHeight: Int, originalAspect: Float,
        landscape: Boolean, minAspect: Float = 0f, maxAspect: Float = 0f): List<Float> {
        val minimum = max(1f, minAspect.takeIf { it.isFinite() } ?: 0f)
        val maximum = maxAspect.takeIf { it.isFinite() && it > 1f }
            ?: Float.POSITIVE_INFINITY
        val original = if (landscape) originalAspect else 1f / originalAspect
        val ratios = mutableListOf<Float>()
        for (ratio in listOf(displayAspect(displayWidth, displayHeight, true), 4f / 3f, 1f,
            original)) {
            if (!ratio.isFinite() || ratio !in minimum..maximum ||
                ratios.any { abs(ln(ratio / it)) < .02f }) continue
            ratios.add(ratio)
        }
        return ratios.map { if (landscape) it else 1f / it }.sorted()
    }

    /** Require an advantage to switch, capped for distinct but closely spaced presets. */
    fun snapAspect(requested: Float, presets: List<Float>, state: AspectSnapState): Float? {
        if (presets.isEmpty()) return null
        val nearest = presets.minBy { abs(ln(requested / it)) }
        val current = presets.minBy { abs(ln(state.selectedAspect / it)) }
        val hysteresis = min(.03f, abs(ln(nearest / current)) * .25f)
        val target = if (abs(ln(requested / nearest)) + hysteresis < abs(ln(requested / current)))
            nearest else current
        state.selectedAspect = target
        return target
    }

    /**
     * A corner dragged along one axis chooses a preset; a diagonal drag keeps scale resizing, so
     * that resizing never changes the ratio by accident. The direction is decided once the drag
     * has moved [reshapeSlop].
     */
    fun presetCorner(start: Rect, logical: Rect, stable: Rect, ctrl: Int,
        dx: Float, dy: Float, minWidth: Int, minHeight: Int,
        minimumScale: Float = MIN_SCALE, minimumVisibleShortSide: Int = 0,
        minAspect: Float = 0f, maxAspect: Float = 0f, orientationMargin: Int = 1,
        out: ScaledResize = ScaledResize(Rect(), Rect(), 1f, false),
        displayWidth: Int = stable.width(), displayHeight: Int = stable.height(),
        snap: AspectSnapState = AspectSnapState(logical.width().toFloat() / logical.height(),
            logical.width().toFloat() / logical.height()),
        reshapeSlop: Float = 0f): ScaledResize {
        if (start.isEmpty || logical.isEmpty || stable.isEmpty) {
            out.visible.set(start)
            out.logical.set(logical)
            out.scale = 1f
            out.commitsBounds = false
            out.atMinimum = false
            return out
        }
        val along = max(abs(dx), abs(dy))
        if (snap.reshaping == null && along > 0f && hypot(dx, dy) >= reshapeSlop) {
            snap.reshaping = min(abs(dx), abs(dy)) <= along * AXIS_DRAG_TANGENT
        }
        if (snap.reshaping != true) {
            return scaledCorner(start, logical, stable, ctrl, dx, dy, minWidth, minHeight,
                out, minimumScale, minimumVisibleShortSide)
        }
        val landscape = logical.width() > logical.height()
        // Activity aspect limits are expressed as long side / short side.
        val minimum = if (minAspect.isFinite() && minAspect > 1f) minAspect else 1f
        val maximum = if (maxAspect.isFinite() && maxAspect >= minimum) maxAspect
            else Float.POSITIVE_INFINITY
        var low = if (landscape) minimum else 1f / maximum
        var high = if (landscape) maximum else 1f / minimum
        low = max(low, minWidth.coerceAtLeast(1).toFloat() / stable.height())
        high = min(high, stable.width().toFloat() / minHeight.coerceAtLeast(1))
        if (low > high) {
            // An app whose minimum geometry cannot fit keeps its existing valid shape.
            return scaledCorner(start, logical, stable, ctrl, dx, dy, minWidth, minHeight,
                out, minimumScale, minimumVisibleShortSide)
        }
        val left = ctrl and CTRL_TYPE_LEFT != 0
        val top = ctrl and CTRL_TYPE_TOP != 0
        val requestedWidth = (start.width() + if (left) -dx else dx).coerceAtLeast(1f)
        val requestedHeight = (start.height() + if (top) -dy else dy).coerceAtLeast(1f)
        val oldAspect = logical.width().toFloat() / logical.height()
        if (abs(ln(requestedWidth / requestedHeight / oldAspect)) < .02f &&
            abs(ln(snap.selectedAspect / oldAspect)) < .02f &&
            oldAspect in low..high) {
            return scaledCorner(start, logical, stable, ctrl, dx, dy, minWidth, minHeight,
                out, minimumScale, minimumVisibleShortSide)
        }
        val availableWidth = if (left) start.right - stable.left else stable.right - start.left
        val availableHeight = if (top) start.bottom - stable.top else stable.bottom - start.top
        val presets = aspectPresets(displayWidth, displayHeight, snap.originalAspect,
            landscape, minAspect, maxAspect).filter { ratio ->
                ratio in low..high && min(availableHeight.toFloat(), availableWidth / ratio) >=
                    maxOf(minHeight * minimumScale, minWidth * minimumScale / ratio,
                        minimumVisibleShortSide / min(1f, ratio))
            }
        val aspect = snapAspect(requestedWidth / requestedHeight, presets, snap)
            ?: return scaledCorner(start, logical, stable, ctrl, dx, dy, minWidth, minHeight,
                out, minimumScale, minimumVisibleShortSide)
        val maxHeight = min(availableHeight.toFloat(), availableWidth / aspect)
        val minHeightVisible = maxOf(minHeight * minimumScale,
            minWidth * minimumScale / aspect, minimumVisibleShortSide / min(1f, aspect))
        if (maxHeight < minHeightVisible) {
            return scaledCorner(start, logical, stable, ctrl, dx, dy, minWidth, minHeight,
                out, minimumScale, minimumVisibleShortSide)
        }
        // Project onto the constrained ratio without changing the opposite corner.
        val height = ((requestedWidth * aspect + requestedHeight) / (aspect * aspect + 1f))
            .coerceIn(minHeightVisible, maxHeight)
        val width = height * aspect
        val requestedScale = sqrt(width * height / (logical.width().toFloat() * logical.height()))
        val lowerScale = maxOf(minimumScale, width / stable.width(), height / stable.height())
        val upperScale = minOf(1f, width / minWidth.coerceAtLeast(1),
            height / minHeight.coerceAtLeast(1))
        val scale = requestedScale.coerceIn(lowerScale.coerceAtMost(upperScale), upperScale)
        val maxLogicalWidth = min(stable.width(), (availableWidth / scale).toInt())
        val maxLogicalHeight = min(stable.height(), (availableHeight / scale).toInt())
        var w = (width / scale).roundToInt().coerceIn(minWidth.coerceAtLeast(1), maxLogicalWidth)
        var h = (height / scale).roundToInt().coerceIn(minHeight.coerceAtLeast(1), maxLogicalHeight)
        // A full dp of separation also preserves orientation after configuration rounding.
        val gap = orientationMargin.coerceAtLeast(1)
        if (landscape) low = max(low, 1f + gap.toFloat() / h)
        else high = min(high, (h - gap).coerceAtLeast(1).toFloat() / h)
        if (w < h * low) {
            val requiredWidth = kotlin.math.ceil(h * low).toInt()
            if (requiredWidth <= maxLogicalWidth) w = requiredWidth
            else h = (w / low).toInt()
        }
        if (w > h * high) {
            val requiredHeight = kotlin.math.ceil(w / high).toInt()
            if (requiredHeight <= maxLogicalHeight) h = requiredHeight
            else w = (h * high).toInt()
        }
        val visibleWidth = (w * scale).roundToInt()
        val visibleHeight = (h * scale).roundToInt()
        val x = if (left) start.right - visibleWidth else start.left
        val y = if (top) start.bottom - visibleHeight else start.top
        out.visible.set(x, y, x + visibleWidth, y + visibleHeight)
        out.logical.set(x.coerceIn(stable.left, stable.right - w),
            y.coerceIn(stable.top, stable.bottom - h), 0, 0)
        out.logical.right = out.logical.left + w
        out.logical.bottom = out.logical.top + h
        out.scale = scale
        out.commitsBounds = w != logical.width() || h != logical.height()
        out.atMinimum = requestedScale <= minimumScale + .001f ||
            minimumVisibleShortSide > 0 && min(visibleWidth, visibleHeight) <= minimumVisibleShortSide + 1
        return out
    }

    fun fixedLandscape(requested: Int, current: Rect): Boolean? = when {
        ActivityInfo.isFixedOrientationLandscape(requested) -> true
        ActivityInfo.isFixedOrientationPortrait(requested) -> false
        requested == ActivityInfo.SCREEN_ORIENTATION_LOCKED -> current.width() > current.height()
        else -> null
    }

    fun corner(start: Rect, stable: Rect, ctrl: Int, dx: Float, dy: Float,
        minWidth: Int, minHeight: Int, out: Rect = Rect()): Rect {
        val w = start.width().toFloat()
        val h = start.height().toFloat()
        if (w <= 0 || h <= 0) return out.apply { set(start) }
        val left = ctrl and CTRL_TYPE_LEFT != 0
        val top = ctrl and CTRL_TYPE_TOP != 0
        val dw = if (left) -dx else dx
        val dh = if (top) -dy else dy
        val horizontal = ctrl and (CTRL_TYPE_LEFT or CTRL_TYPE_RIGHT) != 0
        val vertical = ctrl and (CTRL_TYPE_TOP or CTRL_TYPE_BOTTOM) != 0
        val scale = 1f + when {
            horizontal && vertical -> (dw * w + dh * h) / (w * w + h * h)
            horizontal -> dw / w
            else -> dh / h
        }
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
