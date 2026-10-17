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

import android.app.TaskInfo
import android.app.WindowConfiguration
import android.content.res.Resources
import android.graphics.Rect
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper
import android.util.DisplayMetrics
import android.view.Display
import android.window.TransitionRequestInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.window.flags.Flags
import com.android.wm.shell.R
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.shared.desktopmode.DesktopState
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt

/**
 * Unit tests against [PinnedLayerPresentationController]
 *
 * Build/Install/Run: atest WMShellUnitTests:PinnedLayerPresentationControllerTests
 */
@SmallTest
@TestableLooper.RunWithLooper
@EnableFlags(Flags.FLAG_ENABLE_INTERACTIVE_PICTURE_IN_PICTURE)
@RunWith(AndroidJUnit4::class)
class PinnedLayerPresentationControllerTests : ShellTestCase() {
    @Mock private lateinit var displayController: DisplayController
    @Mock private lateinit var displayLayout: DisplayLayout
    @Mock private lateinit var desktopState: DesktopState
    private lateinit var resources: Resources
    private lateinit var displayMetrics: DisplayMetrics

    private lateinit var controller: PinnedLayerPresentationController

    @Before
    fun setUp() {
        // Default display setup: 1080x1920, density 2.0, insets top=100 bottom=50
        // 10px padding for pinned window.
        // The center of available display area (excl. insets) is (540, 910).
        val testableResources = mContext.getOrCreateTestableResources()
        testableResources.addOverride(R.dimen.pinned_window_init_padding, 10)
        displayMetrics = DisplayMetrics()
        displayMetrics.density = 2.0f
        whenever(testableResources.resources.displayMetrics).thenReturn(displayMetrics)
        whenever(displayController.getDisplayLayout(Display.DEFAULT_DISPLAY))
            .thenReturn(displayLayout)
        whenever(displayController.getDisplayContext(Display.DEFAULT_DISPLAY)).thenReturn(context)
        whenever(displayLayout.width()).thenReturn(1080)
        whenever(displayLayout.height()).thenReturn(1920)
        whenever(displayLayout.stableInsets()).thenReturn(Rect(0, 100, 0, 50))
        whenever(displayLayout.getStableBounds(any())).thenAnswer {
            val outRect = it.getArgument<Rect>(0)
            outRect.set(0, 100, 1080, 1870) // incl. insets
        }
        whenever(desktopState.isDesktopModeSupportedOnDisplay(anyInt())).thenReturn(true)

        controller = PinnedLayerPresentationController(context, displayController, desktopState)
    }

    @Test
    fun miniAndStash_doNotOverwriteRememberedNormalGeometry() {
        val task = createTaskInfo(Rect(100, 200, 700, 1000)).apply {
            isFloatingWindow = true
            baseActivity = android.content.ComponentName("test.mini", "Main")
            positionInParent = android.graphics.Point(100, 200)
            floatingWindowPosition.set(100, 200)
            userId = 10
        }
        val normal = Rect(task.floatingWindowRestoreBounds)
        controller.rememberBounds(task)
        val entry = controller.miniState.enter(task.userId, task.taskId,
            FloatingMiniState.Restore(normal, task.configuration.windowConfiguration.bounds,
                task.floatingWindowScale), Rect(8, 108, 332, 540), .4f)
        task.floatingWindowScale = .4f
        task.positionInParent = android.graphics.Point(8, 108)
        task.floatingWindowPosition.set(8, 108)
        controller.rememberBounds(task)
        entry.edge = FloatingMiniState.Edge.LEFT
        task.floatingWindowPosition.set(-292, 108)
        controller.rememberBounds(task)
        assertThat(controller.getPinEntryDestinationBounds(task)).isEqualTo(normal)
    }

