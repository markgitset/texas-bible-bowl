package net.markdrew.biblebowl.generate.text.typst

import net.markdrew.biblebowl.generate.text.AnnotatedDoc
import net.markdrew.biblebowl.generate.text.BibleTextHandler
import net.markdrew.biblebowl.generate.text.BibleTextWalker
import net.markdrew.biblebowl.generate.text.BibleTextWriter
import net.markdrew.biblebowl.generate.text.TextOptions
import net.markdrew.biblebowl.generate.text.HighlightContext
import net.markdrew.biblebowl.generate.text.Typst
import net.markdrew.biblebowl.generate.text.OutputFormat
import net.markdrew.biblebowl.generate.text.toAnnotatedDoc
import net.markdrew.biblebowl.analysis.oneTimeWords
import net.markdrew.biblebowl.model.AnalysisUnit
import net.markdrew.biblebowl.model.AnalysisUnit.UNIQUE_WORD
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.ChapterRef
import net.markdrew.biblebowl.model.FULL_BOOK_FORMAT
import net.markdrew.biblebowl.model.AnalysisUnit.BOOK
import net.markdrew.biblebowl.model.AnalysisUnit.CHAPTER
import net.markdrew.biblebowl.model.AnalysisUnit.FOOTNOTE
import net.markdrew.biblebowl.model.AnalysisUnit.HEADING
import net.markdrew.biblebowl.model.AnalysisUnit.LEADING_FOOTNOTE
import net.markdrew.biblebowl.model.AnalysisUnit.PARAGRAPH
import net.markdrew.biblebowl.model.AnalysisUnit.POETRY
import net.markdrew.biblebowl.model.AnalysisUnit.VERSE
import net.markdrew.biblebowl.model.StudyData
import net.markdrew.biblebowl.model.VerseRef
import net.markdrew.biblebowl.ws.DEFAULT_COPYRIGHT_DISCLAIMER
import net.markdrew.chupacabra.core.DisjointRangeSet

private val asteriskBracketedWordRegex = Regex("""\*([^*]+)\*""")

/**
 * A heading size in points, computed from the body size so it tracks it.
 *
 * Deliberately emitted as an absolute length, never as `em`: these sizes are set *inside*
 * `heading(...)`, where Typst has already applied its own 1.4em/1.2em, so a relative size would
 * compound with that rather than replace it (`1.4em` would render at 1.96em) — the same trap that
 * made a relative footnote size render at ~0.74em. Points sidestep it while still tracking the
 * body size, because the multiplication happens here.
 */
internal fun headingPt(fontSize: Int, scale: Double): String {
    val points = Math.round(fontSize * scale * 100.0) / 100.0
    // Whole sizes print as "14pt", not "14.0pt" — Typst accepts both; this keeps the source readable.
    return if (points % 1.0 == 0.0) points.toInt().toString() else points.toString()
}

/**
 * Renders [studyData] as a formatted Typst Bible-text document and returns the source (the server compiles
 * it to a PDF via TypstCompiler). Server-side entry point that replaces bible-bowl's file/CLI writer.
 *
 * The structural annotations (chapters, headings, verses, paragraphs, poetry, footnotes) come straight from
 * [StudyData]; pass a [doc] pre-loaded with REGEX/SMALL_CAPS/UNIQUE_WORD layers to add highlighting.
 */
fun bibleTextTypst(
    studyData: StudyData,
    options: TextOptions = TextOptions(),
    doc: AnnotatedDoc<AnalysisUnit> = studyData.toAnnotatedDoc(
        BOOK, CHAPTER, HEADING, VERSE, POETRY, PARAGRAPH, LEADING_FOOTNOTE, FOOTNOTE,
    ),
    copyrightDisclaimer: String = DEFAULT_COPYRIGHT_DISCLAIMER,
): String = buildString {
    // Underlining the study set's hapaxes (words that occur exactly once) is a pure structural feature —
    // it needs only StudyData.wordIndex, no NLP — so add its layer here rather than in the doc builders.
    // The walker gates the actual underline emission on options.underlineUniqueWords; adding the layer
    // unconditionally would be harmless, but skipping it when off avoids building the range set for nothing.
    if (options.underlineUniqueWords) {
        doc.setAnnotations(UNIQUE_WORD, DisjointRangeSet(oneTimeWords(studyData)))
    }
    BibleTextWalker.walk(doc, studyData, options, TypstHandler(this, options, copyrightDisclaimer))
}

