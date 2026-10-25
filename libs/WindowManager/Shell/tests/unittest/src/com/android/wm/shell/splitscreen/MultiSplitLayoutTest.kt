// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import android.content.res.Resources
import android.graphics.Insets
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.splitscreen.MultiSplitLayout.DividerId
import com.android.wm.shell.splitscreen.MultiSplitLayout.Leaf
import com.android.wm.shell.splitscreen.MultiSplitLayout.LeafId
import com.android.wm.shell.splitscreen.MultiSplitLayout.Orientation
import com.android.wm.shell.splitscreen.MultiSplitLayout.Split
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@SmallTest
@RunWith(AndroidJUnit4::class)
class MultiSplitLayoutTest {
    private val display = Rect(0, 0, 1010, 810)
    private val a = Leaf(LeafId(1), 101)
    private val b = Leaf(LeafId(2), 102)
    private val c = Leaf(LeafId(3), 103)
    private val d = Leaf(LeafId(4), 104)
    private val main = DividerId(10)
    private val left = DividerId(11)
    private val right = DividerId(12)

    @Test
    fun bounds_threeAndFourLeavesTileInBothOrientations() {
        for (orientation in Orientation.entries) {
            val original = pair(orientation)
            val perpendicular = if (orientation == Orientation.LEFT_RIGHT) {
                Orientation.TOP_BOTTOM
            } else Orientation.LEFT_RIGHT
            val three = original.insert(
                a.id, c, left, perpendicular, 0.4f, display, Insets.NONE, 10, 100
            )!!
            val four = three.insert(
                b.id, d, right, perpendicular, 0.6f, display, Insets.NONE, 10, 100
            )!!
            assertEquals(3, three.calculateBounds(display, Insets.NONE, 10)!!.leaves.size)
            assertEquals(3, four.calculateBounds(display, Insets.NONE, 10)!!.dividers.size)
            assertTiling(three.calculateBounds(display, Insets.NONE, 10)!!, display)
            assertTiling(four.calculateBounds(display, Insets.NONE, 10)!!, display)
        }
    }

    @Test
    fun insert_checksInsetsAndEveryExistingLeaf() {
        assertNull(pair().insertBelow(
            a.id, c, left, 0.5f, display, Insets.of(0, 20, 0, 20), 10, 400
        ))
        assertNull(below(pair(ratio = 0.9f), a.id, c, left, minimum = 200))
    }

}
