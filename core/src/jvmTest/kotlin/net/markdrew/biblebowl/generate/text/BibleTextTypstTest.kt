package net.markdrew.biblebowl.generate.text

import net.markdrew.biblebowl.generate.text.typst.bibleTextTypst
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.ws.EsvIndexer
import net.markdrew.biblebowl.ws.Passage
import net.markdrew.biblebowl.ws.PassageMeta
import kotlin.test.Test
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
    fun twoColumnOptionIsHonored() {
        val oneCol = bibleTextTypst(genesis1(), TextOptions(twoColumns = false))
        val twoCol = bibleTextTypst(genesis1(), TextOptions(twoColumns = true))
        assertTrue(oneCol.contains("columns: 1"))
        assertTrue(twoCol.contains("columns: 2"))
    }
}