    @Test
    fun restart_rebuildsStashAndPreservesRememberedNormalGeometry() {
        val store = FloatingWindowGeometryStore({ null }, java.util.concurrent.Executor { it.run() })
        val first = PinnedLayerPresentationController(context, displayController, desktopState, store)
        val task = createTaskInfo(Rect(0, 0, 800, 1200)).apply {
            isFloatingWindow = true
            baseActivity = android.content.ComponentName("test.restart", "Main")
            floatingWindowScale = .75f
            floatingWindowPosition.set(100, 200)
        }
        val normal = Rect(task.floatingWindowRestoreBounds)
        first.rememberBounds(task)
        val restarted = PinnedLayerPresentationController(context, displayController, desktopState, store)
        task.floatingWindowScale = .405f
        task.floatingWindowPosition.set(-268, 200)
        val recovered = requireNotNull(restarted.recoverMini(task))
        assertThat(recovered.edge).isEqualTo(FloatingMiniState.Edge.LEFT)
        assertThat(recovered.bounds.right).isEqualTo(56)
        assertThat(recovered.restore.bounds).isEqualTo(normal)
        restarted.rememberBounds(task)
        assertThat(restarted.getPinEntryDestinationBounds(task)).isEqualTo(normal)
    }

    @Test
    fun restart_miniWithoutSavedGeometryGetsOnscreenNormalRestore() {
        val task = createTaskInfo(Rect(0, 0, 800, 1200)).apply {
            isFloatingWindow = true
            floatingWindowScale = .405f
            floatingWindowPosition.set(16, 116)
        }
        val recovered = requireNotNull(controller.recoverMini(task))
        assertThat(recovered.edge).isEqualTo(FloatingMiniState.Edge.NONE)
        assertThat(Rect(0, 100, 1080, 1870).contains(recovered.restore.bounds)).isTrue()
        assertThat(recovered.restore.bounds.width()).isGreaterThan(recovered.bounds.width())
        assertThat(recovered.restore.scale).isEqualTo(FloatingWindowGeometry.DEFAULT_SCALE)
    }

    @Test
    fun remember_afterRestartRebuildsMiniBeforeStore() {
        val task = createTaskInfo(Rect(0, 0, 800, 1200)).apply {
            isFloatingWindow = true
            floatingWindowScale = .405f
            floatingWindowPosition.set(16, 116)
        }
        controller.rememberBounds(task)
        assertThat(controller.miniState.get(task.userId, task.taskId)).isNotNull()
    }

    @Test
    fun getPinEntryDestinationBounds_portrait_centeredAndSizedToDisplay() {
        val bounds = requireNotNull(
            controller.getPinEntryDestinationBounds(createTaskInfo(Rect(0, 0, 500, 600)))
        )

        assertThat(bounds.width()).isEqualTo(576)
        assertThat(bounds.height()).isEqualTo(777)
        assertThat(bounds.centerX()).isEqualTo(540)
        assertThat(bounds.centerY()).isEqualTo(985)
    }

    @Test
    fun getPinEntryDestinationBounds_respectsAppMinimumSize() {
        val task = createTaskInfo(Rect(0, 0, 500, 600))
        task.minWidth = 800
        task.minHeight = 900

        val bounds = requireNotNull(controller.getPinEntryDestinationBounds(task))

        assertThat(bounds.width()).isAtLeast(800)
        assertThat(bounds.height()).isAtLeast(900)
        assertThat(Rect(0, 100, 1080, 1870).contains(bounds)).isTrue()
    }

    @Test
    fun getPinEntryDestinationBounds_remembersPackageAndUser() {
        val task = createTaskInfo(Rect(100, 200, 700, 1000))
        task.baseActivity = android.content.ComponentName("test.floating", "Main")
        task.isFloatingWindow = true
        task.userId = 10
        controller.rememberBounds(task)

        assertThat(controller.getPinEntryDestinationBounds(task))
            .isEqualTo(Rect(100, 200, 700, 1000))
        task.userId = 11
        assertThat(controller.getPinEntryDestinationBounds(task))
            .isNotEqualTo(Rect(100, 200, 700, 1000))
    }

    @Test
    fun getPinEntryDestinationBounds_landscape_usesSeparateGeometry() {
        val task = createTaskInfo(Rect(100, 200, 700, 1000))
        task.baseActivity = android.content.ComponentName("test.floating", "Main")
        task.isFloatingWindow = true
        controller.rememberBounds(task)
        whenever(displayLayout.width()).thenReturn(1920)
        whenever(displayLayout.height()).thenReturn(1080)
        whenever(displayLayout.getStableBounds(any())).thenAnswer {
            it.getArgument<Rect>(0).set(0, 100, 1920, 1030)
        }

        val bounds = requireNotNull(controller.getPinEntryDestinationBounds(task))

        assertThat(bounds.width()).isEqualTo(777)
        assertThat(bounds.height()).isEqualTo(576)
        assertThat(bounds.centerX()).isEqualTo(960)
    }

