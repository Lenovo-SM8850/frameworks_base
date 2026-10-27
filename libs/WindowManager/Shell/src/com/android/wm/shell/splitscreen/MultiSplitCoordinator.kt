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
import com.android.wm.shell.transition.OneShotRemoteHandler
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
    private val restoreHandlers = mutableMapOf<IBinder, OneShotRemoteHandler>()
    private val restoreInfos = mutableMapOf<IBinder, TransitionInfo>()
    private val recentsTransitions = mutableSetOf<IBinder>()
    private val readyInfos = mutableMapOf<IBinder, TransitionInfo>()
    private var restoring = false
    private var recentsAnimating = false
    private val animationBranches = mutableListOf<SurfaceControl>()
    private val animationChanges = mutableListOf<TransitionInfo.Change>()
    private val animationParents = linkedMapOf<SurfaceControl, Pair<SurfaceControl, Rect>>()
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

    fun dump(pw: PrintWriter, prefix: String) {
        pw.println("${prefix}MultiSplitCoordinator: active=$isActive capacity=${host.capacity()} " +
            "busy=$isBusy imeTop=$imeTop keyguard=$keyguard revision=$revision")
        pw.println("$prefix  pending=$pending transitions=${runningTransitions.size} " +
            "interacting=$interacting spares=${spare.count { it.mHasRootTask && it in attached }}")
        pw.println("$prefix  belowSlot=$lastBelowRejection insertion=$lastInsertRejection")
        insertion?.let {
            pw.println("$prefix  awaitingStage=${it.stage.mRootTaskInfo?.taskId} leaf=${it.leaf.value} " +
                "task=${it.taskId} transitionFinished=${it.finished} failure=${it.failure} " +
                "remainingMs=${it.deadline - SystemClock.uptimeMillis()}")
        }
        pw.println("$prefix  retiredStages=${retiredStages.map { it.mRootTaskInfo?.taskId }}")
        val model = layout ?: pairModel() ?: return
        val rectangles = bounds(model)?.leaves
        model.getLeaves().forEach { leaf ->
            val task = organizer.getRunningTaskInfo(leaf.taskId)
            pw.println("$prefix  leaf=${leaf.id.value} task=${leaf.taskId} " +
                "bounds=${rectangles?.get(leaf.id)} min=${task?.minWidth}x${task?.minHeight} " +
                "columns=${model.isThreeColumns} columnGroup=${model.threeColumnGroup} " +
                "columnPairFirst=${model.columnPairFirst}")
        }
    }

    val isActive: Boolean get() = layout != null
    val isBusy: Boolean get() = keyguard || insertion != null || pending != null ||
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
        setRecentsAnimating(true)
        clearStripZone()
        val parent = host.rootInfo()?.token ?: return
        dividers.values.forEach { split ->
            val leash = split.dividerLeash?.takeIf { it.isValid } ?: return@forEach
            val rect = Rect()
            split.getDividerLeashBounds(rect)
            rect.offset(split.rootBounds.left, split.rootBounds.top)
            // Recents owns and releases its copy, as it does for the ordinary pair divider.
            val change = TransitionInfo.Change(null,
                SurfaceControl(leash, "MultiSplitCoordinator.startRecents"))
            change.setParent(parent)
            change.setStartAbsBounds(rect)
            change.setEndAbsBounds(rect)
            change.setMode(TRANSIT_TO_BACK)
            change.setFlags(FLAG_IS_DIVIDER_BAR)
            info.addChange(change)
        }
    }

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

    fun cancelRecents() {
        setRecentsAnimating(false)
        syncQueue.runInSync {
            restoreAnimationBranches(it)
            applySurfaceChanges(it)
        }
        host.rootInfo()?.let {
            organizer.applyTransaction(WindowContainerTransaction()
                .setReparentLeafTaskIfRelaunch(it.token, true))
        }
        setDividerVisible(host.rootInfo()?.isVisible == true, null)
        recentsTransitions.clear()
    }

    private fun restoreBusy(): Boolean = keyguard || insertion != null || pending != null || interacting ||
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
        val membersAlreadyPlaced = isActive && layout?.getLeaves()?.map { it.taskId }?.toSet() ==
            group.members.map { it.taskId }.toSet()
        if (isActive && !membersAlreadyPlaced) exit(-1, wct)
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
            val stage = stages.getValue(leaf).mRootTaskInfo
            if (!membersAlreadyPlaced || task.parentTaskId != stage.taskId) {
                wct.reparent(task.token, stage.token, true)
                    .setWindowingMode(task.token, WINDOWING_MODE_UNDEFINED)
                    .setBounds(task.token, Rect())
            }
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

    fun hasRestoreRemote(transition: IBinder): Boolean = restoreRemotes.containsKey(transition)

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
        setRecentsAnimating(false)
        restoreAnimationBranches(t)
        setDividerVisible(showGroup, t)
        if (showGroup) {
            applyTasks(wct)
            wct.reorder(host.rootInfo()!!.token, true)
            stages.values.forEach { stage ->
                stage.mRootLeash?.takeIf { it.isValid }?.let { t.show(it).setAlpha(it, 1f) }
                stage.restoreChildSurfaces(t)
            }
        } else {
            host.rootInfo()?.let { wct.setReparentLeafTaskIfRelaunch(it.token, true) }
        }
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
        override fun onRootTaskInfoChanged(stage: StageTaskListener) { onStageChanged() }
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

    private fun canInsertColumn(model: MultiSplitLayout): Boolean {
        val configuration = displays.getDisplayContext(host.rootInfo()!!.displayId)
            ?.resources?.configuration ?: return false
        return model.getLeaves().size == 2 && model.root.orientation == Orientation.LEFT_RIGHT &&
            configuration.smallestScreenWidthDp >= 600 && host.capacity() > 2 &&
            displayBounds().width() > displayBounds().height()
    }

    private fun hasInsertionBudget(model: MultiSplitLayout): Boolean {
        val tasks = organizer.getRunningTasks(host.rootInfo()!!.displayId).orEmpty()
        val split = com.android.wm.shell.common.MultitaskingWindowLimit.splitLeaves(tasks)
        val floating = tasks.filter { it.isFloatingWindow && (it.isVisible || it.isVisibleRequested) }
            .map { it.taskId }
        return (model.getLeaves().map { it.taskId } + split + floating).distinct().size <
            com.android.wm.shell.common.MultitaskingWindowLimit.MAX_WINDOWS
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

    /** Splits the longer axis, with the new app below or on the layout's end side. */
    fun getBelowSlot(taskId: Int, canMutate: Boolean = true): AppDragSlot? =
        belowSlot(taskId, canMutate, true)

    /** The handle action prefers a third column while keeping add-below callers unchanged. */
    fun getAddSlot(taskId: Int, canMutate: Boolean = true): AppDragSlot? =
        belowSlot(taskId, canMutate, true, true) ?: belowSlot(taskId, canMutate, true)

    fun getNewWindowSlot(taskId: Int, canMutate: Boolean): AppDragSlot? =
        getAddSlot(taskId, canMutate) ?:
            belowSlot(taskId, canMutate, true, normalize = true)

    /** Session identity is independent of a transient unrelated transition. */
    fun getBelowSlotForValidation(taskId: Int): AppDragSlot? =
        belowSlot(taskId, true, false, true) ?: belowSlot(taskId, true, false)

    private fun belowSlot(taskId: Int, canMutate: Boolean, requireReady: Boolean,
        columns: Boolean = false, normalize: Boolean = false): AppDragSlot? {
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
        if (!hasInsertionBudget(model)) return reject("Window budget")
        val source = bounds(model)?.leaves?.get(leaf.id) ?: return reject("Source bounds")
        if (columns && !canInsertColumn(model)) return reject("Columns unavailable")
        val insertion = if (normalize) insertionModel(model, leaf.id) ?: return reject("At capacity")
            else model
        val below = if (columns) false else insertion.insertionAxis(leaf.id)?.let {
            it == Orientation.TOP_BOTTOM
        } ?: return reject("At capacity")
        val first = !below && displays.getDisplayContext(host.rootInfo()!!.displayId)
            ?.resources?.configuration?.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val proposed = candidate(model, leaf.id, first, columns)
            ?: return reject("Minimum leaf size")
        existingTaskRejection(proposed)?.let { return reject(it) }
        val rect = bounds(proposed)?.leaves?.get(LeafId(nextLeaf)) ?: return reject("Target bounds")
        // Root creation is asynchronous; preview eligibility must not depend on its callbacks.
        ensureSpares()
        lastBelowRejection = "task=$taskId None"
        return AppDragSlot(position(leaf.id, model), nextLeaf, rect, rect, false,
            leaf.id.value, first, revision, below, columns)
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
        if (!hasInsertionBudget(model)) return emptyList()
        return model.getLeaves().flatMap { leaf ->
            val source = calculated.visibleLeaves.getValue(leaf.id)
            val axis = model.insertionAxis(leaf.id)
            if (axis == null) return@flatMap listOf(AppDragSlot(position(leaf.id, model),
                leaf.id.value, source, source, true, leaf.id.value, false, revision))
            val columns = if (canInsertColumn(model)) listOf(true, false).mapNotNull { first ->
                val added = candidate(model, leaf.id, first, true) ?: return@mapNotNull null
                if (!fitsExistingTasks(added)) return@mapNotNull null
                val preview = bounds(added)?.leaves?.get(LeafId(nextLeaf)) ?: return@mapNotNull null
                val hit = Rect(source)
                if (first) hit.right = source.left + source.width() / 4
                else hit.left = source.right - source.width() / 4
                AppDragSlot(position(leaf.id, model), nextLeaf, preview, hit, false,
                    leaf.id.value, first, revision, false, true)
            } else emptyList()
            val rows = listOf(true, false).mapNotNull { first ->
                val added = candidate(model, leaf.id, first) ?: return@mapNotNull null
                if (!fitsExistingTasks(added)) return@mapNotNull null
                val preview = bounds(added)?.leaves?.get(LeafId(nextLeaf)) ?: return@mapNotNull null
                val hit = Rect(source)
                if (columns.any { it.newTaskFirst }) hit.left += source.width() / 4
                if (columns.any { !it.newTaskFirst }) hit.right -= source.width() / 4
                if (axis == Orientation.LEFT_RIGHT) {
                    if (first) hit.right = source.centerX() else hit.left = source.centerX()
                } else {
                    if (first) hit.bottom = source.centerY() else hit.top = source.centerY()
                }
                AppDragSlot(position(leaf.id, model), nextLeaf, preview, hit, false,
                    leaf.id.value, first, revision)
            }
            columns + rows
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
    fun startIntent(intent: PendingIntent, fillIn: Intent?, options: Bundle?, slot: AppDragSlot): Boolean =
        insert(slot, options, true) { wct, opts, _ -> wct.sendPendingIntent(intent, fillIn, opts.toBundle()) }

    /** Replacement addresses a whole existing leaf, including an unsplit half. */
    fun getReplacementSlot(taskId: Int): AppDragSlot? {
        if (isBusy || !isVisible || imeTop != null) return null
        val model = layout ?: return null
        val leaf = model.getLeaves().firstOrNull { it.taskId == taskId } ?: return null
        val calculated = bounds(model) ?: return null
        val rect = calculated.leaves[leaf.id] ?: return null
        return AppDragSlot(position(leaf.id, model), leaf.id.value, rect, calculated.visibleLeaves.getValue(leaf.id), true,
            leaf.id.value, false, revision)
    }

    fun startTask(taskId: Int, slot: AppDragSlot): Boolean {
        if ((layout ?: pairModel())?.getLeaves()?.any { it.taskId == taskId } == true) return false
        return insert(slot, null, true) { wct, opts, _ ->
            wct.startTask(taskId, opts.toBundle())
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

    private fun insert(slot: AppDragSlot, options: Bundle?, awaitTask: Boolean = false,
        launch: (WindowContainerTransaction, ActivityOptions, StageTaskListener) -> Unit): Boolean {
        if (isBusy) return rejectInsertion("Busy")
        if (imeTop != null) return rejectInsertion("IME")
        if (slot.layoutVersion != revision) return rejectInsertion("Stale revision ${slot.layoutVersion}")
        if (!isActive && !host.canEnter()) return rejectInsertion("Pair unavailable or busy")
        val model = layout ?: pairModel() ?: return rejectInsertion("Pair model unavailable")
        val targetId = LeafId(slot.leafId)
        if (model.getLeaves().none { it.id == targetId }) return rejectInsertion("Unknown leaf $targetId")
        val inserting = !slot.occupied
        if (inserting && host.capacity() < 4) return rejectInsertion("Capacity")
        if (inserting && model.getLeaves().size >= host.capacity()) return rejectInsertion("At capacity")
        if (inserting && !hasInsertionBudget(model)) return rejectInsertion("Window budget")
        if (inserting && !ensureSpares()) return rejectInsertion("Spare roots not ready")
        val proposed = if (inserting) candidate(model, targetId, slot.newTaskFirst, slot.threeColumns)
            ?: return rejectInsertion("Minimum leaf size") else model
        val expected = bounds(proposed)?.leaves?.get(if (inserting) LeafId(nextLeaf) else targetId)
        if (expected != slot.bounds) return rejectInsertion("Stale bounds expected=$expected slot=${slot.bounds}")
        existingTaskRejection(proposed)?.let { return rejectInsertion(it) }
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
        val beforeStages = stages.toMap()
        val target = if (inserting) {
            val stage = pool.firstOrNull { it !in stages.values && it.mHasRootTask && reusable(it) }
                ?: return rejectInsertion("No free stage root")
            stages[LeafId(nextLeaf)] = stage
            nextLeaf++
            nextDivider++
            stage
        } else stages.getValue(targetId)
        // Stage history must not resurface after the leaf's new app closes.
        val previousTasks = ((0 until target.mChildrenTaskInfo.size())
            .map { target.mChildrenTaskInfo.keyAt(it) } + childTasks(target).map { it.taskId }).toSet()
        target.evictAllChildren(wct)
        // Evict unreported history too, before a new cold launch can enter this stage.
        wct.reparentTasks(target.mRootTaskInfo.token, null, null, null, false)
        val opts = ActivityOptions.fromBundle(options ?: ActivityOptions.makeBasic().toBundle())
        opts.setLaunchRootTask(target.mRootTaskInfo.token)
        opts.setLaunchWindowingMode(android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW)
        opts.setLaunchFloatingWindow(false)
        opts.setLaunchDisplayId(host.rootInfo()!!.displayId)
        opts.setDisallowEnterPictureInPictureWhileLaunching(true)
        opts.setPendingIntentBackgroundActivityStartMode(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
        // A nested stage is a parent, not the outer root used by WM's launch-bounds path.
        opts.launchBounds = null
        layout = proposed
        revision++
        // Cold tasks must be born into an adjacent, foreground stage before WM resumes them.
        applyTasks(wct)
        launch(wct, opts, target)
        val waiting = if (awaitTask) {
            PendingInsertion(if (inserting) LeafId(nextLeaf - 1) else targetId,
                target, model, beforeStages, previousTasks).also {
                insertion = it
                it.timeout = Runnable {
                    if (insertion === it) {
                        if (it.taskId != null && !it.finished) requestInsertionRollback("Transition timed out")
                        else if (!probeInsertion()) requestInsertionRollback("No task arrived")
                    }
                }
                it.probe = Runnable {
                    if (insertion === it && it.failure == null && !probeInsertion()) {
                        executor.executeDelayed(it.probe, INSERTION_PROBE_MS)
                    }
                }
            }
        } else null
        try {
            pending = transitions.startTransition(TRANSIT_OPEN, wct, this)
        } catch (e: RuntimeException) {
            if (waiting != null) {
                val rollback = WindowContainerTransaction()
                rollbackInsertion(waiting, "Launch failed: ${e.javaClass.simpleName}", rollback)
                organizer.applyTransaction(rollback)
                syncDividers()
                syncQueue.runInSync { applySurfaceChanges(it) }
                publishGroup()
                host.changed()
            }
            return rejectInsertion("Launch failed: ${e.javaClass.simpleName}")
        }
        waiting?.let {
            it.transition = pending
            armInsertionTimeout(it, INSERTION_TRANSITION_TIMEOUT_MS)
            executor.executeDelayed(it.probe, INSERTION_PROBE_MS)
        }
        setInteractive(false)
        lastInsertRejection = if (waiting == null) "None"
            else "Awaiting task in stage=${target.mRootTaskInfo.taskId}"
        return true
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
        val replacing = waiting.stage in waiting.beforeStages.values
        if (!replacing) retiredStages.add(waiting.stage)
        resetPreparedStage(waiting.stage, wct)
        if (replacing) {
            waiting.previousTasks.mapNotNull { organizer.getRunningTaskInfo(it) }.forEach { task ->
                wct.reparent(task.token, waiting.stage.mRootTaskInfo.token, true)
                    .setWindowingMode(task.token, WINDOWING_MODE_UNDEFINED)
                    .setBounds(task.token, Rect())
            }
        }
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

    private fun leafOfTask(taskId: Int): LeafId? {
        val parentId = organizer.getRunningTaskInfo(taskId)?.parentTaskId
        return stages.entries.firstOrNull {
            it.value.containsTask(taskId) || it.value.mRootTaskInfo.taskId == parentId
        }?.key
    }

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

    fun updateTaskBounds(wct: WindowContainerTransaction): Boolean {
        val calculated = bounds(layout ?: return false) ?: return false
        if (stages.all { (id, stage) ->
                stage.mRootTaskInfo.configuration.windowConfiguration.bounds ==
                    calculated.leaves.getValue(id)
            }) return false
        applyTasks(wct, updateHierarchy = false)
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

    fun moveToFullscreen(taskId: Int) {
        if (!isActive || isBusy) return
        val wct = WindowContainerTransaction()
        exit(taskId, wct)
        groups.remove(taskId)
        pending = transitions.startTransition(TRANSIT_CHANGE, wct, this)
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

    /** Removes dead leaves after organizer callbacks settle, including aborted launches. */
    fun onTaskChanged(stage: StageTaskListener, taskId: Int, present: Boolean) {
        unreportedTasks[stage]?.remove(taskId)
        if (stage in retiredStages && stage !in stages.values) {
            if (present) {
                val wct = WindowContainerTransaction()
                resetPreparedStage(stage, wct)
                organizer.applyTransaction(wct)
            }
            return
        }
        if (!isActive || stage !in stages.values) return
        if (!present && insertion != null && insertion?.stage !== stage) {
            requestInsertionRollback("Group member vanished")
        }
        insertion?.takeIf { it.stage === stage }?.let {
            if (!present && taskId !in it.previousTasks) requestInsertionRollback("Task vanished")
            else if (present) probeInsertion()
        }
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

    fun onStageChanged() {
        unreportedTasks.toMap().forEach { (stage, ids) ->
            val current = childTasks(stage).associateBy { it.taskId }
            ids.toList().forEach { id ->
                val task = current[id]
                if (task == null) {
                    stage.mChildrenTaskInfo.remove(id)
                    ids.remove(id)
                    groups.remove(id)
                    if (insertion?.stage === stage) requestInsertionRollback("Unreported task vanished")
                    else scheduleReconcile()
                } else stage.mChildrenTaskInfo.put(id, task)
            }
            if (ids.isEmpty()) unreportedTasks.remove(stage)
        }
        retiredStages.filter { it.mHasRootTask && it !in stages.values }.forEach {
            if (childTasks(it).isNotEmpty()) {
                val wct = WindowContainerTransaction()
                resetPreparedStage(it, wct)
                organizer.applyTransaction(wct)
            }
        }
        probeInsertion()
        if (!isActive) return
        val active = stages.values.toList()
        if (active.all { it.mRootTaskInfo?.isVisible == true }) visible = true
        if (active.all { it.mRootTaskInfo?.isVisible != true } &&
            (insertion == null || host.rootInfo()?.isVisible != true)) {
            visible = false
            if (!keyguard) requestInsertionRollback("Group hidden")
        }
        setDividerVisible(visible, null)
        host.changed()
    }

    fun setKeyguard(active: Boolean) {
        keyguard = active
        if (active) requestInsertionRollback("Keyguard")
        setDividerVisible(visible, null)
    }

    fun setDividerVisible(show: Boolean, transaction: SurfaceControl.Transaction?) {
        visible = show
        if (recentsAnimating) return
        val t = transaction ?: SurfaceControl.Transaction()
        dividers.values.forEach { split -> split.dividerLeash?.takeIf { it.isValid }?.let {
            if (show && !keyguard) t.show(it).setAlpha(it, 1f) else t.hide(it)
        } }
        if (transaction == null) { t.apply(); t.close() }
        syncStripZone()
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
                // Insets must not bring tasks forward and dismiss the overlay that changed them.
                applyTasks(wct, updateHierarchy = false)
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
            applyTasks(wct, updateHierarchy = false)
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

    override fun onTransitionConsumed(transition: IBinder, aborted: Boolean,
        finish: SurfaceControl.Transaction?) {
        consumeRestoreRemote(transition, aborted)
        restoreHandlers.remove(transition)?.let { handler ->
            handler.onTransitionConsumed(transition, aborted, finish)
            if (finish != null) restoreAnimationBranches(finish)
            else syncQueue.runInSync { restoreAnimationBranches(it) }
            restoreInfos.remove(transition)?.releaseAllSurfaces()
        }
        finishInsertion(transition, aborted)
        if (recentsAnimating && transition in recentsTransitions) cancelRecents()
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
        probeInsertion(info)
        if (!isActive) return
        cancelDrag()
        setInteractive(false)
        info.changes.firstOrNull { it.taskInfo?.taskId == host.rootInfo()?.taskId }?.let {
            if (TransitionUtil.isOpeningType(it.mode) || TransitionUtil.isClosingType(it.mode)) {
                visible = TransitionUtil.isOpeningType(it.mode)
                host.rootInfo()?.let { root -> organizer.applyTransaction(WindowContainerTransaction()
                    .setReparentLeafTaskIfRelaunch(root.token, !visible)) }
            }
        }
        val displayId = host.rootInfo()?.displayId
        if (displayId == null || !TransitionUtil.isHomeTransitionEndingOnDisplay(info, displayId)) {
            setDividerVisible(visible, start)
        }
        syncDividers(finish)
        applySurfaceChanges(finish)
    }

    override fun onTransitionMerged(merged: IBinder, playing: IBinder) {
        runningTransitions.remove(merged)
        readyInfos.remove(merged)
        if (pending == merged) pending = playing
        insertion?.takeIf { it.transition == merged }?.transition = playing
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
        finishInsertion(transition, aborted)
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
        syncQueue.runInSync { restoreAnimationBranches(it) }
        restoreHandlers.values.toList().forEach { it.finishAnimation() }
        insertion?.let { clearInsertion(it) }
        retiredStages.clear()
        unreportedTasks.clear()
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
        setRecentsAnimating(false)
        clearStripZone()
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
