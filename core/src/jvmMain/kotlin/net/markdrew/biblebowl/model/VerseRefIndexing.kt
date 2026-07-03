package net.markdrew.biblebowl.model

import net.markdrew.biblebowl.analysis.WithCount
import net.markdrew.biblebowl.generate.indices.formatWithCount

/**
 * Like [Iterable.format] but appends a frequency count to each verse, suitable for word-index entries.
 *
 * Uses standard wrapping-friendly comma separators between collapsed verses.
 *
 * @throws IllegalArgumentException if [bookFormat] is [NO_BOOK_FORMAT] and the list spans multiple books
 */
fun Iterable<WithCount<VerseRef>>.formatWithCounts(bookFormat: BookFormat): String {
    require(distinctBy { it.item.book }.size <= 1 || bookFormat != NO_BOOK_FORMAT) {
        "Don't use NO_BOOK_FORMAT for multi-book verse lists!"
    }
    val versesByBook: Map<Book, List<WithCount<VerseRef>>> = groupingBy { it.item.book }
        .aggregate { _, accumulator: MutableList<WithCount<VerseRef>>?, verseRef, _ ->
            accumulator?.apply { add(verseRef) } ?: mutableListOf(verseRef)
        }
    return versesByBook.entries.joinToString("; ") { (book, inBookVerseRefs) ->
        inBookVerseRefs.groupingBy { it.item.chapterRef }
            .aggregate { _, accumulator: StringBuilder?, verseRef, _ ->
                if (accumulator == null) {
                    StringBuilder(formatWithCount(verseRef.item.format(NO_BOOK_FORMAT), verseRef.count))
                } else {
                    accumulator.append(", ")
                        .append(formatWithCount(verseRef.item.verse.toString(), verseRef.count))
                }
            }
            .values
            .joinToString("; ", prefix = bookFormat(book) + "~")
        }.trim() // trim() removes leading space in the NO_BOOK_FORMAT case
}
