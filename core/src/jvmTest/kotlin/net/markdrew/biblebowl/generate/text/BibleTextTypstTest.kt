package net.markdrew.biblebowl.generate.text

import net.markdrew.biblebowl.generate.text.typst.bibleTextTypst
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.ws.EsvIndexer
import net.markdrew.biblebowl.ws.Passage
import net.markdrew.biblebowl.ws.PassageMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the copied render engine (walker + Typst handler) end-to-end over a fixture with prose and poetry. */
class BibleTextTypstTest {

    private fun genesis1(): net.markdrew.biblebowl.model.StudyData {
        val meta = PassageMeta(
            canonical = "Genesis 1:1–28",
            chapterStart = listOf(1001001, 1001028),
            chapterEnd = listOf(1001001, 1001028),
            prevVerse = null, nextVerse = null, prevChapter = null, nextChapter = null,
        )
        val passage = Passage(
            canonical = "Genesis 1:1–28",
            range = 1001001..1001028,
            meta = meta,
            text = """
                _______________________________________________________
                The Creation of the World

                [1] In the beginning, God created the heavens and the earth. [2] The earth was without form and void.

                [27] So God created man in his own image,
                    in the image of God he created him;
                    male and female he created them.

                [28] And God blessed them.
            """.trimIndent(),
        )
        return EsvIndexer(StandardStudySet.GENESIS.set).indexBook(sequenceOf(passage))
    }

    @Test
    fun rendersFormattedTypstWithHeadingsVersesAndCopyright() {
        val typst = bibleTextTypst(genesis1())

        assertTrue(typst.startsWith("#set page"), "has a Typst page preamble")
        assertTrue(typst.contains("The Creation of the World"), "renders the section heading")
        assertTrue(typst.contains("In the beginning"), "renders verse text")
        assertTrue(typst.contains("#versenum"), "renders boxed verse numbers")
        assertTrue(typst.contains("Crossway"), "includes the ESV copyright line")
    }

    @Test
    fun highlightingTagsCategorizedWordsWithColors() {
        val sd = genesis1()
        val typst = highlightedBibleTextTypst(sd, net.markdrew.biblebowl.analysis.AnnotationStore(sd, cacheDir = null))
        // "God" is a divine name in the word lists → highlighted; its color is defined in the preamble.
        assertTrue(typst.contains("#let divine = rgb"), "defines the divine highlight color")
        assertTrue(typst.contains("#myhl("), "emits highlight spans for categorized words")
    }

    @Test
    fun underlineUniqueWordsIsOffByDefaultAndOnWhenRequested() {
        val sd = genesis1()
        // "beginning" occurs exactly once in the fixture → a hapax that should be underlined when enabled.
        assertTrue("beginning" in sd.wordIndex.filterValues { it.size == 1 }.keys, "fixture has a hapax to underline")

        val plain = bibleTextTypst(sd)
        assertTrue(!plain.contains("#underline["), "no underlining without the option")

        val underlined = bibleTextTypst(sd, TextOptions(underlineUniqueWords = true))
        assertTrue(underlined.contains("#underline["), "underlines hapax words when the option is set")
    }

    @Test
    fun twoColumnOptionIsHonored() {
        val oneCol = bibleTextTypst(genesis1(), TextOptions(twoColumns = false))
        val twoCol = bibleTextTypst(genesis1(), TextOptions(twoColumns = true))
        assertTrue(oneCol.contains("columns: 1"))
        assertTrue(twoCol.contains("columns: 2"))
    }

    @Test
    fun footerStampsTheProvidedDateLine() {
        val typst = bibleTextTypst(genesis1(), TextOptions(dateLine = "April 2–4, 2027"))
        assertTrue(typst.contains("Texas Bible Bowl, April 2–4, 2027"), "footer carries the date line verbatim")
    }

    @Test
    fun chapterTitleOptionsAreHonored() {
        val inline = bibleTextTypst(genesis1())
        assertTrue(!inline.contains("#chapter-heading["), "default renders the chapter label inline")
        assertTrue(inline.contains("*Chapter 1*"), "inline label opens the chapter's first verse")

        val headings = bibleTextTypst(genesis1(), TextOptions(useHeadingsForChapters = true))
        assertTrue(headings.contains("#chapter-heading[Chapter 1]"), "chapter label becomes a heading")
        assertTrue(!headings.contains("line(length: 100%"), "no divider lines unless requested")

        val lines = bibleTextTypst(genesis1(), TextOptions(useHeadingsForChapters = true, chapterEndLines = true))
        assertTrue(lines.contains("line(length: 100%"), "chapterEndLines draws divider lines beside the label")
    }

