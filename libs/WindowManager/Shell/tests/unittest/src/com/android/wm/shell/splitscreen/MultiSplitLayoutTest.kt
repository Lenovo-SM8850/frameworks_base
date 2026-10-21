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
    fun twoLeaves_sideBySide() {
        val bounds = pair().calculateBounds(display, Insets.NONE, 10)!!
        assertEquals(Rect(0, 0, 500, 810), bounds.leaves[a.id])
        assertEquals(Rect(510, 0, 1010, 810), bounds.leaves[b.id])
        assertEquals(Rect(500, 0, 510, 810), bounds.dividers[main])
        assertTiling(bounds, display)
    }

    @Test
    fun twoLeaves_stacked() {
        val bounds = pair(Orientation.TOP_BOTTOM).calculateBounds(display, Insets.NONE, 10)!!
        assertEquals(Rect(0, 0, 1010, 400), bounds.leaves[a.id])
        assertEquals(Rect(0, 410, 1010, 810), bounds.leaves[b.id])
        assertEquals(Rect(0, 400, 1010, 410), bounds.dividers[main])
        assertTiling(bounds, display)
    }

    @Test
    fun bounds_applyAsymmetricInsetsAndOriginOnce() {
        val frame = Rect(100, 200, 1140, 1060)
        val bounds = grid().calculateBounds(frame, Insets.of(10, 20, 20, 30), 10)!!
        assertEquals(Rect(110, 220, 610, 620), bounds.leaves[a.id])
        assertEquals(Rect(620, 630, 1120, 1030), bounds.leaves[d.id])
        assertTiling(bounds, Rect(110, 220, 1120, 1030))
        assertEquals(Rect(100, 200, 1140, 1060), frame)
    }

    @Test
    fun bounds_ratiosAndOddPixelsAreDeterministic() {
        val layout = pair(ratio = 0.3f)
        val frame = Rect(11, 17, 1023, 830)
        val bounds = layout.calculateBounds(frame, Insets.NONE, 9)!!
        assertEquals(Rect(11, 17, 312, 830), bounds.leaves[a.id])
        assertEquals(Rect(312, 17, 321, 830), bounds.dividers[main])
        assertEquals(Rect(321, 17, 1023, 830), bounds.leaves[b.id])
        assertEquals(bounds, layout.calculateBounds(frame, Insets.NONE, 9))
        assertTiling(bounds, frame)
    }

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
    fun insertBelow_keepsChosenAppAboveNewApp() {
        val original = pair()
        val three = below(original, a.id, c, left)!!
        val bounds = three.calculateBounds(display, Insets.NONE, 10)!!
        assertEquals(Rect(0, 0, 500, 400), bounds.leaves[a.id])
        assertEquals(Rect(0, 410, 500, 810), bounds.leaves[c.id])
        assertEquals(Rect(510, 0, 1010, 810), bounds.leaves[b.id])
        assertEquals(2, original.calculateBounds(display, Insets.NONE, 10)!!.leaves.size)
    }

    @Test
    fun insert_rejectsNestedParallelAndFifthLeavesInBothOrientations() {
        for (orientation in Orientation.entries) {
            val pair = pair(orientation)
            val axis = pair.insertionAxis(a.id)!!
            val three = pair.insert(a.id, c, left, axis, 0.5f, display, Insets.NONE, 10, 0)!!
            assertNull(three.insert(c.id, d, right, axis, 0.5f, display, Insets.NONE, 10, 0))
            assertNull(three.insert(a.id, d, right, orientation, 0.5f, display, Insets.NONE, 10, 0))
            assertNull(pair.insert(a.id, c, left, orientation, 0.5f, display, Insets.NONE, 10, 0))
            val four = three.insert(b.id, d, right, axis, 0.5f, display, Insets.NONE, 10, 0)!!
            assertTiling(four.calculateBounds(display, Insets.NONE, 10)!!, display)
            for (leaf in four.getLeaves()) assertNull(four.insertionAxis(leaf.id))
            val rotated = four.reorient(if (orientation == Orientation.LEFT_RIGHT)
                Orientation.TOP_BOTTOM else Orientation.LEFT_RIGHT)
            assertEquals(four.root.first.let { (it as Split).first },
                (rotated.root.first as Split).first)
            assertEquals(four.root, rotated.reorient(orientation).root)
        }
    }

    @Test
    fun policyEnumeratesEveryBinaryShapeAndAxisThroughFiveApps() {
        fun trees(count: Int, offset: Int = 0): List<MultiSplitLayout.Node> {
            if (count == 1) return listOf(Leaf(LeafId(offset), 100 + offset))
            return (1 until count).flatMap { leftCount ->
                trees(leftCount, offset).flatMap { first ->
                    trees(count - leftCount, offset + leftCount).flatMap { second ->
                        Orientation.entries.map { axis ->
                            Split(DividerId(offset * 10 + count), axis, 0.5f, first, second)
                        }
                    }
                }
            }
        }
        for (count in 2..5) for (tree in trees(count)) {
            val root = tree as Split
            fun half(node: MultiSplitLayout.Node): Boolean = when (node) {
                is Leaf -> true
                is Split -> node.orientation != root.orientation &&
                    node.first is Leaf && node.second is Leaf
            }
            assertEquals(count <= 4 && half(root.first) && half(root.second),
                MultiSplitLayout.isAllowed(root))
        }
    }


    @Test
    fun insert_rejectsUnknownLeafAndReusedIdentities() {
        assertNull(below(pair(), LeafId(99), c, left))
        assertNull(below(pair(), a.id, a.copy(taskId = 103), left))
        assertNull(below(pair(), a.id, c.copy(taskId = 101), left))
        assertNull(below(pair(), a.id, c, main))
    }

    @Test
    fun insert_minimumBoundaryAndFailedInsertPreserveOriginal() {
        val original = pair()
        val before = original.calculateBounds(display, Insets.NONE, 10)
        assertNotNull(below(original, a.id, c, left, minimum = 400))
        assertNull(below(original, a.id, c, left, minimum = 401))
        assertEquals(before, original.calculateBounds(display, Insets.NONE, 10))
    }

    @Test
    fun insert_checksInsetsAndEveryExistingLeaf() {
        assertNull(pair().insertBelow(
            a.id, c, left, 0.5f, display, Insets.of(0, 20, 0, 20), 10, 400
        ))
        assertNull(below(pair(ratio = 0.9f), a.id, c, left, minimum = 200))
    }

    @Test
    fun insert_rejectsInvalidRatios() {
        for (ratio in listOf(0f, 1f, -0.5f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(pair().insertBelow(a.id, c, left, ratio, display, Insets.NONE, 10, 100))
        }
    }

    @Test
    fun bounds_rejectsInsufficientSpaceAndInvalidDimensions() {
        assertNull(pair().calculateBounds(Rect(0, 0, 10, 100), Insets.NONE, 10))
        assertNull(pair().calculateBounds(display, Insets.of(1000, 0, 1000, 0), 10))
        assertNull(pair().calculateBounds(Rect(), Insets.NONE, 0))
        assertThrows(IllegalArgumentException::class.java) {
            pair().calculateBounds(display, Insets.NONE, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            pair().calculateBounds(display, Insets.of(-1, 0, 0, 0), 10)
        }
    }

    @Test
    fun remove_collapsesFourToThreeToTwoAndKeepsSurvivingIdsAndRatios() {
        val four = grid()
        val three = four.remove(c.id)!!
        val two = three.remove(d.id)!!
        assertEquals(
            setOf(main, right), three.calculateBounds(display, Insets.NONE, 10)!!.dividers.keys
        )
        assertEquals(pair().root, two.root)
        assertNull(two.remove(a.id))
        assertNull(four.remove(LeafId(99)))
        assertEquals(4, four.calculateBounds(display, Insets.NONE, 10)!!.leaves.size)
    }

    @Test
    fun remove_allFourLeavesCanBeRemovedAndSingleSidePromotesToRoot() {
        for (leaf in listOf(a, b, c, d)) {
            val three = grid().remove(leaf.id)!!
            assertEquals(3, three.calculateBounds(display, Insets.NONE, 10)!!.leaves.size)
            assertTiling(three.calculateBounds(display, Insets.NONE, 10)!!, display)
        }
        val three = below(pair(), a.id, c, left)!!
        val promoted = three.remove(b.id)!!
        assertEquals(left, promoted.root.id)
        assertEquals(Orientation.TOP_BOTTOM, promoted.root.orientation)
        assertEquals(
            setOf(a.id, c.id), promoted.calculateBounds(display, Insets.NONE, 10)!!.leaves.keys
        )
    }

    @Test
    fun swap_exchangesTaskAssignmentsAndPreservesGeometryAndTree() {
        val layout = grid()
        val swapped = layout.swap(a.id, d.id)!!
        assertEquals(layout.calculateBounds(display, Insets.NONE, 10),
            swapped.calculateBounds(display, Insets.NONE, 10))
        assertEquals(104, ((swapped.root.first as Split).first as Leaf).taskId)
        assertEquals(101, ((swapped.root.second as Split).second as Leaf).taskId)
        assertEquals(layout.root, swapped.swap(a.id, d.id)!!.root)
        assertEquals(layout.root, layout.swap(a.id, a.id)!!.root)
        assertNull(layout.swap(a.id, LeafId(99)))
    }

    @Test
    fun topology_rejectsInvalidRatiosDuplicateIdsAndExcessLeaves() {
        for (ratio in listOf(0f, 1f, -1f, Float.NaN, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { pair(ratio = ratio) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MultiSplitLayout(Split(main, Orientation.LEFT_RIGHT, 0.5f, a, a))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MultiSplitLayout(Split(main, Orientation.LEFT_RIGHT, 0.5f, a, b.copy(taskId = 101)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MultiSplitLayout(Split(main, Orientation.LEFT_RIGHT, 0.5f,
                Split(main, Orientation.TOP_BOTTOM, 0.5f, a, c), b))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MultiSplitLayout(Split(main, Orientation.LEFT_RIGHT, 0.5f,
                Split(left, Orientation.TOP_BOTTOM, 0.5f, a,
                    Split(right, Orientation.LEFT_RIGHT, 0.5f, c,
                        Split(DividerId(13), Orientation.TOP_BOTTOM, 0.5f,
                            d, Leaf(LeafId(5), 105)))), b))
        }
    }

    @Test
    fun policy_reusesExistingMinimumResourceAndChecksBothAxes() {
        val resources = mock(Resources::class.java)
        `when`(resources.getDimensionPixelSize(
            com.android.internal.R.dimen.default_minimal_size_resizable_task
        )).thenReturn(400)
        val policy = LayoutPolicy()
        assertEquals(400, policy.getMinimumLeafSize(resources))
        val bounds = grid().calculateBounds(display, Insets.NONE, 10)!!
        assertTrue(policy.isFeasible(bounds, policy.getMinimumLeafSize(resources)))
        assertFalse(policy.isFeasible(bounds, 401))
        assertThrows(IllegalArgumentException::class.java) { policy.isFeasible(bounds, -1) }
    }

    private fun pair(
        orientation: Orientation = Orientation.LEFT_RIGHT,
        ratio: Float = 0.5f,
    ) = MultiSplitLayout(Split(main, orientation, ratio, a, b))

    private fun grid(): MultiSplitLayout = below(below(pair(), a.id, c, left)!!, b.id, d, right)!!

    private fun below(
        layout: MultiSplitLayout,
        chosen: LeafId,
        leaf: Leaf,
        divider: DividerId,
        minimum: Int = 100,
    ) = layout.insertBelow(chosen, leaf, divider, 0.5f, display, Insets.NONE, 10, minimum)

    private fun assertTiling(bounds: MultiSplitLayout.Bounds, content: Rect) {
        val rectangles = bounds.leaves.values + bounds.dividers.values
        var area = 0L
        rectangles.forEachIndexed { index, rect ->
            assertTrue(content.contains(rect))
            area += rect.width().toLong() * rect.height()
            rectangles.drop(index + 1).forEach { assertFalse(Rect.intersects(rect, it)) }
        }
        assertEquals(content.width().toLong() * content.height(), area)
        assertEquals(bounds.leaves.size - 1, bounds.dividers.size)
    }
    @Test fun insertionCanPutNewTaskInFirstHalf() {
        val initial = MultiSplitLayout(MultiSplitLayout.Split(
            MultiSplitLayout.DividerId(0), MultiSplitLayout.Orientation.LEFT_RIGHT, 0.5f,
            MultiSplitLayout.Leaf(MultiSplitLayout.LeafId(0), 10),
            MultiSplitLayout.Leaf(MultiSplitLayout.LeafId(1), 20)))
        val model = initial.insert(MultiSplitLayout.LeafId(0),
            MultiSplitLayout.Leaf(MultiSplitLayout.LeafId(2), 30), MultiSplitLayout.DividerId(1),
            MultiSplitLayout.Orientation.TOP_BOTTOM, 0.5f, Rect(0, 0, 1200, 900),
            Insets.NONE, 8, 100, true)!!
        val bounds = model.calculateBounds(Rect(0, 0, 1200, 900), Insets.NONE, 8)!!
        assertThat(bounds.leaves.getValue(MultiSplitLayout.LeafId(2)).bottom)
            .isLessThan(bounds.leaves.getValue(MultiSplitLayout.LeafId(0)).top)
    }

    @Test fun resizingOneBranchPreservesTheOtherBranchRatio() {
        val model = MultiSplitLayout(MultiSplitLayout.Split(
            MultiSplitLayout.DividerId(0), MultiSplitLayout.Orientation.LEFT_RIGHT, 0.5f,
            MultiSplitLayout.Split(MultiSplitLayout.DividerId(1),
                MultiSplitLayout.Orientation.TOP_BOTTOM, 0.4f,
                MultiSplitLayout.Leaf(MultiSplitLayout.LeafId(0), 10),
                MultiSplitLayout.Leaf(MultiSplitLayout.LeafId(2), 30)),
            MultiSplitLayout.Split(MultiSplitLayout.DividerId(2),
                MultiSplitLayout.Orientation.TOP_BOTTOM, 0.6f,
                MultiSplitLayout.Leaf(MultiSplitLayout.LeafId(1), 20),
                MultiSplitLayout.Leaf(MultiSplitLayout.LeafId(3), 40))))
        val resized = model.resize(MultiSplitLayout.DividerId(1), 0.7f)!!
        assertThat((resized.root.first as MultiSplitLayout.Split).ratio).isEqualTo(0.7f)
        assertThat((resized.root.second as MultiSplitLayout.Split).ratio).isEqualTo(0.6f)
        assertThat(resized.root.ratio).isEqualTo(0.5f)
        assertThat(model.resize(MultiSplitLayout.DividerId(1), Float.NaN)).isNull()
    }

}
