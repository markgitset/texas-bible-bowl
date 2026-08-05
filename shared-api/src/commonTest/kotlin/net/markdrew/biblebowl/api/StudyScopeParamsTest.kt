package net.markdrew.biblebowl.api

import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.model.StudyScope
import kotlin.test.Test
import kotlin.test.assertEquals

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
    }

    @Test
    fun writesWholeBookScopesAsBareBookParams() {
        // A whole-book scope needs no set param — `book` alone reproduces it in any season.
        val acts = StandardStudySet.ACTS.set
        assertEquals(
            listOf("book" to "ACT", "chapter" to "22"),
            StudyScopeParams.write(StudyScope(acts, Book.ACT, 22)),
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
            StudyScopeParams.write(StudyScope(moses, Book.NUM, 14)),
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
            StudyScopeParams.write(StudyScope(ltc, Book.EXO, 7)),
        )
        val luke = StandardStudySet.LUKE.set
        assertEquals(
            listOf("book" to "LUK", "chapter" to "7"),
            StudyScopeParams.write(StudyScope(luke, Book.LUK, 7)),
        )
    }

    @Test
    fun writesCumulativeScopesUnderThroughChapter() {
        val acts = StandardStudySet.ACTS.set
        assertEquals(
            listOf("book" to "ACT", "throughChapter" to "5"),
            StudyScopeParams.write(StudyScope(acts, Book.ACT, 5), chapterKey = StudyScopeParams.THROUGH_CHAPTER),
        )
    }
}
