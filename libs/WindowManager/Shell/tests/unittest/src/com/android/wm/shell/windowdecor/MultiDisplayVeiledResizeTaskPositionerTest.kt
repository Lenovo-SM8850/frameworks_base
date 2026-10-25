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

import android.app.ActivityManager
import android.app.WindowConfiguration
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Region
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.platform.test.annotations.EnableFlags
import android.testing.AndroidTestingRunner
import android.testing.TestableResources
import android.view.Display
import android.view.Surface.ROTATION_0
import android.view.Surface.ROTATION_270
import android.view.Surface.ROTATION_90
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import android.view.WindowManager.TRANSIT_CHANGE
import android.window.TransitionInfo
import android.window.WindowContainerToken
import androidx.test.filters.SmallTest
import androidx.test.internal.runner.junit4.statement.UiThreadStatement.runOnUiThread
import com.android.internal.jank.InteractionJankMonitor
import com.android.window.flags.Flags
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.MultiDisplayDragMoveIndicatorController
import com.android.wm.shell.common.MultiDisplayTestUtil.TestDisplay
import com.android.wm.shell.desktopmode.DesktopScrimController
import com.android.wm.shell.desktopmode.DesktopTasksController
import com.android.wm.shell.desktopmode.DesktopUserRepositories
import com.android.wm.shell.desktopmode.data.DesktopRepository
import com.android.wm.shell.shared.desktopmode.FakeDesktopState
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.TransitionFinishCallback
import com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_BOTTOM
import com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_LEFT
import com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_RIGHT
import com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_TOP
import com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_UNDEFINED
import com.android.wm.shell.windowdecor.DragPositioningCallback.INPUT_METHOD_TYPE_UNKNOWN
import java.util.function.Supplier
import junit.framework.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * Tests for [MultiDisplayVeiledResizeTaskPositioner].
 *
 * Build/Install/Run: atest WMShellUnitTests:MultiDisplayVeiledResizeTaskPositionerTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class MultiDisplayVeiledResizeTaskPositionerTest : ShellTestCase() {

    private val mockShellTaskOrganizer = mock<ShellTaskOrganizer>()
    private val mockWindowDecoration = mock<WindowDecorationWrapper>()
    private val mockDragEventListener = mock<DragPositioningCallbackUtility.DragEventListener>()

    private val taskToken = mock<WindowContainerToken>()
    private val taskBinder = mock<IBinder>()

    private val mockDisplayController = mock<DisplayController>()
    private val mockDisplay = mock<Display>()
    private val mockTransactionFactory = mock<Supplier<SurfaceControl.Transaction>>()
    private val mockTransaction = mock<SurfaceControl.Transaction>()
    private val mockTransitionBinder = mock<IBinder>()
    private val mockTransitionInfo = mock<TransitionInfo>()
    private val mockFinishCallback = mock<TransitionFinishCallback>()
    private val mockTransitions = mock<Transitions>()
    private val mockInteractionJankMonitor = mock<InteractionJankMonitor>()
    private val mockSurfaceControl = mock<SurfaceControl>()
    private val mockMultiDisplayDragMoveIndicatorController =
        mock<MultiDisplayDragMoveIndicatorController>()
    private val mockDesktopTasksController = mock<DesktopTasksController>()
    private val mockDesktopUserRepositories = mock<DesktopUserRepositories>()
    private val mockDesktopRepository = mock<DesktopRepository>()
    private val mockResizeBinder = mock<IBinder>()
    private val mockDesktopScrimController = mock<DesktopScrimController>()
    private lateinit var resources: TestableResources
    private lateinit var spyDisplayLayout0: DisplayLayout
    private lateinit var spyDisplayLayout1: DisplayLayout
    private val desktopState = FakeDesktopState()

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var taskPositioner: MultiDisplayVeiledResizeTaskPositioner

    @Before
    fun setUp() {
        whenever(taskToken.asBinder()).thenReturn(taskBinder)
        whenever(mockWindowDecoration.display).thenReturn(mockDisplay)
        whenever(mockWindowDecoration.decorWindowContext).thenReturn(mContext)
        resources = mContext.orCreateTestableResources
        val resourceConfiguration = Configuration()
        resourceConfiguration.uiMode = 0
        resources.overrideConfiguration(resourceConfiguration)
        spyDisplayLayout0 = TestDisplay.DISPLAY_0.getSpyDisplayLayout(resources.resources)
        spyDisplayLayout1 = TestDisplay.DISPLAY_1.getSpyDisplayLayout(resources.resources)

        whenever(mockDisplayController.getDisplayLayout(DISPLAY_ID_0)).thenReturn(spyDisplayLayout0)
        whenever(mockDisplayController.getDisplayLayout(DISPLAY_ID_1)).thenReturn(spyDisplayLayout1)
        whenever(spyDisplayLayout0.densityDpi()).thenReturn(DENSITY_DPI)
        whenever(spyDisplayLayout1.densityDpi()).thenReturn(DENSITY_DPI)
        doAnswer { i ->
                val rect = i.getArgument<Rect>(0)
                if (
                    mockWindowDecoration.taskInfo.configuration.windowConfiguration
                        .displayRotation == ROTATION_90 ||
                        mockWindowDecoration.taskInfo.configuration.windowConfiguration
                            .displayRotation == ROTATION_270
                ) {
                    rect.set(STABLE_BOUNDS_LANDSCAPE)
                } else {
                    rect.set(STABLE_BOUNDS_PORTRAIT)
                }
                null
            }
            .whenever(spyDisplayLayout0)
            .getStableBounds(any())
        whenever(mockTransactionFactory.get()).thenReturn(mockTransaction)
        whenever(mockWindowDecoration.taskSurface).thenReturn(mockSurfaceControl)
        whenever(mockWindowDecoration.exclusionRegion).thenReturn(Region())
        whenever(mockTransaction.setPosition(any(), any(), any())).thenReturn(mockTransaction)
        whenever(mockTransaction.setAlpha(any(), any())).thenReturn(mockTransaction)
        whenever(mockTransaction.setFrameTimeline(any())).thenReturn(mockTransaction)
        whenever(mockTransaction.setMatrix(any(), any<Float>(), any(), any(), any()))
            .thenReturn(mockTransaction)
        whenever(mockWindowDecoration.taskInfo)
            .thenReturn(
                ActivityManager.RunningTaskInfo().apply {
                    taskId = TASK_ID
                    token = taskToken
                    minWidth = MIN_WIDTH
                    minHeight = MIN_HEIGHT
                    defaultMinSize = DEFAULT_MIN
                    displayId = DISPLAY_ID_0
                    configuration.windowConfiguration.setBounds(STARTING_BOUNDS)
                    configuration.windowConfiguration.displayRotation = ROTATION_90
                    isResizeable = true
                }
            )
        whenever(mockWindowDecoration.getValidDragArea()).thenReturn(VALID_DRAG_AREA)
        whenever(mockWindowDecoration.display).thenReturn(mockDisplay)
        whenever(mockDisplay.displayId).thenAnswer { DISPLAY_ID_0 }
        whenever(mockDesktopUserRepositories.getProfile(anyInt())).thenReturn(mockDesktopRepository)
        whenever(mockTransitions.startTransition(any(), any(), any())).thenReturn(mockResizeBinder)
        whenever(mockDesktopTasksController.getDesktopScrimController())
            .thenReturn(mockDesktopScrimController)

        taskPositioner =
            MultiDisplayVeiledResizeTaskPositioner(
                mockShellTaskOrganizer,
                mockWindowDecoration,
                mockDisplayController,
                { mockTransaction },
                mockTransitions,
                mockInteractionJankMonitor,
                mainHandler,
                mockMultiDisplayDragMoveIndicatorController,
                desktopState,
                mockDesktopTasksController,
                mockDesktopUserRepositories,
            )
    }

    @Test
    fun testDragResize_noMove_doesNotShowResizeVeil() = runOnUiThread {
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_TOP or CTRL_TYPE_RIGHT,
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )
        verify(mockWindowDecoration, never()).showResizeVeil(STARTING_BOUNDS)
        verify(spyDisplayLayout0).getStableBounds(any())

        taskPositioner.onDragPositioningEnd(
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
        )

        verify(mockTransitions, never())
            .startTransition(
                eq(TRANSIT_CHANGE),
                argThat { wct ->
                    return@argThat wct.changes.any { (token, change) ->
                        token == taskBinder &&
                            (change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) !=
                                0 &&
                            change.configuration.windowConfiguration.bounds == STARTING_BOUNDS
                    }
                },
                eq(taskPositioner),
            )
        verify(mockWindowDecoration, never()).hideResizeVeil()
        verifyNoInteractions(mockMultiDisplayDragMoveIndicatorController)
    }

    @Test
    fun testDragResize_movesTask_doesNotShowResizeVeil() = runOnUiThread {
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_UNDEFINED,
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        taskPositioner.onDragPositioningMove(
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat() + 60,
            STARTING_BOUNDS.top.toFloat() + 100,
        )

        val endBounds =
            taskPositioner.onDragPositioningEnd(
                DISPLAY_ID_0,
                STARTING_BOUNDS.left.toFloat() + 70,
                STARTING_BOUNDS.top.toFloat() + 20,
            )
        val rectAfterEnd = Rect(STARTING_BOUNDS)
        rectAfterEnd.offset(70, 20)

        verify(mockWindowDecoration, never()).showResizeVeil(any())
        verify(mockWindowDecoration, never()).hideResizeVeil()
        verify(mockMultiDisplayDragMoveIndicatorController, never()).onDragEnd(any(), any())
        Assert.assertEquals(rectAfterEnd, endBounds)
    }

    @Test
    fun testDragResize_movesTask_movesTaskSurfaceOffscreen() = runOnUiThread {
        whenever(spyDisplayLayout0.width()).thenReturn(DISPLAY_BOUNDS.width())
        whenever(spyDisplayLayout0.height()).thenReturn(DISPLAY_BOUNDS.height())

        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_UNDEFINED,
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        taskPositioner.onDragPositioningMove(
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat() + 60,
            STARTING_BOUNDS.top.toFloat() + 100,
        )

        val leftAfterMoveCaptor = argumentCaptor<Float>()
        val topAfterMoveCaptor = argumentCaptor<Float>()
        verify(mockTransaction)
            .setPosition(
                eq(mockSurfaceControl),
                leftAfterMoveCaptor.capture(),
                topAfterMoveCaptor.capture(),
            )
        val rectAfterMove = Rect(STARTING_BOUNDS)
        rectAfterMove.offsetTo(
            leftAfterMoveCaptor.firstValue.toInt(),
            topAfterMoveCaptor.firstValue.toInt(),
        )
        Assert.assertFalse(DISPLAY_BOUNDS.intersect(rectAfterMove))
    }

    @Test
    fun testDragResize_movesTaskOnSameDisplay_noPxDpConversion() = runOnUiThread {
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_UNDEFINED,
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        taskPositioner.onDragPositioningEnd(
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat() + 70,
            STARTING_BOUNDS.top.toFloat() + 20,
        )

        verify(spyDisplayLayout0, never()).localPxToGlobalDp(any(), any())
        verify(spyDisplayLayout0, never()).globalDpToLocalPx(any(), any())
        verify(mockMultiDisplayDragMoveIndicatorController, never()).onDragEnd(any(), any())
    }

    @Test
    fun testDragResize_movesTaskToNewDisplay() = runOnUiThread {
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_UNDEFINED,
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        taskPositioner.onDragPositioningMove(DISPLAY_ID_1, 200f, 1900f)

        val endBounds = taskPositioner.onDragPositioningEnd(DISPLAY_ID_1, 300f, 450f)
        val rectAfterEnd = Rect(300, 450, 500, 650)

        verify(mockWindowDecoration, never()).showResizeVeil(any())
        verify(mockWindowDecoration, never()).hideResizeVeil()
        verify(mockMultiDisplayDragMoveIndicatorController, never()).onDragEnd(any(), any())
        Assert.assertEquals(rectAfterEnd, endBounds)
    }

    @Test
    fun testDragResize_movesTaskToNewDisplay_movesTaskSurfaceOffscreen() = runOnUiThread {
        whenever(spyDisplayLayout0.width()).thenReturn(DISPLAY_BOUNDS.width())
        whenever(spyDisplayLayout0.height()).thenReturn(DISPLAY_BOUNDS.height())

        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_UNDEFINED,
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        taskPositioner.onDragPositioningMove(DISPLAY_ID_1, 200f, 1900f)

        val leftAfterMoveCaptor = argumentCaptor<Float>()
        val topAfterMoveCaptor = argumentCaptor<Float>()
        verify(mockTransaction)
            .setPosition(
                eq(mockSurfaceControl),
                leftAfterMoveCaptor.capture(),
                topAfterMoveCaptor.capture(),
            )
        val rectAfterMove = Rect(STARTING_BOUNDS)
        rectAfterMove.offsetTo(
            leftAfterMoveCaptor.firstValue.toInt(),
            topAfterMoveCaptor.firstValue.toInt(),
        )
        Assert.assertFalse(DISPLAY_BOUNDS.intersect(rectAfterMove))
    }

    @Test
    fun testDragResize_movesTaskToNewDisplayThenBackToOriginalDisplay() = runOnUiThread {
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_UNDEFINED,
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        // Move to the display 1
        taskPositioner.onDragPositioningMove(DISPLAY_ID_1, 200f, 800f)

        // Moving back to the original display
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, 100f, 1500f)

        // Finish the drag move on the original display
        val endBounds = taskPositioner.onDragPositioningEnd(DISPLAY_ID_0, 50f, 50f)
        val rectAfterMove = Rect(50, 50, 150, 150)

        verify(mockWindowDecoration, never()).showResizeVeil(any())
        verify(mockWindowDecoration, never()).hideResizeVeil()
        verify(mockMultiDisplayDragMoveIndicatorController, never()).onDragEnd(any(), any())
        Assert.assertEquals(rectAfterMove, endBounds)
    }

    @Test
    fun testDragResize_movesTaskToNewDisplayThenBackToOriginalDisplay_movesTaskSurfaceOffscreen() =
        runOnUiThread {
            whenever(spyDisplayLayout0.width()).thenReturn(DISPLAY_BOUNDS.width())
            whenever(spyDisplayLayout0.height()).thenReturn(DISPLAY_BOUNDS.height())

            taskPositioner.onDragPositioningStart(
                CTRL_TYPE_UNDEFINED,
                DISPLAY_ID_0,
                STARTING_BOUNDS.left.toFloat(),
                STARTING_BOUNDS.top.toFloat(),
                INPUT_METHOD_TYPE_UNKNOWN,
            )

            // Move to the display 1
            taskPositioner.onDragPositioningMove(DISPLAY_ID_1, 200f, 800f)

            // Moving back to the original display
            taskPositioner.onDragPositioningMove(DISPLAY_ID_0, 100f, 1500f)

            // Check that setPosition is only called once and the surface is outside of display.
            val leftAfterMoveCaptor = argumentCaptor<Float>()
            val topAfterMoveCaptor = argumentCaptor<Float>()
            verify(mockTransaction)
                .setPosition(
                    eq(mockSurfaceControl),
                    leftAfterMoveCaptor.capture(),
                    topAfterMoveCaptor.capture(),
                )
            val rectAfterMove = Rect(STARTING_BOUNDS)
            rectAfterMove.offsetTo(
                leftAfterMoveCaptor.firstValue.toInt(),
                topAfterMoveCaptor.firstValue.toInt(),
            )
            Assert.assertFalse(DISPLAY_BOUNDS.intersect(rectAfterMove))
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_BOUNDS_RESTORING_ON_DRAG_EXIT)
    fun testDragMove_whenSnappedOrMaximized_restoresBounds() = runOnUiThread {
        val prevBounds = Rect(100, 250, 200, 500) // width=100, height=250
        whenever(mockDesktopRepository.hasBoundsBeforeSnapOrMaximize(any())).thenReturn(true)
        whenever(mockDesktopRepository.getBoundsBeforeSnapOrMaximize(TASK_ID))
            .thenReturn(prevBounds)
        val expectedRestoredBounds = Rect(100, 100, 200, 350) // width=100, height=250

        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_UNDEFINED,
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        val returnedBounds =
            taskPositioner.onDragPositioningMove(
                DISPLAY_ID_0,
                STARTING_BOUNDS.left.toFloat() + 400f, // 500f
                STARTING_BOUNDS.top.toFloat(), // 100f
            )

        Assert.assertEquals(expectedRestoredBounds, returnedBounds)

        verify(mockTransitions).registerObserver(eq(taskPositioner))
        verify(mockTransitions)
            .startTransition(
                eq(TRANSIT_CHANGE),
                argThat { wct ->
                    val change = wct.changes[taskBinder]
                    change != null &&
                        (change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0 &&
                        change.configuration.windowConfiguration.bounds == expectedRestoredBounds
                },
                eq(taskPositioner),
            )
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_BOUNDS_RESTORING_ON_DRAG_EXIT)
    fun testManualDragResize_clearsPreviousBoundsAndDoesNotRestore() = runOnUiThread {
        // Given a task hasBoundsBeforeSnapOrMaximize
        val prevBounds = Rect(10, 20, 90, 80) // width=80, height=60
        whenever(mockDesktopRepository.hasBoundsBeforeSnapOrMaximize(any())).thenReturn(true)
        whenever(mockDesktopRepository.removeBoundsBeforeSnapOrMaximize(TASK_ID))
            .thenReturn(prevBounds)

        // When a resize drag starts.
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_RIGHT, // A resize trigger
            DISPLAY_ID_0,
            STARTING_BOUNDS.right.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        // And the user manually resizes the window.
        val returnedBounds =
            taskPositioner.onDragPositioningMove(
                DISPLAY_ID_0,
                STARTING_BOUNDS.right.toFloat() + 50,
                STARTING_BOUNDS.top.toFloat(),
            )
        // Then, the previous bounds are not applied but manually resized bounds are applied.
        val expectedBounds = Rect(STARTING_BOUNDS)
        expectedBounds.right += 50
        Assert.assertEquals(expectedBounds, returnedBounds)

        // User continues to manually resize.
        val finalBounds =
            taskPositioner.onDragPositioningMove(
                DISPLAY_ID_0,
                STARTING_BOUNDS.right.toFloat() + 100,
                STARTING_BOUNDS.top.toFloat(),
            )
        val finalExpectedBounds = Rect(STARTING_BOUNDS)
        finalExpectedBounds.right += 100

        // Previous bounds should be cleared.
        verify(mockDesktopRepository).removeBoundsBeforeSnapOrMaximize(TASK_ID)
        Assert.assertEquals(finalExpectedBounds, finalBounds)
        Assert.assertFalse("Previous bounds should not be restored", finalBounds.equals(prevBounds))
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_BOUNDS_RESTORING_ON_DRAG_EXIT)
    fun testDragMove_awaitsResizeTransition_beforeMoving() = runOnUiThread {
        val prevBounds = Rect(10, 20, 90, 80) // width=80, height=60
        whenever(mockDesktopRepository.hasBoundsBeforeSnapOrMaximize(any())).thenReturn(true)
        whenever(mockDesktopRepository.getBoundsBeforeSnapOrMaximize(TASK_ID))
            .thenReturn(prevBounds)

        val dragStartX = 150f
        val dragStartY = 150f
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_UNDEFINED,
            DISPLAY_ID_0,
            dragStartX,
            dragStartY,
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        // First move triggers the resize to previous bounds and sets pendingResizeTransition to a
        // non-null value.
        val restoredBounds =
            taskPositioner.onDragPositioningMove(
                DISPLAY_ID_0,
                200f, // moveX
                200f, // moveY
            )

        // Second move: pendingResizeTransition is still non-null, so it should return the same
        // bounds without moving the window to new coordinates.
        val boundsDuringTransition =
            taskPositioner.onDragPositioningMove(
                DISPLAY_ID_0,
                300f, // User continues to move the window (bigger moveX)
                300f, // User continues to move the window (bigger moveY)
            )
        Assert.assertEquals(
            "Bounds shouldn't be updated until resize transition finishes",
            restoredBounds,
            boundsDuringTransition,
        )

        // Simulate transition ready signal, which sets pendingResizeTransition to null.
        taskPositioner.onTransitionReady(mockResizeBinder, mock(), mock(), mock())

        val finalMoveX = 400f
        val finalMoveY = 400f
        // Third move: pendingResizeTransition is now null, so window can move.
        val boundsAfterTransition =
            taskPositioner.onDragPositioningMove(DISPLAY_ID_0, finalMoveX, finalMoveY)
        Assert.assertFalse(
            "Bounds should be updated based on the move after resize transition finishes",
            restoredBounds.equals(boundsAfterTransition),
        )

        // Verify the final bounds are correct after the move.
        val expectedFinalBounds = Rect(restoredBounds)
        expectedFinalBounds.offset(
            (finalMoveX - dragStartX).toInt(),
            (finalMoveY - dragStartY).toInt(),
        )
        Assert.assertEquals(expectedFinalBounds, boundsAfterTransition)
    }

    @Test
    fun testDragResize_resize_boundsUpdateOnEnd() = runOnUiThread {
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_RIGHT or CTRL_TYPE_TOP,
            DISPLAY_ID_0,
            STARTING_BOUNDS.right.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        taskPositioner.onDragPositioningMove(
            DISPLAY_ID_0,
            STARTING_BOUNDS.right.toFloat() + 10,
            STARTING_BOUNDS.top.toFloat() + 10,
        )

        val rectAfterMove = Rect(STARTING_BOUNDS)
        rectAfterMove.right += 10
        rectAfterMove.top += 10
        verify(mockWindowDecoration).showResizeVeil(rectAfterMove)
        verify(mockShellTaskOrganizer, never())
            .applyTransaction(
                argThat { wct ->
                    return@argThat wct.changes.any { (token, change) ->
                        token == taskBinder &&
                            (change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) !=
                                0 &&
                            change.configuration.windowConfiguration.bounds == rectAfterMove
                    }
                }
            )

        taskPositioner.onDragPositioningEnd(
            DISPLAY_ID_0,
            STARTING_BOUNDS.right.toFloat() + 20,
            STARTING_BOUNDS.top.toFloat() + 20,
        )
        val rectAfterEnd = Rect(rectAfterMove)
        rectAfterEnd.right += 10
        rectAfterEnd.top += 10
        verify(mockWindowDecoration).updateResizeVeil(any())
        verify(mockTransitions)
            .startTransition(
                eq(TRANSIT_CHANGE),
                argThat { wct ->
                    return@argThat wct.changes.any { (token, change) ->
                        token == taskBinder &&
                            (change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) !=
                                0 &&
                            change.configuration.windowConfiguration.bounds == rectAfterEnd
                    }
                },
                eq(taskPositioner),
            )
        verifyNoInteractions(mockMultiDisplayDragMoveIndicatorController)
    }

    @Test
    fun testDragResize_noEffectiveMove_skipsTransactionOnEnd() = runOnUiThread {
        taskPositioner.onDragPositioningStart(
            DISPLAY_ID_0,
            CTRL_TYPE_TOP or CTRL_TYPE_RIGHT,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        taskPositioner.onDragPositioningMove(
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
        )

        taskPositioner.onDragPositioningEnd(
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat() + 10,
            STARTING_BOUNDS.top.toFloat() + 10,
        )

        verify(mockTransitions, never())
            .startTransition(
                eq(TRANSIT_CHANGE),
                argThat { wct ->
                    return@argThat wct.changes.any { (token, change) ->
                        token == taskBinder &&
                            (change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) !=
                                0 &&
                            change.configuration.windowConfiguration.bounds == STARTING_BOUNDS
                    }
                },
                eq(taskPositioner),
            )

        verify(mockShellTaskOrganizer, never())
            .applyTransaction(
                argThat { wct ->
                    return@argThat wct.changes.any { (token, change) ->
                        token == taskBinder &&
                            ((change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) !=
                                0)
                    }
                }
            )
    }

    @Test
    fun testDragResize_drag_setBoundsNotRunIfDragEndsInDisallowedEndArea() = runOnUiThread {
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_UNDEFINED, // drag
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        val newX = STARTING_BOUNDS.left.toFloat() + 5
        val newY = DISALLOWED_AREA_FOR_END_BOUNDS_HEIGHT.toFloat() - 1
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, newX, newY)

        taskPositioner.onDragPositioningEnd(DISPLAY_ID_0, newX, newY)

        verify(mockShellTaskOrganizer, never())
            .applyTransaction(
                argThat { wct ->
                    return@argThat wct.changes.any { (token, change) ->
                        token == taskBinder &&
                            ((change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) !=
                                0)
                    }
                }
            )
    }

    @Test
    fun testDragResize_resize_resizingTaskReorderedToTopWhenNotFocused() = runOnUiThread {
        whenever(mockWindowDecoration.hasGlobalFocus).thenReturn(false)
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_RIGHT, // Resize right
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        // Verify task is reordered to top
        verify(mockShellTaskOrganizer, never())
            .applyTransaction(
                argThat { wct ->
                    return@argThat wct.hierarchyOps.any { hierarchyOps ->
                        hierarchyOps.container == taskBinder && hierarchyOps.toTop
                    }
                }
            )
    }

    @Test
    fun testDragResize_resize_resizingTaskNotReorderedToTopWhenFocused() = runOnUiThread {
        whenever(mockWindowDecoration.hasGlobalFocus).thenReturn(true)
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_RIGHT, // Resize right
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        // Verify task is not reordered to top
        verify(mockShellTaskOrganizer, never())
            .applyTransaction(
                argThat { wct ->
                    return@argThat wct.hierarchyOps.any { hierarchyOps ->
                        hierarchyOps.container == taskBinder && hierarchyOps.toTop
                    }
                }
            )
    }

    @Test
    @EnableFlags(Flags.FLAG_MOVE_TASK_TO_FRONT_ON_DRAG_RESIZING_BUGFIX)
    fun testDragResize_resize_movesTaskToFront_whenStartOutside() = runOnUiThread {
        whenever(mockWindowDecoration.hasGlobalFocus).thenReturn(false)
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_RIGHT,
            DISPLAY_ID_0,
            STARTING_BOUNDS.right.toFloat() + 10,
            STARTING_BOUNDS.bottom.toFloat() + 10,
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        verify(mockDesktopTasksController).moveTaskToFront(mockWindowDecoration.taskInfo)
    }

    @Test
    @EnableFlags(Flags.FLAG_MOVE_TASK_TO_FRONT_ON_DRAG_RESIZING_BUGFIX)
    fun testDragResize_resize_doesNotMoveTaskToFront_whenStartInside() = runOnUiThread {
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_RIGHT,
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat() + 10,
            STARTING_BOUNDS.top.toFloat() + 10,
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        verify(mockDesktopTasksController, never()).moveTaskToFront(any(), any(), any())
    }

    @Test
    @EnableFlags(Flags.FLAG_MOVE_TASK_TO_FRONT_ON_DRAG_RESIZING_BUGFIX)
    fun testDragResize_resize_doesNotMoveTaskToFront_whenStartOutsideAndAlreadyFocused() =
        runOnUiThread {
            whenever(mockWindowDecoration.hasGlobalFocus).thenReturn(true)
            taskPositioner.onDragPositioningStart(
                CTRL_TYPE_RIGHT,
                DISPLAY_ID_0,
                STARTING_BOUNDS.right.toFloat() + 10,
                STARTING_BOUNDS.bottom.toFloat() + 10,
                INPUT_METHOD_TYPE_UNKNOWN,
            )

            verify(mockDesktopTasksController, never()).moveTaskToFront(any(), any(), any())
        }

    @Test
    fun testDragResize_drag_draggedTaskNotReorderedToTop() = runOnUiThread {
        whenever(mockWindowDecoration.hasGlobalFocus).thenReturn(false)
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_UNDEFINED, // drag
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        // Verify task is not reordered to top since task is already brought to top before dragging
        // begins
        verify(mockShellTaskOrganizer, never())
            .applyTransaction(
                argThat { wct ->
                    return@argThat wct.hierarchyOps.any { hierarchyOps ->
                        hierarchyOps.container == taskBinder && hierarchyOps.toTop
                    }
                }
            )
    }

    @Test
    fun testDragResize_moved_updatesTaskbarRounding() = runOnUiThread {
        val moveBounds = Rect(STARTING_BOUNDS)
        moveBounds.union(moveBounds.right + 100, moveBounds.bottom + 100)
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM,
            DISPLAY_ID_0,
            STARTING_BOUNDS.right.toFloat(),
            STARTING_BOUNDS.bottom.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        taskPositioner.onDragPositioningMove(
            DISPLAY_ID_0,
            moveBounds.right.toFloat(),
            moveBounds.bottom.toFloat(),
        )

        verify(mockDesktopScrimController)
            .updateDesktopScrimOnResize(DISPLAY_ID_0, TASK_ID, moveBounds)
    }

    @Test
    fun testDragResize_movedSeveralTimes_updatesTaskbarRoundingOnce() = runOnUiThread {
        val firstmoveBounds = Rect(STARTING_BOUNDS)
        firstmoveBounds.union(firstmoveBounds.right + 100, firstmoveBounds.bottom + 100)
        val secondMoveBounds = Rect(firstmoveBounds)
        secondMoveBounds.union(secondMoveBounds.right + 100, secondMoveBounds.bottom + 100)
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM,
            DISPLAY_ID_0,
            STARTING_BOUNDS.right.toFloat(),
            STARTING_BOUNDS.bottom.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        taskPositioner.onDragPositioningMove(
            DISPLAY_ID_0,
            firstmoveBounds.right.toFloat(),
            firstmoveBounds.bottom.toFloat(),
        )
        taskPositioner.onDragPositioningMove(
            DISPLAY_ID_0,
            secondMoveBounds.right.toFloat() + 100,
            secondMoveBounds.bottom.toFloat() + 100,
        )

        verify(mockDesktopScrimController)
            .updateDesktopScrimOnResize(DISPLAY_ID_0, TASK_ID, firstmoveBounds)
        verify(mockDesktopScrimController, never())
            .updateDesktopScrimOnResize(DISPLAY_ID_0, TASK_ID, secondMoveBounds)
    }

    @Test
    @EnableFlags(Flags.FLAG_UPDATE_DESKTOP_SCRIM_ON_DRAG_RESIZE_END)
    fun testDragResize_desktopScrimIsUpdatedOnDragPositioningEnd() = runOnUiThread {
        val firstMoveBounds = Rect(STARTING_BOUNDS)
        firstMoveBounds.union(firstMoveBounds.right + 100, firstMoveBounds.bottom + 100)
        val secondMoveBounds = Rect(firstMoveBounds)
        secondMoveBounds.union(secondMoveBounds.right + 100, secondMoveBounds.bottom + 100)
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM,
            DISPLAY_ID_0,
            STARTING_BOUNDS.right.toFloat(),
            STARTING_BOUNDS.bottom.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        taskPositioner.onDragPositioningMove(
            DISPLAY_ID_0,
            firstMoveBounds.right.toFloat(),
            firstMoveBounds.bottom.toFloat(),
        )
        taskPositioner.onDragPositioningMove(
            DISPLAY_ID_0,
            secondMoveBounds.right.toFloat(),
            secondMoveBounds.bottom.toFloat(),
        )

        verify(mockDesktopScrimController, never())
            .updateDesktopScrimOnResize(DISPLAY_ID_0, TASK_ID, secondMoveBounds)

        taskPositioner.onDragPositioningEnd(
            DISPLAY_ID_0,
            secondMoveBounds.right.toFloat(),
            secondMoveBounds.bottom.toFloat(),
        )

        // Desktop scrim is updated at the end of drag-resize.
        verify(mockDesktopScrimController, times(1))
            .updateDesktopScrimOnResize(DISPLAY_ID_0, TASK_ID, secondMoveBounds)
    }

    @Test
    fun testDragMove_desktopScrimIsNotUpdatedOnDragPositioningEnd() = runOnUiThread {
        val firstMoveBounds = Rect(STARTING_BOUNDS)
        firstMoveBounds.union(firstMoveBounds.right + 100, firstMoveBounds.bottom + 100)
        val secondMoveBounds = Rect(firstMoveBounds)
        secondMoveBounds.union(secondMoveBounds.right + 100, secondMoveBounds.bottom + 100)
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_UNDEFINED,
            DISPLAY_ID_0,
            STARTING_BOUNDS.right.toFloat(),
            STARTING_BOUNDS.bottom.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        taskPositioner.onDragPositioningMove(
            DISPLAY_ID_0,
            firstMoveBounds.right.toFloat(),
            firstMoveBounds.bottom.toFloat(),
        )
        taskPositioner.onDragPositioningMove(
            DISPLAY_ID_0,
            secondMoveBounds.right.toFloat(),
            secondMoveBounds.bottom.toFloat(),
        )

        taskPositioner.onDragPositioningEnd(
            DISPLAY_ID_0,
            secondMoveBounds.right.toFloat(),
            secondMoveBounds.bottom.toFloat(),
        )

        // Desktop scrim is not updated at the end of drag-move.
        verify(mockDesktopScrimController, never())
            .updateDesktopScrimOnResize(DISPLAY_ID_0, TASK_ID, secondMoveBounds)
    }

    @Test
    fun testClose() = runOnUiThread {
        verify(mockDisplayController, times(1)).addDisplayWindowListener(eq(taskPositioner))

        taskPositioner.close()

        verify(mockDisplayController, times(1)).removeDisplayWindowListener(eq(taskPositioner))
    }

    @Test
    fun testIsResizingOrAnimatingResizeSet() = runOnUiThread {
        taskPositioner.addDragEventListener(mockDragEventListener)
        Assert.assertFalse(taskPositioner.isResizingOrAnimating)

        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_TOP or CTRL_TYPE_RIGHT,
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            INPUT_METHOD_TYPE_UNKNOWN,
        )

        taskPositioner.onDragPositioningMove(
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat() - 20,
            STARTING_BOUNDS.top.toFloat() - 20,
        )

        // isResizingOrAnimating should be set to true after move during a resize
        Assert.assertTrue(taskPositioner.isResizingOrAnimating)
        verify(mockDragEventListener, times(1)).onDragMove(eq(TASK_ID))

        taskPositioner.onDragPositioningEnd(
            DISPLAY_ID_0,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
        )

        // isResizingOrAnimating should be not be set till false until after transition animation
        Assert.assertTrue(taskPositioner.isResizingOrAnimating)
    }

    @Test
    fun testIsResizingOrAnimatingResizeResetAfterStartAnimation() = runOnUiThread {
        performDrag(
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat(),
            STARTING_BOUNDS.left.toFloat() - 20,
            STARTING_BOUNDS.top.toFloat() - 20,
            CTRL_TYPE_TOP or CTRL_TYPE_RIGHT,
        )

        taskPositioner.startAnimation(
            mockTransitionBinder,
            mockTransitionInfo,
            mockTransaction,
            mockTransaction,
            mockFinishCallback,
        )

        // isResizingOrAnimating should be set to false until after transition successfully consumed
        Assert.assertFalse(taskPositioner.isResizingOrAnimating)
    }

    @Test
    fun testStartAnimation_updatesLeash() = runOnUiThread {
        val changeMock = mock<TransitionInfo.Change>()
        val nonTaskChangeMock = mock<TransitionInfo.Change>()
        val taskLeash = mock<SurfaceControl>()
        val nonTaskLeash = mock<SurfaceControl>()
        val startTransaction = mock<Transaction>()
        val finishTransaction = mock<Transaction>()
        val point = Point(10, 20)
        val bounds = Rect(1, 2, 3, 4)
        whenever(changeMock.leash).thenReturn(taskLeash)
        whenever(changeMock.endRelOffset).thenReturn(point)
        whenever(changeMock.endAbsBounds).thenReturn(bounds)
        whenever(changeMock.taskInfo).thenReturn(ActivityManager.RunningTaskInfo())
        whenever(nonTaskChangeMock.leash).thenReturn(nonTaskLeash)
        whenever(nonTaskChangeMock.endRelOffset).thenReturn(point)
        whenever(nonTaskChangeMock.endAbsBounds).thenReturn(bounds)
        whenever(nonTaskChangeMock.taskInfo).thenReturn(null)
        whenever(mockTransitionInfo.changes).thenReturn(listOf(changeMock, nonTaskChangeMock))
        whenever(startTransaction.setWindowCrop(any(), eq(bounds.width()), eq(bounds.height())))
            .thenReturn(startTransaction)
        whenever(finishTransaction.setWindowCrop(any(), eq(bounds.width()), eq(bounds.height())))
            .thenReturn(finishTransaction)

        taskPositioner.startAnimation(
            mockTransitionBinder,
            mockTransitionInfo,
            startTransaction,
            finishTransaction,
            mockFinishCallback,
        )

        verify(startTransaction)
            .setPosition(eq(taskLeash), eq(point.x.toFloat()), eq(point.y.toFloat()))
        verify(finishTransaction)
            .setPosition(eq(taskLeash), eq(point.x.toFloat()), eq(point.y.toFloat()))
        verify(startTransaction, never()).setPosition(eq(nonTaskLeash), any(), any())
        verify(finishTransaction, never()).setPosition(eq(nonTaskLeash), any(), any())
        verify(changeMock).endRelOffset
    }

    private fun beginFloatingCorner() {
        val task = mockWindowDecoration.taskInfo
        task.isFloatingWindow = true
        task.floatingWindowScale = .75f
        task.positionInParent = Point(100, 100)
        task.floatingWindowPosition.set(100, 100)
        task.configuration.windowConfiguration.setBounds(Rect(100, 100, 900, 1300))
        taskPositioner.onDragPositioningStart(CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM,
            DISPLAY_ID_0, 700f, 1000f, INPUT_METHOD_TYPE_UNKNOWN)
    }

    @Test
    fun floatingBoundsTransition_consumedRestoresCommittedDecorations() = runOnUiThread {
        verifyFloatingBoundsTransitionConsumed(false)
    }

    @Test
    fun floatingBoundsTransition_abortedRestoresCommittedDecorations() = runOnUiThread {
        verifyFloatingBoundsTransitionConsumed(true)
    }

    private fun verifyFloatingBoundsTransitionConsumed(aborted: Boolean) {
        beginFloatingCorner()
        taskPositioner.onDragPositioningEnd(DISPLAY_ID_0, 1100f, 1600f)
        verify(mockTransitions).startTransition(eq(TRANSIT_CHANGE), any(), eq(taskPositioner))
        Assert.assertTrue(taskPositioner.isResizingOrAnimating)
        val committed = ActivityManager.RunningTaskInfo(mockWindowDecoration.taskInfo).apply {
            floatingWindowScale = 1f
            configuration.windowConfiguration.setBounds(Rect(100, 100, 1100, 1600))
        }
        val change = mock<TransitionInfo.Change>()
        whenever(change.taskInfo).thenReturn(committed)
        whenever(change.leash).thenReturn(mockSurfaceControl)
        whenever(mockTransitionInfo.changes).thenReturn(listOf(change))
        val finish = mock<Transaction>()
        whenever(finish.setMatrix(any(), any<Float>(), any(), any(), any())).thenReturn(finish)
        whenever(finish.setPosition(any(), any(), any())).thenReturn(finish)
        taskPositioner.onTransitionReady(mockResizeBinder, mockTransitionInfo,
            mockTransaction, finish)
        org.mockito.Mockito.clearInvocations(mockWindowDecoration)
        taskPositioner.onTransitionConsumed(mockResizeBinder, aborted, finish)
        verify(mockWindowDecoration).finishFloatingWindowPreview()
        verify(mockWindowDecoration).relayout(eq(committed), any(), eq(finish),
            eq(false), eq(false), any(), any(), eq(true), org.mockito.kotlin.isNull())
        verify(mockWindowDecoration).updateFloatingWindowChildScale(eq(finish), eq(1f))
        verify(finish).setMatrix(eq(mockSurfaceControl), eq(1f), eq(0f), eq(0f), eq(1f))
        verify(mockTransaction).setMatrix(eq(mockSurfaceControl), eq(1f), eq(0f), eq(0f), eq(1f))
        verify(mockTransaction, never()).setPosition(eq(mockSurfaceControl), any(), any())
        Assert.assertFalse(taskPositioner.isResizingOrAnimating)
    }

    @Test
    fun floatingCorner_incomingTransitionCancelsRemainingDrag() = runOnUiThread {
        beginFloatingCorner()
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, 780f, 1120f)
        taskPositioner.flushFloatingResizePreview()
        Assert.assertTrue(taskPositioner.isResizingOrAnimating)
        val change = mock<TransitionInfo.Change>()
        val taskInfo = mockWindowDecoration.taskInfo
        whenever(change.taskInfo).thenReturn(taskInfo)
        whenever(mockTransitionInfo.changes).thenReturn(listOf(change))
        val start = mock<Transaction>()
        val finish = mock<Transaction>()
        taskPositioner.onTransitionReady(mockTransitionBinder, mockTransitionInfo, start, finish)
        Assert.assertFalse(taskPositioner.isResizingOrAnimating)
        org.mockito.Mockito.clearInvocations(mockWindowDecoration, mockShellTaskOrganizer)
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, 800f, 1200f)
        taskPositioner.flushFloatingResizePreview()
        taskPositioner.onDragPositioningEnd(DISPLAY_ID_0, 800f, 1200f)
        verify(mockWindowDecoration, never()).updateFloatingWindowPreview(any(), any(), any())
        verify(mockShellTaskOrganizer, never()).applyTransaction(any())
        verify(mockTransitions, never()).startTransition(any(), any(), any())
        verify(start, never()).setPosition(eq(mockSurfaceControl), any(), any())
    }

    @Test
    fun floatingCorner_motionSamplesCoalesceAndUnchangedReleaseDoesNotPreviewAgain() = runOnUiThread {
        beginFloatingCorner()
        for (delta in listOf(20f, 40f, 60f, 80f)) {
            taskPositioner.onDragPositioningMove(DISPLAY_ID_0, 700f + delta, 1000f + delta * 1.5f)
        }
        verify(mockWindowDecoration, never()).updateFloatingWindowPreview(any(), any(), any())
        Assert.assertTrue(taskPositioner.isResizingOrAnimating)
        taskPositioner.flushFloatingResizePreview()
        verify(mockWindowDecoration, times(1)).updateFloatingWindowPreview(any(), eq(.85f), any())
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, 780f, 1120f)
        taskPositioner.flushFloatingResizePreview()
        taskPositioner.onDragPositioningEnd(DISPLAY_ID_0, 780f, 1120f)
        verify(mockWindowDecoration, times(1)).updateFloatingWindowPreview(any(), any(), any())
    }

    @Test
    fun floatingScaledCorner_closeCancelsPreviewWithoutRelayoutOrPresentationCommit() = runOnUiThread {
        val task = mockWindowDecoration.taskInfo
        task.isFloatingWindow = true
        task.floatingWindowScale = .75f
        task.positionInParent = Point(100, 100)
        task.configuration.windowConfiguration.setBounds(Rect(100, 100, 900, 1300))
        taskPositioner.onDragPositioningStart(CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM,
            DISPLAY_ID_0, 700f, 1000f, INPUT_METHOD_TYPE_UNKNOWN)
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, 780f, 1120f)
        org.mockito.Mockito.clearInvocations(mockShellTaskOrganizer, mockWindowDecoration)
        taskPositioner.close()
        verify(mockWindowDecoration).cancelFloatingWindowPreview(any())
        verify(mockWindowDecoration, never()).updateFloatingWindowPreview(any(), any(), any())
        verify(mockShellTaskOrganizer).applyTransaction(argThat {
            changes[taskBinder]?.changeMask?.and(
                android.window.WindowContainerTransaction.Change.CHANGE_FLOATING_WINDOW_PRESENTATION) == 0
        })
    }

    @Test
    fun floatingScaledCorner_previewsLocallyAndCommitsOnceAtRelease() = runOnUiThread {
        val task = mockWindowDecoration.taskInfo
        task.isFloatingWindow = true
        task.floatingWindowScale = .75f
        task.positionInParent = Point(100, 100)
        task.floatingWindowPosition.set(100, 300)
        task.configuration.windowConfiguration.setBounds(Rect(100, 100, 900, 1300))
        taskPositioner.onDragPositioningStart(CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM,
            DISPLAY_ID_0, 700f, 1000f, INPUT_METHOD_TYPE_UNKNOWN)
        org.mockito.Mockito.clearInvocations(mockShellTaskOrganizer)
        for (delta in listOf(20f, 40f, 60f, 80f)) {
            taskPositioner.onDragPositioningMove(DISPLAY_ID_0, 700f + delta, 1000f + delta * 1.5f)
        }
        verify(mockShellTaskOrganizer, never()).applyTransaction(any())
        taskPositioner.flushFloatingResizePreview()
        verify(mockWindowDecoration, times(1))
            .updateFloatingWindowPreview(any(), any(), any())
        taskPositioner.onDragPositioningEnd(DISPLAY_ID_0, 780f, 1120f)
        verify(mockShellTaskOrganizer, times(1)).applyTransaction(argThat {
            changes[taskBinder]?.let { change ->
                change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS == 0 &&
                    kotlin.math.abs(change.floatingWindowScale - .85f) < .001f &&
                    change.floatingWindowPosition == Point(100, 300)
            } == true
        })
        verify(mockTransitions, never()).startTransition(any(), any(), any())
    }

    @Test
    fun floatingScaledCorner_updatesPresentationWithoutBoundsTransaction() = runOnUiThread {
        val task = mockWindowDecoration.taskInfo
        task.isFloatingWindow = true
        task.floatingWindowScale = .75f
        task.positionInParent = android.graphics.Point(100, 100)
        task.floatingWindowPosition.set(100, 100)
        task.configuration.windowConfiguration.setBounds(Rect(100, 100, 900, 1300))
        taskPositioner.onDragPositioningStart(CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM,
            DISPLAY_ID_0, 700f, 1000f, INPUT_METHOD_TYPE_UNKNOWN)
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, 780f, 1120f)
        taskPositioner.onDragPositioningEnd(DISPLAY_ID_0, 780f, 1120f)
        verify(mockShellTaskOrganizer, org.mockito.Mockito.atLeastOnce()).applyTransaction(argThat {
            changes[taskBinder]?.let { change ->
                change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS == 0 &&
                    kotlin.math.abs(change.floatingWindowScale - .85f) < .001f
            } == true
        })
        verify(mockTransitions, never()).startTransition(any(), any(), any())
        verify(mockWindowDecoration, never()).showResizeVeil(any())
        Assert.assertEquals(Rect(100, 100, 900, 1300), task.configuration.windowConfiguration.bounds)
    }

    @Test
    fun floatingCorner_preservesAspectAndCommitsOnlyAtRelease() = runOnUiThread {
        val initial = Rect(100, 100, 500, 900)
        val task = mockWindowDecoration.taskInfo
        task.isFloatingWindow = true
        task.configuration.windowConfiguration.setBounds(initial)
        taskPositioner.onDragPositioningStart(CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM,
            DISPLAY_ID_0, 500f, 900f, INPUT_METHOD_TYPE_UNKNOWN)
        val outline = taskPositioner.onDragPositioningMove(DISPLAY_ID_0, 600f, 1100f)
        Assert.assertEquals(Rect(100, 100, 600, 1100), outline)
        Assert.assertEquals(initial, task.configuration.windowConfiguration.bounds)
        taskPositioner.flushFloatingResizePreview()
        verify(mockWindowDecoration).showResizeVeil(outline)
        verify(mockTransitions, never()).startTransition(any(), any(), any())
        taskPositioner.onDragPositioningEnd(DISPLAY_ID_0, 600f, 1100f)
        verify(mockTransitions, times(1)).startTransition(eq(TRANSIT_CHANGE),
            argThat { changes[taskBinder]?.configuration?.windowConfiguration?.bounds == outline },
            eq(taskPositioner))
    }

    @Test
    fun floatingTopEdge_movesWindow() = runOnUiThread {
        verifyFloatingEdgeMove(CTRL_TYPE_TOP, 400f, 100f, 400f, 40f)
    }

    @Test
    fun floatingRightEdge_movesWindow() = runOnUiThread {
        verifyFloatingEdgeMove(CTRL_TYPE_RIGHT, 700f, 550f, 740f, 550f)
    }

    @Test
    fun floatingLeftEdge_movesWindow() = runOnUiThread {
        verifyFloatingEdgeMove(CTRL_TYPE_LEFT, 100f, 550f, 60f, 550f)
    }

    @Test
    fun floatingBottomEdge_movesWindow() = runOnUiThread {
        verifyFloatingEdgeMove(CTRL_TYPE_BOTTOM, 400f, 1000f, 400f, 1060f)
    }

    private fun verifyFloatingEdgeMove(ctrl: Int, x: Float, y: Float, endX: Float, endY: Float) {
        val task = mockWindowDecoration.taskInfo
        task.isFloatingWindow = true
        task.floatingWindowScale = .75f
        task.positionInParent = Point(100, 100)
        task.floatingWindowPosition.set(100, 100)
        val initial = Rect(100, 100, 900, 1300)
        task.configuration.windowConfiguration.setBounds(initial)
        taskPositioner.onDragPositioningStart(ctrl, DISPLAY_ID_0, x, y, INPUT_METHOD_TYPE_UNKNOWN)
        val pinned = mock<com.android.wm.shell.pinnedlayer.phone.PinnedLayerController>()
        whenever(mockWindowDecoration.pinnedLayerController).thenReturn(pinned)
        val preview = taskPositioner.onDragPositioningMove(DISPLAY_ID_0, endX, endY)
        Assert.assertEquals(Rect(100 + (endX - x).toInt(), 100 + (endY - y).toInt(),
            700 + (endX - x).toInt(), 1000 + (endY - y).toInt()), preview)
        taskPositioner.onDragPositioningEnd(DISPLAY_ID_0, endX, endY)
        verify(pinned).onDragEnded(eq(mockSurfaceControl), eq(task),
            eq(Rect(100, 100, 700, 1000)), eq(preview), eq(DISPLAY_ID_0))
        Assert.assertEquals(initial, task.configuration.windowConfiguration.bounds)
        Assert.assertEquals(.75f, task.floatingWindowScale)
        verify(mockWindowDecoration, never()).showResizeVeil(any())
        verify(mockTransitions, never()).startTransition(any(), any(), any())
        verify(mockDesktopTasksController, never()).rememberFloatingOrientation(any(), any(), any())
    }

    private fun performDrag(startX: Float, startY: Float, endX: Float, endY: Float, ctrlType: Int) {
        taskPositioner.onDragPositioningStart(
            ctrlType,
            DISPLAY_ID_0,
            startX,
            startY,
            INPUT_METHOD_TYPE_UNKNOWN,
        )
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, endX, endY)

        taskPositioner.onDragPositioningEnd(DISPLAY_ID_0, endX, endY)
    }

    companion object {
        private const val TASK_ID = 5
        private const val MIN_WIDTH = 10
        private const val MIN_HEIGHT = 10
        private const val DENSITY_DPI = 20
        private const val DEFAULT_MIN = 40
        private const val DISPLAY_ID_0 = 0
        private const val DISPLAY_ID_1 = 1
        private const val NAVBAR_HEIGHT = 50
        private const val CAPTION_HEIGHT = 50
        private const val DISALLOWED_AREA_FOR_END_BOUNDS_HEIGHT = 10
        private val DISPLAY_BOUNDS = Rect(0, 0, 2400, 1600)
        private val STARTING_BOUNDS = Rect(100, 100, 200, 200)
        private val STABLE_BOUNDS_LANDSCAPE =
            Rect(
                DISPLAY_BOUNDS.left,
                DISPLAY_BOUNDS.top + CAPTION_HEIGHT,
                DISPLAY_BOUNDS.right,
                DISPLAY_BOUNDS.bottom - NAVBAR_HEIGHT,
            )
        private val STABLE_BOUNDS_PORTRAIT =
            Rect(
                DISPLAY_BOUNDS.top,
                DISPLAY_BOUNDS.left + CAPTION_HEIGHT,
                DISPLAY_BOUNDS.bottom,
                DISPLAY_BOUNDS.right - NAVBAR_HEIGHT,
            )
        private val VALID_DRAG_AREA =
            Rect(
                DISPLAY_BOUNDS.left - 100,
                STABLE_BOUNDS_LANDSCAPE.top,
                DISPLAY_BOUNDS.right - 100,
                DISPLAY_BOUNDS.bottom - 100,
            )
    }
    @Test
    fun floatingResizeWithoutMovement_releasesOwner() = runOnUiThread {
        beginFloatingCorner()
        taskPositioner.onDragPositioningEnd(DISPLAY_ID_0, 700f, 1000f)
        Assert.assertFalse(com.android.wm.shell.common.SurfaceFrameRate.isInteracting)
    }


    private fun beginFloatingMove() {
        val task = mockWindowDecoration.taskInfo
        task.isFloatingWindow = true
        task.floatingWindowScale = .75f
        task.floatingWindowPosition.set(100, 100)
        task.configuration.windowConfiguration.setBounds(Rect(100, 100, 900, 1300))
        taskPositioner.onDragPositioningStart(CTRL_TYPE_UNDEFINED,
            DISPLAY_ID_0, 400f, 110f, INPUT_METHOD_TYPE_UNKNOWN)
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, 1000f, 30f)
    }

    @Test
    fun floatingMove_displayChangeCancelsPreviewAndReleaseTogether() = runOnUiThread {
        beginFloatingMove()
        taskPositioner.onDisplayConfigurationChanged(DISPLAY_ID_0, Configuration())
        Assert.assertTrue(taskPositioner.isDragPositioningCancelled())
        Assert.assertFalse(com.android.wm.shell.common.SurfaceFrameRate.isInteracting)
        org.mockito.Mockito.clearInvocations(mockShellTaskOrganizer, mockTransaction)
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, 1200f, 30f)
        taskPositioner.onDragPositioningEnd(DISPLAY_ID_0, 1200f, 30f)
        verify(mockShellTaskOrganizer, never()).applyTransaction(any())
        verify(mockTransaction, never()).setPosition(any(), any(), any())
    }

    @Test
    fun floatingMove_sameTaskTransitionCancelsEvenBeforePreviewFrame() = runOnUiThread {
        beginFloatingMove()
        val change = mock<TransitionInfo.Change>()
        val taskInfo = mockWindowDecoration.taskInfo
        whenever(change.taskInfo).thenReturn(taskInfo)
        whenever(mockTransitionInfo.changes).thenReturn(listOf(change))
        taskPositioner.onTransitionReady(mockTransitionBinder, mockTransitionInfo,
            mock<Transaction>(), mock<Transaction>())
        Assert.assertTrue(taskPositioner.isDragPositioningCancelled())
        Assert.assertFalse(com.android.wm.shell.common.SurfaceFrameRate.isInteracting)
        org.mockito.Mockito.clearInvocations(mockShellTaskOrganizer)
        taskPositioner.onDragPositioningEnd(DISPLAY_ID_0, 1200f, 30f)
        verify(mockShellTaskOrganizer, never()).applyTransaction(any())
    }


    @Test
    fun floatingMove_displayRemovalCancelsPreviewAndReleaseTogether() = runOnUiThread {
        beginFloatingMove()
        taskPositioner.onDisplayRemoved(DISPLAY_ID_0)
        Assert.assertTrue(taskPositioner.isDragPositioningCancelled())
        Assert.assertFalse(com.android.wm.shell.common.SurfaceFrameRate.isInteracting)
        org.mockito.Mockito.clearInvocations(mockShellTaskOrganizer)
        taskPositioner.onDragPositioningEnd(DISPLAY_ID_0, 1200f, 30f)
        verify(mockShellTaskOrganizer, never()).applyTransaction(any())
    }


    @Test fun floatingResizeFeedback_rearmsAfterLeavingMinimumAndAfterCancel() = runOnUiThread {
        val task = mockWindowDecoration.taskInfo
        task.isFloatingWindow = true
        task.floatingWindowScale = .75f
        task.floatingWindowPosition.set(100, 100)
        task.configuration.windowConfiguration.setBounds(Rect(0, 0, 800, 1200))
        fun begin(method: Int) = taskPositioner.onDragPositioningStart(
            CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM, DISPLAY_ID_0, 700f, 1000f, method)
        begin(DragPositioningCallback.INPUT_METHOD_TYPE_TOUCH)
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, -1000f, -1000f)
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, -1100f, -1100f)
        verify(mockWindowDecoration).performFloatingTargetHapticFeedback()
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, 700f, 1000f)
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, -1000f, -1000f)
        verify(mockWindowDecoration, times(2)).performFloatingTargetHapticFeedback()
        taskPositioner.onDragPositioningCancel()
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, -1200f, -1200f)
        verify(mockWindowDecoration, times(2)).performFloatingTargetHapticFeedback()
        begin(DragPositioningCallback.INPUT_METHOD_TYPE_TOUCH)
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, -1000f, -1000f)
        verify(mockWindowDecoration, times(3)).performFloatingTargetHapticFeedback()
        taskPositioner.onDragPositioningCancel()
        begin(DragPositioningCallback.INPUT_METHOD_TYPE_MOUSE)
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, -1000f, -1000f)
        taskPositioner.onDragPositioningCancel()
        begin(DragPositioningCallback.INPUT_METHOD_TYPE_TOUCHPAD)
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, -1000f, -1000f)
        verify(mockWindowDecoration, times(3)).performFloatingTargetHapticFeedback()
    }
    @Test fun floatingCorner_changesAspectInOneBoundsCommit() = runOnUiThread {
        beginFloatingCorner()
        taskPositioner.onDragPositioningMove(DISPLAY_ID_0, 800f, 1000f)
        taskPositioner.flushFloatingResizePreview()
        verify(mockShellTaskOrganizer, never()).applyTransaction(argThat {
            changes[taskBinder]?.windowSetMask?.and(WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0
        })
        taskPositioner.onDragPositioningEnd(DISPLAY_ID_0, 800f, 1000f)
        verify(mockTransitions).startTransition(eq(TRANSIT_CHANGE), argThat {
            val bounds = changes[taskBinder]?.configuration?.windowConfiguration?.bounds
            bounds != null && bounds.height() > bounds.width() &&
                kotlin.math.abs(bounds.width().toFloat() / bounds.height() - 3f / 4f) < .003f
        }, eq(taskPositioner))
    }

}
