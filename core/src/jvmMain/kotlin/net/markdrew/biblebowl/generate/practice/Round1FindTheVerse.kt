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

    appendLine(
        """
        #set page(
          paper: "us-letter",
          margin: (left: 0.7in, right: 0.7in, top: 0.7in, bottom: 0.7in)
        )
        #set text(size: 10pt, font: "Libertinus Serif")

        Number #box(width: 1in, stroke: (bottom: 0.5pt)) #h(1fr) Name #box(width: 3in, stroke: (bottom: 0.5pt)) #h(1fr) Score #box(width: 1in, stroke: (bottom: 0.5pt))

        #v(0.1in)
        #align(center)[
          #text(size: 15pt, weight: "bold")[#$seedString Find The Verse (Open Bible, $minutes minutes) #h(1fr) Round 1]
        ]
        #v(0.05in)
        Using your Bible, write the ${escapeTypst(answerDesc)}${escapeTypst(coverage)} of each quotation in its matching box.

        #v(0.1in)
        #align(center)[
    """.trimIndent()
    )

    val cols = if (multiBook) "auto, 1fr, 45pt, 45pt, 45pt" else "auto, 1fr, 45pt, 45pt"
    val colAligns = if (multiBook) {
        "center + horizon, left + horizon, center + horizon, center + horizon, center + horizon"
    } else {
        "center + horizon, left + horizon, center + horizon, center + horizon"
    }
    val colspan = if (multiBook) 3 else 2
    val headings = if (multiBook) "[*Book*], [*Chapter*], [*Verse*]" else "[*Chapter*], [*Verse*]"

    appendLine(
        """
        #table(
          columns: ($cols),
          align: ($colAligns),
          stroke: 0.5pt + black,
          table.cell(colspan: 2)[], table.cell(colspan: $colspan)[*ANSWER*],
          [], [], $headings,
    """.trimIndent()
    )

    versesToFind.forEachIndexed { i, refVerse ->
        val clue = removeUnmatchedCharPairs(refVerse.verse.normalizeWS())
        val escapedClue = escapeTypst(clue)
        val rowNum = "${i + 1}."
        val emptyCells = if (multiBook) "[], [], []" else "[], []"
        appendLine("    [$rowNum], [$escapedClue], $emptyCells,")
    }

    appendLine(
        """
        )
        ]

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
