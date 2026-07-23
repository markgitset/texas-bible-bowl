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
    fun roundsTwoThroughFiveAreScanGradedAndTheRestAreHandGraded() {
        // 2026 ran R2–R5 through ZipGrade (R4/R5 answers are bubbled chapter numbers); only
        // Find the Verse and the Power Round were hand-entered.
        assertTrue(Round.FACT_FINDER.scanGraded)
        assertTrue(Round.IDENTIFICATION.scanGraded)
        assertTrue(Round.QUOTES.scanGraded)
        assertTrue(Round.EVENTS.scanGraded)
        assertFalse(Round.FIND_THE_VERSE.scanGraded)
        assertFalse(Round.POWER.scanGraded)
        Round.entries.forEach { assertEquals(!it.scanGraded, it.handGraded, "$it handGraded is the complement") }
    }

    @Test
    fun scanGradedIsBroaderThanMultipleChoice() {
        // multipleChoice is answer-format (R2/R3 only); scanGraded is grading method (R2–R5).
        // Quotes and Events are scan-graded but NOT multiple-choice — the distinction G7 fixes.
        assertTrue(Round.QUOTES.scanGraded && !Round.QUOTES.multipleChoice)
        assertTrue(Round.EVENTS.scanGraded && !Round.EVENTS.multipleChoice)
        Round.entries.forEach { assertTrue(!it.multipleChoice || it.scanGraded, "$it: multipleChoice implies scanGraded") }
    }

    @Test
    fun scanGradedAndHandGradedRoundHelpersPartitionAllRounds() {
        assertEquals(
            listOf(Round.FACT_FINDER, Round.IDENTIFICATION, Round.QUOTES, Round.EVENTS),
            Round.scanGradedRounds,
        )
        assertEquals(listOf(Round.FIND_THE_VERSE, Round.POWER), Round.handGradedRounds)
        assertEquals(Round.entries.size, Round.scanGradedRounds.size + Round.handGradedRounds.size)
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
    fun teamScoresExcludeThePowerRoundAndMaxAtEightHundred() {
        // site/content/event/round-description.md: "Team (4 members) | 800 points" = 4 × 200.
        assertEquals(200, TEAM_MEMBER_MAX_POINTS)
        assertEquals(800, 4 * TEAM_MEMBER_MAX_POINTS)
        val members = listOf(
            mapOf(Round.FIND_THE_VERSE to 40, Round.POWER to 50), // Power never counts for the team
            mapOf(Round.QUOTES to 30),
            emptyMap(), // ungraded member contributes 0
        )
        assertEquals(70, teamPoints(members))
    }

    @Test
    fun ordinalsFollowEnglishSuffixRules() {
        assertEquals(
            listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "111th"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 111).map { ordinal(it) },
        )
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
