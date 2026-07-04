package net.markdrew.biblebowl.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.markdrew.biblebowl.model.Round

class RoundTest {

    @Test
    fun onlyFactFinderAndIdentificationAreCrowdSourced() {
        assertTrue(Round.FACT_FINDER.crowdSourced)
        assertTrue(Round.IDENTIFICATION.crowdSourced)
        assertFalse(Round.FIND_THE_VERSE.crowdSourced)
        assertFalse(Round.QUOTES.crowdSourced)
        assertFalse(Round.EVENTS.crowdSourced)
        assertFalse(Round.POWER.crowdSourced)
    }

    @Test
    fun textGeneratedAndCrowdSourcedArePartitioned() {
        // R1, R4, R5 are text-generated; R2, R3 are crowd-sourced; the two flags never overlap.
        assertTrue(Round.FIND_THE_VERSE.textGenerated)
        assertTrue(Round.QUOTES.textGenerated)
        assertTrue(Round.EVENTS.textGenerated)
        Round.entries.forEach { assertFalse(it.crowdSourced && it.textGenerated, "$it can't be both") }
    }

    @Test
    fun crowdSourcedRoundsHelperListsExactlyR2AndR3() {
        assertEquals(listOf(Round.FACT_FINDER, Round.IDENTIFICATION), Round.crowdSourcedRounds)
    }
}
