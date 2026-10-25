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
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.Display
import android.view.Display.DEFAULT_DISPLAY
import android.view.LayoutInflater
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.WindowInsets.Type.systemBars
import android.view.WindowManager
import android.window.DesktopExperienceFlags
import androidx.core.graphics.toPointF
import androidx.test.filters.SmallTest
import com.android.window.flags.Flags
import com.android.wm.shell.R
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.desktopmode.DesktopModeUiEventLogger
import com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT
import com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT
import com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_UNDEFINED
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.windowdecor.WindowDecoration2.SurfaceControlViewHostFactory
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalSystemViewContainer
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalViewHostViewContainer
import com.android.wm.shell.windowdecor.common.DecorThemeUtil
import com.android.wm.shell.windowdecor.common.WindowDecorTaskResourceLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for [HandleMenu].
 *
 * Build/Install/Run: atest WMShellUnitTests:HandleMenuTest
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner::class)
class HandleMenuTest : ShellTestCase() {
    @Mock private lateinit var mockDesktopWindowDecoration: DesktopModeWindowDecoration
    @Mock private lateinit var mockWindowManager: WindowManager
    @Mock private lateinit var displayController: DisplayController
    @Mock private lateinit var mockDisplay: Display
    @Mock private lateinit var mockSurfaceControl: SurfaceControl
    @Mock private lateinit var splitScreenController: SplitScreenController
    @Mock private lateinit var displayLayout: DisplayLayout
    @Mock private lateinit var mockSurfaceControlViewHost: SurfaceControlViewHost
    @Mock private lateinit var mockTaskResourceLoader: WindowDecorTaskResourceLoader
    @Mock private lateinit var mockWindowDecorationActions: WindowDecorationActions
    @Mock private lateinit var mockAppIcon: Bitmap
    @Mock private lateinit var mockDesktopModeUiEventLogger: DesktopModeUiEventLogger
    @Mock private lateinit var mockSurfaceControlViewHostFactory: SurfaceControlViewHostFactory

    private val decorThemeUtilFactory = DecorThemeUtil.Factory()
    private val handleMenuFactory = HandleMenu.HandleMenuFactory
    private lateinit var handleMenu: HandleMenu

