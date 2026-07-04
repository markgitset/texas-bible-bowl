package net.markdrew.biblebowl.generate.indices

import net.markdrew.biblebowl.analysis.WordIndexEntryC
import net.markdrew.biblebowl.analysis.numbersIndex
import net.markdrew.biblebowl.model.IndexEntry
import net.markdrew.biblebowl.model.StudyData

/** Escapes Typst markup metacharacters so arbitrary index text renders literally. */
private fun escapeTypst(s: String): String =
    s.replace("\\", "\\\\")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("#", "\\#")
        .replace("*", "\\*")
        .replace("_", "\\_")

/** Appends a full Typst document (centered title + optional intro) and runs [content] in its body. */
private fun StringBuilder.appendDoc(docTitle: String, docPreface: String?, content: StringBuilder.() -> Unit) {
    appendLine(
        """
        #set page(
          paper: "us-letter",
          margin: (x: 0.75in, y: 0.75in)
        )
        #set text(size: 10pt, font: "Libertinus Serif")
        #align(center)[
          #text(size: 17pt, weight: "bold")[${escapeTypst(docTitle)}]
        ]
        #v(0.15in)
    """.trimIndent()
    )
    if (docPreface != null) {
        appendLine(escapeTypst(docPreface))
        appendLine("#v(0.25in)")
    } else {
        appendLine("#v(0.1in)")
    }
    content()
}

/** Appends one index section (optional heading/preface) as N-column hanging-indent entries. */
private fun <K, V> StringBuilder.appendIndex(
    entries: Iterable<IndexEntry<K, V>>,
    indexTitle: String? = null,
    indexPreface: String? = null,
    columns: Int = 2,
    formatKey: (K) -> String = { it.toString() },
    formatValues: (List<V>) -> String = { it.joinToString() },
) {
    if (indexTitle != null) {
        appendLine("== ${escapeTypst(indexTitle)}")
        appendLine()
    }
    if (indexPreface != null) {
        appendLine(escapeTypst(indexPreface))
        appendLine()
    }
    appendLine("#columns($columns)[")
    appendLine("  #set par(hanging-indent: 0.25in, justify: false)")
    entries.forEach {
        appendLine("  *${escapeTypst(formatKey(it.key))}*, ${formatValues(it.values)} \\")
    }
    appendLine("]")
    appendLine()
}

/**
 * Renders the numbers index for [studyData] as a complete Typst document (alphabetical section + an
 * increasing-frequency section) and returns the source, ready for the server to compile to a PDF.
 */
fun numbersIndexTypst(studyData: StudyData): String {
    val entries: List<WordIndexEntryC> = numbersIndex(studyData)
    val name = studyData.studySet.name
    val longName = studyData.studySet.longName
    return buildString {
        appendDoc("$name Number Index", "The following is a complete index of all numbers in $longName.") {
            appendIndex(
                entries,
                columns = 3,
                formatValues = studyData.compactWithCountVerseRefListFormat,
            )
            appendLine("#pagebreak()")
            appendLine()
            val frequencies: List<IndexEntry<String, Int>> = entries
                .map { IndexEntry(it.key, listOf(it.values.sumOf { withCount -> withCount.count })) }
                .sortedWith(compareBy({ it.values.single() }, { it.key }))
            appendIndex(
                frequencies,
                indexTitle = "Numbers in $name in Order of Increasing Frequency",
                indexPreface = "Each number here occurs in $longName the number of times shown next to it.",
                columns = 4,
            )
        }
    }
}
