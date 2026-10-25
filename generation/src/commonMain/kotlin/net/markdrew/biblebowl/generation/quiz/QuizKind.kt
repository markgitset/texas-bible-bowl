package net.markdrew.biblebowl.generation.quiz

import net.markdrew.biblebowl.api.StudySection

/**
 * The quizzes, one per study section that has one. Each is its own screen at `quiz/<section slug>`
 * in both apps — a quiz never switches kind in place, so what you're drilling always matches the
 * section you came from.
 */
enum class QuizKind(val section: StudySection, val title: String) {
    /** The community question bank (Rounds 2 and 3). */
    GENERAL(StudySection.GENERAL, "General Knowledge"),
    /** ESV section headings → which chapter (Round 5). */
    HEADINGS(StudySection.HEADINGS, "Chapter Headings"),
    /** A verse → which heading, or which chapter. */
    VERSES(StudySection.VERSES, "Verses"),
    ;

    /** The URL segment after `quiz/` — the section's own slug. */
    val slug: String get() = section.slug

    /** The quiz lengths offered: the verse quiz is sampled server-side, so it has no "All". */
    val lengths: List<QuizLength>
        get() = if (this == VERSES) QuizLength.entries - QuizLength.ALL else QuizLength.entries.toList()

    /** The line above each prompt saying what's being asked. */
    fun askLine(item: QuizItem): String = when (this) {
        VERSES -> if (item.isMultipleChoice) "Which heading is this verse under?" else "Which chapter is this verse in?"
        else -> item.question.roundType.displayName
    }

    companion object {
        /** Strict slug lookup, like StudySection.bySlug — never a lenient parse. */
        fun bySlug(slug: String?): QuizKind? = entries.firstOrNull { it.slug == slug }

        /** The kind an old single-screen link (`#quiz?source=questions|headings`) asked for. */
        fun byLegacySource(source: String?): QuizKind? = when (source) {
            "questions" -> GENERAL
            "headings" -> HEADINGS
            else -> null
        }
    }
}
