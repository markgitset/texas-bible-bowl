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

class StudySetChapterCountTest {

    @kotlin.test.Test
    fun singleBookSetClampsOpenEndedRangeToTheRealChapterCount() {
        kotlin.test.assertEquals(28, StandardStudySet.ACTS.set.chapterCount)
        kotlin.test.assertEquals(24, StandardStudySet.LUKE.set.chapterCount)
    }

    @kotlin.test.Test
    fun multiBookSetsSumTheirRanges() {
        // Joshua (24) + Judges (21) + Ruth (4)
        kotlin.test.assertEquals(49, StandardStudySet.JOSHUA_JUDGES_RUTH.set.chapterCount)
        // Exo 1-20, 32-34 + Num 1-3, 10-14, 16-17, 20-27, 31-36 + Deut 31-34
        kotlin.test.assertEquals(20 + 3 + 3 + 5 + 2 + 8 + 6 + 4, StandardStudySet.LIFE_OF_MOSES.set.chapterCount)
    }
}
