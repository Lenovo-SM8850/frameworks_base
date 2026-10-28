// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.systemui.lyrics

import kotlin.math.*

/** Bounded, local correlation of line starts with band-limited audio onsets. */
object LyricOffsetEstimator {
    const val SAMPLE_RATE = 16_000
    private const val FRAME = 512
    private const val HOP = 160
    private const val SEARCH_MS = 3000
    private const val STEP_MS = 20

    data class Result(
        val offsetMs: Long = 0,
        val confidence: Double = 0.0,
        val accepted: Boolean = false,
    )

    fun estimate(pcm: ShortArray, startPositionMs: Double, lineStartsMs: List<Long>): Result {
        if (pcm.size < SAMPLE_RATE * LyricCalibrationRules.MARGIN_MS * 2 / 1000) return Result()
        val rms = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size) / 32768
        if (rms < 0.0001) return Result()
        val features = features(pcm)
        val endMs = startPositionMs + pcm.size * 1000.0 / SAMPLE_RATE
        val starts =
            lineStartsMs.distinct().filter {
                it - SEARCH_MS >= startPositionMs + 100 && it + SEARCH_MS <= endMs - 100
            }
        if (starts.size < 4) return Result()
        val times =
            DoubleArray(features.size) {
                startPositionMs + (it * HOP + FRAME / 2) * 1000.0 / SAMPLE_RATE
            }
        val scores =
            DoubleArray(2 * SEARCH_MS / STEP_MS + 1) { i ->
                score(features, times, starts, i * STEP_MS - SEARCH_MS)
            }
        val peak = scores.indices.maxByOrNull { scores[it] } ?: return Result()
        val shift = peak * STEP_MS - SEARCH_MS
        val confidence = scores[peak]
        if (peak == 0 || peak == scores.lastIndex || confidence < 0.6) return Result()
        val other = scores.indices.filter { abs(it - peak) * STEP_MS > 300 }.maxOf { scores[it] }
        if (confidence - other < 0.1) return Result()
        val middle = (startPositionMs + endMs) / 2
        val halves = listOf(starts.filter { it < middle }, starts.filter { it >= middle })
        if (halves.any { it.size < 2 }) return Result()
        val halfPeaks =
            halves.mapIndexed { halfIndex, half ->
                (-SEARCH_MS..SEARCH_MS step STEP_MS).maxByOrNull { candidate ->
                    // Keep the same fully covered starts in each half for every candidate.
                    score(
                        features,
                        times,
                        half,
                        candidate,
                        if (halfIndex == 0) startPositionMs else middle,
                        if (halfIndex == 0) middle else endMs,
                    )
                } ?: 0
            }
        if (abs(halfPeaks[0] - halfPeaks[1]) > 200 || halfPeaks.any { abs(it - shift) > 200 })
            return Result()
        val supporting =
            starts.count { start ->
                features.indices.any {
                    abs(times[it] - start - shift) <= 100 && features[it] >= 0.35
                }
            }
        return if (supporting >= 4) Result(shift.toLong(), confidence, true) else Result()
    }

    private fun score(
        feature: DoubleArray,
        times: DoubleArray,
        starts: List<Long>,
        shift: Int,
        from: Double = Double.NEGATIVE_INFINITY,
        to: Double = Double.POSITIVE_INFINITY,
    ): Double {
        var xy = 0.0
        var xx = 0.0
        var yy = 0.0
        var xSum = 0.0
        var ySum = 0.0
        var count = 0
        for (i in feature.indices) {
            if (times[i] < from || times[i] >= to) continue
            var train = 0.0
            for (start in starts) {
                val d = (times[i] - start - shift) / 40.0
                if (abs(d) < 4) train += exp(-0.5 * d * d)
            }
            val value = feature[i]
            xy += value * train
            xx += value * value
            yy += train * train
            xSum += value
            ySum += train
            count++
        }
        if (count == 0) return 0.0
        val denominator =
            sqrt(
                (xx - xSum * xSum / count).coerceAtLeast(0.0) *
                    (yy - ySum * ySum / count).coerceAtLeast(0.0)
            )
        return if (denominator > 1e-9) (xy - xSum * ySum / count) / denominator else 0.0
    }

    private fun features(pcm: ShortArray): DoubleArray {
        val count = (pcm.size - FRAME) / HOP + 1
        val energy = DoubleArray(count)
        val flux = DoubleArray(count)
        var previous = DoubleArray(FRAME / 2)
        val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1)) }
        for (frame in 0 until count) {
            val real = DoubleArray(FRAME) { pcm[frame * HOP + it] / 32768.0 * window[it] }
            val imaginary = DoubleArray(FRAME)
            fft(real, imaginary)
            val spectrum = DoubleArray(FRAME / 2)
            var power = 0.0
            var positiveFlux = 0.0
            for (bin in 10..108) {
                spectrum[bin] = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
                power += spectrum[bin] * spectrum[bin]
                positiveFlux += (spectrum[bin] - previous[bin]).coerceAtLeast(0.0)
            }
            energy[frame] = ln(1 + power)
            flux[frame] = positiveFlux
            previous = spectrum
        }
        normalize(energy)
        normalize(flux)
        val feature = DoubleArray(count) { energy[it] * 0.45 + flux[it] * 0.55 }
        // Smooth the proxy at the same scale as line onset uncertainty.
        val smoothed =
            DoubleArray(count) { i ->
                var sum = 0.0
                var weights = 0.0
                for (j in -6..6) {
                    val k = i + j
                    if (k !in feature.indices) continue
                    val weight = exp(-0.5 * j * j / 4.0)
                    sum += feature[k] * weight
                    weights += weight
                }
                sum / weights
            }
        normalize(smoothed)
        return smoothed
    }

    private fun normalize(values: DoubleArray) {
        val low = values.minOrNull() ?: 0.0
        val range = (values.maxOrNull() ?: 0.0) - low
        for (i in values.indices) values[i] = if (range > 1e-9) (values[i] - low) / range else 0.0
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        var j = 0
        for (i in 1 until FRAME) {
            var bit = FRAME shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val value = real[i]
                real[i] = real[j]
                real[j] = value
            }
        }
        var size = 2
        while (size <= FRAME) {
            val angle = -2 * PI / size
            for (base in 0 until FRAME step size) {
                for (k in 0 until size / 2) {
                    val a = base + k
                    val b = a + size / 2
                    val c = cos(angle * k)
                    val s = sin(angle * k)
                    val r = real[b] * c - imaginary[b] * s
                    val im = real[b] * s + imaginary[b] * c
                    real[b] = real[a] - r
                    imaginary[b] = imaginary[a] - im
                    real[a] += r
                    imaginary[a] += im
                }
            }
            size *= 2
        }
    }
}
