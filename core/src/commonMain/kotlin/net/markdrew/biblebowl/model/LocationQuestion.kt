package net.markdrew.biblebowl.model

/**
 * A "where is this?" question — the shape of every quiz, practice round, flashcard and export that
 * shows a piece of the text (a heading, a verse, a quote) and asks where it is. [answers] lists
 * EVERY place that wording occurs in the study set: usually one, but refrains and repeated headings
 * occur more than once, and each place is a right answer. A heading's place is its first verse, so
 * its chapter is the one it begins in.
 */
data class LocationQuestion(val prompt: String, val answers: List<VerseRef>) {

    init {
        require(answers.isNotEmpty()) { "A location question needs at least one answer" }
    }

    /** The chapters holding an answer, in Bible order, each once. */
    val chapters: List<ChapterRef> get() = answers.map { it.chapterRef }.distinct().sorted()

    /** True when the wording occurs in more than one place — worth pointing out to whoever's answering. */
    val isRepeated: Boolean get() = answers.size > 1

    fun acceptsChapter(chapter: ChapterRef): Boolean = answers.any { it.chapterRef == chapter }

    fun acceptsVerse(verse: VerseRef): Boolean = verse in answers
}

/** [text] compared as wording: case and runs of whitespace don't make two passages different. */
fun wordingKey(text: String): String = text.trim().replace(Regex("\\s+"), " ").lowercase()

/**
 * Groups [items] whose [text] is the same wording (see [wordingKey]), keeping first-seen order —
 * the groups that become one [LocationQuestion] each.
 */
fun <T> groupByWording(items: List<T>, text: (T) -> String): List<List<T>> =
    items.groupBy { wordingKey(text(it)) }.values.toList()
