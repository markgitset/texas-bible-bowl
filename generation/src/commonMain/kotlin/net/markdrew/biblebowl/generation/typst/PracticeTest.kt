package net.markdrew.biblebowl.generation.typst

import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.generate.fittedQuestionSheetTypst
import net.markdrew.biblebowl.model.Round

/**
 * Builds a Typst document for a printable practice test in Texas Bible Bowl round formats.
 *
 * Pure Kotlin string building (runs on any platform); compilation to PDF happens server-side.
 * Layout follows the competition's written-test style: numbered items, A–E choices for the
 * multiple-choice rounds (scantron-friendly), blanks for short-answer rounds, and an answer key
 * on its own page. The question sheet fits on one physical page front and back — a fit search
 * picks the largest text size that packs the questions into two pages, and each question is an
 * unbreakable block so its answer choices can never land on a different page than the question
 * (see [fittedQuestionSheetTypst]).
 *
 * @param headingsByReference chapter heading title(s) keyed by each question's serialized verse
 *   reference (joined with " AND " when a verse spans headings); shown in the answer key under the
 *   answer, alongside the reference, when the server has the study text to look them up in
 */
fun practiceTestTypst(
    roundType: Round,
    questions: List<QuestionDto>,
    seasonBook: String = "Acts",
    title: String = "Texas Bible Bowl Practice Test",
    headingsByReference: Map<String, String> = emptyMap(),
): String = buildString {
    val header = """
        #align(center)[
          #text(size: 15pt, weight: "bold")[${escapeTypst(title)}]

          #text(size: 12pt)[${escapeTypst(roundType.displayName)} · ${escapeTypst(seasonBook)}]

          #text(size: 10pt, style: "italic")[${if (roundType.openBible) "Open Bible" else "Closed Bible"} · ${questions.size} questions · ${roundType.maxPoints} points maximum]
        ]
        #v(0.35em)
        Name: #box(width: 2.6in, repeat[.]) #h(1fr) Date: #box(width: 1.6in, repeat[.])
        #v(0.5em)
        #line(length: 100%, stroke: 0.5pt)
    """.trimIndent()

    val items = questions.mapIndexed { i, q ->
        if (roundType.multipleChoice && q.choices.isNotEmpty()) {
            // Choices flow inline (wrapping as needed) rather than one per line: at 40 questions a
            // line per choice can never fit two pages.
            val choices = q.choices.mapIndexed { c, choice ->
                "*${'A' + c}.* ${escapeTypst(choice)}"
            }.joinToString(" #h(1.4em, weak: true) ")
            "*${i + 1}.* ${escapeTypst(q.prompt)}\n#v(0.3em)\n#pad(left: 1.2em)[$choices]"
        } else {
            "*${i + 1}.* ${escapeTypst(q.prompt)} #h(0.6em) #box(width: ${answerBlankWidth(roundType)}, repeat[.])"
        }
    }

    append(fittedQuestionSheetTypst(header, items))

    // Answer key on its own page: the correct answer plus, when known, the verse reference(s) and
    // the chapter heading(s) they fall under.
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
        val refs = q.references.takeIf { it.isNotEmpty() }
            ?.joinToString("; ", prefix = "  #text(size: 9pt)[(", postfix = ")]") { escapeTypst(it) } ?: ""
        append("*${i + 1}.* ${escapeTypst(answer)}$refs")
        val headings = q.references.mapNotNull { headingsByReference[it] }.distinct()
        if (headings.isEmpty()) appendLine(" \\")
        else appendLine(" \\\n  #text(size: 9pt, style: \"italic\")[${headings.joinToString(" AND ") { escapeTypst(it) }}] \\")
    }
    appendLine("]")
}

/** Wider blanks for verse references, narrower for chapter numbers. */
private fun answerBlankWidth(roundType: Round): String = when (roundType) {
    Round.FIND_THE_VERSE -> "1.6in"
    Round.QUOTES, Round.EVENTS -> "0.9in"
    else -> "2.2in"
}