private class TypstHandler(
    private val out: Appendable,
    private val options: TextOptions,
    private val copyrightDisclaimer: String,
) : BibleTextHandler {

    private val verseOnNewLine = options.verseOnNewLine

    /** Indent level of the poetry line currently being emitted; passed from [paragraphBegin] to
     *  [verseBegin] so the hanging verse number knows how far back to reach. 0 outside poetry. */
    private var currentPoetryIndentLevel = 0

    /** True until the first verse of the current prose paragraph is emitted; used by [verseOnNewLine]
     *  to skip the leading line break on a paragraph's opening verse. */
    private var firstVerseInParagraph = false

    private fun resolveTypstFont(fontName: String): String = when (fontName) {
        "Quattrocento Sans", "Times New Roman", "Times New Roman:liga", "Liberation Sans" -> "Libertinus Serif"
        else -> fontName
    }

    override fun documentBegin(studyData: StudyData, options: TextOptions) {
        val columns = if (options.twoColumns) 2 else 1
        val justify = if (options.justified) "true" else "false"
        val date = options.dateLine
        val title = studyData.studySet.name

        val mainFont = resolveTypstFont(options.mainFont)
        val headingFont = resolveTypstFont(options.headingFont)
        val verseNumFont = resolveTypstFont(options.verseNumFont)

        val chapterFontSize = headingPt(options.fontSize, options.chapterHeadingScale)
        val headingFontSize = headingPt(options.fontSize, options.sectionHeadingScale)

        out.appendLine("""
            #set page(
              paper: "us-letter",
              margin: (top: 1in, bottom: 0.75in, x: 0.75in),
              columns: $columns,
              header: context {
                let page-num = counter(page).get().first()
                let page-verses = query(<verse-marker>).filter(v => v.location().page() == page-num)
                let val = if page-verses.len() > 0 {
                  if calc.even(page-num) {
                    page-verses.first().value
                  } else {
                    page-verses.last().value
                  }
                } else {
                  let before-verses = query(<verse-marker>).filter(v => v.location().page() < page-num)
                  if before-verses.len() > 0 {
                    before-verses.last().value
                  } else {
                    ""
                  }
                }
                if val != "" {
                  if calc.even(page-num) {
                    align(left)[*#val*]
                  } else {
                    align(right)[*#val*]
                  }
                }
              },
              footer: context {
                let page-num = counter(page).get().first()
                if calc.even(page-num) {
                  grid(
                    columns: (1fr, 1fr),
                    align(left)[#counter(page).display()],
                    align(right)[Texas Bible Bowl, ${escape(date)}],
                  )
                } else {
                  grid(
                    columns: (1fr, 1fr),
                    align(left)[Texas Bible Bowl, ${escape(date)}],
                    align(right)[#counter(page).display()],
                  )
                }
              },
            )
            #set text(font: "$mainFont", size: ${options.fontSize}pt)
            #set par(justify: $justify)
            // Deliberately no `#show footnote.entry: set text(size: ...)`. Typst already renders
            // footnote entries at 0.85em, which tracks the body size and is always smaller than it —
            // exactly what we want. An absolute size here would break that at some body sizes (this
            // used to be a fixed 10pt, larger than the text below 10pt), and a relative one would
            // compound with the 0.85em rather than replace it (`0.87em` renders at ~0.74em).

            // Built-in highlight color — the default fill for divine names (matching DOCX)
            #let divineColor = rgb($DIVINE_R, $DIVINE_G, $DIVINE_B)
        """.trimIndent())

        // Palette-supplied colors (dedup against built-ins by name). Names (`other`) and numbers
        // (`numbers`) are ordinary palette entries now and emit their colors through this loop.
        val seen = mutableSetOf("divineColor")
        for ((color, _) in this.options.customHighlights.entries) {
            if (seen.add(color.name)) {
                val (r, g, b) = color.rgb
                out.appendLine("#let ${color.name} = rgb($r, $g, $b)")
            }
        }

        out.appendLine("""
            #let myhl(color, body) = highlight(fill: color, body)
            #let versenum(n) = box(
                fill: rgb("404040"),
                inset: (x: 3pt, y: 1pt),
                radius: 1pt,
                text(fill: white, weight: "bold", font: "$verseNumFont")[#n],
            )
            #let chapter-heading(label) = heading(
                level: 1, outlined: false,
                ${if (this.options.chapterEndLines) """
                grid(
                    columns: (1fr, auto, 1fr),
                    align: horizon,
                    gutter: 0.5em,
                    line(length: 100%, stroke: 0.5em + black),
                    text(font: "$headingFont", size: ${chapterFontSize}pt, weight: "bold")[#label],
                    line(length: 100%, stroke: 0.5em + black),
                )
                """.trimIndent().trim() else """
                text(font: "$headingFont", size: ${chapterFontSize}pt, weight: "bold")[#label],
                """.trimIndent().trim()}
            )
            #let section-heading(label) = heading(
                level: 2, outlined: false,
                text(font: "$headingFont", size: ${headingFontSize}pt, weight: "bold")[#label],
            )
            #let pstep = 2em
            #let pind(level) = h(pstep * level)
            // Poetry verse number: hangs into the whitespace to the left of the *first* indent
            // (`pstep`), regardless of this line's indent `level`, with zero net advance — so the
            // contents stay at `pstep * level` and the number sits near the margin at every level.
            #let pverse(n, level) = context {
                let label = versenum(n)
                let w = measure(label).width
                let gap = 0.3em
                let back = calc.max(level - 1, 0) * pstep + w + gap
                h(-back)
                label
                h(back - w)
            }
        """.trimIndent())
        out.appendLine()
    }

    override fun documentEnd() {
        out.appendLine()
        out.appendLine()
        val paragraphs = copyrightDisclaimer.split("\n\n")
            .map { escape(it.trim()) }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n  ")
        out.appendLine("""
            #v(1em)
            #align(center)[
              #set text(size: 0.85em)
              $paragraphs
            ]
        """.trimIndent())
    }

    override fun chapterBegin(chapter: ChapterRef, multiBook: Boolean, asHeading: Boolean, inParagraph: Boolean) {
        if (asHeading) {
            out.appendLine().appendLine("#chapter-heading[${escape(chapterLabel(chapter, multiBook))}]").appendLine()
        }
    }

    override fun chapterEnd(pageBreak: Boolean) {
        if (pageBreak) out.appendLine().appendLine("#pagebreak()")
    }

    override fun headingBegin(heading: String, inParagraph: Boolean) {
        out.appendLine().appendLine("#section-heading[${escape(heading)}]").appendLine()
    }

    override fun paragraphBegin(poetryIndentLevel: Int, inPoetry: Boolean, isFirstParagraphOfPoetry: Boolean) {
        // Emit the poetry line's indent here, before the verse number, so every line's contents start
        // at `pstep * level` whether or not the line begins with a verse number (the number hangs into
        // the whitespace to the left via pverse). Same-level lines therefore align by their contents.
        // Prose paragraphs and base-level (0) poetry lines start flush; line separation comes from
        // paragraphEnd(). Wrapped lines hang at `pstep * 4` via the block's hanging-indent setting.
        currentPoetryIndentLevel = if (inPoetry) poetryIndentLevel else 0
        firstVerseInParagraph = true
        if (inPoetry && poetryIndentLevel > 0) out.append("#pind($poetryIndentLevel)")
    }

    override fun paragraphEnd(inPoetry: Boolean) {
        // Every line — prose paragraph or poetry line — is separated by a blank line. For poetry
        // this makes each line its own paragraph so `hanging-indent` applies per line.
        out.appendLine().appendLine()
    }

    override fun poetryBegin() {
        // Scope poetry in a breakable block (so it can still split across columns/pages) that renders
        // lines single-spaced, ragged-left, and hanging-indented — matching esv.org.
        out.appendLine()
        out.appendLine("#block(breakable: true)[")
        out.appendLine("#set par(justify: false, spacing: 0.6em, hanging-indent: pstep * 4)")
    }

    override fun poetryEnd() {
        out.appendLine("]")
        out.appendLine()
    }

    override fun verseBegin(
        verse: VerseRef,
        chapter: ChapterRef,
        multiBook: Boolean,
        isFirstVerseOfChapter: Boolean,
        useHeadingsForChapters: Boolean,
        inPoetry: Boolean,
    ) {
        val formattedRef = escape(verse.format(FULL_BOOK_FORMAT))
        out.append("#metadata(\"$formattedRef\")<verse-marker>")
        // In poetry, the line break is produced by paragraphEnd; only prose needs the leading newline.
        if (!inPoetry) {
            // With verseOnNewLine, force a visual line break before every prose verse except the one
            // that opens the paragraph (which already starts a fresh line).
            if (verseOnNewLine && !firstVerseInParagraph) out.append("#linebreak()")
            out.appendLine()
        }
        firstVerseInParagraph = false
        if (isFirstVerseOfChapter && !useHeadingsForChapters) {
            // Inline chapter label at the start of the chapter's first verse — mirrors DOCX's
            // useHeadingsForChapters=false path.
            out.append("*${escape(chapterLabel(chapter, multiBook))}*")
        } else {
            // In poetry the number hangs left of the first indent (near the margin) while the contents
            // stay at their indent; in prose it sits inline.
            out.append(
                if (inPoetry) "#pverse(${verse.verse}, $currentPoetryIndentLevel)"
                else "#versenum(${verse.verse})"
            )
        }
    }

    override fun verseSeparator(inPoetry: Boolean) {
        // Prose separates the verse number from its text with a non-breaking space; in poetry the gap
        // is built into the hanging pverse, and the contents must stay flush to their indent.
        if (!inPoetry) out.append("~")
    }

    override fun bookBegin(book: Book) {
        // No emit — book heading is handled by the chapter label.
    }

    override fun poetryIndent(numIndents: Int) {
        // No-op — the poetry indent is emitted in paragraphBegin(), before the verse number, so the
        // contents (not the number) carry the indent and same-level lines align.
    }

    override fun leadingFootnote(verseRef: VerseRef, content: String) {
        out.append(renderFootnote(verseRef, content))
    }

    override fun trailingFootnote(verseRef: VerseRef, content: String, continuing: HighlightContext) {
        // Typst's #footnote[] works fine inside #highlight(...)[ ... ], so no close/reopen dance.
        out.append(renderFootnote(verseRef, content))
    }

    override fun uniqueWordBegin() { out.append("#underline[") }
    override fun uniqueWordEnd()   { out.append(']') }
    override fun regexBegin(category: String) { out.append("#myhl($category)[") }
    override fun regexEnd()        { out.append(']') }
    override fun smallCapsBegin() {} // Typst handles small caps via inline `LORD` substitution in text().
    override fun smallCapsEnd()   {}

    override fun text(text: String, inPoetry: Boolean, inParagraph: Boolean) {
        // Inter-line regions in poetry are whitespace only (the newlines between lines and ESV's
        // trailing post-poetry indent line); drop them so indentation is driven solely by pind().
        if (inPoetry && !inParagraph) return
        out.append(emitText(text))
    }

    private fun chapterLabel(chapterRef: ChapterRef, multiBook: Boolean): String =
        if (multiBook) chapterRef.format(FULL_BOOK_FORMAT) else "Chapter ${chapterRef.chapter}"

    private fun emitText(rawText: String): String =
        escape(rawText).replace("LORD".toRegex(), "#smallcaps[Lord]")

    private fun renderFootnote(verseRef: VerseRef, content: String): String {
        val escaped = escape(content)
        val italicized = escaped.replace(asteriskBracketedWordRegex, "#emph[\$1]")
        return "#footnote[${verseRef.format(FULL_BOOK_FORMAT)} $italicized]"
    }

    /**
     * Escapes the four Typst structural delimiters: `\`, `[`, `]`, `#`. Backslash must be escaped
     * first so we don't double-escape backslashes introduced by the other replacements.
     */
    private fun escape(s: String): String =
        s.replace("\\", "\\\\")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("#", "\\#")

    companion object {
        // Built-in divine highlight color — matching DOCX historical value. Names and numbers come from
        // the palette now (the `other` and `numbers` categories).
        private const val DIVINE_R = 255
        private const val DIVINE_G = 255
        private const val DIVINE_B = 0
    }
}
