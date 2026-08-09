package net.markdrew.biblebowl.api

/**
 * Canonical, param-encoded filenames for the season-text-deterministic PDFs. Shared by the app
 * (save-as name) and the server (Content-Disposition name AND the generated-PDF cache key), so the
 * two can never drift and every cached/downloaded file spells out exactly how it was generated.
 */
object PdfFileNames {

    /** The server's default bible-text font size; only non-default sizes appear in the name. */
    const val DEFAULT_FONT_SIZE: Int = 11

    /**
     * Prefixes [fileName] with the study-set slug, e.g. `acts-bible-text.pdf` — so files from
     * different seasons never collide in a Downloads folder, and the name matches the `set=` URLs.
     */
    fun withSet(setSlug: String, fileName: String): String = "$setSlug-$fileName"

    /** The covered-text PDF, e.g. `bible-text-highlighted-2col-14pt.pdf`. */
    fun bibleText(
        highlight: Boolean = true,
        twoColumns: Boolean = false,
        justified: Boolean = false,
        chapterBreaksPage: Boolean = false,
        useHeadingsForChapters: Boolean = false,
        chapterEndLines: Boolean = false,
        verseOnNewLine: Boolean = false,
        underlineUniqueWords: Boolean = false,
        fontSize: Int = DEFAULT_FONT_SIZE,
        chapterHeading: HeadingSize = HeadingSize.DEFAULT_CHAPTER,
        sectionHeading: HeadingSize = HeadingSize.DEFAULT_SECTION,
    ): String = buildString {
        append("bible-text")
        if (highlight) append("-highlighted")
        if (twoColumns) append("-2col")
        if (justified) append("-justified")
        if (chapterBreaksPage) append("-page-per-ch")
        if (useHeadingsForChapters) append("-ch-headings")
        if (chapterEndLines) append("-ch-lines")
        if (verseOnNewLine) append("-verse-per-line")
        if (underlineUniqueWords) append("-unique-words")
        if (fontSize != DEFAULT_FONT_SIZE) append("-${fontSize}pt")
        // Heading sizes are part of the layout, so they must reach the cache key — a PDF cached
        // under one pair of sizes must not be served for another.
        if (chapterHeading != HeadingSize.DEFAULT_CHAPTER) append("-ch-head-${chapterHeading.slug}")
        if (sectionHeading != HeadingSize.DEFAULT_SECTION) append("-sec-head-${sectionHeading.slug}")
        append(".pdf")
    }

    fun namesIndex(): String = "names-index.pdf"

    fun numbersIndex(): String = "numbers-index.pdf"

    fun menIndex(): String = "men-index.pdf"

    fun womenIndex(): String = "women-index.pdf"

    fun placesIndex(): String = "places-index.pdf"

    /** The complete word concordance. */
    fun fullIndex(): String = "full-index.pdf"

    /** The one-time-words (hapax) index — alphabetical + in order of appearance. */
    fun uniqueWordsIndex(): String = "unique-words-index.pdf"

    /** The one-time-words flashcard deck (word on the front, its verse on the back). */
    fun uniqueWordFlashcards(): String = "unique-word-flashcards.pdf"

    /** The multiple-choice study guide (cover, questions by chapter, answer key). */
    fun studyGuide(): String = "study-guide.pdf"

    /** The study-guide answer copy: each correct choice starred inline, with no answer key. */
    fun studyGuideAnswers(): String = "study-guide-answers.pdf"

    /** The Round 5 headings deck, cumulatively scoped, e.g. `heading-flashcards-through-ch5.pdf`. */
    fun headingFlashcards(throughChapter: Int? = null): String =
        "heading-flashcards${throughChapter?.let { "-through-ch$it" } ?: ""}.pdf"
}
