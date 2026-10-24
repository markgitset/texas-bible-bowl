package net.markdrew.biblebowl.generation.quiz

import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.model.Book
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeadingQuizTest {

    private val headings = (1..8).map { i ->
        HeadingDto(title = "Heading $i", reference = "$i:1-10", bookCode = "ACT", chapter = i, index = i, total = 8)
    }

    @Test
    fun labelsQualifyTheBookOnlyInMultiBookSets() {
        assertEquals("Chapter 14", headingChapterLabel("NUM", 14, multiBook = false))
        assertEquals("Num 14", headingChapterLabel("NUM", 14, multiBook = true))
        assertEquals("Chapter 3", headingChapterLabel(null, 3, multiBook = true))
    }

    @Test
    fun choiceModesOfferFourInScopeDistractorsPlusTheAnswer() {
        HeadingQuizMode.entries.filter { it != HeadingQuizMode.TYPED }.forEach { mode ->
            val q = headingQuestions(headings, multiBook = false, mode = mode, random = Random(1)).first()
            assertEquals(5, q.choices.size)
            assertTrue(q.answer in q.choices)
            assertEquals(5, q.choices.distinct().size)
            assertTrue(q.choices.all { it.removePrefix("Chapter ").toInt() in 1..8 })
        }
    }

    @Test
    fun promptIsJustTheHeading() {
        val q = headingQuestions(headings, multiBook = false, mode = HeadingQuizMode.CHOICE).first { it.chapter == 3 }
        assertEquals("Heading 3", q.prompt)
    }

    @Test
    fun typedModeCarriesNoChoices() {
        val qs = headingQuestions(headings, multiBook = false, mode = HeadingQuizMode.TYPED)
        assertTrue(qs.all { it.choices.isEmpty() })
        // Self-graded in the engine's eyes; the UI grades the typed chapter.
        assertFalse(QuizEngine(qs).current!!.isMultipleChoice)
    }

    @Test
    fun typedGradingChecksBookOnlyInMultiBookSets() {
        val q = headingQuestions(headings, multiBook = true, mode = HeadingQuizMode.TYPED).first { it.chapter == 3 }
        assertTrue(typedAnswerCorrect(q, Book.ACT, 3, multiBook = true))
        assertFalse(typedAnswerCorrect(q, Book.NUM, 3, multiBook = true))
        assertFalse(typedAnswerCorrect(q, null, 3, multiBook = true))
        assertFalse(typedAnswerCorrect(q, Book.ACT, 4, multiBook = true))
        assertTrue(typedAnswerCorrect(q, null, 3, multiBook = false))
    }

    @Test
    fun chapterEntryCompletesWhenNoLongerNumberIsPossible() {
        assertTrue(chapterEntryComplete("3", 28))
        assertFalse(chapterEntryComplete("2", 28))
        assertTrue(chapterEntryComplete("28", 28))
        assertTrue(chapterEntryComplete("1", 1))
        assertFalse(chapterEntryComplete("1", 10))
        assertTrue(chapterEntryComplete("10", 10))
        assertFalse(chapterEntryComplete("0", 28))
        assertFalse(chapterEntryComplete("", 28))
    }

    @Test
    fun bookKeysPreferFirstFreeLetter() {
        assertEquals(
            mapOf(Book.JOS to 'J', Book.JDG to 'D', Book.RUT to 'R'),
            bookShortcutKeys(listOf(Book.JOS, Book.JDG, Book.RUT)),
        )
    }
}
