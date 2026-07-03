package net.markdrew.biblebowl.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StandardStudySetTest {

    @Test
    fun parseOrNullMatchesAnExactEnumNameCaseInsensitively() {
        assertEquals(StandardStudySet.ACTS.set, StandardStudySet.parseOrNull("acts"))
        assertEquals(StandardStudySet.ACTS.set, StandardStudySet.parseOrNull("ACTS"))
    }

    @Test
    fun parseOrNullMatchesASimpleName() {
        assertEquals(StandardStudySet.JOSHUA_JUDGES_RUTH.set, StandardStudySet.parseOrNull("josh-judg-ruth"))
    }

    @Test
    fun parseOrNullMatchesAPrefixOfTheEnumName() {
        assertEquals(StandardStudySet.JOSHUA_JUDGES_RUTH.set, StandardStudySet.parseOrNull("joshua"))
    }

    @Test
    fun parseOrNullFallsBackToASingleBookStudySet() {
        assertEquals("mark", StandardStudySet.parseOrNull("Mark")?.simpleName)
    }

    @Test
    fun parseOrNullReturnsNullWhenNothingMatches() {
        assertNull(StandardStudySet.parseOrNull("zzznotabook"))
    }

    @Test
    fun parseReturnsTheDefaultForANullName() {
        assertEquals(StandardStudySet.DEFAULT, StandardStudySet.parse(null))
    }

    @Test
    fun parseFallsBackToTheDefaultForAnUnrecognizedName() {
        assertEquals(StandardStudySet.DEFAULT, StandardStudySet.parse("zzznotabook"))
    }
}
