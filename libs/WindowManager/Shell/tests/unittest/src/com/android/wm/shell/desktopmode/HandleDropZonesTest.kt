// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.desktopmode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.desktopmode.HandleDropZones.FloatingZone
import com.android.wm.shell.desktopmode.HandleDropZones.Target
import com.android.wm.shell.desktopmode.HandleDropZones.Zone
import com.android.wm.shell.pinnedlayer.phone.FloatingMiniState.Corner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class HandleDropZonesTest {
    private val sizes = HandleDropZones.Sizes(corner = 176, splitEdge = 128)

    private fun pick(x: Float, y: Float, allowSplit: Boolean = true, allowMini: Boolean = true) =
        HandleDropZones.pick(x, y, WIDTH, HEIGHT, TOP, sizes, allowSplit, allowMini)

    @Test
    fun corners_pickMini() {
        assertEquals(Target(Zone.MINI, Corner.TOP_LEFT), pick(10f, 10f))
        assertEquals(Target(Zone.MINI, Corner.TOP_RIGHT), pick(2550f, 175f))
        assertEquals(Target(Zone.MINI, Corner.BOTTOM_LEFT), pick(175f, 1590f))
        assertEquals(Target(Zone.MINI, Corner.BOTTOM_RIGHT), pick(2384f, 1424f))
    }

    @Test
    fun cornerSquares_endAtCornerSize() {
        assertEquals(Zone.FULLSCREEN, pick(176f, 10f).zone)
        assertEquals(Zone.FULLSCREEN, pick(2383f, 10f).zone)
        assertEquals(Zone.SPLIT_LEFT, pick(10f, 176f).zone)
        assertEquals(Zone.SPLIT_RIGHT, pick(2550f, 1423f).zone)
    }

    @Test
    fun cornerSquares_winOverSplitAndTopBands() {
        assertEquals(Zone.MINI, pick(10f, 50f).zone)
        assertEquals(Zone.MINI, pick(2550f, 50f).zone)
        assertEquals(Zone.MINI, pick(-20f, 1500f).zone)
    }

    @Test
    fun sideBands_pickSplitBetweenCorners() {
        assertEquals(Zone.SPLIT_LEFT, pick(127f, 800f).zone)
        assertEquals(Zone.SPLIT_RIGHT, pick(2432f, 800f).zone)
        assertEquals(Zone.FLOATING, pick(128f, 800f).zone)
        assertEquals(Zone.FLOATING, pick(2431f, 800f).zone)
    }

    @Test
    fun offscreenPointer_picksSplit() {
        assertEquals(Zone.SPLIT_LEFT, pick(-20f, 800f).zone)
        assertEquals(Zone.SPLIT_RIGHT, pick(2600f, 800f).zone)
    }

    @Test
    fun topBand_picksFullscreenBetweenCorners() {
        assertEquals(Zone.FULLSCREEN, pick(1280f, 0f).zone)
        assertEquals(Zone.FULLSCREEN, pick(1280f, 99f).zone)
        assertEquals(Zone.FLOATING, pick(1280f, 100f).zone)
    }

    @Test
    fun betweenSplitBandAndCorner_belowCorner_picksFloating() {
        assertEquals(Zone.FLOATING, pick(150f, 800f).zone)
        assertEquals(Zone.FLOATING, pick(150f, 1300f).zone)
    }

    @Test
    fun center_picksFloating() {
        assertEquals(Target(Zone.FLOATING), pick(1280f, 800f))
    }

    @Test
    fun withoutSplit_sideBandsFallThrough() {
        assertEquals(Zone.FLOATING, pick(10f, 800f, allowSplit = false).zone)
        assertEquals(Zone.FLOATING, pick(-20f, 800f, allowSplit = false).zone)
        assertEquals(Zone.MINI, pick(10f, 10f, allowSplit = false).zone)
    }

    @Test
    fun withoutMini_bandsSpanDisplay() {
        assertEquals(Zone.SPLIT_LEFT, pick(10f, 10f, allowMini = false).zone)
        assertEquals(Zone.SPLIT_RIGHT, pick(2550f, 1590f, allowMini = false).zone)
        assertEquals(Zone.FULLSCREEN, pick(150f, 10f, allowMini = false).zone)
        assertEquals(Zone.FLOATING, pick(150f, 1590f, allowMini = false).zone)
    }

    @Test
    fun withoutSplitOrMini_topBandSpansDisplay() {
        assertEquals(Zone.FULLSCREEN, pick(10f, 10f, allowSplit = false, allowMini = false).zone)
        assertEquals(Zone.FLOATING, pick(10f, 800f, allowSplit = false, allowMini = false).zone)
    }

    private fun pickFloating(x: Float, y: Float) =
        HandleDropZones.pickFloating(x, y, WIDTH, TOP)

    @Test
    fun floating_middleHalfOfTopBand_picksFullscreen() {
        assertEquals(FloatingZone.FULLSCREEN, pickFloating(1280f, 0f))
        assertEquals(FloatingZone.FULLSCREEN, pickFloating(640f, 99f))
        assertEquals(FloatingZone.FULLSCREEN, pickFloating(1920f, 99f))
        assertEquals(FloatingZone.NONE, pickFloating(1280f, 100f))
    }

    @Test
    fun floating_outerQuartersOfTopBand_stayFloating() {
        assertEquals(FloatingZone.NONE, pickFloating(10f, 10f))
        assertEquals(FloatingZone.NONE, pickFloating(639f, 10f))
        assertEquals(FloatingZone.NONE, pickFloating(1921f, 10f))
        assertEquals(FloatingZone.NONE, pickFloating(2550f, 200f))
    }

    @Test
    fun floating_belowZones_staysFloating() {
        assertEquals(FloatingZone.NONE, pickFloating(1280f, 800f))
        assertEquals(FloatingZone.NONE, pickFloating(10f, 800f))
    }

    private companion object {
        const val WIDTH = 2560
        const val HEIGHT = 1600
        const val TOP = 100
    }
}
