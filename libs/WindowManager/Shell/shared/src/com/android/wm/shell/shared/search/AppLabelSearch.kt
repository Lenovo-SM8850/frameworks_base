// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.shared.search

import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import android.icu.text.SearchIterator
import android.icu.text.StringSearch
import java.text.StringCharacterIterator
import java.util.Locale

/** Locale-aware substring matching at primary collation strength (case/diacritics insensitive). */
class AppLabelSearch(locale: Locale) {
    private val collator = (Collator.getInstance(locale) as RuleBasedCollator).apply {
        strength = Collator.PRIMARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }

    fun matches(label: String, query: String): Boolean {
        val text = query.trim()
        if (text.isEmpty()) return true
        if (label.isEmpty()) return false
        return StringSearch(text, StringCharacterIterator(label), collator).first() != SearchIterator.DONE
    }
}
