// SPDX-License-Identifier: Apache-2.0

package com.android.systemui.statusbar.quickactions.media.domain.interactor

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class LyricTimingTest {
    private val lines = listOf(LyricLine(1000, "one"), LyricLine(2500, "two"), LyricLine(4000, ""))

    @Test
    fun parseOffsetsFractionsMultipleTagsAndStableOrdering() {
        val result =
            LyricTiming.parse(
                "[offset:+250]\n[00:00.0]\n[00:02.3456] two\n[00:01.2][00:03:45]one\n[00:04]\n[ar:artist]\n[00:99]bad"
            )
        assertEquals(250L, result.lrcOffsetMs)
        assertEquals(listOf(1200L, 2345L, 3450L, 4000L), result.lines.map { it.timestampMs })
        assertEquals(listOf("one", "two", "one", ""), result.lines.map { it.text })
        assertEquals(
            listOf(LyricLine(1200, "leading whitespace")),
            LyricTiming.parse("  [00:01.2]leading whitespace  ").lines,
        )
        assertEquals(
            "keep [00:42] in text",
            LyricTiming.parse("[00:01]keep [00:42] in text").lines.single().text,
        )
        assertEquals(-800L, LyricTiming.parse("[offset:-800]").lrcOffsetMs)
        assertEquals(0L, LyricTiming.parse("[offset:bad]").lrcOffsetMs)
    }

    @Test
    fun selectionAppliesOffsetExactlyOnceAndIncludesBoundary() {
        assertEquals(-1, LyricTiming.activeIndex(lines, 2199, 1200))
        assertEquals(0, LyricTiming.activeIndex(lines, 2200, 1200))
        assertEquals(1, LyricTiming.activeIndex(lines, 3700, 1200))
        assertEquals(0, LyricTiming.activeIndex(lines, 200, -800))
        assertEquals(0, LyricTiming.activeIndex(lines, 750, -250))
        assertEquals(-1, LyricTiming.activeIndex(emptyList(), 100_000, 0))
        assertEquals(2, LyricTiming.activeIndex(lines, 100_000, 0))
    }

    @Test
    fun positionGuardsZeroTimePauseSpeedAndDuration() {
        assertEquals(500L, LyricTiming.position(500, 0, 100_000, 1f, true, 4000))
        assertEquals(2500L, LyricTiming.position(500, 1000, 2000, 2f, true, 4000))
        assertEquals(500L, LyricTiming.position(500, 1000, 2000, 1f, false, 4000))
        assertEquals(4000L, LyricTiming.position(3500, 1000, 2000, 1f, true, 4000))
        assertEquals(0L, LyricTiming.position(500, 1000, 2000, -1f, true, 4000))
        assertEquals(500L, LyricTiming.position(500, 2000, 1000, 1f, true, 4000))
        assertEquals(500L, LyricTiming.position(500, 1000, 2000, Float.NaN, true, 4000))
    }

    @Test
    fun boundarySchedulingAccountsForOffsetSpeedPauseAndEnd() {
        assertEquals(2200L, LyricTiming.nextDelayMs(lines, 0, 1200, 1f, true, 10_000))
        assertEquals(750L, LyricTiming.nextDelayMs(lines, 2200, 1200, 2f, true, 10_000))
        assertEquals(200L, LyricTiming.nextDelayMs(lines, 0, -800, 1f, true, 10_000))
        assertEquals(1L, LyricTiming.nextDelayMs(lines, 2500, 0, -1f, true, 10_000))
        assertNull(LyricTiming.nextDelayMs(lines, 0, 0, 1f, false, 10_000))
        assertNull(LyricTiming.nextDelayMs(lines, 0, 0, 0f, true, 10_000))
        assertNull(LyricTiming.nextDelayMs(lines, 4000, 0, 1f, true, 10_000))
        assertNull(LyricTiming.nextDelayMs(lines, 0, 0, 1f, true, 500))
    }

    @Test
    fun reverseBoundaryAtClampedZeroCannotBeCrossed() {
        val lines = listOf(LyricLine(0, "first"), LyricLine(1000, "next"))
        assertNull(LyricTiming.nextDelayMs(lines, 0, 0, -1f, true, 10_000))
        assertEquals(501L, LyricTiming.nextDelayMs(lines, 500, 0, -1f, true, 10_000))
        assertNull(LyricTiming.nextDelayMs(lines, 0, -100, -1f, true, 10_000))
    }
}