    @Before
    fun setUp() {
        val mockAdditionalViewHostViewContainer =
            AdditionalViewHostViewContainer(
                mock(SurfaceControl::class.java),
                mockSurfaceControlViewHost,
            ) {
                SurfaceControl.Transaction()
            }
        val menuView =
            LayoutInflater.from(mContext)
                .inflate(R.layout.desktop_mode_window_decor_handle_menu, null)
        whenever(
                mockDesktopWindowDecoration.addWindow(
                    anyInt(),
                    any(),
                    any(),
                    any(),
                    anyInt(),
                    anyInt(),
                    anyInt(),
                    anyInt(),
                )
            )
            .thenReturn(mockAdditionalViewHostViewContainer)
        whenever(
                mockDesktopWindowDecoration.addWindow(
                    any<View>(),
                    any(),
                    any(),
                    any(),
                    anyInt(),
                    anyInt(),
                    anyInt(),
                    anyInt(),
                )
            )
            .thenReturn(mockAdditionalViewHostViewContainer)
        mockDesktopWindowDecoration.mDecorationContainerSurface = mockSurfaceControl
        mockDesktopWindowDecoration.mDisplay = mockDisplay
        whenever(mockAdditionalViewHostViewContainer.view).thenReturn(menuView)
        whenever(displayController.getDisplayLayout(anyInt())).thenReturn(displayLayout)
        whenever(displayLayout.width()).thenReturn(DISPLAY_BOUNDS.width())
        whenever(displayLayout.height()).thenReturn(DISPLAY_BOUNDS.height())
        whenever(displayLayout.isLandscape).thenReturn(true)
        whenever(mockSurfaceControlViewHostFactory.create(any(), any(), any(), any()))
            .thenReturn(mockSurfaceControlViewHost)
        mContext.orCreateTestableResources.apply {
            addOverride(R.dimen.desktop_mode_handle_menu_width, MENU_WIDTH)
            addOverride(R.dimen.desktop_mode_handle_menu_height, MENU_HEIGHT)
            addOverride(R.dimen.desktop_mode_handle_menu_margin_top, MENU_TOP_MARGIN)
            addOverride(R.dimen.desktop_mode_handle_menu_padding_left_bottom_right, MENU_PADDING)
            addOverride(
                R.dimen.desktop_mode_handle_menu_pill_spacing_margin,
                MENU_PILL_SPACING_MARGIN,
            )
        }
        mockDesktopWindowDecoration.mDecorWindowContext = mContext
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HANDLE_INPUT_FIX)
    fun testFullscreenMenuUsesSystemViewContainer() = runTest {
        createTaskInfo(WINDOWING_MODE_FULLSCREEN, SPLIT_POSITION_UNDEFINED)
        val handleMenu = createAndShowHandleMenu(SPLIT_POSITION_UNDEFINED)
        assertTrue(handleMenu.handleMenuViewContainer is AdditionalSystemViewContainer)
        // Verify menu is created at coordinates that, when added to WindowManager,
        // show at the top-center of display.
        val expected = Point(DISPLAY_BOUNDS.centerX() - MENU_WIDTH / 2, MENU_TOP_MARGIN)
        assertEquals(expected.toPointF(), handleMenu.handleMenuPosition)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HANDLE_INPUT_FIX)
    fun testFreeformMenu_usesViewHostViewContainer() = runTest {
        createTaskInfo(WINDOWING_MODE_FREEFORM, SPLIT_POSITION_UNDEFINED)
        handleMenu = createAndShowHandleMenu(SPLIT_POSITION_UNDEFINED)
        assertTrue(handleMenu.handleMenuViewContainer is AdditionalViewHostViewContainer)
        // Verify menu is created near top-left of task.
        val expected = Point(0, MENU_TOP_MARGIN)
        assertEquals(expected.toPointF(), handleMenu.handleMenuPosition)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HANDLE_INPUT_FIX)
    fun testSplitLeftMenu_usesSystemViewContainer() = runTest {
        createTaskInfo(WINDOWING_MODE_MULTI_WINDOW, SPLIT_POSITION_TOP_OR_LEFT)
        handleMenu = createAndShowHandleMenu(SPLIT_POSITION_TOP_OR_LEFT)
        assertTrue(handleMenu.handleMenuViewContainer is AdditionalSystemViewContainer)
        // Verify menu is created at coordinates that, when added to WindowManager,
        // show at the top-center of split left task.
        val expected = Point(SPLIT_LEFT_BOUNDS.centerX() - MENU_WIDTH / 2, MENU_TOP_MARGIN)
        assertEquals(expected.toPointF(), handleMenu.handleMenuPosition)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HANDLE_INPUT_FIX)
    fun testSplitRightMenu_usesSystemViewContainer() = runTest {
        createTaskInfo(WINDOWING_MODE_MULTI_WINDOW, SPLIT_POSITION_BOTTOM_OR_RIGHT)
        handleMenu = createAndShowHandleMenu(SPLIT_POSITION_BOTTOM_OR_RIGHT)
        assertTrue(handleMenu.handleMenuViewContainer is AdditionalSystemViewContainer)
        // Verify menu is created at coordinates that, when added to WindowManager,
        // show at the top-center of split right task.
        val expected = Point(SPLIT_RIGHT_BOUNDS.centerX() - MENU_WIDTH / 2, MENU_TOP_MARGIN)
        assertEquals(expected.toPointF(), handleMenu.handleMenuPosition)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HANDLE_INPUT_FIX)
    fun multiSplitBottomRightMenu_usesOwningLeafInBothAxes() = runTest {
        createTaskInfo(WINDOWING_MODE_MULTI_WINDOW, SPLIT_POSITION_BOTTOM_OR_RIGHT)
        val leaf = Rect(SPLIT_RIGHT_BOUNDS.left, DISPLAY_BOUNDS.centerY(),
            DISPLAY_BOUNDS.right, DISPLAY_BOUNDS.bottom)
        whenever(splitScreenController.getMultiSplitTaskBounds(anyInt(), any())).thenAnswer {
            (it.arguments[1] as Rect).set(leaf)
            true
        }
        handleMenu = createAndShowHandleMenu(SPLIT_POSITION_BOTTOM_OR_RIGHT,
            captionXOverride = leaf.width() / 2 - HANDLE_WIDTH / 2)
        assertTrue(handleMenu.handleMenuViewContainer is AdditionalSystemViewContainer)
        assertEquals(Point(leaf.centerX() - MENU_WIDTH / 2, leaf.top + MENU_TOP_MARGIN).toPointF(),
            handleMenu.handleMenuPosition)
    }

