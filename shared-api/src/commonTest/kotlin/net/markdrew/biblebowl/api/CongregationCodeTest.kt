package net.markdrew.biblebowl.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CongregationCodeTest {

    @Test
    fun derivesMnemonicInitialsFromSignificantWords() {
        val candidates = congregationCodeCandidates("West Bexar County Church of Christ")
        // "Church", "of", "Christ" are dropped; the ranked head is the significant-word initials.
        assertEquals(listOf("WB", "WC", "BC"), candidates.take(3))
        // The first word's own opening letters come next (WEst), so the user's "even WE" is in range.
        assertTrue(candidates.indexOf("WE") in 3..6, "WE should rank just after the initial pairs: $candidates")
        // WB outranks BC (earlier words win).
        assertTrue(candidates.indexOf("WB") < candidates.indexOf("BC"))
    }

    @Test
    fun fallsBackToFirstWordThenBruteForce() {
        // A single significant word: first two letters lead.
        assertEquals("NO", congregationCodeCandidates("Northside Church of Christ").first())
        // Every code is two uppercase letters, and the full A–Z sweep guarantees coverage.
        val candidates = congregationCodeCandidates("A")
        assertTrue(candidates.all { it.length == 2 && it.all(Char::isUpperCase) })
        assertEquals(26 * 26, candidates.toSet().size, "the exhaustive fallback covers every AA..ZZ code")
    }

    @Test
    fun ignoresPunctuationAndExtraWhitespace() {
        val candidates = congregationCodeCandidates("  St. John's   Church ")
        // "St", "Johns" are the significant words (punctuation stripped) → SJ leads.
        assertEquals("SJ", candidates.first())
    }

    @Test
    fun namesMadeEntirelyOfStopWordsStillProduceCandidates() {
        // "Church of Christ" filters to nothing — fall back to the raw words rather than crash. The
        // leading candidate is then the initials of the first two words: Church + of = "CO".
        val candidates = congregationCodeCandidates("Church of Christ")
        assertEquals("CO", candidates.first())
    }
}
