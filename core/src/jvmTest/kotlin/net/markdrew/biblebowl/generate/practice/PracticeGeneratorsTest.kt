package net.markdrew.biblebowl.generate.practice

import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.model.StudyData
import net.markdrew.biblebowl.ws.EsvIndexer
import net.markdrew.biblebowl.ws.Passage
import net.markdrew.biblebowl.ws.PassageMeta
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exercises the ported text generators end-to-end: index a small ESV-shaped passage into [StudyData], then
 * render the practice-test Typst and assert the scaffolding and clues are present. This is the real path the
 * server uses (minus the Typst→PDF compile), so a regression in the port surfaces here.
 */
class PracticeGeneratorsTest {

    private fun genesisChapter1(): StudyData {
        val meta = PassageMeta(
            canonical = "Genesis 1:1–5",
            chapterStart = listOf(1001001, 1001005),
            chapterEnd = listOf(1001001, 1001005),
            prevVerse = null, nextVerse = null, prevChapter = null, nextChapter = null,
        )
        val passage = Passage(
            canonical = "Genesis 1:1–5",
            range = 1001001..1001005,
            meta = meta,
            text = """
                _______________________________________________________
                The Creation of the World

                [1] In the beginning, God created the heavens and the earth. [2] The earth was without form and void, and darkness was over the face of the deep. And the Spirit of God was hovering over the face of the waters.

                [3] And God said, "Let there be light," and there was light. [4] And God saw that the light was good. And God separated the light from the darkness. [5] God called the light Day, and the darkness he called Night. And there was evening and there was morning, the first day.
            """.trimIndent(),
        )
        return EsvIndexer(StandardStudySet.GENESIS.set).indexBook(sequenceOf(passage))
    }

    @Test
    fun findTheVerseRendersSheetAndAnswerKey() {
        val studyData = genesisChapter1()
        val practiceTest = PracticeTest(
            round = Round.FIND_THE_VERSE,
            content = studyData.practice(),
            numQuestions = 3,
            randomSeed = 42,
        )
        val typst = findTheVerseTypst(practiceTest)

        assertTrue(typst.contains("Find The Verse"), "has the R1 title")
        assertTrue(typst.contains("ANSWER KEY"), "has an answer key page")
        assertTrue(typst.contains("#table("), "renders the answer-entry table")
        // The answer key formats verse references like "Genesis 1:2".
        assertTrue(Regex("""Genesis 1:\d""").containsMatchIn(typst), "answer key cites verse references")
    }

    @Test
    fun findTheVerseIsDeterministicForAFixedSeed() {
        val a = findTheVerseTypst(PracticeTest(Round.FIND_THE_VERSE, genesisChapter1().practice(), numQuestions = 3, randomSeed = 7))
        val b = findTheVerseTypst(PracticeTest(Round.FIND_THE_VERSE, genesisChapter1().practice(), numQuestions = 3, randomSeed = 7))
        assertTrue(a == b, "same seed + content yields identical output")
    }
}
