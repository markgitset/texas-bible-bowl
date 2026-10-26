package net.markdrew.biblebowl.generation.quiz

import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.ChapterRef
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

    private val everywhere: (ChapterRef) -> Boolean = { true }

    @Test
    fun choiceModesOfferFourInScopeDistractorsPlusTheAnswer() {
        HeadingQuizMode.entries.filter { it != HeadingQuizMode.TYPED }.forEach { mode ->
            val q = headingQuestions(headings, everywhere, multiBook = false, mode = mode, random = Random(1)).first()
            assertEquals(5, q.choices.size)
            assertTrue(q.answer in q.choices)
            assertEquals(5, q.choices.distinct().size)
            assertTrue(q.choices.all { it.removePrefix("Chapter ").toInt() in 1..8 })
        }
    }

    @Test
    fun distractorsStayInsideTheQuizzedChapters() {
        val q = headingQuestions(headings, { it.chapter in 2..5 }, multiBook = false, mode = HeadingQuizMode.CHOICE)
        assertEquals(listOf(2, 3, 4, 5), q.map { it.location.answers.single().chapter })
        assertTrue(q.all { it.choices.all { c -> c.removePrefix("Chapter ").toInt() in 2..5 } })
    }

    @Test
    fun promptIsJustTheHeading() {
        val q = headingQuestions(headings, everywhere, multiBook = false, mode = HeadingQuizMode.CHOICE)
            .first { it.answer == "Chapter 3" }
        assertEquals("Heading 3", q.prompt)
        assertEquals(listOf("3:1-10"), q.references)
        assertEquals(null, q.repeatNote)
    }

    @Test
    fun typedModeCarriesNoChoices() {
        val qs = headingQuestions(headings, everywhere, multiBook = false, mode = HeadingQuizMode.TYPED)
        assertTrue(qs.all { it.choices.isEmpty() })
        // Self-graded in the engine's eyes; the UI grades the typed chapter.
        assertFalse(QuizEngine(qs).current!!.isMultipleChoice)
    }

    @Test
    fun typedGradingChecksBookOnlyInMultiBookSets() {
        val q = headingQuestions(headings, everywhere, multiBook = true, mode = HeadingQuizMode.TYPED)
            .first { it.answer == "Acts 3" }
        assertTrue(q.accepts(Book.ACT, 3, multiBook = true))
        assertFalse(q.accepts(Book.NUM, 3, multiBook = true))
        assertFalse(q.accepts(null, 3, multiBook = true))
        assertFalse(q.accepts(Book.ACT, 4, multiBook = true))
        assertTrue(q.accepts(null, 3, multiBook = false))
    }

    @Test
    fun aRepeatedTitleIsOneQuestionThatAcceptsEveryChapter() {
        val twins = headings.map { if (it.chapter == 2 || it.chapter == 7) it.copy(title = "The Same") else it }
        val qs = headingQuestions(twins, { it.chapter <= 5 }, multiBook = false, mode = HeadingQuizMode.CHOICE, random = Random(4))
        val q = qs.single { it.prompt == "The Same" }
        assertEquals(1, qs.count { it.prompt == "The Same" })
        assertEquals("Chapter 2", q.answer)
        assertTrue(q.accepts(null, 2, multiBook = false))
        assertTrue(q.accepts(null, 7, multiBook = false), "the twin outside the quizzed chapters counts too")
        assertFalse("Chapter 7" in q.choices)
        assertEquals(
            "This exact wording appears in 2 places: 2:1-10 · 7:1-10 (outside your chapters)",
            q.repeatNote,
        )
        assertTrue(q.references.isEmpty(), "the repeat note names the places instead")
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
