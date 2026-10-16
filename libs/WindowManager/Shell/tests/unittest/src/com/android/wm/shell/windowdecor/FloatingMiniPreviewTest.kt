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
}