    @Test
    fun footnoteSizeIsLeftToTypstSoItTracksTheBodyText() {
        // Typst renders footnote entries at 0.85em — relative to the body, so always smaller than it.
        // Setting any size here breaks that: an absolute one doesn't track the body (this was a fixed
        // 10pt, larger than the text below 10pt), and a relative one compounds with the 0.85em instead
        // of replacing it. The server coerces the requested size to 6..24, so check across that range.
        for (fontSize in 6..24) {
            // Skip Typst comments — the preamble explains this rule's absence by naming it.
            val emitted = bibleTextTypst(genesis1(), TextOptions(fontSize = fontSize))
                .lineSequence().filterNot { it.trimStart().startsWith("//") }
            assertTrue(
                emitted.none { "#show footnote.entry: set text(size:" in it },
                "must not override Typst's body-relative footnote size (${fontSize}pt body)",
            )
        }
    }

    @Test
    fun headingSizesScaleWithTheBodyTextAtEverySelectableSize() {
        // The old fixed 14pt/16pt were right at one body size and wrong everywhere else — smaller than
        // the text at the top of the range, dwarfing it at the bottom. Sizes are now multiples of the
        // body size, so the relationship holds across the server's coerced 6..24 range.
        for (fontSize in 6..24) {
            val typst = bibleTextTypst(
                genesis1(),
                TextOptions(fontSize = fontSize, useHeadingsForChapters = true),
            )
            val sizes = Regex("""size: ([\d.]+)pt, weight: "bold"\)\[#label]""")
                .findAll(typst).map { it.groupValues[1].toDouble() }.toList()
            assertEquals(2, sizes.size, "one size for the chapter heading, one for the section heading")
            val (chapter, section) = sizes
            assertEquals(fontSize * 1.4, chapter, 0.01, "chapter heading defaults to Typst's 1.4em")
            assertEquals(fontSize * 1.2, section, 0.01, "section heading defaults to Typst's 1.2em")
            // The default hierarchy is chapter > section > body, at every body size.
            assertTrue(chapter > section && section > fontSize, "headings outrank the body (${fontSize}pt)")
        }
    }

    @Test
    fun headingSizesAreAbsoluteBecauseEmWouldCompoundInsideHeading() {
        // These sizes are set inside `heading(...)`, where Typst has already applied its own
        // 1.4em/1.2em — a relative size would multiply with that rather than replace it (the same
        // trap that made a relative footnote size render at ~0.74em). Points sidestep it.
        val typst = bibleTextTypst(genesis1(), TextOptions(useHeadingsForChapters = true))
        assertTrue(!typst.contains("em, weight: \"bold\")[#label]"), "heading sizes must not be em-relative")
    }

    @Test
    fun sectionHeadingMayDeliberatelyOutsizeTheChapterHeading() {
        // The pre-2026-08 study text had a larger section heading than chapter heading. That is a real
        // preference, so it stays reachable — it just isn't the default any more.
        val typst = bibleTextTypst(genesis1(), TextOptions(
            fontSize = 10, useHeadingsForChapters = true,
            chapterHeadingScale = 1.2, sectionHeadingScale = 1.7,
        ))
        assertTrue(typst.contains("size: 12pt, weight: \"bold\")[#label]"), "chapter heading at 1.2x")
        assertTrue(typst.contains("size: 17pt, weight: \"bold\")[#label]"), "section heading at 1.7x")
    }

    @Test
    fun verseOnNewLineBreaksBeforeLaterProseVerses() {
        assertTrue(!bibleTextTypst(genesis1()).contains("#linebreak()"), "no forced breaks by default")
        // The fixture's first paragraph holds verses 1–2, so verse 2 gets the forced break.
        val typst = bibleTextTypst(genesis1(), TextOptions(verseOnNewLine = true))
        assertTrue(typst.contains("#linebreak()"), "later verses in a paragraph start on a new line")
    }
}
