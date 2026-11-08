// SPDX-License-Identifier: Apache-2.0
package com.android.systemui.statusbar.quickactions.ui.compose

import com.android.systemui.statusbar.quickactions.ui.compose.popupDismissProgress
import org.junit.Assert.*
import org.junit.Test

class StatusBarPopupMotionTest {
    @Test fun dismissal_keepsFrontOpaqueUntilFortyPercentThenCrossfades() {
        assertEquals(0f, popupDismissProgress(0f, 100f), 0f)
        assertEquals(0f, popupDismissProgress(40f, 100f), 0f)
        assertEquals(0.5f, popupDismissProgress(70f, 100f), 0.0001f)
        assertEquals(1f, popupDismissProgress(100f, 100f), 0f)
    }

    @Test fun dismissal_isSymmetricClampedAndComplementary() {
        for (offset in 0..150) {
            val next = popupDismissProgress(offset.toFloat(), 100f)
            assertEquals(next, popupDismissProgress(-offset.toFloat(), 100f), 0f)
            assertTrue(next in 0f..1f)
            assertEquals(1f, (1f - next) + next, 0f)
        }
        assertEquals(0f, popupDismissProgress(0f, 0f), 0f)
    }
}
