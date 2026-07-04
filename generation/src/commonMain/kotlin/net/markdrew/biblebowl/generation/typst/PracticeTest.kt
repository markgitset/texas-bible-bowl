package net.markdrew.biblebowl.generation.typst

import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.model.Round

/**
 * Builds a Typst document for a printable practice test in Texas Bible Bowl round formats.
 *
 * Pure Kotlin string building (runs on any platform); compilation to PDF happens server-side.
 * Layout follows the competition's written-test style: numbered items, A–E choices for the
 * multiple-choice rounds (scantron-friendly), blanks for short-answer rounds, and an answer key
 * on its own page.
 */
fun practiceTestTypst(
    roundType: Round,
    questions: List<QuestionDto>,
    seasonBook: String = "Acts",
    title: String = "Texas Bible Bowl Practice Test",
): String = buildString {
    appendLine(
        """
        #set page(paper: "us-letter", margin: (x: 0.9in, y: 0.8in), numbering: "1")
        #set text(font: "Libertinus Serif", size: 11pt)
        #set par(justify: false)

        #align(center)[
          #text(size: 17pt, weight: "bold")[${escapeTypst(title)}]

          #text(size: 13pt)[${escapeTypst(roundType.displayName)} · ${escapeTypst(seasonBook)}]

          #text(size: 10pt, style: "italic")[${if (roundType.openBible) "Open Bible" else "Closed Bible"} · ${questions.size} questions · ${roundType.maxPoints} points maximum]
        ]

        #v(0.5em)
        Name: #box(width: 2.6in, repeat[.]) #h(1fr) Date: #box(width: 1.6in, repeat[.])
        #v(1em)
        #line(length: 100%, stroke: 0.5pt)
        #v(1em)
        """.trimIndent()
    )

    questions.forEachIndexed { i, q ->
        appendLine("#block(breakable: false)[")
        appendLine("*${i + 1}.* ${escapeTypst(q.prompt)}")
        appendLine()
        if (roundType.multipleChoice && q.choices.isNotEmpty()) {
            q.choices.forEachIndexed { c, choice ->
                appendLine("  #h(1.5em) ${'A' + c}. ${escapeTypst(choice)} \\")
            }
        } else {
            appendLine("  #h(1.5em) Answer: #box(width: ${answerBlankWidth(roundType)}, repeat[.])")
        }
        appendLine("]")
        appendLine("#v(0.9em)")
    }

    // Answer key on its own page.
    appendLine(
        """
        #pagebreak()
        #align(center)[
          #text(size: 15pt, weight: "bold")[Answer Key]

          #text(size: 11pt)[${escapeTypst(roundType.displayName)} · ${escapeTypst(seasonBook)}]
        ]
        #v(1em)
        #columns(2)[
        """.trimIndent()
    )
    questions.forEachIndexed { i, q ->
        val answer = if (roundType.multipleChoice && q.choices.isNotEmpty()) {
            val letter = q.choices.indexOfFirst { it.trim() == q.answer.trim() }
                .takeIf { it >= 0 }?.let { "${'A' + it}. " } ?: ""
            "$letter${q.answer}"
        } else q.answer
        val refs = q.references.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "  #text(size: 9pt)[(", postfix = ")]") ?: ""
        appendLine("*${i + 1}.* ${escapeTypst(answer)}$refs \\")
    }
    appendLine("]")
}

/** Wider blanks for verse references, narrower for chapter numbers. */
private fun answerBlankWidth(roundType: Round): String = when (roundType) {
    Round.FIND_THE_VERSE -> "1.6in"
    Round.KNOW_THE_CHAPTER_QUOTES, Round.KNOW_THE_CHAPTER_HEADINGS -> "0.9in"
    else -> "2.2in"
}
