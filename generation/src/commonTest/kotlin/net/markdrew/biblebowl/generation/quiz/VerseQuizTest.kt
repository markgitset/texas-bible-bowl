package net.markdrew.biblebowl.generation.quiz

import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.StudySection
import net.markdrew.biblebowl.api.VersePlaceDto
import net.markdrew.biblebowl.api.VerseQuestionDto
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.ChapterRef
import net.markdrew.biblebowl.model.Round
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerseQuizTest {

    private fun heading(index: Int, title: String = "Heading $index", chapter: Int = index, book: String = "ACT", ref: String = "$chapter:1-10") =
        HeadingDto(title = title, reference = ref, bookCode = book, chapter = chapter, index = index, total = 99)

    private val headings = (1..8).map { heading(it) }
    private val everywhere: (ChapterRef) -> Boolean = { true }

    private fun place(chapter: Int, verse: Int = 1, heading: HeadingDto? = headings.first { it.chapter == chapter }, book: String = "ACT") =
        VersePlaceDto(reference = "$chapter:$verse", bookCode = book, chapter = chapter, verse = verse, heading = heading)

    private fun verse(vararg places: VersePlaceDto, text: String = "Verse text ${places.first().reference}") =
        VerseQuestionDto(text, places.toList())

    @Test
    fun headingChoicesOfferTheVersesHeadingPlusFourInScopeDistractors() {
        listOf(VerseQuizMode.HEADING_CHOICE, VerseQuizMode.HEADING_CHOICE_NOTA).forEach { mode ->
            val q = verseQuestions(listOf(verse(place(3))), headings, everywhere, multiBook = false, mode = mode, random = Random(1)).single()
            assertEquals("Heading 3", q.answer)
            assertEquals(5, q.choices.size)
            assertEquals(5, q.choices.distinct().size)
            assertTrue(q.answer in q.choices)
            assertTrue(q.choices.all { c -> headings.any { it.title == c } })
            assertEquals("Verse text 3:1", q.prompt)
            assertEquals(listOf("3:1 — Heading 3"), q.references)
            assertNull(q.repeatNote)
        }
    }

    @Test
    fun distractorsComeFromTheQuizzedChaptersOnly() {
        val q = verseQuestions(listOf(verse(place(3))), headings, { it.chapter in 2..4 }, multiBook = false,
            mode = VerseQuizMode.HEADING_CHOICE).single()
        assertEquals(setOf("Heading 2", "Heading 3", "Heading 4"), q.choices.toSet())
    }

    @Test
    fun aVerseWithNoHeadingIsSkippedForChoicesButKeptWhenTyped() {
        val verses = listOf(verse(place(2)), verse(place(3, heading = null)))
        assertEquals(1, verseQuestions(verses, headings, everywhere, multiBook = false, mode = VerseQuizMode.HEADING_CHOICE).size)
        val typed = verseQuestions(verses, headings, everywhere, multiBook = false, mode = VerseQuizMode.TYPED)
        assertEquals(listOf("Chapter 2 — Heading 2", "Chapter 3"), typed.map { it.answer })
    }

    @Test
    fun typedItemsCarryNoChoicesAndGradeTheChapterAndBook() {
        val q = verseQuestions(listOf(verse(place(3))), headings, everywhere, multiBook = true, mode = VerseQuizMode.TYPED).single()
        assertTrue(q.choices.isEmpty())
        assertFalse(QuizEngine(listOf(q)).current!!.isMultipleChoice)
        assertTrue(q.accepts(Book.ACT, 3, multiBook = true))
        assertFalse(q.accepts(Book.NUM, 3, multiBook = true))
        assertFalse(q.accepts(Book.ACT, 4, multiBook = true))
        assertEquals(listOf("Acts 3:1 — Heading 3"), q.references)
        assertEquals("Acts 3 — Heading 3", q.answer)
    }

    @Test
    fun aRepeatedVerseAcceptsEveryPlaceAndSaysSo() {
        val refrain = verse(place(2, 4), place(5, 9), place(7, 2), text = "And it was so.")
        val typed = verseQuestions(listOf(refrain), headings, { it.chapter <= 5 }, multiBook = false,
            mode = VerseQuizMode.TYPED).single()
        assertTrue(listOf(2, 5, 7).all { typed.accepts(null, it, multiBook = false) }, "every place, even outside the span")
        assertFalse(typed.accepts(null, 3, multiBook = false))
        assertEquals(
            "This exact wording appears in 3 places: 2:4 — Heading 2 · 5:9 — Heading 5 · " +
                "7:2 — Heading 7 (outside your chapters)",
            typed.repeatNote,
        )

        val choice = verseQuestions(listOf(refrain), headings, everywhere, multiBook = false,
            mode = VerseQuizMode.HEADING_CHOICE, random = Random(2)).single()
        assertEquals("Heading 2", choice.answer)
        assertTrue(choice.choices.none { it == "Heading 5" || it == "Heading 7" }, "no other place's heading is a wrong choice")
    }

    @Test
    fun theRightHeadingFallsBackToAPlaceThatHasOne() {
        val q = verseQuestions(listOf(verse(place(2, heading = null), place(6))), headings, everywhere, multiBook = false,
            mode = VerseQuizMode.HEADING_CHOICE).single()
        assertEquals("Heading 6", q.answer)
    }

    @Test
    fun uniqueTitlesStayBare() {
        assertEquals(headings.associate { it.index to it.title }, headingChoiceLabels(headings, multiBook = false))
    }

    @Test
    fun duplicateTitlesNameTheirChapterOnlyWhenNeeded() {
        val twins = listOf(heading(1, "The Plague"), heading(2, "Another"), heading(3, "The Plague"))
        val labels = headingChoiceLabels(twins, multiBook = false)
        assertEquals("The Plague (Ch 1)", labels[1])
        assertEquals("Another", labels[2])
        assertEquals("The Plague (Ch 3)", labels[3])
    }

    @Test
    fun duplicateTitlesInMultiBookSetsNameTheBook() {
        val twins = listOf(heading(1, "The Census", chapter = 1, book = "NUM"), heading(2, "The Census", chapter = 1, book = "DEU"))
        val labels = headingChoiceLabels(twins, multiBook = true)
        assertEquals("The Census (Num 1)", labels[1])
        assertEquals("The Census (Deut 1)", labels[2])
    }

    @Test
    fun duplicateTitlesInOneChapterFallBackToTheReference() {
        val twins = listOf(heading(1, "A Psalm", chapter = 4, ref = "4:1-5"), heading(2, "A Psalm", chapter = 4, ref = "4:6-9"))
        val labels = headingChoiceLabels(twins, multiBook = false)
        assertEquals("A Psalm (4:1-5)", labels[1])
        assertEquals("A Psalm (4:6-9)", labels[2])
    }

    @Test
    fun theAnswerIsMatchedByIndexNotTitle() {
        val twins = listOf(heading(1, "The Plague", chapter = 1), heading(2, "The Plague", chapter = 5))
        val q = verseQuestions(listOf(verse(place(5, heading = twins[1]))), twins, everywhere, multiBook = false,
            mode = VerseQuizMode.HEADING_CHOICE).single()
        assertEquals("The Plague (Ch 5)", q.answer)
        assertEquals(setOf("The Plague (Ch 1)", "The Plague (Ch 5)"), q.choices.toSet())
    }

    @Test
    fun kindsResolveBySectionSlugAndLegacySource() {
        assertEquals(QuizKind.VERSES, QuizKind.bySlug(StudySection.VERSES.slug))
        assertEquals(QuizKind.HEADINGS, QuizKind.bySlug("chapter-headings"))
        assertNull(QuizKind.bySlug("headings"))
        assertEquals(QuizKind.GENERAL, QuizKind.byLegacySource("questions"))
        assertEquals(QuizKind.HEADINGS, QuizKind.byLegacySource("headings"))
        assertNull(QuizKind.byLegacySource("verses"))
        assertFalse(QuizLength.ALL in QuizKind.VERSES.lengths)
        assertTrue(QuizLength.ALL in QuizKind.GENERAL.lengths)
    }

    @Test
    fun askLineFollowsTheKind() {
        val bank = BankQuestion(QuestionDto(id = "q", roundType = Round.FACT_FINDER, prompt = "p", answer = "a",
            status = QuestionStatus.APPROVED, authorId = "x"))
        val typed = QuizItem(bank, emptyList(), -1)
        val choice = QuizItem(bank, listOf("a", "b"), 0)
        assertEquals(Round.FACT_FINDER.displayName, QuizKind.GENERAL.askLine(typed))
        assertEquals(Round.EVENTS.displayName, QuizKind.HEADINGS.askLine(choice))
        assertEquals("Which chapter is this verse in?", QuizKind.VERSES.askLine(typed))
        assertEquals("Which heading is this verse under?", QuizKind.VERSES.askLine(choice))
    }
}
