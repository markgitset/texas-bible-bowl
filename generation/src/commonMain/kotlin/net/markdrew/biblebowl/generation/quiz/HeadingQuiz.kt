package net.markdrew.biblebowl.generation.quiz

import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.ChapterRef
import net.markdrew.biblebowl.model.LocationQuestion
import net.markdrew.biblebowl.model.StudySet
import net.markdrew.biblebowl.model.VerseRef
import net.markdrew.biblebowl.model.bookByCode
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

/** A heading's place: its book, its chapter, and the verse it starts at. */
fun HeadingDto.place(): VerseRef =
    VerseRef(bookCode?.let(::bookByCode) ?: error("heading \"$title\" has no book"), chapter, verse)

/** Marks a place outside the chapters being quizzed — still a right answer, but worth saying so. */
internal const val OUTSIDE_CHAPTERS = " (outside your chapters)"

/**
 * Turns the season's headings into "which chapter?" quiz items, one per distinct title among the
 * headings [inScope] chapters begin in. [headings] is the WHOLE set's list, so a title that recurs —
 * even outside the quizzed span — is one question that accepts every chapter it begins in. The
 * multiple-choice modes offer four distractors drawn from the other in-scope chapters, never another
 * chapter the title begins in (none-of-the-above is applied by [QuizEngine], not here); [TYPED] items
 * carry no choices — the UI grades the typed chapter with [PlaceQuestion.accepts].
 */
fun headingQuestions(
    headings: List<HeadingDto>,
    inScope: (ChapterRef) -> Boolean,
    multiBook: Boolean,
    mode: HeadingQuizMode,
    random: Random = Random.Default,
): List<PlaceQuestion> {
    fun label(h: HeadingDto) = headingChapterLabel(h.bookCode, h.chapter, multiBook)
    val scoped = headings.filter { inScope(it.place().chapterRef) }
    val chaptersInScope = scoped.map(::label).distinct()
    val byTitle = headings.groupBy { it.title }
    return scoped.distinctBy { it.title }.map { asked ->
        val twins = byTitle.getValue(asked.title).sortedBy { it.place() }
        val answer = label(asked)
        PlaceQuestion(
            id = "heading-${asked.index}",
            // Just the heading: the ask line above it already asks "which chapter?".
            location = LocationQuestion(asked.title, twins.map { it.place() }),
            answer = answer,
            choices = if (mode == HeadingQuizMode.TYPED) emptyList()
            else (chaptersInScope - twins.map(::label).toSet()).shuffled(random).take(4) + answer,
            placeLabels = twins.map { h ->
                val book = h.bookCode?.takeIf { multiBook }?.let(::bookByCode)?.briefName
                listOfNotNull(book, h.reference).joinToString(" ") +
                    if (inScope(h.place().chapterRef)) "" else OUTSIDE_CHAPTERS
            },
        )
    }
}

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
