// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import android.app.ActivityTaskManager
import android.content.res.Configuration
import android.testing.TestableContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.R
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.DisplayController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@SmallTest
@RunWith(AndroidJUnit4::class)
class SplitCapacityPolicyTest : ShellTestCase() {
    @Test
    fun capacity_requiresCapabilitySupportAndSize() {
        for (width in listOf(0, 599, 600, 711)) {
            for (enabled in listOf(false, true)) {
                for (supported in listOf(false, true)) {
                    val config = Configuration().apply { smallestScreenWidthDp = width }
                    assertEquals(
                        if (width >= 600 && enabled && supported) 4 else 2,
                        SplitCapacityPolicy.getSplitCapacity(config, enabled, supported),
                    )
                }
            }
        }
    }

    @Test
    fun capacity_usesSmallestWidthInBothRotations() {
        for (orientation in listOf(
            Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE
        )) {
            val config = Configuration().apply {
                this.orientation = orientation
                smallestScreenWidthDp = 600
                screenWidthDp = 300
                screenHeightDp = 400
            }
            assertEquals(4, SplitCapacityPolicy.getSplitCapacity(config, true, true))
            config.smallestScreenWidthDp = 599
            config.screenWidthDp = 1200
            assertEquals(2, SplitCapacityPolicy.getSplitCapacity(config, true, true))
        }
    }

    @Test
    fun capacity_shellCapabilityDefaultsToDisabled() {
        assertFalse(mContext.resources.getBoolean(R.bool.config_enableMultiAppSplit))
    }

    @Test
    fun capacity_readsRequestedDisplayAndUpdatesAfterConfigurationChanges() {
        val displays = mock(DisplayController::class.java)
        val small = displayContext(599, true)
        val large = displayContext(600, true)
        assumeTrue(ActivityTaskManager.supportsSplitScreenMultiWindow(large))
        `when`(displays.getDisplayContext(0)).thenReturn(small)
        `when`(displays.getDisplayContext(7)).thenReturn(large)
        assertEquals(2, SplitCapacityPolicy.getSplitCapacity(displays, 0))
        assertEquals(4, SplitCapacityPolicy.getSplitCapacity(displays, 7))
        `when`(displays.getDisplayContext(7)).thenReturn(small)
        assertEquals(2, SplitCapacityPolicy.getSplitCapacity(displays, 7))
        `when`(displays.getDisplayContext(7)).thenReturn(large)
        assertEquals(4, SplitCapacityPolicy.getSplitCapacity(displays, 7))
    }

    @Test
    fun capacity_disabledCapabilityKeepsTwoOnLargeDisplay() {
        val displays = mock(DisplayController::class.java)
        `when`(displays.getDisplayContext(7)).thenReturn(displayContext(711, false))
        assertEquals(2, SplitCapacityPolicy.getSplitCapacity(displays, 7))
    }

    @Test
    fun capacity_unknownDisplayFallsBackToTwo() {
        val displays = mock(DisplayController::class.java)
        assertEquals(2, SplitCapacityPolicy.getSplitCapacity(displays, 9))
    }

    private fun displayContext(smallestWidth: Int, enabled: Boolean): TestableContext {
        val config = Configuration().apply { smallestScreenWidthDp = smallestWidth }
        return TestableContext(mContext.createConfigurationContext(config)).also {
            it.orCreateTestableResources.addOverride(R.bool.config_enableMultiAppSplit, enabled)
        }
    }
}
