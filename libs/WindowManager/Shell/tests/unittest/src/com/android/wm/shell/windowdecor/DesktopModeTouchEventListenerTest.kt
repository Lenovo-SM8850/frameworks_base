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

package com.android.wm.shell.windowdecor

import android.app.ActivityManager
import android.graphics.Rect
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.os.Looper
import android.os.SystemClock
import android.platform.test.annotations.EnableFlags
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.Display.DEFAULT_DISPLAY
import android.view.MotionEvent
import androidx.test.filters.SmallTest
import com.android.window.flags.Flags
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestHandler
import com.android.wm.shell.TestShellExecutor
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.MultiDisplayTestUtil.TestDisplay.DISPLAY_0
import com.android.wm.shell.desktopmode.DesktopTasksController
import com.android.wm.shell.desktopmode.DesktopUserRepositories
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerController
import com.android.wm.shell.shared.desktopmode.FakeDesktopConfig
import com.android.wm.shell.shared.desktopmode.FakeDesktopState
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.windowdecor.WindowDecorationTestHelper.TestAppHeaderViewHolderFactory
import com.android.wm.shell.windowdecor.WindowDecorationTestHelper.TestHeaderDimensions
import com.android.wm.shell.windowdecor.WindowDecorationTestHelper.TestHeaderDimensions.Companion.APP_HEADER_HEIGHT
import com.android.wm.shell.windowdecor.WindowDecorationTestHelper.TestHeaderDimensions.Companion.CUSTOMIZABLE_REGION_MARGIN_START
import com.android.wm.shell.windowdecor.WindowDecorationTestHelper.TestInputPilferer
import com.android.wm.shell.windowdecor.WindowDecorationTestHelper.TestWindowDecoration
import com.android.wm.shell.windowdecor.WindowDecorationTestHelper.createAppHeaderTask
import com.android.wm.shell.windowdecor.WindowDecorationTestHelper.createCustomAppHeaderTask
import com.android.wm.shell.windowdecor.common.CaptionVisibilityHelper
import com.android.wm.shell.windowdecor.common.WindowDecorationGestureExclusionTracker
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for [DesktopModeTouchEventListener].
 *
 * Build/Install/Run: atest WMShellUnitTests:DesktopModeTouchEventListenerTest
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class DesktopModeTouchEventListenerTest : ShellTestCase() {
    private val mockSplitScreenController = mock<SplitScreenController>()
    private val mockDesktopTasksController = mock<DesktopTasksController>()
    private val mockTaskOperations = mock<TaskOperations>()
    private val mockWindowDecorationActions = mock<WindowDecorationActions>()
    private val mockDisplayController = mock<DisplayController>()
    private val mockWindowDecorationExclusionTracker =
        mock<WindowDecorationGestureExclusionTracker>()
    private val mockCaptionVisibilityHelper = mock<CaptionVisibilityHelper>()
    private val mockPinnedLayerController = mock<PinnedLayerController>()

    private val testDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
    private val testHandler = TestHandler(Looper.getMainLooper())
    private val testExecutor = TestShellExecutor()
    private val testScope = TestScope(testDispatcher)
    private val testInputPilferer = TestInputPilferer()

    private val shellInit = ShellInit(testExecutor)
    private val desktopState = FakeDesktopState()
    private val desktopConfig = FakeDesktopConfig()

    private val windowDecorations = mutableMapOf<Int, WindowDecorationWrapper>()
    private lateinit var userRepositories: DesktopUserRepositories
    private val defaultDisplayExclusionRegion = Region.obtain()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val display =
            context.getSystemService(DisplayManager::class.java).getDisplay(DEFAULT_DISPLAY)
        val displayLayout = DISPLAY_0.getSpyDisplayLayout(mContext.resources)
        whenever(mockDisplayController.getDisplay(DEFAULT_DISPLAY)).thenReturn(display)
        whenever(mockDisplayController.getDisplayLayout(DEFAULT_DISPLAY)).thenReturn(displayLayout)
        whenever(mockWindowDecorationExclusionTracker.getExclusionRegion(DEFAULT_DISPLAY))
            .thenReturn(defaultDisplayExclusionRegion)
        whenever(mockCaptionVisibilityHelper.shouldCreateCaption(any(), eq(false))).thenReturn(true)
        userRepositories =
            DesktopUserRepositories(
                shellInit,
                mock(),
                mock(),
                mock(),
                testScope.backgroundScope,
                testScope.backgroundScope,
                mock(),
                desktopState,
                desktopConfig,
            )
    }

    @After
    fun tearDown() {
        defaultDisplayExclusionRegion.setEmpty()
        testInputPilferer.reset()
        windowDecorations.forEach { (_, decor) -> decor.close() }
        windowDecorations.clear()
        testScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOW_DECORATION_REFACTOR)
    fun testAppHeaderClick_withActionMoveButNoBoundsChange_invokesDragPositioningEndCallback() =
        testScope.runTest {
            val decor = setUpWindowDecoration(createAppHeaderTask(TASK_BOUNDS))
            val appHeader =
                assertNotNull(
                    decor.findViewById(com.android.wm.shell.R.id.desktop_mode_caption),
                    "Expected decoration to have an app header view",
                )
            val x = TASK_BOUNDS.left + 100f
            val y = TASK_BOUNDS.top + (APP_HEADER_HEIGHT / 2f)

            // Simulate a click on the app header that includes an ACTION_MOVE event with no bounds
            // change.
            val startTime = SystemClock.uptimeMillis()
            appHeader.dispatchTouchEvent(
                MotionEvent.obtain(
                    /* downTime = */ startTime,
                    /* eventTime = */ startTime,
                    /* action = */ MotionEvent.ACTION_DOWN,
                    /* x = */ x,
                    /* y = */ y,
                    /* metaState = */ 0,
                )
            )
            appHeader.dispatchTouchEvent(
                MotionEvent.obtain(
                    /* downTime = */ startTime,
                    /* eventTime = */ startTime + 10,
                    /* action = */ MotionEvent.ACTION_MOVE,
                    /* x = */ x,
                    /* y = */ y,
                    /* metaState = */ 0,
                )
            )
            val upEvent =
                MotionEvent.obtain(
                    /* downTime = */ startTime,
                    /* eventTime = */ startTime + 20,
                    /* action = */ MotionEvent.ACTION_UP,
                    /* x = */ x,
                    /* y = */ y,
                    /* metaState = */ 0,
                )
            appHeader.dispatchTouchEvent(upEvent)
            flushAll()

            // Verify that the drag positioning end callback was invoked even if not actually
            // drag-moved, to allow the controller to know about the gesture completing and to
            // clean up its state. See b/418225224.
            verify(mockDesktopTasksController)
                .onDragPositioningEnd(
                    taskInfo = eq(decor.defaultWindowDecoration.taskInfo),
                    taskSurface = any(),
                    inputCoordinate = any(),
                    currentDragBounds = any(),
                    validDragArea = any(),
                    dragStartBounds = eq(TASK_BOUNDS),
                    motionEvent = eq(upEvent),
                )
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOW_DECORATION_REFACTOR)
    fun testCustomAppHeaderClick_inCustomizedRegion_doesNotPilfer() =
        testScope.runTest {
            val decor =
                setUpWindowDecoration(createCustomAppHeaderTask(TASK_BOUNDS)).apply {
                    addCustomCaptionContent(
                        outRegion = defaultDisplayExclusionRegion,
                        contentWidth = 100,
                    )
                }
            val appHeader =
                assertNotNull(
                    decor.findViewById(com.android.wm.shell.R.id.desktop_mode_caption),
                    "Expected decoration to have an app header view",
                )
            val x = TASK_BOUNDS.left + CUSTOMIZABLE_REGION_MARGIN_START + 50f
            val y = TASK_BOUNDS.top + (APP_HEADER_HEIGHT / 2f)
            val startTime = SystemClock.uptimeMillis()
            appHeader.dispatchTouchEvent(
                MotionEvent.obtain(
                    /* downTime = */ startTime,
                    /* eventTime = */ startTime,
                    /* action = */ MotionEvent.ACTION_DOWN,
                    /* x = */ x,
                    /* y = */ y,
                    /* metaState = */ 0,
                )
            )
            appHeader.dispatchTouchEvent(
                MotionEvent.obtain(
                    /* downTime = */ startTime,
                    /* eventTime = */ startTime + 20,
                    /* action = */ MotionEvent.ACTION_UP,
                    /* x = */ x,
                    /* y = */ y,
                    /* metaState = */ 0,
                )
            )
            flushAll()

            assertThat(testInputPilferer.pilferCallCount).isEqualTo(0)
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_WINDOW_DECORATION_REFACTOR)
    fun testCustomAppHeaderClick_inSystemRegion_pilfers() =
        testScope.runTest {
            val decor =
                setUpWindowDecoration(createCustomAppHeaderTask(TASK_BOUNDS)).apply {
                    addCustomCaptionContent(
                        outRegion = defaultDisplayExclusionRegion,
                        contentWidth = 100,
                    )
                }
            val appHeader =
                assertNotNull(
                    decor.findViewById(com.android.wm.shell.R.id.desktop_mode_caption),
                    "Expected decoration to have an app header view",
                )
            val x = TASK_BOUNDS.left + (CUSTOMIZABLE_REGION_MARGIN_START / 2f)
            val y = TASK_BOUNDS.top + (APP_HEADER_HEIGHT / 2f)

            val startTime = SystemClock.uptimeMillis()
            appHeader.dispatchTouchEvent(
                MotionEvent.obtain(
                    /* downTime = */ startTime,
                    /* eventTime = */ startTime,
                    /* action = */ MotionEvent.ACTION_DOWN,
                    /* x = */ x,
                    /* y = */ y,
                    /* metaState = */ 0,
                )
            )
            appHeader.dispatchTouchEvent(
                MotionEvent.obtain(
                    /* downTime = */ startTime,
                    /* eventTime = */ startTime + 20,
                    /* action = */ MotionEvent.ACTION_UP,
                    /* x = */ x,
                    /* y = */ y,
                    /* metaState = */ 0,
                )
            )
            flushAll()

            assertThat(testInputPilferer.pilferCallCount).isEqualTo(1)
            assertThat(testInputPilferer.lastPilferedView).isEqualTo(appHeader)
        }

    private fun setUpWindowDecoration(
        taskInfo: ActivityManager.RunningTaskInfo
    ): TestWindowDecoration =
        WindowDecorationTestHelper.createWindowDecoration(
                context = context,
                taskInfo = taskInfo,
                windowDecorationFinder = { taskId: Int -> windowDecorations[taskId] },
                desktopState = desktopState,
                desktopConfig = desktopConfig,
                scope = testScope.backgroundScope,
                handler = testHandler,
                executor = testExecutor,
                inputPilferer = testInputPilferer,
                displayController = mockDisplayController,
                desktopUserRepositories = userRepositories,
                splitScreenController = mockSplitScreenController,
                desktopTasksController = mockDesktopTasksController,
                taskOperations = mockTaskOperations,
                windowDecorationActions = mockWindowDecorationActions,
                captionVisibilityHelper = mockCaptionVisibilityHelper,
                appHeaderViewHolderFactory =
                    TestAppHeaderViewHolderFactory(TestHeaderDimensions(context.resources)),
                windowDecorationExclusionTracker = mockWindowDecorationExclusionTracker,
                pinnedLayerController = mockPinnedLayerController,
            )
            .also {
                assertThat(windowDecorations[taskInfo.taskId]).isNull()
                windowDecorations[taskInfo.taskId] = it.wrapped
                it.relayout(taskInfo)
            }

    private fun flushAll() {
        testScope.advanceUntilIdle()
        testExecutor.flushAll()
        TestableLooper.get(this).processAllMessages()
    }

    private companion object {
        private val TASK_BOUNDS = Rect(200, 200, 800, 600) // 600x400
    }

    @Test
    fun floatingUpAndCancelAfterDragDisabled_cancelPositionerWithoutCommit() {
        for (action in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            val task = createAppHeaderTask(TASK_BOUNDS).apply { isFloatingWindow = true }
            val decor = mock<WindowDecorationWrapper>()
            whenever(decor.taskInfo).thenReturn(task)
            val positioner = mock<DragPositioningCallback>()
            whenever(positioner.onDragPositioningStart(any(), any(), any(), any(), any()))
                .thenReturn(Rect(TASK_BOUNDS))
            val repository = mock<com.android.wm.shell.desktopmode.data.DesktopRepository>()
            val repositories = mock<DesktopUserRepositories>()
            whenever(repositories.getProfile(any())).thenReturn(repository)
            val listener = DesktopModeTouchEventListener(mContext, task, positioner, { decor },
                mockDesktopTasksController, mockTaskOperations, mock(), mockWindowDecorationActions,
                repositories, mockWindowDecorationExclusionTracker, mock(), null, mock(), mock(),
                { mock() }, null, mock(), mockPinnedLayerController)
            val view = mock<android.view.View>()
            whenever(view.id).thenReturn(com.android.wm.shell.R.id.caption_handle)
            val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 400f, 200f, 0)
            listener.handleMotionEvent(view, down)
            whenever(repository.isTaskInFullImmersiveState(task.taskId)).thenReturn(true)
            val up = MotionEvent.obtain(0, 10, action, 1200f, 0f, 0)
            listener.handleMotionEvent(view, up)
            verify(positioner).onDragPositioningCancel()
            org.mockito.kotlin.verify(positioner, org.mockito.kotlin.never())
                .onDragPositioningEnd(any(), any(), any())
            down.recycle()
            up.recycle()
        }
    }

    @Test
    fun floatingTopHandle_dragOutsideDesktop_movesLiveWithoutClick() {
        verifyFloatingHandleGesture(com.android.wm.shell.R.id.caption_handle, drag = true)
    }

    @Test
    fun floatingBottomHandle_dragOutsideDesktop_movesLiveWithoutClick() {
        verifyFloatingHandleGesture(com.android.wm.shell.R.id.floating_bottom_handle, drag = true)
    }

    @Test
    fun floatingTopHandle_tapOutsideDesktop_opensMenu() {
        verifyFloatingHandleGesture(com.android.wm.shell.R.id.caption_handle, drag = false)
    }

    @Test
    fun floatingBottomHandle_tapOutsideDesktop_opensMenu() {
        verifyFloatingHandleGesture(com.android.wm.shell.R.id.floating_bottom_handle, drag = false)
    }

    private fun verifyFloatingHandleGesture(handleId: Int, drag: Boolean) {
        val task = createAppHeaderTask(TASK_BOUNDS).apply {
            isFloatingWindow = true
            displayId = DEFAULT_DISPLAY
        }
        val decor = mock<WindowDecorationWrapper>()
        whenever(decor.taskInfo).thenReturn(task)
        whenever(decor.handleMenuController).thenReturn(mock())
        whenever(decor.taskSurface).thenReturn(mock())
        val positioner = mock<DragPositioningCallback>()
        whenever(positioner.onDragPositioningStart(any(), any(), any(), any(), any()))
            .thenReturn(Rect(TASK_BOUNDS))
        val moved = Rect(TASK_BOUNDS).apply { offset(150, 100) }
        val settled = Rect(TASK_BOUNDS).apply { offset(250, 150) }
        whenever(positioner.onDragPositioningMove(any(), eq(550f), eq(300f)))
            .thenReturn(moved)
        whenever(positioner.onDragPositioningMove(any(), eq(650f), eq(350f)))
            .thenReturn(settled)
        whenever(positioner.onDragPositioningEnd(any(), any(), any()))
            .thenReturn(if (drag) settled else Rect(TASK_BOUNDS))
        val repository = mock<com.android.wm.shell.desktopmode.data.DesktopRepository>()
        val repositories = mock<DesktopUserRepositories>()
        whenever(repositories.getProfile(any())).thenReturn(repository)
        val shellDesktopState = mock<com.android.wm.shell.desktopmode.ShellDesktopState>()
        whenever(shellDesktopState.isEligibleWindowDropTarget(any())).thenReturn(false)
        val actions = mock<WindowDecorationActions>()
        val listener = DesktopModeTouchEventListener(mContext, task, positioner, { decor },
            mockDesktopTasksController, mockTaskOperations, mock(), actions, repositories,
            mockWindowDecorationExclusionTracker, mock(), mock(), shellDesktopState, mock(),
            { mock() }, null, mock(), mockPinnedLayerController)
        // Match the touch/click contract of both FloatingBottomHandle view hosts.
        val handle = android.view.View(mContext).apply {
            id = handleId
            setOnClickListener(listener)
            setOnTouchListener { v, e ->
                val handled = listener.onTouch(v, e)
                if (!handled && e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                true
            }
        }
        fun dispatch(action: Int, time: Long, x: Float, y: Float) {
            val event = MotionEvent.obtain(0, time, action, x, y, 0).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                setDisplayId(DEFAULT_DISPLAY)
            }
            handle.dispatchTouchEvent(event)
            event.recycle()
        }
        dispatch(MotionEvent.ACTION_DOWN, 0, 400f, 200f)
        if (drag) {
            dispatch(MotionEvent.ACTION_MOVE, 200, 550f, 300f)
            verify(positioner).onDragPositioningMove(DEFAULT_DISPLAY, 550f, 300f)
            verify(decor).setIsDragging(true)
            dispatch(MotionEvent.ACTION_MOVE, 400, 650f, 350f)
            verify(positioner).onDragPositioningMove(DEFAULT_DISPLAY, 650f, 350f)
            // The positioner must receive each move before any release/commit.
            org.mockito.kotlin.verify(positioner, org.mockito.kotlin.never())
                .onDragPositioningEnd(any(), any(), any())
        }
        dispatch(MotionEvent.ACTION_UP, 500, if (drag) 650f else 400f,
            if (drag) 350f else 200f)
        verify(mockPinnedLayerController).onDragEnded(any(), eq(task), eq(TASK_BOUNDS),
            eq(if (drag) settled else TASK_BOUNDS), eq(DEFAULT_DISPLAY))
        org.mockito.kotlin.verify(actions, org.mockito.kotlin.times(if (drag) 0 else 1))
            .onOpenHandleMenu(task.taskId)
    }

}
