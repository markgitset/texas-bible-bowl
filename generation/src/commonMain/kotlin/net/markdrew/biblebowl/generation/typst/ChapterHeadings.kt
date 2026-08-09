package net.markdrew.biblebowl.generation.typst

/** One line of the chapter-headings sheet: an ESV section [title] and the [reference] it covers. */
data class ChapterHeadingRow(val title: String, val reference: String)

/**
 * The headings of one chapter — everything that *starts* in [chapter], including a heading that runs
 * on into the next one. These are the sheet's shading groups: a chapter's headings share a background
 * and the shade flips at each chapter, so the chapter divisions read off the page without a label.
 */
data class ChapterHeadingChapter(val chapter: Int, val headings: List<ChapterHeadingRow>)

/**
 * One book's chapters on the sheet. [book] labels the band printed above them; pass null for a
 * single-book study set, where the sheet title already names the book and bands would be noise.
 */
data class ChapterHeadingBook(val book: String?, val chapters: List<ChapterHeadingChapter>)

/** Page geometry of the sheet, in Typst units. Kept here so the fit search and the render agree. */
private const val PAGE_WIDTH = "8.5in"
private const val PAGE_HEIGHT = "11in"
private const val MARGIN_X = "0.45in"
private const val MARGIN_Y = "0.4in"
private const val GUTTER = "0.2in"

/**
 * The text sizes each column count may use, in quarter-points. These bounds only keep the search from
 * spending measures on layouts that could never win — a 4-column sheet at 16pt wraps every heading
 * twice over — so they can stay generous; which layout is actually chosen is decided by [CANDIDATES]
 * and [WRAP_TOLERANCE]. The top of the range is well above a normal body size on purpose: the rows
 * are tight, so a short set has to grow the text to fill its page rather than trail off half-empty.
 */
private val COLUMN_TIERS: List<Pair<Int, IntRange>> = listOf(
    2 to 32..64, // 8.0 – 16.0pt
    3 to 24..56, // 6.0 – 14.0pt
    4 to 18..44, // 4.5 – 11.0pt
)

/**
 * How much taller than its one-line-per-heading ideal a layout may be before the search passes it
 * over. Fitting alone is a bad objective: a big face in a narrow column can fit a short set while
 * wrapping half its headings, and that reads worse than the same sheet a size or two smaller with
 * every heading on one line. 1.12 is roughly one heading in eight. If nothing meets it — a set can
 * be dense enough that some wrapping is unavoidable — the search falls back to the largest layout
 * that merely fits.
 */
private const val WRAP_TOLERANCE = 1.12

/**
 * Candidate (column count, text size) layouts, best first: biggest text wins, and at equal size the
 * fewest columns (fewer columns are wider, so fewer headings wrap). The last candidate is the floor
 * — if even that overflows, Typst renders it anyway and the sheet spills onto a second page rather
 * than shrinking to unreadable.
 */
private val CANDIDATES: List<Pair<Int, Double>> = COLUMN_TIERS
    .flatMap { (columns, quarterPoints) -> quarterPoints.map { columns to it / 4.0 } }
    .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })

/**
 * Renders a one-page, at-a-glance listing of every ESV section heading in a study set as Typst
 * source: the set's title, then the headings in scripture order down flowing columns, each with the
 * verses it covers, shaded a chapter at a time so the chapter divisions are visible at a glance.
 *
 * "Fits on one page" is enforced by Typst rather than guessed here: at render time the sheet is
 * `measure`d at each of [CANDIDATES] in turn and drawn at the first one that both fits the page and
 * stays within [WRAP_TOLERANCE]. Measuring — rather than predicting a row height from the text size
 * — is what makes long headings that wrap to a second line count correctly, in both tests.
 *
 * The sheet is deliberately unlabelled apart from the book bands: the chapter shading and each row's
 * own reference carry the structure, which keeps a heading per line and the whole set on one page.
 */
