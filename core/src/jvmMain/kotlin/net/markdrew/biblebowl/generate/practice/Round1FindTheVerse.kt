package net.markdrew.biblebowl.generate.practice

import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.model.BibleUse
import net.markdrew.biblebowl.generate.normalizeWS
import net.markdrew.biblebowl.model.FULL_BOOK_FORMAT
import net.markdrew.biblebowl.model.PracticeContent
import net.markdrew.biblebowl.model.ReferencedVerse
import net.markdrew.biblebowl.model.StudyData
import net.markdrew.biblebowl.model.VerseRef
import net.markdrew.chupacabra.core.DisjointRangeMap
import net.markdrew.chupacabra.core.length

/**
 * Renders a Round 1 ("Find the Verse") test sheet from the study text and returns the complete Typst source.
 *
 * Picks [PracticeTest.numQuestions] single-verse sentence fragments at least [minCharLength] characters long
 * from the covered chapters and emits them along with an answer key. Ported from bible-bowl, rewritten to
 * return a string instead of writing a `.typ`/PDF file.
 */
fun findTheVerseTypst(practiceTest: PracticeTest, minCharLength: Int = 15): String {
    val content: PracticeContent = practiceTest.content
    val studyData: StudyData = content.studyData
    var cluePool: DisjointRangeMap<VerseRef> = studyData.oneVerseSentParts
    if (!content.allChapters) {
        cluePool = cluePool.enclosedBy(content.coveredOffsets)
    }

    val versesToFind: List<ReferencedVerse> = cluePool
        .filterKeys { it.length() >= minCharLength }
        .entries.shuffled(practiceTest.random)
        .take(practiceTest.numQuestions)
        .map { (range, verseRef) -> ReferencedVerse(verseRef, studyData.text.substring(range)) }

    return buildString { appendFindTheVerse(versesToFind, practiceTest) }
}

/** Strings containing pairs of characters that normally occur as pairs. */
private val charPairs = listOf("()", "“”", "\"\"", "‘’", "''")

/**
 * Removes leading or trailing unmatched halves of common paired characters from [str].
 *
 * Used to clean up sentence-fragment clues that start or end mid-quote/parenthesis.
 */
fun removeUnmatchedCharPairs(str: String): String =
    charPairs.fold(str) { s, pair -> removeUnmatchedCharPair(s, pair) }

/**
 * Removes one leading `charPair[0]` (if there are more opens than closes) or one trailing `charPair[1]`
 * (if there are more closes than opens) from [s].
 *
 * @throws IllegalArgumentException if [charPair] is not exactly two characters
 */
fun removeUnmatchedCharPair(s: String, charPair: String): String {
    if (s.isBlank()) return s
    require(charPair.length == 2)
    val startCount = s.count { it == charPair[0] }
    val endCount = s.count { it == charPair[1] }
    if (startCount > endCount && s.first() == charPair[0]) return s.drop(1)
    if (startCount < endCount && s.last() == charPair[1]) return s.dropLast(1)
    return s
}

