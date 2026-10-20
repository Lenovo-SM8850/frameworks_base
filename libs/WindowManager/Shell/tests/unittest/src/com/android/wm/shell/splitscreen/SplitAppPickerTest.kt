// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import android.content.res.Configuration
import android.content.ComponentName
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.UserHandle
import android.testing.TestableContext
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import androidx.test.annotation.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import java.util.Locale

@SmallTest
@RunWith(AndroidJUnit4::class)
class SplitAppPickerTest : ShellTestCase() {
    private val stable = Rect(0, 80, 1600, 1500)
    private val destination = Rect(800, 800, 1600, 1500)
    private val windows = mutableListOf<Pair<View, WindowManager.LayoutParams>>()
    private var cancelled = 0
    private var selected = 0

    private fun picker(hardwareKeyboard: Boolean = false, rtl: Boolean = false): SplitAppPicker {
        windows.clear()
        val config = Configuration(mContext.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
            keyboard = if (hardwareKeyboard) Configuration.KEYBOARD_QWERTY else Configuration.KEYBOARD_NOKEYS
            hardKeyboardHidden = if (hardwareKeyboard) Configuration.HARDKEYBOARDHIDDEN_NO
                else Configuration.HARDKEYBOARDHIDDEN_YES
            if (rtl) setLayoutDirection(Locale("ar"))
        }
        val local = TestableContext(mContext.createConfigurationContext(config))
        val manager = mock<WindowManager>()
        doAnswer {
            windows += (it.arguments[0] as View) to (it.arguments[1] as WindowManager.LayoutParams)
            null
        }.`when`(manager).addView(any(), any())
        local.addMockSystemService(WindowManager::class.java, manager)
        val host = object : android.content.ContextWrapper(local) {
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(
                super.getApplicationInfo()).apply {
                flags = flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
            }
        }
        return SplitAppPicker(host, stable, destination, { cancelled++ }, { selected++ })
            .also { it.show(destination) }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()

    private fun app(label: String, availability: SplitPickerPolicy.Availability) =
        SplitPickerController.App(ComponentName("test", label), UserHandle.of(10), label,
            "$label (work)", ColorDrawable(0xff000000.toInt()), availability, null, Int.MAX_VALUE)

    @Test @UiThreadTest fun panelIsFocusableOutlineIsInputlessAndBothStayInTheirBounds() {
        val picker = picker()
        val outline = windows[0].second
        val panel = windows[1].second
        assertThat(outline.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isNotEqualTo(0)
        assertThat(outline.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
        assertThat(panel.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isEqualTo(0)
        assertThat(panel.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH).isNotEqualTo(0)
        assertThat(stable.contains(Rect(panel.x, panel.y, panel.x + panel.width, panel.y + panel.height)))
            .isTrue()
        picker.close()
    }

    @Test @UiThreadTest fun searchFiltersDisabledProfileBadgedAppsWithoutLaunchingThem() {
        val picker = picker(rtl = true)
        picker.bind(listOf(app("Calendar", SplitPickerPolicy.Availability.ALREADY_OPEN),
            app("Clock", SplitPickerPolicy.Availability.AVAILABLE)))
        val root = windows[1].first
        assertThat(root.layoutDirection).isEqualTo(View.LAYOUT_DIRECTION_RTL)
        val disabled = descendants(root).first { it.contentDescription?.startsWith("Calendar (work)") == true }
        assertThat(disabled.isEnabled).isFalse()
        assertThat(disabled.contentDescription.toString()).contains("Already open")
        descendants(root).filterIsInstance<EditText>().single().setText(" cal ")
        assertThat(descendants(root).any { it.contentDescription?.startsWith("Clock (work)") == true }).isFalse()
        assertThat(descendants(root).any { it.contentDescription?.startsWith("Calendar (work)") == true }).isTrue()
        assertThat(selected).isEqualTo(0)
        picker.close()
    }

    @Test @UiThreadTest fun backAndOutsideTouchCancelSelection() {
        val picker = picker()
        val root = windows[1].first
        root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        assertThat(cancelled).isEqualTo(1)
        val outside = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, 0f, 0f, 0)
        root.dispatchTouchEvent(outside)
        outside.recycle()
        assertThat(cancelled).isEqualTo(2)
        picker.close()
    }

    @Test @UiThreadTest fun onlyAttachedHardwareKeyboardGetsInitialSearchFocus() {
        val touch = picker()
        assertThat(descendants(windows[1].first).filterIsInstance<EditText>().single().isFocused).isFalse()
        touch.close()
        val keyboard = picker(hardwareKeyboard = true)
        assertThat(descendants(windows[1].first).filterIsInstance<EditText>().single().isFocused).isTrue()
        keyboard.close()
    }
    @Test @UiThreadTest fun singleRecentUsesSameCellWidthAsAllAppsIncludingAfterSearchAndInRtl() {
        for (rtl in listOf(false, true)) {
            val picker = picker(rtl = rtl)
            val recent = app("Recent", SplitPickerPolicy.Availability.AVAILABLE)
                .copy(recentTaskId = 50, recentOrder = 0)
            picker.bind(listOf(recent) + (1..5).map {
                app("Other $it", SplitPickerPolicy.Availability.AVAILABLE)
            })
            val root = windows[1].first
            val params = windows[1].second
            fun measure() {
                root.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, params.width, params.height)
            }
            measure()
            var grids = descendants(root).filterIsInstance<android.widget.GridLayout>()
            assertThat(grids).hasSize(2)
            assertThat(grids[0].columnCount).isEqualTo(grids[1].columnCount)
            val width = grids[1].getChildAt(0).width
            assertThat(width).isGreaterThan(0)
            assertThat(grids[0].getChildAt(0).width).isEqualTo(width)
            assertThat(grids[0].getChildAt(0).width).isLessThan(grids[0].width)
            descendants(root).filterIsInstance<EditText>().single().setText("Recent")
            measure()
            grids = descendants(root).filterIsInstance<android.widget.GridLayout>()
            assertThat(grids[0].childCount).isEqualTo(1)
            assertThat(grids[1].childCount).isEqualTo(1)
            assertThat(grids[0].getChildAt(0).width).isEqualTo(width)
            assertThat(grids[1].getChildAt(0).width).isEqualTo(width)
            picker.close()
        }
    }

}
