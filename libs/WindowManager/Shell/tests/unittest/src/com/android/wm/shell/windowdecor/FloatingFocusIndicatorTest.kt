// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.R
import com.android.wm.shell.ShellTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FloatingFocusIndicatorTest : ShellTestCase() {
    private fun task(focused: Boolean) = RunningTaskInfo().apply {
        isFloatingWindow = true
        isVisible = true
        isFocused = focused
        floatingWindowScale = .75f
        configuration.windowConfiguration.setBounds(Rect(0, 0, 800, 1200))
    }

    @Test fun organizerFocusSelectsOnlyTheBackTarget() {
        val first = task(true)
        val second = task(false)
        assertTrue(FloatingFocusIndicator.select(first, false)!!.focused)
        assertFalse(FloatingFocusIndicator.select(second, false)!!.focused)
        first.isFocused = false
        second.isFocused = true
        assertFalse(FloatingFocusIndicator.select(first, false)!!.focused)
        assertTrue(FloatingFocusIndicator.select(second, false)!!.focused)
        second.isFocused = false
        assertFalse(FloatingFocusIndicator.select(first, false)!!.focused)
        assertFalse(FloatingFocusIndicator.select(second, false)!!.focused)
    }

    @Test fun miniInvisibleAndNormalTasksHaveNoCue() {
        val task = task(true)
        assertNull(FloatingFocusIndicator.select(task, true))
        task.isVisible = false
        assertNull(FloatingFocusIndicator.select(task, false))
        task.isVisible = true
        task.isFloatingWindow = false
        assertNull(FloatingFocusIndicator.select(task, false))
    }

    @Test fun focusOnlyAnimatesWhileInitialRestoreAndRotationApplyImmediately() {
        val before = FloatingFocusIndicator.select(task(false), false)!!
        val after = FloatingFocusIndicator.select(task(true), false)!!
        assertFalse(FloatingFocusIndicator.canAnimate(null, after))
        assertTrue(FloatingFocusIndicator.canAnimate(before, after))
        assertFalse(FloatingFocusIndicator.canAnimate(before,
            after.copy(bounds = Rect(0, 0, 900, 600))))
        assertFalse(FloatingFocusIndicator.canAnimate(before, after.copy(scale = .5f)))
    }

    @Test fun floatingValuesPreserveFocusedOpacityAndUseDedicatedShadows() {
        assertEquals(1f, FloatingFocusIndicator.handleAlpha(true), 0f)
        assertEquals(.4f, FloatingFocusIndicator.handleAlpha(false), 0f)
        assertEquals(R.dimen.floating_decor_shadow_focused_radius,
            FloatingFocusIndicator.shadowRadiusId(true))
        assertEquals(R.dimen.floating_decor_shadow_unfocused_radius,
            FloatingFocusIndicator.shadowRadiusId(false))
    }
}
