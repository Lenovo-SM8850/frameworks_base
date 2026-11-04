// SPDX-License-Identifier: Apache-2.0
package com.android.systemui.statusbar.quickactions.media.domain.interactor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LyricLanguagePolicyTest {
    private fun language(text: String): String? = LyricLanguagePolicy.scriptLanguage(text)
        ?: if (text.startsWith("English")) "en" else null
    private val mixed = LyricRecord("mixed", 240000,
        "[00:10.000]これは架空の試験行QZ17です\n[00:10.020]Dữ liệu thử nghiệm giả lập QZ17\n" +
        "[00:10.040]English fabricated test translation QZ17\n[00:20.000]これは架空の試験行QZ18です", null)

    @Test fun japaneseOriginal_rejectsVietnameseAtSameOrNearTimestamp() {
        val original = LyricLanguagePolicy.original(listOf(mixed), "架空試験曲七", ::language)
        assertEquals("ja", original)
        val result = LyricLanguagePolicy.select(mixed, original, "ko", ::language)!!
        assertEquals(listOf("これは架空の試験行QZ17です", "これは架空の試験行QZ18です"), LyricTiming.parse(result.synced!!).lines.map { it.text })
    }
    @Test fun koreanSystemLineWins_thenOriginal_thenEnglish() {
        val record = mixed.copy(synced = mixed.synced + "\n[00:10.060]가상 시험 문장 QZ17입니다\n[00:30]English fabricated fallback QZ18")
        val result = LyricLanguagePolicy.select(record, "ja", "ko", ::language)!!
        assertEquals(listOf("가상 시험 문장 QZ17입니다", "これは架空の試験行QZ18です", "English fabricated fallback QZ18"), LyricTiming.parse(result.synced!!).lines.map { it.text })
    }
    @Test fun translationOnlyIsHidden_andOriginalSetRanksFirst() {
        val foreign = mixed.copy(id = "vi", synced = "[00:10]Dữ liệu thử nghiệm giả lập QZ17")
        assertNull(LyricLanguagePolicy.select(foreign, "ja", "ko", ::language))
        assertNull(LyricLanguagePolicy.original(listOf(foreign), "Fictional Track QZ17", ::language))
        assertTrue(LyricLanguagePolicy.rank(mixed, "ja", "ko", ::language) < LyricLanguagePolicy.rank(foreign, "ja", "ko", ::language))
    }
    @Test fun foreignGapClearsPreviousLine_offsetAndDurationStayIntact() {
        val record = mixed.copy(synced = "[offset:-50]\n[00:10]これは架空の試験行QZ19です\n[00:20]Dữ liệu thử nghiệm giả lập QZ18")
        val result = LyricLanguagePolicy.select(record, "ja", "ko", ::language)!!
        val parsed = LyricTiming.parse(result.synced!!)
        assertEquals(-50L, parsed.lrcOffsetMs)
        assertEquals("", parsed.lines.last().text)
        assertEquals(240000L, result.durationMs)
    }
    @Test fun plainForeignTranslationIsHidden_butEnglishFallbackIsAllowed() {
        val foreign = LyricRecord("vi", 240000, null, "Dữ liệu thử nghiệm giả lập QZ17")
        assertNull(LyricLanguagePolicy.select(foreign, "ja", "ko", ::language))
        val english = foreign.copy(plain = "English fabricated fallback QZ18")
        assertEquals("English fabricated fallback QZ18", LyricLanguagePolicy.select(english, "ja", "ko", ::language)?.plain)
    }

    @Test fun fictionalLatinMetadata_doesNotMakeJapaneseTranslationOriginal() {
        val japanese = LyricRecord("ja", 219000, "[00:10]これは架空の試験行QZ29です", null,
            "Fictional Track QZ29\nFictional Ensemble QZ29\nFictional Album QZ29")
        val original = LyricLanguagePolicy.original(listOf(japanese), "Fictional Track QZ29", ::language,
            "Fictional Ensemble QZ29", "Fictional Album QZ29")
        assertNull(original)
        assertNull(LyricLanguagePolicy.select(japanese, original, "ko", ::language))
    }

    @Test fun fictionalKoreanCandidate_beatsJapaneseAndEnglish() {
        val japanese = LyricRecord("ja", 219000, "[00:10]これは架空の試験行QZ29です", null)
        val korean = japanese.copy(id = "ko", synced = "[00:10]가상 시험 문장 QZ29입니다")
        val english = japanese.copy(id = "en", synced = "[00:10]English fabricated fallback QZ18")
        val records = listOf(japanese, english, korean)
        val original = LyricLanguagePolicy.original(records, "Fictional Track QZ29", ::language, "Fictional Ensemble QZ29", "Fictional Album QZ29")
        val chosen = records.sortedBy { LyricLanguagePolicy.rank(it, original, "ko", ::language) }
            .firstNotNullOfOrNull { LyricLanguagePolicy.select(it, original, "ko", ::language) }
        assertEquals("ko", chosen?.id)
    }

    @Test fun systemCandidate_beatsPositivelyIdentifiedOriginalSet() {
        val japanese = mixed.copy(synced = "[00:10]これは架空の試験行QZ19です")
        val korean = japanese.copy(id = "ko", synced = "[00:10]가상 시험 문장 QZ17입니다")
        assertTrue(LyricLanguagePolicy.rank(korean, "ja", "ko", ::language) <
            LyricLanguagePolicy.rank(japanese, "ja", "ko", ::language))
    }

    @Test fun fictionalJapaneseAlbum_isPositiveEvidenceForOriginal() {
        val record = mixed.copy(metadata = "Fictional Track QZ17\nFictional Ensemble QZ17\n架空のアルバム七")
        val original = LyricLanguagePolicy.original(listOf(record), "Fictional Track QZ17", ::language,
            "Fictional Ensemble QZ17", "Fictional Album QZ18")
        assertEquals("ja", original)
        assertEquals(listOf("これは架空の試験行QZ17です", "これは架空の試験行QZ18です"),
            LyricTiming.parse(LyricLanguagePolicy.select(record, original, "ko", ::language)!!.synced!!)
                .lines.map { it.text })
    }

    @Test fun fictionalLatinMetadataWithoutDeclaredLanguage_cannotEstablishOriginal() {
        val japanese = mixed.copy(synced = "[00:10]これは架空の試験行QZ19です", metadata = "Fictional Track QZ17\nFictional Ensemble QZ17\nFictional Album QZ17")
        val original = LyricLanguagePolicy.original(listOf(japanese), "Fictional Track QZ17", ::language,
            "Fictional Ensemble QZ17", "Fictional Album QZ17")
        assertNull(original)
        assertNull(LyricLanguagePolicy.select(japanese, original, "ko", ::language))
    }

    @Test fun hanMetadata_requiresUnambiguousMatchingLyricScript() {
        val japanese = mixed.copy(metadata = "架空試験曲七")
        val chinese = mixed.copy(id = "zh", synced = "[00:10]虚构测试数据甲七")
        fun detect(text: String): String? = if (text == "虚构测试数据甲七") "zh" else language(text)
        assertEquals("ja", LyricLanguagePolicy.original(listOf(japanese), "架空試験曲七", ::detect))
        assertNull(LyricLanguagePolicy.original(listOf(japanese, chinese), "架空試験曲七", ::detect))
    }

    @Test fun unknownOriginal_keepsLinesOfTheSongInAnotherLanguage_synced() {
        val record = LyricRecord("ko", 200000,
            "[00:10]가상 시험 문장 QZ60입니다\n[00:20]English fabricated line QZ60\n[00:30]가상 시험 문장 QZ61입니다", null)
        for (system in listOf("en", "ja")) {
            val result = LyricLanguagePolicy.select(record, null, system, ::language)!!
            assertEquals(listOf("가상 시험 문장 QZ60입니다", "English fabricated line QZ60", "가상 시험 문장 QZ61입니다"),
                LyricTiming.parse(result.synced!!).lines.map { it.text })
        }
    }

    @Test fun unknownOriginal_keepsLinesOfTheSongInAnotherLanguage_plain() {
        val record = LyricRecord("ko", 200000, null,
            "가상 시험 문장 QZ60입니다\nEnglish fabricated line QZ60\n가상 시험 문장 QZ61입니다")
        for (system in listOf("en", "ja")) {
            val result = LyricLanguagePolicy.select(record, null, system, ::language)!!
            assertEquals(record.plain, result.plain)
            assertNull(result.synced)
        }
    }

    @Test fun hanMetadata_isDecidedByLyricScriptNotByTheClassifier() {
        val japanese = mixed.copy(metadata = "架空試験曲七")
        val chinese = mixed.copy(id = "zh", synced = "[00:10]虚构测试数据甲七")
        fun detect(text: String): String? =
            if (text == "架空試験曲七" || text == "虚构测试数据甲七") "zh" else language(text)
        assertEquals("ja", LyricLanguagePolicy.original(listOf(japanese), "架空試験曲七", ::detect))
        assertNull(LyricLanguagePolicy.original(listOf(japanese, chinese), "架空試験曲七", ::detect))
    }

    private fun item(id: String, durationSec: Double, synced: String?, plain: String?,
        album: String = "", track: String = "Fictional Track QZ40",
        artist: String = "Fictional Ensemble QZ40") =
        JSONObject().put("id", id).put("duration", durationSec)
            .put("trackName", track).put("artistName", artist)
            .put("albumName", album)
            .put("syncedLyrics", synced ?: JSONObject.NULL).put("plainLyrics", plain ?: JSONObject.NULL)

    // The length and synced rules of the lookup, which pick from the order.
    private fun pick(items: JSONArray, durationSec: Long?): JSONObject? {
        fun JSONObject.lyric(name: String) = if (isNull(name)) "" else optString(name, "").trim()
        val withLyrics = (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.lyric("syncedLyrics").isNotBlank() || it.lyric("plainLyrics").isNotBlank() }
        val matching = withLyrics.filter {
            durationSec == null || kotlin.math.abs(it.optDouble("duration", -1000.0) - durationSec) <= 3.0
        }
        return matching.firstOrNull { it.lyric("syncedLyrics").isNotBlank() }
            ?: matching.firstOrNull()
            ?: withLyrics.firstOrNull { it.lyric("syncedLyrics").isBlank() }
    }

    private fun classify(text: String): String? = if (text.startsWith("English")) "en" else null

    private fun order(items: JSONArray, durationSec: Long?, classify: (String) -> String? = ::classify) =
        LyricLanguagePolicy.order(items, "Fictional Track QZ40", "Fictional Ensemble QZ40", "ko",
            durationSec, 3.0, classify)

    private val englishSynced = "[00:10]English fabricated line QZ40\n[00:20]English fabricated line QZ41"
    private val spanishSynced = "[00:10]Linea ficticia de prueba QZ40\n[00:20]Linea ficticia de prueba QZ41"

    @Test fun englishSynced_beatsSystemPlainOfAnotherLength() {
        val items = JSONArray(listOf(item("ko", 300.0, null, "가상 시험 문장 QZ40입니다"),
            item("en", 200.0, englishSynced, null)))
        assertEquals("en", pick(order(items, 200), 200)?.optString("id"))
        assertEquals(pick(items, 200)?.optString("id"), pick(order(items, 200), 200)?.optString("id"))
    }

    @Test fun englishSynced_beatsUnknownSyncedOfTheSameLength() {
        val items = JSONArray(listOf(item("es", 201.0, spanishSynced, null),
            item("en", 199.0, englishSynced, null), item("ko", 260.0, null, "가상 시험 문장 QZ40입니다")))
        assertEquals("en", pick(order(items, 200), 200)?.optString("id"))
    }

    @Test fun systemSynced_beatsEnglishSyncedOfTheSameLength_butNotOfAnotherLength() {
        val korean = item("ko", 200.0, "[00:10]가상 시험 문장 QZ40입니다", null)
        val items = JSONArray(listOf(item("en", 200.0, englishSynced, null), korean))
        assertEquals("ko", pick(order(items, 200), 200)?.optString("id"))
        val far = JSONArray(listOf(item("en", 200.0, englishSynced, null),
            item("ko", 210.0, "[00:10]가상 시험 문장 QZ40입니다", null)))
        assertEquals("en", pick(order(far, 200), 200)?.optString("id"))
    }

    @Test fun syncedOfTheSameLength_beatsSystemPlainOfTheSameLength() {
        val items = JSONArray(listOf(item("ko", 200.0, null, "가상 시험 문장 QZ40입니다"),
            item("es", 200.0, spanishSynced, null)))
        val chosen = pick(order(items, 200), 200)
        assertEquals("es", chosen?.optString("id"))
        assertEquals(spanishSynced, chosen?.optString("syncedLyrics"))
    }

    @Test fun unknownLanguageSong_keepsTheLookupPick() {
        val items = JSONArray(listOf(item("es1", 230.0, null, "Linea ficticia de prueba QZ42"),
            item("es2", 200.0, spanishSynced, null), item("es3", 201.0, null, "Linea ficticia QZ43")))
        val ordered = order(items, 200) { null }
        assertEquals(items.toString(), ordered.toString())
        assertEquals("es2", pick(ordered, 200)?.optString("id"))
        assertEquals(pick(items, null)?.optString("id"), pick(order(items, null) { null }, null)?.optString("id"))
        val none = JSONArray(listOf(item("es1", 230.0, null, "Linea ficticia de prueba QZ42")))
        assertEquals("es1", pick(order(none, 200) { null }, 200)?.optString("id"))
    }

    @Test fun originalLanguage_comesOnlyFromResultsOfTheTrackLength() {
        val items = JSONArray(listOf(item("cover", 250.0, "[00:10]これは架空の試験行QZ40です", null, "架空のアルバム七"),
            item("ja", 200.0, "[00:10]これは架空の試験行QZ41です", null),
            item("en", 200.0, englishSynced, null)))
        assertEquals("en", pick(order(items, 200), 200)?.optString("id"))
        val album = JSONArray(listOf(item("ja", 200.0, "[00:10]これは架空の試験行QZ41です", null, "架空のアルバム七"),
            item("en", 200.0, englishSynced, null)))
        assertEquals("ja", pick(order(album, 200), 200)?.optString("id"))
    }

    @Test fun linesWithoutScriptEvidence_areClassifiedOncePerResult() {
        val calls = mutableListOf<String>()
        val items = JSONArray(listOf(item("en", 200.0, englishSynced, null),
            item("es", 200.0, spanishSynced, null), item("far", 260.0, englishSynced + "\n[00:30]English QZ49", null)))
        order(items, 200) { calls += it; classify(it) }
        assertEquals(2, calls.size)
    }

    private val koreanSynced = "[00:10]가상 시험 문장 QZ50입니다"

    @Test fun systemSyncedOfAnotherTitle_doesNotPassTheTrackSynced() {
        val other = item("other", 200.0, koreanSynced, null, track = "Another Fictional Track QZ50")
        val items = JSONArray(listOf(item("en", 200.0, englishSynced, null), other))
        val ordered = order(items, 200)
        assertEquals("en", pick(ordered, 200)?.optString("id"))
        assertEquals(other.toString(), ordered.getJSONObject(1).toString())
    }

    @Test fun matchingResults_keepTheLanguageRanking() {
        val items = JSONArray(listOf(item("en", 200.0, englishSynced, null),
            item("other", 200.0, koreanSynced, null, artist = "Another Fictional Ensemble QZ50"),
            item("ko", 200.0, koreanSynced, null, track = "Fictional Track QZ40 (Remastered)",
                artist = "FICTIONAL ENSEMBLE QZ40")))
        val ordered = LyricLanguagePolicy.order(items, "Fictional Track QZ40",
            "Fictional Ensemble QZ40", "ko", 200, 3.0, ::classify,
            cleanTitle = { it.removeSuffix(" (Remastered)") })
        assertEquals(listOf("ko", "other", "en"), (0 until ordered.length()).map {
            ordered.getJSONObject(it).optString("id")
        })
        assertEquals("ko", pick(ordered, 200)?.optString("id"))
    }

    @Test fun resultsOfAnotherTrack_keepTheirPlacesAndLyrics() {
        val otherTitle = item("otherTitle", 200.0, koreanSynced, null,
            track = "Another Fictional Track QZ50")
        val otherArtist = item("otherArtist", 200.0,
            "[00:10]これは架空の試験行QZ50です\n" + koreanSynced, null,
            artist = "Another Fictional Ensemble QZ50")
        val items = JSONArray(listOf(otherTitle, item("en", 200.0, englishSynced, null),
            otherArtist, item("ko", 200.0, koreanSynced, null)))
        val ordered = order(items, 200)
        assertEquals(otherTitle.toString(), ordered.getJSONObject(0).toString())
        assertEquals("ko", ordered.getJSONObject(1).optString("id"))
        assertEquals(otherArtist.toString(), ordered.getJSONObject(2).toString())
        assertEquals("en", ordered.getJSONObject(3).optString("id"))
    }
}
