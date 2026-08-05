package net.markdrew.biblebowl.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ACTS = StandardStudySet.ACTS.set
private val MOSES = StandardStudySet.LIFE_OF_MOSES.set

private fun resolved(setParam: String? = null, bookParam: String? = null, chapter: Int? = null): StudyScope =
    assertIs<ScopeResolution.Resolved>(resolveStudyScope(setParam, bookParam, chapter, ACTS)).scope

private fun error(
    setParam: String? = null,
    bookParam: String? = null,
    chapter: Int? = null,
    season: StudySet = ACTS,
): ScopeError = assertIs<ScopeResolution.Invalid>(resolveStudyScope(setParam, bookParam, chapter, season)).error

class ChapterRangeChapterRefsTest {

    @Test
    fun enumeratesASimpleRange() {
        assertEquals(
            listOf(Book.EXO.chapterRef(32), Book.EXO.chapterRef(33), Book.EXO.chapterRef(34)),
            Book.EXO.chapterRange(32, 34).chapterRefs(),
        )
    }

    @Test
    fun clampsAnOpenEndedSentinelRangeToTheRealChapterCount() {
        val refs = Book.ACT.allChapters().chapterRefs()
        assertEquals(28, refs.size)
        assertEquals(Book.ACT.chapterRef(1), refs.first())
        assertEquals(Book.ACT.chapterRef(28), refs.last())
    }
}

class StudySetScopeHelpersTest {

    @Test
    fun chapterRefsSkipTheGapsOfPartialSets() {
        val refs = MOSES.chapterRefs
        assertEquals(MOSES.chapterCount, refs.size)
        assertTrue(Book.EXO.chapterRef(20) in refs)
        assertTrue(Book.EXO.chapterRef(21) !in refs) // gap: Exo 21-31 is not in the set
        assertTrue(Book.NUM.chapterRef(14) in refs)
        assertTrue(Book.NUM.chapterRef(15) !in refs) // gap: Num 15 is not in the set
        assertEquals(Book.EXO.chapterRef(1), refs.first())
        assertEquals(Book.DEU.chapterRef(34), refs.last())
    }

    @Test
    fun booksAndSingleBookFlag() {
        assertEquals(listOf(Book.EXO, Book.NUM, Book.DEU), MOSES.books)
        assertEquals(listOf(Book.ACT), ACTS.books)
        assertTrue(ACTS.isSingleBook)
        assertTrue(!MOSES.isSingleBook)
    }

    @Test
    fun rangesInRestrictsToOneBook() {
        assertEquals(5, MOSES.rangesIn(Book.NUM).size)
        assertEquals(emptyList(), MOSES.rangesIn(Book.JOH))
    }
}

class BookByCodeTest {

    @Test
    fun matchesEnumNameOrFullNameExactly() {
        assertEquals(Book.ACT, bookByCode("ACT"))
        assertEquals(Book.ACT, bookByCode("act"))
        assertEquals(Book.ACT, bookByCode("Acts"))
        assertEquals(Book.SA1, bookByCode("1 Samuel"))
    }

    @Test
    fun neverPrefixMatchesAndNeverDefaults() {
        assertNull(bookByCode("AC"))
        assertNull(bookByCode("Ac"))
        // Book.parse would default this to Matthew — bookByCode must not
        assertNull(bookByCode("zzznotabook"))
    }
}

class BookFromRefsTest {

    @Test
    fun parsesPrintedReferences() {
        assertEquals(Book.ACT, bookFromRefs(listOf("Acts 1:1")))
        assertEquals(Book.SA1, bookFromRefs(listOf("1 Samuel 3:4")))
        assertEquals(Book.JDG, bookFromRefs(listOf("Judges 5:1"))) // must not match Jude
        assertEquals(Book.JUD, bookFromRefs(listOf("Jude 5")))
    }

    @Test
    fun parsesSerializedReferences() {
        assertEquals(Book.ACT, bookFromRefs(listOf("ACT2:38")))
        assertEquals(Book.JUD, bookFromRefs(listOf("JUD1:5")))
    }

