// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import android.app.ActivityManager.RunningTaskInfo
import android.graphics.Insets
import android.graphics.Rect

/**
 * Immutable geometry for two to four sibling stages with independently resizable branches.
 * Branches describe geometry only and never represent task roots or own surfaces.
 */
class MultiSplitLayout @JvmOverloads constructor(
    val root: Split,
    val strip: LeafId? = null,
    val threeColumnGroup: Boolean = false,
    /** The paired side before entering columns; null keeps the legacy single-first fallback. */
    val columnPairFirst: Boolean? = null,
) {
    val isThreeColumns: Boolean get() = threeColumnGroup && root.orientation == Orientation.LEFT_RIGHT
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

    data class Bounds @JvmOverloads constructor(val leaves: Map<LeafId, Rect>, val dividers: Map<DividerId, Rect>,
        val visibleLeaves: Map<LeafId, Rect> = leaves)

    private val leaves = mutableMapOf<LeafId, Leaf>()
    private val dividers = mutableSetOf<DividerId>()

    companion object {
        /** A root has two halves, each containing one app or a perpendicular pair. */
        @JvmStatic
        @JvmOverloads
        fun isAllowed(root: Split, allowThreeColumns: Boolean = false): Boolean {
            fun half(node: Node): Boolean = node is Leaf || node is Split &&
                node.orientation != root.orientation && node.first is Leaf && node.second is Leaf
            val halves = half(root.first) && half(root.second)
            return if (allowThreeColumns) isColumnTree(root) ||
                root.orientation == Orientation.TOP_BOTTOM && halves &&
                    ((root.first is Split) xor (root.second is Split))
            else halves
        }

        private fun isColumnTree(root: Split): Boolean = root.orientation == Orientation.LEFT_RIGHT &&
            root.first is Leaf && (root.second as? Split)?.let {
                it.orientation == Orientation.LEFT_RIGHT && it.first is Leaf && it.second is Leaf
            } == true
    }

    /** Only a single-app half can acquire a second app. */
    fun insertionAxis(id: LeafId): Orientation? {
        if (threeColumnGroup || !isAllowed(root) ||
            listOf(root.first, root.second).none { it is Leaf && it.id == id }) {
            return null
        }
        return if (root.orientation == Orientation.LEFT_RIGHT) Orientation.TOP_BOTTOM
            else Orientation.LEFT_RIGHT
    }

    init {
        require(isAllowed(root, threeColumnGroup))
        require(isColumnTree(root) == isThreeColumns)
        require(threeColumnGroup || columnPairFirst == null)
        require(!isThreeColumns || strip == null)
        require(strip == null || listOf(root.first, root.second).any { it is Leaf && it.id == strip })
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
        require(leaves.size in 2..if (threeColumnGroup) 3 else 4)
        require(!threeColumnGroup || leaves.size == 3)
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
        if (content.isEmpty || isThreeColumns && content.width() <= 2 * dividerSize) return null
        val leafNodes = mutableMapOf<LeafNode, LeafId>()
        val dividerNodes = mutableMapOf<Pair<LayoutNode, LayoutNode>, DividerId>()
        fun convert(node: Node, weight: Float): LayoutNode = when (node) {
            is Leaf -> LeafNode(RunningTaskInfo().apply { taskId = node.taskId }, weight).also {
                leafNodes[it] = node.id
            }
            is Split -> {
                val ratio = if (isThreeColumns && node.id == root.id) {
                    // Reserve both dividers before dividing the remaining width equally.
                    (content.width() - 2 * dividerSize) / 3f / (content.width() - dividerSize)
                } else if (isThreeColumns) 0.5f else node.ratio
                val first = convert(node.first, ratio)
                val second = convert(node.second, 1f - ratio)
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
        if (strip == null) return bounds.takeIf { it.leaves.values.all { rect -> !rect.isEmpty } }
        val logical = bounds.leaves.mapValues { Rect(it.value) }
        val rect = logical[strip] ?: return null
        val first = (root.first as? Leaf)?.id == strip
        // Keep the hidden app large, as in flexible pairs, while exposing only its peek.
        if (root.orientation == Orientation.LEFT_RIGHT) {
            if (first) rect.left = rect.right - (content.width() - rect.width() - dividerSize)
            else rect.right = rect.left + (content.width() - rect.width() - dividerSize)
        } else {
            if (first) rect.top = rect.bottom - (content.height() - rect.height() - dividerSize)
            else rect.bottom = rect.top + (content.height() - rect.height() - dividerSize)
        }
        return Bounds(logical, bounds.dividers, bounds.leaves)
            .takeIf { it.leaves.values.all { rect -> !rect.isEmpty } }
    }

    data class DividerHandle(val position: Int, val first: LeafId, val second: LeafId)

    /** Places root handles in the overlapping rows, clear of either independent crossing. */
    fun dividerHandles(bounds: Bounds): Map<DividerId, List<DividerHandle>> {
        val result = linkedMapOf<DividerId, List<DividerHandle>>()
        if (isThreeColumns) {
            val ordered = getLeaves()
            val child = root.second as Split
            listOf(root.id, child.id).forEachIndexed { index, id ->
                result[id] = listOf(DividerHandle(bounds.dividers.getValue(id).centerY(),
                    ordered[index].id, ordered[index + 1].id))
            }
            return result
        }
        fun start(rect: Rect) = if (root.orientation == Orientation.LEFT_RIGHT) rect.top else rect.left
        fun end(rect: Rect) = if (root.orientation == Orientation.LEFT_RIGHT) rect.bottom else rect.right
        fun first(node: Node): Leaf = if (node is Leaf) node else (node as Split).first as Leaf
        fun last(node: Node): Leaf = if (node is Leaf) node else (node as Split).second as Leaf
        val children = listOf(root.first, root.second).filterIsInstance<Split>()
        val rootRect = bounds.dividers.getValue(root.id)
        val beforeEnd = children.minOfOrNull { start(bounds.dividers.getValue(it.id)) }
            ?: end(rootRect)
        val afterStart = children.maxOfOrNull { end(bounds.dividers.getValue(it.id)) }
            ?: start(rootRect)
        val before = DividerHandle((start(rootRect) + beforeEnd) / 2,
            first(root.first).id, first(root.second).id)
        val after = DividerHandle((afterStart + end(rootRect)) / 2,
            last(root.first).id, last(root.second).id)
        result[root.id] = when (children.size) {
            2 -> listOf(before, after)
            1 -> listOf(if (beforeEnd - start(rootRect) >= end(rootRect) - afterStart) before else after)
            else -> listOf(before)
        }
        children.forEach { child ->
            val rect = bounds.dividers.getValue(child.id)
            val position = if (child.orientation == Orientation.LEFT_RIGHT) rect.centerY() else rect.centerX()
            result[child.id] = listOf(DividerHandle(position, first(child.first).id, first(child.second).id))
        }
        return result
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
        if (insertionAxis(chosenLeaf) != orientation) return null
        val chosen = leaves.getValue(chosenLeaf)
        val replacement = if (newTaskFirst) {
            Split(dividerId, orientation, ratio, newLeaf, chosen)
        } else {
            Split(dividerId, orientation, ratio, chosen, newLeaf)
        }
        val source = if (strip == chosenLeaf) normal() else this
        val candidate = MultiSplitLayout(source.replace(source.root, chosenLeaf) { replacement } as Split,
            source.strip)
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
        val collapsed = collapse(root) as Split
        val retained = strip?.takeIf { id ->
            listOf(collapsed.first, collapsed.second).any { it is Leaf && it.id == id }
        }
        val pair = if (isThreeColumns) collapsed.copy(orientation = root.orientation) else collapsed
        val result = MultiSplitLayout(pair, retained).reorient(root.orientation)
        return if (retained == null) result.normal() else result
    }

    /** Exchanges task assignments without changing slot IDs, branches, divider IDs, or ratios. */
    fun swap(first: LeafId, second: LeafId): MultiSplitLayout? {
        val firstTask = leaves[first]?.taskId ?: return null
        val secondTask = leaves[second]?.taskId ?: return null
        val swapped = replace(root, first) { it.copy(taskId = secondTask) }
        return MultiSplitLayout(replace(swapped, second) { it.copy(taskId = firstTask) } as Split,
            strip, threeColumnGroup, columnPairFirst)
    }

    /** Stable slot order, independent of task assignments and stage allocation. */
    fun getLeaves(): List<Leaf> = leaves.values.toList()

    /** Replaces a slot's app without changing geometry. */
    fun assign(id: LeafId, taskId: Int): MultiSplitLayout? {
        if (id !in leaves || leaves.values.any { it.id != id && it.taskId == taskId }) return null
        return MultiSplitLayout(replace(root, id) { it.copy(taskId = taskId) } as Split,
            strip, threeColumnGroup, columnPairFirst)
    }

    /** Reorients the whole topology for the display without changing task or divider identities. */
    fun reorient(orientation: Orientation): MultiSplitLayout {
        if (root.orientation == orientation) return this
        if (threeColumnGroup) {
            return MultiSplitLayout(threeAppRoot(orientation,
                columns = orientation == Orientation.LEFT_RIGHT),
                threeColumnGroup = true, columnPairFirst = columnPairFirst)
        }
        fun rotate(node: Node): Node = when (node) {
            is Leaf -> node
            is Split -> node.copy(
                orientation = if (node.orientation == Orientation.LEFT_RIGHT) {
                    Orientation.TOP_BOTTOM
                } else Orientation.LEFT_RIGHT,
                first = rotate(node.first), second = rotate(node.second),
            )
        }
        return MultiSplitLayout(rotate(root) as Split, strip)
    }

    /** Toggles columns while retaining the originating paired side and spatial app order. */
    fun rotateThreeAppShape(): MultiSplitLayout? {
        if (getLeaves().size != 3 || root.orientation != Orientation.LEFT_RIGHT) return null
        return if (isThreeColumns) {
            MultiSplitLayout(threeAppRoot(Orientation.LEFT_RIGHT, columns = false))
        } else {
            MultiSplitLayout(threeAppRoot(Orientation.LEFT_RIGHT, columns = true),
                threeColumnGroup = true, columnPairFirst = root.first is Split)
        }
    }

    private fun threeAppRoot(orientation: Orientation, columns: Boolean): Split {
        val ordered = getLeaves()
        val child = listOf(root.first, root.second).filterIsInstance<Split>().single()
        val childAxis = if (columns || orientation == Orientation.TOP_BOTTOM) {
            Orientation.LEFT_RIGHT
        } else Orientation.TOP_BOTTOM
        val pairFirst = !columns && columnPairFirst == true
        val pair = Split(child.id, childAxis, 0.5f,
            ordered[if (pairFirst) 0 else 1], ordered[if (pairFirst) 1 else 2])
        val single = ordered[if (pairFirst) 2 else 0]
        return Split(root.id, orientation, 0.5f,
            if (pairFirst) pair else single, if (pairFirst) single else pair)
    }

    /** Moves the complete single and paired sides, retaining the pair's internal order. */
    fun mirror(): MultiSplitLayout? {
        if (isThreeColumns || getLeaves().size != 3) return null
        return MultiSplitLayout(root.copy(first = root.second, second = root.first,
            ratio = 1f - root.ratio), strip, threeColumnGroup,
            if (threeColumnGroup) root.second is Split else null)
    }

    /** Updates only the selected divider's ratio; descendant ratios remain unchanged. */
    fun resize(id: DividerId, ratio: Float): MultiSplitLayout? {
        if (isThreeColumns || id !in dividers || !ratio.isFinite() || ratio <= 0f || ratio >= 1f) return null
        fun update(node: Node): Node = when (node) {
            is Leaf -> node
            is Split -> node.copy(
                ratio = if (node.id == id) ratio else node.ratio,
                first = update(node.first), second = update(node.second),
            )
        }
        return MultiSplitLayout(update(root) as Split, strip, threeColumnGroup, columnPairFirst)
    }

    /** A structural change without a surviving peek starts at the normal middle target. */
    fun normal(): MultiSplitLayout =
        MultiSplitLayout(root.copy(ratio = 0.5f), threeColumnGroup = threeColumnGroup,
            columnPairFirst = columnPairFirst)

    fun withRootRatio(ratio: Float, strip: LeafId? = null): MultiSplitLayout =
        MultiSplitLayout(root.copy(ratio = ratio), strip, threeColumnGroup, columnPairFirst)

    private fun replace(node: Node, id: LeafId, transform: (Leaf) -> Node): Node = when (node) {
        is Leaf -> if (node.id == id) transform(node) else node
        is Split -> node.copy(
            first = replace(node.first, id, transform),
            second = replace(node.second, id, transform),
        )
    }
}
