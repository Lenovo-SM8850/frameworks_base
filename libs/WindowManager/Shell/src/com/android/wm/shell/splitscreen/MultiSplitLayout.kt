// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import android.app.ActivityManager.RunningTaskInfo
import android.graphics.Insets
import android.graphics.Rect

/**
 * Immutable geometry for two to four sibling stages with independently resizable branches.
 * Branches describe geometry only and never represent task roots or own surfaces.
 */
class MultiSplitLayout(val root: Split) {
    data class LeafId(val value: Int)
    data class DividerId(val value: Int)

    enum class Orientation {
        LEFT_RIGHT,
        TOP_BOTTOM,
    }

    sealed interface Node

    /** The leaf ID identifies the slot; swapping apps exchanges task IDs only. */
    data class Leaf(val id: LeafId, val taskId: Int) : Node

    /** Ratio is the first child's fraction of the space remaining after the divider. */
    data class Split(
        val id: DividerId,
        val orientation: Orientation,
        val ratio: Float,
        val first: Node,
        val second: Node,
    ) : Node {
        init {
            require(ratio.isFinite() && ratio > 0f && ratio < 1f)
        }
    }

    data class Bounds(val leaves: Map<LeafId, Rect>, val dividers: Map<DividerId, Rect>)

    private val leaves = mutableMapOf<LeafId, Leaf>()
    private val dividers = mutableSetOf<DividerId>()

    init {
        fun validate(node: Node, depth: Int) {
            when (node) {
                is Leaf -> require(leaves.put(node.id, node) == null)
                is Split -> {
                    require(depth < 3 && dividers.add(node.id))
                    validate(node.first, depth + 1)
                    validate(node.second, depth + 1)
                }
            }
        }
        validate(root, 0)
        require(leaves.size in 2..4)
        require(leaves.values.map { it.taskId }.distinct().size == leaves.size)
    }

    /** Uses stable insets once at the root and the existing recursive rectangle calculator. */
    fun calculateBounds(displayBounds: Rect, insets: Insets, dividerSize: Int): Bounds? {
        require(dividerSize >= 0)
        require(insets.left >= 0 && insets.top >= 0 && insets.right >= 0 && insets.bottom >= 0)
        val content = Rect(
            displayBounds.left + insets.left,
            displayBounds.top + insets.top,
            displayBounds.right - insets.right,
            displayBounds.bottom - insets.bottom,
        )
        if (content.isEmpty) return null
        val leafNodes = mutableMapOf<LeafNode, LeafId>()
        val dividerNodes = mutableMapOf<Pair<LayoutNode, LayoutNode>, DividerId>()
        fun convert(node: Node, weight: Float): LayoutNode = when (node) {
            is Leaf -> LeafNode(RunningTaskInfo().apply { taskId = node.taskId }, weight).also {
                leafNodes[it] = node.id
            }
            is Split -> {
                val first = convert(node.first, node.ratio)
                val second = convert(node.second, 1f - node.ratio)
                dividerNodes[first to second] = node.id
                BranchNode(
                    if (node.orientation == Orientation.LEFT_RIGHT) {
                        BranchNode.ORIENTATION_HORIZONTAL
                    } else {
                        BranchNode.ORIENTATION_VERTICAL
                    },
                    listOf(first, second),
                    weight,
                    dividerSize,
                )
            }
        }
        val result = LayoutEngine().calculateLayout(convert(root, 1f), content)
        val bounds = Bounds(
            result.leafNodeBounds.mapKeys { leafNodes.getValue(it.key) },
            result.dividerBounds.mapKeys { dividerNodes.getValue(it.key) },
        )
        return bounds.takeIf { it.leaves.values.all { rect -> !rect.isEmpty } }
    }