fun chapterHeadingsTypst(
    title: String,
    books: List<ChapterHeadingBook>,
    subtitle: String = "Chapter Headings",
): String = buildString {
    appendLine(
        """
        #set page(paper: "us-letter", margin: (x: $MARGIN_X, y: $MARGIN_Y))
        // Only fonts the typst binary embeds: the prod image ships no system fonts.
        #set text(font: "Libertinus Serif")
        #set par(justify: false, leading: 0.5em)

        #let accent = rgb("#1f3864")
        // The two chapter shades. They carry the chapter divisions on their own (nothing else marks
        // them), so they need enough contrast to tell apart at a glance without going dark enough to
        // fight the text or waste toner over a full page.
        #let band_a = rgb("#c9dcf2")
        #let band_b = rgb("#edf3fb")

        #let gutter = $GUTTER
        #let content_width = $PAGE_WIDTH - 2 * $MARGIN_X
        #let content_height = $PAGE_HEIGHT - 2 * $MARGIN_Y

        #let books = (
        """.trimIndent()
    )

    books.forEach { book ->
        appendLine("""  (book: ${book.book?.let { "\"${escapeTypstString(it)}\"" } ?: "none"}, chapters: (""")
        book.chapters.forEach { chapter ->
            // The chapter number itself isn't drawn — each row's reference already carries it — so
            // only the grouping crosses into the markup.
            appendLine("    (")
            chapter.headings.forEach { h ->
                appendLine(
                    """      (title: "${escapeTypstString(h.title)}", """ +
                        """reference: "${escapeTypstString(h.reference)}"),"""
                )
            }
            appendLine("    ),")
        }
        appendLine("  )),")
    }

    appendLine(
        """
        )

        #let title_block = align(center)[
          #text(size: 16pt, weight: "bold", fill: accent)[${escapeTypst(title)}]
          #v(1pt)
          #text(size: 10pt, fill: luma(80))[${escapeTypst(subtitle)}]
          #v(4pt)
        ]

        #let row_inset = (x: 3.5pt, y: 1.4pt)

        // A heading row: the title, then its reference pushed out to the right edge. Non-breakable so
        // a wrapped title never splits across a column boundary, and with no spacing above or below so
        // that a chapter's rows meet and read as one solid block of colour rather than as stripes.
        #let heading_row(h, shade) = block(
          breakable: false,
          width: 100%,
          fill: shade,
          inset: row_inset,
          above: 0pt,
          below: 0pt,
          grid(
            columns: (1fr, auto),
            column-gutter: 8pt,
            h.title,
            text(fill: accent)[#h.reference],
          ),
        )

        #let book_band(name) = block(
          breakable: false,
          width: 100%,
          fill: accent,
          inset: (x: row_inset.x, y: 2.5pt),
          above: 4pt,
          below: 0pt,
          text(fill: white, weight: "bold")[#name],
        )

        #let candidates = (${CANDIDATES.joinToString(", ") { (cols, size) -> "($cols, ${size}pt)" }},)

        #let sheet(size) = {
          set text(size: size)
          // Wrapped titles run on at exactly the row pitch, so a two-line heading looks like one taller
          // row of its chapter's colour instead of opening a gap mid-block.
          set par(leading: 2 * row_inset.y)
          for b in books {
            if b.book != none { book_band(b.book) }
            // Shade by chapter, not by row, and alternate over the chapter's position rather than its
            // number — a set with gaps in it (Exodus 1-20 then 32-34) must not run two same-shaded
            // chapters together just because 20 and 32 are both even.
            for (i, chapter) in b.chapters.enumerate() {
              for h in chapter { heading_row(h, if calc.even(i) { band_a } else { band_b }) }
            }
          }
        }

        #context {
          let title_height = measure(block(width: content_width, title_block)).height
          let available = content_height - title_height
          // The sheet's height with every heading on one line: laid out far wider than any column,
          // so nothing can wrap. Comparing a candidate against this is how much wrapping it causes.
          let unwrapped(size) = measure(block(width: 40in, sheet(size))).height

          let fitting = none // first candidate that fits at all — the fallback
          let chosen = none  // ...and the first that also keeps wrapping under the tolerance
          for (cols, size) in candidates {
            let column_width = (content_width - (cols - 1) * gutter) / cols
            let height = measure(block(width: column_width, sheet(size))).height
            // The 0.97 leaves room for the rounding at each column break, where a row that doesn't
            // quite fit moves down whole.
            if height <= cols * available * 0.97 {
              if fitting == none { fitting = (cols, size) }
              if height <= unwrapped(size) * $WRAP_TOLERANCE {
                chosen = (cols, size)
                break
              }
            }
          }
          let (cols, size) = if chosen != none { chosen } else if fitting != none { fitting }
            else { candidates.last() }

          title_block
          columns(cols, gutter: gutter, sheet(size))
        }
        """.trimIndent()
    )
}
