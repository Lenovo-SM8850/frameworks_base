// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FloatingReleaseInertiaTest {
    private val limits = FloatingReleaseInertia.Limits(0f, 0f, 150f, 150f)

    @Test
    fun estimateEnd_travelsVelocityOverFrictionScalar() {
        // 1260 / (3 * 4.2) = 100
        assertEquals(200f, FloatingReleaseInertia.estimateEnd(100f, 1260f, 0f, 1000f), .01f)
        assertEquals(0f, FloatingReleaseInertia.estimateEnd(100f, -1260f, 0f, 1000f), .01f)
        // 2000 px/s drifts about 160 px
        assertEquals(158.7f, FloatingReleaseInertia.estimateEnd(0f, 2000f, 0f, 1000f), .1f)
    }

    @Test
    fun estimateEnd_clampsToLimits() {
        assertEquals(150f, FloatingReleaseInertia.estimateEnd(100f, 5000f, 0f, 150f), 0f)
        assertEquals(20f, FloatingReleaseInertia.estimateEnd(100f, -5000f, 20f, 150f), 0f)
    }

    @Test
    fun isFast_needsOneAxisAtMinimum() {
        assertTrue(FloatingReleaseInertia.isFast(300f, 0f, 250f))
        assertTrue(FloatingReleaseInertia.isFast(0f, -300f, 250f))
        assertFalse(FloatingReleaseInertia.isFast(200f, -200f, 250f))
        assertFalse(FloatingReleaseInertia.isFast(Float.NaN, 900f, 250f))
    }

    @Test
    fun minVelocityPx_scalesWithDensity() {
        assertEquals(500f, FloatingReleaseInertia.minVelocityPx(2f), 0f)
    }

    @Test
    fun start_belowThresholdDoesNotAnimate() {
        var frames = 0
        var ends = 0
        val run = FloatingReleaseInertia.start(FloatingReleaseInertia.Position(10f, 10f),
            100f, -100f, FloatingReleaseInertia.minVelocityPx(2f), limits,
            { frames++ }, { ends++ })
        assertNull(run)
        assertEquals(0, frames)
        assertEquals(0, ends)
    }
}
