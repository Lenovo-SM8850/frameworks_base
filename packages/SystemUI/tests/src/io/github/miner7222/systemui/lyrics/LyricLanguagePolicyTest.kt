// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.systemui.lyrics

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

    @Test fun languageRankingNeverAdmitsAnotherTrackOrDuration() {
        assertTrue(LyricLanguagePolicy.matchesTrack("Fictional Track QZ17", "Fictional Ensemble QZ17", 240000, "Fictional Track QZ17", "Fictional Ensemble QZ17", 242000))
        assertFalse(LyricLanguagePolicy.matchesTrack("Fictional Track QZ17", "Fictional Ensemble QZ17", 240000, "Fictional Track QZ17 live", "Fictional Ensemble QZ17", 240000))
        assertFalse(LyricLanguagePolicy.matchesTrack("Fictional Track QZ17", "Fictional Ensemble QZ17", 240000, "Fictional Track QZ17", "another artist", 240000))
        assertFalse(LyricLanguagePolicy.matchesTrack("Fictional Track QZ17", "Fictional Ensemble QZ17", 240000, "Fictional Track QZ17", "Fictional Ensemble QZ17", 242001))
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
}
