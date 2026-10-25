// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.desktopmode

import android.app.ActivityManager.RunningTaskInfo
import android.app.KeyguardManager
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.graphics.Rect
import android.os.Binder
import android.window.IWindowContainerToken
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.policy.DesktopModeCompatPolicy
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.LockTaskChangeListener
import com.android.wm.shell.pinnedlayer.phone.PinnedLayerController
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.kotlin.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class SplitLeafFloatingTest {
    private val split = mock<SplitScreenController>()
    private val organizer = mock<ShellTaskOrganizer>()
    private val transitions = mock<Transitions>()
    private val floating = mock<PinnedLayerController>()
    // Exercise the entry methods without creating an unrelated desktop session or user fixture.
    private val controller = mock<DesktopTasksController>(defaultAnswer = CALLS_REAL_METHODS)
    private val task = RunningTaskInfo().apply {
        taskId = 42
        configuration.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN)
        val container = mock<IWindowContainerToken>()
        whenever(container.asBinder()).thenReturn(Binder())
        token = WindowContainerToken(container)
    }

    private fun inject(name: String, value: Any) {
        DesktopTasksController::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(controller, value)
        }
    }

    @Before fun setup() {
        inject("splitScreenController", split)
        inject("shellTaskOrganizer", organizer)
        inject("transitions", transitions)
        inject("lockTaskChangeListener", mock<LockTaskChangeListener>())
        inject("keyguardManager", mock<KeyguardManager>())
        inject("desktopModeCompatPolicy", mock<DesktopModeCompatPolicy>())
        controller.setPinnedLayerController(floating)
        whenever(split.isTaskInSplitScreen(task.taskId)).thenReturn(true)
        whenever(split.allTaskInfos).thenReturn(arrayOf(task))
        whenever(organizer.getRunningTaskInfo(task.taskId)).thenReturn(task)
    }

    @Test fun splitLeafToFloatingUsesCollapseInsteadOfThePairExit() {
        val wct = WindowContainerTransaction()
        whenever(split.prepareSplitLeafToFloating(task.taskId, wct)).thenReturn(true)
        controller.addExitSplitForFloating(wct, task)
        verify(split).prepareSplitLeafToFloating(task.taskId, wct)
        verify(split, never()).prepareExitSplitScreen(any(), anyInt(), anyInt())
    }

    @Test fun pairToFloatingRetainsTheExistingExitPath() {
        val wct = WindowContainerTransaction()
        controller.addExitSplitForFloating(wct, task)
        verify(split).prepareExitSplitScreen(eq(wct), anyInt(),
            eq(SplitScreenController.EXIT_REASON_DESKTOP_MODE))
    }

    @Test fun captionFloatingMergesLeafRemovalWithFloatingProperties() {
        val floatingBounds = Rect(10, 20, 410, 620)
        whenever(floating.prepareFloatingTask(eq(task), any())).thenAnswer {
            (it.arguments[1] as WindowContainerTransaction).setBounds(task.token, floatingBounds)
            true
        }
        whenever(split.prepareSplitLeafToFloating(eq(task.taskId), any())).thenReturn(true)
        assertThat(controller.moveToFloating(task.taskId)).isTrue()
        verify(split).prepareSplitLeafToFloating(eq(task.taskId), any())
        verify(split, never()).prepareExitSplitScreen(any(), anyInt(), anyInt())
        val wct = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(eq(android.view.WindowManager.TRANSIT_CHANGE),
            wct.capture(), isNull())
        assertThat(wct.firstValue.changes[task.token.asBinder()]!!
            .configuration.windowConfiguration.bounds).isEqualTo(floatingBounds)
    }
}
