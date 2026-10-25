// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import android.app.ActivityManager.RunningTaskInfo
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.splitscreen.MultiSplitLayout.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class MultiSplitGroupRepositoryTest : ShellTestCase() {
    private val groups = MultiSplitGroupRepository(Runnable {})
    private val tasks = (10..13).map { id -> RunningTaskInfo().apply {
        taskId = id
        userId = 0
        displayId = 0
    } }
    private val model = MultiSplitLayout(Split(DividerId(0), Orientation.LEFT_RIGHT, 0.4f,
        Split(DividerId(1), Orientation.TOP_BOTTOM, 0.3f, Leaf(LeafId(0), 10), Leaf(LeafId(1), 11)),
        Split(DividerId(2), Orientation.TOP_BOTTOM, 0.7f, Leaf(LeafId(2), 12), Leaf(LeafId(3), 13))))
    private val stageIds = (0..3).associate { LeafId(it) to (100 + it) }

    private fun save(layout: MultiSplitLayout = model) = groups.save(layout, stageIds.filterKeys { id -> layout.getLeaves().any { it.id == id } },
        tasks.filter { task -> layout.getLeaves().any { it.taskId == task.taskId } },
        Rect(0, 0, 3200, 2400), 8)!!

    @Test fun columnSnapshotsKeepBothOriginsThroughPortraitAndLandscapeRestore() {
        val three = MultiSplitLayout(Split(DividerId(0), Orientation.LEFT_RIGHT, 0.5f,
            Split(DividerId(1), Orientation.TOP_BOTTOM, 0.5f,
                Leaf(LeafId(0), 10), Leaf(LeafId(1), 11)), Leaf(LeafId(2), 12)))
        for (source in listOf(three, three.mirror()!!)) {
            val columns = source.rotateThreeAppShape()!!
            save(columns)
            val restored = groups.get(10)!!.layout
            assertThat(restored.columnPairFirst).isEqualTo(source.root.first is Split)
            assertThat(restored.rotateThreeAppShape()!!.root).isEqualTo(source.root)
            save(restored.reorient(Orientation.TOP_BOTTOM))
            val portrait = groups.get(10)!!.layout
            assertThat(portrait.root.first is Split).isEqualTo(source.root.first is Split)
            assertThat(portrait.columnPairFirst).isEqualTo(restored.columnPairFirst)
            assertThat(portrait.reorient(Orientation.LEFT_RIGHT).rotateThreeAppShape()!!.root)
                .isEqualTo(source.root)
        }
    }

    @Test fun columnOriginChangeUpdatesSnapshotEvenWhenColumnGeometryIsIdentical() {
        val columns = model.remove(LeafId(3))!!.rotateThreeAppShape()!!
        val first = save(columns)
        val changed = MultiSplitLayout(columns.root, threeColumnGroup = true,
            columnPairFirst = columns.columnPairFirst != true)
        val second = save(changed)
        assertThat(second).isNotSameInstanceAs(first)
        assertThat(groups.get(10)!!.layout.columnPairFirst).isEqualTo(changed.columnPairFirst)
        val unknown = save(MultiSplitLayout(columns.root, threeColumnGroup = true))
        assertThat(unknown.layout.columnPairFirst).isNull()
        assertThat(unknown.layout.rotateThreeAppShape()!!.root.first).isInstanceOf(Leaf::class.java)
    }

    @Test fun everyMemberResolvesOneSnapshotAndOuterBranchMetadata() {
        val group = save()
        tasks.forEach { assertThat(groups.get(it.taskId)).isSameInstanceAs(group) }
        assertThat(group.firstRepresentative).isEqualTo(10)
        assertThat(group.secondRepresentative).isEqualTo(12)
        assertThat(group.splitBounds.leftTopTaskIds).containsExactly(10, 11)
        assertThat(group.splitBounds.rightBottomTaskIds).containsExactly(12, 13)
        assertThat(group.splitBounds.leftTopBounds).isEqualTo(Rect(0, 0, 1277, 2400))
        assertThat(group.splitBounds.rightBottomBounds).isEqualTo(Rect(1285, 0, 3200, 2400))
    }

    @Test fun representativesSurviveResizeAndNonRepresentativeRemoval() {
        save()
        val resized = model.resize(DividerId(1), 0.6f)!!
        val group = save(resized.remove(LeafId(1))!!)
        assertThat(group.firstRepresentative).isEqualTo(10)
        assertThat(group.secondRepresentative).isEqualTo(12)
        assertThat(groups.get(11)).isNull()
        assertThat(group.members).hasSize(3)
    }

    @Test fun taskRemovalOrUserDisplayMismatchInvalidatesAllMembership() {
        save()
        groups.remove(11)
        tasks.forEach { assertThat(groups.get(it.taskId)).isNull() }
        save()
        tasks[0].userId = 10
        groups.validate(tasks)
        assertThat(groups.get(12)).isNull()
        tasks[0].userId = 0
        save()
        tasks[1].displayId = 1
        groups.validate(tasks)
        assertThat(groups.get(10)).isNull()
    }

    @Test fun incompleteListAndCapacityChangeFallBackWithoutASplitRecord() {
        save()
        groups.validate(tasks.take(3))
        assertThat(groups.get(10)).isNull()
        save()
        groups.setValidator { false }
        assertThat(groups.get(13)).isNull()
        assertThat(groups.peek(10)).isNull()
    }
    @Test fun missingExtraOrDuplicateStageAssignmentsAreNotSaved() {
        assertThat(groups.save(model, stageIds - LeafId(0), tasks,
            Rect(0, 0, 3200, 2400), 8)).isNull()
        assertThat(groups.save(model, stageIds + (LeafId(9) to 109), tasks,
            Rect(0, 0, 3200, 2400), 8)).isNull()
        assertThat(groups.save(model, stageIds.mapValues { 100 }, tasks,
            Rect(0, 0, 3200, 2400), 8)).isNull()
    }

}
