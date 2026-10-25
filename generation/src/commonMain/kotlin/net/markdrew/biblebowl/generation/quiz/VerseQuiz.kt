package net.markdrew.biblebowl.generation.quiz

import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.VerseDto
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.Round
import kotlin.random.Random

/** How a verse quiz asks where the verse is. */
enum class VerseQuizMode(val label: String) {
    HEADING_CHOICE("Pick the heading"),
    HEADING_CHOICE_NOTA("Pick the heading + none of the above"),
    TYPED("Type the chapter"),
}

/**
 * Answer-choice labels for [headings], keyed by [HeadingDto.index]. A title is shown bare unless
 * another heading in the list shares it — some study sets repeat a heading — and only then is it
 * disambiguated with its chapter ("Title (Ch 4)", book-qualified in multi-book sets), or with its
 * verse reference when the twins share a chapter too.
 */
fun headingChoiceLabels(headings: List<HeadingDto>, multiBook: Boolean): Map<Int, String> {
    val byTitle = headings.distinctBy { it.index }.groupBy { it.title }
    return headings.associate { h ->
        val twins = byTitle.getValue(h.title)
        val book = briefBookName(h.bookCode, multiBook)
        val label = when {
            twins.size == 1 -> h.title
            twins.count { it.bookCode == h.bookCode && it.chapter == h.chapter } == 1 ->
                "${h.title} (${book?.let { "$it ${h.chapter}" } ?: "Ch ${h.chapter}"})"
            else -> "${h.title} (${book?.let { "$it " }.orEmpty()}${h.reference})"
        }
        h.index to label
    }
}

/** The book's brief name ("Num") in multi-book sets, where chapters need it; null otherwise. */
private fun briefBookName(bookCode: String?, multiBook: Boolean): String? =
    bookCode?.takeIf { multiBook }?.let { code -> Book.entries.firstOrNull { it.name == code }?.briefName }

/**
 * Turns sampled [verses] into quiz items. The heading-choice modes offer the verse's own heading
 * plus four distractors drawn from [headings] (the headings of the quizzed span); verses with no
 * single heading are left out of them. [VerseQuizMode.TYPED] items carry no choices — the UI grades
 * the typed chapter with [typedAnswerCorrect] — and name the heading alongside the chapter in the
 * revealed answer.
 */
fun verseQuestions(
    verses: List<VerseDto>,
    headings: List<HeadingDto>,
    multiBook: Boolean,
    mode: VerseQuizMode,
    random: Random = Random.Default,
): List<QuestionDto> {
    // A verse's heading may have started before the span, so it joins the pool it's labelled within.
    val pool = (headings + verses.mapNotNull { it.heading }).distinctBy { it.index }
    val labels = headingChoiceLabels(pool, multiBook)
    return verses.mapNotNull { v ->
        val heading = v.heading
        val chapterLabel = headingChapterLabel(v.bookCode, v.chapter, multiBook)
        val reference = briefBookName(v.bookCode, multiBook)?.let { "$it ${v.reference}" } ?: v.reference
        val choices: List<String>
        val answer: String
        if (mode == VerseQuizMode.TYPED) {
            choices = emptyList()
            answer = heading?.let { "$chapterLabel — ${labels.getValue(it.index)}" } ?: chapterLabel
        } else {
            if (heading == null) return@mapNotNull null
            answer = labels.getValue(heading.index)
            val distractors = headings.filter { it.index != heading.index }.distinctBy { it.index }
                .shuffled(random).take(4).map { labels.getValue(it.index) }
            choices = distractors + answer
        }
        QuestionDto(
            id = "verse-${v.bookCode}-${v.chapter}-${v.verse}",
            roundType = Round.QUOTES,
            prompt = v.text,
            answer = answer,
            references = listOf(reference),
            choices = choices,
            bookCode = v.bookCode,
            chapter = v.chapter,
            status = QuestionStatus.APPROVED,
            authorId = "esv-verses",
        )
    }
}
