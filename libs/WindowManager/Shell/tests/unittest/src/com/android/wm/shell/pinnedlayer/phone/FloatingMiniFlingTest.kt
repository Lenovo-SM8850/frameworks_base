// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FloatingMiniFlingTest {
    private val stable = Rect(100, 200, 1300, 2000)

    @Test fun diagonalFling_selectsEachCornerEvenBeyondDisplay() {
        for (corner in FloatingMiniState.Corner.values()) {
            val left = corner == FloatingMiniState.Corner.TOP_LEFT ||
                corner == FloatingMiniState.Corner.BOTTOM_LEFT
            val top = corner == FloatingMiniState.Corner.TOP_LEFT ||
                corner == FloatingMiniState.Corner.TOP_RIGHT
            assertThat(FloatingMiniFling.corner(stable, 1f, 700f, 1100f,
                if (left) -10000f else 10000f, if (top) -10000f else 10000f))
                .isEqualTo(corner)
        }
    }

    @Test fun speedThreshold_scalesWithDensityAndIncludesBoundary() {
        assertThat(FloatingMiniFling.corner(stable, 1f, 100f, 200f, -600f, -800f))
            .isEqualTo(FloatingMiniState.Corner.TOP_LEFT)
        assertThat(FloatingMiniFling.corner(stable, 1f, 100f, 200f, -599f, -800f)).isNull()
        assertThat(FloatingMiniFling.corner(stable, 2f, 100f, 200f, -600f, -800f)).isNull()
        assertThat(FloatingMiniFling.corner(stable, 2f, 100f, 200f, -1200f, -1600f))
            .isEqualTo(FloatingMiniState.Corner.TOP_LEFT)
    }

    @Test fun direction_requiresBothComponentsAndProjectedCornerZone() {
        assertThat(FloatingMiniFling.corner(stable, 1f, 100f, 200f, 0f, -2000f)).isNull()
        assertThat(FloatingMiniFling.corner(stable, 1f, 100f, 200f, -2000f, -500f)).isNull()
        assertThat(FloatingMiniFling.corner(stable, 1f, 1200f, 1900f, -1000f, -1000f)).isNull()
        assertThat(FloatingMiniFling.corner(stable, 1f, 100f, 200f, 1000f, 1000f)).isNull()
    }

    @Test fun quarterZones_includeBoundaryButRejectJustOutside() {
        // Both projections land exactly on the top-left quarter-zone boundary.
        val velocity = -1596f
        val distance = -velocity / (1.9f * 4.2f)
        val x = 400f + distance
        val y = 650f + distance
        assertThat(FloatingMiniFling.corner(stable, 1f, x, y, velocity, velocity))
            .isEqualTo(FloatingMiniState.Corner.TOP_LEFT)
        assertThat(FloatingMiniFling.corner(stable, 1f, x + 1f, y, velocity, velocity)).isNull()
        assertThat(FloatingMiniFling.corner(stable, 1f, x, y + 1f, velocity, velocity)).isNull()
    }

    @Test fun reflectedInputs_makeIdenticalDecisionsAtAllFourCorners() {
        val display = Rect(60, 100, 2560, 1500)
        for (density in listOf(1f, 2.25f)) {
            for (x in listOf(-500f, 60f, 685f, 1310f, 1935f, 2560f, 3000f)) {
                for (y in listOf(-500f, 100f, 450f, 800f, 1150f, 1500f, 2000f)) {
                    for (vx in listOf(0f, 700f, 1000f, 2000f, 8000f)) {
                        for (vy in listOf(0f, 700f, 1000f, 2000f, 8000f)) {
                            val accepted = FloatingMiniFling.corner(display, density,
                                x, y, -vx, -vy) != null
                            val rightX = display.left + display.right - x
                            val bottomY = display.top + display.bottom - y
                            assertThat(FloatingMiniFling.corner(display, density,
                                rightX, y, vx, -vy)).isEqualTo(
                                if (accepted) FloatingMiniState.Corner.TOP_RIGHT else null)
                            assertThat(FloatingMiniFling.corner(display, density,
                                x, bottomY, -vx, vy)).isEqualTo(
                                if (accepted) FloatingMiniState.Corner.BOTTOM_LEFT else null)
                            assertThat(FloatingMiniFling.corner(display, density,
                                rightX, bottomY, vx, vy)).isEqualTo(
                                if (accepted) FloatingMiniState.Corner.BOTTOM_RIGHT else null)
                        }
                    }
                }
            }
        }
    }

    @Test fun invalidPointerOrVelocityAndEmptyDisplay_doNotDock() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThat(FloatingMiniFling.corner(stable, 1f, invalid, 200f, -2000f, -2000f)).isNull()
            assertThat(FloatingMiniFling.corner(stable, 1f, 100f, invalid, -2000f, -2000f)).isNull()
            assertThat(FloatingMiniFling.corner(stable, 1f, 100f, 200f, invalid, -2000f)).isNull()
            assertThat(FloatingMiniFling.corner(stable, 1f, 100f, 200f, -2000f, invalid)).isNull()
        }
        assertThat(FloatingMiniFling.corner(Rect(), 1f, 100f, 200f, -2000f, -2000f)).isNull()
    }
}
