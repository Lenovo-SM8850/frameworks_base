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

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.graphics.Rect
import android.os.IBinder
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper
import android.util.DisplayMetrics
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_CLOSE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.view.WindowManager.TRANSIT_TO_BACK
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.window.DisplayAreaInfo
import android.window.DisplayAreaOrganizer
import android.window.TransitionInfo
import android.window.TransitionInfo.FLAG_IS_WALLPAPER
import android.window.TransitionInfo.FLAG_NONE
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.testing.wm.util.MockToken
import com.android.window.flags.Flags
import com.android.wm.shell.R
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.MultiDisplayDragMoveIndicatorController
import com.android.wm.shell.desktopmode.DesktopTestHelpers
import com.android.wm.shell.desktopmode.FakeShellDesktopState
import com.android.wm.shell.desktopmode.WindowDragTransitionHandler
import com.android.wm.shell.recents.RecentsTransitionHandler
import com.android.wm.shell.recents.RecentsTransitionStateListener
import com.android.wm.shell.recents.RecentsTransitionStateListener.TRANSITION_STATE_ANIMATING
import com.android.wm.shell.recents.RecentsTransitionStateListener.TRANSITION_STATE_REQUESTED
import com.android.wm.shell.shared.TransactionPool
import com.android.wm.shell.shared.desktopmode.FakeDesktopState
import com.android.wm.shell.sysui.KeyguardChangeListener
import com.android.wm.shell.sysui.OverviewVisibilityChangeListener
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * Unit tests against [PinnedLayerController]
 *
 * Build/Install/Run: atest WMShellUnitTests:PinnedLayerControllerTest
 */
@SmallTest
@TestableLooper.RunWithLooper
@EnableFlags(Flags.FLAG_ENABLE_INTERACTIVE_PICTURE_IN_PICTURE)
@RunWith(AndroidJUnit4::class)
class PinnedLayerControllerTest : ShellTestCase() {
    @Mock private lateinit var shellInit: ShellInit
    @Mock private lateinit var transitions: Transitions
    @Mock private lateinit var windowDragTransitionHandler: WindowDragTransitionHandler
    @Mock private lateinit var transactionPool: TransactionPool
    @Mock private lateinit var poolTransaction: SurfaceControl.Transaction
    @Mock private lateinit var startTransaction: SurfaceControl.Transaction
    @Mock private lateinit var finishTransaction: SurfaceControl.Transaction
    @Mock private lateinit var repositionTransaction: SurfaceControl.Transaction
    @Mock private lateinit var leash: SurfaceControl
    @Mock private lateinit var displayController: DisplayController
    @Mock private lateinit var rootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer
    @Mock private lateinit var shellTaskOrganizer: ShellTaskOrganizer
    @Mock
    private lateinit var multiDisplayDragMoveIndicatorController:
        MultiDisplayDragMoveIndicatorController
    private val windowRepositionAnimator = mock<PinnedWindowRepositionAnimator>()

    private lateinit var defaultDisplayToken: WindowContainerToken
    private lateinit var desktopState: FakeDesktopState
    private lateinit var shellDesktopState: FakeShellDesktopState
    private lateinit var presentationController: PinnedLayerPresentationController
    private lateinit var pinnedWindowRepositionAnimationHandler:
        PinnedWindowRepositionAnimationHandler
    private lateinit var pinnedLayerController: PinnedLayerController

    @Before
    fun setup() {
        defaultDisplayToken = MockToken.token()
        desktopState = FakeDesktopState()
        shellDesktopState = FakeShellDesktopState(desktopState)

        whenever(transactionPool.acquire()).thenReturn(poolTransaction)

        setupDisplay(DEFAULT_DISPLAY, defaultDisplayToken, isDesktopModeSupported = true)

        presentationController =
            PinnedLayerPresentationController(context, displayController, desktopState)
        pinnedWindowRepositionAnimationHandler =
            PinnedWindowRepositionAnimationHandler(transitions, windowRepositionAnimator)
        pinnedLayerController =
            PinnedLayerController(
                shellInit,
                transitions,
                shellDesktopState,
                rootTaskDisplayAreaOrganizer,
                shellTaskOrganizer,
                presentationController,
                windowDragTransitionHandler,
                pinnedWindowRepositionAnimationHandler,
                windowRepositionAnimator,
                transactionPool,
                multiDisplayDragMoveIndicatorController,
            )
    }

    @Test
    fun floatingEnter_capsTaskLeashInStartTransaction() {
        val task = setupTask().apply { isFloatingWindow = true }
        whenever(leash.isValid).thenReturn(true)

        hdrTransition(task)

        verify(startTransaction).setDesiredMaxHdrHeadroom(leash, 1.0f)
        verify(finishTransaction, never()).setDesiredMaxHdrHeadroom(any(), any())
    }

    @Test
    fun pinFromFullscreen_capsTaskLeashInStartTransaction() {
        assertPinCapsHdr(WINDOWING_MODE_FULLSCREEN)
    }

    @Test
    fun pinFromDesktop_capsTaskLeashInStartTransaction() {
        assertPinCapsHdr(WINDOWING_MODE_FREEFORM)
    }

    private fun assertPinCapsHdr(windowingMode: Int) {
        val task = setupTask().apply {
            configuration.windowConfiguration.windowingMode = windowingMode
        }
        whenever(leash.isValid).thenReturn(true)
        val transition = mock<IBinder>()
        assertNotNull(pinnedLayerController.pinTask(transition, task, null))
        task.isFloatingWindow = true
        task.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FREEFORM
        val info = TransitionInfo(TRANSIT_CHANGE, FLAG_NONE)
        info.addChange(TransitionInfo.Change(task.token, leash).apply {
            taskInfo = task
            mode = TRANSIT_CHANGE
        })

        pinnedLayerController.onTransitionReady(transition, info, startTransaction, finishTransaction)

        verify(startTransaction).setDesiredMaxHdrHeadroom(leash, 1.0f)
    }

    @Test
    fun transitionAnimationLeash_capsOrganizerTaskSurface() {
        val task = setupTask()
        val taskLeash = mock<SurfaceControl>()
        whenever(taskLeash.isValid).thenReturn(true)
        whenever(leash.isValid).thenReturn(true)
        initController()
        val appeared = argumentCaptor<ShellTaskOrganizer.TaskAppearedListener>()
        verify(shellTaskOrganizer).addTaskAppearedListener(appeared.capture())
        appeared.firstValue.onTaskAppeared(task, taskLeash)
        task.isFloatingWindow = true

        hdrTransition(task)

        verify(startTransaction).setDesiredMaxHdrHeadroom(taskLeash, 1.0f)
        verify(startTransaction, never()).setDesiredMaxHdrHeadroom(leash, any())
    }

    @Test
    fun fullscreenTask_doesNotRequestHdrCap() {
        val task = setupTask().apply {
            configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN
        }
        whenever(leash.isValid).thenReturn(true)

        hdrTransition(task)

        verify(startTransaction, never()).setDesiredMaxHdrHeadroom(any(), any())
    }

    @Test
    fun maximize_clearsHdrCapInFullscreenStartTransaction() {
        val task = setupTask().apply { isFloatingWindow = true }
        whenever(leash.isValid).thenReturn(true)
        hdrTransition(task)
        org.mockito.Mockito.clearInvocations(startTransaction, poolTransaction)

        assertTrue(pinnedLayerController.maximizeTask(task))
        verify(poolTransaction, never()).setDesiredMaxHdrHeadroom(any(), any())
        task.isFloatingWindow = false
        task.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN
        hdrTransition(task)

        verify(startTransaction).setDesiredMaxHdrHeadroom(leash, 0f)
        verify(startTransaction, never()).setDesiredMaxHdrHeadroom(leash, 1.0f)
    }

    @Test
    fun desktopHandoff_clearsHdrCapWhileRemainingFreeform() {
        val task = setupTask().apply { isFloatingWindow = true }
        whenever(leash.isValid).thenReturn(true)
        hdrTransition(task)
        org.mockito.Mockito.clearInvocations(startTransaction)

        task.isFloatingWindow = false
        hdrTransition(task)

        verify(startTransaction).setDesiredMaxHdrHeadroom(leash, 0f)
    }

