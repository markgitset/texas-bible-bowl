package net.markdrew.biblebowl.generate.indices

import net.markdrew.biblebowl.analysis.oneTimeWordsIndexByChapter
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.model.StudyData
import net.markdrew.biblebowl.ws.EsvIndexer
import net.markdrew.biblebowl.ws.Passage
import net.markdrew.biblebowl.ws.PassageMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The homework sheet's two layers over one fixture: the by-chapter grouping
 * ([oneTimeWordsIndexByChapter]) and the markup it renders ([oneTimeWordsByChapterTypst]). The
 * grouping needs more than one chapter to say anything, which is why it's tested here rather than
 * beside the single-chapter by-word/by-verse fixture in `WordListAndFullIndexTest`.
 */
class OneTimeWordsByChapterTest {

    /** Two-chapter Genesis fixture: Abraham and Jacob each recur, so neither is a one-time word. */
    private fun fixture(): StudyData {
        fun chapter(chapterNum: Int, heading: String, verses: String): Passage {
            val first = 1_000_000 + chapterNum * 1_000 + 1
            val meta = PassageMeta(
                canonical = "Genesis $chapterNum:1–2",
                chapterStart = listOf(first, first + 1),
                chapterEnd = listOf(first, first + 1),
                prevVerse = null, nextVerse = null, prevChapter = null, nextChapter = null,
            )
            return Passage(
                canonical = "Genesis $chapterNum:1–2",
                range = first..(first + 1),
                meta = meta,
                text = "_______________________________________________________\n$heading\n\n$verses",
            )
        }
        return EsvIndexer(StandardStudySet.GENESIS.set).indexBook(
            sequenceOf(
                chapter(1, "A Heading", "[1] Abraham and Isaac reached Egypt. [2] Abraham saw Rachel."),
                chapter(2, "Another Heading", "[1] Jacob camped beside Bethel. [2] Jacob built an altar."),
            )
        )
    }

    @Test
    fun groupsOneTimeWordsByChapterAlphabeticallyWithinEachChapter() {
        val byChapter = oneTimeWordsIndexByChapter(fixture())

        assertEquals(listOf(1, 2), byChapter.map { it.key.chapter }, "chapters run in canonical order")

        val (ch1, ch2) = byChapter.map { entry -> entry.values }
        assertEquals(ch1.sortedBy { it.lowercase() }, ch1, "chapter 1 is alphabetical: $ch1")
        assertEquals(ch2.sortedBy { it.lowercase() }, ch2, "chapter 2 is alphabetical: $ch2")

        assertTrue("Isaac" in ch1 && "Egypt" in ch1 && "Rachel" in ch1, "chapter 1 hapaxes: $ch1")
        assertTrue("Bethel" in ch2 && "camped" in ch2 && "altar" in ch2, "chapter 2 hapaxes: $ch2")
        // Each word is grouped only under the chapter it occurs in, and recurring names drop out.
        assertFalse("Bethel" in ch1 || "Isaac" in ch2, "a word belongs to one chapter only")
        assertFalse("Abraham" in ch1 || "Jacob" in ch2, "words occurring twice are not one-time words")
    }

    @Test
    fun rendersChapterLabelsAndWithholdsVerseReferences() {
        val typst = oneTimeWordsByChapterTypst(fixture())

        assertTrue(typst.startsWith("#set page"), "has a Typst page preamble")
        assertTrue(typst.contains("Genesis Unique Words by Chapter"), "titles the sheet: $typst")
        // Single-book set, so chapters are labelled by number alone, joined to the number by a
        // non-breaking space so a label never wraps away from its chapter.
        assertTrue(
            typst.contains("*Chapter\u00A01*") && typst.contains("*Chapter\u00A02*"),
            "labels chapters, non-breaking",
        )
        assertTrue(typst.contains("Bethel"), "lists each chapter's words")
        // The point of the sheet: the student finds the verse, so no reference may leak into it.
        assertFalse(Regex("""\d+:\d+""").containsMatchIn(typst), "no verse references on the homework sheet")
    }
}
