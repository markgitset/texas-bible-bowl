package net.markdrew.biblebowl.api

/**
 * Canonical, param-encoded filenames for the season-text-deterministic PDFs. Shared by the app
 * (save-as name) and the server (Content-Disposition name AND the generated-PDF cache key), so the
 * two can never drift and every cached/downloaded file spells out exactly how it was generated.
 */
object PdfFileNames {

    /** The server's default bible-text font size; only non-default sizes appear in the name. */
    const val DEFAULT_FONT_SIZE: Int = 11

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
        append(".pdf")
    }

    fun namesIndex(): String = "names-index.pdf"

    fun numbersIndex(): String = "numbers-index.pdf"

    /** The Round 5 headings deck, cumulatively scoped, e.g. `heading-flashcards-through-ch5.pdf`. */
    fun headingFlashcards(throughChapter: Int? = null): String =
        "heading-flashcards${throughChapter?.let { "-through-ch$it" } ?: ""}.pdf"
}
