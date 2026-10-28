// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.systemui.lyrics

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil

data class LyricLine(val timestampMs: Long, val text: String)

data class ParsedLyrics(val lines: List<LyricLine>, val lrcOffsetMs: Long = 0)

data class LyricState(
    val lines: List<LyricLine> = emptyList(),
    val activeIndex: Int = -1,
    val offsetMs: Long = 0,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val playerPackage: String? = null,
    val sessionKey: Int? = null,
    val title: String? = null,
    val artist: String? = null,
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

data class LyricRecord(
    val id: String,
    val durationMs: Long,
    val synced: String?,
    val plain: String?,
)

object LyricLookup {
    fun choose(records: List<LyricRecord>, durationMs: Long): LyricRecord? {
        val usable = records.filter { !it.synced.isNullOrBlank() || !it.plain.isNullOrBlank() }
        if (durationMs <= 0) return usable.firstOrNull()
        val matching = usable.filter { abs(it.durationMs - durationMs) <= 2000 }
        matching
            .filter { !it.synced.isNullOrBlank() }
            .minByOrNull { abs(it.durationMs - durationMs) }
            ?.let {
                return it
            }
        val plain =
            matching
                .filter { !it.plain.isNullOrBlank() }
                .minByOrNull { abs(it.durationMs - durationMs) }
                ?: usable.firstOrNull { !it.plain.isNullOrBlank() }
        return plain?.copy(synced = null)
    }

    fun title(title: String, durationMs: Long): String {
        if (durationMs > 0) return title.trim()
        return title
            .split(Regex("\\s+[-–—|•]\\s+"))[0]
            .replace(
                Regex(
                    "(?i)\\s*[\\(\\[][^\\)\\]]*(?:feat|remaster|live|video|version|edit|acoustic|single|studio|mono|stereo|re-recorded)[^\\)\\]]*[\\]\\)]"
                ),
                "",
            )
            .replace(Regex("(?i)\\s+\\b(feat\\.?|featuring|ft\\.?|with)\\b.*"), "")
            .trim()
    }

    fun artist(artist: String): String =
        artist
            .split(Regex("(?i)\\s*[,/;]\\s*|\\s+\\b(feat\\.?|featuring|ft\\.?|and|&)\\b\\s+"))[0]
            .trim()
}

data class LyricTrackKey(
    val userId: Int,
    val playerPackage: String,
    val mediaId: String,
    val durationMs: Long,
    val lyricIdentity: String,
) {
    companion object {
        fun contentHash(content: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

class LyricOffsetCache(private val capacity: Int = 200) {
    private val offsets =
        object : LinkedHashMap<LyricTrackKey, Long>(capacity, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<LyricTrackKey, Long>
            ): Boolean = size > capacity
        }

    operator fun get(key: LyricTrackKey): Long? = offsets[key]

    fun put(key: LyricTrackKey, offsetMs: Long) {
        offsets[key] = offsetMs
    }
}

data class LyricPlaybackSnapshot(
    val positionMs: Long,
    val clockMs: Long,
    val playing: Boolean,
    val speed: Float,
    val route: String,
    val identity: String = "",
)

object LyricCalibrationRules {
    // A loopback/render policy blocks new MMAP outputs globally; keep its lifetime short.
    const val WINDOW_MS = 10_000L
    const val MARGIN_MS = 3100L

    fun windowStart(
        lines: List<LyricLine>,
        lrcOffsetMs: Long,
        earliestStartMs: Long = 0,
        durationMs: Long = 0,
    ): Long? {
        val starts =
            lines
                .filter { it.text.isNotBlank() }
                .map { it.timestampMs - lrcOffsetMs }
                .distinct()
                .sorted()
        val first = starts.firstOrNull() ?: return null
        if (first > 120_000 || first < 0) return null
        val earliest = maxOf(0L, first - 4000, earliestStartMs)
        val candidates =
            (listOf(earliest) +
                    starts.flatMap { listOf(it - WINDOW_MS + MARGIN_MS, it - WINDOW_MS / 2 + 1) })
                .filter { it >= earliest }
                .distinct()
                .sorted()
        return candidates.firstOrNull { start ->
            val covered =
                starts.filter { it >= start + MARGIN_MS && it <= start + WINDOW_MS - MARGIN_MS }
            (durationMs <= 0 || start + WINDOW_MS <= durationMs) &&
                covered.size >= 4 &&
                covered.count { it < start + WINDOW_MS / 2 } >= 2 &&
                covered.count { it >= start + WINDOW_MS / 2 } >= 2
        }
    }

    fun cancels(before: LyricPlaybackSnapshot, after: LyricPlaybackSnapshot?): Boolean =
        after == null ||
            before.identity != after.identity ||
            !after.playing ||
            after.speed != 1f ||
            before.route != after.route ||
            after.clockMs < before.clockMs ||
            abs(after.positionMs - (before.positionMs + after.clockMs - before.clockMs)) > 50
}

class LyricAttemptCache(private val capacity: Int = 200) {
    private val tracks =
        object : LinkedHashMap<LyricTrackKey, Unit>(capacity, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<LyricTrackKey, Unit>
            ): Boolean = size > capacity
        }

    operator fun contains(key: LyricTrackKey): Boolean = tracks[key] != null

    fun begin(key: LyricTrackKey): Boolean {
        if (key in this) return false
        tracks[key] = Unit
        return true
    }
}

class LyricCalibrationAttempt {
    var attempted = false
        private set

    fun begin(): Boolean {
        if (attempted) return false
        attempted = true
        return true
    }
}

class LyricCaptureClock(private val sampleRate: Int = 16_000) {
    private var originNs: Double? = null
    private var lastFrame = -1L
    private var lastNs = -1L
    private var stamps = 0

    fun observe(frame: Long, nanos: Long, nowNs: Long): Boolean {
        if (abs(nanos - nowNs) > 500_000_000L) return false
        if (frame == lastFrame && nanos == lastNs) return true
        if (frame < 0 || frame <= lastFrame || nanos <= lastNs) return false
        val origin = nanos - frame * 1e9 / sampleRate
        val first = originNs
        if (first == null) originNs = origin else if (abs(origin - first) > 20_000_000) return false
        lastFrame = frame
        lastNs = nanos
        stamps++
        return true
    }

    fun startPositionMs(anchor: LyricPlaybackSnapshot, nowNs: Long): Double? {
        val origin = originNs ?: return null
        return if (stamps >= 3 && abs(nowNs - lastNs) <= 500_000_000L)
            anchor.positionMs + origin / 1e6 - anchor.clockMs
        else null
    }
}
