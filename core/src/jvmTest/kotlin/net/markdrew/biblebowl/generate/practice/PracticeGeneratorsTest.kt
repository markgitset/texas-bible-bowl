package net.markdrew.biblebowl.generate.practice

import net.markdrew.biblebowl.model.LocationQuestion
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.model.StudyData
import net.markdrew.biblebowl.model.VerseRef
import net.markdrew.biblebowl.ws.EsvIndexer
import net.markdrew.biblebowl.ws.Passage
import net.markdrew.biblebowl.ws.PassageMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * A four-chapter Genesis fixture: enough chapters for the R4 (≥4) and R5 (≥3) generators. Verse 2 reads
     * the same in every chapter (a repeated clue); [heading] and [quoted] can make headings and quotations
     * repeat too.
     */
    private fun genesisChapters(
        heading: (String) -> String = { "The Heading of Chapter $it" },
        quoted: (String) -> String = { "This is the story told in chapter ${it.lowercase()} of the book." },
    ): StudyData {
        val ordinals = listOf("One", "Two", "Three", "Four")
        val passages = (1..4).asSequence().map { c ->
            val firstVerse = 1_000_000 + c * 1_000 + 1
            val lastVerse = 1_000_000 + c * 1_000 + 3
            val meta = PassageMeta(
                canonical = "Genesis $c:1–3",
                chapterStart = listOf(firstVerse, lastVerse),
                chapterEnd = listOf(firstVerse, lastVerse),
                prevVerse = null, nextVerse = null, prevChapter = null, nextChapter = null,
            )
            Passage(
                canonical = "Genesis $c:1–3",
                range = firstVerse..lastVerse,
                meta = meta,
                text = """
                    _______________________________________________________
                    ${heading(ordinals[c - 1])}

                    [1] And God said, “${quoted(ordinals[c - 1])}” [2] The people of the land listened carefully to every word that was spoken to them that day. [3] And there was evening and there was morning in chapter ${ordinals[c - 1].lowercase()}.
                """.trimIndent(),
            )
        }
        return EsvIndexer(StandardStudySet.GENESIS.set).indexBook(passages)
    }

    @Test
    fun quotesKeyNamesChapterHeadingAndVerseReference() {
        val studyData = genesisChapters()
        val typst = quotesTypst(
            PracticeTest(Round.QUOTES, studyData.practice(), numQuestions = 3, randomSeed = 5),
        )
        assertTrue(typst != null, "four chapters should be enough for a quotes test")
        assertTrue(typst.contains("ANSWER KEY"), "has an answer key page")
        assertTrue(typst.contains("breakable: false"), "choices must stay on the question's page")
        assertTrue(typst.contains("pages_for"), "carries the two-page fit search")
        // The key names the heading over each quoted verse, not just the chapter letter.
        assertTrue(Regex("""ANSWER KEY[\s\S]*The Heading of Chapter""").containsMatchIn(typst))
    }

    @Test
    fun eventsKeyKeepsClassicLetterAndChapterFormat() {
        val studyData = genesisChapters()
        val typst = eventsTypst(
            PracticeTest(Round.EVENTS, studyData.practice(), numQuestions = 3, randomSeed = 5),
        )
        assertTrue(typst != null, "three chapters should be enough for an events test")
        // The bible-bowl key format: letter + chapter, no heading line — the question IS the heading.
        assertTrue(
            Regex("""ANSWER KEY[\s\S]*\+ \*[A-E]\* \(chapter \d\)""").containsMatchIn(typst),
            "key should show letter + chapter",
        )
        assertFalse(
            typst.substringAfter("ANSWER KEY").contains("The Heading of Chapter"),
            "key entries should not repeat the heading",
        )
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
        assertTrue(typst.contains("table("), "renders the answer-entry table")
        assertTrue(typst.contains("table.header("), "the answer-column header repeats on the sheet's back side")
        // The answer key formats verse references like "Genesis 1:2".
        assertTrue(Regex("""Genesis 1:\d""").containsMatchIn(typst), "answer key cites verse references")
    }

    @Test
    fun findTheVerseIsDeterministicForAFixedSeed() {
        val a = findTheVerseTypst(PracticeTest(Round.FIND_THE_VERSE, genesisChapter1().practice(), numQuestions = 3, randomSeed = 7))
        val b = findTheVerseTypst(PracticeTest(Round.FIND_THE_VERSE, genesisChapter1().practice(), numQuestions = 3, randomSeed = 7))
        assertTrue(a == b, "same seed + content yields identical output")
    }

    @Test
    fun findTheVerseKeyNamesEveryVerseARepeatedClueOccursIn() {
        val studyData = genesisChapters()
        val typst = findTheVerseTypst(PracticeTest(Round.FIND_THE_VERSE, studyData.practice(), numQuestions = 50, randomSeed = 3))
        val clue = "The people of the land listened carefully to every word that was spoken to them that day."
        assertEquals(1, Regex(Regex.escape(clue)).findAll(typst).count(), "a repeated clue is asked once")
        val key = typst.substringAfter("ANSWER KEY")
        assertTrue(
            Regex("""Genesis 1:2\*[\s\S]*?_or_ \*Genesis 2:2\*[\s\S]*?_or_ \*Genesis 3:2\*[\s\S]*?_or_ \*Genesis 4:2""")
                .containsMatchIn(key),
            "the key lists all four places",
        )
    }

    @Test
    fun quotesAskARepeatedQuotationOnceAndAcceptEveryChapter() {
        val studyData = genesisChapters(quoted = { "Behold, I am with you always." })
        val typst = quotesTypst(PracticeTest(Round.QUOTES, studyData.practice(), numQuestions = 50, randomSeed = 5))!!
        assertEquals(1, Regex("Behold, I am with you always").findAll(typst.substringBefore("ANSWER KEY")).count())
        assertTrue(typst.substringAfter("ANSWER KEY").contains("1:1 or 2:1 or 3:1 or 4:1"), "the key names every place")
    }

    @Test
    fun eventsAskARepeatedHeadingOnceAndAcceptEveryChapter() {
        val studyData = genesisChapters(heading = { if (it == "Two" || it == "Four") "The Same Heading" else "Heading $it" })
        val typst = eventsTypst(PracticeTest(Round.EVENTS, studyData.practice(), numQuestions = 50, randomSeed = 5))!!
        assertEquals(1, Regex("The Same Heading").findAll(typst.substringBefore("ANSWER KEY")).count())
        assertTrue(typst.substringAfter("ANSWER KEY").contains("(chapter 2 or 4)"), "the key names both chapters")
    }

    @Test
    fun multipleChoiceNeverOffersAnotherCorrectChapterAsWrong() {
        val studyData = genesisChapters()
        val chapters = studyData.chapterRefs
        val q = LocationQuestion("clue", listOf(VerseRef(chapters[0], 2), VerseRef(chapters[2], 2)))
        repeat(20) { seed ->
            val mc = multiChoice(q, chapters, kotlin.random.Random(seed), nChoices = 4)
            val right = mc.choices.filterNotNull().filter { q.acceptsChapter(it) }
            assertTrue(right.size <= 1, "at most one right choice offered, got $right")
            if (right.isEmpty()) assertEquals(mc.noneIndex, mc.correctChoice) else assertEquals(mc.choices.indexOf(right.single()), mc.correctChoice)
        }
    }
}
