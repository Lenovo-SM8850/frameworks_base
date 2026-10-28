/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo
import android.graphics.PointF
import com.android.wm.shell.pinnedlayer.phone.FloatingWindowGeometry
import kotlin.math.max
import kotlin.math.roundToInt
import android.graphics.Rect
import android.hardware.display.DisplayTopology
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Choreographer
import android.view.DisplayInfo
import android.view.Surface
import android.view.SurfaceControl
import android.view.ViewConfiguration
import android.view.WindowManager
import android.window.DesktopExperienceFlags
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerTransaction
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.jank.Cuj
import com.android.internal.jank.InteractionJankMonitor
import com.android.window.flags.Flags
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.SurfaceFrameRate
import com.android.wm.shell.common.MultiDisplayDragMoveBoundsCalculator
import com.android.wm.shell.common.MultiDisplayDragMoveIndicatorController
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.InputMethod
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.ResizeTrigger
import com.android.wm.shell.desktopmode.DesktopModeEventLogger.Companion.getInputMethodType
import com.android.wm.shell.desktopmode.DesktopTasksController
import com.android.wm.shell.desktopmode.DesktopUserRepositories
import com.android.wm.shell.desktopmode.data.DesktopRepository
import com.android.wm.shell.desktopmode.freeformCaptionInsets
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.shared.desktopmode.DesktopState
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_BOTTOM
import com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_LEFT
import com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_RIGHT
import com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_TOP
import java.util.concurrent.TimeUnit

/**
 * A task positioner that also takes into account resizing a
 * [com.android.wm.shell.windowdecor.ResizeVeil] and dragging move across multiple displays.
 * - If the drag is resizing the task, we resize the veil instead.
 * - If the drag is repositioning, we consider multi-display topology if needed, and update in the
 *   typical manner.
 */