    @Test
    fun close_clearsHdrCapInStartTransaction() {
        val task = setupTask().apply { isFloatingWindow = true }
        whenever(leash.isValid).thenReturn(true)
        hdrTransition(task)
        org.mockito.Mockito.clearInvocations(startTransaction)

        hdrTransition(task, TRANSIT_CLOSE)

        verify(startTransaction).setDesiredMaxHdrHeadroom(leash, 0f)
    }

    @Test
    fun cleanUnpin_withoutTaskChange_clearsHdrCapInStartTransaction() {
        val task = setupTask().apply { isFloatingWindow = true }
        whenever(leash.isValid).thenReturn(true)
        hdrTransition(task)
        org.mockito.Mockito.clearInvocations(startTransaction)
        val transition = mock<IBinder>()
        pinnedLayerController.unpinTask(transition, task, PinnedLayerController.UnpinStrategy.CLEAN)

        pinnedLayerController.onTransitionReady(transition, TransitionInfo(TRANSIT_CHANGE, FLAG_NONE),
            startTransaction, finishTransaction)

        verify(startTransaction).setDesiredMaxHdrHeadroom(leash, 0f)
    }

    @Test
    fun miniRestoreAndRotation_keepHdrCap() {
        val task = setupTask().apply { isFloatingWindow = true }
        whenever(leash.isValid).thenReturn(true)
        hdrTransition(task)
        val bounds = task.configuration.windowConfiguration.bounds
        presentationController.miniState.enter(task.userId, task.taskId,
            FloatingMiniState.Restore(bounds, bounds, 1f), Rect(0, 0, 100, 100), .25f)
        org.mockito.Mockito.clearInvocations(startTransaction)

        hdrTransition(task)
        presentationController.miniState.remove(task.userId, task.taskId)
        hdrTransition(task)
        task.configuration.windowConfiguration.setBounds(Rect(0, 0, 900, 600))
        hdrTransition(task)

        verify(startTransaction, org.mockito.kotlin.times(3))
            .setDesiredMaxHdrHeadroom(leash, 1.0f)
        verify(startTransaction, never()).setDesiredMaxHdrHeadroom(leash, 0f)
    }

    @Test
    fun minimize_keepsHdrCap() {
        val task = setupTask().apply { isFloatingWindow = true }
        whenever(leash.isValid).thenReturn(true)
        hdrTransition(task)
        org.mockito.Mockito.clearInvocations(startTransaction, poolTransaction)
        val transition = mock<IBinder>()
        pinnedLayerController.unpinTask(transition, task, PinnedLayerController.UnpinStrategy.HIDE)

        pinnedLayerController.onTransitionReady(transition, TransitionInfo(TRANSIT_CHANGE, FLAG_NONE),
            startTransaction, finishTransaction)

        verify(startTransaction).setDesiredMaxHdrHeadroom(leash, 1.0f)
        verify(startTransaction, never()).setDesiredMaxHdrHeadroom(leash, 0f)
        verify(poolTransaction, never()).setDesiredMaxHdrHeadroom(any(), any())
    }

    @Test
    fun initialFloatingTask_capsRecoveredLeash() {
        val task = setupTask().apply { isFloatingWindow = true }
        whenever(leash.isValid).thenReturn(true)
        whenever(shellTaskOrganizer.initialTasks).thenReturn(listOf(android.util.Pair(task, leash)))

        initController()

        verify(poolTransaction).setDesiredMaxHdrHeadroom(leash, 1.0f)
        verify(poolTransaction).apply()
    }

    @Test
    fun appearedFloatingTask_capsLeashAndVanishedTaskClearsIt() {
        val task = setupTask().apply { isFloatingWindow = true }
        whenever(leash.isValid).thenReturn(true)
        initController()
        val appeared = argumentCaptor<ShellTaskOrganizer.TaskAppearedListener>()
        val vanished = argumentCaptor<ShellTaskOrganizer.TaskVanishedListener>()
        verify(shellTaskOrganizer).addTaskAppearedListener(appeared.capture())
        verify(shellTaskOrganizer).addTaskVanishedListener(vanished.capture())

        appeared.firstValue.onTaskAppeared(task, leash)
        vanished.firstValue.onTaskVanished(task)

        val order = org.mockito.kotlin.inOrder(poolTransaction)
        order.verify(poolTransaction).setDesiredMaxHdrHeadroom(leash, 1.0f)
        order.verify(poolTransaction).apply()
        order.verify(poolTransaction).setDesiredMaxHdrHeadroom(leash, 0f)
        order.verify(poolTransaction).apply()
    }

    @Test
    fun replacedLeash_clearsOldCapBeforeCappingNewLeash() {
        val task = setupTask().apply { isFloatingWindow = true }
        whenever(leash.isValid).thenReturn(true)
        val replacement = mock<SurfaceControl>()
        whenever(replacement.isValid).thenReturn(true)
        initController()
        val appeared = argumentCaptor<ShellTaskOrganizer.TaskAppearedListener>()
        verify(shellTaskOrganizer).addTaskAppearedListener(appeared.capture())
        appeared.firstValue.onTaskAppeared(task, leash)
        org.mockito.Mockito.clearInvocations(poolTransaction)

        appeared.firstValue.onTaskAppeared(task, replacement)

        val order = org.mockito.kotlin.inOrder(poolTransaction)
        order.verify(poolTransaction).setDesiredMaxHdrHeadroom(leash, 0f)
        order.verify(poolTransaction).setDesiredMaxHdrHeadroom(replacement, 1.0f)
        order.verify(poolTransaction).apply()
    }

