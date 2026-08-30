package net.markdrew.biblebowl.generation.typst

/**
 * One line of the chapter-headings sheet: an ESV section [title] and the [reference] it covers.
 *
 * The row is drawn under its chapter's number, so [reference] is expected to be verses only ("12-26")
 * for a heading that stays inside its chapter, and to name both chapters ("21:37-22:21") only for one
 * that runs on into the next.
 */
data class ChapterHeadingRow(val title: String, val reference: String)

/**
 * The headings of one chapter — everything that *starts* in [chapter], including a heading that runs
 * on into the next one. A chapter is the sheet's unit: its headings are drawn as one block, labelled
 * with its number, and a column is only ever cut between blocks, never inside one.
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
private const val MARGIN_X = "0.35in"
private const val MARGIN_Y = "0.35in"
private const val GUTTER = "0.18in"

/**
 * The text sizes each column count may use, in quarter-points. These bounds only keep the search from
 * spending measures on layouts that could never win — a 4-column sheet at 24pt wraps every heading
 * twice over — so they can stay generous; which layout is actually chosen is decided by [CANDIDATES]
 * and [WRAP_TOLERANCE]. The top of the range is well above a normal body size on purpose: a short set
 * has to grow its text to fill the page rather than trail off half empty.
 */
private val COLUMN_TIERS: List<Pair<Int, IntRange>> = listOf(
    2 to 32..96, // 8.0 – 24.0pt
    3 to 28..72, // 7.0 – 18.0pt
    4 to 26..56, // 6.5 – 14.0pt
)

/**
 * How much taller than its one-line-per-heading ideal a layout may be before the search passes it
 * over. Fitting alone is a bad objective: a big face in a narrow column can fit a short set while
 * wrapping half its headings, and that reads worse than the same sheet a size or two smaller with
 * every heading on one line. 1.03 leaves room for the odd unavoidably long title — Acts and the Life
 * of Moses land on one wrapped heading each, Joshua/Judges/Ruth on two — while pulling the sets that
 * were wrapping six or eight of them back onto one line, at a cost of about a point of type. Chasing
 * zero costs two or three points, which is more than a wall chart can spare. If nothing meets the
 * tolerance — a set can be dense enough that some wrapping is unavoidable — the search falls back to
 * the largest layout that merely fits.
 */
private const val WRAP_TOLERANCE = 1.03

/**
 * The row padding, as a fraction of the text size, that a layout has to be able to afford. Below this
 * the rows run together into a wall of text (descenders touching the line under them), so a candidate
 * that only fits by crushing its rows is not treated as fitting at all — the search drops a size, or
 * adds a column, instead.
 */
private const val MIN_ROW_PAD = 0.11

/**
 * ...and how far the padding may then grow to fill the page out. Past this the rows drift apart into
 * a list of unrelated lines; a set small enough to hit the cap is better left with white space under
 * it than spread over the whole sheet.
 */
private const val MAX_ROW_PAD = 0.75

/**
 * How far a wrapped heading's second and later lines are indented, in ems of the sheet's text size —
 * about two characters, which reads as a continuation without starting the line so far in that it
 * looks like a nested entry. [WRAP_TOLERANCE] keeps wrapping rare, so this is for the exceptions.
 */
private const val HANGING_INDENT = "1.6em"

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
 * source: the set's title over the headings in scripture order down flowing columns, a chapter at a
 * time. Each chapter is one shaded block with its number set large alongside it and a rule down its
 * left edge, so the chapter divisions read off the page without spending a row on a label.
 *
 * "Fits on one page" is enforced by Typst rather than guessed here, in two passes. The first measures
 * every chapter block at each of [CANDIDATES] in turn and takes the first layout that both fits the
 * page at [MIN_ROW_PAD] and stays within [WRAP_TOLERANCE]; the second grows the row padding from
 * there until the longest column reaches the bottom margin, so the sheet ends up as full as its
 * chapter blocks allow. Measuring — rather than predicting a row height from the text size — is what
 * makes long headings that wrap to a second line count correctly.
 *
 * Columns are split and balanced here too rather than left to Typst's `columns`, which fills the
 * first column to the page bottom and leaves the last one short. Cutting only between chapter blocks
 * is what keeps a chapter (and a book band, which is carried by the chapter under it) whole.
 */