    @Test
    fun rememberedManualBounds_acceptSmallRoundingDifferences() {
        val task = createTaskInfo(Rect(100, 200, 600, 1000)).apply {
            isFloatingWindow = true
            baseActivity = android.content.ComponentName("test.geometry", "Main")
            minWidth = 10
            minHeight = 10
        }
        controller.setManualOrientation(task, true, Rect(200, 300, 900, 900))
        val actual = Rect(201, 301, 901, 901)
        task.configuration.windowConfiguration.setBounds(actual)
        controller.rememberBounds(task)
        assertThat(controller.getPinEntryDestinationBounds(task)).isEqualTo(actual)
    }

    @Test
    fun rememberedManualBounds_mismatchedCallbackDoesNotBlockLaterGeometry() {
        val task = createTaskInfo(Rect(100, 200, 600, 1000)).apply {
            isFloatingWindow = true
            baseActivity = android.content.ComponentName("test.geometry", "Main")
            minWidth = 10
            minHeight = 10
        }
        controller.setManualOrientation(task, true, Rect(200, 300, 900, 900))
        controller.rememberBounds(task)
        val actual = Rect(200, 400, 800, 1400)
        task.configuration.windowConfiguration.setBounds(actual)
        controller.rememberBounds(task)
        assertThat(controller.getPinEntryDestinationBounds(task)).isEqualTo(actual)
    }

    @Test
    fun entry_ignoresSavedManualRotationAndUsesCurrentFullscreenAspect() {
        val task = createTaskInfo(Rect(0, 0, 1000, 600)).apply {
            isFloatingWindow = true
            baseActivity = android.content.ComponentName("test.entry", "Main")
            minWidth = 1
            minHeight = 1
        }
        controller.setManualOrientation(task, true, Rect(100, 200, 1000, 800))
        task.isFloatingWindow = false
        task.configuration.windowConfiguration.setBounds(Rect(0, 0, 600, 1000))
        task.configuration.orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT
        val bounds = requireNotNull(controller.getPinEntryDestinationBounds(task))
        assertThat(bounds.width().toFloat() / bounds.height()).isWithin(.002f).of(.6f)
        assertThat(controller.getManualRotationBounds(task)).isNull()
    }

