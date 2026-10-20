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
import android.util.Log
import android.window.RemoteTransition
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.view.InsetsState
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_CLOSE
import android.view.WindowManager.TRANSIT_OPEN
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
    }

    private val groups = host.groups() ?: MultiSplitGroupRepository(Runnable {})
    private val restoreRemotes = mutableMapOf<IBinder, RemoteTransition>()
    private val recentsTransitions = mutableSetOf<IBinder>()
    private val readyInfos = mutableMapOf<IBinder, TransitionInfo>()
    private var restoring = false
    private var projectedRepresentatives = emptyList<Int>()
    private val spare = mutableListOf<StageTaskListener>()
    private val attached = mutableSetOf<StageTaskListener>()
    private val stages = linkedMapOf<LeafId, StageTaskListener>()
    private val pool = mutableListOf<StageTaskListener>()
    private val dividers = linkedMapOf<DividerId, SplitLayout>()
    private val dividerHandlers = mutableMapOf<DividerId, DividerHandler>()
    private var layout: MultiSplitLayout? = null
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

    val isActive: Boolean get() = layout != null
    val isBusy: Boolean get() = keyguard || pending != null ||
        runningTransitions.isNotEmpty() || interacting ||
        dividers.values.any { it.isCurrentlyDividerFlinging } ||
        (isActive && stages.values.any { it.childCount == 0 })
    val isVisible: Boolean get() = isActive && visible &&
        stages.values.any { it.mRootTaskInfo?.isVisible == true }
    val activeStages: List<StageTaskListener> get() = stages.values.toList()
    val isLeftRightSplit: Boolean get() = layout?.root?.orientation == Orientation.LEFT_RIGHT

    fun getBranchBounds(first: Rect, second: Rect) {
        val model = layout ?: return
        val rectangles = bounds(model)?.leaves ?: return
        first.setEmpty()
        second.setEmpty()
        rectangles.forEach { (id, rect) ->
            if (position(id) == SPLIT_POSITION_TOP_OR_LEFT) first.union(rect) else second.union(rect)
        }
    }

    fun taskAtPosition(requestedPosition: Int): Int = stages.entries.firstOrNull {
        position(it.key) == requestedPosition && it.value.isFocused
    }?.value?.topChildTaskId ?: stages.entries.firstOrNull {
        position(it.key) == requestedPosition
    }?.value?.topChildTaskId ?: -1

    init {
        groups.setValidator { group ->
            val roots = (host.pairStages() + spare).filter { it.mHasRootTask }
                .map { it.mRootTaskInfo.taskId }.toSet()
            host.rootInfo()?.displayId == group.displayId && host.capacity() >= group.members.size &&
                group.stageIds.values.all { it in roots }
        }
        transitions.registerObserver(this)
    }

    /** Publishes only complete snapshots; organizer launch callbacks replace placeholder IDs. */
    fun publishGroup(): MultiSplitGroupRepository.Group? {
        var model = layout ?: return null
        if (stages.values.any { it.childCount == 0 }) return null
        val tasks = stages.map { (id, stage) ->
            val task = organizer.getRunningTaskInfo(stage.topChildTaskId) ?: return null
            model = model.assign(id, task.taskId) ?: return null
            task
        }
        layout = model
        val group = groups.save(model, stages.mapValues { it.value.mRootTaskInfo.taskId },
            tasks, displayBounds(), host.pairLayout().dividerSize)
        return group
    }

    /** Projects exactly two stage identities, even when physical stage roots have other IDs. */
    fun sendStatus(listener: SplitScreen.SplitScreenListener) {
        val group = publishGroup() ?: return
        val representatives = listOf(group.firstRepresentative, group.secondRepresentative)
        projectedRepresentatives.filter { it !in representatives }.forEach {
            listener.onTaskStageChanged(it, SplitScreen.STAGE_TYPE_UNDEFINED, false)
        }
        listener.onStagePositionChanged(SplitScreen.STAGE_TYPE_MAIN, SPLIT_POSITION_TOP_OR_LEFT)
        listener.onStagePositionChanged(SplitScreen.STAGE_TYPE_SIDE, SPLIT_POSITION_BOTTOM_OR_RIGHT)
        listener.onTaskStageChanged(representatives[0], SplitScreen.STAGE_TYPE_MAIN, isVisible)
        listener.onTaskStageChanged(representatives[1], SplitScreen.STAGE_TYPE_SIDE, isVisible)
        listener.onSplitBoundsChanged(displayBounds(), group.splitBounds.leftTopBounds,
            group.splitBounds.rightBottomBounds)
    }

    fun representativesSent() {
        val group = publishGroup()
        projectedRepresentatives = group?.let {
            listOf(it.firstRepresentative, it.secondRepresentative)
        } ?: emptyList()
    }

    fun startRecents(info: TransitionInfo) {
        publishGroup()
        recentsTransitions.addAll(runningTransitions.filter { readyInfos[it] === info })
        setDividerVisible(false, null)
    }

    private fun restoreBusy(): Boolean = keyguard || pending != null || interacting ||
        runningTransitions.any { it !in recentsTransitions } ||
        dividers.values.any { it.isCurrentlyDividerFlinging }

    /** A pair-card launch is handled before the ordinary active-session guard. */
    fun restorePair(first: Int, second: Int, firstOptions: Bundle?, secondOptions: Bundle?,
        remote: RemoteTransition?): Boolean {
        val group = groups.peek(first) ?: return false
        if (second == -1) {
            if (restoreBusy()) rejectRemote(remote)
            else fallbackPair(first, second, firstOptions, secondOptions, remote)
            return true
        }
        if (setOf(first, second) != setOf(group.firstRepresentative, group.secondRepresentative)) {
            return false
        }
        if (restoreBusy()) {
            rejectRemote(remote)
            return true
        }
        if (listOfNotNull(firstOptions, secondOptions).any {
                ActivityOptions.fromBundle(it).launchWindowingMode == WINDOWING_MODE_FULLSCREEN }) {
            fallbackPair(first, second, firstOptions, secondOptions, remote)
            return true
        }
        val wct = WindowContainerTransaction()
        if (!prepareRestore(group, wct, first)) return false
        pending = transitions.startTransition(TRANSIT_OPEN, wct, this)
        if (remote != null) restoreRemotes[pending!!] = remote
        setInteractive(false)
        return true
    }

    /** Cleans up a previous session before the ordinary pair starter supplies its launch WCT. */
    fun preparePairLaunch(wct: WindowContainerTransaction, remote: RemoteTransition?): Boolean {
        if (restoreBusy()) {
            rejectRemote(remote)
            return false
        }
        if (isActive) exit(-1, wct)
        return true
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

    /** TO_FRONT covers startActivityFromRecents; explicit mode/location changes take precedence. */
    fun restoreRequest(transition: IBinder, request: TransitionRequestInfo):
        WindowContainerTransaction? {
        val task = request.triggerTask ?: return null
        val group = groups.peek(task.taskId) ?: return null
        if (restoreBusy()) return null
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
        if (request.type != android.view.WindowManager.TRANSIT_TO_FRONT) return null
        val wct = WindowContainerTransaction()
        if (!prepareRestore(group, wct, task.taskId)) {
            if (groups.peek(task.taskId) == null && isActive) {
                exit(task.taskId, wct)
                groups.remove(task.taskId)
                pending = transition
                request.remoteTransition?.let { restoreRemotes[transition] = it }
                return wct
            }
            return null
        }
        pending = transition
        request.remoteTransition?.let { restoreRemotes[transition] = it }
        setInteractive(false)
        return wct
    }

    private fun prepareRestore(group: MultiSplitGroupRepository.Group,
        wct: WindowContainerTransaction, focusedTaskId: Int): Boolean {
        if (restoreBusy()) return false
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
        val restoredLayout = group.layout.reorient(if (host.pairLayout().isLeftRightSplit)
            Orientation.LEFT_RIGHT else Orientation.TOP_BOTTOM)
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
        return true
    }

    private fun consumeRestoreRemote(transition: IBinder, aborted: Boolean) {
        val remote = restoreRemotes.remove(transition) ?: return
        notifyRemote(remote, transition, aborted)
    }

    private fun notifyRemote(remote: RemoteTransition, transition: IBinder, aborted: Boolean) {
        try {
            remote.remoteTransition.onTransitionConsumed(transition, aborted)
        } catch (e: RemoteException) {
            Log.w("MultiSplitCoordinator", "Restore transition client disconnected", e)
        }
    }

    /** Returning to apps restores every root and child endpoint in the recents finish WCT. */
    fun finishRecents(returnToApp: Boolean, wct: WindowContainerTransaction,
        t: SurfaceControl.Transaction) {
        val showGroup = returnToApp || restoring
        if (showGroup) {
            applyTasks(wct)
            wct.reorder(host.rootInfo()!!.token, true)
            stages.values.forEach { stage ->
                stage.mRootLeash?.takeIf { it.isValid }?.let { t.show(it).setAlpha(it, 1f) }
                stage.restoreChildSurfaces(t)
            }
        }
        setDividerVisible(showGroup, t)
        applySurfaceChanges(t)
        publishGroup()
        recentsTransitions.clear()
    }

    fun isReorderingToTop(wct: WindowContainerTransaction): Boolean = wct.hierarchyOps.any { op ->
        op.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REORDER &&
            op.toTop && (op.container == host.rootInfo()?.token?.asBinder() || stages.values.any {
                it.containsContainer(op.container) || it.mRootTaskInfo.token.asBinder() == op.container
            })
    }

    private val spareCallbacks = object : StageTaskListener.StageListenerCallbacks {
        override fun onRootTaskAppeared() { executor.execute { attachSpares() } }
        override fun onRootTaskVanished() {
            executor.execute {
                if (isActive) {
                    stages.entries.removeAll { !it.value.mHasRootTask }
                    val wct = WindowContainerTransaction()
                    exit(-1, wct)
                    organizer.applyTransaction(wct)
                }
                attached.removeAll { !it.mHasRootTask }
            }
        }
        override fun onStageVisibilityChanged(stage: StageTaskListener) { onStageChanged() }
        override fun onChildTaskStatusChanged(stage: StageTaskListener, taskId: Int,
            present: Boolean, visible: Boolean) { onTaskChanged(stage, taskId, present) }
        override fun onChildTaskMovedToBubble(stage: StageTaskListener, taskId: Int) {
            onTaskChanged(stage, taskId, false)
        }
        override fun onNoLongerSupportMultiWindow(stage: StageTaskListener, task: RunningTaskInfo) {
            moveToFullscreen(task.taskId)
        }
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
        return MultiSplitLayout(Split(DividerId(0),
            if (leftRight) Orientation.LEFT_RIGHT else Orientation.TOP_BOTTOM,
            (before.toFloat() / available).coerceIn(0.01f, 0.99f),
            Leaf(LeafId(0), pair[0].topChildTaskId), Leaf(LeafId(1), pair[1].topChildTaskId)))
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
        val rect = bounds(model)?.leaves?.get(leaf) ?: return null
        val axis = if (rect.width() >= rect.height()) Orientation.LEFT_RIGHT else Orientation.TOP_BOTTOM
        return model.insert(leaf, Leaf(LeafId(nextLeaf), -100 - nextLeaf), DividerId(nextDivider),
            axis, 0.5f, displayBounds(), layoutInsets(), host.pairLayout().dividerSize,
            LayoutPolicy().getMinimumLeafSize(displays.getDisplayContext(
                host.rootInfo()!!.displayId)!!.resources), first)
    }

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
            val source = calculated.leaves.getValue(leaf.id)
            listOf(true, false).mapNotNull { first ->
                val added = candidate(model, leaf.id, first) ?: return@mapNotNull null
                val preview = bounds(added)?.leaves?.get(LeafId(nextLeaf)) ?: return@mapNotNull null
                val hit = Rect(source)
                if (source.width() >= source.height()) {
                    if (first) hit.right = source.centerX() else hit.left = source.centerX()
                } else {
                    if (first) hit.bottom = source.centerY() else hit.top = source.centerY()
                }
                AppDragSlot(position(leaf.id, model), nextLeaf, preview, hit, false,
                    leaf.id.value, first, revision)
            }
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

    /** Revalidates the preview revision and capacity at DROP, then launches in a single WCT. */
    fun startIntent(intent: PendingIntent, fillIn: Intent?, options: Bundle?, slot: AppDragSlot): Boolean {
        if (isBusy || slot.layoutVersion != revision || host.capacity() < 4 ||
            (!isActive && !host.canEnter())) return false
        val model = layout ?: pairModel() ?: return false
        val targetId = LeafId(slot.leafId)
        if (model.getLeaves().none { it.id == targetId }) return false
        val inserting = !slot.occupied
        if (inserting && (model.getLeaves().size >= host.capacity() || !ensureSpares())) return false
        val proposed = if (inserting) candidate(model, targetId, slot.newTaskFirst) ?: return false
            else model
        val expected = bounds(proposed)?.leaves?.get(if (inserting) LeafId(nextLeaf) else targetId)
        if (expected != slot.bounds) return false
        val wct = WindowContainerTransaction()
        if (!isActive) {
            projectedRepresentatives = host.pairStages().map { it.topChildTaskId }
            pool.clear()
            pool.addAll(host.pairStages())
            pool.addAll(spare)
            host.pairStages().forEachIndexed { i, stage -> stages[LeafId(i)] = stage }
            layout = model
            host.pairStages().forEach { stage ->
                stage.evictOtherChildren(wct, setOf(stage.topChildTaskId))
            }
            host.started()
            listening = true
            insets.addInsetsChangedListener(host.rootInfo()!!.displayId, this)
            ime.addPositionProcessor(imeProcessor)
        }
        val target = if (inserting) {
            val stage = pool.firstOrNull { it !in stages.values && it.mHasRootTask } ?: return false
            stages[LeafId(nextLeaf)] = stage
            nextLeaf++
            nextDivider++
            stage
        } else stages.getValue(targetId)
        // Stage history must not resurface after the leaf's new app closes.
        target.evictAllChildren(wct)
        val opts = ActivityOptions.fromBundle(options ?: ActivityOptions.makeBasic().toBundle())
        opts.setLaunchRootTask(target.mRootTaskInfo.token)
        opts.setLaunchDisplayId(host.rootInfo()!!.displayId)
        opts.setDisallowEnterPictureInPictureWhileLaunching(true)
        opts.setPendingIntentBackgroundActivityStartMode(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
        wct.sendPendingIntent(intent, fillIn, opts.toBundle())
        layout = proposed
        revision++
        applyTasks(wct)
        pending = transitions.startTransition(TRANSIT_OPEN, wct, this)
        setInteractive(false)
        return true
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
            applyTasks(wct)
        }
    }

    /** Handles task finish/removal before legacy pair-only transition classification. */
    override fun handleRequest(transition: IBinder, request: TransitionRequestInfo):
        WindowContainerTransaction? {
        if (!isActive) return null
        if (pending != null || runningTransitions.isNotEmpty()) return null
        val task = request.triggerTask ?: return null
        val stage = stageOfParent(task) ?: stageOfTask(task.taskId)
        val wct = WindowContainerTransaction()
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
        return null
    }

    fun moveToFullscreen(taskId: Int) {
        if (!isActive || isBusy) return
        val wct = WindowContainerTransaction()
        exit(taskId, wct)
        groups.remove(taskId)
        pending = transitions.startTransition(TRANSIT_CHANGE, wct, this)
    }

    fun exit(taskId: Int, wct: WindowContainerTransaction) {
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

    /** Removes dead leaves after organizer callbacks settle, including aborted launches. */
    fun onTaskChanged(stage: StageTaskListener, taskId: Int, present: Boolean) {
        if (!isActive || stage !in stages.values) return
        if (!present) groups.remove(taskId)
        if (present) {
            val id = stages.entries.first { it.value == stage }.key
            layout = layout?.assign(id, stage.topChildTaskId.takeIf { it >= 0 } ?: taskId) ?: layout
        }
        if (present) host.changed()
        scheduleReconcile()
    }

    private fun scheduleReconcile() {
        if (reconcileScheduled) return
        reconcileScheduled = true
        executor.execute {
            transitions.runOnIdle {
                reconcileScheduled = false
                if (isActive) {
                    val empty = stages.entries.filter { it.value.childCount == 0 }.map { it.key }
                    if (empty.isNotEmpty()) {
                        val wct = WindowContainerTransaction()
                        empty.forEach { removeLeaf(it, wct) }
                        if (!wct.isEmpty) pending = transitions.startTransition(TRANSIT_CHANGE, wct, this)
                    }
                }
            }
        }
    }

    fun onStageChanged() {
        if (!isActive) return
        val active = stages.values.toList()
        if (active.all { it.mRootTaskInfo?.isVisible == true }) visible = true
        if (active.all { it.mRootTaskInfo?.isVisible != true }) visible = false
        setDividerVisible(visible, null)
        host.changed()
    }

    fun setKeyguard(active: Boolean) {
        keyguard = active
        setDividerVisible(visible, null)
    }

    fun setDividerVisible(show: Boolean, transaction: SurfaceControl.Transaction?) {
        visible = show
        val t = transaction ?: SurfaceControl.Transaction()
        dividers.values.forEach { split -> split.dividerLeash?.takeIf { it.isValid }?.let {
            if (show && !keyguard) t.show(it).setAlpha(it, 1f) else t.hide(it)
        } }
        if (transaction == null) { t.apply(); t.close() }
    }

    private fun applyTasks(wct: WindowContainerTransaction) {
        val model = layout ?: return
        val calculated = bounds(model) ?: return
        val tokens = stages.values.map { it.mRootTaskInfo.token }.toTypedArray()
        stages.forEach { (id, stage) ->
            wct.reparent(stage.mRootTaskInfo.token, host.rootInfo()!!.token, true)
            wct.setBounds(stage.mRootTaskInfo.token, calculated.leaves.getValue(id))
                .setAppBounds(stage.mRootTaskInfo.token, null)
                .setSmallestScreenWidthDp(stage.mRootTaskInfo.token, 0)
                .setScreenSizeDp(stage.mRootTaskInfo.token, 0, 0)
        }
        if (tokens.size >= 2) wct.setAdjacentRoots(*tokens)
        host.rootInfo()?.let {
            wct.setReparentLeafTaskIfRelaunch(it.token, false).setForceTranslucent(it.token, false)
        }
    }

    fun applySurfaceChanges(t: SurfaceControl.Transaction) {
        val model = layout ?: return
        val calculated = bounds(model) ?: return
        val root = displayBounds()
        stages.forEach { (id, stage) ->
            val rect = calculated.leaves.getValue(id)
            stage.mRootLeash?.takeIf { it.isValid }?.let { leash ->
                t.setPosition(leash, rect.left - root.left.toFloat(), rect.top - root.top.toFloat())
                    .setWindowCrop(leash, rect.width(), rect.height())
            }
            stage.mDimLayer?.takeIf { it.isValid }?.let { t.hide(it) }
        }
        dividers.values.forEach { split ->
            val rect = Rect()
            split.getDividerLeashBounds(rect)
            val branch = split.rootBounds
            split.dividerLeash?.takeIf { it.isValid }?.let {
                val crop = split.multiSplitDividerCrop
                t.setPosition(it, rect.left + branch.left - root.left.toFloat(),
                    rect.top + branch.top - root.top.toFloat()).setWindowCrop(it, crop)
                    .setLayer(it, Int.MAX_VALUE)
                if (visible && !keyguard) t.show(it).setAlpha(it, 1f) else t.hide(it)
            }
        }
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
                split.setMultiSplitDivider(rect, node.orientation == Orientation.LEFT_RIGHT, node.ratio)
            }
            if (created) {
                split.init()
                split.insetsChanged(insetState)
            } else if (changed) split.update(t, false)
        }
        setInteractive(!isBusy)
    }

    private fun setInteractive(enabled: Boolean) {
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
            val candidate = original.resize(id, split.branchRatio) ?: return
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
            if (retired || pending != null || runningTransitions.isNotEmpty()) return
            val model = layout ?: return
            fun find(node: Node): Split? = when (node) {
                is Leaf -> null
                is Split -> if (node.id == id) node else find(node.first) ?: find(node.second)
            }
            val node = find(model.root) ?: return
            fun leaves(node: Node): List<LeafId> = when (node) {
                is Leaf -> listOf(node.id)
                is Split -> leaves(node.first) + leaves(node.second)
            }
            interacting = false
            leaves(if (end) node.second else node.first).forEach { removeLeaf(it, wct) }
            pending = transitions.startTransition(TRANSIT_CHANGE, wct, this@MultiSplitCoordinator)
        }
        override fun onDoubleTappedDivider() {
            if (retired) return
            val model = layout ?: return
            val calculated = bounds(model) ?: return
            val rect = calculated.dividers[id] ?: return
            fun find(node: Node): Split? = when (node) {
                is Leaf -> null
                is Split -> if (node.id == id) node else find(node.first) ?: find(node.second)
            }
            fun leaves(node: Node): List<LeafId> = when (node) {
                is Leaf -> listOf(node.id)
                is Split -> leaves(node.first) + leaves(node.second)
            }
            val branch = find(model.root) ?: return
            fun nearest(node: Node): LeafId = leaves(node).minBy { leaf ->
                val bounds = calculated.leaves.getValue(leaf)
                val dx = bounds.centerX().toLong() - rect.centerX()
                val dy = bounds.centerY().toLong() - rect.centerY()
                dx * dx + dy * dy
            }
            swapLeaves(nearest(branch.first).value, nearest(branch.second).value)
        }
        override fun setLayoutOffsetTarget(x: Int, y: Int, split: SplitLayout) {}
        override fun setExcludeImeInsets(exclude: Boolean) {}
        override fun getSplitItemPosition(token: WindowContainerToken): Int = SPLIT_POSITION_UNDEFINED
    }

    /** Rotation and eligibility changes share the caller's display-change transaction. */
    fun onConfigurationChanged(configuration: Configuration, wct: WindowContainerTransaction) {
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
        layout = layout?.reorient(if (host.pairLayout().isLeftRightSplit) {
            Orientation.LEFT_RIGHT
        } else Orientation.TOP_BOTTOM)
        revision++
        applyTasks(wct)
        disposeDividers()
        syncDividers()
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

    override fun startAnimation(transition: IBinder, info: TransitionInfo,
        start: SurfaceControl.Transaction, finish: SurfaceControl.Transaction,
        callback: Transitions.TransitionFinishCallback): Boolean {
        if (transition != pending) return false
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
        start.apply()
        executor.execute {
            consumeRestoreRemote(transition, false)
            callback.onTransitionFinished(null)
        }
        return true
    }

    override fun onTransitionConsumed(transition: IBinder, aborted: Boolean,
        finish: SurfaceControl.Transaction?) {
        consumeRestoreRemote(transition, aborted)
        if (transition == pending) {
            restoring = false
            pending = null
            if (isActive) {
                syncDividers(finish)
                if (finish != null) applySurfaceChanges(finish)
                scheduleReconcile()
            }
        }
    }

    override fun onTransitionReady(transition: IBinder, info: TransitionInfo,
        start: SurfaceControl.Transaction, finish: SurfaceControl.Transaction) {
        runningTransitions.add(transition)
        readyInfos[transition] = info
        if (!isActive) return
        cancelDrag()
        setInteractive(false)
        info.changes.firstOrNull { it.taskInfo?.taskId == host.rootInfo()?.taskId }?.let {
            visible = !TransitionUtil.isClosingType(it.mode)
        }
        setDividerVisible(visible, start)
        syncDividers(finish)
        applySurfaceChanges(finish)
    }

    override fun onTransitionMerged(merged: IBinder, playing: IBinder) {
        runningTransitions.remove(merged)
        readyInfos.remove(merged)
        if (pending == merged) pending = playing
        if (recentsTransitions.remove(merged)) recentsTransitions.add(playing)
        consumeRestoreRemote(merged, false)
    }

    override fun onTransitionFinished(transition: IBinder, aborted: Boolean) {
        runningTransitions.remove(transition)
        readyInfos.remove(transition)
        recentsTransitions.remove(transition)
        consumeRestoreRemote(transition, aborted)
        if (pending == transition) {
            pending = null
            restoring = false
        }
        if (isActive) {
            visible = host.rootInfo()?.isVisible == true
            syncDividers()
            syncQueue.runInSync { applySurfaceChanges(it) }
            setInteractive(!isBusy)
            scheduleReconcile()
            publishGroup()
            host.changed()
        }
    }

    /** A missing organizer root cannot own a restorable layout or auxiliary surfaces. */
    fun onRootVanished() {
        host.rootInfo()?.let { groups.clearDisplay(it.displayId) }
        restoreRemotes.keys.toList().forEach { consumeRestoreRemote(it, true) }
        pending = null
        restoring = false
        layout = null
        stages.clear()
        recentsTransitions.clear()
        readyInfos.clear()
        revision++
        disposeDividers()
        stopListening()
    }

    private fun disposeDividers() {
        dividerHandlers.values.forEach { it.retired = true }
        dividerHandlers.clear()
        dividers.values.forEach { it.release() }
        dividers.clear()
        interacting = false
        dragStart = null
    }

    private fun stopListening() {
        if (!listening) return
        host.rootInfo()?.let { insets.removeInsetsChangedListener(it.displayId, this) }
        ime.removePositionProcessor(imeProcessor)
        imeTop = null
        listening = false
    }

    fun dispose() {
        disposeDividers()
        stopListening()
        layout = null
        stages.clear()
        attached.clear()
        pending = null
        revision++
    }
}
