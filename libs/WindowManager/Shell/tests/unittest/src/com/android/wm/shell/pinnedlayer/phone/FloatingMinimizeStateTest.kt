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
class FloatingMinimizeStateTest {
    @Test fun minimize_snapshotsBoundsAndPreservesOrder() {
        val state = FloatingMinimizeState()
        val bounds = Rect(100, 100, 500, 900)
        state.minimize(1, 0, bounds, true, .25f)
        state.minimize(2, 10, bounds, false, .75f)
        bounds.setEmpty()
        assertThat(state.entries.keys.toList()).containsExactly(1, 2).inOrder()
        assertThat(state.entries[1]!!.bounds).isEqualTo(Rect(100, 100, 500, 900))
        assertThat(state.entries[2]!!.userId).isEqualTo(10)
    }

    @Test fun move_updatesEdgeAndRelativePositionAndClamps() {
        val state = FloatingMinimizeState()
        state.minimize(1, 0, Rect(0, 0, 400, 800), true, .3f)
        state.move(1, false, .8f)
        assertThat(state.entries[1]!!.right).isFalse()
        assertThat(state.entries[1]!!.fractionY).isEqualTo(.8f)
        state.move(1, true, 2f)
        assertThat(state.entries[1]!!.fractionY).isEqualTo(1f)
    }

    @Test fun restoreOrClose_removesOnlyThatHandleAndReturnsBounds() {
        val state = FloatingMinimizeState()
        val bounds = Rect(100, 100, 500, 900)
        for (id in 1..6) state.minimize(id, 0, bounds, true, .5f)
        val restored = state.remove(1)!!
        assertThat(restored.bounds).isEqualTo(bounds)
        assertThat(state.entries.keys).containsExactly(2, 3, 4, 5, 6)
        assertThat(state.remove(1)).isNull()
    }

    @Test fun stackPositions_separatesHandlesWithIdenticalRequestedPositions() {
        val state = FloatingMinimizeState()
        for (id in 1..5) state.minimize(id, 0, Rect(0, 0, 400, 800), true, .5f)
        val positions = state.stackPositions(state.entries.values.toList(), 100, 900, 48, 56)
        assertThat(positions.zipWithNext().all { (a, b) -> b - a >= 56 }).isTrue()
        assertThat(positions.first()).isAtLeast(100)
        assertThat(positions.last() + 48).isAtMost(900)
    }

    @Test fun stackPositions_packsWithinSmallDisplayAndKeepsRelativeState() {
        val state = FloatingMinimizeState()
        for (id in 1..5) state.minimize(id, 0, Rect(0, 0, 400, 800), true, .9f)
        val positions = state.stackPositions(state.entries.values.toList(), 0, 150, 48, 56)
        assertThat(positions.zipWithNext().all { (a, b) -> b > a }).isTrue()
        assertThat(positions.first()).isAtLeast(0)
        assertThat(positions.last() + 48).isAtMost(150)
        assertThat(state.entries.values.all { it.fractionY == .9f }).isTrue()
    }

    @Test fun drop_interiorPersistsBothAxesAndKeepsRestoreBounds() {
        val state = FloatingMinimizeState()
        val restore = Rect(100, 100, 500, 900)
        state.minimize(1, 0, restore, true, .5f)
        val stable = Rect(40, 100, 1160, 1900)
        val dropped = Rect(500, 400, 548, 448)
        state.drop(1, dropped, state.entries.values.toList(), stable, 1200, 48, 24, 8, 48)
        val entry = state.entries.getValue(1)
        assertThat(entry.fractionX).isNotNull()
        assertThat(state.positions(listOf(entry), stable, 1200, 48, 24, 8)[1]).isEqualTo(dropped)
        state.minimize(1, 0, restore, true, .5f)
        assertThat(state.handleBounds(state.entries.getValue(1), stable, 1200, 48, 24))
            .isEqualTo(dropped)
        assertThat(state.remove(1)!!.bounds).isEqualTo(restore)
    }