    @Test
    fun testCreate_forceShowSystemBars_usesSystemViewContainer() = runTest {
        createTaskInfo(WINDOWING_MODE_FREEFORM)

        handleMenu = createAndShowHandleMenu(forceShowSystemBars = true)

        // Only AdditionalSystemViewContainer supports force showing system bars.
        assertTrue(handleMenu.handleMenuViewContainer is AdditionalSystemViewContainer)
    }

    @Test
    fun testCreate_forceShowSystemBars() = runTest {
        createTaskInfo(WINDOWING_MODE_FREEFORM)

        handleMenu = createAndShowHandleMenu(forceShowSystemBars = true)

        val types =
            (handleMenu.handleMenuViewContainer as AdditionalSystemViewContainer)
                .lp
                .forciblyShownTypes
        assertTrue((types and systemBars()) != 0)
    }

    @Test
    fun testCreate_loadsAppInfoInBackground() = runTest {
        createTaskInfo(WINDOWING_MODE_FREEFORM)

        handleMenu = createAndShowHandleMenu()
        advanceUntilIdle()

        assertThat(handleMenu.handleMenuView!!.appNameView.text).isEqualTo(APP_NAME)
        val drawable = handleMenu.handleMenuView!!.appIconView.drawable as BitmapDrawable
        assertThat(drawable.bitmap).isEqualTo(mockAppIcon)
    }

