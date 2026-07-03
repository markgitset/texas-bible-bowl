package net.markdrew.biblebowl.generation.quiz

import net.markdrew.biblebowl.api.QuestionDto
import kotlin.random.Random

/**
 * One quiz item, display-ready: choices are pre-shuffled (so a submitter's habit of listing the
 * correct answer first can't be gamed) with [correctIndex] tracking where the answer landed.
 * [choices] is empty for self-graded (short-answer) items.
 */
data class QuizItem(
    val question: QuestionDto,
    val choices: List<String>,
    val correctIndex: Int,
) {
    val isMultipleChoice: Boolean get() = choices.isNotEmpty()
}

/** The outcome of one answered item. */
data class QuizAnswer(val item: QuizItem, val correct: Boolean)

/** Final results of a quiz run. */
data class QuizResult(val answers: List<QuizAnswer>) {
    val total: Int get() = answers.size
    val score: Int get() = answers.count { it.correct }
    val missed: List<QuizItem> get() = answers.filterNot { it.correct }.map { it.item }
}

/**
 * Pure quiz state machine over a set of approved questions. UI-independent and deterministic
 * given [random], so every transition is unit-testable on any platform.
 *
 * Flow: repeatedly show [current], call [answerChoice] (MC) or [answerSelf] (self-graded reveal),
 * then [next]; when [isFinished], read [result].
 */
class QuizEngine(
    questions: List<QuestionDto>,
    limit: Int = 20,
    random: Random = Random.Default,
) {
    val items: List<QuizItem> = questions
        .shuffled(random)
        .take(limit)
        .map { q ->
            if (q.choices.isNotEmpty()) {
                val shuffled = q.choices.shuffled(random)
                // The correct choice equals the answer text; fall back to 0 if the submitter
                // didn't include it verbatim among the choices.
                val idx = shuffled.indexOfFirst { it.trim() == q.answer.trim() }.coerceAtLeast(0)
                QuizItem(q, shuffled, idx)
            } else {
                QuizItem(q, emptyList(), correctIndex = -1)
            }
        }

    private val answered = mutableListOf<QuizAnswer>()
    private var index = 0

    val isEmpty: Boolean get() = items.isEmpty()
    val isFinished: Boolean get() = index >= items.size
    val current: QuizItem? get() = items.getOrNull(index)
    val position: Int get() = index + 1
    val total: Int get() = items.size
    val scoreSoFar: Int get() = answered.count { it.correct }

    /**
     * Answers the current multiple-choice item with the tapped [choiceIndex]; returns whether it
     * was correct. No-op (returns false) when finished or on a self-graded item.
     */
    fun answerChoice(choiceIndex: Int): Boolean {
        val item = current ?: return false
        if (!item.isMultipleChoice) return false
        val correct = choiceIndex == item.correctIndex
        answered += QuizAnswer(item, correct)
        return correct
    }

    /** Records a self-graded verdict ("I got it" / "I missed it") for the current item. */
    fun answerSelf(gotIt: Boolean) {
        val item = current ?: return
        answered += QuizAnswer(item, gotIt)
    }

    /** Advances to the next item (call after answering). */
    fun next() {
        if (index < items.size) index++
    }

    /** Results; call once [isFinished]. */
    fun result(): QuizResult = QuizResult(answered.toList())
}
