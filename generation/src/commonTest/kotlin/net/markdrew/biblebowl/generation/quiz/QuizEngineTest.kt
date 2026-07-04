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
}
