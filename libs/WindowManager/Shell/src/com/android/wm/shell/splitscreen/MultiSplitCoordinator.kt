// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import com.android.wm.shell.transition.OneShotRemoteHandler
import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Insets
import android.graphics.Rect
import android.os.Bundle
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.io.PrintWriter
import android.window.RemoteTransition
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.view.InsetsState
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_CLOSE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_BACK
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayImeController
import com.android.wm.shell.common.DisplayInsetsController
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.common.split.OffscreenTouchZone
import com.android.wm.shell.common.split.SplitLayout
import com.android.wm.shell.common.split.SplitWindowManager
import com.android.wm.shell.shared.split.SplitScreenConstants.FLAG_IS_DIVIDER_BAR
import com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT
import com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT
import com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_UNDEFINED
import com.android.wm.shell.splitscreen.MultiSplitLayout.DividerId
import com.android.wm.shell.splitscreen.MultiSplitLayout.Leaf
import com.android.wm.shell.splitscreen.MultiSplitLayout.LeafId
import com.android.wm.shell.splitscreen.MultiSplitLayout.Node
import com.android.wm.shell.splitscreen.MultiSplitLayout.Orientation
import com.android.wm.shell.splitscreen.MultiSplitLayout.Split
import com.android.wm.shell.splitscreen.SplitScreenController.AppDragSlot
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.shared.TransitionUtil

