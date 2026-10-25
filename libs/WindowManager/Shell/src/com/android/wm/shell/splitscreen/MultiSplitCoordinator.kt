// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

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

    fun getBranchBounds(first: Rect, second: Rect) {
        val model = layout ?: return
        val rectangles = bounds(model)?.leaves ?: return
        first.setEmpty()
        second.setEmpty()
        rectangles.forEach { (id, rect) ->
            if (position(id) == SPLIT_POSITION_TOP_OR_LEFT) first.union(rect) else second.union(rect)
        }
    }

    private fun fallbackPair(first: Int, second: Int, firstOptions: Bundle?, secondOptions: Bundle?,
        remote: RemoteTransition?) {
        val wct = WindowContainerTransaction()
        if (isActive) exit(-1, wct)
        groups.remove(first)
        groups.remove(second)
        listOf(second to secondOptions, first to firstOptions).filter { it.first >= 0 }
            .forEach { (id, bundle) ->
                val options = ActivityOptions.fromBundle(
                    bundle ?: ActivityOptions.makeBasic().toBundle())
                options.setLaunchRootTask(null)
                options.launchWindowingMode = WINDOWING_MODE_FULLSCREEN
                wct.startTask(id, options.toBundle())
            }
        pending = transitions.startTransition(TRANSIT_OPEN, wct, this)
        if (remote != null) restoreRemotes[pending!!] = remote
    }

    /** Rejected overlapping pair launches still release the caller's animation bookkeeping. */
    fun rejectRemote(remote: RemoteTransition?) {
        if (remote == null) return
        notifyRemote(remote, Binder(), true)
    }

    /** Group cards enter through restorePair; a task TO_FRONT is also used by normal launches. */
    fun restoreRequest(transition: IBinder, request: TransitionRequestInfo):
        WindowContainerTransaction? {
        val task = request.triggerTask ?: return null
        if (groups.peek(task.taskId) == null) return null
        if (restoreBusy()) return null
        if (isActive && leafOfTask(task.taskId) != null &&
            request.windowingLayerChange?.windowingLayer ==
                android.app.ActivityManager.AppTask.WINDOWING_LAYER_PINNED) return null
        if (request.fullscreenRequestChange != null || request.requestedLocation != null ||
            request.windowingLayerChange != null || request.pipChange != null) {
            val wct = WindowContainerTransaction()
            if (isActive) exit(task.taskId, wct)
            groups.remove(task.taskId)
            if (wct.isEmpty) return null
            pending = transition
            request.remoteTransition?.let { restoreRemotes[transition] = it }
            return wct
        }
        if (!isActive && TransitionUtil.isOpeningType(request.type) &&
            task.windowingMode == WINDOWING_MODE_FULLSCREEN && !task.hasParentTask()) {
            groups.remove(task.taskId)
        }
        return null
    }

    private fun prepareRestore(group: MultiSplitGroupRepository.Group,
        wct: WindowContainerTransaction, focusedTaskId: Int): Boolean {
        if (restoreBusy()) return false
        if (!MultiSplitLayout.isAllowed(group.layout.root, group.layout.threeColumnGroup)) {
            groups.remove(group.firstRepresentative)
            return false
        }
        val tasks = group.members.map { organizer.getRunningTaskInfo(it.taskId) ?: run {
            groups.remove(group.firstRepresentative)
            return false
        } }
        if (!group.matches(tasks) || host.capacity() < group.members.size ||
            host.rootInfo()?.displayId != group.displayId || tasks.any {
                it.isFloatingWindow || it.windowingMode ==
                    android.app.WindowConfiguration.WINDOWING_MODE_PINNED }) {
            groups.remove(group.firstRepresentative)
            return false
        }
        var restoredLayout = group.layout.reorient(if (group.layout.threeColumnGroup) {
            if (displayBounds().width() > displayBounds().height()) Orientation.LEFT_RIGHT
            else Orientation.TOP_BOTTOM
        } else if (host.pairLayout().isLeftRightSplit)
            Orientation.LEFT_RIGHT else Orientation.TOP_BOTTOM)
        if (!fitsExistingTasks(restoredLayout)) restoredLayout = restoredLayout.normal()
        val calculated = bounds(restoredLayout)
        val resources = displays.getDisplayContext(group.displayId)?.resources
        if (calculated == null || resources == null || !LayoutPolicy().isFeasible(calculated,
                LayoutPolicy().getMinimumLeafSize(resources))) {
            groups.remove(group.firstRepresentative)
            return false
        }
        if (!ensureSpares()) return false
        val available = (host.pairStages() + spare).distinct()
        val restoredStages = group.stageIds.mapValues { (_, rootId) ->
            available.firstOrNull { it.mHasRootTask && it.mRootTaskInfo.taskId == rootId }
                ?: run {
                    groups.remove(group.firstRepresentative)
                    return false
                }
        }
        if (isActive && layout?.getLeaves()?.map { it.taskId }?.toSet() !=
            group.members.map { it.taskId }.toSet()) exit(-1, wct)
        if (!isActive) {
            available.forEach { it.evictOtherChildren(wct, tasks.map { task -> task.taskId }.toSet()) }
            host.started()
            if (!listening) {
                listening = true
                insets.addInsetsChangedListener(group.displayId, this)
                ime.addPositionProcessor(imeProcessor)
            }
        }
        pool.clear()
        pool.addAll(available)
        stages.clear()
        stages.putAll(restoredStages)
        layout = restoredLayout
        nextLeaf = maxOf(nextLeaf, group.layout.getLeaves().maxOf { it.id.value } + 1)
        fun maxDivider(node: Node): Int = when (node) {
            is Leaf -> -1
            is Split -> maxOf(node.id.value, maxDivider(node.first), maxDivider(node.second))
        }
        nextDivider = maxOf(nextDivider, maxDivider(group.layout.root) + 1)
        tasks.forEach { task ->
            val leaf = group.layout.getLeaves().first { it.taskId == task.taskId }.id
            wct.reparent(task.token, stages.getValue(leaf).mRootTaskInfo.token, true)
                .setWindowingMode(task.token, WINDOWING_MODE_UNDEFINED).setBounds(task.token, Rect())
        }
        visible = true
        restoring = true
        revision++
        applyTasks(wct)
        val focusedLeaf = group.layout.getLeaves().first { it.taskId == focusedTaskId }.id
        wct.reorder(stages.getValue(focusedLeaf).mRootTaskInfo.token, true)
            .reorder(tasks.first { it.taskId == focusedTaskId }.token, true)
            .reorder(host.rootInfo()!!.token, true)
        returnPair(wct)
        return true
    }

    private fun ensureSpares(): Boolean {
        if (host.capacity() <= 2) return false
        if (spare.isEmpty()) spare.addAll(host.spareStages(spareCallbacks))
        attachSpares()
        return spare.size >= 2 && spare.all { it.mHasRootTask && it in attached }
    }

    private fun attachSpares() {
        val root = host.rootInfo() ?: return
        val wct = WindowContainerTransaction()
        spare.filter { it.mHasRootTask && it !in attached }.forEach { stage ->
            val token = stage.mRootTaskInfo.token
            wct.reparent(token, root.token, false)
                .setDisallowOverrideBoundsForChildren(token, true)
                .setDisallowOverrideWindowingModeForChildren(token, true)
            attached.add(stage)
        }
        if (!wct.isEmpty) syncQueue.queue(wct)
    }

    /** Seeds from actual pair bounds, retaining the current divider ratio. */
    private fun pairModel(): MultiSplitLayout? {
        val pair = host.pairStages()
        if (pair.size != 2 || pair.any { it.topChildTaskId < 0 }) return null
        val split = host.pairLayout()
        val first = split.topLeftBounds
        val root = split.rootBounds
        val leftRight = split.isLeftRightSplit
        val available = (if (leftRight) root.width() else root.height()) - split.dividerSize
        val before = if (leftRight) first.width() else first.height()
        if (available <= 0) return null
        val measured = split.dividerPositionAsFraction
        val fraction = if (measured > 0f && measured < 1f) measured
            else (before.toFloat() / available).coerceIn(0.01f, 0.99f)
        val ratio = if (fraction < 0.2f || fraction > 0.8f) fraction
            else (before.toFloat() / available).coerceIn(0.01f, 0.99f)
        val strip = if (com.android.wm.shell.Flags.enableFlexibleTwoAppSplit()) {
            when { ratio < 0.2f -> LeafId(0); ratio > 0.8f -> LeafId(1); else -> null }
        } else null
        return MultiSplitLayout(Split(DividerId(0),
            if (leftRight) Orientation.LEFT_RIGHT else Orientation.TOP_BOTTOM,
            ratio, Leaf(LeafId(0), pair[0].topChildTaskId),
            Leaf(LeafId(1), pair[1].topChildTaskId)), strip)
    }

    private fun displayBounds(): Rect = host.pairLayout().rootBounds

    private fun layoutInsets(): Insets {
        // App roots reach the display edges; each app receives its own system bar insets.
        val bottom = imeTop?.let { maxOf(0, displayBounds().bottom - it) } ?: 0
        return Insets.of(0, 0, 0, bottom)
    }

    private fun bounds(model: MultiSplitLayout): MultiSplitLayout.Bounds? {
        return model.calculateBounds(displayBounds(), layoutInsets(), host.pairLayout().dividerSize)
    }

    private fun candidate(model: MultiSplitLayout, leaf: LeafId, first: Boolean): MultiSplitLayout? {
        val axis = model.insertionAxis(leaf) ?: return null
        return model.insert(leaf, Leaf(LeafId(nextLeaf), -100 - nextLeaf), DividerId(nextDivider),
            axis, 0.5f, displayBounds(), layoutInsets(), host.pairLayout().dividerSize,
            LayoutPolicy().getMinimumLeafSize(displays.getDisplayContext(
                host.rootInfo()!!.displayId)!!.resources), first)
    }

    /** Splits the longer axis, with the new app below or on the layout's end side. */
    fun getBelowSlot(taskId: Int, canMutate: Boolean = true): AppDragSlot? =
        belowSlot(taskId, canMutate, true)

    /** Session identity is independent of a transient unrelated transition. */
    fun getBelowSlotForValidation(taskId: Int): AppDragSlot? = belowSlot(taskId, true, false)

    private fun belowSlot(taskId: Int, canMutate: Boolean, requireReady: Boolean): AppDragSlot? {
        fun reject(reason: String): AppDragSlot? {
            lastBelowRejection = "task=$taskId $reason"
            return null
        }
        if (host.capacity() < 4) return reject("Capacity")
        if (keyguard || (requireReady && isBusy)) return reject("Busy")
        if (imeTop != null) return reject("IME")
        if (requireReady && (!canMutate || (!isActive && !host.canEnter()))) {
            return reject("Pair unavailable or busy")
        }
        if (!requireReady && (host.rootInfo()?.isVisible != true ||
                host.pairStages().none { it.mRootTaskInfo?.isVisible == true })) {
            return reject("Pair unavailable")
        }
        val model = layout ?: pairModel() ?: return reject("Pair model unavailable")
        val leaf = model.getLeaves().firstOrNull { it.taskId == taskId }
            ?: return reject("Task is not a leaf")
        if (model.getLeaves().size >= host.capacity()) return reject("At capacity")
        val source = bounds(model)?.leaves?.get(leaf.id) ?: return reject("Source bounds")
        val below = model.insertionAxis(leaf.id)?.let { it == Orientation.TOP_BOTTOM }
            ?: return reject("At capacity")
        val first = !below && displays.getDisplayContext(host.rootInfo()!!.displayId)
            ?.resources?.configuration?.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val proposed = candidate(model, leaf.id, first) ?: return reject("Minimum leaf size")
        existingTaskRejection(proposed)?.let { return reject(it) }
        val rect = bounds(proposed)?.leaves?.get(LeafId(nextLeaf)) ?: return reject("Target bounds")
        // Root creation is asynchronous; preview eligibility must not depend on its callbacks.
        ensureSpares()
        lastBelowRejection = "task=$taskId None"
        return AppDragSlot(position(leaf.id, model), nextLeaf, rect, rect, false,
            leaf.id.value, first, revision, below)
    }

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

    private fun fitsExistingTasks(model: MultiSplitLayout): Boolean =
        existingTaskRejection(model) == null

    /** Each preview is the exact new leaf rectangle of its proposed immutable layout. */
    fun getSlots(displayId: Int): List<AppDragSlot> {
        if (host.rootInfo()?.displayId != displayId || isBusy || imeTop != null || keyguard) {
            return emptyList()
        }
        if (!isActive && !host.canEnter()) return emptyList()
        if (!isActive && !ensureSpares()) return emptyList()
        val model = layout ?: pairModel() ?: return emptyList()
        val calculated = bounds(model) ?: return emptyList()
        if (model.getLeaves().size >= host.capacity()) {
            return model.getLeaves().map { leaf ->
                val rect = calculated.leaves.getValue(leaf.id)
                AppDragSlot(position(leaf.id, model), leaf.id.value, rect, rect, true,
                    leaf.id.value, false, revision)
            }
        }
        return model.getLeaves().flatMap { leaf ->
            val source = calculated.visibleLeaves.getValue(leaf.id)
            val axis = model.insertionAxis(leaf.id)
            if (axis == null) return@flatMap listOf(AppDragSlot(position(leaf.id, model),
                leaf.id.value, source, source, true, leaf.id.value, false, revision))
            listOf(true, false).mapNotNull { first ->
                val added = candidate(model, leaf.id, first) ?: return@mapNotNull null
                if (!fitsExistingTasks(added)) return@mapNotNull null
                val preview = bounds(added)?.leaves?.get(LeafId(nextLeaf)) ?: return@mapNotNull null
                val hit = Rect(source)
                if (axis == Orientation.LEFT_RIGHT) {
                    if (first) hit.right = source.centerX() else hit.left = source.centerX()
                } else {
                    if (first) hit.bottom = source.centerY() else hit.top = source.centerY()
                }
                AppDragSlot(position(leaf.id, model), nextLeaf, preview, hit, false,
                    leaf.id.value, first, revision)
            }
        }
    }

    fun startTask(task: android.app.TaskInfo, slot: AppDragSlot): Boolean {
        val model = layout
        val existing = model?.getLeaves()?.firstOrNull { it.taskId == task.taskId }
        if (model != null && existing != null && slot.occupied) {
            val target = LeafId(slot.leafId)
            if (isBusy || !isVisible || imeTop != null || slot.layoutVersion != revision ||
                bounds(model)?.leaves?.get(target) != slot.bounds || existing.id == target ||
                task.displayId != host.rootInfo()?.displayId || !task.supportsMultiWindow) return false
            return swapLeaves(target.value, existing.id.value)
        }
        if (!task.supportsMultiWindow || task.displayId != host.rootInfo()?.displayId ||
            task.minWidth > slot.bounds.width() || task.minHeight > slot.bounds.height() ||
            (layout ?: pairModel())?.getLeaves()?.any { it.taskId == task.taskId } == true) {
            return rejectInsertion("Task ${task.taskId} unavailable, visible or incompatible with ${slot.bounds}")
        }
        return insert(slot, null, true) { wct, opts, target ->
            task.token?.let { token ->
                if (task.isFloatingWindow) wct.merge(
                    com.android.wm.shell.pinnedlayer.phone.PinnedLayerUtils
                        .getRemovedFromLayerWct(token), true)
                wct.setWindowingMode(token, android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED)
                    .setBounds(token, Rect())
                    .reparent(token, target.mRootTaskInfo.token, true)
            }
            wct.startTask(task.taskId, opts.toBundle())
        }
    }

    private fun childTasks(stage: StageTaskListener): List<RunningTaskInfo> =
        if (!stage.mHasRootTask) emptyList() else try {
            organizer.getChildTasks(stage.mRootTaskInfo.token, intArrayOf(
                android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD,
                android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED)).orEmpty()
        } catch (e: RuntimeException) {
            Log.w("MultiSplitCoordinator", "Unable to query prepared stage", e)
            emptyList()
        }

    private fun reusable(stage: StageTaskListener): Boolean {
        if (stage !in retiredStages) return true
        if (childTasks(stage).isNotEmpty()) return false
        retiredStages.remove(stage)
        return true
    }

    private fun armInsertionTimeout(waiting: PendingInsertion, delay: Long) {
        executor.removeCallbacks(waiting.timeout)
        waiting.deadline = SystemClock.uptimeMillis() + delay
        executor.executeDelayed(waiting.timeout, delay)
    }

    private fun clearInsertion(waiting: PendingInsertion) {
        executor.removeCallbacks(waiting.timeout)
        executor.removeCallbacks(waiting.probe)
        if (insertion === waiting) insertion = null
    }

    /** Resolves membership by the reserved parent, without requiring an opaque/visible activity. */
    private fun probeInsertion(info: TransitionInfo? = null, incoming: RunningTaskInfo? = null): Boolean {
        val waiting = insertion ?: return false
        if (waiting.failure != null || probingInsertion) return false
        if (waiting.taskId != null) {
            if (waiting.finished) clearInsertion(waiting)
            return true
        }
        probingInsertion = true
        try {
            val stage = waiting.stage
            if (!stage.mHasRootTask || stages[waiting.leaf] !== stage) return false
            val known = stage.topChildTaskId.takeIf { stage.childCount > 0 && stage.containsTask(it) }
                ?.let { organizer.getRunningTaskInfo(it) ?: stage.mChildrenTaskInfo[it] }
            val task = known?.takeIf { it.taskId !in waiting.previousTasks }
                ?: incoming?.takeIf {
                    it.parentTaskId == stage.mRootTaskInfo.taskId &&
                        it.taskId !in waiting.previousTasks && it.taskId != stage.mRootTaskInfo.taskId
                } ?: info?.changes?.mapNotNull { it.taskInfo }?.firstOrNull {
                    it.parentTaskId == stage.mRootTaskInfo.taskId &&
                        it.taskId !in waiting.previousTasks && it.taskId != stage.mRootTaskInfo.taskId
                } ?: childTasks(stage).firstOrNull {
                    it.parentTaskId == stage.mRootTaskInfo.taskId && it.taskId !in waiting.previousTasks &&
                        (it.topActivityInfo != null || it.numActivities > 0)
                } ?: return false
            val rect = bounds(layout ?: return false)?.leaves?.get(waiting.leaf) ?: return false
            if (task.displayId != host.rootInfo()?.displayId ||
                task.minWidth > rect.width() || task.minHeight > rect.height()) {
                requestInsertionRollback("Arriving task does not fit")
                return false
            }
            layout = layout?.assign(waiting.leaf, task.taskId) ?: return false
            // Organizer appearance may still be deferred until the translucent app becomes visible.
            val reported = stage.containsTask(task.taskId)
            if (!reported) {
                stage.mChildrenTaskInfo.put(task.taskId, task)
                unreportedTasks.getOrPut(stage) { mutableSetOf() }.add(task.taskId)
            }
            waiting.taskId = task.taskId
            executor.removeCallbacks(waiting.probe)
            lastInsertRejection = "None"
            if (!reported || waiting.finished) {
                val wct = WindowContainerTransaction()
                applyTasks(wct)
                wct.setWindowingMode(task.token, WINDOWING_MODE_UNDEFINED).setBounds(task.token, Rect())
                    .reorder(task.token, true)
                val options = ActivityOptions.makeBasic().apply {
                    setLaunchRootTask(stage.mRootTaskInfo.token)
                    setLaunchDisplayId(task.displayId)
                    setDisallowEnterPictureInPictureWhileLaunching(true)
                }
                // Resume the resolved task by ID, including a paused translucent first activity.
                wct.startTask(task.taskId, options.toBundle())
                if (visible && !keyguard) host.rootInfo()?.let { wct.reorder(it.token, true) }
                organizer.applyTransaction(wct)
                syncQueue.runInSync { applySurfaceChanges(it) }
            }
            if (waiting.finished) clearInsertion(waiting)
            publishGroup()
            host.changed()
            return true
        } finally {
            probingInsertion = false
        }
    }

    private fun finishInsertion(transition: IBinder, aborted: Boolean) {
        val waiting = insertion?.takeIf { it.transition == transition } ?: return
        if (waiting.failure != null) return
        if (aborted) {
            requestInsertionRollback("Insertion transition aborted")
        } else if (!waiting.finished) {
            val bound = probeInsertion()
            waiting.finished = true
            if (bound) clearInsertion(waiting) else armInsertionTimeout(waiting, INSERTION_GRACE_MS)
        }
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

    private fun requestInsertionRollback(reason: String) {
        val waiting = insertion ?: return
        if (waiting.failure != null) return
        waiting.failure = reason
        executor.removeCallbacks(waiting.timeout)
        executor.removeCallbacks(waiting.probe)
        transitions.runOnIdle {
            if (insertion !== waiting) return@runOnIdle
            val wct = WindowContainerTransaction()
            rollbackInsertion(waiting, reason, wct)
            organizer.applyTransaction(wct)
            syncDividers()
            syncQueue.runInSync { applySurfaceChanges(it) }
            setInteractive(!isBusy)
            publishGroup()
            scheduleReconcile()
            host.changed()
        }
    }

    private fun rejectInsertion(reason: String): Boolean {
        lastInsertRejection = reason
        return false
    }

    fun stageOfTask(taskId: Int): StageTaskListener? = stages.values.firstOrNull {
        it.containsTask(taskId) || it.isRootTaskId(taskId)
    }

    fun stageOfParent(task: RunningTaskInfo): StageTaskListener? = stages.values.firstOrNull {
        it.mRootTaskInfo?.taskId == task.parentTaskId
    }

    private fun leafOfTask(taskId: Int): LeafId? = stages.entries.firstOrNull {
        it.value.containsTask(taskId)
    }?.key

    fun positionOfTask(taskId: Int): Int = leafOfTask(taskId)?.let { position(it) }
        ?: SPLIT_POSITION_UNDEFINED

    fun position(id: LeafId, model: MultiSplitLayout? = layout): Int {
        fun contains(node: Node): Boolean = when (node) {
            is Leaf -> node.id == id
            is Split -> contains(node.first) || contains(node.second)
        }
        return if (model != null && contains(model.root.first)) SPLIT_POSITION_TOP_OR_LEFT
            else SPLIT_POSITION_BOTTOM_OR_RIGHT
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

    fun mirror(): Boolean = layout?.mirror()?.let { changeShape(it) } ?: false

    fun rotateThreeAppShape(): Boolean {
        if (!canRotateThreeAppShape()) return false
        return layout?.rotateThreeAppShape()?.let { changeShape(it) } ?: false
    }

    private fun canRotateThreeAppShape(): Boolean {
        val model = layout ?: return false
        val configuration = displays.getDisplayContext(host.rootInfo()!!.displayId)
            ?.resources?.configuration ?: return false
        return model.getLeaves().size == 3 && model.root.orientation == Orientation.LEFT_RIGHT &&
            configuration.smallestScreenWidthDp >= 600 && host.capacity() > 2 &&
            displayBounds().width() > displayBounds().height()
    }

    /** Copies the owning leaf's screen bounds, independently of the root split axis. */
    fun getTaskBounds(taskId: Int, outBounds: Rect): Boolean {
        val model = layout ?: return false
        val id = leafOfTask(taskId) ?: return false
        val rect = bounds(model)?.leaves?.get(id) ?: return false
        outBounds.set(rect)
        return true
    }

    /** Exposes both neighbours for multi-app menus; pairs retain their existing swap action. */
    fun getSwapTargets(taskId: Int): SplitScreenController.SplitSwapTargets? {
        val model = layout ?: return null
        if (model.getLeaves().size <= 2) return null
        val id = leafOfTask(taskId) ?: return null
        return SplitScreenController.SplitSwapTargets(id.value,
            swapNeighbour(id, true)?.value ?: -1, swapNeighbour(id, false)?.value ?: -1)
    }

    /** Follows the nearest divider in the requested axis, preserving grid rows and columns. */
    private fun swapNeighbour(id: LeafId, horizontal: Boolean): LeafId? {
        val model = layout ?: return null
        val all = bounds(model)?.leaves ?: return null
        val source = all[id] ?: return null
        val axis = if (horizontal) Orientation.LEFT_RIGHT else Orientation.TOP_BOTTOM
        fun contains(node: Node): Boolean = when (node) {
            is Leaf -> node.id == id
            is Split -> contains(node.first) || contains(node.second)
        }
        fun leaves(node: Node): List<LeafId> = when (node) {
            is Leaf -> listOf(node.id)
            is Split -> leaves(node.first) + leaves(node.second)
        }
        fun find(node: Node): LeafId? {
            if (node !is Split) return null
            val first = contains(node.first)
            val own = if (first) node.first else node.second
            find(own)?.let { return it }
            if (node.orientation != axis) return null
            var other = if (first) node.second else node.first
            // Independent branch ratios must not change the corresponding row or column.
            if (own is Split && other is Split && own.orientation == other.orientation) {
                other = if (contains(own.first)) other.first else other.second
            }
            return leaves(other).minByOrNull { leaf ->
                val rect = all.getValue(leaf)
                val dx = rect.centerX().toLong() - source.centerX()
                val dy = rect.centerY().toLong() - source.centerY()
                dx * dx + dy * dy
            }
        }
        return find(model.root)
    }

    fun swapTask(taskId: Int): Boolean {
        val id = leafOfTask(taskId) ?: return false
        val other = swapNeighbour(id, isLeftRightSplit) ?: return false
        return swapLeaves(id.value, other.value)
    }

    fun closeTask(taskId: Int): Boolean {
        if (isBusy) return false
        val id = leafOfTask(taskId) ?: return false
        val task = organizer.getRunningTaskInfo(taskId) ?: return false
        val wct = WindowContainerTransaction().removeTask(task.token)
        stages.getValue(id).markToBeVanished(taskId)
        removeLeaf(id, wct, taskId)
        pending = transitions.startTransition(TRANSIT_CLOSE, wct, this)
        setInteractive(false)
        return true
    }

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

    /** Removes only the selected leaf in the same transaction that reparents it to floating. */
    fun prepareLeafToFloating(taskId: Int, wct: WindowContainerTransaction): Boolean {
        if (!isActive) return false
        val id = leafOfTask(taskId) ?: return false
        removeLeaf(id, wct, taskId)
        setInteractive(false)
        return true
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

    /** Handles task finish/removal before legacy pair-only transition classification. */
    override fun handleRequest(transition: IBinder, request: TransitionRequestInfo):
        WindowContainerTransaction? {
        if (!isActive) return null
        if (pending != null || runningTransitions.isNotEmpty()) return null
        if (request.windowingLayerChange?.windowingLayer ==
                android.app.ActivityManager.AppTask.WINDOWING_LAYER_PINNED) return null
        val task = request.triggerTask ?: return null
        val wct = WindowContainerTransaction()
        insertion?.let {
            if (task.parentTaskId == it.stage.mRootTaskInfo?.taskId ||
                task.taskId == it.stage.mRootTaskInfo?.taskId) probeInsertion(incoming = task)
            else rollbackInsertion(it, "Group changed", wct)
        }
        val stage = stageOfParent(task) ?: stageOfTask(task.taskId)
        if (stage != null && TransitionUtil.isClosingType(request.type) &&
            stage.childCount <= 1 && stages.containsValue(stage)) {
            val id = stages.entries.first { it.value == stage }.key
            removeLeaf(id, wct, task.taskId)
            pending = transition
            setInteractive(false)
            return wct
        }
        if (stage != null && TransitionUtil.isOpeningType(request.type)) {
            if (task.windowingMode == WINDOWING_MODE_FULLSCREEN && !task.hasParentTask()) {
                exit(task.taskId, wct)
                groups.remove(task.taskId)
            } else {
                visible = true
                wct.reorder(host.rootInfo()!!.token, true)
                    .setForceTranslucent(host.rootInfo()!!.token, false)
                applyTasks(wct)
            }
            return wct
        }
        // Home, keyguard, and unrelated fullscreen apps keep the group in the background.
        return wct.takeUnless { it.isEmpty }
    }

    fun forgetGroup(taskId: Int) { groups.remove(taskId) }

    private fun scheduleReconcile() {
        if (reconcileScheduled) return
        reconcileScheduled = true
        executor.execute {
            transitions.runOnIdle {
                reconcileScheduled = false
                if (isActive) {
                    val empty = stages.entries.filter {
                        it.value.childCount == 0 && it.key != insertion?.leaf
                    }.map { it.key }
                    if (empty.isNotEmpty()) {
                        val wct = WindowContainerTransaction()
                        empty.forEach { removeLeaf(it, wct) }
                        if (!wct.isEmpty) pending = transitions.startTransition(TRANSIT_CHANGE, wct, this)
                    }
                }
            }
        }
    }

    fun setKeyguard(active: Boolean) {
        keyguard = active
        if (active) requestInsertionRollback("Keyguard")
        setDividerVisible(visible, null)
    }

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

    private fun setInteractive(enabled: Boolean) {
        if (!enabled) clearStripZone()
        dividers.forEach { (id, divider) ->
            val keepDrag = interacting && id == dragDivider && pending == null &&
                runningTransitions.isEmpty()
            divider.setDividerInteractive((enabled || keepDrag) && !keyguard && imeTop == null,
                false, "multi split")
        }
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

    /** Rotation and eligibility changes share the caller's display-change transaction. */
    fun onConfigurationChanged(configuration: Configuration, wct: WindowContainerTransaction) {
        insertion?.let { rollbackInsertion(it, "Configuration changed", wct) }
        cancelDrag()
        val model = layout ?: return
        if (configuration.smallestScreenWidthDp < 600 || host.capacity() <= 2) {
            host.rootInfo()?.let { groups.clearDisplay(it.displayId) }
            val keep = model.getLeaves().take(2)
            stages.keys.toList().filter { id -> keep.none { it.id == id } }.forEach { id ->
                stages.remove(id)?.let { stage ->
                    stage.evictAllChildren(wct)
                    wct.clearAdjacentRoots(stage.mRootTaskInfo.token)
                }
            }
            layout = MultiSplitLayout(Split(model.root.id,
                if (host.pairLayout().isLeftRightSplit) Orientation.LEFT_RIGHT else Orientation.TOP_BOTTOM,
                0.5f, keep[0], keep[1]))
        }
        if (returnPair(wct)) return
        layout = layout?.reorient(if (layout?.threeColumnGroup == true) {
            if (displayBounds().width() > displayBounds().height()) Orientation.LEFT_RIGHT
            else Orientation.TOP_BOTTOM
        } else if (host.pairLayout().isLeftRightSplit) {
            Orientation.LEFT_RIGHT
        } else Orientation.TOP_BOTTOM)
        layout?.takeIf { it.strip != null }?.let { rotated ->
            val rectangles = bounds(rotated)
            val resources = displays.getDisplayContext(host.rootInfo()!!.displayId)!!.resources
            if (rectangles == null || !LayoutPolicy().isFeasible(rectangles,
                    LayoutPolicy().getMinimumLeafSize(resources)) || !fitsExistingTasks(rotated)) {
                layout = rotated.normal()
            }
        }
        revision++
        applyTasks(wct)
        disposeDividers()
        syncDividers()
        publishGroup()
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

    private fun stopListening() {
        if (!listening) return
        host.rootInfo()?.let { insets.removeInsetsChangedListener(it.displayId, this) }
        ime.removePositionProcessor(imeProcessor)
        imeTop = null
        listening = false
    }

    fun dispose() {
        insertion?.let { clearInsertion(it) }
        retiredStages.clear()
        unreportedTasks.clear()
        disposeDividers()
        stopListening()
        layout = null
        stages.clear()
        attached.clear()
        pending = null
        revision++
    }
}
