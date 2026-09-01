package net.markdrew.biblebowl.generate.practice

import net.markdrew.biblebowl.generate.fittedQuestionSheetTypst
import net.markdrew.biblebowl.model.BRIEF_BOOK_FORMAT
import net.markdrew.biblebowl.model.BookFormat
import net.markdrew.biblebowl.model.Heading
import net.markdrew.biblebowl.model.NO_BOOK_FORMAT
import net.markdrew.biblebowl.model.VerseRef

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
 * The question sheet fits on one page front and back (see [fittedQuestionSheetTypst]); the answer key
 * follows on its own page and names each answer's chapter heading and verse reference alongside the letter.
 */
fun inWhatChapterTypst(practiceTest: PracticeTest, questions: List<MultiChoiceQuestion>): String {
    val bookFormat = if (practiceTest.content.studyData.isMultiBook) BRIEF_BOOK_FORMAT else NO_BOOK_FORMAT
    val round = practiceTest.round
    val minutes: Int = round.minutesAtPaceFor(questions.size)
    val seedString = "%04d".format(practiceTest.randomSeed)
    val titleLeft = "#$seedString ${round.longName} (${round.bibleUse} Bible, $minutes min)"
    val titleRight = "Round ${round.number}"

    val content = practiceTest.content
    val limitedTo: String =
        if (content.allChapters) ""
        else " (ONLY ${content.coveredChaptersString()})"

    val header = """
        #align(center)[
          #text(size: 14pt, weight: "bold")[${escapeTypst(titleLeft)} #h(1fr) ${escapeTypst(titleRight)}]
        ]
        #v(0.06in)
        Without using your Bible, mark on your score sheet the letter corresponding to the chapter number in which
        each of the following ${round.shortName} is found (i.e., begins) in ${escapeTypst(practiceTest.studySet.name)}${escapeTypst(limitedTo)}.
        #v(0.04in)
    """.trimIndent()

    val items = questions.mapIndexed { i, multiChoice ->
        val qText = escapeTypst(multiChoice.question.question)
        val choicesStr = multiChoice.choices.mapIndexed { idx, choice ->
            val label = ('A' + idx).toString()
            val text = choice?.format(bookFormat) ?: "none of these"
            "[*$label.* ${escapeTypst(text)}]"
        }.joinToString(", ")
        """
        *${i + 1}.* $qText
        #v(0.3em)
        #pad(left: 1.2em, grid(
          columns: (1fr,) * ${multiChoice.choices.size},
          align: left,
          $choicesStr
        ))
        """.trimIndent()
    }

    val sb = StringBuilder(fittedQuestionSheetTypst(header, items))
    sb.appendLine("\n#pagebreak()\n")
    appendAnswerKey(sb, practiceTest, titleLeft, titleRight, questions, bookFormat)
    return sb.toString()
}

private fun appendAnswerKey(
    appendable: Appendable,
    practiceTest: PracticeTest,
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
    val headingsByTitle: Map<String, List<Heading>> = practiceTest.content.headings().groupBy { it.title }
    questions.forEach { multiChoice ->
        val q = multiChoice.question
        val prefix = if (bookFormat == NO_BOOK_FORMAT) "chapter " else ""
        val ref: String =
            if (q.answerRefs != null) q.answerRefs.first().format(bookFormat)
            else prefix + q.answers.joinToString(" and ") { it.format(bookFormat) }
        val label = ('A' + multiChoice.correctChoice).toString()
        // Each key entry carries its chapter heading(s) and verse reference under the letter, so the
        // key reads on its own: for R4 the heading(s) the quoted verse falls under, for R5 the verse
        // span of the heading the question quoted.
        val headingLines: List<String> = when {
            q.answerRefs != null -> q.answerRefs.flatMap { verseRef: VerseRef ->
                practiceTest.content.studyData.headingsIntersecting(verseRef).map { escapeTypst(it) }
            }.distinct()
            else -> q.answers.flatMap { chapter ->
                headingsByTitle[q.question].orEmpty()
                    .filter { it.chapterRange.start == chapter }
                    .map { heading ->
                        "${escapeTypst(heading.title)} (${escapeTypst(heading.verseRange.format(bookFormat))})"
                    }
            }
        }
        appendable.append("  + *$label* (${escapeTypst(ref)})")
        if (headingLines.isEmpty()) appendable.appendLine()
        else appendable.appendLine(" \\\n    " + headingLines.joinToString(" AND \\\n    "))
    }
    appendable.appendLine("""]""")
}
