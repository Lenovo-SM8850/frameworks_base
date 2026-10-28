// SPDX-License-Identifier: Apache-2.0

package com.android.systemui.statusbar.quickactions.media.domain.interactor

import kotlin.math.abs
import kotlin.math.ceil

data class LyricLine(val timestampMs: Long, val text: String)

data class ParsedLyrics(val lines: List<LyricLine>, val lrcOffsetMs: Long = 0)

data class LyricState(
    val lines: List<LyricLine> = emptyList(),
    val activeIndex: Int = -1,
    val plainLyrics: String? = null,
    val isPlaying: Boolean = false,
)

object LyricTiming {
    private val timestamp = Regex("\\[(\\d{1,6}):(\\d{1,2})(?:[.:](\\d{1,9}))?\\]")
    private val offset = Regex("\\[offset:([+-]?\\d+)\\]", RegexOption.IGNORE_CASE)

    fun parse(lrc: String): ParsedLyrics {
        val lines = mutableListOf<LyricLine>()
        var offsetMs = 0L
        for (line in lrc.lineSequence()) {
            val raw = line.trim()
            offset.find(raw)?.groupValues?.get(1)?.toLongOrNull()?.let {
                offsetMs = it.coerceIn(-86_400_000L, 86_400_000L)
            }
            var end = 0
            val tags =
                timestamp
                    .findAll(raw)
                    .takeWhile { tag ->
                        (tag.range.first == end).also { if (it) end = tag.range.last + 1 }
                    }
                    .toList()
            if (tags.isEmpty() || tags.first().range.first != 0) continue
            val text = raw.substring(tags.last().range.last + 1).trim()
            for (tag in tags) {
                val (minutes, seconds, fraction) = tag.destructured
                if (seconds.toInt() >= 60) continue
                val millis = fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                lines += LyricLine((minutes.toLong() * 60 + seconds.toLong()) * 1000 + millis, text)
            }
        }
        return ParsedLyrics(
            lines.sortedBy { it.timestampMs }.dropWhile { it.text.isEmpty() },
            offsetMs,
        )
    }

    fun position(
        positionMs: Long,
        updatedMs: Long,
        nowMs: Long,
        speed: Float,
        playing: Boolean,
        durationMs: Long,
    ): Long {
        val elapsed = if (playing && updatedMs > 0) (nowMs - updatedMs).coerceAtLeast(0) else 0
        val value =
            (positionMs.coerceAtLeast(0) +
                    elapsed * (if (speed.isFinite()) speed.toDouble() else 0.0))
                .toLong()
                .coerceAtLeast(0)
        return if (durationMs > 0) value.coerceAtMost(durationMs) else value
    }

    // Positive audio offsets delay display; positive LRC offsets advance it.
    fun activeIndex(lines: List<LyricLine>, mediaPositionMs: Long, offsetMs: Long): Int {
        val position = mediaPositionMs - offsetMs
        var low = 0
        var high = lines.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timestampMs <= position) low = mid + 1 else high = mid
        }
        return low - 1
    }

    fun nextDelayMs(
        lines: List<LyricLine>,
        positionMs: Long,
        offsetMs: Long,
        speed: Float,
        playing: Boolean,
        durationMs: Long,
    ): Long? {
        if (!playing || speed == 0f || !speed.isFinite()) return null
        val index = activeIndex(lines, positionMs, offsetMs)
        val boundary =
            if (speed > 0) lines.getOrNull(index + 1)?.timestampMs
            else lines.getOrNull(index)?.timestampMs
        boundary ?: return null
        val mediaBoundary = boundary + offsetMs
        if (mediaBoundary < 0 || (durationMs > 0 && mediaBoundary > durationMs)) return null
        if (speed < 0 && positionMs <= 0 && mediaBoundary <= 0) return null
        // At a reverse boundary the inclusive selector changes on the following millisecond.
        val distance = if (speed > 0) mediaBoundary - positionMs else positionMs - mediaBoundary + 1
        return ceil(distance / abs(speed.toDouble())).toLong().coerceAtLeast(1)
    }
}