class MultiDisplayVeiledResizeTaskPositioner(
    private val taskOrganizer: ShellTaskOrganizer,
    private val windowDecoration: WindowDecorationWrapper,
    private val displayController: DisplayController,
    private val transactionSupplier: () -> SurfaceControl.Transaction,
    private val transitions: Transitions,
    private val interactionJankMonitor: InteractionJankMonitor,
    @ShellMainThread private val handler: Handler,
    private val multiDisplayDragMoveIndicatorController: MultiDisplayDragMoveIndicatorController,
    private val desktopState: DesktopState,
    private val desktopTasksController: DesktopTasksController,
    private val desktopUserRepositories: DesktopUserRepositories,
) :
    TaskPositioner,
    Transitions.TransitionHandler,
    Transitions.TransitionObserver,
    DisplayController.OnDisplaysChangedListener {

    private val dragEventListeners =
        mutableListOf<DragPositioningCallbackUtility.DragEventListener>()
    private val stableBounds = Rect()
    private val taskBoundsAtDragStart = Rect()
    private val repositionStartPoint = PointF()
    private val repositionTaskBounds = Rect()
    private val changeBoundsResult = DragPositioningCallbackUtility.ChangeBoundsResult()
    private val isResizing: Boolean
        get() =
            (ctrlType and DragPositioningCallback.CTRL_TYPE_TOP) != 0 ||
                (ctrlType and DragPositioningCallback.CTRL_TYPE_BOTTOM) != 0 ||
                (ctrlType and DragPositioningCallback.CTRL_TYPE_LEFT) != 0 ||
                (ctrlType and DragPositioningCallback.CTRL_TYPE_RIGHT) != 0

    @DragPositioningCallback.CtrlType private var ctrlType = 0
    private var isResizingOrAnimatingResize = false
    @Surface.Rotation private var rotation = 0
    private var startDisplayId = 0
    private var hasMoved = false
    private val floatingLogicalBounds = Rect()
    private var floatingAspectSnap = FloatingWindowGeometry.AspectSnapState(1f, 1f)
    private var floatingNaturalWidth = 1
    private var floatingNaturalHeight = 1
    private var floatingStartScale = 1f
    private var floatingPinchSpan = 0f
    private val floatingPinchCenter = PointF()
    private var floatingMinimumScale = .5f
    private var floatingMinimumVisibleSize = 0
    private var floatingMinWidth = 1
    private var floatingMinHeight = 1
    private val floatingBaseBounds = Rect()
    private var floatingPreviewActive = false
    private var floatingDragActive = false
    private var floatingDragCancelled = false
    private var floatingObserverRegistered = false
    private var pendingFloatingTransition: IBinder? = null
    private var pendingFloatingChange: TransitionInfo.Change? = null
    private val floatingResizeScratch = FloatingWindowGeometry.ScaledResize(Rect(), Rect(), 1f, false)
    private val floatingPreviewBounds = Rect()
    private var floatingPreviewScale = 1f
    private var floatingPreviewVeiled = false
    private val floatingAppliedBounds = Rect()
    private var floatingAppliedScale = Float.NaN
    private var floatingAppliedVeiled = false
    private var floatingFramePending = false
    private var floatingPreviewTransaction: SurfaceControl.Transaction? = null
    private var lastFloatingX = Float.NaN
    private var lastFloatingY = Float.NaN
    private var floatingMovePending = false
    private var floatingEdgeMove = false
    private val miniPreview = FloatingMiniPreview(desktopTasksController::createFloatingMiniPreview)
    private var miniPreviewCancelled = false
    private val resizeFeedback = FloatingTargetFeedback()
    private var allowFloatingFeedback = false
    private val floatingFrameCallback = Choreographer.FrameCallback {
        if (floatingMovePending) flushFloatingMove() else flushFloatingResizePreview()
    }
    private var closing = false
    private var floatingResize: FloatingWindowGeometry.ScaledResize? = null
    private val displayIds = mutableSetOf<Int>()
    private var hasMovedTaskSurfaceOffScreen = false
    private var resizeTrigger = ResizeTrigger.UNKNOWN_RESIZE_TRIGGER
    private var inputMethod = InputMethod.UNKNOWN_INPUT_METHOD
    private var shouldRestoreBoundsOnMove: Boolean = false
    private var pendingResizeTransition: IBinder? = null
    private lateinit var desktopRepository: DesktopRepository

    constructor(
        taskOrganizer: ShellTaskOrganizer,
        windowDecoration: WindowDecorationWrapper,
        displayController: DisplayController,
        transitions: Transitions,
        interactionJankMonitor: InteractionJankMonitor,
        @ShellMainThread handler: Handler,
        multiDisplayDragMoveIndicatorController: MultiDisplayDragMoveIndicatorController,
        desktopState: DesktopState,
        desktopTasksController: DesktopTasksController,
        desktopUserRepositories: DesktopUserRepositories,
    ) : this(
        taskOrganizer,
        windowDecoration,
        displayController,
        { SurfaceControl.Transaction() },
        transitions,
        interactionJankMonitor,
        handler,
        multiDisplayDragMoveIndicatorController,
        desktopState,
        desktopTasksController,
        desktopUserRepositories,
    )

    init {
        displayController.addDisplayWindowListener(this)
    }

    override fun onDragPositioningStart(
        ctrlType: Int,
        displayId: Int,
        x: Float,
        y: Float,
        inputMethodType: Int,
    ): Rect {
        miniPreview.close()
        miniPreviewCancelled = false
        resizeFeedback.reset()
        allowFloatingFeedback = true
        windowDecoration.floatingMiniPreviewCancellation = ::onDragPositioningCancel
        floatingEdgeMove = windowDecoration.taskInfo.isFloatingWindow &&
            Integer.bitCount(ctrlType) == 1
        this.ctrlType = if (floatingEdgeMove) DragPositioningCallback.CTRL_TYPE_UNDEFINED else ctrlType
        startDisplayId = displayId
        hasMovedTaskSurfaceOffScreen = false
        val task = windowDecoration.taskInfo
        floatingDragActive = task.isFloatingWindow
        if (floatingDragActive) SurfaceFrameRate.setInteracting(this, true)
        floatingLogicalBounds.set(task.configuration.windowConfiguration.bounds)
        floatingStartScale = task.floatingWindowScale
        if (task.isFloatingWindow) {
            val current = floatingLogicalBounds.width().toFloat() /
                floatingLogicalBounds.height().coerceAtLeast(1)
            val original = desktopTasksController.originalFloatingAspect(task).takeIf { it > 0f }
                ?: current
            floatingAspectSnap = FloatingWindowGeometry.AspectSnapState(original, current)
            val info = DisplayInfo()
            val display = displayController.getDisplay(task.displayId)
            val layout = displayController.getDisplayLayout(task.displayId)
            if (display?.getDisplayInfo(info) == true) {
                floatingNaturalWidth = info.naturalWidth
                floatingNaturalHeight = info.naturalHeight
            } else {
                floatingNaturalWidth = layout?.width() ?: 1
                floatingNaturalHeight = layout?.height() ?: 1
            }
        }
        floatingBaseBounds.set(task.floatingWindowRestoreBounds)
        floatingResize = null
        pendingFloatingChange = null
        floatingDragCancelled = false
        lastFloatingX = Float.NaN
        lastFloatingY = Float.NaN
        floatingAppliedScale = Float.NaN
        taskBoundsAtDragStart.set(task.presentationBounds)
        if (task.isFloatingWindow) {
            if (!floatingObserverRegistered) {
                transitions.registerObserver(this)
                floatingObserverRegistered = true
            }
            windowDecoration.floatingWindowTransitionListener = ::cancelFloatingDragForTransition
            if (isResizing) {
                val (minWidth, minHeight) = floatingMinimums()
                floatingMinWidth = minWidth
                floatingMinHeight = minHeight
                taskOrganizer.applyTransaction(WindowContainerTransaction().setDragResizing(task.token, true))
            }
        }
        logD(
            TAG,
            windowDecoration.taskInfo.taskId,
            "onDragPositioningStart: taskId=%d, ctrlType=%d, displayId=%d, x=%f, y=%f, " +
                "taskBounds=%s",
            windowDecoration.taskInfo.taskId,
            ctrlType,
            displayId,
            x,
            y,
            taskBoundsAtDragStart,
        )

        if (!windowDecoration.taskInfo.isFloatingWindow &&
            DesktopExperienceFlags.ENABLE_BOUNDS_RESTORING_ON_DRAG_EXIT.isTrue) {
            desktopRepository = desktopUserRepositories.getProfile(windowDecoration.taskInfo.userId)
            shouldRestoreBoundsOnMove =
                desktopRepository.hasBoundsBeforeSnapOrMaximize(windowDecoration.taskInfo)
            if (shouldRestoreBoundsOnMove) {
                logD(
                    TAG,
                    windowDecoration.taskInfo.taskId,
                    "onDragPositioningStart: shouldRestoreBoundsOnMove",
                )
            }
        }
        repositionStartPoint[x] = y
        hasMoved = false
        inputMethod = getInputMethodType(inputMethodType)
        if (isResizing) {
            resizeTrigger =
                if (
                    ctrlType == CTRL_TYPE_BOTTOM ||
                        ctrlType == CTRL_TYPE_TOP ||
                        ctrlType == CTRL_TYPE_RIGHT ||
                        ctrlType == CTRL_TYPE_LEFT
                ) {
                    ResizeTrigger.EDGE
                } else {
                    ResizeTrigger.CORNER
                }
            for (dragEventListener in dragEventListeners) {
                dragEventListener.onDragResizeStarted(
                    windowDecoration.taskInfo.taskId,
                    resizeTrigger,
                    inputMethod,
                    taskBoundsAtDragStart,
                )
            }
            // Events within the task bounds are handled by WMS#onPointerDownOutsideFocusLocked and
            // WMS moves the focus to the touched window.
            if (
                Flags.moveTaskToFrontOnDragResizingBugfix() &&
                    !taskBoundsAtDragStart.contains(x.toInt(), y.toInt()) &&
                    !windowDecoration.hasGlobalFocus
            ) {
                desktopTasksController.moveTaskToFront(windowDecoration.taskInfo)
            }
            // Capture CUJ for re-sizing window in DW mode.
            interactionJankMonitor.begin(
                createLongTimeoutJankConfigBuilder(Cuj.CUJ_DESKTOP_MODE_RESIZE_WINDOW)
            )
        }
        repositionTaskBounds.set(taskBoundsAtDragStart)
        this.rotation = windowDecoration.taskInfo.configuration.windowConfiguration.displayRotation
        displayController
            .getDisplayLayout(windowDecoration.taskInfo.displayId)
            ?.getStableBounds(stableBounds)
        if (task.isFloatingWindow && !stableBounds.isEmpty) {
            val layout = displayController.getDisplayLayout(task.displayId)
            if (layout != null) {
                floatingMinimumScale = minOf(floatingStartScale,
                    com.android.wm.shell.pinnedlayer.phone.FloatingMiniState.scale(
                        floatingLogicalBounds, stableBounds, minOf(layout.width(), layout.height()),
                        (8 * windowDecoration.decorWindowContext.resources.displayMetrics.density).toInt()))
                floatingMinimumVisibleSize = (minOf(floatingLogicalBounds.width(),
                    floatingLogicalBounds.height()) * floatingMinimumScale).roundToInt()
            }
        }
        return Rect(repositionTaskBounds)
    }

    override fun onDragPositioningMove(displayId: Int, x: Float, y: Float): Rect {
        check(Looper.myLooper() == handler.looper) {
            "This method must run on the shell main thread."
        }
        motionEventLogD(
            TAG,
            windowDecoration.taskInfo.taskId,
            "onDragPositioningMove: displayId=%d, x=%f, y=%f",
            displayId,
            x,
            y,
        )
        if (floatingDragCancelled) return Rect(repositionTaskBounds)
        if (windowDecoration.taskInfo.isFloatingWindow) {
            if (isResizing) return moveFloatingResize(x, y)
            DragPositioningCallbackUtility.updateTaskBounds(repositionTaskBounds,
                taskBoundsAtDragStart, repositionStartPoint, x, y)
            if (!miniPreviewCancelled) {
                miniPreview.updateFeedback(windowDecoration.taskInfo,
                    windowDecoration.pinnedLayerController?.getMiniDragTarget(windowDecoration.taskInfo,
                        taskBoundsAtDragStart, repositionTaskBounds),
                    allowFloatingFeedback && inputMethod == InputMethod.TOUCH,
                    windowDecoration::performFloatingTargetHapticFeedback)
            }
            if (!floatingMovePending) {
                floatingMovePending = true
                Choreographer.getInstance().postFrameCallback(floatingFrameCallback)
            }
            return Rect(repositionTaskBounds)
        }
        // If the window needs to be resized to its bounds before snap or maximize, then wait until
        // resizing is complete before moving it.
        if (
            DesktopExperienceFlags.ENABLE_BOUNDS_RESTORING_ON_DRAG_EXIT.isTrue &&
                pendingResizeTransition != null
        ) {
            logD(
                TAG,
                windowDecoration.taskInfo.taskId,
                "onDragPositioningMove: deferring move due to ongoing bounds restore transition",
            )
            return taskBoundsAtDragStart
        }
        val delta = DragPositioningCallbackUtility.calculateDelta(x, y, repositionStartPoint)
        DragPositioningCallbackUtility.changeBounds(
            ctrlType,
            repositionTaskBounds,
            taskBoundsAtDragStart,
            stableBounds,
            delta,
            displayController,
            windowDecoration,
            desktopState.canEnterDesktopMode,
            changeBoundsResult,
        )
        if (isResizing && changeBoundsResult.boundsChanged) {
            if (!isResizingOrAnimatingResize) {
                for (dragEventListener in dragEventListeners) {
                    dragEventListener.onDragMove(windowDecoration.taskInfo.taskId)
                }
                windowDecoration.showResizeVeil(repositionTaskBounds)
                isResizingOrAnimatingResize = true
            } else {
                windowDecoration.updateResizeVeil(repositionTaskBounds)
                // Remove the stored previous bounds if user manually resizes the window.
                if (!windowDecoration.taskInfo.isFloatingWindow &&
            DesktopExperienceFlags.ENABLE_BOUNDS_RESTORING_ON_DRAG_EXIT.isTrue) {
                    desktopRepository.removeBoundsBeforeSnapOrMaximize(
                        windowDecoration.taskInfo.taskId
                    )
                }
            }
        } else if (ctrlType == DragPositioningCallback.CTRL_TYPE_UNDEFINED) {
            // Begin window drag CUJ instrumentation only when drag position moves.
            interactionJankMonitor.begin(
                createLongTimeoutJankConfigBuilder(Cuj.CUJ_DESKTOP_MODE_DRAG_WINDOW)
            )

            val t = transactionSupplier()
            val startDisplayLayout = displayController.getDisplayLayout(startDisplayId)
            val currentDisplayLayout = displayController.getDisplayLayout(displayId)

            if (startDisplayLayout == null || currentDisplayLayout == null) {
                logD(
                    TAG,
                    windowDecoration.taskInfo.taskId,
                    "onDragPositioningMove: falling back to single-display move (startLayout=%s, " +
                        "currentLayout=%s)",
                    startDisplayLayout,
                    currentDisplayLayout,
                )
                // Fall back to single-display drag behavior if any display layout is unavailable.
                DragPositioningCallbackUtility.setPositionOnDrag(
                    windowDecoration,
                    repositionTaskBounds,
                    taskBoundsAtDragStart,
                    repositionStartPoint,
                    t,
                    x,
                    y,
                )
            } else {
                val boundsDp =
                    MultiDisplayDragMoveBoundsCalculator.calculateGlobalDpBoundsForDrag(
                        startDisplayLayout,
                        repositionStartPoint,
                        taskBoundsAtDragStart,
                        currentDisplayLayout,
                        x,
                        y,
                    )
                repositionTaskBounds.set(
                    MultiDisplayDragMoveBoundsCalculator.convertGlobalDpToLocalPxForRect(
                        boundsDp,
                        startDisplayLayout,
                    )
                )

                // If window needs to be resized to its bounds before snap or maximize,
                // then fetch the previous bounds and calculate the restoredBounds bounds.
                if (
                    DesktopExperienceFlags.ENABLE_BOUNDS_RESTORING_ON_DRAG_EXIT.isTrue &&
                        shouldRestoreBoundsOnMove
                ) {
                    val prevBounds =
                        desktopRepository.getBoundsBeforeSnapOrMaximize(
                            windowDecoration.taskInfo.taskId
                        )

                    if (prevBounds != null) {
                        val currentBounds =
                            windowDecoration.taskInfo.configuration.windowConfiguration.bounds
                        val restoredBounds =
                            calculateOnMoveRestoredBounds(prevBounds, currentBounds, x)
                        logD(
                            TAG,
                            windowDecoration.taskInfo.taskId,
                            "onDragPositioningMove: restoredBounds=%s",
                            restoredBounds,
                        )
                        taskBoundsAtDragStart.set(restoredBounds)

                        transitions.registerObserver(this)

                        val wct = WindowContainerTransaction()
                        wct.setBounds(windowDecoration.taskInfo.token, taskBoundsAtDragStart)
                        pendingResizeTransition =
                            transitions.startTransition(WindowManager.TRANSIT_CHANGE, wct, this)
                    }
                    shouldRestoreBoundsOnMove = false
                    return taskBoundsAtDragStart
                }

                multiDisplayDragMoveIndicatorController.onDragMove(
                    boundsDp,
                    displayId,
                    startDisplayId,
                    windowDecoration.taskSurface,
                    windowDecoration.taskInfo,
                    displayIds,
                    t,
                )
                // Move the original task surface off-screen to hide it. A mirrored surface is
                // used for the drag indicator on all displays, including the start display.
                // This is necessary for independent opacity control, as a mirror's alpha is
                // capped by its source.
                if (!hasMovedTaskSurfaceOffScreen) {
                    hasMovedTaskSurfaceOffScreen = true
                    t.setPosition(
                        windowDecoration.taskSurface,
                        startDisplayLayout.width().toFloat(),
                        startDisplayLayout.height().toFloat(),
                    )
                }
            }
            t.setFrameTimeline(Choreographer.getInstance().vsyncId)
            t.apply()
        }
        if (!hasMoved) {
            // Update taskbar rounding once the drag/resize has registered a move event - in case
            // the moved task is no longer maximized. Only call this once per resize/drag so we
            // don't call into Launcher with each drag/resize frame to try to update the taskbar.
            desktopTasksController
                .getDesktopScrimController()
                .updateDesktopScrimOnResize(
                    displayId,
                    windowDecoration.taskInfo.taskId,
                    Rect(repositionTaskBounds),
                )
            hasMoved = true
        }
        return Rect(repositionTaskBounds)
    }

    override fun onDragPositioningEnd(displayId: Int, x: Float, y: Float): Rect {
        allowFloatingFeedback = false
        resizeFeedback.reset()
        windowDecoration.floatingMiniPreviewCancellation = null
        miniPreview.close()
        logD(
            TAG,
            windowDecoration.taskInfo.taskId,
            "onDragPositioningEnd: taskId=%d, displayId=%d, x=%f, y=%f",
            windowDecoration.taskInfo.taskId,
            displayId,
            x,
            y,
        )
        if (floatingDragCancelled) {
            ctrlType = DragPositioningCallback.CTRL_TYPE_UNDEFINED
            taskBoundsAtDragStart.setEmpty()
            return Rect(repositionTaskBounds)
        }
        if (windowDecoration.taskInfo.isFloatingWindow) {
            if (isResizing) return endFloatingResize(x, y)
            cancelFloatingFrame()
            DragPositioningCallbackUtility.updateTaskBounds(repositionTaskBounds,
                taskBoundsAtDragStart, repositionStartPoint, x, y)
            val edgeMove = floatingEdgeMove
            val startBounds = Rect(taskBoundsAtDragStart)
            floatingEdgeMove = false
            taskBoundsAtDragStart.setEmpty()
            ctrlType = DragPositioningCallback.CTRL_TYPE_UNDEFINED
            stopFloatingObserver()
            resetVeilIfVisible()
            interactionJankMonitor.end(Cuj.CUJ_DESKTOP_MODE_DRAG_WINDOW)
            if (edgeMove) {
                windowDecoration.pinnedLayerController?.onDragEnded(windowDecoration.taskSurface,
                    windowDecoration.taskInfo, startBounds, repositionTaskBounds, displayId)
            }
            return Rect(repositionTaskBounds)
        }
        val delta = DragPositioningCallbackUtility.calculateDelta(x, y, repositionStartPoint)
        if (isResizing) {
            val boundsChanged = taskBoundsAtDragStart != repositionTaskBounds
            logD(
                TAG,
                windowDecoration.taskInfo.taskId,
                "onDragPositioningEnd: boundsChanged=%b",
                boundsChanged,
            )
            if (boundsChanged) {
                DragPositioningCallbackUtility.changeBounds(
                    ctrlType,
                    repositionTaskBounds,
                    taskBoundsAtDragStart,
                    stableBounds,
                    delta,
                    displayController,
                    windowDecoration,
                    desktopState.canEnterDesktopMode,
                    changeBoundsResult,
                )
                for (dragEventListener in dragEventListeners) {
                    dragEventListener.onDragResizeEnded(
                        windowDecoration.taskInfo.taskId,
                        resizeTrigger,
                        inputMethod,
                        repositionTaskBounds,
                    )
                }
                windowDecoration.updateResizeVeil(repositionTaskBounds)
                val wct = WindowContainerTransaction()
                wct.setBounds(windowDecoration.taskInfo.token, repositionTaskBounds)
                val captionInsets = windowDecoration.taskInfo.freeformCaptionInsets
                if (!Flags.refactorCaptionSandboxingToCore() && captionInsets != 0) {
                    logD(
                        TAG,
                        windowDecoration.taskInfo.taskId,
                        "onDragPositioningEnd: resetting app bounds for caption insets=%d",
                        captionInsets,
                    )
                    // Reset app bounds if app bounds were overridden.
                    wct.setAppBounds(windowDecoration.taskInfo.token, null)
                }
                val t = transitions.startTransition(WindowManager.TRANSIT_CHANGE, wct, this)
                desktopTasksController.onDragResizeTransitionStarted(t)
            } else {
                // If bounds haven't changed, perform necessary veil reset here as startAnimation
                // won't be called.
                resetVeilIfVisible()
            }
            if (Flags.updateDesktopScrimOnDragResizeEnd()) {
                // Update desktop scrim for b/484100709. This covers drag-resize cases like
                // (maximized ->) unmaximized -> maximized.
                desktopTasksController
                    .getDesktopScrimController()
                    .updateDesktopScrimOnResize(
                        displayId,
                        windowDecoration.taskInfo.taskId,
                        repositionTaskBounds,
                    )
            }
        } else {
            val startDisplayLayout = displayController.getDisplayLayout(startDisplayId)
            val currentDisplayLayout = displayController.getDisplayLayout(displayId)

            if (
                startDisplayId == displayId ||
                    startDisplayLayout == null ||
                    currentDisplayLayout == null
            ) {
                logD(
                    TAG,
                    windowDecoration.taskInfo.taskId,
                    "onDragPositioningEnd: falling back to single-display move (sameDisplay=%b, " +
                        "startLayout=%s, currentLayout=%s)",
                    startDisplayId == displayId,
                    startDisplayLayout,
                    currentDisplayLayout,
                )
                // Fall back to single-display drag behavior if:
                // 1. The drag destination display is the same as the start display. This prevents
                // unnecessary animations caused by minor width/height changes due to DPI scaling.
                // 2. Either the starting or current display layout is unavailable.
                DragPositioningCallbackUtility.updateTaskBounds(
                    repositionTaskBounds,
                    taskBoundsAtDragStart,
                    repositionStartPoint,
                    x,
                    y,
                )
            } else {
                val boundsDp =
                    MultiDisplayDragMoveBoundsCalculator.calculateGlobalDpBoundsForDrag(
                        startDisplayLayout,
                        repositionStartPoint,
                        taskBoundsAtDragStart,
                        currentDisplayLayout,
                        x,
                        y,
                    )
                repositionTaskBounds.set(
                    MultiDisplayDragMoveBoundsCalculator.convertGlobalDpToLocalPxForRect(
                        boundsDp,
                        currentDisplayLayout,
                    )
                )
            }

            interactionJankMonitor.end(Cuj.CUJ_DESKTOP_MODE_DRAG_WINDOW)
        }

        ctrlType = DragPositioningCallback.CTRL_TYPE_UNDEFINED
        taskBoundsAtDragStart.setEmpty()
        repositionStartPoint[0f] = 0f
        hasMovedTaskSurfaceOffScreen = false
        logD(
            TAG,
            windowDecoration.taskInfo.taskId,
            "onDragPositioningEnd: bounds=%s",
            repositionTaskBounds,
        )
        return Rect(repositionTaskBounds)
    }

    override fun close() {
        allowFloatingFeedback = false
        resizeFeedback.reset()
        windowDecoration.floatingMiniPreviewCancellation = null
        miniPreview.close()
        closing = true
        pendingFloatingTransition = null
        pendingFloatingChange = null
        resetVeilIfVisible()
        restoreFloatingPresentation()
        stopFloatingObserver()
        floatingPreviewTransaction?.close()
        floatingPreviewTransaction = null
        displayController.removeDisplayWindowListener(this)
    }

    private fun floatingMinimums(): Pair<Int, Int> {
        val task = windowDecoration.taskInfo
        val density = windowDecoration.decorWindowContext.resources.displayMetrics.density
        val defaultMin = (max(1, task.defaultMinSize) * density).roundToInt()
        return Pair(if (task.minWidth > 0) task.minWidth else defaultMin,
            if (task.minHeight > 0) task.minHeight else defaultMin)
    }

    fun getFloatingHandlePinchBounds() = Rect(repositionTaskBounds)

    fun startFloatingHandlePinch(displayId: Int, span: Float, x: Float, y: Float,
        bounds: Rect = windowDecoration.taskInfo.presentationBounds) {
        onDragPositioningStart(CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM, displayId, x, y,
            DragPositioningCallback.INPUT_METHOD_TYPE_TOUCH)
        floatingBaseBounds.offset(bounds.left - taskBoundsAtDragStart.left,
            bounds.top - taskBoundsAtDragStart.top)
        taskBoundsAtDragStart.set(bounds)
        repositionTaskBounds.set(bounds)
        floatingPinchSpan = span
        floatingPinchCenter.set(x, y)
        lastFloatingX = x
        lastFloatingY = y
    }

    fun moveFloatingHandlePinch(span: Float, x: Float, y: Float) {
        if (!floatingDragActive || floatingDragCancelled || floatingPinchSpan <= 0f) return
        val result = FloatingWindowGeometry.pinch(taskBoundsAtDragStart, floatingLogicalBounds,
            stableBounds, span / floatingPinchSpan, floatingPinchCenter.x, floatingPinchCenter.y,
            x, y, floatingMinWidth, floatingMinHeight, floatingMinimumScale,
            floatingMinimumVisibleSize, floatingResizeScratch)
        updateFloatingResize(result)
    }

    fun endFloatingHandlePinch(cancelled: Boolean) {
        if (floatingPinchSpan <= 0f) return
        floatingPinchSpan = 0f
        if (cancelled || floatingDragCancelled) onDragPositioningCancel()
        else endFloatingResize(lastFloatingX, lastFloatingY)
    }

    private fun moveFloatingResize(x: Float, y: Float): Rect {
        if (x == lastFloatingX && y == lastFloatingY) return Rect(repositionTaskBounds)
        lastFloatingX = x
        lastFloatingY = y
        val dx = x - repositionStartPoint.x
        val dy = y - repositionStartPoint.y
        val result = FloatingWindowGeometry.presetCorner(taskBoundsAtDragStart,
            floatingLogicalBounds, stableBounds, ctrlType, dx, dy,
            floatingMinWidth, floatingMinHeight, floatingMinimumScale, floatingMinimumVisibleSize,
            windowDecoration.taskInfo.topActivityInfo?.minAspectRatio ?: 0f,
            windowDecoration.taskInfo.topActivityInfo?.maxAspectRatio ?: 0f,
            kotlin.math.ceil(windowDecoration.decorWindowContext.resources.displayMetrics.density)
                .toInt(), floatingResizeScratch, floatingNaturalWidth, floatingNaturalHeight,
            floatingAspectSnap,
            ViewConfiguration.get(windowDecoration.decorWindowContext).scaledTouchSlop.toFloat())
        updateFloatingResize(result)
        return Rect(repositionTaskBounds)
    }

    private fun updateFloatingResize(result: FloatingWindowGeometry.ScaledResize) {
        floatingResize = result
        val entersMini = result.atMinimum && (result.visible != taskBoundsAtDragStart ||
            result.scale < floatingStartScale - .001f || result.commitsBounds)
        resizeFeedback.update(if (entersMini) true else null,
            allowFloatingFeedback && inputMethod == InputMethod.TOUCH,
            windowDecoration::performFloatingTargetHapticFeedback)
        previewFloatingResize(result.visible, if (result.commitsBounds) 1f else result.scale,
            result.commitsBounds)
        repositionTaskBounds.set(result.visible)
    }

    private fun previewFloatingResize(bounds: Rect, scale: Float, veiled: Boolean = false) {
        if (floatingDragCancelled || closing) return
        if (floatingPreviewActive && floatingPreviewBounds == bounds &&
            floatingPreviewScale == scale && floatingPreviewVeiled == veiled) return
        floatingPreviewBounds.set(bounds)
        floatingPreviewScale = scale
        floatingPreviewVeiled = veiled
        floatingPreviewActive = true
        if (!floatingFramePending) {
            floatingFramePending = true
            Choreographer.getInstance().postFrameCallback(floatingFrameCallback)
        }
    }

    @VisibleForTesting
    fun flushFloatingResizePreview() {
        if (!floatingFramePending) return
        Choreographer.getInstance().removeFrameCallback(floatingFrameCallback)
        floatingFramePending = false
        if (floatingDragCancelled || closing) return
        if (floatingAppliedBounds == floatingPreviewBounds &&
            floatingAppliedScale == floatingPreviewScale &&
            floatingAppliedVeiled == floatingPreviewVeiled) return
        floatingAppliedBounds.set(floatingPreviewBounds)
        floatingAppliedScale = floatingPreviewScale
        floatingAppliedVeiled = floatingPreviewVeiled
        val t = floatingPreviewTransaction ?: transactionSupplier().also {
            floatingPreviewTransaction = it
        }
        windowDecoration.updateFloatingWindowPreview(floatingPreviewBounds, floatingPreviewScale, t)
        t.setFrameTimeline(Choreographer.getInstance().vsyncId).apply()
        if (floatingPreviewVeiled) {
            if (!isResizingOrAnimatingResize) {
                dragEventListeners.forEach { it.onDragMove(windowDecoration.taskInfo.taskId) }
                windowDecoration.showResizeVeil(floatingPreviewBounds)
                isResizingOrAnimatingResize = true
            } else windowDecoration.updateResizeVeil(floatingPreviewBounds)
        } else resetVeilIfVisible()
    }

    private fun flushFloatingMove() {
        if (!floatingMovePending) return
        floatingMovePending = false
        if (!miniPreviewCancelled) {
            miniPreview.update(windowDecoration.taskInfo, windowDecoration.taskSurface,
                windowDecoration.pinnedLayerController?.getMiniDragTarget(windowDecoration.taskInfo,
                    taskBoundsAtDragStart, repositionTaskBounds))
        }
        val t = floatingPreviewTransaction ?: transactionSupplier().also { floatingPreviewTransaction = it }
        t.setPosition(windowDecoration.taskSurface, repositionTaskBounds.left.toFloat(),
            repositionTaskBounds.top.toFloat())
            .setFrameTimeline(Choreographer.getInstance().vsyncId).apply()
    }

    private fun cancelFloatingFrame() {
        if (floatingFramePending || floatingMovePending) {
            floatingMovePending = false
            Choreographer.getInstance().removeFrameCallback(floatingFrameCallback)
            floatingFramePending = false
        }
    }

    override fun isDragPositioningCancelled() = floatingDragCancelled

    override fun onDragPositioningCancel() {
        if (!floatingDragActive) return
        cancelFloatingDragForTransition()
        floatingDragActive = false
        SurfaceFrameRate.setInteracting(this, false)
    }

    private fun stopFloatingObserver() {
        allowFloatingFeedback = false
        resizeFeedback.reset()
        floatingDragActive = false
        SurfaceFrameRate.setInteracting(this, false)
        if (floatingObserverRegistered) {
            transitions.unregisterObserver(this)
            floatingObserverRegistered = false
        }
        windowDecoration.floatingWindowTransitionListener = null
    }

    private fun cancelFloatingDragForTransition() {
        allowFloatingFeedback = false
        resizeFeedback.reset()
        miniPreview.close()
        miniPreviewCancelled = true
        cancelFloatingFrame()
        floatingPreviewActive = false
        windowDecoration.finishFloatingWindowPreview()
        resetVeilIfVisible()
        if (pendingFloatingTransition == null) {
            floatingDragCancelled = true
            repositionTaskBounds.set(windowDecoration.taskInfo.presentationBounds)
            if (!isResizing && windowDecoration.taskSurface.isValid) {
                transactionSupplier().use { t ->
                    t.setPosition(windowDecoration.taskSurface, repositionTaskBounds.left.toFloat(),
                        repositionTaskBounds.top.toFloat()).apply()
                }
            }
            floatingResize = null
            taskOrganizer.applyTransaction(WindowContainerTransaction()
                .setDragResizing(windowDecoration.taskInfo.token, false))
            stopFloatingObserver()
            interactionJankMonitor.end(Cuj.CUJ_DESKTOP_MODE_RESIZE_WINDOW)
            interactionJankMonitor.end(Cuj.CUJ_DESKTOP_MODE_DRAG_WINDOW)
        }
    }

    private fun restoreFloatingDecorations(task: RunningTaskInfo,
        startT: SurfaceControl.Transaction, finishT: SurfaceControl.Transaction) {
        cancelFloatingFrame()
        windowDecoration.finishFloatingWindowPreview()
        floatingPreviewActive = false
        windowDecoration.relayout(task, startT, finishT, false, false,
            windowDecoration.hasGlobalFocus, windowDecoration.exclusionRegion, true, null)
        windowDecoration.updateFloatingWindowChildScale(startT, task.floatingWindowScale)
        windowDecoration.updateFloatingWindowChildScale(finishT, task.floatingWindowScale)
    }

    private fun baseResizeBounds(visible: Rect): Rect = Rect(visible).apply {
        offset(floatingBaseBounds.left - taskBoundsAtDragStart.left,
            floatingBaseBounds.top - taskBoundsAtDragStart.top)
        offsetTo(left.coerceIn(stableBounds.left, stableBounds.right - width()),
            top.coerceIn(stableBounds.top, stableBounds.bottom - height()))
    }

    private fun endFloatingResize(x: Float, y: Float): Rect {
        if (x != lastFloatingX || y != lastFloatingY) moveFloatingResize(x, y)
        flushFloatingResizePreview()
        val task = windowDecoration.taskInfo
        floatingResize?.let { result ->
            val base = baseResizeBounds(result.visible)
            if (result.atMinimum && (result.visible != taskBoundsAtDragStart ||
                result.scale < floatingStartScale - .001f || result.commitsBounds)) {
                val ratioChanged = kotlin.math.abs(result.logical.width().toFloat() /
                    result.logical.height() - floatingLogicalBounds.width().toFloat() /
                    floatingLogicalBounds.height()) > .001f
                val restoreLogical = if (ratioChanged) result.logical else floatingLogicalBounds
                val restore = Rect(floatingBaseBounds.left, floatingBaseBounds.top,
                    floatingBaseBounds.left + (restoreLogical.width() * floatingStartScale).roundToInt(),
                    floatingBaseBounds.top + (restoreLogical.height() * floatingStartScale).roundToInt())
                restore.offsetTo(restore.left.coerceIn(stableBounds.left, stableBounds.right - restore.width()),
                    restore.top.coerceIn(stableBounds.top, stableBounds.bottom - restore.height()))
                windowDecoration.pinnedLayerController?.enterMiniAfterResize(task,
                    restore, Rect(restoreLogical), floatingStartScale, base, result.scale)
            }
            val wct = WindowContainerTransaction().setDragResizing(task.token, false)
                .setFloatingWindowPresentation(task.token,
                    android.graphics.Point(base.left, base.top), result.scale)
            if (result.commitsBounds) {
                val logical = Rect(result.logical).apply {
                    offsetTo(base.left.coerceIn(stableBounds.left, stableBounds.right - width()),
                        base.top.coerceIn(stableBounds.top, stableBounds.bottom - height()))
                }
                wct.setBounds(task.token, logical)
                pendingFloatingTransition =
                    transitions.startTransition(WindowManager.TRANSIT_CHANGE, wct, this)
            } else {
                windowDecoration.finishFloatingWindowPreview()
                floatingPreviewActive = false
                taskOrganizer.applyTransaction(wct)
                stopFloatingObserver()
                interactionJankMonitor.end(Cuj.CUJ_DESKTOP_MODE_RESIZE_WINDOW)
            }
            dragEventListeners.forEach { it.onDragResizeEnded(task.taskId, resizeTrigger, inputMethod, result.visible) }
            repositionTaskBounds.set(result.visible)
            ctrlType = DragPositioningCallback.CTRL_TYPE_UNDEFINED
            taskBoundsAtDragStart.setEmpty()
            floatingResize = null
            return Rect(repositionTaskBounds)
        }
        taskOrganizer.applyTransaction(WindowContainerTransaction().setDragResizing(task.token, false))
        stopFloatingObserver()
        taskBoundsAtDragStart.setEmpty()
        ctrlType = DragPositioningCallback.CTRL_TYPE_UNDEFINED
        return Rect(repositionTaskBounds)
    }

    private fun restoreFloatingPresentation() {
        cancelFloatingFrame()
        stopFloatingObserver()
        val task = windowDecoration.taskInfo
        if (!task.isFloatingWindow) return
        if (floatingPreviewActive) {
            transactionSupplier().use {
                if (closing) windowDecoration.cancelFloatingWindowPreview(it)
                else windowDecoration.updateFloatingWindowPreview(task.presentationBounds,
                    task.floatingWindowScale, it)
                it.apply()
            }
            windowDecoration.finishFloatingWindowPreview()
            floatingPreviewActive = false
        }
        taskOrganizer.applyTransaction(WindowContainerTransaction().setDragResizing(task.token, false))
    }

    private fun resetVeilIfVisible() {
        if (isResizingOrAnimatingResize) {
            windowDecoration.hideResizeVeil()
            isResizingOrAnimatingResize = false
        }
    }

    private fun calculateOnMoveRestoredBounds(
        prevBounds: Rect,
        currentBounds: Rect,
        dragStartX: Float,
    ): Rect {
        val prevWidth = prevBounds.width()
        val prevHeight = prevBounds.height()

        val touchPointHorizontalRatio = (dragStartX - currentBounds.left) / currentBounds.width()
        val positionOffset = (prevWidth * touchPointHorizontalRatio).toInt()

        val newLeft = dragStartX - positionOffset
        return Rect(
            newLeft.toInt(),
            currentBounds.top,
            (newLeft + prevWidth).toInt(),
            currentBounds.top + prevHeight,
        )
    }

    private fun createLongTimeoutJankConfigBuilder(@Cuj.CujType cujType: Int) =
        InteractionJankMonitor.Configuration.Builder.withSurface(
                cujType,
                windowDecoration.decorWindowContext,
                windowDecoration.taskSurface,
                handler,
            )
            .setTimeout(LONG_CUJ_TIMEOUT_MS)

    override fun startAnimation(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: Transitions.TransitionFinishCallback,
    ): Boolean {
        for (change in info.changes) {
            if (change.taskInfo == null) {
                // Ignore non-task (e.g., display, activity) changes.
                continue
            }
            if (change.taskInfo!!.isFloatingWindow) {
                if (change.taskInfo!!.taskId == windowDecoration.taskInfo.taskId) {
                    restoreFloatingDecorations(change.taskInfo!!, startTransaction, finishTransaction)
                }
                Transitions.applyFloatingWindowTransform(change, startTransaction)
                Transitions.applyFloatingWindowTransform(change, finishTransaction)
                continue
            }
            val sc = change.leash
            val endBounds = change.endAbsBounds
            val endPosition = change.endRelOffset
            startTransaction
                .setWindowCrop(sc, endBounds.width(), endBounds.height())
                .setPosition(sc, endPosition.x.toFloat(), endPosition.y.toFloat())
            finishTransaction
                .setWindowCrop(sc, endBounds.width(), endBounds.height())
                .setPosition(sc, endPosition.x.toFloat(), endPosition.y.toFloat())
        }

        pendingFloatingTransition = null
        pendingFloatingChange = null
        stopFloatingObserver()
        startTransaction.apply()
        resetVeilIfVisible()
        ctrlType = DragPositioningCallback.CTRL_TYPE_UNDEFINED
        finishCallback.onTransitionFinished(null /* wct */)
        isResizingOrAnimatingResize = false
        // This is only called when drag resize ends as the class is working as the transition
        // handler of the drag resize end event only.
        interactionJankMonitor.end(Cuj.CUJ_DESKTOP_MODE_RESIZE_WINDOW)
        return true
    }

    override fun onTransitionReady(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
    ) {
        if (floatingObserverRegistered) {
            val floatingChange = info.changes.firstOrNull {
                it.taskInfo?.taskId == windowDecoration.taskInfo.taskId
            }
            if (transition == pendingFloatingTransition) {
                pendingFloatingChange = floatingChange
                if (floatingChange?.taskInfo != null) {
                    restoreFloatingDecorations(floatingChange.taskInfo!!,
                        startTransaction, finishTransaction)
                }
            } else if (floatingChange != null &&
                (floatingDragActive || floatingPreviewActive || floatingFramePending)) {
                if (isFloatingFocusOnlyChange(info, floatingChange)) {
                    // Handle DOWN may focus the task after the drag has already started.
                    finishTransaction.addTransactionCommittedListener({ command ->
                        handler.post(command)
                    }) {
                        if (!closing && floatingDragActive && !floatingDragCancelled &&
                            !floatingMovePending) {
                            floatingMovePending = true
                            Choreographer.getInstance().postFrameCallback(floatingFrameCallback)
                        }
                    }
                    return
                }
                cancelFloatingDragForTransition()
                restoreFloatingDecorations(floatingChange.taskInfo!!,
                    startTransaction, finishTransaction)
            }
        }
        if (transition == pendingResizeTransition) {
            pendingResizeTransition = null
            transitions.unregisterObserver(this)
        }
    }

    private fun isFloatingFocusOnlyChange(info: TransitionInfo,
        change: TransitionInfo.Change): Boolean {
        val task = change.taskInfo ?: return false
        return !isResizing && info.type == WindowManager.TRANSIT_CHANGE &&
            (change.mode == WindowManager.TRANSIT_CHANGE ||
                change.mode == WindowManager.TRANSIT_TO_FRONT) &&
            task.isFloatingWindow && task.isVisible && task.isFocused &&
            task.displayId == startDisplayId &&
            change.startDisplayId == startDisplayId && change.endDisplayId == startDisplayId &&
            change.startAbsBounds == change.endAbsBounds &&
            task.configuration.windowConfiguration.bounds == floatingLogicalBounds &&
            task.configuration.windowConfiguration.displayRotation == rotation &&
            task.presentationBounds == taskBoundsAtDragStart &&
            task.floatingWindowScale == floatingStartScale &&
            info.changes.none { it.hasFlags(TransitionInfo.FLAG_IS_DISPLAY) }
    }

    override fun onTransitionConsumed(transition: IBinder, aborted: Boolean,
        finishTransaction: SurfaceControl.Transaction?) {
        if (transition != pendingFloatingTransition) return
        val change = pendingFloatingChange
        val task = change?.taskInfo ?: windowDecoration.taskInfo
        transactionSupplier().use { t ->
            restoreFloatingDecorations(task, t, finishTransaction ?: t)
            // Only restore task position in the finish transaction, after remote reparenting.
            if (finishTransaction != null) {
                if (change != null) Transitions.applyFloatingWindowTransform(change, finishTransaction)
                else {
                    val bounds = task.presentationBounds
                    val logical = task.configuration.windowConfiguration.bounds
                    finishTransaction.setMatrix(windowDecoration.taskSurface,
                        task.floatingWindowScale, 0f, 0f, task.floatingWindowScale)
                        .setPosition(windowDecoration.taskSurface,
                            bounds.left.toFloat(), bounds.top.toFloat())
                        .setWindowCrop(windowDecoration.taskSurface, logical.width(), logical.height())
                }
            }
            if (task.isFloatingWindow) {
                val logical = task.configuration.windowConfiguration.bounds
                t.setMatrix(windowDecoration.taskSurface,
                    task.floatingWindowScale, 0f, 0f, task.floatingWindowScale)
                    .setWindowCrop(windowDecoration.taskSurface, logical.width(), logical.height())
            }
            t.apply()
        }
        pendingFloatingTransition = null
        pendingFloatingChange = null
        stopFloatingObserver()
        resetVeilIfVisible()
        ctrlType = DragPositioningCallback.CTRL_TYPE_UNDEFINED
        interactionJankMonitor.end(Cuj.CUJ_DESKTOP_MODE_RESIZE_WINDOW)
    }

    /**
     * We should never reach this as this handler's transitions are only started from shell
     * explicitly.
     */
    override fun handleRequest(
        transition: IBinder,
        request: TransitionRequestInfo,
    ): WindowContainerTransaction? {
        return null
    }

    override fun isResizingOrAnimating() = isResizingOrAnimatingResize || floatingPreviewActive ||
        floatingFramePending || pendingFloatingTransition != null

    override fun addDragEventListener(
        dragEventListener: DragPositioningCallbackUtility.DragEventListener
    ) {
        dragEventListeners.add(dragEventListener)
    }

    override fun removeDragEventListener(
        dragEventListener: DragPositioningCallbackUtility.DragEventListener
    ) {
        dragEventListeners.remove(dragEventListener)
    }

    override fun onDisplayConfigurationChanged(displayId: Int,
        configuration: android.content.res.Configuration) {
        if (displayId != startDisplayId) return
        onDragPositioningCancel()
    }

    override fun onDisplayRemoved(displayId: Int) {
        if (displayId != startDisplayId) return
        onDragPositioningCancel()
    }

    override fun onTopologyChanged(topology: DisplayTopology?) {
        // TODO: b/383069173 - Cancel window drag when topology changes happen during drag.

        displayIds.clear()
        if (topology == null) return
        displayIds.addAll(topology.allNodesIdMap().keys)
        logD(TAG, windowDecoration.taskInfo.taskId, "onTopologyChanged: displayIds=%s", displayIds)
    }

    companion object {
        private const val TAG = "MultiDisplayVeiledResizeTaskPositioner"

        // Timeout used for resize and drag CUJs, this is longer than the default timeout to avoid
        // timing out in the middle of a resize or drag action.
        private val LONG_CUJ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(/* duration= */ 10L)

        private val ALPHA_FOR_WINDOW_ON_DISPLAY_WITH_CURSOR = 1.0f
        private val ALPHA_FOR_WINDOW_ON_NON_CURSOR_DISPLAY = 0.7f
    }
}