fun chapterHeadingsTypst(
    title: String,
    books: List<ChapterHeadingBook>,
    subtitle: String = "Chapter Headings",
): String = buildString {
    val headingCount = books.sumOf { book -> book.chapters.sumOf { it.headings.size } }

    appendLine(
        """
        #set page(paper: "us-letter", margin: (x: $MARGIN_X, y: $MARGIN_Y))
        // Only fonts the typst binary embeds: the prod image ships no system fonts.
        #set text(font: "Libertinus Serif")
        #set par(justify: false)

        #let accent = rgb("#1f4368")
        // Chapters alternate between the tint and plain white. The shading only has to separate one
        // chapter from the next — the number and the accent rule already label them — so it stays
        // pale enough not to fight the text or soak a page in toner.
        #let stripe = rgb("#eff4fa")
        #let muted = rgb("#7c8894")

        #let gutter = $GUTTER
        #let content_width = $PAGE_WIDTH - 2 * $MARGIN_X
        #let content_height = $PAGE_HEIGHT - 2 * $MARGIN_Y

        #let books = (
        """.trimIndent()
    )

    books.forEach { book ->
        appendLine("""  (book: ${book.book?.let { "\"${escapeTypstString(it)}\"" } ?: "none"}, chapters: (""")
        book.chapters.forEach { chapter ->
            appendLine("    (chapter: ${chapter.chapter}, headings: (")
            chapter.headings.forEach { h ->
                appendLine(
                    """      (title: "${escapeTypstString(h.title)}", """ +
                        """reference: "${escapeTypstString(h.reference)}"),"""
                )
            }
            appendLine("    )),")
        }
        appendLine("  )),")
    }

    appendLine(
        """
        )

        #let multibook = books.len() > 1

        // What a column may be cut between: one entry per chapter, in scripture order. A book band is
        // carried by the chapter under it, so a band can never be left stranded at the foot of a
        // column. Chapters alternate shade by their position in the set, not by their number — a set
        // with a gap in it (Exodus 1-20, then 32-34) must not run two same-shaded chapters together
        // just because 20 and 32 are both even.
        #let items = {
          let out = ()
          let i = 0
          for b in books {
            let band = if multibook { b.book } else { none }
            for ch in b.chapters {
              out.push((chapter: ch.chapter, headings: ch.headings, shade: calc.even(i), band: band))
              band = none
              i += 1
            }
          }
          out
        }

        // One chapter: its headings stacked, its number set large and centred against them, the whole
        // block shaded and ruled down the accent edge.
        #let chapter_block(it, pad, size) = block(
          width: 100%,
          fill: if it.shade { stripe } else { white },
          above: 0pt, below: 0pt,
          stroke: (left: 1.5pt + accent),
          inset: (left: 3pt, right: 4pt, y: pad * 0.7),
          grid(
            columns: (size * 1.9, 1fr, auto),
            column-gutter: 6pt,
            row-gutter: 2 * pad,
            align: (center + horizon, left + top, right + top),
            grid.cell(
              rowspan: it.headings.len(),
              text(size: size * 1.35, weight: "bold", fill: accent)[#it.chapter],
            ),
            // A heading the search couldn't keep on one line hangs its continuation, so a two-line
            // title reads as one heading rather than as two short ones.
            ..it.headings.map(h => (
              par(hanging-indent: $HANGING_INDENT, h.title), text(fill: muted)[#h.reference],
            )).flatten(),
          ),
        )

        // `top` is false everywhere the height is *measured*, so an item that lands at the head of a
        // column (where the band's leading space is dropped) can only ever draw shorter than planned.
        #let draw(it, pad, size, top: false) = {
          if it.band != none {
            block(width: 100%, above: 0pt, below: 0pt,
              inset: (top: if top { 0pt } else { pad * 2.5 }, bottom: pad),
              block(width: 100%, fill: accent, above: 0pt, below: 0pt,
                inset: (x: 5pt, y: pad * 0.9 + 1pt),
                text(fill: white, weight: "bold", size: size * 1.02)[#it.band],
              ),
            )
          }
          block(width: 100%, above: 0pt, below: 0pt, inset: (bottom: pad * 0.9),
            chapter_block(it, pad, size),
          )
        }

        #let stack(rows, pad, size, top: false) = {
          set text(size: size)
          set par(leading: 0.5em)
          for (i, it) in rows.enumerate() { draw(it, pad, size, top: top and i == 0) }
        }

        #let title_block = align(center)[
          #text(size: 22pt, weight: "bold", fill: accent)[${escapeTypst(title)}]
          #v(-0.62em)
          #text(size: 10.5pt, weight: "bold", fill: accent, tracking: 0.08em)[
            #upper[$headingCount ${escapeTypst(subtitle)}]
          ]
          #v(7pt)
        ]

        #let column_width(cols) = (content_width - (cols - 1) * gutter) / cols

        // Fill each column until the next block would pass `target`, then move on. Blocks are whole
        // chapters, so every cut lands on a chapter boundary.
        #let split(heights, cols, target, avail) = {
          let out = ()
          let cur = ()
          let h = 0pt
          for (i, hi) in heights.enumerate() {
            if cur.len() > 0 and out.len() + 1 < cols and (h + hi > target or h + hi > avail) {
              out.push(cur)
              cur = ()
              h = 0pt
            }
            cur.push(i)
            h += hi
          }
          out.push(cur)
          out
        }

        #let col_heights(heights, sp) = sp.map(c => c.fold(0pt, (a, i) => a + heights.at(i)))

        #let fits(heights, sp, cols, avail) = (
          sp.len() <= cols and col_heights(heights, sp).all(h => h <= avail)
        )

        // The most even split that still fits: sweep the target the fill aims at and keep the one
        // whose longest column is shortest. Evening the columns out is also what lets the rows
        // breathe — the padding can only grow until the *longest* column reaches the page bottom.
        #let balance(heights, cols, avail) = {
          let floor = calc.max(..heights.map(h => h.pt())) * 1pt
          let best = none
          let best_max = avail
          for step in range(0, 41) {
            let sp = split(heights, cols, floor + (avail - floor) * step / 40, avail)
            if not fits(heights, sp, cols, avail) { continue }
            let m = calc.max(..col_heights(heights, sp).map(h => h.pt())) * 1pt
            if best == none or m < best_max {
              best = sp
              best_max = m
            }
          }
          best
        }

        #let candidates = (${CANDIDATES.joinToString(", ") { (cols, size) -> "($cols, ${size}pt)" }},)

        #context {
          // 2pt of slack absorbs the rounding at each column break, where a block that doesn't quite
          // fit moves down whole.
          let avail = content_height - measure(block(width: content_width, title_block)).height - 2pt
          let min_pad(size) = $MIN_ROW_PAD * size

          let plan(cols, size, pad) = {
            let cw = column_width(cols)
            let hs = items.map(it => measure(block(width: cw, stack((it,), pad, size))).height)
            (hs: hs, sp: balance(hs, cols, avail))
          }

          let chosen = none
          for (cols, size) in candidates {
            let p = plan(cols, size, min_pad(size))
            if p.sp == none { continue }
            if chosen == none { chosen = (cols, size) } // fits, but may wrap heavily — the fallback
            // The sheet's height with every heading on one line: laid out far wider than any column,
            // so nothing can wrap. Comparing a candidate against it is how much wrapping it causes.
            let unwrapped = measure(block(width: 40in, stack(items, min_pad(size), size))).height
            if p.hs.fold(0pt, (a, b) => a + b) <= unwrapped * $WRAP_TOLERANCE {
              chosen = (cols, size)
              break
            }
          }
          let (cols, size) = if chosen != none { chosen } else { candidates.last() }

          let pad = min_pad(size)
          let best = plan(cols, size, pad)
          for step in range(1, 200) {
            let p = min_pad(size) + step * 0.3pt
            if p > $MAX_ROW_PAD * size { break }
            let candidate = plan(cols, size, p)
            if candidate.sp == none { break }
            pad = p
            best = candidate
          }

          title_block
          block(above: 0pt, below: 0pt, grid(
            columns: (1fr,) * cols,
            column-gutter: gutter,
            ..best.sp.map(c => stack(c.map(i => items.at(i)), pad, size, top: true)),
          ))
        }
        """.trimIndent()
    )
}
