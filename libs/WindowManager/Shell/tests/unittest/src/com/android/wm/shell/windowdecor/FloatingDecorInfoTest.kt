// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo
import android.graphics.Point
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FloatingDecorInfoTest {
    @Test fun bookkeepingSkipsRelayoutButPresentationVisibilityAndThemeDoNot() {
        val old = TestRunningTaskInfoBuilder().build().apply { isFloatingWindow = true }
        val next = RunningTaskInfo(old)
        next.lastActiveTime++
        next.isInteractive = !old.isInteractive
        assertThat(FloatingDecorInfo.canSkipRelayout(old, next)).isTrue()
        next.floatingWindowScale = .75f
        assertThat(FloatingDecorInfo.canSkipRelayout(old, next)).isFalse()
        next.floatingWindowScale = old.floatingWindowScale
        next.positionInParent = Point(20, 30)
        assertThat(FloatingDecorInfo.canSkipRelayout(old, next)).isFalse()
        next.positionInParent = old.positionInParent
        next.isVisible = !old.isVisible
        assertThat(FloatingDecorInfo.canSkipRelayout(old, next)).isFalse()
        next.isVisible = old.isVisible
        next.configuration.uiMode = old.configuration.uiMode xor 0x20
        assertThat(FloatingDecorInfo.canSkipRelayout(old, next)).isFalse()
    }
}
