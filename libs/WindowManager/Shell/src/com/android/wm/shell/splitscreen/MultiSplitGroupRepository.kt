// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import android.app.ActivityManager.RunningTaskInfo
import android.app.TaskInfo
import android.graphics.Insets
import android.graphics.Rect
import com.android.wm.shell.shared.split.SplitBounds
import com.android.wm.shell.shared.split.SplitScreenConstants.SNAP_TO_2_50_50
import com.android.wm.shell.splitscreen.MultiSplitLayout.Leaf
import com.android.wm.shell.splitscreen.MultiSplitLayout.LeafId
import com.android.wm.shell.splitscreen.MultiSplitLayout.Node
import com.android.wm.shell.splitscreen.MultiSplitLayout.Split
import java.util.function.Predicate

/** Shell-main-thread records; no extra task cardinality is added to the overview protocol. */
class MultiSplitGroupRepository(private val onChanged: Runnable) {
    data class Member(val taskId: Int, val userId: Int)
    data class Group(
        val layout: MultiSplitLayout,
        val stageIds: Map<LeafId, Int>,
        val members: List<Member>,
        val displayId: Int,
        val firstRepresentative: Int,
        val secondRepresentative: Int,
        val splitBounds: SplitBounds,
    ) {
        fun matches(tasks: Collection<TaskInfo>): Boolean = members.all { member ->
            tasks.any { it.taskId == member.taskId && it.userId == member.userId &&
                it.displayId == displayId }
        }
    }

    private val records = mutableMapOf<Int, Group>()
    private var validator = Predicate<Group> { true }

    fun setValidator(value: Predicate<Group>) { validator = value }

    fun peek(taskId: Int): Group? = records[taskId]

    fun get(taskId: Int): Group? {
        val group = records[taskId] ?: return null
        if (!validator.test(group)) {
            remove(taskId)
            return null
        }
        return group
    }

    /** Any removed/reused member invalidates the snapshot until a live collapse is published. */
    fun remove(taskId: Int) {
        val group = records[taskId] ?: return
        group.members.forEach { records.remove(it.taskId) }
        onChanged.run()
    }

    fun clearDisplay(displayId: Int) {
        records.values.toSet().filter { it.displayId == displayId }.forEach {
            remove(it.firstRepresentative)
        }
    }

    /** Visible-task lists and bounded recents queries need not contain background members. */
    fun validatePresent(tasks: Collection<TaskInfo>) {
        tasks.forEach { task ->
            val group = records[task.taskId] ?: return@forEach
            val member = group.members.first { it.taskId == task.taskId }
            if (member.userId != task.userId || group.displayId != task.displayId ||
                !validator.test(group)) remove(task.taskId)
        }
    }

    fun validate(tasks: Collection<TaskInfo>) {
        records.values.toSet().forEach {
            if (!it.matches(tasks) || !validator.test(it)) remove(it.firstRepresentative)
        }
    }

    /** Representatives remain stable while they remain in the same outer branch. */
    fun save(layout: MultiSplitLayout, stageIds: Map<LeafId, Int>, tasks: List<RunningTaskInfo>,
        displayBounds: Rect, dividerSize: Int): Group? {
        val leaves = layout.getLeaves()
        if (leaves.any { it.taskId < 0 } || stageIds.keys != leaves.map { it.id }.toSet() ||
            stageIds.values.distinct().size != leaves.size ||
            tasks.size != leaves.size || tasks.map { it.taskId }.toSet() !=
                leaves.map { it.taskId }.toSet() || tasks.map { it.displayId }.distinct().size != 1) {
            return null
        }
        val calculated = layout.calculateBounds(displayBounds, Insets.NONE, dividerSize) ?: return null
        fun ids(node: Node): List<Int> = when (node) {
            is Leaf -> listOf(node.taskId)
            is Split -> ids(node.first) + ids(node.second)
        }
        val first = ids(layout.root.first)
        val second = ids(layout.root.second)
        val previous = leaves.firstNotNullOfOrNull { records[it.taskId] }
        val firstRep = previous?.firstRepresentative?.takeIf { it in first } ?: first.first()
        val secondRep = previous?.secondRepresentative?.takeIf { it in second } ?: second.first()
        fun branchBounds(node: Node): Rect = when (node) {
            is Leaf -> Rect(calculated.leaves.getValue(node.id))
            is Split -> branchBounds(node.first).apply { union(branchBounds(node.second)) }
        }
        val group = Group(layout, stageIds.toMap(), tasks.map { Member(it.taskId, it.userId) },
            tasks.first().displayId, firstRep, secondRep,
            SplitBounds(branchBounds(layout.root.first), branchBounds(layout.root.second),
                firstRep, secondRep, first, second, SNAP_TO_2_50_50))
        if (!validator.test(group)) return null
        if (previous != null && previous.layout.root == group.layout.root &&
            previous.stageIds == group.stageIds && previous.members == group.members &&
            previous.splitBounds == group.splitBounds) return previous
        leaves.mapNotNull { records[it.taskId] }.toSet().forEach {
            it.members.forEach { member -> records.remove(member.taskId) }
        }
        group.members.forEach { records[it.taskId] = group }
        onChanged.run()
        return group
    }
}
