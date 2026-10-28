// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.systemui.lyrics

import kotlin.math.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class LyricOffsetEstimatorTest {
    private val starts = listOf(5000L, 7600L, 11_100L, 15_000L, 18_500L)

    private fun signal(
        shift: Long,
        clicks: Boolean = false,
        onsets: List<Long> = starts,
    ): ShortArray {
        val pcm = ShortArray(24 * 16_000)
        for (start in onsets) {
            val sample = ((start + shift) * 16).toInt()
            for (i in 0 until if (clicks) 40 else 1280) {
                if (sample + i !in pcm.indices) continue
                val value =
                    if (clicks) 20_000.0 * exp(-i / 8.0)
                    else 18_000.0 * sin(2 * PI * 900 * i / 16_000) * exp(-i / 400.0)
                pcm[sample + i] = value.toInt().toShort()
            }
        }
        return pcm
    }

    private fun assertShift(shift: Long, clicks: Boolean) {
        val result = LyricOffsetEstimator.estimate(signal(shift, clicks), 0.0, starts)
        assertTrue("shift=$shift clicks=$clicks result=$result", result.accepted)
        assertTrue("$result", abs(result.offsetMs - shift) <= 40)
        assertTrue(result.confidence >= 0.6)
    }

    @Test
    fun recoversPositive1200msToneBursts() {
        assertShift(1200, false)
    }

    @Test
    fun recoversNegative800msToneBursts() {
        assertShift(-800, false)
    }

    @Test
    fun recoversPositive1200msClicks() {
        assertShift(1200, true)
    }

    @Test
    fun recoversNegative800msClicks() {
        assertShift(-800, true)
    }

    @Test
    fun silenceIsRejected() {
        assertEquals(
            LyricOffsetEstimator.Result(),
            LyricOffsetEstimator.estimate(ShortArray(24 * 16_000), 0.0, starts),
        )
    }

    @Test
    fun noiseIsRejected() {
        val random = Random(7222)
        val pcm = ShortArray(24 * 16_000) { random.nextInt(-12_000, 12_001).toShort() }
        assertEquals(LyricOffsetEstimator.Result(), LyricOffsetEstimator.estimate(pcm, 0.0, starts))
    }

    @Test
    fun periodicAmbiguousPeaksAreRejected() {
        val periodic = (1000L..23_000L step 1000).toList()
        assertEquals(
            LyricOffsetEstimator.Result(),
            LyricOffsetEstimator.estimate(
                signal(0, false, periodic),
                0.0,
                (5000L..19_000L step 2000).toList(),
            ),
        )
    }

    @Test
    fun inconsistentHalvesAreRejected() {
        val first = signal(1200, false, starts.take(3))
        val second = signal(-800, false, starts.drop(3))
        val pcm = ShortArray(first.size) { (first[it] + second[it]).toShort() }
        assertFalse(LyricOffsetEstimator.estimate(pcm, 0.0, starts).accepted)
    }

    @Test
    fun uncoveredStartsAndSearchBoundariesAreRejected() {
        assertFalse(LyricOffsetEstimator.estimate(signal(3000), 0.0, starts).accepted)
        assertFalse(LyricOffsetEstimator.estimate(signal(1200), 0.0, starts.take(3)).accepted)
        assertFalse(LyricOffsetEstimator.estimate(signal(1200), 12_000.0, starts).accepted)
    }

    private val shortStarts = listOf(3200L, 3700L, 5800L, 6700L)

    @Test
    fun tenSecondWindowRetainsAllAccuracyGates() {
        for (shift in listOf(1200L, -800L)) {
            for (clicks in listOf(false, true)) {
                val pcm = signal(shift, clicks, shortStarts).copyOf(10 * 16_000)
                val result = LyricOffsetEstimator.estimate(pcm, 0.0, shortStarts)
                assertTrue("$shift clicks=$clicks $result", result.accepted)
                assertTrue("$result", abs(result.offsetMs - shift) <= 40)
                assertTrue(result.confidence >= 0.6)
                println(
                    "10s shift=$shift clicks=$clicks offset=${result.offsetMs} confidence=${result.confidence}"
                )
            }
        }
    }

    @Test
    fun nineSecondsCannotCoverTheFourShortWindowStarts() {
        val pcm = signal(1200, false, shortStarts).copyOf(9 * 16_000)
        assertFalse(LyricOffsetEstimator.estimate(pcm, 0.0, shortStarts).accepted)
    }

    @Test
    fun shortWindowRejectsSilenceNoisePeriodicAndDisagreeingHalves() {
        assertFalse(
            LyricOffsetEstimator.estimate(ShortArray(10 * 16_000), 0.0, shortStarts).accepted
        )
        val random = Random(7222)
        val noise = ShortArray(10 * 16_000) { random.nextInt(-12_000, 12_001).toShort() }
        assertFalse(LyricOffsetEstimator.estimate(noise, 0.0, shortStarts).accepted)
        val periodic = signal(0, false, (500L..9500L step 500).toList()).copyOf(10 * 16_000)
        assertFalse(LyricOffsetEstimator.estimate(periodic, 0.0, shortStarts).accepted)
        val first = signal(1200, false, shortStarts.take(2))
        val second = signal(-800, false, shortStarts.drop(2))
        val mixed = ShortArray(10 * 16_000) { (first[it] + second[it]).toShort() }
        assertFalse(LyricOffsetEstimator.estimate(mixed, 0.0, shortStarts).accepted)
    }
}