    @Test
    @DisableFlags(FLAG_ENABLE_NON_DEFAULT_DISPLAY_SPLIT_BUGFIX)
    fun splitButton_onDefaultDisplay_flagOff_isVisible() = runTest {
        createTaskInfo(windowingMode = WINDOWING_MODE_FULLSCREEN, displayId = DEFAULT_DISPLAY)
        val handleMenu = createAndShowHandleMenu()

        val handleMenuView =
            checkNotNull(handleMenu.handleMenuView) { "Expected non-null handle menu view" }
        val splitButton =
            handleMenuView.windowingPillView.findViewById<View>(R.id.split_screen_button)
        assertThat(splitButton.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    @DisableFlags(FLAG_ENABLE_NON_DEFAULT_DISPLAY_SPLIT_BUGFIX)
    fun splitButton_onExternalDisplay_flagOff_isGone() = runTest {
        createTaskInfo(windowingMode = WINDOWING_MODE_FULLSCREEN, displayId = EXTERNAL_DISPLAY_ID)
        val handleMenu = createAndShowHandleMenu()

        val handleMenuView =
            checkNotNull(handleMenu.handleMenuView) { "Expected non-null handle menu view" }
        val splitButton =
            handleMenuView.windowingPillView.findViewById<View>(R.id.split_screen_button)
        assertThat(splitButton.visibility).isEqualTo(View.GONE)
    }

    @Test
    @EnableFlags(FLAG_ENABLE_NON_DEFAULT_DISPLAY_SPLIT_BUGFIX)
    fun splitButton_onExternalDisplay_flagOn_isVisible() = runTest {
        createTaskInfo(windowingMode = WINDOWING_MODE_FULLSCREEN, displayId = EXTERNAL_DISPLAY_ID)
        val handleMenu = createAndShowHandleMenu()

        val handleMenuView =
            checkNotNull(handleMenu.handleMenuView) { "Expected non-null handle menu view" }
        val splitButton =
            handleMenuView.windowingPillView.findViewById<View>(R.id.split_screen_button)
        assertThat(splitButton.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    @EnableFlags(FLAG_ENABLE_NON_DEFAULT_DISPLAY_SPLIT_BUGFIX)
    fun splitButton_onDefaultDisplay_flagOn_isVisible() = runTest {
        createTaskInfo(windowingMode = WINDOWING_MODE_FULLSCREEN, displayId = DEFAULT_DISPLAY)
        val handleMenu = createAndShowHandleMenu()

        val handleMenuView =
            checkNotNull(handleMenu.handleMenuView) { "Expected non-null handle menu view" }
        val splitButton =
            handleMenuView.windowingPillView.findViewById<View>(R.id.split_screen_button)
        assertThat(splitButton.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun windowingPill_fullscreenTask_showsFloatingAndHidesDesktop() {
        val root = LayoutInflater.from(context)
            .inflate(R.layout.desktop_mode_window_decor_handle_menu, null)
        val pill = root.requireViewById<WindowingPillView>(R.id.windowing_pill)
        pill.desktopModeCompatPolicy = mock()
        pill.initialize(mock(), mock(), true, {})
        val task = TestRunningTaskInfoBuilder()
            .setDisplayId(DEFAULT_DISPLAY)
            .setWindowingMode(android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN)
            .build()

        pill.bind(task, false)

        assertThat(pill.requireViewById<View>(R.id.floating_button).visibility)
            .isEqualTo(View.VISIBLE)
        assertThat(pill.requireViewById<View>(R.id.desktop_button).visibility)
            .isEqualTo(View.GONE)
        assertThat(pill.requireViewById<View>(R.id.floating_button).contentDescription.toString())
            .isEqualTo("Floating window")
    }

    @Test
    fun windowingPill_blockedTask_hidesEntry() {
        val root = LayoutInflater.from(context)
            .inflate(R.layout.desktop_mode_window_decor_handle_menu, null)
        val pill = root.requireViewById<WindowingPillView>(R.id.windowing_pill)
        pill.desktopModeCompatPolicy = mock()
        val task = TestRunningTaskInfoBuilder()
            .setDisplayId(DEFAULT_DISPLAY)
            .setWindowingMode(android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN)
            .build()
        whenever(pill.desktopModeCompatPolicy.shouldDisableDesktopEntryPoints(task)).thenReturn(true)
        pill.initialize(mock(), mock(), true, {})

        pill.bind(task, false)

        assertThat(pill.requireViewById<View>(R.id.floating_button).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun windowingPill_floatingTask_hidesEntry() {
        val root = LayoutInflater.from(context)
            .inflate(R.layout.desktop_mode_window_decor_handle_menu, null)
        val pill = root.requireViewById<WindowingPillView>(R.id.windowing_pill)
        pill.desktopModeCompatPolicy = mock()
        pill.initialize(mock(), mock(), true, {})
        val task = TestRunningTaskInfoBuilder()
            .setDisplayId(DEFAULT_DISPLAY)
            .setWindowingMode(android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM)
            .build()
        task.isFloatingWindow = true

        pill.bind(task, false)

        assertThat(pill.requireViewById<View>(R.id.floating_button).visibility)
            .isEqualTo(View.GONE)
    }

    @Test
    fun testWindowingPill_shownWhenConfigured() = runTest {
        createTaskInfo(WINDOWING_MODE_FREEFORM)
        handleMenu = createAndShowHandleMenu(shouldShowWindowingPill = true)

        val windowingPill = handleMenu.handleMenuView!!.windowingPillView
        assertThat(windowingPill.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun testWindowingPill_hiddenWhenConfigured() = runTest {
        createTaskInfo(WINDOWING_MODE_FREEFORM)
        handleMenu = createAndShowHandleMenu(shouldShowWindowingPill = false)

        val windowingPill = handleMenu.handleMenuView!!.windowingPillView
        assertThat(windowingPill.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun relayout_shouldShowWindowingPillFalse_doesNotCrash() = runTest {
        createTaskInfo(WINDOWING_MODE_FREEFORM)
        handleMenu = createAndShowHandleMenu(shouldShowWindowingPill = false)

        // Relayout should not crash even if shouldShowWindowingPill is false
        handleMenu.relayout(
            SurfaceControl.Transaction(),
            mContext.resources.configuration,
            captionX = 0,
            captionY = 0,
        )
    }

    private suspend fun createTaskInfo(
        windowingMode: Int,
        splitPosition: Int? = null,
        displayId: Int = DEFAULT_DISPLAY,
    ) {
        val taskDescriptionBuilder =
            ActivityManager.TaskDescription.Builder().setBackgroundColor(Color.YELLOW)
        val bounds =
            when (windowingMode) {
                WINDOWING_MODE_FULLSCREEN -> DISPLAY_BOUNDS
                WINDOWING_MODE_FREEFORM -> FREEFORM_BOUNDS
                WINDOWING_MODE_MULTI_WINDOW -> {
                    checkNotNull(splitPosition)
                    if (splitPosition == SPLIT_POSITION_TOP_OR_LEFT) {
                        SPLIT_LEFT_BOUNDS
                    } else {
                        SPLIT_RIGHT_BOUNDS
                    }
                }
                else -> error("Unsupported windowing mode")
            }
        mockDesktopWindowDecoration.mTaskInfo =
            TestRunningTaskInfoBuilder()
                .setDisplayId(displayId)
                .setTaskDescriptionBuilder(taskDescriptionBuilder)
                .setWindowingMode(windowingMode)
                .setBounds(bounds)
                .setVisible(true)
                .build()
        if (windowingMode == WINDOWING_MODE_MULTI_WINDOW) {
            whenever(splitScreenController.getSplitPosition(any())).thenReturn(splitPosition)
            whenever(splitScreenController.getStageBounds(any(), any())).thenAnswer {
                (it.arguments.first() as Rect).set(SPLIT_LEFT_BOUNDS)
                (it.arguments[1] as Rect).set(SPLIT_RIGHT_BOUNDS)
            }
        }
        whenever(mockTaskResourceLoader.getNameAndHeaderIcon(mockDesktopWindowDecoration.mTaskInfo))
            .thenReturn(Pair(APP_NAME, mockAppIcon))
    }

    private fun TestScope.createAndShowHandleMenu(
        splitPosition: Int? = null,
        forceShowSystemBars: Boolean = false,
        shouldShowWindowingPill: Boolean = true,
        captionXOverride: Int? = null,
    ): HandleMenu {
        val layoutId =
            if (mockDesktopWindowDecoration.mTaskInfo.isFreeform) {
                R.layout.desktop_mode_app_header
            } else {
                R.layout.desktop_mode_app_handle
            }
        val captionX =
            captionXOverride ?: when (mockDesktopWindowDecoration.mTaskInfo.windowingMode) {
                WINDOWING_MODE_FULLSCREEN -> (DISPLAY_BOUNDS.width() / 2) - (HANDLE_WIDTH / 2)
                WINDOWING_MODE_FREEFORM -> 0
                WINDOWING_MODE_MULTI_WINDOW -> {
                    checkNotNull(splitPosition)
                    if (splitPosition == SPLIT_POSITION_TOP_OR_LEFT) {
                        (SPLIT_LEFT_BOUNDS.width() / 2) - (HANDLE_WIDTH / 2)
                    } else {
                        SPLIT_LEFT_BOUNDS.width() + (SPLIT_RIGHT_BOUNDS.width() / 2) -
                            (HANDLE_WIDTH / 2)
                    }
                }
                else -> error("Invalid windowing mode")
            }
        val captionView = LayoutInflater.from(mContext).inflate(layoutId, null)
        val handleMenu =
            if (DesktopExperienceFlags.ENABLE_WINDOW_DECORATION_REFACTOR.isTrue) {
                handleMenuFactory.create(
                    StandardTestDispatcher(testScheduler),
                    this,
                    mContext,
                    mockDesktopWindowDecoration.mTaskInfo,
                    mockSurfaceControl,
                    mockDisplay,
                    WindowManagerWrapper(mockWindowManager),
                    mockWindowDecorationActions,
                    mockTaskResourceLoader,
                    layoutId,
                    splitScreenController,
                    shouldShowWindowingPill = shouldShowWindowingPill,
                    shouldShowNewWindowButton = true,
                    shouldShowManageWindowsButton = false,
                    shouldShowChangeAspectRatioButton = false,
                    shouldShowGameControlsButton = false,
                    shouldShowDesktopModeButton = true,
                    shouldShowRestartButton = true,
                    appToWebData = null,
                    mockDesktopModeUiEventLogger,
                    captionView = captionView,
                    captionWidth = HANDLE_WIDTH,
                    captionHeight = 50,
                    captionX = captionX,
                    captionY = 0,
                    decorThemeUtilFactory = decorThemeUtilFactory,
                    surfaceControlViewHostFactory = mockSurfaceControlViewHostFactory,
                )
            } else {
                handleMenuFactory.create(
                    StandardTestDispatcher(testScheduler),
                    this,
                    mockDesktopWindowDecoration,
                    WindowManagerWrapper(mockWindowManager),
                    mockWindowDecorationActions,
                    mockTaskResourceLoader,
                    layoutId,
                    splitScreenController,
                    shouldShowWindowingPill = shouldShowWindowingPill,
                    shouldShowNewWindowButton = true,
                    shouldShowManageWindowsButton = false,
                    shouldShowChangeAspectRatioButton = false,
                    shouldShowGameControlsButton = false,
                    shouldShowDesktopModeButton = true,
                    shouldShowRestartButton = true,
                    appToWebData = null,
                    mockDesktopModeUiEventLogger,
                    captionView = captionView,
                    captionWidth = HANDLE_WIDTH,
                    captionHeight = 50,
                    captionX = captionX,
                    captionY = 0,
                    decorThemeUtilFactory = decorThemeUtilFactory,
                    surfaceControlViewHostFactory = mockSurfaceControlViewHostFactory,
                )
            }
        handleMenu.show(
            openInAppOrBrowserClickListener = mock(),
            onOpenByDefaultClickListener = mock(),
            onCloseMenuClickListener = mock(),
            onOutsideTouchListener = mock(),
            onHandleMenuClicked = mock(),
            forceShowSystemBars = forceShowSystemBars,
        )
        return handleMenu
    }

    companion object {
        private val DISPLAY_BOUNDS = Rect(0, 0, 2560, 1600)
        private val FREEFORM_BOUNDS = Rect(500, 500, 2000, 1200)
        private val SPLIT_LEFT_BOUNDS = Rect(0, 0, 1280, 1600)
        private val SPLIT_RIGHT_BOUNDS = Rect(1280, 0, 2560, 1600)
        private const val MENU_WIDTH = 200
        private const val MENU_HEIGHT = 400
        private const val MENU_TOP_MARGIN = 10
        private const val MENU_PADDING = 20
        private const val MENU_PILL_SPACING_MARGIN = 4
        private const val HANDLE_WIDTH = 80
        private const val APP_NAME = "Test App"
        private const val EXTERNAL_DISPLAY_ID = 1
        private const val FLAG_ENABLE_NON_DEFAULT_DISPLAY_SPLIT_BUGFIX =
            "com.android.window.flags.enable_non_default_display_split_bugfix"
    }


    @Test
    fun windowingPill_fullscreenHasFullscreenSplitAndFloat_usesExistingAction() {
        val actions = org.mockito.kotlin.mock<WindowDecorationActions>()
        val pill = WindowingPillView(mContext, mock())
        pill.initialize(actions, mockDesktopModeUiEventLogger, false) {}
        pill.desktopModeCompatPolicy = mock()
        val task = TestRunningTaskInfoBuilder().setWindowingMode(WINDOWING_MODE_FULLSCREEN).build()
        task.displayId = 0
        task.isResizeable = true
        pill.bind(task, false)
        val button = pill.requireViewById<View>(R.id.floating_button)
        val params = button.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        assertEquals(R.id.split_screen_button, params.startToEnd)
        assertEquals(R.id.desktop_button, params.endToStart)
        assertEquals(View.VISIBLE, button.visibility)
        assertEquals(View.VISIBLE, pill.requireViewById<View>(R.id.fullscreen_button).visibility)
        button.performClick()
        org.mockito.kotlin.verify(actions).onToFloat(task.taskId)
        task.isFloatingWindow = true
        pill.bind(task, false)
        assertEquals(View.GONE, button.visibility)
        assertEquals(View.VISIBLE, pill.requireViewById<View>(R.id.fullscreen_button).visibility)
    }

    @Test
    fun windowingPill_remainingDescriptionsHaveNoFormatPlaceholder() {
        val task = TestRunningTaskInfoBuilder().setWindowingMode(WINDOWING_MODE_FULLSCREEN).build()
        task.taskDescription = android.app.ActivityManager.TaskDescription.Builder()
            .setLabel("Test app").build()
        val pill = WindowingPillView(mContext, mock())
        pill.initialize(mock(), mock(), false) {}
        listOf(false, true).forEach { floating ->
            task.isFloatingWindow = floating
            pill.bind(task, false)
            listOf(R.id.rotate_window_button, R.id.fullscreen_button, R.id.split_screen_button,
                R.id.floating_button)
                .forEach { id ->
                    val button = pill.requireViewById<View>(id)
                    assertThat(button.contentDescription?.toString().orEmpty()).doesNotContain("%")
                    assertThat(button.tooltipText?.toString().orEmpty()).doesNotContain("%")
                }
        }
    }


    @Test fun floatingCloseUsesDismissRowBelowIcons_withoutAppName() = runTest {
        createTaskInfo(windowingMode = WINDOWING_MODE_FREEFORM, displayId = DEFAULT_DISPLAY)
        val task = mockDesktopWindowDecoration.mTaskInfo
        task.isFloatingWindow = true
        task.taskDescription = android.app.ActivityManager.TaskDescription.Builder()
            .setLabel("Test app").build()
        handleMenu = createAndShowHandleMenu()
        val root = handleMenu.handleMenuView!!.windowingPillView.parent as View
        val pill = root.requireViewById<View>(R.id.windowing_pill)
        val row = root.requireViewById<View>(R.id.floating_close_pill)
        val parent = row.parent as android.view.ViewGroup
        assertEquals(parent.indexOfChild(pill) + 1, parent.indexOfChild(row))
        assertEquals(View.VISIBLE, row.visibility)
        val button = root.requireViewById<View>(R.id.floating_close_button)
        val label = mContext.getString(R.string.floating_window_close)
        assertEquals(label, button.contentDescription.toString())
        assertEquals(label, button.tooltipText.toString())
        assertThat(label).doesNotContain("%")
        assertThat(label).doesNotContain("Test app")
        button.performClick()
        org.mockito.kotlin.verify(mockWindowDecorationActions).onClose(task)
    }

    @Test fun fullscreenHasNoCloseRow() = runTest {
        createTaskInfo(windowingMode = WINDOWING_MODE_FULLSCREEN, displayId = DEFAULT_DISPLAY)
        handleMenu = createAndShowHandleMenu()
        assertEquals(View.GONE, (handleMenu.handleMenuView!!.windowingPillView.parent as View)
            .requireViewById<View>(R.id.floating_close_pill).visibility)
    }

    @Test fun desktopFloat_requiresActiveDeskAndNeverShowsForFloatingTask() {
        val actions = mock<WindowDecorationActions>()
        val task = TestRunningTaskInfoBuilder().setWindowingMode(
            android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM).build()
        task.isResizeable = true
        val pill = WindowingPillView(mContext, mock())
        pill.initialize(actions, mock(), false) {}
        pill.bind(task, false)
        assertEquals(View.GONE, pill.requireViewById<View>(R.id.floating_button).visibility)
        whenever(actions.canMoveDeskToFloating(task)).thenReturn(true)
        pill.bind(task, false)
        assertEquals(View.VISIBLE, pill.requireViewById<View>(R.id.floating_button).visibility)
        task.isFloatingWindow = true
        pill.bind(task, false)
        assertEquals(View.GONE, pill.requireViewById<View>(R.id.floating_button).visibility)
    }

    @Test fun rotate_onlyFloatingAndIncludesFixedOrientationApps() {
        val resources = org.mockito.kotlin.spy(mContext.resources)
        val context = org.mockito.kotlin.spy(mContext)
        org.mockito.kotlin.doReturn(resources).whenever(context).resources
        org.mockito.kotlin.doReturn(android.R.string.ok).whenever(resources)
            .getIdentifier("accessibility_rotate_button", "string", "com.android.systemui")
        val task = TestRunningTaskInfoBuilder().setWindowingMode(WINDOWING_MODE_FULLSCREEN).build()
        val pill = WindowingPillView(context, mock())
        pill.initialize(mock(), mock(), false) {}
        listOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE).forEach {
            task.topActivityRequestedOrientation = it
            task.isFloatingWindow = false
            pill.bind(task, false)
            assertEquals(View.GONE, pill.requireViewById<View>(R.id.rotate_window_button).visibility)
            task.isFloatingWindow = true
            pill.bind(task, false)
            assertEquals(View.VISIBLE, pill.requireViewById<View>(R.id.rotate_window_button).visibility)
        }
    }

    @Test fun addBelowRow_rechecksDestinationAndDispatchesSharedAction() = runTest {
        createTaskInfo(windowingMode = WINDOWING_MODE_FULLSCREEN, displayId = DEFAULT_DISPLAY)
        val task = mockDesktopWindowDecoration.mTaskInfo
        whenever(splitScreenController.isTaskInSplitScreenForeground(task.taskId)).thenReturn(true)
        val bounds = Rect(1280, 800, 2560, 1600)
        val slot = SplitScreenController.AppDragSlot(1, 2, bounds, bounds, false, 1, false, 0, true)
        whenever(splitScreenController.getAddBelowSlot(task.taskId)).thenReturn(slot)
        handleMenu = createAndShowHandleMenu()
        val root = handleMenu.handleMenuView!!.rootView
        val button = root.requireViewById<HandleMenuActionButton>(R.id.split_add_button)
        assertEquals(View.VISIBLE, button.visibility)
        assertEquals(mContext.getString(R.string.split_picker_add_below), button.textView.text.toString())
        button.performClick()
        org.mockito.kotlin.verify(mockWindowDecorationActions).onAddSplitBelow(task.taskId)
        whenever(splitScreenController.getAddBelowSlot(task.taskId)).thenReturn(null)
        button.performClick()
        org.mockito.kotlin.verify(mockWindowDecorationActions, org.mockito.kotlin.times(1))
            .onAddSplitBelow(task.taskId)
        handleMenu.handleMenuView!!.bind(task, true)
        assertEquals(View.GONE, button.visibility)
    }

    @Test fun addSideRowUsesAdaptiveLabelAndSharedPickerAction() = runTest {
        createTaskInfo(windowingMode = WINDOWING_MODE_FULLSCREEN, displayId = DEFAULT_DISPLAY)
        val task = mockDesktopWindowDecoration.mTaskInfo
        whenever(splitScreenController.isTaskInSplitScreenForeground(task.taskId)).thenReturn(true)
        val bounds = Rect(1280, 0, 2560, 800)
        val slot = SplitScreenController.AppDragSlot(1, 2, bounds, bounds, false, 1, false, 0, false)
        whenever(splitScreenController.getAddBelowSlot(task.taskId)).thenReturn(slot)
        handleMenu = createAndShowHandleMenu()
        val button = handleMenu.handleMenuView!!.rootView
            .requireViewById<HandleMenuActionButton>(R.id.split_add_button)
        assertEquals(View.VISIBLE, button.visibility)
        assertEquals(mContext.getString(R.string.split_picker_add_to_side), button.textView.text.toString())
        button.performClick()
        org.mockito.kotlin.verify(mockWindowDecorationActions).onAddSplitBelow(task.taskId)
    }

}
