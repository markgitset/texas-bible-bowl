package net.markdrew.biblebowl.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationQuestionTest {

    private val num7v13 = VerseRef(Book.NUM, 7, 13)
    private val num7v19 = VerseRef(Book.NUM, 7, 19)
    private val num12v5 = VerseRef(Book.NUM, 12, 5)

    @Test
    fun acceptsEveryPlaceTheWordingOccurs() {
        val q = LocationQuestion("his offering was one silver plate", listOf(num7v13, num7v19, num12v5))
        assertTrue(q.isRepeated)
        assertEquals(listOf(ChapterRef(Book.NUM, 7), ChapterRef(Book.NUM, 12)), q.chapters)
        assertTrue(q.acceptsChapter(ChapterRef(Book.NUM, 12)))
        assertFalse(q.acceptsChapter(ChapterRef(Book.NUM, 8)))
        assertTrue(q.acceptsVerse(num7v19))
        assertFalse(q.acceptsVerse(VerseRef(Book.NUM, 7, 14)))
    }

    @Test
    fun aSinglePlaceIsNotRepeated() {
        assertFalse(LocationQuestion("Jesus wept.", listOf(VerseRef(Book.JOH, 11, 35))).isRepeated)
    }

    @Test
    fun needsAnAnswer() {
        assertFailsWith<IllegalArgumentException> { LocationQuestion("x", emptyList()) }
    }

    @Test
    fun wordingIgnoresCaseAndWhitespaceButNotPunctuation() {
        val texts = listOf("And it was so.", "and  it\nwas so.", "And it was so", "Another verse.")
        assertEquals(
            listOf(listOf("And it was so.", "and  it\nwas so."), listOf("And it was so"), listOf("Another verse.")),
            groupByWording(texts) { it },
        )
    }
}
