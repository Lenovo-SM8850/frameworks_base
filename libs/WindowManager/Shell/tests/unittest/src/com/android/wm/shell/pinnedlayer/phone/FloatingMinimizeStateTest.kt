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

    @Test fun rotation_keepsNormalizedPositionAndAcceptsRefittedBounds() {
        val state = FloatingMinimizeState()
        state.minimize(1, 0, Rect(0, 0, 400, 800), true, .7f)
        state.entries[1]!!.bounds.set(200, 100, 1000, 500)
        assertThat(state.entries[1]!!.fractionY).isEqualTo(.7f)
        assertThat(state.remove(1)!!.bounds).isEqualTo(Rect(200, 100, 1000, 500))
    }
}
