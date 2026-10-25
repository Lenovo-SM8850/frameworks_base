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

}
