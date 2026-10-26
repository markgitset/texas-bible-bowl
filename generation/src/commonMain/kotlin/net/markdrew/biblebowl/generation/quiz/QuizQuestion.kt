package net.markdrew.biblebowl.generation.quiz

import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.ChapterRef
import net.markdrew.biblebowl.model.LocationQuestion

/** What a quiz asks: a community-bank question ([BankQuestion]) or a where-is-this one ([PlaceQuestion]). */
sealed interface QuizQuestion {
    val id: String
    val prompt: String
    /** The right answer as shown once answered. */
    val answer: String
    /** The choices offered — the right one equals [answer]; empty when typed or self-graded. */
    val choices: List<String>
    /** References shown alongside the answer. */
    val references: List<String>
}

/** A community-bank question (Rounds 2 and 3), asked as written. */
data class BankQuestion(val dto: QuestionDto) : QuizQuestion {
    override val id: String get() = dto.id
    override val prompt: String get() = dto.prompt
    override val answer: String get() = dto.answer
    override val choices: List<String> get() = dto.choices
    override val references: List<String> get() = dto.references
}

/**
 * A where-is-this question (a heading or a verse): [location] holds every place the wording occurs,
 * and each is a right answer. Multiple choice offers exactly one of them ([answer]); a typed chapter
 * is right if it names any ([accepts]).
 */
data class PlaceQuestion(
    override val id: String,
    val location: LocationQuestion,
    override val answer: String,
    override val choices: List<String>,
    /** One line per place, in Bible order — what the answer feedback shows. */
    val placeLabels: List<String>,
    override val prompt: String = location.prompt,
) : QuizQuestion {

    override val references: List<String> get() = if (location.isRepeated) emptyList() else placeLabels

    /**
     * For a wording that occurs more than once, the note naming every place — shown after any answer,
     * right or wrong, before the quiz moves on; null otherwise.
     */
    val repeatNote: String?
        get() = if (!location.isRepeated) null
        else "This exact wording appears in ${placeLabels.size} places: ${placeLabels.joinToString(" · ")}"

    /** The books the answers sit in — the book choices a typed answer offers. */
    val books: List<Book> get() = location.answers.map { it.book }.distinct()

    /** Whether a typed answer names one of the places (by chapter; and book, in a multi-book set). */
    fun accepts(book: Book?, chapter: Int, multiBook: Boolean): Boolean =
        if (multiBook) book != null && location.acceptsChapter(ChapterRef(book, chapter))
        else location.answers.any { it.chapter == chapter }
}
