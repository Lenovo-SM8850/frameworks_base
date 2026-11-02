// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.windowdecor

import android.graphics.PointF
import android.graphics.Rect
import android.testing.TestableLooper
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.R
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.shared.animation.PhysicsAnimator
import com.android.wm.shell.shared.animation.PhysicsAnimatorTestUtils
import com.android.wm.shell.shared.magnetictarget.MagnetizedObject
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidJUnit4::class)
class FloatingDismissTargetTest : ShellTestCase() {
    @Before fun setUp() {
        PhysicsAnimatorTestUtils.prepareForTest()
        PhysicsAnimatorTestUtils.setAllAnimationsBlock(true)
    }

    @After fun tearDown() { PhysicsAnimatorTestUtils.tearDown() }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: FloatingDismissTarget, name: String): T =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T

    private inner class Harness(val original: Rect) : AutoCloseable {
        val target = FloatingDismissTarget(mContext)
        val magnet = field<MagnetizedObject<FloatingDismissTarget>>(target, "magnet")
        var moved: Rect? = null
        private var time = 0L
        val destination = MagnetizedObject.MagneticTarget(mock<View>().apply {
            whenever(context).thenReturn(mContext)
            whenever(width).thenReturn(80)
            whenever(height).thenReturn(80)
            doAnswer {
                it.getArgument<IntArray>(0).apply { this[0] = 460; this[1] = 1860 }
                null
            }.whenever(this).getLocationOnScreen(any())
            doAnswer { it.getArgument<Runnable>(0).run(); true }.whenever(this).post(any())
        }, 100)

        init {
            field<Rect>(target, "bounds").set(original)
            field<Rect>(target, "originalBounds").set(original)
            field<PointF>(target, "down").set(500f, 500f)
            val move: (Rect) -> Unit = { moved = Rect(it) }
            target.javaClass.getDeclaredField("move").apply { isAccessible = true }.set(target, move)
            magnet.hapticsEnabled = false
            magnet.addTarget(destination)
        }

        fun event(action: Int, x: Float, y: Float): Boolean {
            time += 100
            val bounds = Rect(original).apply { offset((x - 500f).toInt(), (y - 500f).toInt()) }
            val event = MotionEvent.obtain(0, time, action, x, y, 0)
            return try { target.onMotionEvent(event, bounds) } finally { event.recycle() }
        }

        override fun close() { target.close() }
    }

    @Test fun floatingShrink_doesNotAnimateAnEqualEmptyPipRectangle() {
        val pipBounds = Rect()
        PhysicsAnimator.getInstance(pipBounds)
        Harness(Rect(100, 200, 700, 1100)).use { h ->
            h.magnet.animateStuckToTarget(h.destination, 0f, 0f, false, null)
            val expected = FloatingDismissTarget.dismissBounds(h.original, PointF(500f, 1900f),
                mContext.resources.getDimensionPixelSize(R.dimen.dismiss_circle_size))
            assertThat(h.moved).isEqualTo(expected)
            assertThat(expected.width()).isLessThan(h.original.width())
            assertThat(pipBounds).isEqualTo(Rect())
        }
    }

    @Test fun magnetEngagement_usesTheSameTouchDistanceForSmallAndLargeWindows() {
        for (bounds in listOf(Rect(0, 0, 360, 540), Rect(0, 0, 1200, 1800))) {
            Harness(bounds).use { h ->
                assertThat(h.event(MotionEvent.ACTION_DOWN, 500f, 500f)).isFalse()
                assertThat(h.event(MotionEvent.ACTION_MOVE, 500f, 1600f)).isFalse()
                assertThat(h.event(MotionEvent.ACTION_MOVE, 500f, 1799f)).isFalse()
                assertThat(h.moved).isNull()
                assertThat(h.event(MotionEvent.ACTION_MOVE, 500f, 1801f)).isTrue()
                assertThat(h.moved!!.width()).isLessThan(bounds.width())
            }
        }
    }
}
