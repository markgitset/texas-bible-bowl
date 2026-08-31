package net.markdrew.biblebowl.api

import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.ScopeResolution
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.model.StudyScope
import net.markdrew.biblebowl.model.resolveStudyScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StudyScopeParamsTest {

    @Test
    fun readsScopeParamsFromAnyLookup() {
        val params = mapOf("set" to "moses", "book" to "NUM", "chapter" to "14")
        assertEquals(
            StudyScopeParams.RawScope("moses", "NUM", 14),
            StudyScopeParams.read(params::get),
        )
        assertEquals(
            StudyScopeParams.RawScope(null, null, null),
            StudyScopeParams.read(get = { null }),
        )
        // Cumulative endpoints read the chapter under throughChapter instead.
        val cumulative = mapOf("throughChapter" to "7")
        assertEquals(
            StudyScopeParams.RawScope(null, null, 7),
            StudyScopeParams.read(cumulative::get, chapterKey = StudyScopeParams.THROUGH_CHAPTER),
        )
        // fromChapter reads the same under either endpoint key — narrowing the start means one thing.
        val ranged = mapOf("fromChapter" to "3", "throughChapter" to "7")
        assertEquals(
            StudyScopeParams.RawScope(null, null, 7, fromChapter = 3),
            StudyScopeParams.read(ranged::get, chapterKey = StudyScopeParams.THROUGH_CHAPTER),
        )
    }

    @Test
    fun writesWholeBookScopesAsBareBookParams() {
        // A whole-book scope needs no set param — `book` alone reproduces it in any season.
        val acts = StandardStudySet.ACTS.set
        assertEquals(
            listOf("book" to "ACT", "chapter" to "22"),
            StudyScopeParams.write(StudyScope(acts, Book.ACT, Book.ACT.chapterRange(22, 22))),
        )
        assertEquals(
            listOf("book" to "ACT"),
            StudyScopeParams.write(StudyScope(acts, Book.ACT, null)),
        )
    }

    @Test
    fun writesMultiBookSetScopesWithSetAndBook() {
        val moses = StandardStudySet.LIFE_OF_MOSES.set
        assertEquals(
            listOf("set" to "moses", "book" to "NUM", "chapter" to "14"),
            StudyScopeParams.write(StudyScope(moses, Book.NUM, Book.NUM.chapterRange(14, 14))),
        )
        assertEquals(
            listOf("set" to "moses"),
            StudyScopeParams.write(StudyScope(moses, null, null)),
        )
    }

    @Test
    fun neverCollapsesAPartialSetSliceToABareBook() {
        // moses-ltc covers only Exo 1-20, so its Exodus slice must keep the set slug — a bare
        // `book=EXO` would wrongly widen the scope to all of Exodus.
        val ltc = StandardStudySet.LIFE_OF_MOSES_LTC.set
        assertEquals(
            listOf("set" to "moses-ltc", "book" to "EXO", "chapter" to "7"),
            StudyScopeParams.write(StudyScope(ltc, Book.EXO, Book.EXO.chapterRange(7, 7))),
        )
        val luke = StandardStudySet.LUKE.set
        assertEquals(
            listOf("book" to "LUK", "chapter" to "7"),
            StudyScopeParams.write(StudyScope(luke, Book.LUK, Book.LUK.chapterRange(7, 7))),
        )
    }

    @Test
    fun writesCumulativeScopesUnderThroughChapter() {
        // A span starting at the set's own first chapter drops the redundant start, which is exactly
        // the pre-range spelling — the guarantee that no existing link changes.
        val acts = StandardStudySet.ACTS.set
        assertEquals(
            listOf("book" to "ACT", "throughChapter" to "5"),
            StudyScopeParams.write(
                StudyScope(acts, Book.ACT, Book.ACT.chapterRange(1, 5)),
                chapterKey = StudyScopeParams.THROUGH_CHAPTER,
            ),
        )
        // A single chapter under a cumulative key must spell its start — `throughChapter=7` alone
        // would read back as chapters 1-7 rather than just 7.
        assertEquals(
            listOf("book" to "ACT", "fromChapter" to "7", "throughChapter" to "7"),
            StudyScopeParams.write(
                StudyScope(acts, Book.ACT, Book.ACT.chapterRange(7, 7)),
                chapterKey = StudyScopeParams.THROUGH_CHAPTER,
            ),
        )
    }

    @Test
    fun keepsARangeStartOnExactChapterEndpoints() {
        // Only a cumulative key re-derives a dropped start; on an exact-chapter key `chapter=5`
        // alone reads back as chapter 5 on its own, so chapters 1-5 must keep its fromChapter.
        val acts = StandardStudySet.ACTS.set
        assertEquals(
            listOf("book" to "ACT", "fromChapter" to "1", "chapter" to "5"),
            StudyScopeParams.write(StudyScope(acts, Book.ACT, Book.ACT.chapterRange(1, 5))),
        )
    }

    @Test
    fun spellsARangeWithBothEndsAndNeitherRedundantOne() {
        val acts = StandardStudySet.ACTS.set
        assertEquals(
            listOf("book" to "ACT", "fromChapter" to "3", "chapter" to "7"),
            StudyScopeParams.write(StudyScope(acts, Book.ACT, Book.ACT.chapterRange(3, 7))),
        )
        // Running to the end of the set needs no endpoint: naming the last chapter would only go
        // stale if the set ever grew, and `fromChapter` alone already says "from here on".
        assertEquals(
            listOf("book" to "ACT", "fromChapter" to "26"),
            StudyScopeParams.write(StudyScope(acts, Book.ACT, Book.ACT.chapterRange(26, 28))),
        )
        // A span covering the whole set is just the set — canonicalised, not spelled out.
        assertEquals(
            listOf("book" to "ACT"),
            StudyScopeParams.write(StudyScope(acts, Book.ACT, Book.ACT.chapterRange(1, 28))),
        )
    }

    @Test
    fun everySpellingSurvivesARoundTrip() {
        // write -> read -> resolve must land on the scope we started from, for each shape, under the
        // endpoint key its own route would use. This is what keeps advertised canonical URLs honest.
        val acts = StandardStudySet.ACTS.set
        listOf(
            Book.ACT.chapterRange(22, 22) to StudyScopeParams.CHAPTER,
            Book.ACT.chapterRange(22, 22) to StudyScopeParams.THROUGH_CHAPTER,
            Book.ACT.chapterRange(1, 1) to StudyScopeParams.THROUGH_CHAPTER,
            Book.ACT.chapterRange(28, 28) to StudyScopeParams.THROUGH_CHAPTER,
            Book.ACT.chapterRange(1, 5) to StudyScopeParams.THROUGH_CHAPTER,
            Book.ACT.chapterRange(1, 5) to StudyScopeParams.CHAPTER,
            Book.ACT.chapterRange(3, 7) to StudyScopeParams.CHAPTER,
            Book.ACT.chapterRange(3, 7) to StudyScopeParams.THROUGH_CHAPTER,
            Book.ACT.chapterRange(26, 28) to StudyScopeParams.CHAPTER,
            Book.ACT.chapterRange(26, 28) to StudyScopeParams.THROUGH_CHAPTER,
        ).forEach { (span, key) ->
            val scope = StudyScope(acts, Book.ACT, span)
            val written = StudyScopeParams.write(scope, key).toMap()
            val raw = StudyScopeParams.read(written::get, key)
            val round = assertIs<ScopeResolution.Resolved>(
                resolveStudyScope(
                    raw.set, raw.book, raw.chapter, acts, raw.fromChapter,
                    cumulative = key == StudyScopeParams.THROUGH_CHAPTER,
                )
            ).scope
            assertEquals(scope.chapters, round.chapters, "round trip of $span under $key")
        }
    }

    @Test
    fun tapTogglesASingleSelectPicker() {
        val acts = StandardStudySet.ACTS.set
        assertEquals(ScopeSelection(Book.ACT, 5), ScopeSelection(Book.ACT).tap(acts, Book.ACT, 5))
        assertEquals(ScopeSelection(Book.ACT), ScopeSelection(Book.ACT, 5).tap(acts, Book.ACT, 5))
        // A stale range start (say, restored from a shared URL) never survives a single-select tap.
        assertEquals(
            ScopeSelection(Book.ACT, 7),
            ScopeSelection(Book.ACT, 5, fromChapter = 3).tap(acts, Book.ACT, 7),
        )
    }

    @Test
    fun tapPairsEveryTapWithThePreviousOne() {
        // 3, 7, 9 reads 1-3, 3-7, 7-9 — the first tap reaches back to the start, and each later tap
        // spans from the tap before it.
        val acts = StandardStudySet.ACTS.set
        var sel = ScopeSelection(Book.ACT).tap(acts, Book.ACT, 3, range = true)
        assertEquals(ScopeSelection(Book.ACT, 3, fromChapter = 1), sel)
        sel = sel.tap(acts, Book.ACT, 7, range = true)
        assertEquals(ScopeSelection(Book.ACT, 7, fromChapter = 3), sel)
        sel = sel.tap(acts, Book.ACT, 9, range = true)
        assertEquals(ScopeSelection(Book.ACT, 9, fromChapter = 7), sel)
        // Descending works the same — the anchor is simply the most recent tap. The raw pair may run
        // backwards; normalized() puts it in span order for labels, chips, and URLs.
        sel = sel.tap(acts, Book.ACT, 2, range = true)
        assertEquals(ScopeSelection(Book.ACT, 2, fromChapter = 9), sel)
        assertEquals(ScopeSelection(Book.ACT, 9, fromChapter = 2), sel.normalized())
    }

    @Test
    fun tapStepsDownToJustThatChapterThenClears() {
        val acts = StandardStudySet.ACTS.set
        val through5 = ScopeSelection(Book.ACT).tap(acts, Book.ACT, 5, range = true)
        assertEquals(ScopeSelection(Book.ACT, 5, fromChapter = 1), through5)
        val just5 = through5.tap(acts, Book.ACT, 5, range = true)
        assertEquals(ScopeSelection(Book.ACT, 5, fromChapter = 5), just5)
        // Clears to the same state as the All chip — no book either, on a single-book set.
        assertEquals(ScopeSelection(), just5.tap(acts, Book.ACT, 5, range = true))
    }

    @Test
    fun tapOnAnotherBookStartsFresh() {
        val moses = StandardStudySet.LIFE_OF_MOSES.set
        val numStart = moses.chapterRefs.first { it.book == Book.NUM }.chapter
        assertEquals(
            ScopeSelection(Book.NUM, 3, fromChapter = numStart),
            ScopeSelection(Book.EXO, 5).tap(moses, Book.NUM, 3, range = true),
        )
    }

    @Test
    fun labelsAndQueryParamsNormalizeTapOrder() {
        assertEquals("Acts 3-7", ScopeSelection(Book.ACT, 3, fromChapter = 7).label())
        assertEquals("Acts 1-5", ScopeSelection(Book.ACT, 5, fromChapter = 1).label())
        assertEquals("Acts 5", ScopeSelection(Book.ACT, 5, fromChapter = 5).label())
        val acts = StandardStudySet.ACTS.set
        assertEquals(
            listOf("book" to "ACT", "fromChapter" to "3", "chapter" to "7"),
            scopeQueryParams(acts, ScopeSelection(Book.ACT, 3, fromChapter = 7)),
        )
    }
}
