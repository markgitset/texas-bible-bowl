package net.markdrew.biblebowl.generate.practice

import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.model.BibleUse
import net.markdrew.biblebowl.model.BRIEF_BOOK_FORMAT
import net.markdrew.biblebowl.model.BookFormat
import net.markdrew.biblebowl.model.NO_BOOK_FORMAT

/** Escapes Typst markup metacharacters so arbitrary study text renders literally. */
internal fun escapeTypst(s: String): String =
    s.replace("\\", "\\\\")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("#", "\\#")
        .replace("*", "\\*")
        .replace("_", "\\_")

/**
 * Renders an "In What Chapter" Typst test sheet (questions + answer key) for the given multiple-choice
 * [questions] and returns the complete Typst source.
 *
 * Shared by Round 4 (Quotations) and Round 5 (Headings/Events): both ask contestants to identify the
 * chapter a clue comes from. Ported from bible-bowl, rewritten to return a string instead of writing a file.
 */
fun inWhatChapterTypst(practiceTest: PracticeTest, questions: List<MultiChoiceQuestion>): String {
    val bookFormat = if (practiceTest.content.studyData.isMultiBook) BRIEF_BOOK_FORMAT else NO_BOOK_FORMAT
    val sb = StringBuilder()
    val (titleLeft, titleRight) = appendTest(sb, practiceTest, questions, bookFormat)
    sb.appendLine("\n#pagebreak()\n")
    appendAnswerKey(sb, titleLeft, titleRight, questions, bookFormat)
    return sb.toString()
}

private fun appendTest(
    appendable: Appendable,
    practiceTest: PracticeTest,
    questions: List<MultiChoiceQuestion>,
    bookFormat: BookFormat,
): Pair<String, String> {
    val round = practiceTest.round
    val minutes: Int = round.minutesAtPaceFor(questions.size)

    val seedString = "%04d".format(practiceTest.randomSeed)
    val titleLeft = "#$seedString ${round.longName} (${round.bibleUse} Bible, $minutes min)"
    val titleRight = "Round ${round.number}"

    val content = practiceTest.content
    val limitedTo: String =
        if (content.allChapters) ""
        else " (ONLY ${content.coveredChaptersString()})"

    appendable.appendLine(
        """
        #set page(
          paper: "us-letter",
          margin: (left: 0.7in, right: 0.7in, top: 0.3in, bottom: 0.3in)
        )
        #set text(size: 10pt, font: "Libertinus Serif")
        #set par(justify: false)

        #align(center)[
          #text(size: 14pt, weight: "bold")[${escapeTypst(titleLeft)} #h(1fr) ${escapeTypst(titleRight)}]
        ]
        #v(0.1in)

        Without using your Bible, mark on your score sheet the letter corresponding to the chapter number in which
        each of the following ${round.shortName} is found (i.e., begins) in ${escapeTypst(practiceTest.studySet.name)}${escapeTypst(limitedTo)}.

        #v(0.08in)

        #set enum(indent: 0pt, body-indent: 8pt)
    """.trimIndent()
    )
    questions.forEach { multiChoice ->
        val qText = escapeTypst(multiChoice.question.question)
        appendable.appendLine("+ $qText")
        appendable.appendLine("  #v(4pt)")
        appendable.appendLine("  #grid(")
        appendable.appendLine("    columns: (1fr,) * ${multiChoice.choices.size},")
        appendable.appendLine("    align: left,")
        val choicesStr = multiChoice.choices.mapIndexed { idx, choice ->
            val label = ('A' + idx).toString()
            val text = choice?.format(bookFormat) ?: "none of these"
            "[*$label.* ${escapeTypst(text)}]"
        }.joinToString(", ")
        appendable.appendLine("    $choicesStr")
        appendable.appendLine("  )")
        appendable.appendLine("  #v(8pt)")
    }
    return Pair(titleLeft, titleRight)
}

private fun appendAnswerKey(
    appendable: Appendable,
    titleLeft: String,
    titleRight: String,
    questions: List<MultiChoiceQuestion>,
    bookFormat: BookFormat,
) {
    appendable.appendLine(
        """
        #align(center)[
          #text(size: 14pt, weight: "bold")[
            ANSWER KEY \ \
            ${escapeTypst(titleLeft)} #h(1fr) ${escapeTypst(titleRight)}
          ]
        ]
        #v(0.25in)
        #columns(2)[
          #set enum(indent: 0pt, body-indent: 6pt)
    """.trimIndent()
    )
    questions.forEach { multiChoice ->
        val q = multiChoice.question
        val prefix = if (bookFormat == NO_BOOK_FORMAT) "chapter " else ""
        val ref: String =
            if (q.answerRefs != null) q.answerRefs.first().format(bookFormat)
            else prefix + q.answers.joinToString(" and ") { it.format(bookFormat) }
        val label = ('A' + multiChoice.correctChoice).toString()
        appendable.appendLine("  + *$label* (${escapeTypst(ref)})")
    }
    appendable.appendLine("""]""")
}