    @Test
    fun firstParseableReferenceWins() {
        assertEquals(Book.EXO, bookFromRefs(listOf("nonsense", "Exodus 20:1", "Numbers 14:2")))
        assertNull(bookFromRefs(listOf("nonsense", "")))
        assertNull(bookFromRefs(emptyList()))
    }
}

class ResolveStudyScopeTest {

    @Test
    fun noParamsResolvesToTheSeasonSet() {
        assertEquals(StudyScope(ACTS, Book.ACT, null), resolved())
    }

    @Test
    fun bareChapterResolvesAgainstASingleBookSeason() {
        assertEquals(StudyScope(ACTS, Book.ACT, 22), resolved(chapter = 22))
    }

    @Test
    fun bareChapterAgainstAMultiBookSeasonRequiresABook() {
        assertIs<ScopeError.BookRequired>(error(chapter = 5, season = MOSES))
    }

    @Test
    fun multiBookSetWithChapterButNoBookIsAnError() {
        val err = assertIs<ScopeError.BookRequired>(error(setParam = "moses", chapter = 3))
        assertTrue("Exodus" in err.message)
    }

    @Test
    fun multiBookSetWithBookAndChapterResolves() {
        assertEquals(StudyScope(MOSES, Book.NUM, 14), resolved(setParam = "moses", bookParam = "NUM", chapter = 14))
    }

    @Test
    fun wholeMultiBookSetNeedsNoBook() {
        assertEquals(StudyScope(MOSES, null, null), resolved(setParam = "moses"))
    }

    @Test
    fun bareBookScopesToThatWholeBookIndependentOfTheSeason() {
        // Off-year study of John during the Acts season — the durable-URL case
        assertEquals(StudyScope(StandardStudySet.JOHN.set, Book.JOH, 3), resolved(bookParam = "JOH", chapter = 3))
    }

    @Test
    fun bookOutsideTheSetIsRejected() {
        assertIs<ScopeError.BookNotInSet>(error(setParam = "moses", bookParam = "JOH"))
    }

    @Test
    fun chapterInAGapOfThePartialSetIsRejected() {
        assertIs<ScopeError.ChapterNotInSet>(error(setParam = "moses", bookParam = "NUM", chapter = 15))
        assertIs<ScopeError.ChapterNotInSet>(error(setParam = "moses", bookParam = "EXO", chapter = 21))
    }

    @Test
    fun chapterBeyondTheBookIsRejected() {
        assertIs<ScopeError.ChapterNotInSet>(error(chapter = 99))
        assertIs<ScopeError.ChapterNotInSet>(error(chapter = 0))
    }

    @Test
    fun unknownSetAndUnknownBookAreRejectedStrictly() {
        assertIs<ScopeError.UnknownSet>(error(setParam = "zzznotaset"))
        assertIs<ScopeError.UnknownSet>(error(setParam = "jos")) // prefix — bySlug must not match
        assertIs<ScopeError.UnknownBook>(error(bookParam = "zzznotabook"))
    }
}

class StudyScopeRangesTest {

    @Test
    fun singleChapterScopeCoversJustThatChapter() {
        val scope = StudyScope(ACTS, Book.ACT, 22)
        assertEquals(listOf(Book.ACT.chapterRange(22, 22)), scope.ranges())
    }

    @Test
    fun bookScopeCoversThatBooksSliceOfTheSet() {
        val scope = StudyScope(MOSES, Book.DEU, null)
        assertEquals(listOf(Book.DEU.chapterRange(31, 34)), scope.ranges())
    }

    @Test
    fun setScopeCoversTheWholeSet() {
        assertEquals(MOSES.chapterRanges, StudyScope(MOSES, null, null).ranges())
    }

    @Test
    fun throughIsCumulativeAndSkipsGaps() {
        val scope = StudyScope(MOSES, Book.NUM, 14)
        assertEquals(
            listOf(
                Book.EXO.chapterRange(1, 20),
                Book.EXO.chapterRange(32, 34),
                Book.NUM.chapterRange(1, 3),
                Book.NUM.chapterRange(10, 14),
            ),
            scope.through(),
        )
    }

    @Test
    fun throughForASingleBookSetMatchesTheSimpleCase() {
        assertEquals(listOf(Book.ACT.chapterRange(1, 5)), StudyScope(ACTS, Book.ACT, 5).through())
    }
}
