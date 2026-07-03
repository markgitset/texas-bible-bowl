package net.markdrew.biblebowl.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VerseRefTest {

    @Test
    fun packAndUnpackRoundTrips() {
        val ref = VerseRef(Book.ACT, 2, 38) // Acts 2:38 — this season's book
        val packed = ref.absoluteVerse
        assertEquals(ref, packed.toVerseRef())
    }

    @Test
    fun parseFullReference() {
        assertEquals(VerseRef(Book.ACT, 2, 38), VerseRef.parse("Acts 2:38"))
        assertEquals(VerseRef(Book.JOH, 3, 16), VerseRef.parse("Joh 3:16"))
    }

    @Test
    fun parseBareVerseUsesDefaultChapter() {
        val acts2 = ChapterRef(Book.ACT, 2)
        assertEquals(VerseRef(Book.ACT, 2, 38), VerseRef.parse("38", acts2))
    }

    @Test
    fun parseTolueratesTrailingLetter() {
        val acts2 = ChapterRef(Book.ACT, 2)
        assertEquals(VerseRef(Book.ACT, 2, 38), VerseRef.parse("38a", acts2))
    }

    @Test
    fun versesSortInBibleOrder() {
        val unsorted = listOf(
            VerseRef(Book.ACT, 28, 31),
            VerseRef(Book.ACT, 1, 1),
            VerseRef(Book.JOH, 3, 16),
        )
        val sorted = unsorted.sorted()
        assertEquals(listOf(Book.JOH, Book.ACT, Book.ACT), sorted.map { it.book })
        assertEquals(1, sorted[1].chapter)
        assertEquals(28, sorted[2].chapter)
    }

    @Test
    fun rejectsNonPositiveVerse() {
        assertFailsWith<IllegalArgumentException> { VerseRef(Book.ACT, 1, 0) }
    }
}

class ChapterRefTest {

    @Test
    fun serializeDeserializeRoundTrips() {
        val ref = ChapterRef(Book.ACT, 9)
        assertEquals("ACT9", ref.serialize())
        assertEquals(ref, ChapterRef.deserialize(ref.serialize()))
    }

    @Test
    fun parseLenient() {
        assertEquals(ChapterRef(Book.ACT, 9), ChapterRef.parse("Acts 9"))
        assertEquals(ChapterRef(Book.ACT, 9), ChapterRef.parse("9", Book.ACT))
    }
}

class VerseRangeTest {

    @Test
    fun parseAndFormatWithinChapter() {
        val range = VerseRange.parse("Acts 2:38-39")
        assertEquals(VerseRef(Book.ACT, 2, 38), range.start)
        assertEquals(VerseRef(Book.ACT, 2, 39), range.endInclusive)
        assertEquals("Acts 2:38-39", range.format(FULL_BOOK_FORMAT))
    }

    @Test
    fun singleVerseRange() {
        val range = VerseRange.parse("Acts 2:38")
        assertTrue(range.start == range.endInclusive)
        assertEquals("Acts 2:38", range.format(FULL_BOOK_FORMAT))
    }
}

class BookTest {

    @Test
    fun actsIsBook44() {
        assertEquals(44, Book.ACT.number)
        assertEquals(Book.ACT, Book.fromNumber(44))
    }

    @Test
    fun parsePrefixOfFullName() {
        assertEquals(Book.ACT, Book.parse("Acts"))
        assertEquals(Book.ACT, Book.parse("ACT"))
    }
}
