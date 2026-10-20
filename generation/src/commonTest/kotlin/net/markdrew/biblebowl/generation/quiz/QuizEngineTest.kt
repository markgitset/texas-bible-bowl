package net.markdrew.biblebowl.generation.quiz

import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.model.Round
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuizEngineTest {

    private fun mc(id: String, answer: String = "Right", wrong: List<String> = listOf("Wrong1", "Wrong2")) =
        QuestionDto(
            id = id, roundType = Round.FACT_FINDER, prompt = "Prompt $id", answer = answer,
            choices = listOf(answer) + wrong, chapter = 1, status = QuestionStatus.APPROVED, authorId = "a",
        )

    private fun shortAnswer(id: String) = QuestionDto(
        id = id, roundType = Round.FIND_THE_VERSE, prompt = "Quote $id", answer = "Acts 1:$id",
        chapter = 1, status = QuestionStatus.APPROVED, authorId = "a",
    )

    @Test
    fun choicesAreShuffledButCorrectIndexTracksAnswer() {
        // Across several seeds the correct answer must always be at correctIndex.
        repeat(10) { seed ->
            val engine = QuizEngine(listOf(mc("q1")), random = Random(seed))
            val item = engine.items.single()
            assertEquals("Right", item.choices[item.correctIndex])
        }
    }

    @Test
    fun deterministicForSameSeed() {
        val questions = (1..10).map { mc("q$it") }
        val a = QuizEngine(questions, random = Random(42)).items.map { it.question.id }
        val b = QuizEngine(questions, random = Random(42)).items.map { it.question.id }
        assertEquals(a, b)
    }

    @Test
    fun limitCapsItemCount() {
        val engine = QuizEngine((1..50).map { mc("q$it") }, limit = 20, random = Random(1))
        assertEquals(20, engine.total)
    }

    @Test
    fun multipleChoiceScoringFlow() {
        val engine = QuizEngine(listOf(mc("q1"), mc("q2")), random = Random(7))
        assertFalse(engine.isFinished)
        assertEquals(1, engine.position)

        val first = engine.current!!
        assertTrue(engine.answerChoice(first.correctIndex)) // correct
        engine.next()

        val second = engine.current!!
        val wrongIndex = (second.correctIndex + 1) % second.choices.size
        assertFalse(engine.answerChoice(wrongIndex)) // wrong
        engine.next()

        assertTrue(engine.isFinished)
        val result = engine.result()
        assertEquals(2, result.total)
        assertEquals(1, result.score)
        assertEquals("q2", result.missed.single().question.id)
    }

    @Test
    fun selfGradedFlow() {
        val engine = QuizEngine(listOf(shortAnswer("1"), shortAnswer("2")), random = Random(3))
        assertFalse(engine.current!!.isMultipleChoice)
        engine.answerSelf(gotIt = true); engine.next()
        engine.answerSelf(gotIt = false); engine.next()
        assertTrue(engine.isFinished)
        assertEquals(1, engine.result().score)
    }

    @Test
    fun answerChoiceIsNoOpForSelfGradedItems() {
        val engine = QuizEngine(listOf(shortAnswer("1")), random = Random(3))
        assertFalse(engine.answerChoice(0))
        assertEquals(0, engine.result().total) // nothing recorded
    }

    @Test
    fun emptyPoolIsEmptyAndFinished() {
        val engine = QuizEngine(emptyList())
        assertTrue(engine.isEmpty)
        assertTrue(engine.isFinished)
    }

    @Test
    fun noneOfTheAboveIsAlwaysLastAndKeepsButtonCount() {
        val q = mc("q1", wrong = listOf("W1", "W2", "W3", "W4"))
        var noneCorrect = 0
        var answerShown = 0
        repeat(200) { seed ->
            val item = QuizEngine(listOf(q), random = Random(seed), noneOfTheAbove = true).items.single()
            assertEquals(5, item.choices.size)
            assertEquals(QuizEngine.NONE_OF_THE_ABOVE, item.choices.last())
            if (item.noneIsCorrect) {
                noneCorrect++
                assertEquals(item.choices.lastIndex, item.correctIndex)
                assertFalse("Right" in item.choices)
            } else {
                answerShown++
                assertEquals("Right", item.choices[item.correctIndex])
            }
            assertEquals("Right", item.question.answer) // the real answer survives for feedback
        }
        assertTrue(noneCorrect in 20..70, "expected ~1 in 5 none-correct, got $noneCorrect")
        assertTrue(answerShown > 0)
    }

    @Test
    fun noneOfTheAboveCantHideTheOnlyChoice() {
        val q = mc("q1", wrong = emptyList())
        repeat(20) { seed ->
            val item = QuizEngine(listOf(q), random = Random(seed), noneOfTheAbove = true).items.single()
            assertEquals(listOf("Right", QuizEngine.NONE_OF_THE_ABOVE), item.choices)
            assertEquals(0, item.correctIndex)
        }
    }

    @Test
    fun retryMissedHoldsExactlyTheMisses() {
        val engine = QuizEngine((1..6).map { mc("q$it") }, random = Random(5), noneOfTheAbove = true)
        val missedIds = mutableSetOf<String>()
        while (!engine.isFinished) {
            val item = engine.current!!
            if (item.question.id.removePrefix("q").toInt() % 2 == 0) {
                engine.answerChoice((item.correctIndex + 1) % item.choices.size)
                missedIds += item.question.id
            } else {
                engine.answerChoice(item.correctIndex)
            }
            engine.next()
        }
        val retry = engine.retryMissed()
        assertEquals(missedIds, retry.items.map { it.question.id }.toSet())
        assertEquals(3, retry.total)
        assertTrue(retry.items.all { it.choices.last() == QuizEngine.NONE_OF_THE_ABOVE }) // options carry over
    }

    @Test
    fun anItemIsAnsweredOnlyOnce() {
        val engine = QuizEngine(listOf(mc("q1"), shortAnswer("2")), random = Random(2))
        repeat(2) {
            val item = engine.current!!
            assertFalse(engine.isCurrentAnswered)
            if (item.isMultipleChoice) engine.answerChoice(item.correctIndex) else engine.answerSelf(true)
            assertTrue(engine.isCurrentAnswered)
            if (item.isMultipleChoice) engine.answerChoice(item.correctIndex) else engine.answerSelf(false)
            engine.next()
        }
        assertEquals(2, engine.result().total)
        assertEquals(2, engine.result().score)
    }
}
