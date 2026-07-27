package net.markdrew.biblebowl.analysis

import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.model.StudyData
import net.markdrew.biblebowl.ws.EsvIndexer
import net.markdrew.biblebowl.ws.Passage
import net.markdrew.biblebowl.ws.PassageMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordListAndFullIndexTest {

    /** Genesis fixture with two men (Abraham×2, Isaac), one woman (Rachel), one place (Egypt). */
    private fun fixture(): StudyData {
        val meta = PassageMeta(
            canonical = "Genesis 1:1–2",
            chapterStart = listOf(1001001, 1001002),
            chapterEnd = listOf(1001001, 1001002),
            prevVerse = null, nextVerse = null, prevChapter = null, nextChapter = null,
        )
        val passage = Passage(
            canonical = "Genesis 1:1–2",
            range = 1001001..1001002,
            meta = meta,
            text = """
                _______________________________________________________
                A Heading

                [1] Abraham and Isaac went to Egypt. [2] Abraham saw Rachel.
            """.trimIndent(),
        )
        return EsvIndexer(StandardStudySet.GENESIS.set).indexBook(sequenceOf(passage))
    }

    @Test
    fun wordListIndexNarrowsToOneCategory() {
        val studyData = fixture()
        val resolution = AnnotationStore(studyData, cacheDir = null).categoryResolution(studyData.studySet)

        val men = wordListIndex(studyData, resolution, WordList.MEN).map { it.key.lowercase() }
        assertTrue("abraham" in men && "isaac" in men, "men: $men")
        assertFalse("rachel" in men || "egypt" in men, "men list must not leak other categories: $men")

        val women = wordListIndex(studyData, resolution, WordList.WOMEN).map { it.key.lowercase() }
        assertEquals(listOf("rachel"), women, "women: $women")

        val places = wordListIndex(studyData, resolution, WordList.PLACES).map { it.key.lowercase() }
        assertEquals(listOf("egypt"), places, "places: $places")

        // Abraham occurs twice; the per-verse counts survive the narrowing.
        val abraham = wordListIndex(studyData, resolution, WordList.MEN).single { it.key.lowercase() == "abraham" }
        assertEquals(2, abraham.values.sumOf { it.count })
    }

    @Test
    fun fullIndexCoversContentWordsAndCountsButDropsStopWords() {
        val studyData = fixture()
        val index = fullIndex(studyData).associateBy { it.key.lowercase() }

        assertTrue("abraham" in index && "egypt" in index && "saw" in index, "content words: ${index.keys}")
        assertEquals(2, index.getValue("abraham").values.sumOf { it.count }, "Abraham occurs twice")
        // "and"/"to"/"went" are all common function words in STOP_WORDS, so they're excluded.
        assertFalse("and" in index || "to" in index || "went" in index, "stop words must be excluded: ${index.keys}")
        // entries are alphabetically sorted by key (case-insensitive)
        val keys = fullIndex(studyData).map { it.key }
        assertEquals(keys.sortedBy { it.lowercase() }, keys)
    }
}
