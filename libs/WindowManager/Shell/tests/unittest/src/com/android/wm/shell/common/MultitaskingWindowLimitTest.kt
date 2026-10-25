// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.common

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MultitaskingWindowLimitTest {
    private fun pane(id: Int, parent: Int = 100) = RunningTaskInfo().apply {
        taskId = id
        parentTaskId = parent
        isVisible = true
        configuration.windowConfiguration.setActivityType(WindowConfiguration.ACTIVITY_TYPE_STANDARD)
        configuration.windowConfiguration.setWindowingMode(WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW)
    }

    @Test fun countsOnlyVisibleSplitLeavesAndExcludesFullscreenAndStageContainers() {
        val first = pane(1)
        val second = pane(2)
        val stage = pane(100, 200)
        val fullscreen = pane(3).apply {
            configuration.windowConfiguration.setWindowingMode(WindowConfiguration.WINDOWING_MODE_FULLSCREEN)
        }
        val floating = pane(4).apply { isFloatingWindow = true }
        val hidden = pane(5).apply { isVisible = false; isVisibleRequested = false }
        assertThat(MultitaskingWindowLimit.splitLeaves(listOf(first, second, first, stage,
            fullscreen, floating, hidden))).containsExactly(1, 2)
    }

    @Test fun backgroundSplitLeavesDoNotConsumeTheForegroundBudget() {
        val stage = pane(100, 200).apply { isVisible = false; isVisibleRequested = false }
        assertThat(MultitaskingWindowLimit.splitLeaves(listOf(pane(1), pane(2), stage))).isEmpty()
    }

    @Test fun threePanesAndTwoFloatingOrMiniWindowsFitAndSixthMinimizesOldest() {
        assertThat(MultitaskingWindowLimit.overflow(setOf(1, 2, 3), listOf(4, 5))).isEmpty()
        assertThat(MultitaskingWindowLimit.overflow(setOf(1, 2, 3), listOf(4, 5), 6))
            .containsExactly(4)
        assertThat(MultitaskingWindowLimit.overflow(setOf(1, 2, 3, 6), listOf(4, 5)))
            .containsExactly(4)
    }

    @Test fun groupRestoreMinimizesEnoughFloatingWindowsInExistingAgeOrder() {
        assertThat(MultitaskingWindowLimit.overflow(setOf(1, 2, 3), listOf(4, 5, 6, 7, 8)))
            .containsExactly(4, 5, 6).inOrder()
    }

    @Test fun conversionAndReplacementCountEachTaskOnceAndKeepIncomingWindow() {
        assertThat(MultitaskingWindowLimit.overflow(setOf(1, 2, 3), listOf(3, 4, 5), 3)).isEmpty()
        assertThat(MultitaskingWindowLimit.overflow(emptySet(), listOf(1, 2, 3, 4, 5), 6))
            .containsExactly(1)
        assertThat(MultitaskingWindowLimit.overflow(setOf(1, 2, 3), listOf(4, 5), 4)).isEmpty()
    }
}
