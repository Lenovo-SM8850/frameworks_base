// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.graphics.Rect
import android.view.SurfaceControl
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.desktopmode.VisualIndicatorViewContainer
import com.android.wm.shell.pinnedlayer.phone.FloatingMiniController.DragTarget
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

@SmallTest
@RunWith(AndroidJUnit4::class)
class FloatingMiniPreviewTest : ShellTestCase() {
    @Test fun targetChanges_fadeWithoutRepeatedUpdates_andCancelReleases() {
        val container = mock<VisualIndicatorViewContainer>()
        val preview = FloatingMiniPreview { _, _ -> container }
        val task = TestRunningTaskInfoBuilder().build()
        val leash = mock<SurfaceControl>()
        val targets = listOf(Rect(8, 108, 368, 648), Rect(832, 108, 1192, 648),
            Rect(8, 1352, 368, 1892), Rect(832, 1352, 1192, 1892))
        preview.update(task, leash, null)
        verifyNoInteractions(container)
        targets.forEach { bounds ->
            val target = DragTarget(bounds, .45f, 24f)
            preview.update(task, leash, target)
            verify(container).updateMiniPreview(bounds, 24f)
            clearInvocations(container)
            preview.update(task, leash, target)
            verifyNoInteractions(container)
            preview.update(task, leash, null)
            verify(container).updateMiniPreview(null, 0f)
            clearInvocations(container)
        }
        preview.update(task, leash, DragTarget(targets[0], .45f, 24f))
        preview.close()
        verify(container).releaseVisualIndicator()
        clearInvocations(container)
        preview.update(task, leash, null)
        verifyNoInteractions(container)
    }

    @Test fun feedback_tracksSemanticCornerRatherThanBoundsAndResetsOnClose() {
        val preview = FloatingMiniPreview { _, _ -> null }
        val task = TestRunningTaskInfoBuilder().build()
        var cues = 0
        val target = DragTarget(Rect(8, 108, 368, 648), .45f, 24f,
            com.android.wm.shell.pinnedlayer.phone.FloatingMiniState.Corner.TOP_LEFT)
        preview.updateFeedback(task, target, true) { cues++ }
        preview.updateFeedback(task, target.copy(bounds = Rect(8, 656, 368, 1196)), true) { cues++ }
        org.junit.Assert.assertEquals(1, cues)
        preview.updateFeedback(task, null, true) { cues++ }
        preview.updateFeedback(task, target, true) { cues++ }
        org.junit.Assert.assertEquals(2, cues)
        preview.close()
        preview.updateFeedback(task, target, true) { cues++ }
        org.junit.Assert.assertEquals(3, cues)
    }

    @Test fun feedback_nonTouchIsSilentAndTargetsRearmOnlyAfterExit() {
        val state = FloatingTargetFeedback()
        var cues = 0
        state.update("left", false) { cues++ }
        state.update("left", true) { cues++ }
        org.junit.Assert.assertEquals(0, cues)
        state.update(null, true) { cues++ }
        state.update("left", true) { cues++ }
        repeat(10) { state.update("left", true) { cues++ } }
        org.junit.Assert.assertEquals(1, cues)
        state.update("right", true) { cues++ }
        org.junit.Assert.assertEquals(2, cues)
        state.reset()
        state.update("right", true) { cues++ }
        org.junit.Assert.assertEquals(3, cues)
    }
}
