package net.markdrew.biblebowl.generation.quiz

import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.api.VerseQuestionDto
import net.markdrew.biblebowl.api.VersePlaceDto
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.ChapterRef
import net.markdrew.biblebowl.model.LocationQuestion
import net.markdrew.biblebowl.model.VerseRef
import net.markdrew.biblebowl.model.bookByCode
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
 * Turns sampled verse-quiz questions into quiz items. Each [VerseQuestionDto] lists every place its
 * wording occurs, and each is a right answer: the typed mode accepts any of their chapters, and the
 * heading-choice modes offer the heading over the place being quizzed (the first one [inScope], else
 * the first with a single heading) plus four distractors from the in-scope [headings] (the WHOLE
 * set's list, filtered here) — never another place's heading. A question no place of which sits under
 * a single heading is left out of the heading-choice modes; the typed mode keeps it.
 */
fun verseQuestions(
    questions: List<VerseQuestionDto>,
    headings: List<HeadingDto>,
    inScope: (ChapterRef) -> Boolean,
    multiBook: Boolean,
    mode: VerseQuizMode,
    random: Random = Random.Default,
): List<PlaceQuestion> {
    val scopedHeadings = headings.filter { inScope(it.place().chapterRef) }
    // Every heading a choice could name, so repeated titles are told apart across the whole quiz.
    val labels = headingChoiceLabels(
        (scopedHeadings + questions.flatMap { q -> q.places.mapNotNull { it.heading } }).distinctBy { it.index },
        multiBook,
    )
    fun VersePlaceDto.ref() = VerseRef(bookByCode(bookCode) ?: error("unknown book $bookCode"), chapter, verse)
    return questions.mapNotNull { q ->
        val places = q.places.sortedBy { it.ref() }
        if (places.isEmpty()) return@mapNotNull null
        val asked = places.firstOrNull { inScope(it.ref().chapterRef) } ?: places.first()
        val chapterLabel = headingChapterLabel(asked.bookCode, asked.chapter, multiBook)
        val choices: List<String>
        val answer: String
        if (mode == VerseQuizMode.TYPED) {
            choices = emptyList()
            answer = asked.heading?.let { "$chapterLabel — ${labels.getValue(it.index)}" } ?: chapterLabel
        } else {
            val heading = (listOf(asked) + places).firstNotNullOfOrNull { it.heading } ?: return@mapNotNull null
            val placeHeadings = places.mapNotNull { it.heading?.index }.toSet()
            answer = labels.getValue(heading.index)
            choices = scopedHeadings.filter { it.index !in placeHeadings }.distinctBy { it.index }
                .shuffled(random).take(4).map { labels.getValue(it.index) } + answer
        }
        PlaceQuestion(
            id = "verse-${asked.bookCode}-${asked.chapter}-${asked.verse}",
            location = LocationQuestion(q.text, places.map { it.ref() }),
            answer = answer,
            choices = choices,
            placeLabels = places.map { p ->
                val reference = briefBookName(p.bookCode, multiBook)?.let { "$it ${p.reference}" } ?: p.reference
                reference + (p.heading?.let { " — ${it.title}" } ?: "") +
                    if (inScope(p.ref().chapterRef)) "" else OUTSIDE_CHAPTERS
            },
        )
    }
}
