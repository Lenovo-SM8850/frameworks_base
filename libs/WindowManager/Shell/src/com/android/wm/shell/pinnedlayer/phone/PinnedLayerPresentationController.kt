/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.pinnedlayer.phone

import android.app.TaskInfo
import android.app.WindowConfiguration
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.util.Slog
import android.util.SparseArray
import android.util.TypedValue
import android.view.Display.DEFAULT_DISPLAY
import com.android.wm.shell.pinnedlayer.phone.FloatingWindowGeometry.displayAspect
import android.window.TransitionRequestInfo
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.shared.desktopmode.DesktopState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A controller that tracks the available display area for the pinned layer. */
class PinnedLayerPresentationController(
    context: Context,
    private val displayController: DisplayController,
    private val desktopState: DesktopState,
    private val geometryStore: FloatingWindowGeometryStore =
        FloatingWindowGeometryStore({ null }, java.util.concurrent.Executor { it.run() }),
) {

    val miniState = FloatingMiniState()

    private data class Choice(val manual: Boolean, val landscape: Boolean)
    private val choices = mutableMapOf<Int, Choice>()
    private val rotationBounds = mutableMapOf<Int, Rect>()
    private val entryScales = mutableMapOf<Int, Float>()
    private val rotatedStableBounds = mutableMapOf<Int, Rect>()

    fun entryScale(task: TaskInfo): Float = entryScales[task.taskId]
        ?: if (task.isFloatingWindow) task.floatingWindowScale else FloatingWindowGeometry.DEFAULT_SCALE

    fun applyPresentation(task: TaskInfo, visible: Rect, wct: android.window.WindowContainerTransaction,
        scale: Float = entryScale(task)) {
        val stable = rotatedStableBounds.remove(task.taskId)
            ?: getDisplayDetails(task.displayId)?.stableBounds() ?: return
        entryScales.remove(task.taskId)
        val logical = FloatingWindowGeometry.logicalBounds(visible, stable, scale)
        wct.setBounds(task.token, logical)
            .setFloatingWindowPresentation(task.token, android.graphics.Point(visible.left, visible.top), scale)
    }

    fun setManualOrientation(task: TaskInfo, landscape: Boolean, bounds: Rect) {
        choices[task.taskId] = Choice(true, landscape)
        rotationBounds[task.taskId] = Rect(bounds)
        rememberBounds(task, bounds)
    }

    fun removeUser(userId: Int) {
        miniState.removeUser(userId)
        geometryStore.removeUser(userId)
    }

    fun forgetTask(taskId: Int) {
        entryScales.remove(taskId)
        rotatedStableBounds.remove(taskId)
        choices.remove(taskId)
        rotationBounds.remove(taskId)
        overriddenBounds.remove(taskId)
    }

    fun getManualRotationBounds(task: TaskInfo): Rect? {
        val logical = task.configuration.windowConfiguration.bounds
        if (!task.isFloatingWindow || FloatingWindowGeometry.fixedLandscape(
            task.topActivityRequestedOrientation, logical) != null) return null
        val details = getDisplayDetails(task.displayId) ?: return null
        val landscape = logical.width() <= logical.height()
        return FloatingWindowGeometry.scaledAspectBounds(task.floatingWindowRestoreBounds,
            logical, details.stableBounds(),
            displayAspect(details.layout.width(), details.layout.height(), landscape),
            task.floatingWindowScale, minWidth(task, details), minHeight(task, details))
    }

    /** Rotates geometry in the display WCT without changing a manually selected orientation. */
    fun getRotationBounds(task: TaskInfo, fromRotation: Int, toRotation: Int): Rect? {
        if (!task.isFloatingWindow) return null
        val details = getDisplayDetails(task.displayId) ?: return null
        val oldLayout = DisplayLayout(details.layout).apply { rotateTo(details.context.resources, fromRotation) }
        val newLayout = DisplayLayout(details.layout).apply { rotateTo(details.context.resources, toRotation) }
        val oldStable = Rect().also { oldLayout.getStableBounds(it) }
        val stable = Rect().also { newLayout.getStableBounds(it) }
        if (oldStable.isEmpty || stable.isEmpty) return null
        val start = task.floatingWindowRestoreBounds
        if (start.isEmpty) return null
        val choice = choices[task.taskId] ?: restoreChoice(task, DisplayDetails(oldLayout, details.context))
        rememberBounds(task, start, DisplayDetails(oldLayout, details.context))
        val landscape = FloatingWindowGeometry.fixedLandscape(task.topActivityRequestedOrientation, start)
            ?: choice?.takeIf { it.manual }?.landscape
            ?: (newLayout.width() > newLayout.height())
        val x = stable.left + (start.exactCenterX() - oldStable.left) / oldStable.width() * stable.width()
        val y = stable.top + (start.exactCenterY() - oldStable.top) / oldStable.height() * stable.height()
        val logicalStart = task.configuration.windowConfiguration.bounds
        val result = FloatingWindowGeometry.scaledAspectBounds(start, logicalStart, stable,
            displayAspect(newLayout.width(), newLayout.height(), landscape), task.floatingWindowScale,
            minWidth(task, details), minHeight(task, details), x, y) ?: return null
        choices[task.taskId] = Choice(choice?.manual == true, landscape)
        rotationBounds[task.taskId] = Rect(result)
        rotatedStableBounds[task.taskId] = Rect(stable)
        rememberBounds(task, result, DisplayDetails(newLayout, details.context))
        return result
    }

    private fun restoreChoice(task: TaskInfo, details: DisplayDetails): Choice? {
        val entry = restored(task, details) ?: return null
        return Choice(entry.manual, entry.landscape).also { choices[task.taskId] = it }
    }

    private fun restored(task: TaskInfo, details: DisplayDetails): FloatingWindowGeometryStore.Entry? {
        val key = boundsKey(task, details) ?: return null
        return geometryStore.restore(key.first, key.second, key.third, details.stableBounds(),
            details.layout.width(), details.layout.height(), details.context.resources.displayMetrics.densityDpi)
    }

    private fun minWidth(task: TaskInfo, details: DisplayDetails): Int =
        if (task.minWidth > 0) task.minWidth else
            (max(task.defaultMinSize, 1) * details.context.resources.displayMetrics.density).toInt()

    private fun minHeight(task: TaskInfo, details: DisplayDetails): Int =
        if (task.minHeight > 0) task.minHeight else
            (max(task.defaultMinSize, 1) * details.context.resources.displayMetrics.density).toInt()

    // Stores the tasks' initial and overridden bounds, which are used as a reference for
    // subsequent resize operations to prevent implicit movement of the task by resizing it in
    // various ways (e.g., by expanding or contracting against display boundaries).
    private val overriddenBounds: SparseArray<OverridableBounds> = SparseArray()

    /** Returns remembered package bounds, or a centered window matching display orientation. */
    fun getPinEntryDestinationBounds(task: TaskInfo, displayId: Int = task.displayId): Rect? {
        val details = getDisplayDetails(displayId) ?: return null
        val stable = details.stableBounds()
        val remembered = restored(task, details)
        entryScales[task.taskId] = remembered?.scale
            ?: if (task.isFloatingWindow) task.floatingWindowScale else FloatingWindowGeometry.DEFAULT_SCALE
        if (remembered != null) {
            choices[task.taskId] = Choice(remembered.manual, remembered.landscape)
            val fixed = FloatingWindowGeometry.fixedLandscape(task.topActivityRequestedOrientation,
                remembered.bounds)
            val bounds = if (fixed != null && fixed != remembered.landscape) {
                FloatingWindowGeometry.aspectBounds(remembered.bounds, stable,
                    displayAspect(details.layout.width(), details.layout.height(), fixed),
                    (minWidth(task, details) * entryScale(task)).toInt(),
                    (minHeight(task, details) * entryScale(task)).toInt()) ?: remembered.bounds
            } else remembered.bounds
            return entryBounds(task, bounds, details)
        }
        if (task.isFloatingWindow) {
            val current = task.floatingWindowRestoreBounds
            val choice = choices[task.taskId]
            val landscape = FloatingWindowGeometry.fixedLandscape(task.topActivityRequestedOrientation,
                current) ?: choice?.takeIf { it.manual }?.landscape
                ?: (details.layout.width() > details.layout.height())
            choices[task.taskId] = Choice(choice?.manual == true, landscape)
            val bounds = if ((current.width() > current.height()) != landscape) {
                FloatingWindowGeometry.scaledAspectBounds(current,
                    task.configuration.windowConfiguration.bounds, stable,
                    displayAspect(details.layout.width(), details.layout.height(), landscape),
                    entryScale(task), minWidth(task, details), minHeight(task, details)) ?: current
            } else current
            return entryBounds(task, bounds, details)
        }
        val landscape = FloatingWindowGeometry.fixedLandscape(task.topActivityRequestedOrientation,
            task.configuration.windowConfiguration.bounds) ?: (details.layout.width() > details.layout.height())
        choices[task.taskId] = Choice(false, landscape)
        val shortEdge = (max(details.layout.width(), details.layout.height()) * .30f).toInt()
        val longEdge = max(shortEdge,
            (min(details.layout.width(), details.layout.height()) * .72f).toInt())
        val width = if (landscape) longEdge else shortEdge
        val height = if (landscape) shortEdge else longEdge
        val bounds = Rect(0, 0, width, height)
        bounds.offset(stable.centerX() - width / 2, stable.centerY() - height / 2)
        return entryBounds(task, bounds, details)
    }

    private fun entryBounds(task: TaskInfo, bounds: Rect, details: DisplayDetails): Rect {
        val visible = clampFloatingBounds(task, bounds, details)
        val stable = details.stableBounds()
        entryScales[task.taskId] = max(entryScale(task), max(visible.width().toFloat() / stable.width(),
            visible.height().toFloat() / stable.height())).coerceAtMost(1f)
        return visible
    }

    /** Remembers user geometry independently for each package, user, and device orientation. */
    fun rememberBounds(task: TaskInfo) {
        if (!task.isFloatingWindow) return
        val bounds = task.floatingWindowRestoreBounds
        val pending = rotationBounds.remove(task.taskId)
        if (pending != null && !boundsMatch(pending, bounds)) return
        val details = getDisplayDetails(task.displayId) ?: return
        recoverMini(task)
        rememberBounds(task, bounds, details)
    }

    /** Rebuilds mini ownership before surviving presentation geometry can be remembered. */
    fun recoverMini(task: TaskInfo): FloatingMiniState.Entry? {
        if (!task.isFloatingWindow || task.displayId != DEFAULT_DISPLAY ||
            miniState.get(task.userId, task.taskId) != null) return null
        val details = getDisplayDetails(task.displayId) ?: return null
        val stable = details.stableBounds()
        val logical = task.configuration.windowConfiguration.bounds
        val visible = task.floatingWindowRestoreBounds
        if (stable.isEmpty || logical.isEmpty || visible.isEmpty) return null
        val margin = (8 * details.context.resources.displayMetrics.density).toInt()
        val scale = FloatingMiniState.scale(logical, stable,
            min(details.layout.width(), details.layout.height()), margin)
        val mini = FloatingMiniState.sized(logical, scale, visible.left, visible.top)
        val edge = FloatingMiniState.stashEdge(visible, stable)
        val inside = Rect(visible)
        val mostlyOutside = !inside.intersect(stable) ||
            inside.width().toLong() * inside.height() * 2 < visible.width().toLong() * visible.height()
        if (!mostlyOutside && min(visible.width(), visible.height()) >
            min(mini.width(), mini.height()) + 2) return null
        val normalTask = android.app.ActivityManager.RunningTaskInfo(
            task as android.app.ActivityManager.RunningTaskInfo).apply { isFloatingWindow = false }
        val normal = getPinEntryDestinationBounds(normalTask) ?: return null
        val normalScale = entryScale(normalTask)
        entryScales.remove(task.taskId)
        val bounds = if (edge != FloatingMiniState.Edge.NONE)
            FloatingMiniState.stash(mini, stable, edge,
                (28 * details.context.resources.displayMetrics.density).toInt())
            else FloatingMiniState.clamp(mini, stable, margin)
        return miniState.enter(task.userId, task.taskId,
            FloatingMiniState.Restore(normal,
                FloatingWindowGeometry.logicalBounds(normal, stable, normalScale), normalScale),
            bounds, scale).also { it.edge = edge }
    }

    private fun boundsMatch(expected: Rect, actual: Rect): Boolean =
        kotlin.math.abs(expected.left - actual.left) <= 2 &&
            kotlin.math.abs(expected.top - actual.top) <= 2 &&
            kotlin.math.abs(expected.right - actual.right) <= 2 &&
            kotlin.math.abs(expected.bottom - actual.bottom) <= 2

    private fun rememberBounds(task: TaskInfo, bounds: Rect,
        details: DisplayDetails? = getDisplayDetails(task.displayId)) {
        details ?: return
        val mini = miniState.get(task.userId, task.taskId)
        val state = when {
            mini == null -> FloatingWindowGeometryStore.PresentationState.NORMAL
            mini.edge == FloatingMiniState.Edge.NONE -> FloatingWindowGeometryStore.PresentationState.MINI
            else -> FloatingWindowGeometryStore.PresentationState.STASHED
        }
        val key = boundsKey(task, details) ?: return
        val previous = choices[task.taskId] ?: restoreChoice(task, details)
        val choice = Choice(previous?.manual == true, bounds.width() > bounds.height())
        if (mini == null) choices[task.taskId] = choice
        geometryStore.store(key.first, key.second, key.third, bounds, details.stableBounds(),
            details.layout.width(), details.layout.height(), details.context.resources.displayMetrics.densityDpi,
            choice.manual, choice.landscape,
            if (task.isFloatingWindow) task.floatingWindowScale else entryScale(task), state)
    }

    private fun boundsKey(task: TaskInfo, details: DisplayDetails): Triple<String, Int, Int>? {
        val packageName = task.baseActivity?.packageName ?: task.realActivity?.packageName
            ?: task.baseIntent?.component?.packageName ?: return null
        val orientation = if (details.layout.width() > details.layout.height()) {
            Configuration.ORIENTATION_LANDSCAPE
        } else {
            Configuration.ORIENTATION_PORTRAIT
        }
        return Triple(packageName, task.userId, orientation)
    }

    private fun clampFloatingBounds(task: TaskInfo, bounds: Rect, details: DisplayDetails,
        fitLogical: Boolean = false): Rect {
        val stable = details.stableBounds()
        val density = details.context.resources.displayMetrics.density
        val defaultMin = (max(task.defaultMinSize, 1) * density).toInt()
        val scale = entryScale(task)
        val minWidth = ((if (task.minWidth > 0) task.minWidth else defaultMin) * scale).toInt()
        val minHeight = ((if (task.minHeight > 0) task.minHeight else defaultMin) * scale).toInt()
        val sizeScale = if (fitLogical) scale else 1f
        val width = bounds.width().coerceAtLeast(minWidth)
            .coerceAtMost((stable.width() * sizeScale).toInt().coerceAtLeast(1))
        val height = bounds.height().coerceAtLeast(minHeight)
            .coerceAtMost((stable.height() * sizeScale).toInt().coerceAtLeast(1))
        val left = bounds.left.coerceIn(stable.left, stable.right - width)
        val top = bounds.top.coerceIn(stable.top, stable.bottom - height)
        return Rect(left, top, left + width, top + height)
    }

    /**
     * Checks if the task is in a state that is supported for pinning.
     *
     * @param task The task to check.
     * @return True if the task is supported, false otherwise.
     */
    fun isTaskSupportedForPinning(task: TaskInfo): Boolean {
        val mode = task.windowingMode
        return task.displayId == DEFAULT_DISPLAY && task.isResizeable &&
            task.activityType == WindowConfiguration.ACTIVITY_TYPE_STANDARD &&
            (mode == WindowConfiguration.WINDOWING_MODE_FREEFORM ||
                mode == WindowConfiguration.WINDOWING_MODE_FULLSCREEN ||
                mode == WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW) &&
            desktopState.isDesktopModeSupportedOnDisplay(task.displayId)
    }

    /**
     * Changes task's bounds to match constraints and ensures that it's positioned correctly on the
     * display.
     *
     * @param task a task that's being clamped.
     * @param bounds current task's bounds, either real task position or leash position.
     * @param displayId a source display id based on which to calculate bounds.
     * @return new bounds that match window constraints and that're placed inside visible display
     *   bounds.
     */
    fun clampToDisplay(task: TaskInfo, bounds: Rect, displayId: Int = task.displayId): Rect? {
        val displayDetails = getDisplayDetails(displayId) ?: return null

        if (task.isFloatingWindow) {
            return clampFloatingBounds(task, Rect(bounds), displayDetails)
        }
        return clampSizeToConstraints(bounds, displayDetails)?.also {
            ensureNotOffscreen(it, displayDetails)
        }
    }

    /**
     * Calculates the new bounds for a pinned task.
     *
     * For pinned tasks, only resizing is allowed; movement is not. The new bounds must adhere to
     * min/max size constraints and remain on-screen.
     *
     * To prevent users from implicitly moving the task by resizing it in various ways (e.g., by
     * expanding or contracting against display boundaries), the task's initial position is
     * preserved. Subsequent resize operations utilize this preserved position to determine the new
     * bounds. The task is expanded or contracted against the display's center, see
     * [resizeAndAdjustPosition] for more details.
     *
     * @param task The task for which to calculate the bounds.
     * @param requestedLocation The requested location of the task.
     * @return A [Rect] with the calculated bounds, or [null] if calculation is not possible.
     */
    fun calculateNewTaskBounds(
        task: TaskInfo,
        requestedLocation: TransitionRequestInfo.RequestedLocation,
    ): Rect? {
        val currentBounds = task.floatingWindowRestoreBounds
        if (requestedLocation.displayId != task.displayId) {
            Slog.d(TAG, "Requested location on different display, ignoring the requested bounds")
            return if (task.isFloatingWindow) null else currentBounds
        }
        val displayDetails = getDisplayDetails(task.displayId) ?: return null

        if (currentBounds != overriddenBounds.get(task.taskId)?.resolvedBounds()) {
            // Bounds have changed since the last positioning, e.g., due to user interaction.
            // Clear the overrides.
            overriddenBounds.remove(task.taskId)
        }
        val overridableBounds: OverridableBounds =
            overriddenBounds.getOrCompute(task.taskId) { OverridableBounds(currentBounds) }

        val clampedBounds =
            if (task.isFloatingWindow) {
                if (requestedLocation.bounds.isEmpty) return null
                clampFloatingBounds(task, Rect(requestedLocation.bounds), displayDetails, fitLogical = true)
            } else clampSizeToConstraints(requestedLocation.bounds, displayDetails) ?: return null
        // always compare against the initial bounds, to disallow moving the task by subsequent
        // resizes.
        overridableBounds.overriddenBounds =
            resizeAndAdjustPosition(displayDetails, overridableBounds.initBounds, clampedBounds)

        return overridableBounds.overriddenBounds
    }

    /**
     * Adjusts the task [bounds] to match the [targetSize] and adjusts the position to prevent task
     * movement by subsequent resizes.
     *
     * When resized, strives to move only one task's edge on each axis. If the task is expanding,
     * the edge that's farther away from the center is moved. If shrinking, the edge that's closer
     * to the center is moved. Final position could be adjusted to not end up offscreen.
     *
     * @param displayDetails The display details for the task.
     * @param bounds The initial bounds of the task.
     * @param targetSize The requested size of the task.
     */
    private fun resizeAndAdjustPosition(
        displayDetails: DisplayDetails,
        bounds: Rect,
        targetSize: Rect,
    ): Rect {
        val result = Rect(bounds)
        val displayCenter = displayDetails.getCenter()
        val widthExpands = targetSize.width() > bounds.width()
        val heightExpands = targetSize.height() > bounds.height()
        val leftIsCloser =
            displayCenter.xAxisDistanceTo(bounds.left) < displayCenter.xAxisDistanceTo(bounds.right)
        val topIsCloser =
            displayCenter.yAxisDistanceTo(bounds.top) < displayCenter.yAxisDistanceTo(bounds.bottom)

        // If expanding, take the edge father away from the center.
        // If shrinking, take the edge closer to the center.
        val moveFactor =
            Rect(
                // 1 - if edge should move, 0 - otherwise.
                /* left= */ if (widthExpands xor leftIsCloser) 1 else 0,
                /* top= */ if (heightExpands xor topIsCloser) 1 else 0,
                /* right= */ if (widthExpands xor leftIsCloser) 0 else 1,
                /* bottom= */ if (heightExpands xor topIsCloser) 0 else 1,
            )

        val widthDelta = targetSize.width() - bounds.width()
        val heightDelta = targetSize.height() - bounds.height()
        return result.apply {
            left -= widthDelta * moveFactor.left
            top -= heightDelta * moveFactor.top
            right += widthDelta * moveFactor.right
            bottom += heightDelta * moveFactor.bottom
            ensureNotOffscreen(this, displayDetails)
        }
    }

    private fun clampSizeToConstraints(bounds: Rect, displayDetails: DisplayDetails): Rect? {
        val displayLayout = displayDetails.layout
        val displayContext = displayDetails.context

        if (bounds.width() <= 0 || bounds.height() <= 0) {
            Slog.w(TAG, "Cannot get min/max bounds for task with empty or invalid bounds")
            return null
        }

        val w0 = bounds.width().toFloat()
        val h0 = bounds.height().toFloat()

        val minSizePx =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                MIN_SIZE_DP,
                displayContext.resources.displayMetrics,
            )
        val maxWidth = displayLayout.width() * MAX_SIZE_RATIO
        val maxHeight = displayLayout.height() * MAX_SIZE_RATIO

        // Adjust the task size to fit within the constraints.
        val minScale = max(minSizePx / w0, minSizePx / h0)
        val maxScale = minOf(maxWidth / w0, maxHeight / h0)
        val scale =
            if (minScale > maxScale) {
                // Constraints conflict, prioritize max size
                // Should not happen, CDD requires min 440dp display for freeform
                maxScale
            } else {
                1.0f.coerceIn(minScale, maxScale)
            }
        val finalWidth = w0 * scale
        val finalHeight = h0 * scale

        return Rect(
            bounds.left,
            bounds.top,
            bounds.left + finalWidth.toInt(),
            bounds.top + finalHeight.toInt(),
        )
    }

    /**
     * Ensures that the bounds are not offscreen, by adjusting its position or size if necessary.
     */
    private fun ensureNotOffscreen(bounds: Rect, displayDetails: DisplayDetails) {
        val stableBounds = displayDetails.stableBounds()
        bounds.apply {
            // 1. Try to fit |bounds| in |stableBounds| without changing size
            offset(max(stableBounds.left - left, 0), 0)
            offset(min(stableBounds.right - right, 0), 0)
            offset(0, max(stableBounds.top - top, 0))
            offset(0, min(stableBounds.bottom - bottom, 0))

            // 2. Ensure that |bounds| fit inside |stableBounds| even if that
            // requires size changes.
            intersect(stableBounds)
        }
    }

    private companion object {
        private const val TAG = "PinnedLayerDisplayAreaController"
        // min = 220dp, max = 70% of the display width and height
        private const val MIN_SIZE_DP = 220f
        private const val MAX_SIZE_RATIO = 0.7f
    }

    /** Stores the initial bounds of a task, allowing overrides for subsequent resize operations. */
    private class OverridableBounds(val initBounds: Rect) {
        var overriddenBounds: Rect? = null

        fun resolvedBounds(): Rect {
            return overriddenBounds ?: initBounds
        }
    }

    private data class DisplayDetails(val layout: DisplayLayout, val context: Context) {
        fun stableBounds(): Rect = Rect().also { layout.getStableBounds(it) }

        fun getCenter(): DisplayCenter =
            stableBounds().let { DisplayCenter(it.exactCenterX(), it.exactCenterY()) }
    }

    private fun getDisplayDetails(displayId: Int): DisplayDetails? {
        val displayLayout = displayController.getDisplayLayout(displayId)
        val displayContext = displayController.getDisplayContext(displayId)
        if (displayLayout == null || displayContext == null) {
            Slog.w(TAG, "Cannot get bounds for display $displayId, layout or context not found")
            return null
        }
        return DisplayDetails(displayLayout, displayContext)
    }

    private data class DisplayCenter(val x: Float, val y: Float) {
        fun xAxisDistanceTo(x: Int) = abs(x - this.x)

        fun yAxisDistanceTo(y: Int) = abs(y - this.y)
    }

    private fun <T> SparseArray<T>.getOrCompute(key: Int, compute: () -> T): T {
        if (!contains(key)) {
            put(key, compute())
        }
        return get(key)
    }
}