/** Appends the Round 1 Typst test sheet (questions + answer key) for these reference/verse pairs. */
private fun Appendable.appendFindTheVerse(versesToFind: List<ReferencedVerse>, practiceTest: PracticeTest) {
    val seedString = "%04d".format(practiceTest.randomSeed)
    val minutes = Round.FIND_THE_VERSE.minutesAtPaceFor(versesToFind.size)
    val chapters: String = practiceTest.content.coveredChaptersString()
    val multiBook = practiceTest.content.studyData.isMultiBook
    val coverage = if (practiceTest.content.allChapters) "" else " (ONLY $chapters)"
    val answerDesc = if (multiBook) {
        "book, chapter, and verse from ${practiceTest.studySet.name}"
    } else {
        "chapter and verse from ${practiceTest.studySet.name}"
    }

    // Answer columns and cell padding are in em so they scale with the fitted clue size below.
    val cols = if (multiBook) "2em, 1fr, 4.5em, 4.5em, 4.5em" else "2em, 1fr, 4.5em, 4.5em"
    val colAligns = if (multiBook) {
        "center + horizon, left + horizon, center + horizon, center + horizon, center + horizon"
    } else {
        "center + horizon, left + horizon, center + horizon, center + horizon"
    }
    val colspan = if (multiBook) 3 else 2
    val headings = if (multiBook) "[*Book*], [*Chapter*], [*Verse*]" else "[*Chapter*], [*Verse*]"
    val emptyCells = if (multiBook) "[], [], []" else "[], []"

    appendLine(
        """
        #set page(
          paper: "us-letter",
          margin: (left: 0.7in, right: 0.7in, top: 0.7in, bottom: 0.7in)
        )
        #set text(size: 10pt, font: "Libertinus Serif")

        #let sheet_top = [
          Number #box(width: 1in, stroke: (bottom: 0.5pt)) #h(1fr) Name #box(width: 3in, stroke: (bottom: 0.5pt)) #h(1fr) Score #box(width: 1in, stroke: (bottom: 0.5pt))

          #v(0.1in)
          #align(center)[
            #text(size: 15pt, weight: "bold")[\#$seedString Find The Verse (Open Bible, $minutes minutes) #h(1fr) Round 1]
          ]
          #v(0.05in)
          Using your Bible, write the ${escapeTypst(answerDesc)}${escapeTypst(coverage)} of each quotation in its matching box.
          #v(0.06in)
        ]

        #let clues = (
    """.trimIndent()
    )

    versesToFind.forEach { refVerse ->
        val clue = removeUnmatchedCharPairs(refVerse.verse.normalizeWS())
        appendLine("  [${escapeTypst(clue)}],")
    }

    appendLine(
        """
        )

        #let content_width = 8.5in - 2 * 0.7in
        #let content_height = 11in - 2 * 0.7in
        #let header_cells = (
          table.cell(colspan: 2)[], table.cell(colspan: $colspan)[*ANSWER*],
          [], [], $headings,
        )
        #let clue_row(i, clue) = ([#(i + 1).], clue, $emptyCells)
        #let clue_table(s, ..cells) = {
          set text(size: s)
          table(
            columns: ($cols),
            align: ($colAligns),
            stroke: 0.5pt + black,
            inset: 0.55em,
            ..cells,
          )
        }

        // The clue size grows to fill the sheet: the largest size (up to 13pt) whose rows still pack
        // onto the front and back of one page, simulated row by row the way Typst itself fills the
        // table — each side repeats the header (table.header), and a row that doesn't fit the room
        // left on the front moves whole to the back.
        #block(width: 100%, above: 0pt, below: 0pt, sheet_top)
        #context {
          let avail1 = content_height - measure(block(width: content_width, sheet_top)).height - 2pt
          let fits(s) = {
            let hh = measure(block(width: content_width, clue_table(s, ..header_cells))).height
            let pages = 1
            let room = avail1 - hh
            for (i, clue) in clues.enumerate() {
              let h = measure(block(width: content_width, clue_table(s, ..clue_row(i, clue)))).height
              if h > room {
                pages += 1
                room = content_height - hh - 2pt
              }
              room -= h
            }
            pages <= 2
          }
          let sizes = (13pt, 12.5pt, 12pt, 11.5pt, 11pt, 10.5pt, 10pt, 9.5pt, 9pt)
          let chosen = none
          for s in sizes {
            if chosen == none and fits(s) { chosen = s }
          }
          let s = if chosen == none { sizes.last() } else { chosen }
          clue_table(
            s,
            // table.header repeats on the second side of the sheet, so the answer columns stay labeled.
            table.header(..header_cells),
            ..clues.enumerate().map(((i, clue)) => clue_row(i, clue)).flatten(),
          )
        }

        #pagebreak()
        #align(center)[
          #text(size: 15pt, weight: "bold")[ANSWER KEY \ \ \#$seedString Find The Verse (Open Bible, $minutes minutes) #h(1fr) Round 1]
        ]
        #v(0.25in)
        #columns(2)[
          #set enum(indent: 0pt, body-indent: 6pt)
    """.trimIndent()
    )

    versesToFind.forEach {
        val verseRef: VerseRef = it.reference
        val headingsList: List<String> = practiceTest.content.studyData.headingsIntersecting(verseRef)
        if (headingsList.isEmpty()) throw Exception("No chapter heading(s) found for $verseRef!")
        val formattedVerse = escapeTypst(verseRef.format(FULL_BOOK_FORMAT))
        append("  + *$formattedVerse* \\ \n")
        val escapedHeadings = headingsList.map { h -> escapeTypst(h) }
        append("    " + escapedHeadings.joinToString(" AND \\ \n    ") + "\n")
    }

    appendLine(
        """
        ]
    """.trimIndent()
    )
}
