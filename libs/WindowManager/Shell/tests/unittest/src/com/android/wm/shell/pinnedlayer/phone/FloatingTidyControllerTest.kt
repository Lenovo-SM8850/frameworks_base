// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.os.Binder
import android.testing.TestableLooper
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_BACK
import android.window.TransitionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.TaskStackListenerCallback
import com.android.wm.shell.common.TaskStackListenerImpl
import com.android.wm.shell.recents.RecentsTransitionHandler
import com.android.wm.shell.recents.RecentsTransitionStateListener
import com.android.wm.shell.sysui.KeyguardChangeListener
import com.android.wm.shell.sysui.OverviewVisibilityChangeListener
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidJUnit4::class)
class FloatingTidyControllerTest : ShellTestCase() {
    private class Harness {
        val stack = mock<TaskStackListenerImpl>()
        val recents = mock<RecentsTransitionHandler>()
        val shell = mock<ShellController>()
        val transitions = mock<Transitions>()
        val home = task(ACTIVITY_TYPE_HOME)
        val app = task(ACTIVITY_TYPE_STANDARD)
        var tasks = listOf(home)
        var interactive = true
        var tidies = 0
        var gestural = true
        var keyguardLocked = false
        var expanded = true
        var registrations = 0
        var disposals = 0
        val monitor = FloatingTidyGestureMonitor {
            registrations++
            AutoCloseable { disposals++ }
        }
        val controller: FloatingTidyController
        var tidyTasks = emptyList<RunningTaskInfo>()
        val idle = ArrayDeque<Runnable>()
        val stackListener: TaskStackListenerCallback
        val recentsListener: RecentsTransitionStateListener
        val keyguard: KeyguardChangeListener
        val overview: OverviewVisibilityChangeListener
        val observer: Transitions.TransitionObserver

        init {
            doAnswer { idle.add(it.getArgument(0)); null }.whenever(transitions).runOnIdle(any())
            controller = FloatingTidyController(stack, recents, shell, transitions, { interactive },
                { tidies++; tidyTasks = it }, { tasks },
                { gestural && expanded && !keyguardLocked }, monitor::setEnabled)
            controller.updateGestureMonitoring()
            val a = argumentCaptor<TaskStackListenerCallback>()
            verify(stack).addListener(a.capture()); stackListener = a.firstValue
            val b = argumentCaptor<RecentsTransitionStateListener>()
            verify(recents).addTransitionStateListener(b.capture()); recentsListener = b.firstValue
            val c = argumentCaptor<KeyguardChangeListener>()
            verify(shell).addKeyguardChangeListener(c.capture()); keyguard = c.firstValue
            val d = argumentCaptor<OverviewVisibilityChangeListener>()
            verify(shell).addOverviewVisibilityChangeListener(d.capture()); overview = d.firstValue
            val e = argumentCaptor<Transitions.TransitionObserver>()
            verify(transitions).registerObserver(e.capture()); observer = e.firstValue
        }

        fun homePress(wasVisible: Boolean = true) =
            stackListener.onActivityRestartAttempt(home, true, false, wasVisible)
        fun startGesture() = recentsListener.onTransitionStateChanged(2, 0)
        fun finishHome() {
            recentsListener.onTransitionStateChanged(1, 0)
            recentsListener.onTransitionFinishedToHome(0)
            recentsListener.onHomeGestureFinished(0)
        }
        fun drain() { while (idle.isNotEmpty()) idle.removeFirst().run() }

        companion object {
            fun task(type: Int) = RunningTaskInfo().apply {
                displayId = 0
                isVisible = true
                configuration.windowConfiguration.setActivityType(type)
                configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN
            }
        }
    }

    @Test fun monitorOnlyWhileGesturalHomeWithExpandedWindows() {
        val h = Harness()
        assertThat(h.registrations).isEqualTo(1)
        h.controller.updateGestureMonitoring()
        assertThat(h.registrations).isEqualTo(1)
        h.gestural = false
        h.controller.updateGestureMonitoring()
        assertThat(h.disposals).isEqualTo(1)
        h.gestural = true
        h.controller.updateGestureMonitoring()
        assertThat(h.registrations).isEqualTo(2)
        h.expanded = false
        h.controller.updateGestureMonitoring()
        assertThat(h.disposals).isEqualTo(2)
        h.expanded = true
        h.controller.updateGestureMonitoring()
        h.tasks = listOf(h.app, h.home)
        h.stackListener.onTaskMovedToFront(h.app)
        assertThat(h.disposals).isEqualTo(3)
        h.tasks = listOf(h.home)
        h.stackListener.onTaskStackChanged()
        assertThat(h.registrations).isEqualTo(4)
        h.tasks = listOf(h.home.apply { displayId = 1 })
        h.stackListener.onTaskStackChanged()
        assertThat(h.disposals).isEqualTo(4)
        h.home.displayId = 0
        h.stackListener.onTaskStackChanged()
        assertThat(h.registrations).isEqualTo(5)
        h.home.isVisible = false
        h.stackListener.onTaskStackChanged()
        assertThat(h.disposals).isEqualTo(5)
    }

