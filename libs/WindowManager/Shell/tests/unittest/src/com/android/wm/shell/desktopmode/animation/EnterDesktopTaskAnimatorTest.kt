// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.desktopmode.animation

import android.animation.AnimatorTestRule
import android.graphics.Rect
import android.testing.TestableLooper
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.window.TransitionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.windowdecor.OnTaskResizeAnimationListener
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidJUnit4::class)
class EnterDesktopTaskAnimatorTest : ShellTestCase() {
    @JvmField @Rule val animatorRule = AnimatorTestRule(this)

}
