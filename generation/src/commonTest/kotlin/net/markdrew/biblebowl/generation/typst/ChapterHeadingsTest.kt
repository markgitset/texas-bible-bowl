package net.markdrew.biblebowl.generation.typst

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChapterHeadingsTest {

    private val moses = listOf(
        ChapterHeadingBook(
            "Exodus",
            listOf(
                ChapterHeadingRow("The Birth of Moses", "2:1-10"),
                ChapterHeadingRow("The Burning Bush", "3:1-22"),
            ),
        ),
        ChapterHeadingBook("Numbers", listOf(ChapterHeadingRow("Korah's Rebellion", "16:1-50"))),
    )

    @Test
    fun emitsOneEntryPerHeadingGroupedUnderItsBook() {
        val source = chapterHeadingsTypst("Life of Moses", moses)

        assertContains(source, """(book: "Exodus", headings: (""")
        assertContains(source, """(title: "The Birth of Moses", reference: "2:1-10"),""")
        assertContains(source, """(book: "Numbers", headings: (""")
        assertTrue(
            source.indexOf("Korah's Rebellion") > source.indexOf("""book: "Numbers""""),
            "headings stay inside their book",
        )
        assertTrue(
            source.indexOf("The Burning Bush") > source.indexOf("The Birth of Moses"),
            "headings keep scripture order",
        )
    }

    @Test
    fun singleBookSheetsCarryNoBookBands() {
        val source = chapterHeadingsTypst("Acts", listOf(ChapterHeadingBook(null, moses.first().headings)))

        assertContains(source, "(book: none, headings: (")
        assertFalse(source.contains("""book: """"), "a null book must not emit a band label")
    }

    @Test
    fun titleAndSubtitleAreEscapedAsMarkup() {
        // The title is interpolated into content (not a string literal), so Typst's markup
        // delimiters have to be escaped there rather than its string escapes.
        val source = chapterHeadingsTypst("Acts [#1]", emptyList(), subtitle = "Headings *and* verses")

        assertContains(source, """Acts \[\#1\]""")
        assertContains(source, """Headings \*and\* verses""")
    }

    @Test
    fun rowStringsAreEscapedAsStringLiterals() {
        val source = chapterHeadingsTypst(
            "Set",
            listOf(ChapterHeadingBook("""The "Big" Book""", listOf(ChapterHeadingRow("""He said "no"\""", "1:1")))),
        )

        assertContains(source, """(book: "The \"Big\" Book"""")
        assertContains(source, """(title: "He said \"no\"\\", reference: "1:1"),""")
        assertFalse(source.contains("""title: "He said "no""""), "raw quotes must not leak into markup")
    }

    @Test
    fun theFitSearchOffersFewerColumnsFirstAtEachSize() {
        val candidates = Regex("""#let candidates = \((.*?),\)""").find(chapterHeadingsTypst("Set", moses))
            ?.groupValues?.get(1)
            ?: error("no candidate list emitted")
        val pairs = Regex("""\((\d+), ([\d.]+)pt\)""").findAll(candidates)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toDouble() }
            .toList()

        assertTrue(pairs.size > 20, "expected a fine-grained search, got ${pairs.size} candidates")
        assertTrue(pairs.first() == 2 to 11.0, "the search must start at the biggest, widest layout")
        pairs.zipWithNext { (aCols, aSize), (bCols, bSize) ->
            assertTrue(
                bSize < aSize || (bSize == aSize && bCols > aCols),
                "candidates must run biggest-text-first, fewest-columns-first: ($aCols, $aSize) then ($bCols, $bSize)",
            )
        }
    }
}
