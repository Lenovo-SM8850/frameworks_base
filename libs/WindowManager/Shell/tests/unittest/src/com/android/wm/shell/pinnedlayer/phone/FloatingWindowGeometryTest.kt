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

    @Test fun fixedOrientation_neverFlips() {
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
    @Test fun allResizeEdges_useScaleRangeAndCommitAtLimits() {
        val logical = Rect(0, 0, 800, 1200)
        val start = Rect(500, 500, 1100, 1400)
        val stable = Rect(0, 0, 3000, 3000)
        for (ctrl in listOf(CTRL_TYPE_LEFT, CTRL_TYPE_RIGHT, CTRL_TYPE_TOP, CTRL_TYPE_BOTTOM)) {
            val horizontal = ctrl == CTRL_TYPE_LEFT || ctrl == CTRL_TYPE_RIGHT
            val sign = if (ctrl == CTRL_TYPE_LEFT || ctrl == CTRL_TYPE_TOP) -1 else 1
            for (target in listOf(.6f, .85f, 1.1f)) {
                val delta = (target / .75f - 1f) * (if (horizontal) 600 else 900) * sign
                val result = FloatingWindowGeometry.scaledCorner(start, logical, stable,
                    ctrl, if (horizontal) delta else 0f, if (horizontal) 0f else delta,
                    100, 100, minimumScale = .5f)
                assertThat(result.scale).isWithin(.002f).of(target.coerceIn(.5f, 1f))
                assertThat(result.commitsBounds).isEqualTo(target > 1f)
                assertThat(abs(result.visible.width() * 1.5f - result.visible.height())).isLessThan(2f)
            }
        }
    }

    @Test fun presets_landscapeChangesFromWideToFourThirdsAndLocksOrientation() {
        val logical = Rect(100, 100, 1700, 1000)
        val start = Rect(100, 100, 1300, 775)
        val stable = Rect(0, 0, 3000, 2400)
        val ctrl = CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM
        val changed = FloatingWindowGeometry.presetCorner(start, logical, stable, ctrl,
            -300f, 0f, 200, 200)
        assertThat(changed.logical.width().toFloat() / changed.logical.height())
            .isWithin(.003f).of(4f / 3f)
        assertThat(changed.commitsBounds).isTrue()
        val square = FloatingWindowGeometry.presetCorner(start, logical, stable, ctrl,
            -900f, 600f, 200, 200)
        assertThat(square.logical.width()).isGreaterThan(square.logical.height())
        assertThat(square.visible.left).isEqualTo(start.left)
        assertThat(square.visible.top).isEqualTo(start.top)
    }

    @Test fun presets_portraitCannotCrossSquareInAnyCorner() {
        val logical = Rect(0, 0, 900, 1600)
        val start = Rect(800, 800, 1475, 2000)
        val stable = Rect(0, 0, 3000, 3000)
        for (ctrl in listOf(CTRL_TYPE_LEFT or CTRL_TYPE_TOP, CTRL_TYPE_RIGHT or CTRL_TYPE_TOP,
            CTRL_TYPE_LEFT or CTRL_TYPE_BOTTOM, CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM)) {
            val left = ctrl and CTRL_TYPE_LEFT != 0
            val top = ctrl and CTRL_TYPE_TOP != 0
            val result = FloatingWindowGeometry.presetCorner(start, logical, stable, ctrl,
                if (left) -900f else 900f, if (top) 700f else -700f, 200, 300)
            assertThat(result.logical.height()).isGreaterThan(result.logical.width())
            assertThat(if (left) result.visible.right else result.visible.left)
                .isEqualTo(if (left) start.right else start.left)
            assertThat(if (top) result.visible.bottom else result.visible.top)
                .isEqualTo(if (top) start.bottom else start.top)
            assertThat(stable.contains(result.visible)).isTrue()
            assertThat(stable.contains(result.logical)).isTrue()
        }
    }

    @Test fun presets_diagonalDragOnlyResizesAndAxisDragReachesSquare() {
        val logical = Rect(100, 100, 1700, 1000)
        val start = Rect(100, 100, 1300, 775)
        val stable = Rect(0, 0, 3000, 2400)
        val ctrl = CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM
        val diagonal = FloatingWindowGeometry.presetCorner(start, logical, stable, ctrl,
            -300f, 250f, 200, 200)
        assertThat(diagonal.logical).isEqualTo(logical)
        assertThat(diagonal.commitsBounds).isFalse()
        val square = FloatingWindowGeometry.presetCorner(start, logical, stable, ctrl,
            -560f, 0f, 200, 200)
        assertThat(square.logical.width()).isGreaterThan(square.logical.height())
        assertThat(square.logical.width().toFloat() / square.logical.height())
            .isWithin(.01f).of(1f)
        // The first movement decides for the rest of the gesture.
        val snap = FloatingWindowGeometry.AspectSnapState(16f / 9f, 16f / 9f)
        FloatingWindowGeometry.presetCorner(start, logical, stable, ctrl, 0f, 30f, 200, 200,
            snap = snap, reshapeSlop = 20f)
        val reshaped = FloatingWindowGeometry.presetCorner(start, logical, stable, ctrl,
            -300f, 250f, 200, 200, snap = snap, reshapeSlop = 20f)
        assertThat(reshaped.commitsBounds).isTrue()
    }

    @Test fun presets_clampsAppAspectMinimumSizesAndDisplay() {
        val stable = Rect(0, 80, 2400, 2400)
        val ctrl = CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM
        for (landscape in listOf(true, false)) {
            val logical = if (landscape) Rect(0, 0, 1600, 900) else Rect(0, 0, 900, 1600)
            val start = Rect(100, 100, 100 + (logical.width() * .75f).toInt(),
                100 + (logical.height() * .75f).toInt())
            for ((dx, dy) in listOf(Pair(-3000f, 3000f), Pair(3000f, -3000f),
                Pair(3000f, 3000f), Pair(-3000f, -3000f))) {
                val result = FloatingWindowGeometry.presetCorner(start, logical, stable,
                    ctrl, dx, dy, 400, 300, minAspect = 1.2f, maxAspect = 1.8f)
                val ratio = maxOf(result.logical.width(), result.logical.height()).toFloat() /
                    minOf(result.logical.width(), result.logical.height())
                assertThat(ratio).isAtLeast(1.198f)
                assertThat(ratio).isAtMost(1.803f)
                assertThat(result.logical.width()).isAtLeast(400)
                assertThat(result.logical.height()).isAtLeast(300)
                assertThat(stable.contains(result.visible)).isTrue()
                assertThat(stable.contains(result.logical)).isTrue()
                assertThat(result.logical.width() > result.logical.height()).isEqualTo(landscape)
            }
        }
    }

    @Test fun presets_proportionalDragKeepsScaleAndMiniThreshold() {
        val logical = Rect(100, 100, 900, 1300)
        val start = Rect(100, 100, 700, 1000)
        val stable = Rect(0, 0, 2400, 2400)
        for ((dx, dy) in listOf(Pair(-200f, -300f), Pair(200f, 300f))) {
            val result = FloatingWindowGeometry.presetCorner(start, logical, stable,
                CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM, dx, dy, 200, 300)
            assertThat(result.logical).isEqualTo(logical)
            assertThat(result.commitsBounds).isFalse()
            assertThat(result.atMinimum).isEqualTo(dx < 0f)
        }
    }

    @Test fun presets_narrowestPresetPreservesDpOrientation() {
        val stable = Rect(0, 0, 3000, 2400)
        for (density in listOf(1f, 1.5f, 2.75f, 3f)) {
            val result = FloatingWindowGeometry.presetCorner(Rect(100, 100, 1300, 775),
                Rect(100, 100, 1700, 1000), stable, CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM,
                -900f, 0f, 200, 200, orientationMargin = kotlin.math.ceil(density).toInt())
            assertThat((result.logical.width() / density).toInt())
                .isGreaterThan((result.logical.height() / density).toInt())
        }
    }

    @Test fun presets_runtimeDisplayRatioAndOriginalAreDeduplicated() {
        val wuji = FloatingWindowGeometry.aspectPresets(1600, 2560, 1.6f, true)
        assertThat(wuji).containsExactly(1f, 4f / 3f, 1.6f).inOrder()
        val other = FloatingWindowGeometry.aspectPresets(1904, 3040, 16f / 9f, true)
        assertThat(other).containsExactly(1f, 4f / 3f, 3040f / 1904f, 16f / 9f).inOrder()
        assertThat(FloatingWindowGeometry.aspectPresets(2560, 1600, 1.61f, true))
            .containsExactlyElementsIn(wuji).inOrder()
        assertThat(FloatingWindowGeometry.aspectPresets(1600, 2560, .625f, false))
            .containsExactly(.625f, .75f, 1f).inOrder()
        assertThat(FloatingWindowGeometry.aspectPresets(1600, 2560, 1f, true))
            .containsExactlyElementsIn(wuji)
    }

    @Test fun presets_hysteresisRetainsSelectionAroundBoundaryInBothDirections() {
        val values = listOf(4f / 3f, 1.6f)
        val state = FloatingWindowGeometry.AspectSnapState(1.6f, 1.6f)
        for (ratio in listOf(1.47f, 1.45f, 1.46f, 1.44f)) {
            assertThat(FloatingWindowGeometry.snapAspect(ratio, values, state)).isEqualTo(1.6f)
        }
        assertThat(FloatingWindowGeometry.snapAspect(1.42f, values, state)).isEqualTo(4f / 3f)
        for (ratio in listOf(1.45f, 1.47f, 1.46f, 1.48f)) {
            assertThat(FloatingWindowGeometry.snapAspect(ratio, values, state)).isEqualTo(4f / 3f)
        }
        assertThat(FloatingWindowGeometry.snapAspect(1.51f, values, state)).isEqualTo(1.6f)
    }

    @Test fun presets_skipAppExcludedRatiosWithoutAddingLimitAsPreset() {
        assertThat(FloatingWindowGeometry.aspectPresets(1600, 2560, 16f / 9f, true,
            minAspect = 1.5f, maxAspect = 1.7f)).containsExactly(1.6f)
        assertThat(FloatingWindowGeometry.aspectPresets(1600, 2560, 9f / 16f, false,
            minAspect = 1.5f, maxAspect = 1.7f)).containsExactly(.625f)
        assertThat(FloatingWindowGeometry.aspectPresets(1600, 2560, 1.6f, true,
            minAspect = 1.7f, maxAspect = 1.8f)).isEmpty()
        val logical = Rect(100, 100, 1700, 1000)
        val result = FloatingWindowGeometry.presetCorner(Rect(100, 100, 1300, 775),
            logical, Rect(0, 0, 2560, 1600), CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM,
            -300f, 0f, 200, 200, minAspect = 1.5f, maxAspect = 1.7f)
        assertThat(result.logical.width().toFloat() / result.logical.height())
            .isWithin(.003f).of(1.6f)
    }

    @Test fun presets_skipMinimumSizeThatCannotFitAndKeepCurrentShapeIfNoneFit() {
        val logical = Rect(0, 0, 1600, 900)
        val start = Rect(100, 100, 1300, 775)
        val stable = Rect(0, 0, 1800, 1000)
        val result = FloatingWindowGeometry.presetCorner(start, logical, stable,
            CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM, 0f, 600f, 1500, 500,
            displayWidth = 1600, displayHeight = 2560)
        assertThat(result.logical.width()).isAtLeast(1500)
        assertThat(result.logical.width().toFloat() / result.logical.height())
            .isWithin(.003f).of(1.6f)
        val noFit = FloatingWindowGeometry.presetCorner(start, logical, stable,
            CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM, -400f, 600f, 200, 200,
            minAspect = 1.9f, maxAspect = 2f)
        assertThat(noFit.logical.width().toFloat() / noFit.logical.height())
            .isWithin(.003f).of(16f / 9f)
    }

    @Test fun presets_proportionalRoundedWidthNeverPushesHeightPastStableEdge() {
        val result = FloatingWindowGeometry.presetCorner(Rect(650, 180, 1176, 1116),
            Rect(100, 100, 910, 1540), Rect(0, 50, 2560, 1550),
            CTRL_TYPE_LEFT or CTRL_TYPE_BOTTOM, -244f, 435f, 200, 200)
        assertThat(result.visible.bottom).isAtMost(1550)
        assertThat(result.logical).isEqualTo(Rect(100, 100, 910, 1540))
        assertThat(result.commitsBounds).isFalse()
    }

    @Test fun presets_hysteresisStillAllowsCloseButDistinctOriginalRatio() {
        val values = FloatingWindowGeometry.aspectPresets(1600, 2560, 1.64f, true)
        assertThat(values).hasSize(4)
        val state = FloatingWindowGeometry.AspectSnapState(1.64f, 1.6f)
        for (ratio in listOf(1.619f, 1.621f, 1.62f)) {
            assertThat(FloatingWindowGeometry.snapAspect(ratio, values, state)).isEqualTo(1.6f)
        }
        assertThat(FloatingWindowGeometry.snapAspect(1.64f, values, state)).isEqualTo(1.64f)
        assertThat(FloatingWindowGeometry.snapAspect(1.6f, values, state)).isEqualTo(1.6f)
    }

    @Test fun pinch_scalesAboutOffCenterFingerMidpoint() {
        val start = Rect(100, 200, 500, 1000)
        val result = FloatingWindowGeometry.pinch(start, Rect(start), Rect(0, 0, 2000, 3000),
            1.5f, 250f, 600f, 500f, 1000f, 100, 100)
        assertThat(result.visible).isEqualTo(Rect(275, 400, 875, 1600))
        assertThat(result.visible.width().toFloat() / result.visible.height()).isEqualTo(.5f)
    }

    @Test fun pinch_clampsToStableInsetsAndFullDisplaySize() {
        val stable = Rect(20, 50, 1020, 2050)
        val start = Rect(200, 300, 600, 1100)
        val result = FloatingWindowGeometry.pinch(start, Rect(start), stable, 10f,
            400f, 700f, -100f, -100f, 100, 100)
        assertThat(result.visible).isEqualTo(stable)
        assertThat(result.logical).isEqualTo(stable)
        assertThat(result.commitsBounds).isTrue()
        assertThat(result.scale).isEqualTo(1f)
    }

    @Test fun pinch_minimumMatchesCornerAndKeepsRatio() {
        val start = Rect(200, 300, 600, 1100)
        val logical = Rect(200, 300, 1000, 1900)
        val stable = Rect(0, 0, 1200, 2400)
        val result = FloatingWindowGeometry.pinch(start, logical, stable, .01f,
            400f, 700f, 400f, 700f, 200, 300, .4f, 160)
        val corner = FloatingWindowGeometry.scaledCorner(Rect(0, 0, 400, 800), logical,
            stable, CTRL_TYPE_RIGHT or CTRL_TYPE_BOTTOM, -396f, -792f, 200, 300,
            minimumScale = .4f, minimumVisibleShortSide = 160)
        assertThat(result.visible.width()).isEqualTo(corner.visible.width())
        assertThat(result.visible.height()).isEqualTo(corner.visible.height())
        assertThat(result.scale).isEqualTo(corner.scale)
        assertThat(result.atMinimum).isTrue()
    }

    @Test fun pinch_scaleOnlyPreservesLogicalSize() {
        val result = FloatingWindowGeometry.pinch(Rect(100, 100, 500, 900),
            Rect(100, 100, 900, 1700), Rect(0, 0, 1600, 3000), 1.5f,
            300f, 500f, 300f, 700f, 100, 100)
        assertThat(result.visible).isEqualTo(Rect(0, 100, 600, 1300))
        assertThat(result.logical.width()).isEqualTo(800)
        assertThat(result.logical.height()).isEqualTo(1600)
        assertThat(result.scale).isEqualTo(.75f)
        assertThat(result.commitsBounds).isFalse()
    }

}
