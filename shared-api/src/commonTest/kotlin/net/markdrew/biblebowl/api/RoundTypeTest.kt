package net.markdrew.biblebowl.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoundTypeTest {

    @Test
    fun onlyFactFinderAndIdentificationAreCrowdSourced() {
        assertTrue(RoundType.FACT_FINDER.crowdSourced)
        assertTrue(RoundType.IDENTIFICATION.crowdSourced)
        assertFalse(RoundType.FIND_THE_VERSE.crowdSourced)
        assertFalse(RoundType.KNOW_THE_CHAPTER_QUOTES.crowdSourced)
        assertFalse(RoundType.KNOW_THE_CHAPTER_HEADINGS.crowdSourced)
        assertFalse(RoundType.POWER.crowdSourced)
    }

    @Test
    fun textGeneratedAndCrowdSourcedArePartitioned() {
        // R1, R4, R5 are text-generated; R2, R3 are crowd-sourced; the two flags never overlap.
        assertTrue(RoundType.FIND_THE_VERSE.textGenerated)
        assertTrue(RoundType.KNOW_THE_CHAPTER_QUOTES.textGenerated)
        assertTrue(RoundType.KNOW_THE_CHAPTER_HEADINGS.textGenerated)
        RoundType.entries.forEach { assertFalse(it.crowdSourced && it.textGenerated, "$it can't be both") }
    }

    @Test
    fun crowdSourcedRoundsHelperListsExactlyR2AndR3() {
        assertEquals(listOf(RoundType.FACT_FINDER, RoundType.IDENTIFICATION), RoundType.crowdSourcedRounds)
    }
}
