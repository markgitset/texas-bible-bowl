package net.markdrew.biblebowl.model

import net.markdrew.biblebowl.generate.studyguide.studyGuideTypst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudyGuideTest {

    private val actsSet = StudySet("Acts", "acts", Book.ACT.chapterRange(1, 28))

    @Test
    fun parseTsvLineReadsAnswerLetterAndChoices() {
        val q = StudyGuideParser.parseTsvLine(
            listOf("Act", "1", "3", "Until what day?", "2", "D", "Crucified", "Pentecost", "Baptized", "Taken up"),
        )
        assertEquals(1, q.chapterRef.chapter)
        assertEquals(3, q.questionNum)
        assertEquals(3, q.correctAnswer, "letter D → 0-based index 3")
        assertEquals("Taken up", q.choices[q.correctAnswer])
        assertEquals(4, q.choices.size)
    }

    @Test
    fun loadStudyGuideReturnsNullWhenNoResource() {
        assertNull(StudyGuideParser.loadStudyGuideOrNull(StudySet("Nope", "no-such-set", Book.ACT.chapterRange(1, 1))))
    }

    @Test
    fun bundledActsStudyGuideParsesCleanly() {
        val guide = StudyGuideParser.loadStudyGuideOrNull(actsSet)
        assertTrue(guide != null && guide.size > 1000, "expected the full Acts guide, got ${guide?.size}")
        // First question of the bundled guide: Luke addresses Acts to Theophilus (answer A).
        val first = guide.first()
        assertEquals(0, first.correctAnswer)
        assertEquals("Theophilus", first.choices[0])
        // Every row parsed to 3–5 choices with a valid correct-answer index.
        assertTrue(guide.all { it.correctAnswer in it.choices.indices && it.choices.size in 3..5 })
    }

    @Test
    fun studyGuideTypstEmitsCoverQuestionsAndAnswerKey() {
        val questions = listOf(
            StudyGuideParser.parseTsvLine(listOf("Act", "1", "1", "Who?", "1", "A", "Theophilus", "Timothy", "Titus")),
            StudyGuideParser.parseTsvLine(listOf("Act", "2", "1", "What day?", "1", "C", "Passover", "Sabbath", "Pentecost")),
        )
        val typst = studyGuideTypst(questions, actsSet, 2027)
        assertTrue("STUDY GUIDE" in typst && "for Texas Bible Bowl 2027" in typst, "cover")
        assertTrue("== Chapter 1" in typst && "== Chapter 2" in typst, "chapter headings")
        assertTrue("Theophilus" in typst && "Answer Key" in typst, "questions + key")
    }

    @Test
    fun answerCopyMarksCorrectChoiceInlineAndDropsTheKey() {
        val questions = listOf(
            StudyGuideParser.parseTsvLine(listOf("Act", "1", "1", "Who?", "1", "A", "Theophilus", "Timothy", "Titus")),
        )
        val student = studyGuideTypst(questions, actsSet, 2027, markAnswers = false)
        val answers = studyGuideTypst(questions, actsSet, 2027, markAnswers = true)

        // The student copy ends with a separate answer key and no inline star; the answer copy is the inverse.
        assertTrue("Answer Key" in student && "★" !in student, "student copy: key, no stars")
        assertTrue("★" in answers && "Answer Key" !in answers, "answer copy: inline stars, no key")
        assertTrue("ANSWER COPY" in answers, "answer copy is labelled on the cover")
    }
}