    @Test fun monitorStopsForKeyguardOverviewRecentsAndScreenOff() {
        val h = Harness()
        h.keyguard.onKeyguardVisibilityChanged(true, false, false)
        assertThat(h.disposals).isEqualTo(1)
        h.keyguard.onKeyguardVisibilityChanged(false, false, false)
        h.overview.onOverviewShown(0)
        assertThat(h.disposals).isEqualTo(2)
        h.overview.onOverviewHidden(0)
        assertThat(h.registrations).isEqualTo(3)
        h.startGesture()
        assertThat(h.disposals).isEqualTo(3)
        h.recentsListener.onTransitionStateChanged(1, 0)
        assertThat(h.registrations).isEqualTo(4)
        h.interactive = false
        h.controller.updateGestureMonitoring()
        assertThat(h.disposals).isEqualTo(4)
        h.interactive = true
        h.controller.updateGestureMonitoring()
        assertThat(h.registrations).isEqualTo(5)
        h.drain()
        assertThat(h.tidies).isEqualTo(0)
    }

    @Test fun unlockCompletionRechecksInitialKeyguardGateWithoutTidying() {
        val h = Harness()
        h.keyguardLocked = true
        h.keyguard.onKeyguardVisibilityChanged(true, false, false)
        assertThat(h.disposals).isEqualTo(1)
        h.keyguard.onKeyguardVisibilityChanged(false, false, true)
        assertThat(h.registrations).isEqualTo(1)
        h.keyguardLocked = false
        h.keyguard.onKeyguardDismissAnimationFinished()
        assertThat(h.registrations).isEqualTo(2)
        h.drain()
        assertThat(h.tidies).isEqualTo(0)
    }

    @Test fun localHomeSwipeUsesHomeRepeatDebounce() {
        val h = Harness()
        h.controller.onHomeSwipeUp()
        h.homePress()
        h.drain()
        assertThat(h.tidies).isEqualTo(1)
    }

    @Test fun recentsStartCancelsQueuedLocalHomeSwipe() {
        val h = Harness()
        h.controller.onHomeSwipeUp()
        h.startGesture()
        h.controller.onHomeSwipeUp()
        h.drain()
        assertThat(h.tidies).isEqualTo(0)
        assertThat(h.disposals).isEqualTo(1)
    }

    @Test fun localHomeSwipeCannotRunAfterEligibilityChanges() {
        val h = Harness()
        h.expanded = false
        h.controller.onHomeSwipeUp()
        h.expanded = true
        h.gestural = false
        h.controller.onHomeSwipeUp()
        h.gestural = true
        h.tasks = listOf(h.app, h.home)
        h.controller.onHomeSwipeUp()
        h.drain()
        assertThat(h.tidies).isEqualTo(0)
    }

    @Test fun monitorStopsDuringAppTransitionAndResumesAfterIt() {
        val h = Harness()
        val token = Binder()
        val info = TransitionInfo(TRANSIT_OPEN, 0).apply {
            addChange(TransitionInfo.Change(mock(), mock()).apply {
                taskInfo = h.app
                mode = TRANSIT_OPEN
            })
        }
        h.observer.onTransitionReady(token, info, mock(), mock())
        assertThat(h.disposals).isEqualTo(1)
        h.controller.onHomeSwipeUp()
        h.observer.onTransitionFinished(token, false)
        assertThat(h.registrations).isEqualTo(2)
        h.drain()
        assertThat(h.tidies).isEqualTo(0)
    }

    @Test fun homePressWhileAlreadyHomeTidiesAfterIdle() {
        val h = Harness()
        h.homePress()
        assertThat(h.tidies).isEqualTo(0)
        h.drain()
        assertThat(h.tidies).isEqualTo(1)
    }

    @Test fun firstHomeFromAppDoesNotTidy_secondHomeDoes() {
        val h = Harness()
        h.tasks = listOf(h.app)
        h.homePress(false)
        h.tasks = listOf(h.home)
        h.drain()
        assertThat(h.tidies).isEqualTo(0)
        h.homePress()
        h.drain()
        assertThat(h.tidies).isEqualTo(1)
    }

