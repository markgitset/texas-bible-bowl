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

    @Test
    fun pointValuesMatchTheOfficialRoundDescription() {
        // site/content/event/round-description.md: rounds 1-5 are 40 points, the Power Round 50.
        assertEquals(40, Round.FIND_THE_VERSE.maxPoints)
        assertEquals(40, Round.FACT_FINDER.maxPoints)
        assertEquals(40, Round.IDENTIFICATION.maxPoints)
        assertEquals(40, Round.QUOTES.maxPoints)
        assertEquals(40, Round.EVENTS.maxPoints)
        assertEquals(50, Round.POWER.maxPoints)
    }

    @Test
    fun elementarySkipsThePowerRoundAndOthersEndWithIt() {
        assertFalse(Round.POWER in Division.ELEMENTARY.rounds)
        assertEquals(5, Division.ELEMENTARY.rounds.size)
        listOf(Division.JUNIOR, Division.SENIOR, Division.ADULT).forEach { division ->
            assertEquals(Round.POWER, division.rounds.last(), "$division takes the Power Round last")
            assertEquals(6, division.rounds.size)
        }
    }

    @Test
    fun maxScoresMatchTheOfficialScoringSummary() {
        // site/content/event/round-description.md: Elementary max 200, everyone else 250.
        assertEquals(200, Division.ELEMENTARY.maxScore)
        assertEquals(250, Division.JUNIOR.maxScore)
        assertEquals(250, Division.SENIOR.maxScore)
        assertEquals(250, Division.ADULT.maxScore)
    }
}
