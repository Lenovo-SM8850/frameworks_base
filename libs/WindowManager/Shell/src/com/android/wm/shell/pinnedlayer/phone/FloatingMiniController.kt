// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.TaskInfo
import android.graphics.Point
import android.graphics.Rect
import android.view.Choreographer
import android.view.SurfaceControl
import android.window.TransitionInfo
import android.window.WindowContainerTransaction
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.SurfaceFrameRate
import com.android.wm.shell.shared.animation.Interpolators
import kotlin.math.min
import kotlin.math.roundToInt

/** Live mini presentation and stash interaction without hiding or relaunching the task. */
class FloatingMiniController(
    private val organizer: ShellTaskOrganizer,
    private val displays: DisplayController,
    private val state: FloatingMiniState,
    private val choreographer: Choreographer = Choreographer.getInstance(),
    private val transactions: () -> SurfaceControl.Transaction = { SurfaceControl.Transaction() },
    private val animators: () -> ValueAnimator = { ValueAnimator.ofFloat(0f, 1f) },
    private val changed: (TaskInfo) -> Unit,
) {
    private val pendingResize = mutableMapOf<FloatingMiniState.Key, Pair<Int, Int>>()
    private class Operation(val animator: ValueAnimator, val task: TaskInfo,
        val target: Rect, val scale: Float, val logical: Rect?, val radius: Float, val finish: () -> Unit)
    private val animations = mutableMapOf<FloatingMiniState.Key, Operation>()
    private val leashes = mutableMapOf<Int, SurfaceControl>()
    private val dragFrames = mutableMapOf<FloatingMiniState.Key, Frame>()
    private var frameScheduled = false
    private val dragCallback = Choreographer.FrameCallback {
        frameScheduled = false
        dragFrames.values.forEach { frame ->
            if (frame.pending) { frame.pending = false; frame.draw() }
        }
    }

    init {
        organizer.initialTasks.forEach { leashes[it.first.taskId] = it.second }
        organizer.addTaskAppearedListener(object : ShellTaskOrganizer.TaskAppearedListener {
            override fun onTaskAppeared(taskInfo: android.app.ActivityManager.RunningTaskInfo,
                leash: SurfaceControl) { leashes[taskInfo.taskId] = leash }
        })
        organizer.addTaskVanishedListener(object : ShellTaskOrganizer.TaskVanishedListener {
            override fun onTaskVanished(taskInfo: android.app.ActivityManager.RunningTaskInfo) {
                cancel(taskInfo)
                clearDrag(taskInfo)
                leashes.remove(taskInfo.taskId)
            }
        })
    }

    private fun key(task: TaskInfo) = FloatingMiniState.Key(task.userId, task.taskId)

    private inner class Frame(task: TaskInfo) : AutoCloseable {
        val bounds = Rect()
        var pending = false
        private val leash = leashes[task.taskId]
        private val width = task.configuration.windowConfiguration.bounds.width()
        private val height = task.configuration.windowConfiguration.bounds.height()
        val radius = displays.getDisplayContext(task.displayId)?.resources?.getDimension(
            com.android.wm.shell.shared.R.dimen.desktop_windowing_freeform_rounded_corner_radius) ?: 0f
        private val transaction = transactions()
        private var closed = false

        fun draw() {
            val surface = leash?.takeIf { it.isValid } ?: return
            if (closed || width <= 0 || height <= 0) return
            val sx = bounds.width().toFloat() / width
            val sy = bounds.height().toFloat() / height
            transaction.setMatrix(surface, sx, 0f, 0f, sy)
                .setPosition(surface, bounds.left.toFloat(), bounds.top.toFloat())
                .setWindowCrop(surface, width, height)
                .setCornerRadius(surface, radius / min(sx, sy)).apply()
        }

        override fun close() {
            if (!closed) { closed = true; transaction.close() }
        }
    }

    private fun clearDrag(task: TaskInfo) {
        dragFrames.remove(key(task))?.let {
            it.close()
            SurfaceFrameRate.setInteracting(it, false)
        }
        if (frameScheduled && dragFrames.values.none { it.pending }) {
            choreographer.removeFrameCallback(dragCallback)
            frameScheduled = false
        }
    }

    fun recover(task: TaskInfo, entry: FloatingMiniState.Entry) {
        organizer.applyTransaction(WindowContainerTransaction().setDragResizing(task.token, false)
            .setFloatingWindowPresentation(task.token, Point(entry.bounds.left, entry.bounds.top), entry.scale))
        changed(updatedTask(task, entry.bounds, entry.scale, null))
    }

    private fun density(task: TaskInfo) = displays.getDisplayContext(task.displayId)
        ?.resources?.displayMetrics?.density ?: 1f
    private fun stable(task: TaskInfo) = Rect().also {
        displays.getDisplayLayout(task.displayId)?.getStableBounds(it)
    }
    private fun minimum(task: TaskInfo, stable: Rect): Float {
        val layout = displays.getDisplayLayout(task.displayId) ?: return task.floatingWindowScale
        return min(task.floatingWindowScale, FloatingMiniState.scale(
            task.configuration.windowConfiguration.bounds, stable,
            min(layout.width(), layout.height()), (8 * density(task)).toInt()))
    }

    data class DragTarget(val bounds: Rect, val scale: Float, val radius: Float)

    fun getDragTarget(task: TaskInfo, start: Rect, end: Rect): DragTarget? {
        if (!task.isFloatingWindow || state.get(task.userId, task.taskId) != null || start == end) return null
        val stable = stable(task)
        if (stable.isEmpty) return null
        val corner = FloatingMiniState.corner(end, stable, (48 * density(task)).toInt()) ?: return null
        val scale = minimum(task, stable)
        val bounds = FloatingMiniState.snap(FloatingMiniState.sized(
            task.configuration.windowConfiguration.bounds, scale, end.left, end.top),
            stable, corner, (8 * density(task)).toInt())
        val radius = displays.getDisplayContext(task.displayId)?.resources?.getDimension(
            com.android.wm.shell.shared.R.dimen.desktop_windowing_freeform_rounded_corner_radius) ?: 0f
        return DragTarget(bounds, scale, radius)
    }

    fun onDragEnded(task: TaskInfo, start: Rect, end: Rect): Boolean {
        clearDrag(task)
        val stable = stable(task)
        if (stable.isEmpty) return false
        val entry = state.get(task.userId, task.taskId)
        val margin = (8 * density(task)).toInt()
        val corner = FloatingMiniState.corner(end, stable, (48 * density(task)).toInt())
        if (entry == null) {
            val target = getDragTarget(task, start, end) ?: return false
            val restore = FloatingWindowGeometry.movedBaseBounds(
                task.floatingWindowRestoreBounds, start, end) ?: task.floatingWindowRestoreBounds
            enter(task, FloatingMiniState.clamp(restore, stable),
                task.configuration.windowConfiguration.bounds, task.floatingWindowScale,
                end, task.floatingWindowScale, target.bounds, target.scale)
        } else {
            val crossed = FloatingMiniState.stashEdge(end, stable)
            val edge = if (crossed != FloatingMiniState.Edge.NONE) crossed else
                if (FloatingMiniState.canUnstash(end, stable, entry.edge))
                    FloatingMiniState.Edge.NONE else entry.edge
            entry.edge = edge
            val destination = if (edge != FloatingMiniState.Edge.NONE)
                FloatingMiniState.stash(end, stable, edge, (28 * density(task)).toInt()) else
                corner?.let { FloatingMiniState.snap(end, stable, it, margin) }
                    ?: FloatingMiniState.clamp(end, stable, margin)
            animate(task, end, entry.scale, destination, entry.scale)
            changed(task)
        }
        return true
    }

    private fun enter(task: TaskInfo, restore: Rect, logical: Rect, restoreScale: Float,
        from: Rect, fromScale: Float, to: Rect, scale: Float) {
        state.enter(task.userId, task.taskId,
            FloatingMiniState.Restore(restore, logical, restoreScale), to, scale)
        animate(task, from, fromScale, to, scale)
        changed(task)
    }

    fun enterAfterResize(task: TaskInfo, restore: Rect, logical: Rect, restoreScale: Float,
        bounds: Rect, scale: Float) {
        state.enter(task.userId, task.taskId,
            FloatingMiniState.Restore(restore, logical, restoreScale), bounds, scale)
        val expected = Pair((bounds.width() / scale).roundToInt(),
            (bounds.height() / scale).roundToInt())
        val current = task.configuration.windowConfiguration.bounds
        if (expected != Pair(current.width(), current.height())) pendingResize[key(task)] = expected
        changed(task)
    }

    fun restore(task: TaskInfo) {
        val entry = state.get(task.userId, task.taskId) ?: return
        val stable = stable(task)
        if (stable.isEmpty) return
        val restore = entry.restore
        val scale = min(restore.scale, min(stable.width().toFloat() / restore.logical.width(),
            stable.height().toFloat() / restore.logical.height()))
        val destination = FloatingMiniState.clamp(FloatingMiniState.sized(restore.logical, scale,
            restore.bounds.left, restore.bounds.top), stable)
        val current = task.configuration.windowConfiguration.bounds
        val drawingTask = if (current.width() != restore.logical.width() ||
            current.height() != restore.logical.height()) {
            val initialScale = entry.bounds.width().toFloat() / restore.logical.width()
            entry.scale = initialScale
            organizer.applyTransaction(WindowContainerTransaction().setDragResizing(task.token, true)
                .setBounds(task.token, restore.logical)
                .setFloatingWindowPresentation(task.token,
                    Point(entry.bounds.left, entry.bounds.top), initialScale))
            android.app.ActivityManager.RunningTaskInfo(
                task as android.app.ActivityManager.RunningTaskInfo).apply {
                configuration.windowConfiguration.setBounds(restore.logical)
                floatingWindowScale = initialScale
            }
        } else task
        animate(drawingTask, Rect(entry.bounds), entry.scale, destination, scale, restore.logical) {
            state.remove(task.userId, task.taskId)
        }
    }

    fun beginDrag(task: TaskInfo): Rect {
        cancel(task)
        clearDrag(task)
        dragFrames[key(task)] = Frame(task).also { SurfaceFrameRate.setInteracting(it, true) }
        val entry = state.get(task.userId, task.taskId)
        val bounds = Rect(task.presentationBounds)
        entry?.bounds?.set(bounds)
        organizer.applyTransaction(WindowContainerTransaction().setDragResizing(task.token, true))
        return bounds
    }

    fun move(task: TaskInfo, bounds: Rect) {
        val entry = state.get(task.userId, task.taskId) ?: return
        entry.bounds.set(bounds)
        val frame = dragFrames[key(task)] ?: return
        frame.bounds.set(bounds)
        frame.pending = true
        if (!frameScheduled) {
            frameScheduled = true
            choreographer.postFrameCallback(dragCallback)
        }
    }

    fun isAnimating(task: TaskInfo) = animations.containsKey(key(task))

    fun cancel(task: TaskInfo) {
        animations.remove(key(task))?.animator?.cancel()
    }

    fun remove(task: TaskInfo) {
        val entry = state.get(task.userId, task.taskId)
        val animating = isAnimating(task)
        cancel(task)
        clearDrag(task)
        state.remove(task.userId, task.taskId)
        pendingResize.remove(key(task))
        if (entry != null && organizer.getRunningTaskInfo(task.taskId)?.isFloatingWindow == true) {
            val wct = WindowContainerTransaction().setDragResizing(task.token, false)
            if (animating) wct.setFloatingWindowPresentation(task.token,
                Point(entry.bounds.left, entry.bounds.top), entry.scale)
            organizer.applyTransaction(wct)
        }
    }

    fun onTransitionReady(info: TransitionInfo, startT: SurfaceControl.Transaction,
        finishT: SurfaceControl.Transaction) {
        info.changes.forEach { change ->
            val task = change.taskInfo ?: return@forEach
            val operation = animations[key(task)] ?: return@forEach
            val entry = state.get(task.userId, task.taskId) ?: return@forEach
            val current = Rect(entry.bounds)
            cancel(task)
            if (task.isFloatingWindow && change.mode != android.view.WindowManager.TRANSIT_CLOSE) {
                complete(operation)
                setPresentation(task, operation.target, operation.scale, operation.logical)
                if (!change.startFloatingWindowBounds.isEmpty) {
                    change.setStartFloatingWindowBounds(operation.target)
                }
                com.android.wm.shell.transition.Transitions.applyFloatingWindowTransform(change, startT)
                com.android.wm.shell.transition.Transitions.applyFloatingWindowTransform(change, finishT)
                startT.setCornerRadius(change.leash, operation.radius / operation.scale)
                finishT.setCornerRadius(change.leash, operation.radius / operation.scale)
            } else {
                operation.finish()
                pendingResize.remove(key(task))
                // Exiting tasks keep their current visual start; unpin removes mini ownership.
                if (!change.startFloatingWindowBounds.isEmpty) {
                    change.setStartFloatingWindowBounds(current)
                }
            }
        }
    }

    fun onTaskInfoChanged(task: TaskInfo) {
        val entry = state.get(task.userId, task.taskId) ?: return
        if (isAnimating(task)) return
        val logical = task.configuration.windowConfiguration.bounds
        pendingResize[key(task)]?.let { expected ->
            if (expected != Pair(logical.width(), logical.height())) return
            pendingResize.remove(key(task))
        }
        val sameSize = kotlin.math.abs(logical.width() * entry.scale - entry.bounds.width()) <= 2 &&
            kotlin.math.abs(logical.height() * entry.scale - entry.bounds.height()) <= 2
        if (sameSize) return
        val stable = stable(task)
        val layout = displays.getDisplayLayout(task.displayId) ?: return
        if (stable.isEmpty) return
        val margin = (8 * density(task)).toInt()
        val corner = FloatingMiniState.corner(entry.bounds, stable, (48 * density(task)).toInt())
        val scale = min(entry.restore.scale, FloatingMiniState.scale(logical, stable,
            min(layout.width(), layout.height()), margin))
        val sized = FloatingMiniState.sized(logical, scale, entry.bounds.left, entry.bounds.top)
        val bounds = if (entry.edge != FloatingMiniState.Edge.NONE)
            FloatingMiniState.stash(sized, stable, entry.edge, (28 * density(task)).toInt()) else
            corner?.let { FloatingMiniState.snap(sized, stable, it, margin) }
                ?: FloatingMiniState.clamp(sized, stable, margin)
        entry.bounds.set(bounds)
        entry.scale = scale
        organizer.applyTransaction(WindowContainerTransaction()
            .setFloatingWindowPresentation(task.token, Point(bounds.left, bounds.top), scale))
    }

    fun onTransitionFinished(aborted: Boolean) {
        pendingResize.toMap().forEach { (key, expected) ->
            val task = organizer.getRunningTaskInfo(key.taskId) ?: return@forEach
            val logical = task.configuration.windowConfiguration.bounds
            if (aborted || expected == Pair(logical.width(), logical.height())) {
                pendingResize.remove(key)
                onTaskInfoChanged(task)
            }
        }
    }

    fun rotate(task: TaskInfo, from: Int, to: Int, wct: WindowContainerTransaction): Boolean {
        animations[key(task)]?.let { operation ->
            cancel(task)
            complete(operation)
            setPresentation(task, operation.target, operation.scale, operation.logical)
        }
        val entry = state.get(task.userId, task.taskId) ?: return false
        clearDrag(task)
        val context = displays.getDisplayContext(task.displayId) ?: return true
        val layout = displays.getDisplayLayout(task.displayId) ?: return true
        val old = DisplayLayout(layout).apply { rotateTo(context.resources, from) }
        val next = DisplayLayout(layout).apply { rotateTo(context.resources, to) }
        val oldStable = Rect().also(old::getStableBounds)
        val stable = Rect().also(next::getStableBounds)
        if (stable.isEmpty) return true
        val scale = FloatingMiniState.scale(task.configuration.windowConfiguration.bounds,
            stable, min(next.width(), next.height()), (8 * density(task)).toInt())
        val corner = FloatingMiniState.corner(entry.bounds, oldStable, (48 * density(task)).toInt())
        val refitted = FloatingMiniState.refit(entry.bounds, oldStable, stable)
        val sized = FloatingMiniState.sized(task.configuration.windowConfiguration.bounds, scale,
            refitted.left, refitted.top)
        val bounds = if (entry.edge != FloatingMiniState.Edge.NONE)
            FloatingMiniState.stash(sized, stable, entry.edge, (28 * density(task)).toInt()) else
            corner?.let { FloatingMiniState.snap(sized, stable, it, (8 * density(task)).toInt()) }
                ?: FloatingMiniState.clamp(sized, stable, (8 * density(task)).toInt())
        entry.restore.bounds.set(FloatingMiniState.refit(entry.restore.bounds, oldStable, stable))
        entry.bounds.set(bounds)
        entry.scale = scale
        wct.setDragResizing(task.token, false)
            .setFloatingWindowPresentation(task.token, Point(bounds.left, bounds.top), scale)
        return true
    }

    private fun setPresentation(task: TaskInfo, bounds: Rect, scale: Float, logical: Rect?) {
        task.floatingWindowScale = scale
        task.positionInParent = Point(bounds.left, bounds.top)
        task.floatingWindowPosition.set(bounds.left, bounds.top)
        logical?.let { task.configuration.windowConfiguration.setBounds(it) }
    }

    private fun updatedTask(task: TaskInfo, bounds: Rect, scale: Float, logical: Rect?) =
        android.app.ActivityManager.RunningTaskInfo(
            task as android.app.ActivityManager.RunningTaskInfo).apply {
            setPresentation(this, bounds, scale, logical)
        }

    private fun complete(operation: Operation) {
        val task = operation.task
        pendingResize.remove(key(task))
        state.get(task.userId, task.taskId)?.let {
            it.bounds.set(operation.target)
            it.scale = operation.scale
        }
        operation.finish()
        val wct = WindowContainerTransaction().setDragResizing(task.token, false)
            .setFloatingWindowPresentation(task.token,
                Point(operation.target.left, operation.target.top), operation.scale)
        operation.logical?.let { wct.setBounds(task.token, it).reorder(task.token, true) }
        organizer.applyTransaction(wct)
        changed(updatedTask(task, operation.target, operation.scale, operation.logical))
    }

    private fun animate(task: TaskInfo, from: Rect, fromScale: Float, to: Rect, toScale: Float,
        logical: Rect? = null, finish: () -> Unit = {}) {
        cancel(task)
        clearDrag(task)
        val animation = animators()
        val frame = Frame(task)
        val operation = Operation(animation, task, Rect(to), toScale, logical?.let(::Rect), frame.radius, finish)
        animations[key(task)] = operation
        SurfaceFrameRate.setInteracting(operation, true)
        organizer.applyTransaction(WindowContainerTransaction().setDragResizing(task.token, true))
        var cancelled = false
        animation.duration = 300
        animation.interpolator = Interpolators.FAST_OUT_SLOW_IN
        animation.addUpdateListener {
            val p = it.animatedValue as Float
            val scale = fromScale + (toScale - fromScale) * p
            val left = (from.left + (to.left - from.left) * p).toInt()
            val top = (from.top + (to.top - from.top) * p).toInt()
            frame.bounds.set(left, top,
                left + (from.width() + (to.width() - from.width()) * p).toInt(),
                top + (from.height() + (to.height() - from.height()) * p).toInt())
            state.get(task.userId, task.taskId)?.let { entry ->
                entry.bounds.set(frame.bounds)
                entry.scale = scale
            }
            frame.draw()
        }
        animation.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (animations[key(task)] === operation) animations.remove(key(task))
                frame.close()
                if (!cancelled) complete(operation)
                SurfaceFrameRate.setInteracting(operation, false)
            }
        })
        animation.start()
    }
}
