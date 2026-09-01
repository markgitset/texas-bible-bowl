package net.markdrew.biblebowl.generate

/**
 * Layout revisions, one per Typst generator: a small one-up number for how the document is *drawn*,
 * independent of the material in it. Every server-compiled PDF prints its generator's entry in tiny
 * gray type in the bottom-right corner of its last page (see [stampLayoutRevision]; the server's
 * TypstCompiler applies it to every document), so any printed or downloaded copy names the layout
 * that produced it — "was this generated before or after that fix?" is answered by the corner mark.
 *
 * The same entry is folded into the server's PDF-cache stamp. A generated PDF is cached under the
 * study text plus the word-list digest, which moves when the *material* changes but not when the
 * *rendering* does — so without the revision in the key, a layout change is invisible to the cache
 * and clients keep getting the PDF an earlier build compiled, no matter how often it's redeployed
 * (that is how the chapter-grouped headings sheet failed to reach staging). The cache key must be
 * known before the markup is built, which is why the printed mark alone can't replace the salt.
 *
 * **Bump the entry for a generator whenever you change what it emits**, including changes in server
 * routes that feed it (which cards, which columns, what text). A bump costs one recompile per study
 * set; a missed bump costs a silently stale document — though since the corner mark went in, a stale
 * PDF at least names the revision it was drawn with, so the symptom is no longer invisible. When in
 * doubt, bump.
 *
 * Every entry that already existed moved when the corner mark was introduced: the mark itself is a
 * rendering change, and without a bump the cache would keep serving unmarked PDFs.
 */
object LayoutRevisions {
    /**
     * `bibleTextTypst`. 1 = footnotes relative to body text; 2 = headings relative to body text;
     * 3 = running head counts a verse that spills onto a page, not just the numbers printed on it;
     * 4 = the corner revision mark.
     */
    const val BIBLE_TEXT = 4

    /**
     * `chapterHeadingsTypst`. 1 = grouped and shaded by chapter, tighter rows, larger candidate
     * sizes; 2 = the corner revision mark; 3 = a chapter drawn as one labelled block, balanced
     * columns, row padding grown to fill the page, narrower margins, count in the subtitle;
     * 4 = verse-only references under the chapter block, hanging indent on a wrapped heading, and a
     * fit search that spends type size to avoid wrapping in the first place.
     */
    const val CHAPTER_HEADINGS = 4

    /**
     * `indexTypst` / `numbersIndexTypst` / `oneTimeWordsIndexTypst` — the printable word indices.
     * 2 = the corner revision mark.
     */
    const val INDEX = 2

    /**
     * `flashcardsTypst`, shared by the question-bank, unique-word, and chapter-heading decks.
     * 1 = Markdown rich text with the unique word underlined, which only the unique-word deck was
     * salted for at the time — the heading deck served the pre-Markdown rendering until 2 retired
     * it; 3 = the corner revision mark.
     */
    const val FLASHCARDS = 3

    /**
     * `studyGuideTypst`, for both the student and answer copies. 1 = the embedded-font fix;
     * 2 = the corner revision mark.
     */
    const val STUDY_GUIDE = 2

    /**
     * `practiceTestTypst` — the question-bank R2/R3 practice tests. 1 = the corner revision mark;
     * 2 = two-page fit search with unbreakable questions, the competition-style header the other
     * rounds use, inline choices, and chapter headings in the answer key.
     */
    const val PRACTICE_TEST = 2

    /**
     * `findTheVerseTypst` — the R1 text-generated practice test. 1 = the corner revision mark;
     * 2 = the answer-column header repeats on the second page, the sheet title shows its "#" before
     * the seed like the key does, and the clue size grows to fill the sheet's two sides.
     */
    const val FIND_THE_VERSE = 2

    /**
     * `quotesTypst` — the R4 text-generated practice test. 1 = the corner revision mark; 2 = two-page
     * fit search with unbreakable questions (choices tight under their question) and chapter headings
     * in the answer key.
     */
    const val QUOTES = 2

    /**
     * `eventsTypst` — the R5 text-generated practice test. 1 = the corner revision mark; 2 = two-page
     * fit search with unbreakable questions (choices tight under their question) and heading verse
     * spans in the answer key.
     */
    const val EVENTS = 2

    /** `nametagsTypst` — the event nametag sheets. 1 = the corner revision mark. */
    const val NAMETAGS = 1

    /** `awardsTypst` — the event award certificates. 1 = the corner revision mark. */
    const val AWARDS = 1
}

/** Labels the end-of-document marker [stampLayoutRevision] appends, locating the last physical page. */
private const val DOC_END_LABEL = "tbb-doc-end"

/**
 * Wraps complete Typst [typstSource] so the compiled document carries `r<revision>` in 5pt gray
 * inside the bottom-right corner of its last page — small enough to ignore, present enough to date
 * a printout against [LayoutRevisions].
 *
 * Mechanics, all chosen so no generator needs editing and no layout can shift: a `set page`
 * foreground rule (no generator uses `foreground`, and their own `set page` calls leave it alone)
 * draws the mark via `place` (out of flow, so nothing reflows) on the last *physical* page, found by
 * querying an invisible `metadata` marker appended after the source (a trailing tag materializes no
 * extra page, even after the flashcards' explicit `page()` calls). The page *counter* would be wrong
 * here: the study guide resets it for its answer key, so `counter(page).final()` names a page that
 * isn't the last sheet. The mark sits 0.2in into the page margin, clear of every generator's
 * footers, which end at margins of 0.5in or more.
 */
fun stampLayoutRevision(typstSource: String, revision: Int): String = """
    #set page(foreground: context {
      let ends = query(<$DOC_END_LABEL>)
      if ends.len() > 0 and here().page() == ends.last().location().page() {
        place(bottom + right, dx: -0.2in, dy: -0.2in, text(size: 5pt, fill: luma(50%))[r$revision])
      }
    })
""".trimIndent() + "\n" + typstSource + "\n#metadata(none) <$DOC_END_LABEL>\n"