    /** Returns null for an unsupported topology, reused ID, or infeasible insertion. */
    @JvmOverloads
    fun insert(
        chosenLeaf: LeafId,
        newLeaf: Leaf,
        dividerId: DividerId,
        orientation: Orientation,
        ratio: Float,
        displayBounds: Rect,
        insets: Insets,
        dividerSize: Int,
        minimumLeafSize: Int,
        newTaskFirst: Boolean = false,
    ): MultiSplitLayout? {
        require(minimumLeafSize >= 0)
        if (!ratio.isFinite() || ratio <= 0f || ratio >= 1f) return null
        if (leaves.size == 4 || chosenLeaf !in leaves || newLeaf.id in leaves ||
            leaves.values.any { it.taskId == newLeaf.taskId } || dividerId in dividers) return null
        val chosen = leaves.getValue(chosenLeaf)
        val replacement = if (newTaskFirst) {
            Split(dividerId, orientation, ratio, newLeaf, chosen)
        } else {
            Split(dividerId, orientation, ratio, chosen, newLeaf)
        }
        val candidate = MultiSplitLayout(replace(root, chosenLeaf) { replacement } as Split)
        val bounds = candidate.calculateBounds(displayBounds, insets, dividerSize) ?: return null
        return candidate.takeIf { LayoutPolicy().isFeasible(bounds, minimumLeafSize) }
    }

    /** Splits the chosen leaf top/bottom, keeping its current app above the new app. */
    fun insertBelow(
        chosenLeaf: LeafId,
        newLeaf: Leaf,
        dividerId: DividerId,
        ratio: Float,
        displayBounds: Rect,
        insets: Insets,
        dividerSize: Int,
        minimumLeafSize: Int,
    ): MultiSplitLayout? = insert(
        chosenLeaf, newLeaf, dividerId, Orientation.TOP_BOTTOM, ratio,
        displayBounds, insets, dividerSize, minimumLeafSize,
    )

    /** Collapses the removed leaf's parent; two leaves remain the minimum split layout. */
    fun remove(leafId: LeafId): MultiSplitLayout? {
        if (leaves.size <= 2 || leafId !in leaves) return null
        fun collapse(node: Node): Node? = when (node) {
            is Leaf -> node.takeUnless { it.id == leafId }
            is Split -> {
                val first = collapse(node.first)
                val second = collapse(node.second)
                when {
                    first == null -> second
                    second == null -> first
                    else -> node.copy(first = first, second = second)
                }
            }
        }
        return MultiSplitLayout(collapse(root) as Split)
    }

    /** Exchanges task assignments without changing slot IDs, branches, divider IDs, or ratios. */
    fun swap(first: LeafId, second: LeafId): MultiSplitLayout? {
        val firstTask = leaves[first]?.taskId ?: return null
        val secondTask = leaves[second]?.taskId ?: return null
        val swapped = replace(root, first) { it.copy(taskId = secondTask) }
        return MultiSplitLayout(replace(swapped, second) { it.copy(taskId = firstTask) } as Split)
    }

    /** Stable slot order, independent of task assignments and stage allocation. */
    fun getLeaves(): List<Leaf> = leaves.values.toList()

    /** Replaces a slot's app without changing geometry. */
    fun assign(id: LeafId, taskId: Int): MultiSplitLayout? {
        if (id !in leaves || leaves.values.any { it.id != id && it.taskId == taskId }) return null
        return MultiSplitLayout(replace(root, id) { it.copy(taskId = taskId) } as Split)
    }

    /** Reorients the whole topology for the display without changing task or divider identities. */
    fun reorient(orientation: Orientation): MultiSplitLayout {
        if (root.orientation == orientation) return this
        fun rotate(node: Node): Node = when (node) {
            is Leaf -> node
            is Split -> node.copy(
                orientation = if (node.orientation == Orientation.LEFT_RIGHT) {
                    Orientation.TOP_BOTTOM
                } else Orientation.LEFT_RIGHT,
                first = rotate(node.first), second = rotate(node.second),
            )
        }
        return MultiSplitLayout(rotate(root) as Split)
    }

    /** Updates only the selected divider's ratio; descendant ratios remain unchanged. */
    fun resize(id: DividerId, ratio: Float): MultiSplitLayout? {
        if (id !in dividers || !ratio.isFinite() || ratio <= 0f || ratio >= 1f) return null
        fun update(node: Node): Node = when (node) {
            is Leaf -> node
            is Split -> node.copy(
                ratio = if (node.id == id) ratio else node.ratio,
                first = update(node.first), second = update(node.second),
            )
        }
        return MultiSplitLayout(update(root) as Split)
    }

    private fun replace(node: Node, id: LeafId, transform: (Leaf) -> Node): Node = when (node) {
        is Leaf -> if (node.id == id) transform(node) else node
        is Split -> node.copy(
            first = replace(node.first, id, transform),
            second = replace(node.second, id, transform),
        )
    }
}
