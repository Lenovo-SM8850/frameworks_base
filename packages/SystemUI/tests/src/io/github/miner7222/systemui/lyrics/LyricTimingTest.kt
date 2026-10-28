// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.systemui.lyrics

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
    fun lookupPrefersSyncedDurationMatchAndPreservesKnownVersions() {
        val plain = LyricRecord("plain", 200_000, null, "plain")
        val wrong = LyricRecord("wrong", 180_000, "[00:00]wrong", null)
        val matched = LyricRecord("match", 202_000, "[00:00]right", null)
        val closer = matched.copy(id = "closer", durationMs = 200_500)
        assertEquals(matched, LyricLookup.choose(listOf(plain, wrong, matched), 200_000))
        assertEquals(closer, LyricLookup.choose(listOf(matched, closer), 200_000))
        assertEquals(plain, LyricLookup.choose(listOf(plain, wrong), 200_000))
        assertEquals(plain, LyricLookup.choose(listOf(plain, matched), 0))
        assertNull(LyricLookup.choose(listOf(LyricRecord("empty", 0, null, null)), 200_000))
        assertEquals(
            "Song (Live Version) - Radio Edit",
            LyricLookup.title("Song (Live Version) - Radio Edit", 200_000),
        )
        assertEquals("Song", LyricLookup.title("Song (Live Version) - Radio Edit", 0))
    }

    @Test
    fun keySeparatesUsersPlayersTracksDurationAndLyricsAndCacheIsLru() {
        val key = LyricTrackKey(0, "player", "id", 200_000, LyricTrackKey.contentHash("lyrics"))
        val cache = LyricOffsetCache(2)
        cache.put(key, 1200)
        assertEquals(1200L, cache[key])
        assertNull(cache[key.copy(userId = 10)])
        assertNull(cache[key.copy(playerPackage = "other")])
        assertNull(cache[key.copy(mediaId = "other")])
        assertNull(cache[key.copy(durationMs = 201_000)])
        assertNull(cache[key.copy(lyricIdentity = LyricTrackKey.contentHash("new lyrics"))])
        val second = key.copy(mediaId = "second")
        cache.put(second, -800)
        assertEquals(1200L, cache[key])
        cache.put(key.copy(mediaId = "third"), 20)
        assertNull(cache[second])
        assertEquals(1200L, cache[key])
    }

    @Test
    fun calibrationStaysIdleForIntroAndRequiresCoveredStarts() {
        val useful = listOf(30_000L, 30_700L, 31_800L, 32_700L).map { LyricLine(it, "line") }
        assertEquals(26_000L, LyricCalibrationRules.windowStart(useful, 0))
        assertEquals(25_750L, LyricCalibrationRules.windowStart(useful, 250))
        assertNull(LyricCalibrationRules.windowStart(useful.take(3), 0))
        assertNull(
            LyricCalibrationRules.windowStart(
                useful.map { it.copy(timestampMs = it.timestampMs + 100_000) },
                0,
            )
        )
        assertNull(LyricCalibrationRules.windowStart(listOf(LyricLine(1000, "")), 0))
    }

    @Test
    fun cancellationRulesDiscardDiscontinuitiesAndNeverRetryOccurrence() {
        val anchor = LyricPlaybackSnapshot(4000, 100_000, true, 1f, "speaker")
        val next = anchor.copy(positionMs = 5000, clockMs = 101_000)
        assertFalse(LyricCalibrationRules.cancels(anchor, next))
        assertTrue(LyricCalibrationRules.cancels(anchor, next.copy(playing = false)))
        assertTrue(LyricCalibrationRules.cancels(anchor, next.copy(speed = 1.1f)))
        assertTrue(LyricCalibrationRules.cancels(anchor, next.copy(positionMs = 9000)))
        assertTrue(LyricCalibrationRules.cancels(anchor, next.copy(positionMs = 5100)))
        assertTrue(LyricCalibrationRules.cancels(anchor, next.copy(positionMs = 1000)))
        assertTrue(LyricCalibrationRules.cancels(anchor, next.copy(route = "usb")))
        assertTrue(LyricCalibrationRules.cancels(anchor, null))
        assertTrue(LyricCalibrationRules.cancels(anchor, next.copy(identity = "new session/track")))
        val occurrence = LyricCalibrationAttempt()
        assertTrue(occurrence.begin())
        assertTrue(occurrence.attempted)
        assertFalse(occurrence.begin())
        assertTrue(LyricCalibrationAttempt().begin())
    }

    @Test
    fun captureClockMapsBoottimeFramesToPlaybackAndRejectsInsufficientData() {
        val clock = LyricCaptureClock()
        val anchor = LyricPlaybackSnapshot(4000, 100_000, true, 1f, "speaker")
        assertNull(clock.startPositionMs(anchor, 100_030_000_000L))
        assertTrue(clock.observe(160, 100_010_000_000L, 100_010_000_000L))
        assertNull(clock.startPositionMs(anchor, 100_030_000_000L))
        assertTrue(clock.observe(320, 100_020_000_000L, 100_020_000_000L))
        assertTrue(clock.observe(480, 100_030_000_000L, 100_030_000_000L))
        assertEquals(4000.0, clock.startPositionMs(anchor, 100_030_000_000L)!!, 0.001)
        assertNull(clock.startPositionMs(anchor, 101_000_000_000L))
    }

    @Test
    fun captureClockRejectsDriftStaleAndNonMonotonicTimestamps() {
        val clock = LyricCaptureClock()
        assertTrue(clock.observe(160, 100_010_000_000L, 100_010_000_000L))
        assertTrue(clock.observe(160, 100_010_000_000L, 100_010_000_000L))
        assertFalse(clock.observe(160, 100_010_000_000L, 101_010_000_000L))
        assertFalse(clock.observe(159, 100_020_000_000L, 100_020_000_000L))
        assertFalse(clock.observe(320, 100_010_000_000L, 100_010_000_000L))
        assertFalse(clock.observe(320, 100_050_000_000L, 100_050_000_000L))
        assertFalse(clock.observe(320, 100_020_000_000L, 101_020_000_000L))
    }

    @Test
    fun defaultOffsetCacheIsBoundedAt200Entries() {
        val cache = LyricOffsetCache()
        val key = LyricTrackKey(0, "player", "0", 200_000, "lyrics")
        repeat(201) { cache.put(key.copy(mediaId = it.toString()), it.toLong()) }
        assertNull(cache[key])
        assertEquals(1L, cache[key.copy(mediaId = "1")])
        assertEquals(200L, cache[key.copy(mediaId = "200")])
    }

    @Test
    fun attemptedTracksSurviveFailureCancellationAndReplayWithLruBound() {
        val attempts = LyricAttemptCache(2)
        val key = LyricTrackKey(0, "player", "track", 200_000, "lyrics")
        assertTrue(attempts.begin(key))
        assertFalse(attempts.begin(key))
        assertFalse(attempts.begin(key.copy()))
        assertTrue(attempts.begin(key.copy(userId = 10)))
        assertTrue(key in attempts)
        assertTrue(attempts.begin(key.copy(mediaId = "new")))
        assertFalse(key.copy(userId = 10) in attempts)
        assertFalse(attempts.begin(key))
    }

    @Test
    fun reverseBoundaryAtClampedZeroCannotBeCrossed() {
        val lines = listOf(LyricLine(0, "first"), LyricLine(1000, "next"))
        assertNull(LyricTiming.nextDelayMs(lines, 0, 0, -1f, true, 10_000))
        assertEquals(501L, LyricTiming.nextDelayMs(lines, 500, 0, -1f, true, 10_000))
        assertNull(LyricTiming.nextDelayMs(lines, 0, -100, -1f, true, 10_000))
    }

    @Test
    fun unmatchedDurationNeverReturnsArbitrarySyncedLyrics() {
        val wrong = LyricRecord("wrong", 150_000, "[00:01]wrong", "plain fallback")
        val plain = LyricRecord("plain", 200_000, null, "matched plain")
        assertEquals(plain, LyricLookup.choose(listOf(wrong, plain), 200_000))
        assertEquals(wrong.copy(synced = null), LyricLookup.choose(listOf(wrong), 200_000))
        assertNull(LyricLookup.choose(listOf(wrong.copy(plain = null)), 200_000))
    }

    @Test
    fun lateLookupChoosesNextCoverableWindowWithoutConsumingAttempt() {
        val lines =
            listOf(3200L, 3700L, 5800L, 6700L, 13_200L, 13_700L, 15_800L, 16_700L).map {
                LyricLine(it, "line")
            }
        assertEquals(0L, LyricCalibrationRules.windowStart(lines, 0))
        assertEquals(50L, LyricCalibrationRules.windowStart(lines, 0, 50))
        assertEquals(9800L, LyricCalibrationRules.windowStart(lines, 0, 300))
        assertNull(LyricCalibrationRules.windowStart(lines, 0, 14_000))
        assertNull(LyricCalibrationRules.windowStart(lines, 0, 0, 9000))
    }
}