/** A display-local live geometry session over sibling stage roots. All calls run on Shell main. */
class MultiSplitCoordinator(
    private val host: Host,
    private val organizer: ShellTaskOrganizer,
    private val syncQueue: SyncTransactionQueue,
    private val transitions: Transitions,
    private val displays: DisplayController,
    private val ime: DisplayImeController,
    private val insets: DisplayInsetsController,
    private val executor: ShellExecutor,
) : Transitions.TransitionHandler, Transitions.TransitionObserver,
    DisplayInsetsController.OnInsetsChangedListener {
    interface Host {
        fun groups(): MultiSplitGroupRepository?
        fun rootInfo(): RunningTaskInfo?
        fun rootLeash(): SurfaceControl?
        fun pairStages(): List<StageTaskListener>
        fun spareStages(callbacks: StageTaskListener.StageListenerCallbacks): List<StageTaskListener>
        fun pairLayout(): SplitLayout
        fun capacity(): Int
        fun canEnter(): Boolean
        fun newDivider(handler: SplitLayout.SplitLayoutHandler,
            parent: SplitWindowManager.ParentContainerCallbacks): SplitLayout
        fun started()
        fun ended(wct: WindowContainerTransaction)
        fun changed()
        fun returnToPair(survivors: List<@JvmSuppressWildcards StageTaskListener>, ratio: Float,
            wct: WindowContainerTransaction): Boolean = false
    }

    private val groups = host.groups() ?: MultiSplitGroupRepository(Runnable {})
    private val restoreRemotes = mutableMapOf<IBinder, RemoteTransition>()
    private val recentsTransitions = mutableSetOf<IBinder>()
    private val readyInfos = mutableMapOf<IBinder, TransitionInfo>()
    private var restoring = false
    private var recentsAnimating = false
    private var projectedRepresentatives = emptyList<Int>()
    private val spare = mutableListOf<StageTaskListener>()
    private val attached = mutableSetOf<StageTaskListener>()
    private val stages = linkedMapOf<LeafId, StageTaskListener>()
    private val pool = mutableListOf<StageTaskListener>()
    private val dividers = linkedMapOf<DividerId, SplitLayout>()
    private val dividerHandlers = mutableMapOf<DividerId, DividerHandler>()
    private var layout: MultiSplitLayout? = null
    private var stripZone: OffscreenTouchZone? = null
    private var stripZoneLeaf: LeafId? = null
    private var stripZoneStage: StageTaskListener? = null
    private var revision = 0
    private var nextLeaf = 2
    private var nextDivider = 1
    private var pending: IBinder? = null
    private var reconcileScheduled = false
    private var interacting = false
    private var visible = true
    private var keyguard = false
    private var imeTop: Int? = null
    private var listening = false
    private val runningTransitions = mutableSetOf<IBinder>()
    private val insetState = InsetsState()
    private var dragStart: MultiSplitLayout? = null
    private var dragDivider: DividerId? = null
    private var lastBelowRejection = "Not queried"
    private var lastInsertRejection = "Not attempted"
    private class PendingInsertion(
        val leaf: LeafId,
        val stage: StageTaskListener,
        val before: MultiSplitLayout,
        val beforeStages: Map<LeafId, StageTaskListener>,
        val previousTasks: Set<Int>,
    ) {
        var transition: IBinder? = null
        var finished = false
        var taskId: Int? = null
        var failure: String? = null
        var deadline = 0L
        lateinit var timeout: Runnable
        lateinit var probe: Runnable
    }
    private var insertion: PendingInsertion? = null
    private val retiredStages = mutableSetOf<StageTaskListener>()
    private var probingInsertion = false
    private val unreportedTasks = mutableMapOf<StageTaskListener, MutableSet<Int>>()

    private companion object {
        // The grace period covers delayed organizer appearance, not animation timing.
        const val INSERTION_GRACE_MS = 5_000L
        const val INSERTION_TRANSITION_TIMEOUT_MS = 20_000L
        const val INSERTION_PROBE_MS = 100L
    }

    private fun taskMinimumsFit(model: MultiSplitLayout): Boolean {
        val rectangles = bounds(model)?.leaves ?: return false
        return model.getLeaves().all { leaf ->
            val task = organizer.getRunningTaskInfo(leaf.taskId) ?: stages.values.firstNotNullOfOrNull {
                it.mChildrenTaskInfo[leaf.taskId]
            }
            val rect = rectangles.getValue(leaf.id)
            task == null || rect.width() >= task.minWidth && rect.height() >= task.minHeight
        }
    }


    private fun bounds(model: MultiSplitLayout): MultiSplitLayout.Bounds? {
        return model.calculateBounds(displayBounds(), layoutInsets(), host.pairLayout().dividerSize)
    }

    val isActive: Boolean get() = layout != null

    /** Removes only the selected leaf in the same transaction that reparents it to floating. */
    fun prepareLeafToFloating(taskId: Int, wct: WindowContainerTransaction): Boolean {
        if (!isActive) return false
        val id = leafOfTask(taskId) ?: return false
        removeLeaf(id, wct, taskId)
        setInteractive(false)
        return true
    }

    private fun displayBounds(): Rect = host.pairLayout().rootBounds

    private fun layoutInsets(): Insets {
        // App roots reach the display edges; each app receives its own system bar insets.
        val bottom = imeTop?.let { maxOf(0, displayBounds().bottom - it) } ?: 0
        return Insets.of(0, 0, 0, bottom)
    }

    private fun leafOfTask(taskId: Int): LeafId? = stages.entries.firstOrNull {
        it.value.containsTask(taskId)
    }?.key

    private fun removeLeaf(id: LeafId, wct: WindowContainerTransaction, skipTask: Int = -1) {
        cancelDrag()
        val model = layout ?: return
        val removed = stages.remove(id) ?: return
        groups.remove(removed.topChildTaskId)
        removed.doForAllChildTaskInfos { task ->
            if (task.taskId != skipTask) wct.reparent(task.token, null, false)
        }
        wct.clearAdjacentRoots(removed.mRootTaskInfo.token)
        val collapsed = model.remove(id)
        if (collapsed == null) {
            exit(stages.values.firstOrNull()?.topChildTaskId ?: -1, wct)
        } else {
            layout = collapsed
            revision++
            if (!returnPair(wct)) applyTasks(wct)
        }
    }

    private fun setInteractive(enabled: Boolean) {
        if (!enabled) clearStripZone()
        dividers.forEach { (id, divider) ->
            val keepDrag = interacting && id == dragDivider && pending == null &&
                runningTransitions.isEmpty()
            divider.setDividerInteractive((enabled || keepDrag) && !keyguard && imeTop == null,
                false, "multi split")
        }
    }

    private fun returnPair(wct: WindowContainerTransaction): Boolean {
        val model = layout ?: return false
        if (model.getLeaves().size != 2) return false
        val survivors = model.getLeaves().map { stages.getValue(it.id) }
        val ratio = if (model.strip != null) model.root.ratio else 0.5f
        if (!host.returnToPair(survivors, ratio, wct)) return false
        model.getLeaves().forEach { groups.remove(it.taskId) }
        layout = null
        stages.clear()
        disposeDividers()
        stopListening()
        host.changed()
        return true
    }

    private fun clearStripZone() {
        if (stripZone == null) return
        val transaction = SurfaceControl.Transaction()
        stripZone?.release(transaction)
        transaction.apply()
        transaction.close()
        stripZone = null
        stripZoneLeaf = null
        stripZoneStage = null
    }

    private fun cancelDrag() {
        val original = dragStart ?: layout
        if (dragStart != null || dividers.values.any { it.isCurrentlyDividerFlinging }) {
            // Retire callbacks before releasing windows: animator cancellation can call them back.
            disposeDividers()
            layout = original
        }
        dragStart = null
        dragDivider = null
        interacting = false
    }

    private inner class DividerHandler(val id: DividerId) : SplitLayout.SplitLayoutHandler {
        var retired = false

        override fun onDividerInteractionChanged(active: Boolean) {
            if (retired) return
            if (active && isBusy) {
                dividers[id]?.setDividerInteractive(false, false, "multi split busy")
                return
            }
            interacting = active
            if (active) {
                clearStripZone()
                dragStart = layout
                dragDivider = id
                setInteractive(false)
            } else if (dragStart != null && dividers[id]?.isCurrentlyDividerFlinging != true) {
                layout = dragStart
                dragStart = null
                syncDividers()
                syncQueue.runInSync { applySurfaceChanges(it) }
            }
        }
        private fun resized(split: SplitLayout, commit: Boolean) {
            if (retired || pending != null || runningTransitions.isNotEmpty()) return
            val original = dragStart ?: layout ?: return
            var candidate = original.resize(id, split.branchRatio) ?: return
            if (id == original.root.id) {
                val first = original.root.first as? Leaf
                val second = original.root.second as? Leaf
                val peek = when {
                    !com.android.wm.shell.Flags.enableFlexibleTwoAppSplit() || original.isThreeColumns -> null
                    first != null && split.branchRatio < 0.2f -> first.id
                    second != null && split.branchRatio > 0.8f -> second.id
                    else -> null
                }
                candidate = candidate.withRootRatio(split.branchRatio, peek)
            }
            val rectangles = bounds(candidate) ?: return
            val resources = displays.getDisplayContext(host.rootInfo()!!.displayId)!!.resources
            if (!LayoutPolicy().isFeasible(rectangles, LayoutPolicy().getMinimumLeafSize(resources)) ||
                !taskMinimumsFit(candidate)) {
                if (commit) {
                    layout = original
                    dragStart = null
                    interacting = false
                    syncDividers()
                    syncQueue.runInSync { applySurfaceChanges(it) }
                }
                return
            }
            layout = candidate
            // Updating the other branch windows retains their independent ratios.
            syncDividers()
            syncQueue.runInSync { applySurfaceChanges(it) }
            if (commit) {
                interacting = false
                dragStart = null
                revision++
                val wct = WindowContainerTransaction()
                applyTasks(wct)
                pending = transitions.startTransition(TRANSIT_CHANGE, wct, this@MultiSplitCoordinator)
                setInteractive(false)
            }
        }
        override fun onLayoutSizeChanging(split: SplitLayout, x: Int, y: Int, parallax: Boolean) {
            resized(split, false)
        }
        override fun onLayoutSizeChanged(split: SplitLayout) { resized(split, true) }
        override fun onLayoutPositionChanging(split: SplitLayout) {
            if (retired) return
            syncQueue.runInSync { applySurfaceChanges(it) }
        }
        override fun onSnappedToDismiss(end: Boolean, reason: Int, wct: WindowContainerTransaction) {
            if (retired) return
            cancelDrag()
            syncDividers()
            syncQueue.runInSync { applySurfaceChanges(it) }
        }
        var menuShowing = false
        override fun onDividerHandleMenuChanged(showing: Boolean) {
            if (retired) return
            menuShowing = showing
            syncQueue.runInSync { applySurfaceChanges(it) }
        }
        override fun canRotateThreeAppShape(): Boolean = !retired &&
            this@MultiSplitCoordinator.canRotateThreeAppShape() &&
            (layout?.isThreeColumns == true || id != layout?.root?.id)
        override fun onRotateThreeAppShape() {
            if (canRotateThreeAppShape()) this@MultiSplitCoordinator.rotateThreeAppShape()
        }
        override fun onDoubleTappedDivider() { onDividerHandleTapped(0) }
        override fun onDividerHandleTapped(index: Int) {
            if (retired) return
            val model = layout ?: return
            if (!model.isThreeColumns && model.getLeaves().size == 3 && id == model.root.id) {
                mirror()
                return
            }
            val calculated = bounds(model) ?: return
            val handle = model.dividerHandles(calculated)[id]?.getOrNull(index) ?: return
            swapLeaves(handle.first.value, handle.second.value)
        }
        override fun setLayoutOffsetTarget(x: Int, y: Int, split: SplitLayout) {}
        override fun setExcludeImeInsets(exclude: Boolean) {}
        override fun getSplitItemPosition(token: WindowContainerToken): Int = SPLIT_POSITION_UNDEFINED
    }

    fun exit(taskId: Int, wct: WindowContainerTransaction) {
        if (animationBranches.isNotEmpty()) {
            SurfaceControl.Transaction().use { restoreAnimationBranches(it); it.apply() }
        }
        insertion?.let { rollbackInsertion(it, "Group exited", wct) }
        publishGroup()
        stages.values.toList().forEach { stage ->
            wct.clearAdjacentRoots(stage.mRootTaskInfo.token)
            stage.doForAllChildTaskInfos { task ->
                wct.reparent(task.token, null, task.taskId == taskId)
                    .setWindowingMode(task.token, WINDOWING_MODE_FULLSCREEN)
                    .setBounds(task.token, Rect())
            }
        }
        host.rootInfo()?.let { wct.reorder(it.token, false).setForceTranslucent(it.token, true) }
        host.ended(wct)
        layout = null
        stages.clear()
        revision++
        disposeDividers()
        stopListening()
        host.changed()
    }

    private fun applyTasks(wct: WindowContainerTransaction, updateHierarchy: Boolean = true) {
        val model = layout ?: return
        val calculated = bounds(model) ?: return
        val tokens = stages.values.map { it.mRootTaskInfo.token }.toTypedArray()
        stages.forEach { (id, stage) ->
            if (updateHierarchy) {
                wct.reparent(stage.mRootTaskInfo.token, host.rootInfo()!!.token, true)
                    .setHidden(stage.mRootTaskInfo.token, false)
                    .setForceTranslucent(stage.mRootTaskInfo.token, false)
            }
            wct.setBounds(stage.mRootTaskInfo.token, calculated.leaves.getValue(id))
                .setAppBounds(stage.mRootTaskInfo.token, null)
                .setSmallestScreenWidthDp(stage.mRootTaskInfo.token, 0)
                .setScreenSizeDp(stage.mRootTaskInfo.token, 0, 0)
        }
        if (!updateHierarchy) return
        if (tokens.size >= 2) wct.setAdjacentRoots(*tokens)
        host.rootInfo()?.let {
            wct.setReparentLeafTaskIfRelaunch(it.token, !visible).setForceTranslucent(it.token, false)
        }
    }

    fun occupied(displayId: Int): Int = if (host.rootInfo()?.displayId == displayId && isVisible) {
        stages.values.count { stage -> stage.mChildrenTaskInfo.let { children ->
            (0 until children.size()).any { children.valueAt(it).let { task ->
                task.isVisible && task.isVisibleRequested && !task.isFloatingWindow &&
                    task.activityType == android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
            } }
        } }
    } else 0

    private fun rollbackInsertion(waiting: PendingInsertion, reason: String,
        wct: WindowContainerTransaction) {
        clearInsertion(waiting)
        waiting.taskId?.let { groups.remove(it) }
        retiredStages.add(waiting.stage)
        resetPreparedStage(waiting.stage, wct)
        layout = waiting.before
        stages.clear()
        stages.putAll(waiting.beforeStages)
        revision++
        lastInsertRejection = "Rolled back: $reason"
        if (!returnPair(wct)) applyTasks(wct)
    }

    fun swapLeaves(first: Int, second: Int): Boolean {
        if (isBusy || !isVisible) return false
        val a = LeafId(first)
        val b = LeafId(second)
        val model = layout ?: return false
        val swapped = model.swap(a, b) ?: return false
        if (!taskMinimumsFit(swapped)) return false
        val stageA = stages.getValue(a)
        stages[a] = stages.getValue(b)
        stages[b] = stageA
        layout = swapped
        revision++
        val wct = WindowContainerTransaction()
        applyTasks(wct)
        pending = transitions.startTransition(TRANSIT_CHANGE, wct, this)
        setInteractive(false)
        return true
    }

    fun mirror(): Boolean = layout?.mirror()?.let { changeShape(it) } ?: false

    fun applySurfaceChanges(t: SurfaceControl.Transaction) {
        if (recentsAnimating) return
        val model = layout ?: return
        val calculated = bounds(model) ?: return
        val root = displayBounds()
        stages.forEach { (id, stage) ->
            val rect = calculated.leaves.getValue(id)
            stage.mRootLeash?.takeIf { it.isValid }?.let { leash ->
                t.setPosition(leash, rect.left - root.left.toFloat(), rect.top - root.top.toFloat())
                    .setWindowCrop(leash, rect.width(), rect.height())
                if (visible && !keyguard) t.show(leash).setAlpha(leash, 1f) else t.hide(leash)
            }
            stage.mDimLayer?.takeIf { it.isValid }?.let { t.hide(it) }
        }
        dividers.forEach { (id, split) ->
            val rect = Rect()
            split.getDividerLeashBounds(rect)
            val branch = split.rootBounds
            split.dividerLeash?.takeIf { it.isValid }?.let {
                val crop = split.multiSplitDividerCrop
                t.setPosition(it, rect.left + branch.left - root.left.toFloat(),
                    rect.top + branch.top - root.top.toFloat()).setWindowCrop(it, crop)
                    .setLayer(it, if (dividerHandlers[id]?.menuShowing == true) Int.MAX_VALUE
                        else Int.MAX_VALUE - 1)
                if (visible && !keyguard) t.show(it).setAlpha(it, 1f) else t.hide(it)
            }
        }
    }

    private fun stopListening() {
        if (!listening) return
        host.rootInfo()?.let { insets.removeInsetsChangedListener(it.displayId, this) }
        ime.removePositionProcessor(imeProcessor)
        imeTop = null
        listening = false
    }

    private val animationBranches = mutableListOf<SurfaceControl>()

    /** Publishes only complete snapshots; organizer launch callbacks replace placeholder IDs. */
    fun publishGroup(): MultiSplitGroupRepository.Group? {
        var model = layout ?: return null
        if (insertion?.taskId == null && insertion != null) return null
        if (stages.values.any { it.childCount == 0 }) return null
        val tasks = stages.map { (id, stage) ->
            val task = organizer.getRunningTaskInfo(stage.topChildTaskId)
                ?: stage.mChildrenTaskInfo[stage.topChildTaskId] ?: return null
            model = model.assign(id, task.taskId) ?: return null
            task
        }
        layout = model
        val group = groups.save(model, stages.mapValues { it.value.mRootTaskInfo.taskId },
            tasks, displayBounds(), host.pairLayout().dividerSize)
        return group
    }

    fun restoreAnimationBranches(t: SurfaceControl.Transaction) {
        if (animationBranches.isEmpty()) return
        animationParents.forEach { (leash, destination) ->
            if (leash.isValid && destination.first.isValid) {
                val rect = destination.second
                t.reparent(leash, destination.first).setPosition(leash,
                    rect.left.toFloat(), rect.top.toFloat()).setMatrix(leash, 1f, 0f, 0f, 1f)
                    .setWindowCrop(leash, rect.width(), rect.height()).setAlpha(leash, 1f)
            }
        }
        animationParents.clear()
        reparentDividers(t)
        stages.values.forEach { it.restoreChildSurfaces(t) }
        animationBranches.asReversed().forEach { leash ->
            if (leash.isValid) t.remove(leash)
            leash.release()
        }
        animationBranches.clear()
        animationChanges.forEach { it.leash.release(); it.snapshot?.release() }
        animationChanges.clear()
    }

    private fun disposeDividers() {
        setRecentsAnimating(false)
        clearStripZone()
        dividerHandlers.values.forEach { it.retired = true }
        dividerHandlers.clear()
        dividers.values.forEach { it.release() }
        dividers.clear()
        interacting = false
        dragStart = null
    }

    private fun syncDividers(t: SurfaceControl.Transaction? = null) {
        val model = layout ?: return
        val calculated = bounds(model) ?: return
        val branches = linkedMapOf<DividerId, Pair<Split, Rect>>()
        fun walk(node: Node): Rect = when (node) {
            is Leaf -> Rect(calculated.leaves.getValue(node.id))
            is Split -> {
                // Keep each branch rectangle independent when expanding its parent.
                val rect = Rect(walk(node.first)).apply { union(walk(node.second)) }
                if (node.id == model.root.id) {
                    rect.set(displayBounds())
                    val padding = layoutInsets()
                    rect.inset(padding.left, padding.top, padding.right, padding.bottom)
                }
                branches[node.id] = node to rect
                rect
            }
        }
        walk(model.root)
        dividers.keys.toList().filter { it !in branches }.forEach {
            dividerHandlers.remove(it)?.retired = true
            dividers.remove(it)?.release(t)
        }
        branches.forEach { (id, branch) ->
            val (node, rect) = branch
            var created = false
            val split = dividers.getOrPut(id) {
                created = true
                val handler = DividerHandler(id)
                dividerHandlers[id] = handler
                host.newDivider(handler, object : SplitWindowManager.ParentContainerCallbacks {
                    override fun attachToParentSurface(builder: SurfaceControl.Builder) {
                        builder.setParent(host.rootLeash())
                    }
                    override fun onLeashReady(leash: SurfaceControl) {
                        executor.execute { if (isActive) syncQueue.runInSync { applySurfaceChanges(it) } }
                    }
                    override fun inflateOnStageRoot(touchZone: OffscreenTouchZone) {}
                    override fun onSplitLayoutAnimating(animating: Boolean) {
                        if (!animating) executor.execute { setInteractive(!isBusy) }
                    }
                })
            }
            val changed = split.rootBounds != rect || split.isLeftRightSplit !=
                (node.orientation == Orientation.LEFT_RIGHT)
            if (!interacting || id != dragDivider) {
                val singleSide = if (!model.isThreeColumns && node.id == model.root.id && model.getLeaves().size == 3) {
                    if (node.first is Leaf) 0 else 1
                } else -1
                val ratio = if (model.isThreeColumns) {
                    (calculated.dividers.getValue(id).left - rect.left).toFloat() /
                        (rect.width() - host.pairLayout().dividerSize)
                } else node.ratio
                split.setMultiSplitDivider(rect, node.orientation == Orientation.LEFT_RIGHT,
                    ratio, singleSide)
            }
            if (created) {
                split.init()
                split.insetsChanged(insetState)
            } else if (changed) split.update(t, false)
        }
        val handles = model.dividerHandles(calculated)
        branches.forEach { (id, branch) ->
            val (node, rect) = branch
            val origin = if (node.orientation == Orientation.LEFT_RIGHT) rect.top else rect.left
            dividers.getValue(id).setDividerHandlePositions(
                handles.getValue(id).map { it.position - origin }.toIntArray())
        }
        syncStripZone()
        setInteractive(!isBusy)
    }

    val isBusy: Boolean get() = keyguard || insertion != null || pending != null ||
        runningTransitions.isNotEmpty() || interacting ||
        dividers.values.any { it.isCurrentlyDividerFlinging } ||
        (isActive && stages.values.any { it.childCount == 0 })

    val isVisible: Boolean get() = isActive && visible &&
        stages.values.any { it.mRootTaskInfo?.isVisible == true }

    fun rotateThreeAppShape(): Boolean {
        if (!canRotateThreeAppShape()) return false
        return layout?.rotateThreeAppShape()?.let { changeShape(it) } ?: false
    }

    private fun clearInsertion(waiting: PendingInsertion) {
        executor.removeCallbacks(waiting.timeout)
        executor.removeCallbacks(waiting.probe)
        if (insertion === waiting) insertion = null
    }

    /** Evicts even children whose own organizer callbacks have not been sent yet. */
    private fun resetPreparedStage(stage: StageTaskListener, wct: WindowContainerTransaction) {
        if (!stage.mHasRootTask) return
        val root = stage.mRootTaskInfo.token
        unreportedTasks.remove(stage)
        wct.reparentTasks(root, null, null, null, false)
            .clearAdjacentRoots(root).setBounds(root, Rect()).setAppBounds(root, null)
            .setSmallestScreenWidthDp(root, 0).setScreenSizeDp(root, 0, 0)
            .setHidden(root, true).setForceTranslucent(root, true)
        for (i in stage.mChildrenTaskInfo.size() - 1 downTo 0) {
            val task = stage.mChildrenTaskInfo.valueAt(i)
            if (organizer.getRunningTaskInfo(task.taskId) == null) {
                stage.mChildrenTaskInfo.removeAt(i)
            }
        }
    }

    private fun changeShape(changed: MultiSplitLayout): Boolean {
        if (isBusy || !isVisible || imeTop != null || !taskMinimumsFit(changed)) return false
        val calculated = bounds(changed) ?: return false
        val resources = displays.getDisplayContext(host.rootInfo()!!.displayId)?.resources ?: return false
        if (!LayoutPolicy().isFeasible(calculated, LayoutPolicy().getMinimumLeafSize(resources)) ||
            changed.isThreeColumns && calculated.leaves.values.any { it.width() >= it.height() }) return false
        layout = changed
        revision++
        disposeDividers()
        val wct = WindowContainerTransaction()
        applyTasks(wct)
        pending = transitions.startTransition(TRANSIT_CHANGE, wct, this)
        setInteractive(false)
        return true
    }

    private val imeProcessor = object : DisplayImeController.ImePositionProcessor {
        override fun onImeStartPositioning(displayId: Int, hiddenTop: Int, shownTop: Int,
            showing: Boolean, floating: Boolean, t: SurfaceControl.Transaction): Int {
            if (!isActive || host.rootInfo()?.displayId != displayId) return 0
            val target = organizer.getImeLayeringTarget(displayId)
            val owned = target != null && stages.values.any { it.containsToken(target) }
            // A Shell panel's keyboard must not resize the apps behind its destination outline.
            if (showing && !owned) return 0
            imeTop = if (showing && !floating) shownTop else null
            val wct = WindowContainerTransaction()
            applyTasks(wct)
            organizer.applyTransaction(wct)
            syncDividers(t)
            applySurfaceChanges(t)
            setInteractive(!isBusy)
            return 0
        }
        override fun onImeControlTargetChanged(displayId: Int, controlling: Boolean) {
            if (!controlling && imeTop != null && host.rootInfo()?.displayId == displayId) {
                imeTop = null
                insetsChanged(insetState)
            }
        }
    }

    private val animationChanges = mutableListOf<TransitionInfo.Change>()

    private val animationParents = linkedMapOf<SurfaceControl, Pair<SurfaceControl, Rect>>()

    private fun setRecentsAnimating(animating: Boolean) {
        recentsAnimating = animating
        dividers.values.forEach { it.setRecentsAnimating(animating) }
    }

    private fun reparentDividers(t: SurfaceControl.Transaction) {
        val parent = host.rootLeash() ?: return
        dividers.values.forEach { split ->
            split.dividerLeash?.takeIf { it.isValid }?.let { t.reparent(it, parent) }
        }
    }

    private fun syncStripZone() {
        val model = layout
        val leaf = model?.strip
        if (leaf == null || model.getLeaves().size != 3 || isBusy || recentsAnimating || keyguard || !visible || imeTop != null) {
            clearStripZone()
            return
        }
        val stage = stages[leaf] ?: return
        if (stripZoneLeaf == leaf && stripZoneStage === stage) return
        clearStripZone()
        val leash = stage.mRootLeash ?: return
        val first = (model.root.first as? Leaf)?.id == leaf
        val zone = OffscreenTouchZone(first) { expandStrip() }
        zone.inflate(displays.getDisplayContext(host.rootInfo()!!.displayId)!!,
            leash, stage.mRootTaskInfo)
        stripZone = zone
        stripZoneLeaf = leaf
        stripZoneStage = stage
    }

    override fun insetsChanged(state: InsetsState) {
        insetState.set(state)
        if (!isActive) return
        dividers.values.forEach { it.insetsChanged(state) }
        transitions.runOnIdle {
            if (isActive) {
                val wct = WindowContainerTransaction()
                applyTasks(wct)
                syncQueue.queue(wct)
                syncDividers()
                syncQueue.runInSync { applySurfaceChanges(it) }
            }
        }
    }

    /** A strip tap expands its app while retaining the other half's normal minimum. */
    fun expandStrip(): Boolean {
        val current = layout ?: return false
        val leaf = current.strip ?: return false
        if (current.getLeaves().size != 3 || !isVisible || isBusy || keyguard || imeTop != null) return false
        val rootSplit = dividers[current.root.id] ?: return false
        val first = (current.root.first as? Leaf)?.id == leaf
        val endpoint = if (first) rootSplit.lastNonDismissRatio else rootSplit.firstNonDismissRatio
        if (endpoint <= 0f || endpoint >= 1f) return false
        fun feasible(ratio: Float): Boolean {
            val candidate = current.withRootRatio(ratio)
            val rectangles = bounds(candidate) ?: return false
            val resources = displays.getDisplayContext(host.rootInfo()!!.displayId)!!.resources
            return LayoutPolicy().isFeasible(rectangles, LayoutPolicy().getMinimumLeafSize(resources)) &&
                fitsExistingTasks(candidate)
        }
        var ratio = endpoint
        if (!feasible(ratio)) {
            if (!feasible(0.5f)) return false
            var valid = 0.5f
            var invalid = endpoint
            repeat(24) {
                val middle = (valid + invalid) / 2f
                if (feasible(middle)) valid = middle else invalid = middle
            }
            ratio = valid
        }
        layout = current.withRootRatio(ratio)
        revision++
        clearStripZone()
        val wct = WindowContainerTransaction()
        applyTasks(wct)
        pending = transitions.startTransition(TRANSIT_CHANGE, wct, this)
        setInteractive(false)
        return true
    }

    private fun fitsExistingTasks(model: MultiSplitLayout): Boolean =
        existingTaskRejection(model) == null

    private fun existingTaskRejection(model: MultiSplitLayout): String? {
        val rectangles = bounds(model)?.leaves ?: return "Layout bounds"
        model.getLeaves().forEach { leaf ->
            val task = organizer.getRunningTaskInfo(leaf.taskId) ?: return@forEach
            val rect = rectangles.getValue(leaf.id)
            if (rect.width() < task.minWidth || rect.height() < task.minHeight) {
                return "Task ${leaf.taskId} min=${task.minWidth}x${task.minHeight} bounds=$rect"
            }
        }
        return null
    }

    private fun candidate(model: MultiSplitLayout, leaf: LeafId, first: Boolean,
        columns: Boolean = false): MultiSplitLayout? {
        if (columns && !canInsertColumn(model)) return null
        val source = insertionModel(model, leaf) ?: return null
        val axis = source.insertionAxis(leaf) ?: return null
        val minimum = LayoutPolicy().getMinimumLeafSize(displays.getDisplayContext(
            host.rootInfo()!!.displayId)!!.resources)
        val inserted = source.insert(leaf, Leaf(LeafId(nextLeaf), -100 - nextLeaf), DividerId(nextDivider),
            axis, 0.5f, displayBounds(), layoutInsets(), host.pairLayout().dividerSize,
            if (columns) 0 else minimum, first) ?: return null
        if (!columns) return inserted
        val proposed = inserted.rotateThreeAppShape() ?: return null
        val calculated = bounds(proposed) ?: return null
        return proposed.takeIf { LayoutPolicy().isFeasible(calculated, minimum) &&
            calculated.leaves.values.all { it.width() < it.height() } }
    }

    override fun startAnimation(transition: IBinder, info: TransitionInfo,
        start: SurfaceControl.Transaction, finish: SurfaceControl.Transaction,
        callback: Transitions.TransitionFinishCallback): Boolean {
        if (transition != pending) return false
        if (restoring) {
            setRecentsAnimating(false)
            reparentDividers(start)
        }
        info.changes.forEach { change ->
            if (TransitionUtil.isClosingType(change.mode)) {
                start.hide(change.leash)
            } else {
                start.show(change.leash).setAlpha(change.leash, 1f)
                val offset = change.endRelOffset
                start.setPosition(change.leash, offset.x.toFloat(), offset.y.toFloat())
                start.setWindowCrop(change.leash, change.endAbsBounds.width(),
                    change.endAbsBounds.height())
            }
        }
        syncDividers(start)
        applySurfaceChanges(start)
        applySurfaceChanges(finish)
        val remote = restoreRemotes.remove(transition)
        if (remote != null) {
            val remoteInfo = info.localRemoteCopy()
            restoreInfos[transition] = remoteInfo
            if (restoring) projectAnimationBranches(remoteInfo, start)
            val handler = OneShotRemoteHandler(executor, transitions.leashManager, remote)
            restoreHandlers[transition] = handler
            handler.setTransition(transition)
            return handler.startAnimation(transition, remoteInfo, start, finish) { wct ->
                restoreAnimationBranches(finish)
                applySurfaceChanges(finish)
                restoreHandlers.remove(transition)
                restoreInfos.remove(transition)?.releaseAllSurfaces()
                callback.onTransitionFinished(wct)
            }
        }
        start.apply()
        executor.execute { callback.onTransitionFinished(null) }
        return true
    }

    private val restoreHandlers = mutableMapOf<IBinder, OneShotRemoteHandler>()

    private val restoreInfos = mutableMapOf<IBinder, TransitionInfo>()

    /** Keeps nested leaves inside the two branch targets understood by launcher animations. */
    fun projectAnimationBranches(info: TransitionInfo, t: SurfaceControl.Transaction) {
        val group = publishGroup() ?: return
        val model = layout ?: return
        val calculated = bounds(model) ?: return
        val root = host.rootInfo() ?: return
        val rootLeash = host.rootLeash() ?: return
        val representatives = listOf(group.firstRepresentative, group.secondRepresentative)
        val tasks = representatives.map { organizer.getRunningTaskInfo(it) ?: return }
        val taskIds = model.getLeaves().map { it.taskId }.toSet()
        val stageIds = stages.values.map { it.mRootTaskInfo.taskId }.toSet()
        if (info.changes.none { it.taskInfo?.taskId in taskIds + stageIds }) return
        restoreAnimationBranches(t)
        val original = info.changes.toList()
        val parentLeash = original.firstOrNull { it.taskInfo?.taskId == root.taskId }?.leash
            ?: rootLeash
        val display = displayBounds()
        if (original.none { it.taskInfo?.taskId == root.taskId }) {
            info.addChange(TransitionInfo.Change(root.token,
                SurfaceControl(rootLeash, "MultiSplitAnimationRoot")).apply {
                setTaskInfo(RunningTaskInfo(root))
                setMode(if (restoring) TRANSIT_OPEN else TRANSIT_TO_BACK)
                setStartAbsBounds(display)
                setEndAbsBounds(display)
                setDisplayId(root.displayId, root.displayId)
            })
        }
        val branches = mutableListOf<TransitionInfo.Change>()
        val removed = original.filter { it.taskInfo?.taskId in taskIds + stageIds }.toMutableList()
        tasks.forEach { task ->
            val id = model.getLeaves().first { it.taskId == task.taskId }.id
            val branchPosition = position(id)
            val branchStages = stages.filterKeys { position(it) == branchPosition }
            val rect = Rect().apply { branchStages.keys.forEach { union(calculated.leaves.getValue(it)) } }
            val representativeStage = stages.getValue(id).mRootTaskInfo
            val source = original.firstOrNull { it.taskInfo?.taskId == task.taskId }
                ?: original.firstOrNull { it.taskInfo?.taskId == representativeStage.taskId }
            val mode = source?.mode ?: if (restoring) TRANSIT_OPEN else TRANSIT_TO_BACK
            val branch = SurfaceControl.Builder().setName("MultiSplitAnimationBranch")
                .setContainerLayer().setHidden(false).setParent(parentLeash).build()
            val content = SurfaceControl.Builder().setName("MultiSplitAnimationContent")
                .setContainerLayer().setHidden(false).setParent(branch).build()
            animationBranches.addAll(listOf(branch, content))
            t.setPosition(branch, (rect.left - display.left).toFloat(),
                (rect.top - display.top).toFloat()).setWindowCrop(branch, rect.width(), rect.height())
                .setPosition(content, 0f, 0f).setWindowCrop(content, rect.width(), rect.height())
            fun change(taskInfo: RunningTaskInfo, leash: SurfaceControl,
                parent: WindowContainerToken, left: Int, top: Int): TransitionInfo.Change {
                val copy = RunningTaskInfo(taskInfo)
                copy.configuration.windowConfiguration.setBounds(rect)
                return TransitionInfo.Change(copy.token, SurfaceControl(leash, "MultiSplitAnimation"))
                    .apply {
                        setTaskInfo(copy)
                        setParent(parent)
                        setMode(mode)
                        setFlags(source?.flags ?: 0)
                        setDisplayId(root.displayId, root.displayId)
                        setStartAbsBounds(rect)
                        setEndAbsBounds(rect)
                        setEndRelOffset(left, top)
                        if (source != null) setRotation(source.startRotation, source.endRotation)
                    }
            }
            val stageChange = change(representativeStage, branch, root.token,
                rect.left - display.left, rect.top - display.top)
            stageChange.taskInfo!!.parentTaskId = root.taskId
            val leafChange = change(task, content, representativeStage.token, 0, 0)
            leafChange.taskInfo!!.parentTaskId = representativeStage.taskId
            branches.add(leafChange)
            branches.add(stageChange)
            branchStages.forEach stageLoop@ { (leaf, stage) ->
                val leafRect = calculated.leaves.getValue(leaf)
                val nativeStage = stage.mRootLeash ?: return@stageLoop
                val stageLeash = original.firstOrNull {
                    it.taskInfo?.taskId == stage.mRootTaskInfo.taskId
                }?.leash ?: nativeStage
                animationParents[stageLeash] = rootLeash to Rect(leafRect).apply {
                    offset(-display.left, -display.top)
                }
                t.reparent(stageLeash, content)
                    .setPosition(stageLeash, (leafRect.left - rect.left).toFloat(),
                        (leafRect.top - rect.top).toFloat())
                    .setMatrix(stageLeash, 1f, 0f, 0f, 1f)
                    .setWindowCrop(stageLeash, leafRect.width(), leafRect.height())
                    .show(stageLeash).setAlpha(stageLeash, 1f)
                original.filter { it.taskInfo?.parentTaskId == stage.mRootTaskInfo.taskId }
                    .forEach { child ->
                        animationParents[child.leash] = nativeStage to Rect(0, 0,
                            leafRect.width(), leafRect.height())
                        t.reparent(child.leash, stageLeash).setPosition(child.leash, 0f, 0f)
                            .setMatrix(child.leash, 1f, 0f, 0f, 1f)
                            .setWindowCrop(child.leash, leafRect.width(), leafRect.height())
                            .show(child.leash).setAlpha(child.leash, 1f)
                    }
            }
            original.filter { TransitionUtil.isDividerBar(it) && rect.contains(it.startAbsBounds) }
                .forEach { divider ->
                    removed.add(divider)
                    t.reparent(divider.leash, content).setPosition(divider.leash,
                        (divider.startAbsBounds.left - rect.left).toFloat(),
                        (divider.startAbsBounds.top - rect.top).toFloat())
                }
        }
        val first = original.indexOfFirst { it in removed }.coerceAtLeast(0)
        animationChanges.addAll(removed)
        info.changes.removeAll(removed.toSet())
        info.changes.addAll(first.coerceAtMost(info.changes.size), branches)
    }

    private fun canInsertColumn(model: MultiSplitLayout): Boolean {
        val configuration = displays.getDisplayContext(host.rootInfo()!!.displayId)
            ?.resources?.configuration ?: return false
        return model.getLeaves().size == 2 && model.root.orientation == Orientation.LEFT_RIGHT &&
            configuration.smallestScreenWidthDp >= 600 && host.capacity() > 2 &&
            displayBounds().width() > displayBounds().height()
    }

    /** Makes the caller a single-app half before adding its new window to a four-app grid. */
    private fun insertionModel(model: MultiSplitLayout, id: LeafId): MultiSplitLayout? {
        if (model.insertionAxis(id) != null) return model
        if (model.getLeaves().size != 3) return null
        val selected = model.getLeaves().firstOrNull { it.id == id } ?: return null
        val others = model.getLeaves().filter { it.id != id }
        val inner = (model.root.first as? Split) ?: (model.root.second as? Split) ?: return null
        val axis = if (model.root.orientation == Orientation.LEFT_RIGHT) Orientation.TOP_BOTTOM
            else Orientation.LEFT_RIGHT
        val pair = Split(inner.id, axis, .5f, others[0], others[1])
        val first = position(id, model) == SPLIT_POSITION_TOP_OR_LEFT
        return MultiSplitLayout(Split(model.root.id, model.root.orientation, .5f,
            if (first) selected else pair, if (first) pair else selected))
    }

    fun position(id: LeafId, model: MultiSplitLayout? = layout): Int {
        fun contains(node: Node): Boolean = when (node) {
            is Leaf -> node.id == id
            is Split -> contains(node.first) || contains(node.second)
        }
        return if (model != null && contains(model.root.first)) SPLIT_POSITION_TOP_OR_LEFT
            else SPLIT_POSITION_BOTTOM_OR_RIGHT
    }

    private fun canRotateThreeAppShape(): Boolean {
        val model = layout ?: return false
        val configuration = displays.getDisplayContext(host.rootInfo()!!.displayId)
            ?.resources?.configuration ?: return false
        return model.getLeaves().size == 3 && model.root.orientation == Orientation.LEFT_RIGHT &&
            configuration.smallestScreenWidthDp >= 600 && host.capacity() > 2 &&
            displayBounds().width() > displayBounds().height()
    }

    val activeStages: List<StageTaskListener> get() = stages.values.toList()
}
