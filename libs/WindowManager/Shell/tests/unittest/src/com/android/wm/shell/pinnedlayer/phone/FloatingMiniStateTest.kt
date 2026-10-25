// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_BOTTOM
import com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_RIGHT
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FloatingMiniStateTest {
    private val stable = Rect(0, 100, 1200, 1900)
    private val logical = Rect(100, 200, 900, 1400)

    @Test fun cornerThreshold_requiresBothEdgesAndIncludesBoundary() {
        assertThat(FloatingMiniState.corner(Rect(48, 148, 648, 1048), stable, 48))
            .isEqualTo(FloatingMiniState.Corner.TOP_LEFT)
        assertThat(FloatingMiniState.corner(Rect(49, 148, 649, 1048), stable, 48)).isNull()
        assertThat(FloatingMiniState.corner(Rect(0, 600, 600, 1500), stable, 48)).isNull()
        assertThat(FloatingMiniState.corner(Rect(552, 952, 1152, 1852), stable, 48))
            .isEqualTo(FloatingMiniState.Corner.BOTTOM_RIGHT)
    }

    @Test fun miniSize_targetsDisplayShortSideAndSnapsInsideMargin() {
        val scale = FloatingMiniState.scale(logical, stable, 1200, 8)
        assertThat(scale).isWithin(.0001f).of(.45f)
        val sized = FloatingMiniState.sized(logical, scale, 100, 200)
        assertThat(sized.width()).isEqualTo(360)
        assertThat(sized.height()).isEqualTo(540)
        assertThat(FloatingMiniState.snap(sized, stable, FloatingMiniState.Corner.TOP_LEFT, 8))
            .isEqualTo(Rect(8, 108, 368, 648))
        assertThat(FloatingMiniState.snap(sized, stable, FloatingMiniState.Corner.BOTTOM_RIGHT, 8))
            .isEqualTo(Rect(832, 1352, 1192, 1892))
        assertThat(logical).isEqualTo(Rect(100, 200, 900, 1400))
    }

    @Test fun miniSize_extremeAspectFitsStableArea() {
        val tall = Rect(0, 0, 300, 3000)
        val scale = FloatingMiniState.scale(tall, stable, 1200, 8)
        val sized = FloatingMiniState.sized(tall, scale, 8, 108)
        assertThat(stable.contains(sized)).isTrue()
        assertThat(sized.height()).isEqualTo(1784)
    }

    @Test fun dynamicResizeFloor_keepsLogicalBoundsAtLimitAndCommitsPastLimit() {
        val start = Rect(200, 300, 800, 1200)
        val scale = FloatingMiniState.scale(logical, stable, 1200, 8)
        val atLimit = FloatingWindowGeometry.scaledCorner(start, logical, stable,
            CTRL_TYPE_BOTTOM or CTRL_TYPE_RIGHT, -240f, -360f, 10, 10, minimumScale = scale)
        assertThat(atLimit.scale).isWithin(.001f).of(.45f)
        assertThat(atLimit.logical).isEqualTo(logical)
        assertThat(atLimit.commitsBounds).isFalse()
        val beyond = FloatingWindowGeometry.scaledCorner(start, logical, stable,
            CTRL_TYPE_BOTTOM or CTRL_TYPE_RIGHT, -320f, -480f, 10, 10,
            minimumScale = scale, minimumVisibleShortSide = 360)
        assertThat(beyond.commitsBounds).isTrue()
        assertThat(beyond.atMinimum).isTrue()
        assertThat(beyond.visible.width()).isEqualTo(360)
        assertThat(beyond.visible.height()).isEqualTo(540)
        assertThat(beyond.scale).isGreaterThan(.45f)
    }
    @Test fun releaseBeyondCorner_stillSelectsCornerAndNormalReleaseClamps() {
        val area = Rect(0, 54, 2560, 1420)
        val released = Rect(2190, 27, 2849, 1082)
        assertThat(FloatingMiniState.corner(released, area, 48))
            .isEqualTo(FloatingMiniState.Corner.TOP_RIGHT)
        assertThat(FloatingMiniState.clamp(Rect(2300, 300, 2959, 1355), area))
            .isEqualTo(Rect(1901, 300, 2560, 1355))
    }
}
