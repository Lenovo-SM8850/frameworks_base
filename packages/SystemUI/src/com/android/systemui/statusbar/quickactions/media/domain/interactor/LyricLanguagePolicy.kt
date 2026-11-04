// SPDX-License-Identifier: Apache-2.0
package com.android.systemui.statusbar.quickactions.media.domain.interactor

import android.content.Context
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

data class LyricRecord(
    val id: String,
    val durationMs: Long,
    val synced: String?,
    val plain: String?,
    val metadata: String = "",
)

internal object LyricLanguagePolicy {
    private const val TranslationWindowMs = 120L
    private const val LastRank = 3

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
        recordLanguage: (LyricRecord) -> (String) -> String? = { language },
    ): String? = (listOf(title, artist, album) + records.map { it.metadata })
        .firstNotNullOfOrNull { text ->
            scriptLanguage(text) ?: when {
                // Han letters alone do not tell Japanese from Chinese, so only the lyrics decide.
                text.codePoints().anyMatch {
                    Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN
                } ->
                    records.flatMap { record -> texts(record).mapNotNull(recordLanguage(record)) }
                        .filter { it == "ja" || it == "zh" }.distinct().singleOrNull()
                hasNonLatinLetters(text) -> language(text)
                else -> null
            }
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
            else -> LastRank
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
            val chosen = groups.map { group ->
                preference.firstNotNullOfOrNull { code ->
                    group.firstOrNull { language(it.text) == code }
                }
            }
            if (chosen.none { it != null }) return null
            // Without an original language a line outside the preference may be the song's own,
            // so a line alone at its time stays.
            val selected = groups.zip(chosen).map { (group, line) ->
                val text = line?.text
                    ?: if (original == null && group.size == 1) group.first().text else ""
                LyricLine(group.first().timestampMs, text)
            }
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
        // Without an original language a line outside the preference may be the song's own.
        if (original == null && plain.any { line -> language(line)?.let { it !in preference } == true }) {
            return record.copy(synced = null)
        }
        return record.copy(synced = null, plain = plain.filter { language(it) == chosen }.joinToString("\n"))
    }

    private fun hasNonLatinLetters(text: String): Boolean = text.codePoints().anyMatch {
        Character.isLetter(it) && Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN
    }

    private fun texts(record: LyricRecord): List<String> =
        if (!record.synced.isNullOrBlank()) LyricTiming.parse(record.synced).lines.map { it.text }
        else record.plain.orEmpty().lines()

    /**
     * [items] with the results of the track in the order of the language of their lyrics: lines
     * in the system language first, then in the original language, then in English, then the
     * rest, each in search order. A result is of the track when its title and artist, cleaned by
     * [cleanTitle] and [cleanArtist], equal [title] and [artist] ignoring case, and its length
     * matches by [toleranceSec]. Such a result keeps the lines of the best language it has at
     * each time. The other results keep their places and their lyrics. The length and synced
     * rules that pick from the order still decide first, and the language only decides between
     * results they rank equal.
     */
    fun order(
        context: Context,
        items: JSONArray,
        title: String,
        artist: String,
        systemLanguage: String,
        durationSec: Long?,
        toleranceSec: Double,
        cleanTitle: (String) -> String,
        cleanArtist: (String) -> String,
    ): JSONArray {
        val classifier = runCatching {
            context.getSystemService(TextClassificationManager::class.java)?.textClassifier
        }.getOrNull()
        val classify = { text: String ->
            runCatching {
                val result = classifier?.detectLanguage(TextLanguage.Request.Builder(text).build())
                if (result != null && result.localeHypothesisCount > 0) {
                    val locale = result.getLocale(0)
                    locale.language.takeIf { result.getConfidenceScore(locale) >= 0.75f }
                } else null
            }.getOrNull()
        }
        return order(items, title, artist, systemLanguage, durationSec, toleranceSec, classify,
            cleanTitle, cleanArtist)
    }

    /**
     * [order] with [classify] as the language of a text without script evidence, or null when it
     * is not confident. The lines of a result without script evidence are classified together once.
     */
    fun order(
        items: JSONArray,
        title: String,
        artist: String,
        systemLanguage: String,
        durationSec: Long?,
        toleranceSec: Double,
        classify: (String) -> String?,
        cleanTitle: (String) -> String = { it },
        cleanArtist: (String) -> String = { it },
    ): JSONArray {
        val all = (0 until items.length()).map { items.getJSONObject(it) }
        val trackTitle = cleanTitle(title)
        val trackArtist = cleanArtist(artist)
        // Only results of the track are ranked, so a result of another song in the system
        // language cannot take the place of the track.
        val matching = all.indices.filter { index ->
            val item = all[index]
            (item.lyric("syncedLyrics") != null || item.lyric("plainLyrics") != null) &&
                cleanTitle(item.optString("trackName")).equals(trackTitle, ignoreCase = true) &&
                cleanArtist(item.optString("artistName")).equals(trackArtist, ignoreCase = true) &&
                (durationSec == null ||
                    abs(item.optDouble("duration", -1000.0) - durationSec) <= toleranceSec)
        }
        val records = matching.map { all[it].toRecord() }
        val classified = mutableMapOf<String, String?>()
        fun classifyOnce(text: String): String? {
            if (!classified.containsKey(text)) classified[text] = classify(text)
            return classified[text]
        }
        fun language(text: String): String? = when {
            text.isBlank() -> null
            else -> scriptLanguage(text) ?: classifyOnce(text)
        }
        // The lines of a result are classified together, so each result has its own languages.
        val recordLanguages = records.map { record ->
            val lineLanguages = mutableMapOf<String, String?>()
            val unknown = texts(record).filter { it.isNotBlank() && scriptLanguage(it) == null }
            if (unknown.isNotEmpty()) {
                val code = classifyOnce(unknown.distinct().joinToString("\n"))
                unknown.forEach { lineLanguages[it] = code }
            }
            val recordLanguage = { text: String ->
                if (text.isNotBlank() && lineLanguages.containsKey(text)) lineLanguages[text]
                else language(text)
            }
            recordLanguage
        }
        val languageOf = records.zip(recordLanguages).toMap(java.util.IdentityHashMap())
        val original = original(records, title, ::language, artist) { languageOf.getValue(it) }
        val ranked = matching.indices.map { position ->
            val index = matching[position]
            val record = records[position]
            val recordLanguage = recordLanguages[position]
            // A result without these languages stays as it is, and keeps its synced lines.
            val chosen = select(record, original, systemLanguage, recordLanguage)
                ?.takeIf { it.synced.isNullOrBlank() == record.synced.isNullOrBlank() }
            if (chosen == null) LastRank to all[index]
            else rank(record, original, systemLanguage, recordLanguage) to all[index].withLyrics(chosen)
        }
        val ordered = all.toMutableList()
        matching.zip(ranked.sortedBy { it.first }.map { it.second }).forEach { (index, item) ->
            ordered[index] = item
        }
        return JSONArray(ordered)
    }

    private fun JSONObject.toRecord() =
        LyricRecord(
            optString("id"),
            (optDouble("duration", 0.0) * 1000).toLong(),
            lyric("syncedLyrics"),
            lyric("plainLyrics"),
            listOf("trackName", "artistName", "albumName").joinToString("\n") { optString(it) },
        )

    // A track without synced lyrics has them as null, which optString turns into "null".
    private fun JSONObject.lyric(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.withLyrics(record: LyricRecord): JSONObject =
        JSONObject(toString())
            .put("syncedLyrics", record.synced ?: JSONObject.NULL)
            .put("plainLyrics", record.plain ?: JSONObject.NULL)
}