    @Test fun drop_edgeThresholdSnapsHalfHiddenWhileJustOutsideStaysFree() {
        val state = FloatingMinimizeState()
        val stable = Rect(0, 100, 1200, 1900)
        state.minimize(1, 0, Rect(), true, .5f)
        fun drop(left: Int) {
            state.drop(1, Rect(left, 300, left + 48, 348), state.entries.values.toList(),
                stable, 1200, 48, 24, 8, 48)
        }
        drop(24)
        assertThat(state.entries.getValue(1).fractionX).isNull()
        assertThat(state.handleBounds(state.entries.getValue(1), stable, 1200, 48, 24))
            .isEqualTo(Rect(-24, 300, 24, 348))
        drop(25)
        assertThat(state.entries.getValue(1).fractionX).isNotNull()
        assertThat(state.handleBounds(state.entries.getValue(1), stable, 1200, 48, 24))
            .isEqualTo(Rect(25, 300, 73, 348))
        drop(1128)
        assertThat(state.entries.getValue(1).fractionX).isNull()
        assertThat(state.entries.getValue(1).right).isTrue()
        assertThat(state.handleBounds(state.entries.getValue(1), stable, 1200, 48, 24))
            .isEqualTo(Rect(1176, 300, 1224, 348))
    }

    @Test fun drop_offsetsCollisionsWithFreeAndEdgeHandles() {
        val state = FloatingMinimizeState()
        val stable = Rect(0, 100, 1200, 1900)
        for (id in 1..6) state.minimize(id, 0, Rect(), false, .5f)
        state.drop(2, Rect(25, 976, 73, 1024), state.entries.values.take(2),
            stable, 1200, 48, 24, 8, 48)
        assertThat(state.handleBounds(state.entries.getValue(2), stable, 1200, 48, 24).left)
            .isEqualTo(32)
        for (id in 3..6) state.drop(id, Rect(500, 500, 548, 548), state.entries.values.toList(),
            stable, 1200, 48, 24, 8, 48)
        val positions = state.positions(state.entries.values.toList(), stable, 1200, 48, 24, 8)
        state.entries.values.filter { it.fractionX != null }.forEach { entry ->
            val bounds = positions.getValue(entry.taskId)
            assertThat(stable.contains(bounds)).isTrue()
            positions.filterKeys { it != entry.taskId }.values.forEach { other ->
                assertThat(Rect.intersects(bounds, Rect(other).apply { inset(-8, -8) })).isFalse()
            }
        }
    }

    @Test fun freePositions_dropGapBeforeAllowingOverlapOnSmallDisplay() {
        val state = FloatingMinimizeState()
        for (id in 1..2) {
            state.minimize(id, 0, Rect(), true, 0f)
            state.entries.getValue(id).fractionX = 0f
        }
        val stable = Rect(0, 0, 96, 48)
        val positions = state.positions(state.entries.values.toList(), stable, 96, 48, 24, 8)
        assertThat(positions.getValue(1)).isEqualTo(Rect(0, 0, 48, 48))
        assertThat(positions.getValue(2)).isEqualTo(Rect(48, 0, 96, 48))
        assertThat(Rect.intersects(positions.getValue(1), positions.getValue(2))).isFalse()
    }

    @Test fun freePosition_clampsToSafeAreaAndRescalesAcrossRotation() {
        val state = FloatingMinimizeState()
        val stable = Rect(40, 100, 1160, 1900)
        state.minimize(1, 0, Rect(100, 100, 500, 900), true, .5f)
        state.drop(1, Rect(500, -500, 548, -452), state.entries.values.toList(),
            stable, 1200, 48, 24, 8, 48)
        assertThat(state.entries.getValue(1).fractionY).isEqualTo(0f)
        assertThat(state.handleBounds(state.entries.getValue(1), stable, 1200, 48, 24).top)
            .isEqualTo(stable.top)
        state.drop(1, Rect(576, 976, 624, 1024), state.entries.values.toList(),
            stable, 1200, 48, 24, 8, 48)
        val entry = state.entries.getValue(1)
        assertThat(entry.fractionX).isEqualTo(.5f)
        assertThat(entry.fractionY).isEqualTo(.5f)
        val rotated = Rect(100, 40, 1900, 1160)
        val position = state.positions(listOf(entry), rotated, 2000, 48, 24, 8).getValue(1)
        assertThat(position).isEqualTo(Rect(976, 576, 1024, 624))
        assertThat(rotated.contains(position)).isTrue()
        assertThat(entry.fractionX).isEqualTo(.5f)
        assertThat(entry.fractionY).isEqualTo(.5f)
        state.move(1, false, .7f)
        assertThat(entry.fractionX).isNull()
    }

    @Test fun rotation_keepsNormalizedPositionAndAcceptsRefittedBounds() {
        val state = FloatingMinimizeState()
        state.minimize(1, 0, Rect(0, 0, 400, 800), true, .7f)
        state.entries[1]!!.bounds.set(200, 100, 1000, 500)
        assertThat(state.entries[1]!!.fractionY).isEqualTo(.7f)
        assertThat(state.remove(1)!!.bounds).isEqualTo(Rect(200, 100, 1000, 500))
    }
}