    @Test fun gestureStartingAtHomeTidies_firstGestureFromAppDoesNot() {
        val h = Harness()
        h.tasks = listOf(h.app)
        h.startGesture()
        h.tasks = listOf(h.home)
        h.finishHome()
        h.drain()
        assertThat(h.tidies).isEqualTo(0)
        h.startGesture()
        h.finishHome()
        h.drain()
        assertThat(h.tidies).isEqualTo(1)
    }

    @Test fun overviewInvalidatesHomeGestureAndDoesNotTidy() {
        val h = Harness()
        h.startGesture()
        h.overview.onOverviewShown(0)
        h.overview.onOverviewHidden(0)
        h.finishHome()
        h.drain()
        assertThat(h.tidies).isEqualTo(0)
    }

    @Test fun screenOffAndKeyguardChangesNeverTidy() {
        val h = Harness()
        h.interactive = false
        h.homePress()
        h.interactive = true
        h.keyguard.onKeyguardVisibilityChanged(true, false, false)
        h.homePress()
        h.keyguard.onKeyguardVisibilityChanged(false, false, false)
        h.drain()
        assertThat(h.tidies).isEqualTo(0)
    }

    @Test fun fullscreenAppInFrontAndAppLaunchFromHomeDoNotTidy() {
        val h = Harness()
        h.tasks = listOf(h.app, h.home)
        h.homePress()
        h.drain()
        assertThat(h.tidies).isEqualTo(0)
        h.tasks = listOf(h.home)
        h.homePress()
        h.tasks = listOf(h.app, h.home)
        h.stackListener.onTaskMovedToFront(h.app)
        h.drain()
        assertThat(h.tidies).isEqualTo(0)
    }

    @Test fun arrivingAppTransitionCancelsQueuedHomeRepeat() {
        listOf(TRANSIT_OPEN, TRANSIT_TO_BACK).forEach { transitionMode ->
            val h = Harness()
            h.homePress()
            val token = Binder()
            val info = TransitionInfo(transitionMode, 0).apply {
                addChange(TransitionInfo.Change(mock(), mock()).apply {
                    taskInfo = h.app
                    mode = transitionMode
                })
            }
            h.observer.onTransitionReady(token, info, mock(), mock())
            h.observer.onTransitionFinished(token, false)
            h.drain()
            assertThat(h.tidies).isEqualTo(0)
            h.homePress()
            h.drain()
            assertThat(h.tidies).isEqualTo(1)
        }
    }

    @Test fun visibleFloatingTaskAboveHomeDoesNotCountAsFullscreenApp() {
        val h = Harness()
        val floating = Harness.task(ACTIVITY_TYPE_STANDARD).apply { isFloatingWindow = true }
        h.tasks = listOf(floating, h.home)
        h.homePress()
        h.drain()
        assertThat(h.tidies).isEqualTo(1)
    }
    @Test fun finishingHomeReadsFreshFloatingVisibilityAfterIdle() {
        val h = Harness()
        val floating = Harness.task(ACTIVITY_TYPE_STANDARD).apply {
            isFloatingWindow = true
            isVisible = false
        }
        h.tasks = listOf(floating, h.home)
        h.startGesture()
        h.finishHome()
        floating.isVisible = true
        h.drain()
        assertThat(h.tidyTasks).contains(floating)
        assertThat(h.tidyTasks.first().isVisible).isTrue()
    }

    @Test fun overviewFinishWithoutHomeUserLeaveHintDoesNotTidy() {
        val h = Harness()
        h.startGesture()
        h.recentsListener.onTransitionStateChanged(1, 0)
        h.recentsListener.onTransitionFinishedToHome(0)
        h.drain()
        assertThat(h.tidies).isEqualTo(0)
    }

    @Test fun homePressFromOverviewReturnsHome_beforeAnotherHomeCanTidy() {
        val h = Harness()
        h.overview.onOverviewShown(0)
        h.overview.onOverviewHidden(0)
        h.homePress()
        h.drain()
        assertThat(h.tidies).isEqualTo(0)
        h.homePress()
        h.drain()
        assertThat(h.tidies).isEqualTo(1)
    }

    @Test fun ambiguousLauncherFinishRequiresAnotherHomeBeforeTidy() {
        val h = Harness()
        h.startGesture()
        h.recentsListener.onTransitionStateChanged(1, 0)
        h.recentsListener.onTransitionFinishedToHome(0)
        h.homePress()
        h.drain()
        assertThat(h.tidies).isEqualTo(0)
        h.homePress()
        h.drain()
        assertThat(h.tidies).isEqualTo(1)
    }

}
