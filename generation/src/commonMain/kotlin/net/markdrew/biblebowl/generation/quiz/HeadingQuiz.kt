package net.markdrew.biblebowl.generation.quiz

import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.model.StudySet
import kotlin.random.Random

/** How a chapter-headings quiz asks "which chapter?". */
enum class HeadingQuizMode(val label: String) {
    CHOICE("Multiple choice"),
    CHOICE_NOTA("Multiple choice + none of the above"),
    TYPED("Type the chapter"),
}

/** "Chapter 14" for single-book sets; book-qualified ("Num 14") for multi-book sets. */
fun headingChapterLabel(bookCode: String?, chapter: Int, multiBook: Boolean): String {
    val book = bookCode?.takeIf { multiBook }?.let { code -> Book.entries.firstOrNull { it.name == code } }
    return book?.let { "${it.briefName} $chapter" } ?: "Chapter $chapter"
}

/**
 * Turns the season's headings into "which chapter?" quiz items. The multiple-choice modes offer
 * four distractors drawn from the other chapters in scope, so a drill over a chapter span never
 * offers chapters outside it (none-of-the-above is applied by [QuizEngine], not here); [TYPED]
 * items carry no choices — the UI grades the typed chapter with [typedAnswerCorrect].
 */
fun headingQuestions(
    headings: List<HeadingDto>,
    multiBook: Boolean,
    mode: HeadingQuizMode,
    random: Random = Random.Default,
): List<QuestionDto> {
    fun label(h: HeadingDto) = headingChapterLabel(h.bookCode, h.chapter, multiBook)
    val chaptersInScope = headings.map(::label).distinct()
    return headings.map { h ->
        QuestionDto(
            id = "heading-${h.index}",
            roundType = Round.EVENTS,
            prompt = "Which chapter has the heading “${h.title}”?",
            answer = label(h),
            references = listOf(h.reference),
            choices = if (mode == HeadingQuizMode.TYPED) emptyList()
            else (chaptersInScope - label(h)).shuffled(random).take(4) + label(h),
            bookCode = h.bookCode,
            chapter = h.chapter,
            status = QuestionStatus.APPROVED,
            authorId = "esv-headings",
        )
    }
}

/** Whether a typed answer names [question]'s chapter (and, in multi-book sets, its book). */
fun typedAnswerCorrect(question: QuestionDto, book: Book?, chapter: Int, multiBook: Boolean): Boolean =
    chapter == question.chapter && (!multiBook || question.bookCode == null || book?.name == question.bookCode)

/**
 * True once the digits typed so far can only mean one chapter — no further digit could still name
 * a chapter of the book — so the answer can submit without an Enter. For a 28-chapter book "3"
 * is complete at once while "2" waits (it could become 20–28).
 */
fun chapterEntryComplete(typed: String, lastChapter: Int): Boolean {
    val n = typed.toIntOrNull() ?: return false
    return n > 0 && n * 10 > lastChapter
}

/** The last chapter of [book] that [set] covers (0 when the set doesn't cover it). */
fun StudySet.lastChapterIn(book: Book): Int =
    chapterRefs.filter { it.book == book }.maxOfOrNull { it.chapter } ?: 0

/**
 * One keyboard letter per book for picking it in a typed answer: the first letter of the brief name
 * not already taken, falling back to any free letter (so "Joshua, Judges, Ruth" → J, U, R).
 */
fun bookShortcutKeys(books: List<Book>): Map<Book, Char> {
    val taken = mutableSetOf<Char>()
    return books.associateWith { book ->
        val key = book.briefName.uppercase().firstOrNull { it in 'A'..'Z' && it !in taken }
            ?: ('A'..'Z').first { it !in taken }
        taken += key
        key
    }
}
