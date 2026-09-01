package net.markdrew.biblebowl.generate

/** Page geometry of a practice-test sheet, in Typst units. Kept here so the fit search and the render agree. */
private const val PAGE_WIDTH = "8.5in"
private const val PAGE_HEIGHT = "11in"
private const val MARGIN_X = "0.7in"
private const val MARGIN_Y = "0.4in"

/**
 * The text sizes the fit search may choose from, best first. The floor is a last resort: if even
 * 7pt overflows, Typst renders it anyway and the sheet runs long rather than shrinking further
 * into unreadability.
 */
private val SIZE_CANDIDATES_PT: List<Double> = generateSequence(10.5) { it - 0.5 }.takeWhile { it >= 7.0 }.toList()

/**
 * Renders a practice-test question sheet that fits on one physical page, front and back, as Typst
 * source: [headerMarkup] at the top of page 1, then every entry of [itemsMarkup] in order, each an
 * unbreakable block — a question can never end up on a different page than its answer choices.
 *
 * "Fits on two pages" is enforced by Typst rather than guessed here, the same way the chapter-headings
 * sheet enforces its one page: a `context` block measures every question at each candidate text size,
 * simulates Typst's own page fill (a block that doesn't fit the space left on a page moves to the next
 * one whole), and renders at the largest size whose simulated fill needs at most two pages. Measuring
 * at the exact width and size the render uses is what makes wrapped prompts count correctly.
 *
 * The caller appends its own answer key after this (typically `#pagebreak()` + key markup); the key is
 * not part of the two-page budget. [itemsMarkup] entries should use `em` units for any internal
 * spacing so they scale with the chosen size; [headerMarkup] keeps its own fixed sizes and is only
 * measured so page 1's remaining room is known.
 */
/**
 * One numbered question for [fittedQuestionSheetTypst]: the number in its own right-aligned column
 * (so wrapped prompts hang cleanly), the prompt beside it, and — for multiple-choice rounds — the
 * choices row directly under the prompt. The row gutter is deliberately small: a question sits tight
 * against its own choices, while the larger between-question gap comes from the sheet's `qblock`
 * inset, so each item reads as one unit.
 */
fun questionItemTypst(number: Int, promptMarkup: String, choicesMarkup: String? = null): String = buildString {
    appendLine("#grid(")
    appendLine("  columns: (1.6em, 1fr),")
    appendLine("  column-gutter: 0.5em,")
    appendLine("  row-gutter: 0.4em,")
    appendLine("  align: (top + right, top + left),")
    appendLine("  [*$number.*], [$promptMarkup],")
    if (choicesMarkup != null) appendLine("  [], [$choicesMarkup],")
    append(")")
}

fun fittedQuestionSheetTypst(headerMarkup: String, itemsMarkup: List<String>): String = buildString {
    appendLine(
        """
        #set page(paper: "us-letter", margin: (x: $MARGIN_X, y: $MARGIN_Y))
        // Only fonts the typst binary embeds: the prod image ships no system fonts.
        #set text(font: "Libertinus Serif", size: 10pt)
        #set par(justify: false)

        #let sheet_header = [
        $headerMarkup
        ]

        #let questions = (
        """.trimIndent()
    )
    itemsMarkup.forEach { item ->
        appendLine("[$item],")
    }
    appendLine(
        """
        )

        #let content_width = $PAGE_WIDTH - 2 * $MARGIN_X
        #let content_height = $PAGE_HEIGHT - 2 * $MARGIN_Y

        // above/below stay 0 so nothing collapses or stretches between blocks — the render's heights
        // are exactly the measured ones, which is what keeps the page-fill simulation honest. The
        // bottom inset is the space between one question and the next: deliberately larger than the
        // gap between a question and its own choices (see questionItemTypst), so items read as units.
        #let qblock(it) = block(
          breakable: false, width: 100%, above: 0pt, below: 0pt, inset: (bottom: 1.1em), it,
        )

        // Typst's own fill rule: a block that doesn't fit the room left on a page moves whole to the
        // next. (A block taller than a full page still counts one page here; Typst would clip it, but
        // no question is a page tall.)
        #let pages_for(heights, first_avail, page_avail) = {
          let pages = 1
          let room = first_avail
          for h in heights {
            if h > room {
              pages += 1
              room = page_avail
            }
            room -= h
          }
          pages
        }

        // The header is wrapped 0-spaced with an exact gap after it, so page 1's remaining room is
        // measurable to the point — default block spacing would leave a gap the simulation can't see.
        #block(width: 100%, above: 0pt, below: 0pt, sheet_header)
        #v(6pt, weak: false)
        #context {
          // 2pt of slack absorbs rounding at each page break, where a block that doesn't quite fit
          // moves down whole.
          let avail = content_height - measure(block(width: content_width, sheet_header)).height - 6pt - 2pt
          let sizes = (${SIZE_CANDIDATES_PT.joinToString(", ") { "${it}pt" }},)
          let chosen = none
          for s in sizes {
            let hs = questions.map(q =>
              measure(block(width: content_width, { set text(size: s); qblock(q) })).height)
            if pages_for(hs, avail, content_height - 2pt) <= 2 { chosen = s; break }
          }
          let s = if chosen == none { sizes.last() } else { chosen }
          set text(size: s)
          for q in questions { qblock(q) }
        }
        """.trimIndent()
    )
}
