package net.markdrew.biblebowl.generate.studyguide

import net.markdrew.biblebowl.model.StudyGuideQuestion
import net.markdrew.biblebowl.model.StudySet

/** Filename the cover references for the logo; the caller stages these bytes next to the compiled source. */
const val STUDY_GUIDE_LOGO_FILE: String = "tbb-logo.png"

private object StudyGuideAssets

/** The bundled Texas Bible Bowl logo bytes for the cover, or null if the asset is missing from the classpath. */
fun tbbLogoBytes(): ByteArray? =
    StudyGuideAssets.javaClass.getResourceAsStream("/images/$STUDY_GUIDE_LOGO_FILE")?.use { it.readBytes() }

/** Escapes the Typst structural delimiters (backslash first so escapes aren't double-escaped). */
private fun escapeTypst(s: String): String =
    s.replace("\\", "\\\\")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("#", "\\#")
        .replace("*", "\\*")
        .replace("_", "\\_")

/** The letter label ("A", "B", …) for the choice at 0-based [index]. */
private fun choiceLabel(index: Int): Char = 'A' + index

/**
 * Fixed Typst preamble: colors, fonts, heading styles, and the running header/footer. The book title (level-1
 * heading) and current chapter (level-2 heading) are looked up by page number so a section that starts on a
 * page still labels that page. Ported from bible-bowl's StudyGuidePdf.
 */
private val PREAMBLE = """
    #let accent = rgb("#1f3864")
    #let sans = "Liberation Sans"
    #set text(size: 11pt, font: "Libertinus Serif")
    #set par(justify: false, leading: 0.62em)
    #set heading(numbering: none)

    #show heading.where(level: 1): it => {
      pagebreak(weak: true)
      set align(center)
      set text(font: sans, size: 24pt, weight: "bold", fill: accent)
      block(above: 0.2in, below: 0.05in, upper(it.body))
      line(length: 2.2in, stroke: 0.8pt + accent)
      v(0.18in)
    }
    #show heading.where(level: 2): it => {
      set text(font: sans, size: 13pt, weight: "bold", fill: accent)
      block(above: 1.1em, below: 0.5em, {
        it.body
        v(2pt)
        line(length: 100%, stroke: 0.5pt + luma(180))
      })
    }

    #set page(
      paper: "us-letter",
      margin: (x: 0.85in, top: 1.0in, bottom: 0.9in),
      header: context {
        let curPg = here().page()
        let books = query(heading.where(level: 1)).filter(h => h.location().page() <= curPg)
        if books.len() == 0 { return }
        let book = books.last()
        let chaps = query(heading.where(level: 2)).filter(h => {
          let pg = h.location().page()
          pg >= book.location().page() and pg <= curPg
        })
        let chapLbl = if chaps.len() > 0 { chaps.last().body } else { [] }
        set text(font: sans, size: 8.5pt, fill: luma(90))
        grid(columns: (1fr, 1fr),
          align(left, upper(book.body)),
          align(right, chapLbl),
        )
        v(-4pt)
        line(length: 100%, stroke: 0.4pt + luma(200))
      },
      footer: context {
        set text(font: sans, size: 9pt, fill: luma(110))
        align(center)[#counter(page).display()]
      },
    )
""".trimIndent()

/**
 * Renders the multiple-choice study guide as Typst source: a text cover page, the questions grouped by book
 * and chapter (with a running header and per-question A–D choices), and a compact answer key at the end.
 * Ported from bible-bowl's StudyGuidePdf, minus its file I/O. [coverYear] prints on the cover and copyright
 * line. When [logoFile] is given, the cover shows `#image("<logoFile>")` — the caller must stage those bytes
 * next to the compiled source (see [tbbLogoBytes]); pass null for a text-only cover (e.g. no logo bundled).
 */
fun studyGuideTypst(
    questions: List<StudyGuideQuestion>,
    studySet: StudySet,
    coverYear: Int,
    logoFile: String? = null,
): String {
    // groupBy preserves first-encounter order, so books/chapters stay in the guide's natural order.
    val byBook: Map<String, List<StudyGuideQuestion>> = questions.groupBy { it.chapterRef.bookName }
    return buildString {
        appendLine(PREAMBLE)
        appendCover(studySet, coverYear, logoFile)
        appendQuestions(byBook)
        appendAnswerKey(byBook)
    }
}

/** The (header-less) cover page — optional logo, title, subtitle, and copyright — then restarts page numbering. */
private fun StringBuilder.appendCover(studySet: StudySet, coverYear: Int, logoFile: String?) {
    val title = escapeTypst(studySet.name.uppercase())
    // With a logo: a small top gap, the logo, then the label. Without: a larger gap so the title still centers.
    val masthead = if (logoFile != null) {
        "#v(0.7in)\n          #image(\"$logoFile\", width: 1.7in)\n          #v(0.35in)"
    } else {
        "#v(2.2in)"
    }
    appendLine(
        """
        #page(header: none, footer: none, margin: (x: 1in, y: 1in))[
          #set align(center)
          $masthead
          #text(font: sans, size: 13pt, tracking: 3pt, fill: luma(90))[STUDY GUIDE]
          #v(0.15in)
          #line(length: 40%, stroke: 0.8pt + accent)
          #v(0.3in)
          #text(font: sans, size: 30pt, weight: "bold", fill: accent)[$title]
          #v(0.2in)
          #text(size: 13pt, style: "italic")[Chapter Questions to Review]
          #v(0.15in)
          #text(font: sans, size: 12pt)[for Texas Bible Bowl $coverYear]
          #v(1fr)
          #line(length: 30%, stroke: 0.5pt + luma(160))
          #v(0.15in)
          #text(size: 9pt, fill: luma(90))[
            © $coverYear Texas Bible Bowl \
            This study guide, or any section thereof, may not be sold for profit \
            without written permission from the Texas Bible Bowl. \
            #link("https://www.texasbiblebowl.org")[www.texasbiblebowl.org]
          ]
        ]
        #counter(page).update(1)
        """.trimIndent()
    )
    appendLine()
}

