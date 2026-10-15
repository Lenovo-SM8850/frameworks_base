// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.content.pm.ActivityInfo
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.windowdecor.DragPositioningCallback.*
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FloatingWindowGeometryTest {
    @Test fun scaledEntry_defaultsAndClampsBothAxes() {
        val visible = Rect(100, 200, 500, 1000)
        val stable = Rect(0, 80, 1200, 1840)
        assertThat(FloatingWindowGeometry.entryScale(visible, stable))
            .isWithin(.0001f).of(1f / 1.3f)
        assertThat(FloatingWindowGeometry.logicalBounds(visible, stable, 1f / 1.3f))
            .isEqualTo(Rect(100, 200, 620, 1240))
        assertThat(FloatingWindowGeometry.entryScale(Rect(0, 0, 1000, 1000), stable))
            .isWithin(.0001f).of(1f / 1.2f)
        assertThat(FloatingWindowGeometry.entryScale(stable, stable)).isEqualTo(1f)
    }

    @Test fun scaledCorner_preservesLogicalBoundsThroughoutScaleRange() {
        val logical = Rect(100, 100, 900, 1300)
        val start = Rect(100, 100, 700, 1000)
        val stable = Rect(0, 0, 2400, 2400)
        for ((dx, dy, scale) in listOf(Triple(-200f, -300f, .5f),
            Triple(0f, 0f, .75f), Triple(200f, 300f, 1f))) {
            val result = FloatingWindowGeometry.scaledCorner(start, logical, stable,
                CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM, dx, dy, 200, 300)
            assertThat(result.logical).isEqualTo(logical)
            assertThat(result.commitsBounds).isFalse()
            assertThat(result.scale).isWithin(.001f).of(scale)
        }
    }

    @Test fun scaledCorner_commitsNewLogicalSizeOnlyBeyondLimits() {
        val logical = Rect(100, 100, 900, 1300)
        val start = Rect(100, 100, 700, 1000)
        val stable = Rect(0, 0, 2400, 2400)
        for ((dx, dy, scale) in listOf(Triple(-300f, -450f, .5f),
            Triple(300f, 450f, 1f))) {
            val result = FloatingWindowGeometry.scaledCorner(start, logical, stable,
                CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM, dx, dy, 200, 300)
            assertThat(result.commitsBounds).isTrue()
            assertThat(result.scale).isEqualTo(scale)
            assertThat(result.logical).isNotEqualTo(logical)
            assertThat(stable.contains(result.logical)).isTrue()
            assertThat(abs(result.logical.width() * 1.5f - result.logical.height())).isLessThan(2f)
        }
    }

    @Test fun scaledAspect_keepsScaleAndLogicalMinimumsWhenRefitting() {
        val stable = Rect(0, 50, 1200, 1870)
        val logical = Rect(100, 100, 900, 1300)
        val visible = Rect(100, 100, 700, 1000)
        val flipped = requireNotNull(FloatingWindowGeometry.scaledAspectBounds(
            visible, logical, stable, 1.6f, .75f, 300, 400))
        val fitted = FloatingWindowGeometry.logicalBounds(flipped, stable, .75f)
        assertThat(stable.contains(fitted)).isTrue()
        assertThat(fitted.width()).isAtLeast(300)
        assertThat(fitted.height()).isAtLeast(400)
        assertThat(abs(fitted.width().toFloat() / fitted.height() - 1.6f)).isLessThan(.005f)
        assertThat(FloatingWindowGeometry.scaledAspectBounds(visible, logical, stable,
            1.6f, .75f, 600, 1000)).isNull()
    }

    @Test fun hysteresis_crossesOnlyOuterThresholds() {
        assertThat(FloatingWindowGeometry.selectLandscape(false, 1.08f, false)).isFalse()
        assertThat(FloatingWindowGeometry.selectLandscape(false, 1.081f, false)).isTrue()
        assertThat(FloatingWindowGeometry.selectLandscape(true, .92f, false)).isTrue()
        assertThat(FloatingWindowGeometry.selectLandscape(true, .919f, false)).isFalse()
    }

    @Test fun hysteresis_keepsTargetThroughSquareAndJitter() {
        var landscape = false
        for (ratio in listOf(.8f, .99f, 1.02f, 1.07f, 1.01f)) {
            landscape = FloatingWindowGeometry.selectLandscape(landscape, ratio, false)
            assertThat(landscape).isFalse()
        }
        landscape = FloatingWindowGeometry.selectLandscape(landscape, 1.2f, false)
        for (ratio in listOf(1.03f, .99f, .93f, 1.01f)) {
            landscape = FloatingWindowGeometry.selectLandscape(landscape, ratio, false)
            assertThat(landscape).isTrue()
        }
    }

    @Test fun fixedOrientation_neverFlips() {
        assertThat(FloatingWindowGeometry.selectLandscape(false, 4f, true)).isFalse()
        assertThat(FloatingWindowGeometry.selectLandscape(true, .2f, true)).isTrue()
        val portrait = Rect(100, 100, 500, 900)
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT)) {
            assertThat(FloatingWindowGeometry.fixedLandscape(orientation, portrait)).isFalse()
        }
        assertThat(FloatingWindowGeometry.fixedLandscape(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, portrait)).isTrue()
        assertThat(FloatingWindowGeometry.fixedLandscape(
            ActivityInfo.SCREEN_ORIENTATION_LOCKED, portrait)).isFalse()
        assertThat(FloatingWindowGeometry.fixedLandscape(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, portrait)).isNull()
    }

    @Test fun corners_preserveAspectAndOppositeCornerInAllQuadrants() {
        val start = Rect(500, 500, 900, 1100)
        val stable = Rect(0, 50, 2000, 2000)
        for (ctrl in listOf(CTRL_TYPE_LEFT or CTRL_TYPE_TOP, CTRL_TYPE_RIGHT or CTRL_TYPE_TOP,
            CTRL_TYPE_LEFT or CTRL_TYPE_BOTTOM, CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM)) {
            val left = ctrl and CTRL_TYPE_LEFT != 0
            val top = ctrl and CTRL_TYPE_TOP != 0
            val result = FloatingWindowGeometry.corner(start, stable, ctrl,
                if (left) -100f else 100f, if (top) -150f else 150f, 100, 100)
            assertThat(result.width()).isEqualTo(500)
            assertThat(result.height()).isEqualTo(750)
            assertThat(if (left) result.right else result.left)
                .isEqualTo(if (left) start.right else start.left)
            assertThat(if (top) result.bottom else result.top)
                .isEqualTo(if (top) start.bottom else start.top)
            assertThat(stable.contains(result)).isTrue()
        }
    }

    @Test fun corner_clampsScaleForMinimumAndStableBounds() {
        val start = Rect(100, 100, 500, 900)
        val stable = Rect(0, 0, 1000, 1200)
        val ctrl = CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM
        val small = FloatingWindowGeometry.corner(start, stable, ctrl, -2000f, -2000f, 300, 400)
        assertThat(small.width()).isEqualTo(300)
        assertThat(small.height()).isEqualTo(600)
        val large = FloatingWindowGeometry.corner(start, stable, ctrl, 3000f, 3000f, 300, 400)
        assertThat(stable.contains(large)).isTrue()
        assertThat(large.width()).isEqualTo(550)
        assertThat(large.height()).isEqualTo(1100)
    }

    @Test fun edgeOutline_followsDraggedEdgeAndKeepsOtherEdges() {
        val start = Rect(100, 100, 500, 900)
        assertThat(FloatingWindowGeometry.outline(start, CTRL_TYPE_RIGHT, 550f, 80f))
            .isEqualTo(Rect(100, 100, 1050, 900))
        assertThat(FloatingWindowGeometry.outline(start, CTRL_TYPE_BOTTOM, 20f, -500f))
            .isEqualTo(Rect(100, 100, 500, 400))
    }

    @Test fun sideDrag_selectsUnclampedTargetAndFitsAllDisplayOrientations() {
        for ((short, long) in listOf(1200 to 1920, 1904 to 3040, 1600 to 2560)) {
            for ((width, height) in listOf(short to long, long to short)) {
                val stable = Rect(0, 80, width, height - 80)
                val h = (stable.height() * .85f).toInt()
                val w = (h * short.toFloat() / long).toInt()
                val start = Rect((width - w) / 2, stable.top + (stable.height() - h) / 2,
                    (width + w) / 2, stable.top + (stable.height() - h) / 2 + h)
                for (edge in listOf(CTRL_TYPE_LEFT, CTRL_TYPE_RIGHT)) {
                    val delta = h * 1.10f - start.width()
                    val outline = FloatingWindowGeometry.outline(start, edge,
                        if (edge == CTRL_TYPE_LEFT) -delta else delta, 0f)
                    assertThat(FloatingWindowGeometry.selectLandscape(false,
                        outline.width().toFloat() / outline.height(), false)).isTrue()
                    if (width < height) {
                        assertThat(stable.contains(outline)).isFalse()
                    }
                    val committed = requireNotNull(FloatingWindowGeometry.aspectBounds(
                        start, stable, FloatingWindowGeometry.displayAspect(width, height, true),
                        100, 100))
                    assertThat(stable.contains(committed)).isTrue()
                    assertThat(committed.width()).isGreaterThan(committed.height())
                    assertThat(committed.width()).isAtLeast(100)
                    assertThat(committed.height()).isAtLeast(100)
                    val portraitOutline = FloatingWindowGeometry.outline(committed, edge,
                        (committed.height() * .90f - committed.width()) *
                            (if (edge == CTRL_TYPE_LEFT) -1 else 1), 0f)
                    assertThat(FloatingWindowGeometry.selectLandscape(true,
                        portraitOutline.width().toFloat() / portraitOutline.height(), false)).isFalse()
                }
            }
        }
    }

    @Test fun aspectFlip_rejectsMinimumSizeThatCannotFitTarget() {
        val stable = Rect(0, 0, 1200, 1920)
        val start = Rect(100, 100, 700, 1500)
        assertThat(FloatingWindowGeometry.aspectBounds(start, stable, 1.6f, 600, 1000)).isNull()
        val fitting = requireNotNull(FloatingWindowGeometry.aspectBounds(
            start, stable, 1.6f, 600, 750))
        assertThat(fitting.width()).isAtLeast(600)
        assertThat(fitting.height()).isAtLeast(750)
        assertThat(stable.contains(fitting)).isTrue()
    }

    @Test fun aspectFlip_keepsAreaAndUsesDisplayAspect() {
        val start = Rect(100, 100, 500, 900)
        val stable = Rect(0, 0, 2000, 2000)
        val aspect = FloatingWindowGeometry.displayAspect(1200, 1920, true)
        val result = requireNotNull(FloatingWindowGeometry.aspectBounds(start, stable, aspect, 100, 100))
        assertThat(abs(result.width().toFloat() / result.height() - 1.6f)).isLessThan(.005f)
        assertThat(abs(result.width() * result.height() - start.width() * start.height()))
            .isLessThan(1500)
        assertThat(stable.contains(result)).isTrue()
    }
}