    @Test
    fun entry_afterRestartUsesCurrentDesktopAspectInsteadOfSavedRotation() {
        val store = FloatingWindowGeometryStore({ null }, java.util.concurrent.Executor { it.run() })
        val first = PinnedLayerPresentationController(context, displayController, desktopState, store)
        val task = createTaskInfo(Rect(0, 0, 600, 1000)).apply {
            isFloatingWindow = true
            baseActivity = android.content.ComponentName("test.entry", "Main")
            minWidth = 1
            minHeight = 1
        }
        first.setManualOrientation(task, false, Rect(100, 200, 700, 1200))
        val restarted = PinnedLayerPresentationController(context, displayController, desktopState, store)
        task.isFloatingWindow = false
        task.configuration.windowConfiguration.setWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM)
        task.configuration.windowConfiguration.setBounds(Rect(0, 0, 1000, 600))
        task.configuration.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val bounds = requireNotNull(restarted.getPinEntryDestinationBounds(task))
        assertThat(bounds.width().toFloat() / bounds.height()).isWithin(.002f).of(1000f / 600)
    }

    @Test
    fun entry_usesLetterboxedAppFrameInsteadOfFullscreenTaskAspect() {
        val task = createTaskInfo(Rect(0, 0, 1920, 1080)).apply {
            configuration.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
            topActivityMainWindowFrame = Rect(600, 0, 1320, 1080)
            minWidth = 1
            minHeight = 1
        }
        val bounds = requireNotNull(controller.getPinEntryDestinationBounds(task))
        assertThat(bounds.width().toFloat() / bounds.height()).isWithin(.002f).of(2f / 3)
    }

    @Test
    fun entry_usesLetterboxBoundsWhenMainWindowMatchesActivityBounds() {
        val task = createTaskInfo(Rect(0, 0, 1920, 1080)).apply {
            configuration.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
            appCompatTaskInfo.topActivityLetterboxBounds = Rect(600, 0, 1320, 1080)
            minWidth = 1
            minHeight = 1
        }
        val bounds = requireNotNull(controller.getPinEntryDestinationBounds(task))
        assertThat(bounds.width().toFloat() / bounds.height()).isWithin(.002f).of(2f / 3)
    }

    @Test
    fun entry_usesFullscreenBoundsWhenOrientationMetadataIsUnavailable() {
        val task = createTaskInfo(Rect(0, 0, 1000, 600)).apply {
            configuration.windowConfiguration.setWindowingMode(WindowConfiguration.WINDOWING_MODE_FULLSCREEN)
            minWidth = 1
            minHeight = 1
        }
        val bounds = requireNotNull(controller.getPinEntryDestinationBounds(task))
        assertThat(bounds.width().toFloat() / bounds.height()).isWithin(.002f).of(1000f / 600)
    }

    private fun setRealPortraitDisplay() {
        val info = android.view.DisplayInfo().apply {
            logicalWidth = 1080
            logicalHeight = 1920
            logicalDensityDpi = 320
            rotation = android.view.Surface.ROTATION_0
        }
        val layout = DisplayLayout(info, context.resources, false, false)
        whenever(displayController.getDisplayLayout(Display.DEFAULT_DISPLAY)).thenReturn(layout)
    }

    @Test
    fun deviceRotation_unflippedWindowFollowsDeviceAndKeepsArea() {
        setRealPortraitDisplay()
        val initial = Rect(100, 200, 500, 1000)
        val task = createTaskInfo(initial).apply { isFloatingWindow = true }
        val rotated = requireNotNull(controller.getRotationBounds(task,
            android.view.Surface.ROTATION_0, android.view.Surface.ROTATION_90))
        assertThat(rotated.width()).isGreaterThan(rotated.height())
        assertThat(kotlin.math.abs(rotated.width() * rotated.height() - 320000)).isLessThan(1500)
        assertThat(Rect(0, 0, 1920, 1080).contains(rotated)).isTrue()
    }

    @Test
    fun deviceRotation_manualPortraitChoiceStaysPortrait() {
        setRealPortraitDisplay()
        val initial = Rect(100, 200, 500, 1000)
        val task = createTaskInfo(initial).apply {
            isFloatingWindow = true
            baseActivity = android.content.ComponentName("test.rotation", "Main")
        }
        controller.setManualOrientation(task, false, initial)
        val rotated = requireNotNull(controller.getRotationBounds(task,
            android.view.Surface.ROTATION_0, android.view.Surface.ROTATION_90))
        assertThat(rotated.width()).isLessThan(rotated.height())
        assertThat(kotlin.math.abs(rotated.width() * rotated.height() - 320000)).isLessThan(1500)
        assertThat(Rect(0, 0, 1920, 1080).contains(rotated)).isTrue()
    }

    @Test
    fun getPinEntryDestinationBounds_noDisplayLayout_returnsNull() {
        whenever(displayController.getDisplayLayout(Display.DEFAULT_DISPLAY)).thenReturn(null)
        val task = createTaskInfo(Rect(0, 0, 500, 600))

        val bounds = controller.getPinEntryDestinationBounds(task)

        assertThat(bounds).isNull()
    }

    @Test
    fun getPinEntryDestinationBounds_noContext_returnsNull() {
        whenever(displayController.getDisplayContext(Display.DEFAULT_DISPLAY)).thenReturn(null)
        val task = createTaskInfo(Rect(0, 0, 500, 600))

        val bounds = controller.getPinEntryDestinationBounds(task)

        assertThat(bounds).isNull()
    }

    @Test
    fun getPinEntryDestinationBounds_emptySourceBounds_usesDisplayDefault() {
        val task = createTaskInfo(Rect(0, 0, 0, 100)) // Zero width

        val bounds = controller.getPinEntryDestinationBounds(task)

        assertThat(bounds).isNotNull()
    }

    @Test
    fun isTaskSupportedForPinning_supported() {
        val task =
            TestRunningTaskInfoBuilder()
                .setWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM)
                .build()

        assertThat(controller.isTaskSupportedForPinning(task)).isTrue()
    }

    @Test
    fun isTaskSupportedForPinning_fullscreen_returnsTrue() {
        val task =
            TestRunningTaskInfoBuilder()
                .setWindowingMode(WindowConfiguration.WINDOWING_MODE_FULLSCREEN)
                .build()

        assertThat(controller.isTaskSupportedForPinning(task)).isTrue()
    }

    @Test
    fun isTaskSupportedForPinning_desktopModeNotSupported_returnsFalse() {
        whenever(desktopState.isDesktopModeSupportedOnDisplay(anyInt())).thenReturn(false)
        val task =
            TestRunningTaskInfoBuilder()
                .setWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM)
                .build()

        assertThat(controller.isTaskSupportedForPinning(task)).isFalse()
    }

    @Test
    fun calculateNewTaskBounds_resize_fromAllQuadrants() {
        // Deliberately not using junit parameterized testing, for readability.
        data class TestCase(
            val name: String,
            val initialBounds: Rect,
            val requestedSize: Rect,
            val expectedBounds: Rect,
        )

        val testCases =
            listOf(
                // Shrinking cases
                TestCase(
                    name = "shrink, top-left quadrant",
                    // Shrink, closer edges (right, bottom) move.
                    initialBounds = Rect(0, 100, 500, 700),
                    requestedSize = Rect(0, 0, 450, 550),
                    expectedBounds = Rect(0, 100, 450, 650),
                ),
                TestCase(
                    name = "shrink, top-right quadrant",
                    // Shrink, closer edges (left, bottom) move.
                    initialBounds = Rect(580, 100, 1080, 700),
                    requestedSize = Rect(0, 0, 450, 550),
                    expectedBounds = Rect(630, 100, 1080, 650),
                ),
                TestCase(
                    name = "shrink, bottom-left quadrant",
                    // Shrink, closer edges (right, top) move.
                    initialBounds = Rect(0, 1000, 500, 1600),
                    requestedSize = Rect(0, 0, 450, 550),
                    expectedBounds = Rect(0, 1050, 450, 1600),
                ),
                TestCase(
                    name = "shrink, bottom-right quadrant",
                    // Shrink, closer edges (left, top) move.
                    initialBounds = Rect(580, 1000, 1080, 1600),
                    requestedSize = Rect(0, 0, 450, 550),
                    expectedBounds = Rect(630, 1050, 1080, 1600),
                ),
                // Expanding cases
                TestCase(
                    name = "expand, top-left quadrant",
                    // Expand, farther edges (left, top) move.
                    initialBounds = Rect(0, 100, 500, 700),
                    requestedSize = Rect(0, 0, 600, 700),
                    expectedBounds = Rect(0, 100, 600, 800),
                ),
                TestCase(
                    name = "expand, top-right quadrant",
                    // Expand, farther edges (right, top) move.
                    initialBounds = Rect(580, 100, 1080, 700),
                    requestedSize = Rect(0, 0, 600, 700),
                    expectedBounds = Rect(480, 100, 1080, 800),
                ),
                TestCase(
                    name = "expand, bottom-left quadrant",
                    // Expand, farther edges (left, bottom) move.
                    initialBounds = Rect(0, 1000, 500, 1600),
                    requestedSize = Rect(0, 0, 600, 700),
                    expectedBounds = Rect(0, 1000, 600, 1700),
                ),
                TestCase(
                    name = "expand, bottom-right quadrant",
                    // Expand, farther edges (right, bottom) move.
                    initialBounds = Rect(580, 1000, 1080, 1600),
                    requestedSize = Rect(0, 0, 600, 700),
                    expectedBounds = Rect(480, 1000, 1080, 1700),
                ),
            )

        testCases.forEachIndexed { i, testCase ->
            val task = createTaskInfo(testCase.initialBounds, taskId = i)
            val requestedLocation = requestLocation(testCase.requestedSize)

            val newBounds = controller.calculateNewTaskBounds(task, requestedLocation)

            assertWithMessage(testCase.name).that(newBounds).isEqualTo(testCase.expectedBounds)
        }
    }

    @Test
    fun calculateNewTaskBounds_resizeTooSmall_scalesUp() {
        val task = createTaskInfo(Rect(100, 200, 600, 800)) // 500x600
        val requestedBounds = Rect(0, 0, 200, 300)
        val requestedLocation = requestLocation(requestedBounds)

        val newBounds = controller.calculateNewTaskBounds(task, requestedLocation)

        // minSize is 440px. scale = max(440/200, 440/300) = 2.2 -> new size: 440x660.
        // Width shrinks (closer edge, right, moves), height expands (farther edge, top, moves).
        val expected = Rect(100, 140, 540, 800)
        assertThat(newBounds).isEqualTo(expected)
    }

    @Test
    fun calculateNewTaskBounds_resizeTooLarge_scalesDown() {
        val task = createTaskInfo(Rect(100, 200, 600, 800)) // 500x600
        val requestedBounds = Rect(0, 0, 1000, 1500)
        val requestedLocation = requestLocation(requestedBounds)

        val newBounds = controller.calculateNewTaskBounds(task, requestedLocation)

        // maxWidth=756, maxHeight=1344. scale=min(756/1000, 1344/1500) = 0.756 -> 756x1134.
        // Width and height expand, so farther edges (left, top) move. Then ensureNotOffscreen.
        val expected = Rect(0, 100, 756, 1234)
        assertThat(newBounds).isEqualTo(expected)
    }

    @Test
    fun calculateNewTaskBounds_resizeCausesOffscreen_movesOnscreen() {
        data class TestCase(
            val name: String,
            val initialBounds: Rect,
            val requestedSize: Rect,
            val expectedBounds: Rect,
        )
        val testCases =
            listOf(
                TestCase(
                    "resize wider, goes offscreen right",
                    Rect(500, 200, 1000, 800),
                    Rect(0, 0, 600, 600), // right edge would be 1100
                    Rect(480, 200, 1080, 800), // 1080 - 0 (inset) = 1080
                ),
                TestCase(
                    "resize taller, goes offscreen bottom",
                    Rect(100, 1300, 600, 1800),
                    Rect(0, 0, 500, 600), // bottom edge would be 1900
                    Rect(100, 1270, 600, 1870), // 1920 - 50 (inset) = 1870
                ),
                TestCase(
                    "resize wider, goes offscreen left",
                    Rect(50, 200, 550, 800),
                    Rect(0, 0, 600, 600), // left edge would be -50
                    Rect(0, 200, 600, 800),
                ),
                TestCase(
                    "resize taller, goes offscreen top",
                    Rect(100, 150, 600, 750),
                    Rect(0, 0, 500, 700), // top edge would be 50
                    Rect(100, 100, 600, 800), // top inset = 100
                ),
            )

        testCases.forEachIndexed { i, testCase ->
            val task = createTaskInfo(testCase.initialBounds, taskId = i)
            val requestedLocation = requestLocation(testCase.requestedSize)

            val newBounds = controller.calculateNewTaskBounds(task, requestedLocation)

            assertWithMessage(testCase.name).that(newBounds).isEqualTo(testCase.expectedBounds)
        }
    }

    @Test
    fun calculateNewTaskBounds_multipleResizes_maintainsOriginalBoundsForResize() {
        val task = createTaskInfo(Rect(100, 200, 600, 800)) // 500x600

        // First resize
        val requestedLocation1 = requestLocation(Rect(0, 0, 450, 550))
        val newBounds1 = controller.calculateNewTaskBounds(task, requestedLocation1)!!
        val expected1 = Rect(100, 200, 550, 750)
        assertThat(newBounds1).isEqualTo(expected1)

        // Create a new task info with the updated bounds for the second call
        val taskAfterResize = createTaskInfo(newBounds1, taskId = task.taskId)

        // Second resize. The resize should still be based on the original bounds.
        val requestedLocation2 = requestLocation(Rect(0, 0, 650, 850))
        val newBounds2 = controller.calculateNewTaskBounds(taskAfterResize, requestedLocation2)
        val expected2 = Rect(0, 100, 650, 950)
        assertThat(newBounds2).isEqualTo(expected2)
    }

    @Test
    fun calculateNewTaskBounds_multipleResizes_resizesBackToOriginalSize() {
        val task = createTaskInfo(Rect(100, 200, 600, 800)) // 500x600

        // First resize - expand offscreen
        val requestedLocation1 = requestLocation(Rect(0, 0, 650, 800))
        val newBounds1 = controller.calculateNewTaskBounds(task, requestedLocation1)!!
        val expected1 = Rect(0, 100, 650, 900)
        assertThat(newBounds1).isEqualTo(expected1)

        // Create a new task info with the updated bounds for the second call
        val taskAfterResize = createTaskInfo(newBounds1, taskId = task.taskId)

        // Second resize. The resize should still be based on the original bounds.
        val requestedLocation2 = requestLocation(Rect(0, 0, 500, 600))
        val newBounds2 = controller.calculateNewTaskBounds(taskAfterResize, requestedLocation2)
        val expected2 = Rect(100, 200, 600, 800)
        assertThat(newBounds2).isEqualTo(expected2)
    }

    @Test
    fun calculateNewTaskBounds_externalBoundsChange_resetsAnchor() {
        val task = createTaskInfo(Rect(100, 200, 600, 800))

        // First resize
        val requestedLocation1 = requestLocation(Rect(0, 0, 450, 550))
        controller.calculateNewTaskBounds(task, requestedLocation1)

        // Now, simulate an external change (e.g., user moved the window)
        val externallyMovedBounds = Rect(300, 400, 750, 950) // 450x550
        val taskAfterMove = createTaskInfo(externallyMovedBounds, taskId = task.taskId)

        // Second resize. Should be based on the new position.
        val requestedLocation2 = requestLocation(Rect(0, 0, 500, 700))
        val newBounds = controller.calculateNewTaskBounds(taskAfterMove, requestedLocation2)

        // Expanding, farther edges (left, top) should move; dx=50, dy=150
        val expected = Rect(250, 250, 750, 950)
        assertThat(newBounds).isEqualTo(expected)
    }

    @Test
    fun calculateNewTaskBounds_differentDisplay_returnsCurrentBounds() {
        val task = createTaskInfo(Rect(100, 200, 600, 800))
        val requestedBounds = Rect(0, 0, 450, 550)
        val requestedLocation =
            requestLocation(requestedBounds, displayId = Display.DEFAULT_DISPLAY + 1)

        val newBounds = controller.calculateNewTaskBounds(task, requestedLocation)

        assertThat(newBounds).isEqualTo(task.configuration.windowConfiguration.bounds)
    }

    @Test
    fun isTaskSupportedForPinning_split_returnsTrue() {
        val task = TestRunningTaskInfoBuilder()
            .setWindowingMode(WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW).build()
        assertThat(controller.isTaskSupportedForPinning(task)).isTrue()
    }

    @Test
    fun isTaskSupportedForPinning_nonResizable_returnsFalse() {
        val task = TestRunningTaskInfoBuilder()
            .setWindowingMode(WindowConfiguration.WINDOWING_MODE_FULLSCREEN).build()
        task.isResizeable = false
        assertThat(controller.isTaskSupportedForPinning(task)).isFalse()
    }

    @Test
    fun isTaskSupportedForPinning_secondaryDisplay_returnsFalse() {
        val task = TestRunningTaskInfoBuilder().setDisplayId(1)
            .setWindowingMode(WindowConfiguration.WINDOWING_MODE_FULLSCREEN).build()
        assertThat(controller.isTaskSupportedForPinning(task)).isFalse()
    }

    private fun createTaskInfo(bounds: Rect, taskId: Int = 44): TaskInfo {
        return TestRunningTaskInfoBuilder()
            .setTaskId(taskId)
            .setDisplayId(Display.DEFAULT_DISPLAY)
            .setBounds(bounds)
            .build()
    }

    private fun requestLocation(bounds: Rect, displayId: Int = Display.DEFAULT_DISPLAY) =
        TransitionRequestInfo.RequestedLocation(displayId, bounds)
    @Test
    fun newFloatingEntry_usesCurrentDisplayOrientationEvenWithProvisionalBounds() {
        whenever(displayLayout.width()).thenReturn(1920)
        whenever(displayLayout.height()).thenReturn(1080)
        whenever(displayLayout.getStableBounds(any())).thenAnswer {
            it.getArgument<Rect>(0).set(0, 50, 1920, 1030)
        }
        val task = createTaskInfo(Rect(0, 0, 600, 900)).apply {
            isFloatingWindow = true
            floatingWindowScale = .75f
            topActivityRequestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val entry = requireNotNull(controller.getPinEntryDestinationBounds(task))
        assertThat(entry.width()).isGreaterThan(entry.height())
        assertThat(controller.entryScale(task)).isWithin(.001f).of(.75f)
    }

    @Test
    fun newFloatingEntry_respectsFixedPortraitOnLandscapeDisplay() {
        whenever(displayLayout.width()).thenReturn(1920)
        whenever(displayLayout.height()).thenReturn(1080)
        whenever(displayLayout.getStableBounds(any())).thenAnswer {
            it.getArgument<Rect>(0).set(0, 50, 1920, 1030)
        }
        val task = createTaskInfo(Rect(0, 0, 600, 900)).apply {
            isFloatingWindow = true
            topActivityRequestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val entry = requireNotNull(controller.getPinEntryDestinationBounds(task))
        assertThat(entry.height()).isGreaterThan(entry.width())
    }

    @Test
    fun rotateMenu_keepsScaleAndRecordsManualChoice() {
        val task = createTaskInfo(Rect(0, 0, 600, 900)).apply {
            isFloatingWindow = true
            floatingWindowScale = .75f
            topActivityRequestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val bounds = requireNotNull(controller.getManualRotationBounds(task))
        assertThat(bounds.width()).isGreaterThan(bounds.height())
        controller.setManualOrientation(task, true, bounds)
        val wct = android.window.WindowContainerTransaction()
        controller.applyPresentation(task, bounds, wct, task.floatingWindowScale)
        assertThat(wct.changes[task.token.asBinder()]!!.floatingWindowScale).isEqualTo(.75f)
        task.topActivityRequestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        assertThat(controller.getManualRotationBounds(task)).isNotNull()
    }

    @Test
    fun manualRotation_keepsVisibleCenterAndClampsOnlyAtStableEdges() {
        listOf(android.graphics.Point(300, 500), android.graphics.Point(0, 100),
            android.graphics.Point(900, 1700)).forEach { position ->
            val task = createTaskInfo(Rect(0, 0, 600, 900)).apply {
                isFloatingWindow = true
                floatingWindowScale = .75f
                positionInParent = position
                floatingWindowPosition.set(position.x, position.y + 400)
            }
            val start = task.presentationBounds
            val end = requireNotNull(controller.getManualRotationBounds(task))
            val stable = Rect(0, 100, 1080, 1870)
            assertThat(stable.contains(end)).isTrue()
            val x = start.exactCenterX().coerceIn(
                stable.left + end.width() / 2f, stable.right - end.width() / 2f)
            val y = start.exactCenterY().coerceIn(
                stable.top + end.height() / 2f, stable.bottom - end.height() / 2f)
            assertThat(end.exactCenterX()).isWithin(.5f).of(x)
            assertThat(end.exactCenterY()).isWithin(.5f).of(y)
        }
    }

    @Test
    fun fixedApp_manualShapeOverridesRequestAndSurvivesDeviceRotation() {
        setRealPortraitDisplay()
        val task = createTaskInfo(Rect(100, 200, 700, 1100)).apply {
            isFloatingWindow = true
            floatingWindowScale = .75f
            topActivityRequestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val visible = requireNotNull(controller.getManualRotationBounds(task))
        assertThat(visible.width()).isGreaterThan(visible.height())
        controller.setManualOrientation(task, true, visible)
        val wct = android.window.WindowContainerTransaction()
        controller.applyPresentation(task, visible, wct, task.floatingWindowScale)
        val change = requireNotNull(wct.changes[task.token.asBinder()])
        assertThat(change.floatingWindowScale).isEqualTo(.75f)
        task.configuration.windowConfiguration.setBounds(change.configuration.windowConfiguration.bounds)
        task.floatingWindowPosition.set(visible.left, visible.top)
        val rotated = requireNotNull(controller.getRotationBounds(task,
            android.view.Surface.ROTATION_0, android.view.Surface.ROTATION_90))
        assertThat(rotated.width()).isGreaterThan(rotated.height())
    }

    @Test fun deskEntry_preservesBoundsWithinStableAndScaleLimits() {
        val task = createTaskInfo(Rect(100, 200, 700, 1000))
        controller.getPinEntryDestinationBounds(task)
        val requested = Rect(100, 200, 700, 1000)
        val bounds = requireNotNull(controller.getPreservedEntryBounds(task, requested))
        assertThat(bounds).isEqualTo(requested)
        val wct = android.window.WindowContainerTransaction()
        val scale = controller.entryScale(task)
        controller.applyPresentation(task, bounds, wct, scale)
        assertThat(scale).isAtMost(1f)
        val logical = wct.changes[task.token.asBinder()]!!.configuration.windowConfiguration.bounds
        assertThat(Rect(0, 100, 1080, 1870).contains(logical)).isTrue()
        assertThat(kotlin.math.abs(logical.width() * scale - requested.width())).isAtMost(1f)
    }

}