/** Every question, grouped under a level-1 heading per book and a level-2 heading per chapter. */
private fun StringBuilder.appendQuestions(byBook: Map<String, List<StudyGuideQuestion>>) {
    for ((bookName, bookQuestions) in byBook) {
        if (byBook.size > 1) appendLine("= ${escapeTypst(bookName)}")
        for ((chapter, chapterQuestions) in bookQuestions.groupBy { it.chapterRef.chapter }) {
            appendLine("== Chapter $chapter")
            chapterQuestions.forEach { appendQuestion(it) }
        }
    }
}

/**
 * One question as an unbreakable, hanging-indented block: the number sits in a narrow left column while the
 * prompt (with a grey verse reference) and the A–D choices align in the wider right column.
 */
private fun StringBuilder.appendQuestion(q: StudyGuideQuestion) {
    val verseLabel = if (q.verseRefString.any { it == '-' || it == ',' || it == '–' }) "vv." else "v."
    val ref = escapeTypst(q.verseRefString)
    appendLine("#block(breakable: false, spacing: 1.2em)[")
    appendLine("  #grid(columns: (1.7em, 1fr), gutter: 0pt,")
    appendLine("    text(font: sans, weight: \"bold\", fill: accent)[${q.questionNum}.],")
    appendLine("    [")
    appendLine("      #set block(spacing: 0.8em)")
    appendLine("      ${escapeTypst(q.question)} #text(size: 9pt, fill: luma(110))[($verseLabel~$ref)]")
    appendLine("      #grid(columns: (1.4em, 1fr), row-gutter: 2.5pt,")
    q.choices.forEachIndexed { i, choice ->
        appendLine(
            "        text(font: sans, size: 9.5pt, weight: \"bold\", fill: accent)[${choiceLabel(i)}.], " +
                "[${escapeTypst(choice)}],"
        )
    }
    appendLine("      )")
    appendLine("    ],")
    appendLine("  )")
    appendLine("]")
}

/** Page-column count and per-chapter vertical sub-column count for the answer key layout. */
private const val ANSWER_KEY_PAGE_COLUMNS = 4
private const val ANSWER_KEY_SUBCOLUMNS = 3

/** The answer key: a level-1 heading, then a four-page-column flow of compact per-chapter answer blocks. */
private fun StringBuilder.appendAnswerKey(byBook: Map<String, List<StudyGuideQuestion>>) {
    appendLine("= Answer Key")
    appendLine("#set text(size: 9.5pt)")
    appendLine("#columns($ANSWER_KEY_PAGE_COLUMNS, gutter: 0.7em)[")
    for ((bookName, bookQuestions) in byBook) {
        appendLine("  #text(font: sans, size: 12pt, weight: \"bold\", fill: accent)[${escapeTypst(bookName)}]")
        appendLine("  #v(3pt)")
        for ((chapter, chapterQuestions) in bookQuestions.groupBy { it.chapterRef.chapter }) {
            appendAnswerKeyChapter(chapter, chapterQuestions)
        }
        appendLine("  #v(6pt)")
    }
    appendLine("]")
}

/**
 * One chapter's answers as an unbreakable block: a bold chapter label over [ANSWER_KEY_SUBCOLUMNS] vertical
 * sub-columns filled column-major (so answers read top-to-bottom), each a two-cell grid of right-aligned
 * number and left-aligned `-letter` so the hyphens line up.
 */
private fun StringBuilder.appendAnswerKeyChapter(chapter: Int, questions: List<StudyGuideQuestion>) {
    val sorted = questions.sortedBy { it.questionNum }
    val rowsPerColumn = (sorted.size + ANSWER_KEY_SUBCOLUMNS - 1) / ANSWER_KEY_SUBCOLUMNS
    val subColumns = (0 until ANSWER_KEY_SUBCOLUMNS).map { col ->
        val segment = sorted.drop(col * rowsPerColumn).take(rowsPerColumn)
        if (segment.isEmpty()) {
            "[]"
        } else {
            // Values are digits/hyphens/letters only, so no Typst escaping is needed.
            val cells = segment.joinToString(" ") { "[${it.questionNum}], [-${choiceLabel(it.correctAnswer)}]," }
            "[#grid(columns: (auto, auto), align: (right, left), row-gutter: 2.5pt, column-gutter: 0pt, $cells)]"
        }
    }
    appendLine("  #block(breakable: false, spacing: 0.7em)[")
    appendLine("    #text(font: sans, size: 9.5pt, weight: \"bold\")[Chapter $chapter]")
    appendLine("    #v(2pt)")
    appendLine("    #grid(columns: $ANSWER_KEY_SUBCOLUMNS, column-gutter: 0.7em, align: top, ${subColumns.joinToString(", ")})")
    appendLine("  ]")
}
