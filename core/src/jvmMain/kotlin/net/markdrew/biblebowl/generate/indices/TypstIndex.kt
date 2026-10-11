package net.markdrew.biblebowl.generate.indices

import net.markdrew.biblebowl.analysis.WordIndexEntryC
import net.markdrew.biblebowl.analysis.numbersIndex
import net.markdrew.biblebowl.analysis.oneTimeWordsIndexByChapter
import net.markdrew.biblebowl.analysis.oneTimeWordsIndexByVerse
import net.markdrew.biblebowl.analysis.oneTimeWordsIndexByWord
import net.markdrew.biblebowl.model.ChapterRef
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
    /**
     * Sets each entry as its own paragraph rather than a line break inside one long paragraph, so
     * paragraph spacing separates the entries and the hanging indent tucks every wrapped line under
     * its key. Right when one entry is a block (a chapter's worth of words); the default keeps the
     * tight line-per-entry form the reference indices use.
     */
    paragraphPerEntry: Boolean = false,
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
    // A trailing backslash breaks the line but stays in one paragraph; a blank line ends the paragraph.
    val entryEnd = if (paragraphPerEntry) "\n" else " \\"
    entries.forEach {
        appendLine("  *${escapeTypst(formatKey(it.key))}*, ${formatValues(it.values)}$entryEnd")
    }
    appendLine("]")
    appendLine()
}

/**
 * Renders a complete Typst index document for [entries] — an alphabetical section (3 cols, with verse
 * references) followed by an increasing-frequency section (4 cols) — and returns the source.
 *
 * @param singular label for one entry, e.g. "Number" or "Name"
 * @param plural label for many; defaults to [singular] + "s" (supply for irregulars like "Men"/"Women")
 * @param title the document title; defaults to "<set> <singular> Index" (override for e.g. "Acts Men Index")
 */
fun indexTypst(
    studyData: StudyData,
    entries: List<WordIndexEntryC>,
    singular: String,
    plural: String = "${singular}s",
    title: String = "${studyData.studySet.name} $singular Index",
): String {
    val name = studyData.studySet.name
    val longName = studyData.studySet.longName
    return buildString {
        appendDoc(title, "The following is a complete index of all ${plural.lowercase()} in $longName.") {
            appendIndex(
                entries.sortedBy { it.key.lowercase() },
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
                indexTitle = "$plural in $name in Order of Increasing Frequency",
                indexPreface = "Each ${singular.lowercase()} here occurs in $longName the number of times shown next to it.",
                columns = 4,
            )
        }
    }
}

/** The season's numbers index (alphabetical + by frequency) as a Typst document. */
fun numbersIndexTypst(studyData: StudyData): String = indexTypst(studyData, numbersIndex(studyData), "Number")

/**
 * The season's one-time-words (hapax) index as a Typst document: an alphabetical section (word → its verse)
 * followed by an in-order-of-appearance section (verse → its once-only words). Every hapax has count 1, so
 * this uses appearance order instead of [indexTypst]'s frequency section. Ported from bible-bowl.
 */
fun oneTimeWordsIndexTypst(studyData: StudyData): String {
    val name = studyData.studySet.name
    val longName = studyData.studySet.longName
    return buildString {
        appendDoc("$name One-Time Words Index", "The following words appear only once in $longName.") {
            appendIndex(
                oneTimeWordsIndexByWord(studyData).sortedBy { it.key.lowercase() },
                indexTitle = "Alphabetical",
                columns = 4,
                formatValues = studyData.compactVerseRefListFormat,
            )
            appendLine("#pagebreak()")
            appendLine()
            appendIndex(
                oneTimeWordsIndexByVerse(studyData).sortedBy { it.key },
                indexTitle = "In Order of Appearance",
                columns = 4,
                formatKey = studyData.verseRefFormat,
                formatValues = { it.joinToString(", ") },
            )
        }
    }
}

/**
 * The season's unique-words homework sheet as a Typst document: every chapter of the set in order, its
 * once-only words alphabetical beside it, and no verse reference anywhere — finding each word in the
 * text is the exercise. [oneTimeWordsIndexTypst] is the answer copy; this one withholds the references
 * on purpose. Ported from bible-bowl's `writeOneTimeWordsHomework`.
 */
fun oneTimeWordsByChapterTypst(studyData: StudyData): String {
    val name = studyData.studySet.name
    val longName = studyData.studySet.longName
    // A multi-book set's "Act 1" already names its book; a single-book set's bare "1" needs the word.
    val chapterLabel: (ChapterRef) -> String =
        if (studyData.isMultiBook) studyData.chapterRefFormat
        else ({ ref: ChapterRef -> "Chapter ${ref.chapter}" }).noBreak()
    return buildString {
        appendDoc(
            "$name Unique Words by Chapter",
            "The following words appear only once in $longName. Each chapter lists its own " +
                "unique words alphabetically — the verse is yours to find.",
        ) {
            appendIndex(
                oneTimeWordsIndexByChapter(studyData),
                columns = 3,
                formatKey = chapterLabel,
                formatValues = { it.joinToString(", ") },
                paragraphPerEntry = true,
            )
        }
    }
}
