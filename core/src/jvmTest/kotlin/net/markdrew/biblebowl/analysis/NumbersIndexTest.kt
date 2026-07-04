package net.markdrew.biblebowl.analysis

import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.ws.EsvIndexer
import net.markdrew.biblebowl.ws.Passage
import net.markdrew.biblebowl.ws.PassageMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NumbersIndexTest {

    @Test
    fun findsNumeralsCardinalsOrdinalsAndFractions() {
        val found = findNumbers("There were 3 men, forty women, and seven sons on the first day; he gave a tenth.")
            .map { it.excerptText.lowercase() }.toList()
        assertTrue("3" in found, "numeral: $found")
        assertTrue("forty" in found, "cardinal: $found")
        assertTrue("seven" in found, "cardinal: $found")
        assertTrue("first" in found, "ordinal: $found")
        assertTrue(found.any { it.contains("tenth") }, "fraction: $found")
    }

    @Test
    fun numbersIndexGroupsByTextWithPerVerseCounts() {
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

                [1] There were seven days and seven nights. [2] He gave the three men one talent.
            """.trimIndent(),
        )
        val studyData = EsvIndexer(StandardStudySet.GENESIS.set).indexBook(sequenceOf(passage))

        val index = numbersIndex(studyData).associateBy { it.key }
        // "seven" occurs twice, both in verse 1 → one reference with count 2.
        val seven = index.getValue("seven")
        assertEquals(2, seven.total())
        assertEquals(1, seven.values.size)
        assertEquals(2, seven.values.single().count)
        // "three" occurs once in verse 2.
        assertEquals(1, index.getValue("three").total())
        // entries are alphabetically sorted by key
        val keys = numbersIndex(studyData).map { it.key }
        assertEquals(keys.sortedBy { it.lowercase() }, keys)
    }

    private fun WordIndexEntryC.total(): Int = values.sumOf { it.count }

    @Test
    fun namesIndexPicksUpProperNamesFromTheResolution() {
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

                [1] Abraham went to Egypt with Sarah. [2] Abraham built an altar.
            """.trimIndent(),
        )
        val studyData = EsvIndexer(StandardStudySet.GENESIS.set).indexBook(sequenceOf(passage))
        val resolution = AnnotationStore(studyData, cacheDir = null).categoryResolution(studyData.studySet)

        val index = namesIndex(studyData, resolution).associateBy { it.key.lowercase() }
        // "Abraham" (man) occurs twice, both in verse 1..2; "Egypt" (place) and "Sarah" (woman) once each.
        assertTrue("abraham" in index, "found the man Abraham: ${index.keys}")
        assertTrue("egypt" in index, "found the place Egypt: ${index.keys}")
        assertEquals(2, index.getValue("abraham").values.sumOf { it.count })
    }
}