    @Test
    fun taskInfoExit_defersHdrClearUntilTransitionsIdle() {
        val task = setupTask().apply { isFloatingWindow = true }
        whenever(leash.isValid).thenReturn(true)
        whenever(shellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        initController()
        hdrTransition(task)
        val changed = argumentCaptor<ShellTaskOrganizer.TaskInfoChangedListener>()
        verify(shellTaskOrganizer).addTaskInfoChangedListener(changed.capture())
        task.isFloatingWindow = false

        changed.firstValue.onTaskInfoChanged(task)

        verify(poolTransaction, never()).setDesiredMaxHdrHeadroom(any(), any())
        val idle = argumentCaptor<Runnable>()
        verify(transitions).runOnIdle(idle.capture())
        idle.firstValue.run()
        verify(poolTransaction).setDesiredMaxHdrHeadroom(leash, 0f)
        verify(poolTransaction).apply()
    }

    @Test
    fun abortedExit_restoresHdrCapForLiveFloatingTask() {
        val task = setupTask().apply { isFloatingWindow = true }
        whenever(leash.isValid).thenReturn(true)
        whenever(shellTaskOrganizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
        whenever(shellTaskOrganizer.getRunningTasks(DEFAULT_DISPLAY)).thenReturn(arrayListOf(task))
        hdrTransition(task)
        val transition = mock<IBinder>()
        pinnedLayerController.unpinTask(transition, task, PinnedLayerController.UnpinStrategy.CLEAN)
        pinnedLayerController.onTransitionReady(transition, TransitionInfo(TRANSIT_CHANGE, FLAG_NONE),
            startTransaction, finishTransaction)

        pinnedLayerController.onTransitionFinished(transition, true)

        verify(startTransaction).setDesiredMaxHdrHeadroom(leash, 0f)
        verify(poolTransaction).setDesiredMaxHdrHeadroom(leash, 1.0f)
    }

    private fun initController() {
        val init = argumentCaptor<Runnable>()
        verify(shellInit).addInitCallback(init.capture(), eq(pinnedLayerController))
        init.firstValue.run()
    }

    private fun hdrTransition(task: RunningTaskInfo, mode: Int = TRANSIT_CHANGE) {
        val info = TransitionInfo(mode, FLAG_NONE)
        info.addChange(TransitionInfo.Change(task.token, leash).apply {
            taskInfo = task
            this.mode = mode
        })
        pinnedLayerController.onTransitionReady(mock(), info, startTransaction, finishTransaction)
    }

    @Test
    fun vanishedDesktopTask_doesNotNotifyFloatingListeners() {
        val init = argumentCaptor<Runnable>()
        verify(shellInit).addInitCallback(init.capture(), eq(pinnedLayerController))
        init.firstValue.run()
        val vanished = argumentCaptor<ShellTaskOrganizer.TaskVanishedListener>()
        verify(shellTaskOrganizer).addTaskVanishedListener(vanished.capture())
        val listener = mock<PinnedLayerController.PinnedTasksListener>()
        pinnedLayerController.addPinnedTasksListener(listener)
        vanished.firstValue.onTaskVanished(android.app.ActivityManager.RunningTaskInfo().apply {
            taskId = 77
            isFloatingWindow = false
        })
        verifyNoInteractions(listener)
    }

    @Test
    fun floatingDragEnded_preservesPreImeBasePosition() {
        val task = setupTask(bounds = Rect(100, 100, 900, 900)).apply {
            isFloatingWindow = true
            floatingWindowScale = .75f
            floatingWindowPosition.set(100, 300)
            positionInParent = android.graphics.Point(100, 120)
        }
        pinTask(task)
        org.mockito.Mockito.clearInvocations(shellTaskOrganizer, transitions)
        val start = task.presentationBounds
        val end = Rect(start).apply { offset(60, 80) }
        assertTrue(pinnedLayerController.onDragEnded(leash, task, start, end))
        val wct = argumentCaptor<WindowContainerTransaction>()
        verify(shellTaskOrganizer).applyTransaction(wct.capture())
        val change = wct.firstValue.changes[task.token.asBinder()]!!
        assertEquals(android.graphics.Point(160, 380), change.floatingWindowPosition)
        assertEquals(.75f, change.floatingWindowScale)
        assertEquals(0, change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS)
    }

    @Test
    fun floatingDragEnded_withoutMovementDoesNotCommitImeOffset() {
        val task = setupTask(bounds = Rect(100, 100, 900, 900)).apply {
            isFloatingWindow = true
            floatingWindowScale = .75f
            floatingWindowPosition.set(100, 300)
            positionInParent = android.graphics.Point(100, 120)
        }
        pinTask(task)
        org.mockito.Mockito.clearInvocations(shellTaskOrganizer, transitions)
        val bounds = task.presentationBounds
        assertTrue(pinnedLayerController.onDragEnded(leash, task, bounds, Rect(bounds)))
        verify(shellTaskOrganizer, never()).applyTransaction(any())
        verify(transitions, never()).startTransition(any(), any(), anyOrNull())
    }

    @Test
    fun dragEnded_notPinnedTask_doNothing() {
        val task = setupTask()
        val dragStartBounds = Rect(DEFAULT_TASK_BOUNDS)
        val dragEndBounds = Rect(DEFAULT_TASK_BOUNDS)
        dragEndBounds.offset(100, 100)

        val result = pinnedLayerController.onDragEnded(leash, task, dragStartBounds, dragEndBounds)

        assertFalse(result)
        verifyNoInteractions(transitions, transactionPool)
    }

    @Test
    fun dragEnded_snapsBackToStartBounds_animatesWithoutTransition() {
        val task = setupTask()
        pinTask(task)

        val dragStartBounds = Rect(DEFAULT_TASK_BOUNDS)
        val dragEndBounds = Rect(dragStartBounds)
        // Move the window a bit.
        dragEndBounds.offset(-10, -10)

        val result = pinnedLayerController.onDragEnded(leash, task, dragStartBounds, dragEndBounds)

        // Snapshotting surfaces should be cleared by the controller itself.
        assertTrue(result)
        // No Transition is started.
        verify(transitions, never()).startTransition(any(), any(), anyOrNull())
        // Manually animate the leash snap to start.
        verify(windowRepositionAnimator)
            .start(any(), any(), any(), anyOrNull(), anyOrNull(), any(), any())
    }

    @Test
    fun dragEnded_noChangesInBounds_applyTransactionImmediately() {
        val task = setupTask()
        val transition = mock<IBinder>()
        val transitionInfo = TransitionInfo(TRANSIT_CHANGE, FLAG_NONE)

        pinnedLayerController.pinTask(transition, task, null)
        pinnedLayerController.onTransitionReady(
            transition,
            transitionInfo,
            startTransaction,
            finishTransaction,
        )

        val dragStartBounds = Rect(DEFAULT_TASK_BOUNDS)
        val dragEndBounds = Rect(dragStartBounds)
        val result = pinnedLayerController.onDragEnded(leash, task, dragStartBounds, dragEndBounds)

        assertTrue(result)
        verify(poolTransaction)
            .setPosition(leash, dragStartBounds.left.toFloat(), dragStartBounds.top.toFloat())
        verify(transitions, never()).startTransition(any(), any(), any())
    }

    @Test
    fun dragEndedOutsideValidArea_animateToSnappedBounds() {
        val task = setupTask()
        val transition = mock<IBinder>()
        val transitionInfo = TransitionInfo(TRANSIT_CHANGE, FLAG_NONE)

        pinnedLayerController.pinTask(transition, task, null)
        pinnedLayerController.onTransitionReady(
            transition,
            transitionInfo,
            startTransaction,
            finishTransaction,
        )

        val displayLayout = requireNotNull(displayController.getDisplayLayout(DEFAULT_DISPLAY))
        val dragStartBounds = Rect(DEFAULT_TASK_BOUNDS)
        val dragEndBounds = Rect(dragStartBounds)
        // Drag behind right edge of the drag area.
        dragEndBounds.offset(displayLayout.width(), 0)

        val result = pinnedLayerController.onDragEnded(leash, task, dragStartBounds, dragEndBounds)
        assertFalse(result)

        val wctCaptor = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(any(), wctCaptor.capture(), anyOrNull())

        val wct = wctCaptor.firstValue
        val changeBounds = wct.findBoundsChange(task)
        val expectedBounds =
            Rect(
                DISPLAY_STABLE_BOUNDS.right - MIN_WINDOW_SIZE,
                dragEndBounds.top,
                DISPLAY_STABLE_BOUNDS.right,
                dragStartBounds.bottom,
            )

        assertEquals(expectedBounds, changeBounds)
    }

    @Test
    fun dragEnded_endBoundsWithinDragArea_updateTaskBounds() {
        val task = setupTask()
        val transition = mock<IBinder>()
        val transitionInfo = TransitionInfo(TRANSIT_CHANGE, FLAG_NONE)

        pinnedLayerController.pinTask(transition, task, null)
        pinnedLayerController.onTransitionReady(
            transition,
            transitionInfo,
            startTransaction,
            finishTransaction,
        )

        val dragStartBounds = Rect(DEFAULT_TASK_BOUNDS)
        val dragEndBounds = Rect(DEFAULT_TASK_BOUNDS)
        dragEndBounds.offset(100, 100)

        val result = pinnedLayerController.onDragEnded(leash, task, dragStartBounds, dragEndBounds)
        assertFalse(result)

        val wctCaptor = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(any(), wctCaptor.capture(), any())

        val wct = wctCaptor.firstValue
        val changeBounds = wct.findBoundsChange(task)
        assertEquals(dragEndBounds, changeBounds)
    }

    @Test
    fun moveToDisplay_taskNotPinned_doNothing() {
        val task = setupTask()
        setupDisplay(DISPLAY_1, isDesktopModeSupported = true)

        val result = pinnedLayerController.moveToDisplay(task, DISPLAY_1)
        assertFalse(result)
    }

    @Test
    fun moveToDisplay_displayDoNotExist_doNothing() {
        val task = setupTask()

        val result = pinnedLayerController.moveToDisplay(task, DISPLAY_1)
        assertFalse(result)
    }

    @Test
    fun moveToDisplay_displayDoNotSupportDesktopMode_doNothing() {
        val task = setupTask()
        setupDisplay(DISPLAY_1, isDesktopModeSupported = false)

        val result = pinnedLayerController.moveToDisplay(task, DISPLAY_1)
        assertFalse(result)
    }

    @Test
    fun moveToSameDisplay_doNothing() {
        val task = setupTask()
        setupDisplay(DISPLAY_1, isDesktopModeSupported = true)

        val result = pinnedLayerController.moveToDisplay(task, DEFAULT_DISPLAY)
        assertFalse(result)
    }

    @Test
    fun moveToDisplay_nonDefaultDisplay_rejected() {
        val task = setupTask()
        pinTask(task)
        setupDisplay(DISPLAY_1, isDesktopModeSupported = true)

        assertFalse(pinnedLayerController.moveToDisplay(task, DISPLAY_1, DEFAULT_TASK_BOUNDS))
        verify(transitions, never()).startTransition(any(), any(), anyOrNull())
    }

    @Test
    fun onDragEnded_displayIsDisconnected_moveToOriginalBounds() {
        val task = setupTask()
        pinTask(task)
        setupDisplay(DISPLAY_1)

        // Disconnect the display.
        whenever(rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DISPLAY_1)).thenReturn(null)
        whenever(displayController.getDisplayLayout(DISPLAY_1)).thenReturn(null)

        val newBounds = Rect(DEFAULT_TASK_BOUNDS)
        // Assert no transitions has been started and mirrors will be cleared by the API caller.
        val result =
            pinnedLayerController.onDragEnded(
                leash,
                task,
                DEFAULT_TASK_BOUNDS,
                newBounds,
                DISPLAY_1,
            )
        assertTrue(result)

        // Verify bounds restored to the start drag bounds.
        val leftCaptor = argumentCaptor<Float>()
        val topCaptor = argumentCaptor<Float>()
        verify(poolTransaction).setPosition(any(), leftCaptor.capture(), topCaptor.capture())

        assertEquals(DEFAULT_TASK_BOUNDS.left, leftCaptor.firstValue.roundToInt())
        assertEquals(DEFAULT_TASK_BOUNDS.top, topCaptor.firstValue.roundToInt())
    }

    @Test
    fun pinTask_nonDefaultDisplay_rejected() {
        setupDisplay(displayId = DISPLAY_1, isDesktopModeSupported = true)
        val task = setupTask(displayId = DISPLAY_1)

        assertTrue(pinnedLayerController.pinTask(mock(), task, null) == null)
        assertFalse(pinnedLayerController.isPinned(task.taskId))
    }

    @Test
    fun onDisplayDisconnect_taskOnDifferentDisplay_returnsNull() {
        setupDisplay(displayId = DISPLAY_1, isDesktopModeSupported = true)
        val task = setupTask(displayId = DEFAULT_DISPLAY)

        pinTask(task)

        val wct =
            pinnedLayerController.getDisplayDisconnectChanges(mock(), DISPLAY_1, DEFAULT_DISPLAY)
        assertTrue(wct == null, "wct should be null if task is on different display")
    }

    @Test
    fun checkFullscreenTaskOpened_keepsPinnedTask() {
        val pinnedTask = setupTask(displayId = DEFAULT_DISPLAY)
        pinTask(pinnedTask)

        val fullscreenTask = setupTask(displayId = DEFAULT_DISPLAY, taskId = 100)
        fullscreenTask.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN

        val change = TransitionInfo.Change(MockToken.token(), mock())
        change.taskInfo = fullscreenTask
        change.mode = TRANSIT_OPEN

        val info = TransitionInfo(TRANSIT_OPEN, FLAG_NONE)
        info.addChange(change)

        pinnedLayerController.onTransitionReady(mock(), info, startTransaction, finishTransaction)

        verify(transitions, never()).startTransition(eq(TRANSIT_CLOSE), any(), anyOrNull())
        assertTrue(pinnedLayerController.isPinned(pinnedTask.taskId))
    }

    @Test
    fun checkFullscreenTaskToFront_keepsPinnedTask() {
        val pinnedTask = setupTask(displayId = DEFAULT_DISPLAY)
        pinTask(pinnedTask)

        val fullscreenTask = setupTask(displayId = DEFAULT_DISPLAY, taskId = 100)
        fullscreenTask.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN

        val change = TransitionInfo.Change(MockToken.token(), mock())
        change.taskInfo = fullscreenTask
        change.mode = TRANSIT_TO_FRONT

        val info = TransitionInfo(TRANSIT_TO_FRONT, FLAG_NONE)
        info.addChange(change)

        pinnedLayerController.onTransitionReady(mock(), info, startTransaction, finishTransaction)

        verify(transitions, never()).startTransition(eq(TRANSIT_CLOSE), any(), anyOrNull())
        assertTrue(pinnedLayerController.isPinned(pinnedTask.taskId))
    }

    @Test
    fun checkFullscreenTaskChanged_keepsPinnedTask() {
        val pinnedTask = setupTask(displayId = DEFAULT_DISPLAY)
        pinTask(pinnedTask)

        val fullscreenTask = setupTask(displayId = DEFAULT_DISPLAY, taskId = 100)
        fullscreenTask.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN

        val change = TransitionInfo.Change(MockToken.token(), mock())
        change.taskInfo = fullscreenTask
        change.mode = TRANSIT_CHANGE

        val info = TransitionInfo(TRANSIT_CHANGE, FLAG_NONE)
        info.addChange(change)

        pinnedLayerController.onTransitionReady(mock(), info, startTransaction, finishTransaction)

        verify(transitions, never()).startTransition(eq(TRANSIT_CLOSE), any(), anyOrNull())
        assertTrue(pinnedLayerController.isPinned(pinnedTask.taskId))
    }

    @Test
    fun checkFullscreenTaskOnDifferentDisplay_doesNotClosePinnedTask() {
        val pinnedTask = setupTask(displayId = DEFAULT_DISPLAY)
        pinTask(pinnedTask)

        val fullscreenTask = setupTask(displayId = DISPLAY_1, taskId = 100)
        fullscreenTask.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN

        val change = TransitionInfo.Change(MockToken.token(), mock())
        change.taskInfo = fullscreenTask
        change.mode = TRANSIT_OPEN

        val info = TransitionInfo(TRANSIT_OPEN, FLAG_NONE)
        info.addChange(change)

        pinnedLayerController.onTransitionReady(mock(), info, startTransaction, finishTransaction)

        verify(transitions, never()).startTransition(eq(TRANSIT_CLOSE), any(), anyOrNull())
    }

    @Test
    fun checkNonFullscreenTaskOpened_doesNotClosePinnedTask() {
        val pinnedTask = setupTask(displayId = DEFAULT_DISPLAY)
        pinTask(pinnedTask)

        val otherTask = setupTask(displayId = DEFAULT_DISPLAY, taskId = 100)
        otherTask.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FREEFORM

        val change = TransitionInfo.Change(MockToken.token(), mock())
        change.taskInfo = otherTask
        change.mode = TRANSIT_OPEN

        val info = TransitionInfo(TRANSIT_OPEN, FLAG_NONE)
        info.addChange(change)

        pinnedLayerController.onTransitionReady(mock(), info, startTransaction, finishTransaction)

        verify(transitions, never()).startTransition(eq(TRANSIT_CLOSE), any(), anyOrNull())
    }

    @Test
    fun checkWallpaperChange_doesNotClosePinnedTask() {
        val pinnedTask = setupTask(displayId = DEFAULT_DISPLAY)
        pinTask(pinnedTask)

        val fullscreenTask = setupTask(displayId = DEFAULT_DISPLAY, taskId = 100)
        fullscreenTask.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN

        val change = TransitionInfo.Change(MockToken.token(), mock())
        change.taskInfo = fullscreenTask
        change.mode = TRANSIT_OPEN
        change.flags = FLAG_IS_WALLPAPER

        val info = TransitionInfo(TRANSIT_OPEN, FLAG_NONE)
        info.addChange(change)

        pinnedLayerController.onTransitionReady(mock(), info, startTransaction, finishTransaction)

        verify(transitions, never()).startTransition(eq(TRANSIT_CLOSE), any(), anyOrNull())
    }

    @Test
    fun expandedFloatingEligibility_excludesMiniStashMinimizedAndOtherDisplays() {
        assertFalse(pinnedLayerController.hasExpandedFloatingWindows())
        val task = setupTask(taskId = 201).apply { isVisible = true }
        pinTask(task)
        task.isFloatingWindow = true
        assertTrue(pinnedLayerController.hasExpandedFloatingWindows())
        val entry = presentationController.miniState.enter(task.userId, task.taskId,
            FloatingMiniState.Restore(Rect(DEFAULT_TASK_BOUNDS), Rect(DEFAULT_TASK_BOUNDS), 1f),
            Rect(0, 0, 100, 100), .3f)
        assertFalse(pinnedLayerController.hasExpandedFloatingWindows())
        entry.edge = FloatingMiniState.Edge.LEFT
        assertFalse(pinnedLayerController.hasExpandedFloatingWindows())
        presentationController.miniState.remove(task.userId, task.taskId)
        task.isVisible = false
        assertFalse(pinnedLayerController.hasExpandedFloatingWindows())
        task.isVisible = true
        task.displayId = 1
        assertFalse(pinnedLayerController.hasExpandedFloatingWindows())
        task.displayId = DEFAULT_DISPLAY
        pinnedLayerController.minimizeTask(task)
        assertFalse(pinnedLayerController.hasExpandedFloatingWindows())
    }

    @Test
    fun pinTask_unownedFreeform_isNotFloating() {
        val task = setupTask()
        shellTaskOrganizer.stub { on { getRunningTaskInfo(task.taskId) } doReturn task }

        assertFalse(pinnedLayerController.isPinned(task.taskId))
    }

    @Test
    fun pinTask_secondWindow_doesNotRemoveFirst() {
        val first = setupTask(taskId = 201)
        pinTask(first)
        val second = setupTask(taskId = 202)

        val wct = requireNotNull(pinnedLayerController.pinTask(mock(), second, null))

        assertTrue(pinnedLayerController.isPinned(first.taskId))
        assertFalse(wct.hierarchyOps.any {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REMOVE_TASK
        })
    }

    @Test
    fun pinTask_sixthWindow_minimizesOldestWithoutClosing() {
        val tasks = (201..206).map { setupTask(taskId = it) }
        tasks.take(5).forEach { pinTask(it) }

        val wct = requireNotNull(pinnedLayerController.pinTask(mock(), tasks.last(), null))

        assertTrue(wct.changes[tasks.first().token.asBinder()]?.hidden == true)
        assertTrue(wct.hierarchyOps.any {
            it.container == tasks.first().token.asBinder() &&
                it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REORDER &&
                !it.toTop
        })
        assertFalse(wct.hierarchyOps.any {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REMOVE_TASK
        })
        assertTrue(pinnedLayerController.isPinned(tasks.first().taskId))
    }

    @Test
    fun rotateTask_suppliesMissingStartPresentationBeforeDefaultHandler() {
        assertRotatePresentation(preserveCoreBounds = false)
    }

    @Test
    fun rotateTask_keepsCoreStartPresentationWhenAvailable() {
        assertRotatePresentation(preserveCoreBounds = true)
    }

    @Test
    fun rotateTask_hiddenByKeyguard_doesNotCreateAnAnimationStartPresentation() {
        assertRotatePresentation(preserveCoreBounds = false, hidden = true)
    }

    private fun assertRotatePresentation(preserveCoreBounds: Boolean, hidden: Boolean = false) {
        if (hidden) {
            val shell = mock<ShellController>()
            pinnedLayerController.configureVisibility(shell, mock())
            val listener = argumentCaptor<KeyguardChangeListener>()
            verify(shell).addKeyguardChangeListener(listener.capture())
            listener.firstValue.onKeyguardVisibilityChanged(true, false, false)
        }
        val task = setupTask(taskId = 201).apply {
            isFloatingWindow = true
            floatingWindowScale = .75f
            positionInParent = android.graphics.Point(300, 100)
            configuration.windowConfiguration.setBounds(Rect(0, 0, 800, 1200))
        }
        val start = Rect(task.presentationBounds)
        val transition = mock<IBinder>()
        whenever(transitions.startTransition(eq(TRANSIT_CHANGE), any(), anyOrNull()))
            .thenReturn(transition)
        assertTrue(pinnedLayerController.rotateTask(task))
        val change = TransitionInfo.Change(task.token, leash).apply {
            taskInfo = task
            mode = TRANSIT_CHANGE
            setStartAbsBounds(Rect(0, 0, 800, 1200))
            setEndAbsBounds(Rect(0, 0, 1200, 800))
            if (preserveCoreBounds) setStartFloatingWindowBounds(Rect(1, 2, 301, 502))
        }
        val info = TransitionInfo(TRANSIT_CHANGE, FLAG_NONE).apply { addChange(change) }
        pinnedLayerController.onTransitionReady(transition, info,
            startTransaction, finishTransaction)
        assertEquals(if (preserveCoreBounds) Rect(1, 2, 301, 502)
            else if (hidden) Rect() else start, change.startFloatingWindowBounds)
    }

    @Test
    fun rootBackOverHome_startsBackgroundExitAndClearsInterception() {
        val task = setupTask(bounds = Rect(100, 200, 900, 800), taskId = 201).apply { isFloatingWindow = true }
        pinTask(task)

        pinnedLayerController.onFloatingTaskRootBack(task)

        val wct = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(eq(TRANSIT_TO_BACK), wct.capture(), anyOrNull())
        val change = requireNotNull(wct.firstValue.changes[task.token.asBinder()])
        assertFalse(change.floatingWindow)
        assertFalse(change.interceptBackPressed)
        assertEquals(WINDOWING_MODE_UNDEFINED, change.windowingMode)
        assertFalse(wct.firstValue.hierarchyOps.any { it.toTop })
    }

    @Test
    fun rootBackAfterHomeFocus_doesNotTargetAnotherFloatingTask() {
        val home = setupTask().apply {
            isFloatingWindow = false
            configuration.windowConfiguration.setActivityType(
                android.app.WindowConfiguration.ACTIVITY_TYPE_HOME)
        }
        pinnedLayerController.onFloatingTaskRootBack(home)
        verify(transitions, never()).startTransition(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun appMoveToBack_leavesFloatingModeWithoutMinimizeHandleOrForegroundReorder() {
        assertAppDepartureLeavesFloating(TRANSIT_TO_BACK)
    }

    @Test
    fun appFinish_clearsSurvivingRecentTaskAfterTransition() {
        org.mockito.kotlin.doAnswer {
            it.getArgument<Runnable>(0).run()
            null
        }.whenever(transitions).runOnIdle(any())
        assertAppDepartureLeavesFloating(TRANSIT_CLOSE)
    }

    private fun assertAppDepartureLeavesFloating(mode: Int) {
        val task = setupTask(bounds = Rect(100, 200, 900, 800), taskId = 201).apply { isFloatingWindow = true }
        pinTask(task)
        task.configuration.windowConfiguration.setAlwaysOnTop(false)
        task.isVisible = false
        val info = TransitionInfo(mode, FLAG_NONE).apply {
            addChange(TransitionInfo.Change(task.token, leash).apply {
                taskInfo = task
                this.mode = mode
            })
        }

        pinnedLayerController.onTransitionReady(mock(), info, startTransaction, finishTransaction)

        assertTrue(pinnedLayerController.getPinnedTasks().isEmpty())
        val field = PinnedLayerController::class.java.getDeclaredField("minimizedTasks")
            .apply { isAccessible = true }
        assertTrue((field.get(pinnedLayerController) as Set<*>).isEmpty())
        val wct = argumentCaptor<WindowContainerTransaction>()
        verify(shellTaskOrganizer).applyTransaction(wct.capture())
        val change = requireNotNull(wct.firstValue.changes[task.token.asBinder()])
        assertFalse(change.floatingWindow)
        assertFalse(change.disablePip)
        assertFalse(change.hidden)
        assertEquals(WINDOWING_MODE_UNDEFINED, change.windowingMode)
        assertTrue(requireNotNull(wct.firstValue.findBoundsChange(task)).isEmpty)
        assertTrue(wct.firstValue.hierarchyOps.any {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_ALWAYS_ON_TOP &&
                !it.isAlwaysOnTop
        })
        assertFalse(wct.firstValue.hierarchyOps.any { it.toTop })
        // A normal relaunch must not enter the retained-minimized restoration branch.
        task.isFloatingWindow = false
        task.configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN)
        val launch = TransitionInfo(TRANSIT_TO_FRONT, FLAG_NONE).apply {
            addChange(TransitionInfo.Change(task.token, leash).apply {
                taskInfo = task
                this.mode = TRANSIT_TO_FRONT
            })
        }
        pinnedLayerController.onTransitionReady(mock(), launch, startTransaction, finishTransaction)
        assertTrue(pinnedLayerController.getPinnedTasks().isEmpty())
    }

    @Test
    fun appMoveToBack_existingMiniRetainsFloatingIdentity() {
        val task = setupTask(bounds = Rect(100, 200, 900, 800), taskId = 201).apply { isFloatingWindow = true }
        pinTask(task)
        presentationController.miniState.enter(task.userId, task.taskId,
            FloatingMiniState.Restore(Rect(DEFAULT_TASK_BOUNDS), Rect(DEFAULT_TASK_BOUNDS), 1f),
            Rect(0, 0, 100, 100), .3f)
        task.configuration.windowConfiguration.setAlwaysOnTop(false)
        val info = TransitionInfo(TRANSIT_TO_BACK, FLAG_NONE).apply {
            addChange(TransitionInfo.Change(task.token, leash).apply {
                taskInfo = task
                mode = TRANSIT_TO_BACK
            })
        }
        pinnedLayerController.onTransitionReady(mock(), info, startTransaction, finishTransaction)
        assertTrue(pinnedLayerController.isPinned(task.taskId))
        assertTrue(pinnedLayerController.isMini(task))
        verify(shellTaskOrganizer, never()).applyTransaction(any())
    }

    @Test
    fun sleepWakeToBackParticipants_keepFloatingWithoutKeyguardCallback() {
        for ((id, type) in listOf(201 to android.view.WindowManager.TRANSIT_SLEEP,
                202 to android.view.WindowManager.TRANSIT_WAKE)) {
            val task = setupTask(bounds = Rect(100, 200, 900, 800), taskId = id).apply { isFloatingWindow = true }
            pinTask(task)
            task.configuration.windowConfiguration.setAlwaysOnTop(false)
            val info = TransitionInfo(type, FLAG_NONE).apply {
                addChange(TransitionInfo.Change(task.token, leash).apply {
                    taskInfo = task
                    mode = TRANSIT_TO_BACK
                })
            }
            pinnedLayerController.onTransitionReady(mock(), info, startTransaction, finishTransaction)
            assertTrue(pinnedLayerController.getPinnedTasks().any { it.taskId == task.taskId })
        }
        verify(shellTaskOrganizer, never()).applyTransaction(any())
    }

    @Test
    fun keyguardToBackParticipant_doesNotExitBeforeVisibilityCallback() {
        val task = setupTask(bounds = Rect(100, 200, 900, 800), taskId = 201).apply { isFloatingWindow = true }
        pinTask(task)
        task.configuration.windowConfiguration.setAlwaysOnTop(false)
        val info = TransitionInfo(TRANSIT_TO_FRONT,
            android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_APPEARING).apply {
            addChange(TransitionInfo.Change(task.token, leash).apply {
                taskInfo = task
                mode = TRANSIT_TO_BACK
            })
        }
        pinnedLayerController.onTransitionReady(mock(), info, startTransaction, finishTransaction)
        assertTrue(pinnedLayerController.getPinnedTasks().any { it.taskId == task.taskId })
        verify(shellTaskOrganizer, never()).applyTransaction(any())
    }

    @Test
    fun controllerBackExit_doesNotRememberTaskJustBecauseChangeMovesToBack() {
        val task = setupTask(bounds = Rect(100, 200, 900, 800), taskId = 201).apply { isFloatingWindow = true }
        pinTask(task)
        val transition = mock<IBinder>()
        val wct = pinnedLayerController.unpinTask(transition, task,
            PinnedLayerController.UnpinStrategy.BACK)
        assertEquals(WINDOWING_MODE_UNDEFINED,
            requireNotNull(wct.changes[task.token.asBinder()]).windowingMode)
        // Core has already applied the exit WCT when it constructs TransitionInfo.
        task.isFloatingWindow = false
        val info = TransitionInfo(TRANSIT_TO_BACK, FLAG_NONE).apply {
            addChange(TransitionInfo.Change(task.token, leash).apply {
                taskInfo = task
                mode = TRANSIT_TO_BACK
            })
        }
        pinnedLayerController.onTransitionReady(transition, info, startTransaction, finishTransaction)
        assertTrue(pinnedLayerController.getPinnedTasks().isEmpty())
    }

    @Test
    fun maximizeTask_clearsFloatingAndKeepsTask() {
        val task = setupTask(taskId = 201)
        pinTask(task)

        assertTrue(pinnedLayerController.maximizeTask(task))

        val captor = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(eq(TRANSIT_CHANGE), captor.capture(), anyOrNull())
        val wct = captor.firstValue
        assertEquals(WINDOWING_MODE_FULLSCREEN,
            wct.changes[task.token.asBinder()]?.windowingMode)
        val change = requireNotNull(wct.changes[task.token.asBinder()])
        assertTrue(change.changeMask and
            WindowContainerTransaction.Change.CHANGE_FLOATING_WINDOW != 0)
        assertFalse(change.floatingWindow)
        assertTrue(wct.hierarchyOps.any {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_ALWAYS_ON_TOP &&
                it.container == task.token.asBinder() && !it.isAlwaysOnTop
        })
        assertFalse(wct.hierarchyOps.any {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REMOVE_TASK
        })
    }

    @Test
    fun pinRequest_restoresMinimizedFloatingTaskAndAppliesCapacity() {
        val tasks = (401..406).map { setupTask(taskId = it) }
        tasks.forEach { pinTask(it) }

        val wct = requireNotNull(pinnedLayerController.pinTask(mock(), tasks.first(), null))

        assertFalse(requireNotNull(wct.changes[tasks.first().token.asBinder()]).hidden)
        assertTrue(requireNotNull(wct.changes[tasks[1].token.asBinder()]).hidden)
        assertTrue(wct.hierarchyOps.any {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REPARENT &&
                it.container == tasks.first().token.asBinder() && it.toTop
        })
    }

    @Test
    fun pendingIntentLaunch_restoresMinimizedFloatingTaskAndAppliesCapacity() {
        assertLaunchRestoresMinimizedFloatingTask(TRANSIT_OPEN)
    }

    @Test
    fun recentTaskLaunch_restoresMinimizedFloatingTaskAndAppliesCapacity() {
        assertLaunchRestoresMinimizedFloatingTask(TRANSIT_TO_FRONT)
    }

    private fun assertLaunchRestoresMinimizedFloatingTask(mode: Int) {
        val tasks = (301..306).map { setupTask(taskId = it) }
        tasks.forEach { pinTask(it) }
        val restored = tasks.first()
        restored.isFloatingWindow = true
        restored.configuration.windowConfiguration.setAlwaysOnTop(mode == TRANSIT_OPEN)
        val info = TransitionInfo(mode, FLAG_NONE)
        info.addChange(TransitionInfo.Change(restored.token, mock()).apply {
            taskInfo = restored
            this.mode = mode
        })

        pinnedLayerController.onTransitionReady(mock(), info, startTransaction, finishTransaction)

        val captor = argumentCaptor<WindowContainerTransaction>()
        verify(shellTaskOrganizer).applyTransaction(captor.capture())
        val wct = captor.firstValue
        assertFalse(requireNotNull(wct.changes[restored.token.asBinder()]).hidden)
        assertTrue(requireNotNull(wct.changes[tasks[1].token.asBinder()]).hidden)
        assertTrue(wct.hierarchyOps.any {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REORDER &&
                it.container == restored.token.asBinder() && it.toTop
        })
        assertTrue(wct.hierarchyOps.any {
            it.type == WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_ALWAYS_ON_TOP &&
                it.container == restored.token.asBinder() && it.isAlwaysOnTop
        })
        assertEquals(restored.taskId, pinnedLayerController.getCurrentPinnedTask()?.taskId)
        assertTrue(pinnedLayerController.isPinned(tasks[1].taskId))
    }

    @Test
    fun keyguardVisibility_hidesAndRestoresFloatingTask() {
        val task = setupTask(taskId = 201)
        pinTask(task)
        whenever(shellTaskOrganizer.getRunningTasks(DEFAULT_DISPLAY)).thenReturn(arrayListOf(task))
        val shellController = mock<ShellController>()
        pinnedLayerController.configureVisibility(shellController, mock<RecentsTransitionHandler>())
        val listener = argumentCaptor<KeyguardChangeListener>()
        verify(shellController).addKeyguardChangeListener(listener.capture())

        listener.firstValue.onKeyguardVisibilityChanged(true, false, false)
        listener.firstValue.onKeyguardVisibilityChanged(false, false, false)

        val wct = argumentCaptor<WindowContainerTransaction>()
        verify(shellTaskOrganizer, org.mockito.kotlin.times(2)).applyTransaction(wct.capture())
        assertTrue(wct.firstValue.changes[task.token.asBinder()]!!.hidden)
        assertFalse(wct.lastValue.changes[task.token.asBinder()]!!.hidden)
        assertTrue(pinnedLayerController.isPinned(task.taskId))
    }

    @Test
    fun overviewVisibility_hidesAndRestoresFloatingTask() {
        val task = setupTask(taskId = 201)
        pinTask(task)
        whenever(shellTaskOrganizer.getRunningTasks(DEFAULT_DISPLAY)).thenReturn(arrayListOf(task))
        val shellController = mock<ShellController>()
        pinnedLayerController.configureVisibility(shellController, mock<RecentsTransitionHandler>())
        val listener = argumentCaptor<OverviewVisibilityChangeListener>()
        verify(shellController).addOverviewVisibilityChangeListener(listener.capture())

        listener.firstValue.onOverviewShown(DEFAULT_DISPLAY)
        listener.firstValue.onOverviewHidden(DEFAULT_DISPLAY)

        val wct = argumentCaptor<WindowContainerTransaction>()
        verify(shellTaskOrganizer, org.mockito.kotlin.times(2)).applyTransaction(wct.capture())
        assertTrue(wct.firstValue.changes[task.token.asBinder()]!!.hidden)
        assertFalse(wct.lastValue.changes[task.token.asBinder()]!!.hidden)
    }

    @Test
    fun recentsRunning_floatingTaskOnlyHidesForOverviewAndKeyguard() {
        assertRecentsVisibility(mini = false)
    }

    @Test
    fun recentsRunning_miniTaskOnlyHidesForOverviewAndKeyguard() {
        assertRecentsVisibility(mini = true)
    }

    private fun assertRecentsVisibility(mini: Boolean) {
        val task = setupTask(taskId = 201).apply { isFloatingWindow = true }
        pinTask(task)
        if (mini) {
            presentationController.miniState.enter(task.userId, task.taskId,
                FloatingMiniState.Restore(Rect(DEFAULT_TASK_BOUNDS), Rect(DEFAULT_TASK_BOUNDS), 1f),
                Rect(0, 0, 100, 100), .3f)
        }
        val shellController = mock<ShellController>()
        val recents = mock<RecentsTransitionHandler>()
        pinnedLayerController.configureVisibility(shellController, recents)
        val state = argumentCaptor<RecentsTransitionStateListener>()
        val overview = argumentCaptor<OverviewVisibilityChangeListener>()
        val keyguard = argumentCaptor<KeyguardChangeListener>()
        verify(recents).addTransitionStateListener(state.capture())
        verify(shellController).addOverviewVisibilityChangeListener(overview.capture())
        verify(shellController).addKeyguardChangeListener(keyguard.capture())

        state.firstValue.onTransitionStateChanged(TRANSITION_STATE_REQUESTED, DEFAULT_DISPLAY)
        state.firstValue.onTransitionStateChanged(TRANSITION_STATE_ANIMATING, DEFAULT_DISPLAY)
        overview.firstValue.onOverviewShown(DEFAULT_DISPLAY)
        overview.firstValue.onOverviewHidden(DEFAULT_DISPLAY)
        keyguard.firstValue.onKeyguardVisibilityChanged(true, false, false)
        keyguard.firstValue.onKeyguardVisibilityChanged(false, false, false)

        val transactions = argumentCaptor<WindowContainerTransaction>()
        verify(shellTaskOrganizer, org.mockito.kotlin.times(6))
            .applyTransaction(transactions.capture())
        assertEquals(listOf(false, false, true, false, true, false),
            transactions.allValues.map { it.changes[task.token.asBinder()]!!.hidden })
        verify(recents, org.mockito.kotlin.times(2)).setFloatingTasksHidden(DEFAULT_DISPLAY, true)
        verify(recents, org.mockito.kotlin.times(4)).setFloatingTasksHidden(DEFAULT_DISPLAY, false)
        assertTrue(pinnedLayerController.isPinned(task.taskId))
    }

    @Test
    fun recentsHomeFinish_overviewStillVisible_keepsTasksAndHandlesHidden() {
        val task = setupTask(taskId = 201).apply { isFloatingWindow = true }
        pinTask(task)
        val handles = mock<FloatingMinimizeHandles>()
        PinnedLayerController::class.java.getDeclaredField("minimizeHandles").apply {
            isAccessible = true
            set(pinnedLayerController, handles)
        }
        val shellController = mock<ShellController>()
        val recents = mock<RecentsTransitionHandler>()
        pinnedLayerController.configureVisibility(shellController, recents)
        val state = argumentCaptor<RecentsTransitionStateListener>()
        val overview = argumentCaptor<OverviewVisibilityChangeListener>()
        verify(recents).addTransitionStateListener(state.capture())
        verify(shellController).addOverviewVisibilityChangeListener(overview.capture())
        org.mockito.kotlin.doAnswer {
            it.getArgument<Runnable>(0).run()
            null
        }.whenever(transitions).runOnIdle(any())
        whenever(shellController.isOverviewVisible(DEFAULT_DISPLAY)).thenReturn(true)
        overview.firstValue.onOverviewShown(DEFAULT_DISPLAY)
        org.mockito.Mockito.clearInvocations(shellTaskOrganizer, handles, recents)

        state.firstValue.onTransitionFinishedToHome(DEFAULT_DISPLAY)

        val transactions = argumentCaptor<WindowContainerTransaction>()
        verify(shellTaskOrganizer).applyTransaction(transactions.capture())
        assertTrue(transactions.firstValue.changes[task.token.asBinder()]!!.hidden)
        verify(handles).setVisible(false)
        verify(handles, never()).setVisible(true)
        verify(recents).setFloatingTasksHidden(DEFAULT_DISPLAY, true)

        whenever(shellController.isOverviewVisible(DEFAULT_DISPLAY)).thenReturn(false)
        overview.firstValue.onOverviewHidden(DEFAULT_DISPLAY)
        verify(handles).setVisible(true)
        verify(recents).setFloatingTasksHidden(DEFAULT_DISPLAY, false)
    }

    @Test
    fun recentsRunning_entryAndRestoreKeepFloatingTasksVisible() {
        val task = setupTask(taskId = 201).apply { isFloatingWindow = true }
        pinTask(task)
        assertTrue(pinnedLayerController.minimizeTask(task))
        val recents = mock<RecentsTransitionHandler>()
        pinnedLayerController.configureVisibility(mock<ShellController>(), recents)
        val state = argumentCaptor<RecentsTransitionStateListener>()
        verify(recents).addTransitionStateListener(state.capture())
        state.firstValue.onTransitionStateChanged(TRANSITION_STATE_ANIMATING, DEFAULT_DISPLAY)

        val entering = setupTask(taskId = 202)
        val prepared = WindowContainerTransaction()
        assertTrue(pinnedLayerController.prepareFloatingTask(entering, prepared))
        assertFalse(prepared.changes[entering.token.asBinder()]!!.hidden)
        val pinning = setupTask(taskId = 203)
        val pinned = pinnedLayerController.pinTask(mock(), pinning, null)
        assertNotNull(pinned)
        assertFalse(pinned.changes[pinning.token.asBinder()]!!.hidden)

        org.mockito.Mockito.clearInvocations(shellTaskOrganizer)
        hdrTransition(task, TRANSIT_TO_FRONT)

        val restored = argumentCaptor<WindowContainerTransaction>()
        verify(shellTaskOrganizer, org.mockito.kotlin.atLeastOnce())
            .applyTransaction(restored.capture())
        restored.allValues.forEach {
            assertFalse(it.changes[task.token.asBinder()]!!.hidden)
        }
    }

    private fun setupTask(
        displayId: Int = DEFAULT_DISPLAY,
        bounds: Rect = DEFAULT_TASK_BOUNDS,
        taskId: Int? = null,
    ): RunningTaskInfo {
        val task =
            DesktopTestHelpers.createFreeformTask(
                displayId = displayId,
                bounds = bounds,
                taskId = taskId,
            )
        return task
    }

    private fun setupDisplay(
        displayId: Int = DEFAULT_DISPLAY,
        token: WindowContainerToken = MockToken.token(),
        isDesktopModeSupported: Boolean = true,
    ) {
        desktopState.overrideDesktopModeSupportPerDisplay[displayId] = isDesktopModeSupported
        shellDesktopState.overrideWindowDropTargetEligibility[displayId] = isDesktopModeSupported

        whenever(rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(displayId))
            .thenReturn(DisplayAreaInfo(token, displayId, DisplayAreaOrganizer.FEATURE_UNDEFINED))

        // Default display setup: 1080x1920, density 1.0, insets top=100 bottom=50
        // 10px padding for pinned window
        val testableResources = mContext.getOrCreateTestableResources()
        testableResources.addOverride(R.dimen.pinned_window_init_padding, 10)

        val displayMetrics = DisplayMetrics()
        displayMetrics.density = 1.0f

        val displayLayout = mock<DisplayLayout>()
        whenever(testableResources.resources.displayMetrics).thenReturn(displayMetrics)
        whenever(displayController.getDisplayLayout(displayId)).thenReturn(displayLayout)
        whenever(displayController.getDisplayContext(displayId)).thenReturn(context)
        whenever(displayLayout.width()).thenReturn(DISPLAY_WIDTH)
        whenever(displayLayout.height()).thenReturn(DISPLAY_HEIGHT)
        whenever(displayLayout.stableInsets()).thenReturn(DISPLAY_STABLE_INSETS)
        whenever(displayLayout.getStableBounds(any())).thenAnswer {
            val outRect = it.getArgument<Rect>(0)
            outRect.set(DISPLAY_STABLE_BOUNDS)
        }
    }

    private fun pinTask(task: RunningTaskInfo) {
        val transition = mock<IBinder>()
        val transitionInfo = TransitionInfo(TRANSIT_CHANGE, FLAG_NONE)

        shellTaskOrganizer.stub { on { getRunningTaskInfo(task.taskId) } doReturn task }
        pinnedLayerController.pinTask(transition, task, null)
        pinnedLayerController.onTransitionReady(
            transition,
            transitionInfo,
            startTransaction,
            finishTransaction,
        )
    }

    private fun WindowContainerTransaction.findBoundsChange(task: RunningTaskInfo): Rect? =
        changes.entries
            .find { (token, change) ->
                token == task.token.asBinder() &&
                    (change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0
            }
            ?.value
            ?.configuration
            ?.windowConfiguration
            ?.bounds

    private companion object {
        private const val DEFAULT_DISPLAY = 0
        private const val DISPLAY_1 = 1
        private const val MIN_WINDOW_SIZE = 220
        private const val DISPLAY_WIDTH = 1920
        private const val DISPLAY_HEIGHT = 1080

        private val DISPLAY_STABLE_INSETS = Rect(0, 100, 0, 50)
        private val DEFAULT_TASK_BOUNDS =
            Rect(
                0,
                DISPLAY_STABLE_INSETS.top,
                MIN_WINDOW_SIZE,
                MIN_WINDOW_SIZE + DISPLAY_STABLE_INSETS.top,
            )

        private val DISPLAY_STABLE_BOUNDS =
            Rect(
                DISPLAY_STABLE_INSETS.left,
                DISPLAY_STABLE_INSETS.top,
                DISPLAY_WIDTH - DISPLAY_STABLE_INSETS.right,
                DISPLAY_HEIGHT - DISPLAY_STABLE_INSETS.bottom,
            )
    }
    @Test
    fun homeGesture_oneFloatingWindow_doesNotReorderPreviousApp() {
        assertHomeVisibility(1, false)
    }

    @Test
    fun homeGesture_twoFloatingWindows_doesNotReorderPreviousApp() {
        assertHomeVisibility(2, false)
    }

    @Test
    fun recentsToHome_oneFloatingWindow_restoresAboveHome() {
        assertHomeVisibility(1, true)
    }

    @Test
    fun recentsToHome_twoFloatingWindows_restoresAboveHome() {
        assertHomeVisibility(2, true)
    }

    private fun assertHomeVisibility(count: Int, overview: Boolean) {
        val tasks = (0 until count).map { setupTask(taskId = 201 + it).also(::pinTask) }
        val shellController = mock<ShellController>()
        val recents = mock<RecentsTransitionHandler>()
        pinnedLayerController.configureVisibility(shellController, recents)
        val listener = argumentCaptor<com.android.wm.shell.recents.RecentsTransitionStateListener>()
        verify(recents).addTransitionStateListener(listener.capture())
        whenever(shellController.isOverviewVisible(DEFAULT_DISPLAY)).thenReturn(overview)
        org.mockito.kotlin.doAnswer { invocation ->
            invocation.getArgument<Runnable>(0).run()
            null
        }.whenever(transitions).runOnIdle(any())
        listener.firstValue.onTransitionStateChanged(3, DEFAULT_DISPLAY)
        listener.firstValue.onTransitionStateChanged(1, DEFAULT_DISPLAY)
        whenever(shellController.isOverviewVisible(DEFAULT_DISPLAY)).thenReturn(false)
        listener.firstValue.onTransitionFinishedToHome(DEFAULT_DISPLAY)
        val transactions = argumentCaptor<WindowContainerTransaction>()
        verify(shellTaskOrganizer, org.mockito.kotlin.atLeastOnce())
            .applyTransaction(transactions.capture())
        transactions.allValues.forEach { transaction ->
            assertTrue(transaction.hierarchyOps.isEmpty())
            if (!overview) {
                tasks.forEach { assertFalse(transaction.changes[it.token.asBinder()]!!.hidden) }
            }
        }
        tasks.forEach {
            assertFalse(transactions.lastValue.changes[it.token.asBinder()]!!.hidden)
            assertTrue(pinnedLayerController.isPinned(it.taskId))
        }
    }


    @Test
    fun deskMinimizedEntry_retainsFloatingIdentityAndEdgeRestoreGeometry() {
        val task = setupTask(bounds = Rect(100, 100, 700, 900))
        val wct = WindowContainerTransaction()
        assertTrue(pinnedLayerController.prepareFloatingTask(task, wct,
            task.configuration.windowConfiguration.bounds, true))
        assertTrue(pinnedLayerController.isPinned(task.taskId))
        assertTrue(wct.changes[task.token.asBinder()]!!.hidden)
        assertEquals(Rect(100, 100, 700, 900), task.configuration.windowConfiguration.bounds)
        assertTrue(wct.hierarchyOps.any { !it.toTop })
    }

}
