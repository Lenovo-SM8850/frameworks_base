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

    @Test fun restoreGeometry_isCopiedAndKeptAcrossMiniMovesAndStash() {
        val state = FloatingMiniState()
        val normal = Rect(200, 300, 800, 1200)
        val restore = FloatingMiniState.Restore(normal, logical, .75f)
        val entry = state.enter(10, 1, restore, Rect(8, 108, 368, 648), .45f)
        normal.offset(500, 500)
        entry.bounds.offset(200, 200)
        entry.edge = FloatingMiniState.Edge.LEFT
        state.enter(10, 1, FloatingMiniState.Restore(Rect(), Rect(), 1f), Rect(), 1f)
        assertThat(entry.restore.bounds).isEqualTo(Rect(200, 300, 800, 1200))
        assertThat(entry.restore.logical).isEqualTo(logical)
        assertThat(entry.restore.scale).isEqualTo(.75f)
        assertThat(state.canRemember(10, 1)).isFalse()
        assertThat(state.canRemember(11, 1)).isTrue()
        state.remove(10, 1)
        assertThat(state.canRemember(10, 1)).isTrue()
    }

    @Test fun stashThreshold_hasHysteresisAndKeepsVisibleStripOnHorizontalEdges() {
        assertThat(FloatingMiniState.stashEdge(Rect(-216, 300, 144, 840), stable))
            .isEqualTo(FloatingMiniState.Edge.NONE)
        assertThat(FloatingMiniState.stashEdge(Rect(-217, 300, 143, 840), stable))
            .isEqualTo(FloatingMiniState.Edge.LEFT)
        assertThat(FloatingMiniState.canUnstash(Rect(-180, 300, 180, 840), stable,
            FloatingMiniState.Edge.LEFT)).isFalse()
        assertThat(FloatingMiniState.canUnstash(Rect(-179, 300, 181, 840), stable,
            FloatingMiniState.Edge.LEFT)).isTrue()
        for (edge in FloatingMiniState.Edge.entries.filter { it != FloatingMiniState.Edge.NONE }) {
            val stashed = FloatingMiniState.stash(Rect(200, 300, 560, 840), stable, edge, 28)
            val intersection = Rect(stashed)
            assertThat(intersection.intersect(stable)).isTrue()
            if (edge == FloatingMiniState.Edge.LEFT || edge == FloatingMiniState.Edge.RIGHT) {
                assertThat(intersection.width()).isEqualTo(28)
            } else assertThat(intersection.height()).isEqualTo(28)
        }
    }

    @Test fun rotationRefit_keepsStashEdgeAndUserIsolation() {
        val state = FloatingMiniState()
        val restore = FloatingMiniState.Restore(Rect(200, 300, 800, 1200), logical, .75f)
        val entry = state.enter(10, 1, restore, Rect(-332, 300, 28, 840), .45f)
        entry.edge = FloatingMiniState.Edge.LEFT
        val rotated = Rect(0, 100, 1900, 1200)
        val refit = FloatingMiniState.refit(entry.bounds, stable, rotated)
        val stashed = FloatingMiniState.stash(refit, rotated, entry.edge, 28)
        assertThat(stashed.right).isEqualTo(28)
        assertThat(stashed.top).isAtLeast(rotated.top)
        assertThat(stashed.bottom).isAtMost(rotated.bottom)
        assertThat(state.get(11, 1)).isNull()
        state.removeUser(11)
        assertThat(state.get(10, 1)).isSameInstanceAs(entry)
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

    @Test fun topAndBottomPushOut_neverStashAndRefitFullyInside() {
        for (bounds in listOf(Rect(300, -400, 660, 140), Rect(300, 1800, 660, 2340))) {
            assertThat(FloatingMiniState.stashEdge(bounds, stable)).isEqualTo(FloatingMiniState.Edge.NONE)
            assertThat(stable.contains(FloatingMiniState.clamp(bounds, stable, 8))).isTrue()
        }
    }

}
