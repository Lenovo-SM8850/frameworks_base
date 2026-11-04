// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.systemui.lyrics

import java.util.Locale

internal object LyricLanguagePolicy {
    private const val TranslationWindowMs = 120L

    fun matchesTrack(
        title: String, artist: String, durationMs: Long,
        candidateTitle: String, candidateArtist: String, candidateDurationMs: Long,
    ): Boolean =
        LyricLookup.title(title, durationMs).equals(LyricLookup.title(candidateTitle, durationMs), true) &&
            LyricLookup.artist(artist).equals(LyricLookup.artist(candidateArtist), true) &&
            (durationMs <= 0 || kotlin.math.abs(durationMs - candidateDurationMs) <= 2000)


    fun scriptLanguage(text: String): String? {
        val scripts = text.codePoints().toArray().map { Character.UnicodeScript.of(it) }
        return when {
            scripts.any { it == Character.UnicodeScript.HANGUL } -> "ko"
            scripts.any { it == Character.UnicodeScript.HIRAGANA || it == Character.UnicodeScript.KATAKANA } -> "ja"
            text.any { it in "ơƠưƯạảấầẩẫậắằẳẵặẹẻẽếềểễệịỉĩọỏốồổỗộớờởỡợụủũứừửữựỵỷỹ" } -> "vi"
            else -> null
        }
    }

    fun original(
        records: List<LyricRecord>,
        title: String,
        language: (String) -> String?,
        artist: String = "",
        album: String = "",
    ): String? = (listOf(title, artist, album) + records.map { it.metadata })
        .firstNotNullOfOrNull { text ->
            scriptLanguage(text) ?: if (hasNonLatinLetters(text)) {
                language(text) ?: if (text.codePoints().anyMatch {
                    Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN
                }) {
                    records.flatMap { texts(it) }.mapNotNull(language)
                        .filter { it == "ja" || it == "zh" }.distinct().singleOrNull()
                } else null
            } else null
        }

    fun rank(
        record: LyricRecord,
        original: String?,
        systemLanguage: String,
        language: (String) -> String?,
    ): Int {
        val codes = texts(record).mapNotNull(language).toSet()
        return when {
            systemLanguage in codes -> 0
            original != null && original in codes -> 1
            "en" in codes -> 2
            else -> 3
        }
    }

    fun select(
        record: LyricRecord,
        original: String?,
        systemLanguage: String,
        language: (String) -> String?,
    ): LyricRecord? {
        val preference = listOfNotNull(systemLanguage, original, "en").distinct()
        val parsed = LyricTiming.parse(record.synced.orEmpty())
        if (parsed.lines.isNotEmpty()) {
            val groups = mutableListOf<MutableList<LyricLine>>()
            for (line in parsed.lines) {
                val group = groups.lastOrNull()
                val code = language(line.text)
                if (group != null && line.timestampMs - group.first().timestampMs <= TranslationWindowMs &&
                    (line.timestampMs == group.first().timestampMs ||
                        (code != null && group.none { language(it.text) == code }))
                ) group += line else groups += mutableListOf(line)
            }
            val selected = groups.map { group ->
                val chosen = preference.firstNotNullOfOrNull { code ->
                    group.firstOrNull { language(it.text) == code }
                }
                LyricLine(group.first().timestampMs, chosen?.text.orEmpty())
            }
            if (selected.none { it.text.isNotBlank() }) return null
            val synced = buildString {
                if (parsed.lrcOffsetMs != 0L) append("[offset:${parsed.lrcOffsetMs}]\n")
                selected.forEach {
                    append(String.format(Locale.ROOT, "[%02d:%02d.%03d]%s\n",
                        it.timestampMs / 60000, it.timestampMs / 1000 % 60,
                        it.timestampMs % 1000, it.text))
                }
            }
            return record.copy(synced = synced, plain = selected.filter { it.text.isNotBlank() }.joinToString("\n") { it.text })
        }
        val plain = record.plain.orEmpty().lines()
        val chosen = preference.firstOrNull { code -> plain.any { language(it) == code } } ?: return null
        return record.copy(synced = null, plain = plain.filter { language(it) == chosen }.joinToString("\n"))
    }

    private fun hasNonLatinLetters(text: String): Boolean = text.codePoints().anyMatch {
        Character.isLetter(it) && Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN
    }

    private fun texts(record: LyricRecord): List<String> =
        if (!record.synced.isNullOrBlank()) LyricTiming.parse(record.synced).lines.map { it.text }
        else record.plain.orEmpty().lines()
}
