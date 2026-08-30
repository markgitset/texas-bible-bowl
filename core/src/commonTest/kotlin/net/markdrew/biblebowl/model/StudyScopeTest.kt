package net.markdrew.biblebowl.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ACTS = StandardStudySet.ACTS.set
private val MOSES = StandardStudySet.LIFE_OF_MOSES.set

private fun resolved(
    setParam: String? = null,
    bookParam: String? = null,
    chapter: Int? = null,
    fromChapter: Int? = null,
    cumulative: Boolean = false,
): StudyScope = assertIs<ScopeResolution.Resolved>(
    resolveStudyScope(setParam, bookParam, chapter, ACTS, fromChapter, cumulative)
).scope

private fun error(
    setParam: String? = null,
    bookParam: String? = null,
    chapter: Int? = null,
    season: StudySet = ACTS,
    fromChapter: Int? = null,
    cumulative: Boolean = false,
): ScopeError = assertIs<ScopeResolution.Invalid>(
    resolveStudyScope(setParam, bookParam, chapter, season, fromChapter, cumulative)
).error

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
        assertEquals(StudyScope(ACTS, Book.ACT, Book.ACT.chapterRange(22, 22)), resolved(chapter = 22))
    }

    @Test
    fun aLoneEndpointMeansWhateverItsOwnParameterMeant() {
        // The same `chapter = 5` is one chapter on an exact endpoint and a reach-back on a cumulative
        // one — which is the whole reason the caller's key is passed down rather than guessed here.
        assertEquals(Book.ACT.chapterRange(5, 5), resolved(chapter = 5).chapters)
        assertEquals(Book.ACT.chapterRange(1, 5), resolved(chapter = 5, cumulative = true).chapters)
    }

    @Test
    fun aStartAndAnEndMakeAnExplicitRange() {
        assertEquals(Book.ACT.chapterRange(3, 7), resolved(chapter = 7, fromChapter = 3).chapters)
        // Cumulative or not, naming both ends says the same thing — the start is no longer implied.
        assertEquals(Book.ACT.chapterRange(3, 7), resolved(chapter = 7, fromChapter = 3, cumulative = true).chapters)
    }

    @Test
    fun aLoneStartRunsToTheEndOfTheSet() {
        assertEquals(Book.ACT.chapterRange(26, 28), resolved(fromChapter = 26).chapters)
    }

    @Test
    fun aBackwardsRangeIsRejected() {
        assertIs<ScopeError.BackwardsChapterRange>(error(chapter = 3, fromChapter = 7))
        // Equal ends are a single chapter, not backwards.
        assertEquals(Book.ACT.chapterRange(4, 4), resolved(chapter = 4, fromChapter = 4).chapters)
    }

    @Test
    fun aRangeEndpointIsValidatedLikeAnyOtherChapter() {
        assertIs<ScopeError.ChapterNotInSet>(error(fromChapter = 99))
        assertIs<ScopeError.ChapterNotInSet>(error(setParam = "moses", bookParam = "EXO", fromChapter = 21))
        assertIs<ScopeError.BookRequired>(error(setParam = "moses", fromChapter = 3))
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
        assertEquals(
            StudyScope(MOSES, Book.NUM, Book.NUM.chapterRange(14, 14)),
            resolved(setParam = "moses", bookParam = "NUM", chapter = 14),
        )
    }

    @Test
    fun wholeMultiBookSetNeedsNoBook() {
        assertEquals(StudyScope(MOSES, null, null), resolved(setParam = "moses"))
    }

    @Test
    fun bareBookScopesToThatWholeBookIndependentOfTheSeason() {
        // Off-year study of John during the Acts season — the durable-URL case
        assertEquals(
            StudyScope(StandardStudySet.JOHN.set, Book.JOH, Book.JOH.chapterRange(3, 3)),
            resolved(bookParam = "JOH", chapter = 3),
        )
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
        val scope = StudyScope(ACTS, Book.ACT, Book.ACT.chapterRange(22, 22))
        assertEquals(listOf(Book.ACT.chapterRange(22, 22)), scope.ranges())
        assertEquals(Book.ACT.chapterRef(22), scope.singleChapterRef)
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
    fun aCumulativeSpanSkipsTheGapsOfAPartialSet() {
        // Exo 1-20, 32-34, Num 1-3, 10-14 … — the span reaches across books, ranges() clips the gaps.
        val scope = StudyScope(MOSES, Book.NUM, MOSES.chapterRanges.first().start..Book.NUM.chapterRef(14))
        assertEquals(
            listOf(
                Book.EXO.chapterRange(1, 20),
                Book.EXO.chapterRange(32, 34),
                Book.NUM.chapterRange(1, 3),
                Book.NUM.chapterRange(10, 14),
            ),
            scope.ranges(),
        )
    }

    @Test
    fun aCumulativeSpanForASingleBookSetMatchesTheSimpleCase() {
        val scope = StudyScope(ACTS, Book.ACT, Book.ACT.chapterRange(1, 5))
        assertEquals(listOf(Book.ACT.chapterRange(1, 5)), scope.ranges())
    }

    @Test
    fun anExplicitRangeIsClippedToTheSetAtBothEnds() {
        // Exo 18-33 straddles the set's 21-31 gap, so it comes back as the two pieces that survive.
        val scope = StudyScope(MOSES, Book.EXO, Book.EXO.chapterRange(18, 33))
        assertEquals(listOf(Book.EXO.chapterRange(18, 20), Book.EXO.chapterRange(32, 33)), scope.ranges())
    }

    @Test
    fun aMultiChapterScopeHasNoSingleChapter() {
        // The point of the null: file-name suffixes and the single-chapter practice content have no
        // meaning for a span, so they must not silently see one end of it.
        assertNull(StudyScope(ACTS, Book.ACT, Book.ACT.chapterRange(3, 7)).singleChapterRef)
        assertNull(StudyScope(ACTS, Book.ACT, null).singleChapterRef)
    }

    @Test
    fun coversAnswersForEveryShapeOfScope() {
        val range = StudyScope(ACTS, Book.ACT, Book.ACT.chapterRange(3, 7))
        assertTrue(range.covers(Book.ACT.chapterRef(3)) && range.covers(Book.ACT.chapterRef(7)))
        assertFalse(range.covers(Book.ACT.chapterRef(2)) || range.covers(Book.ACT.chapterRef(8)))

        // A book with no span is that book's slice; a bare set covers everything.
        val book = StudyScope(MOSES, Book.NUM, null)
        assertTrue(book.covers(Book.NUM.chapterRef(14)))
        assertFalse(book.covers(Book.EXO.chapterRef(1)))
        assertTrue(StudyScope(MOSES, null, null).covers(Book.EXO.chapterRef(1)))

        // Cumulative spans reach back across books, which is what makes them book-aware.
        val cumulative = StudyScope(MOSES, Book.NUM, MOSES.chapterRanges.first().start..Book.NUM.chapterRef(3))
        assertTrue(cumulative.covers(Book.EXO.chapterRef(20)))
        assertFalse(cumulative.covers(Book.NUM.chapterRef(14)))
    }
}
