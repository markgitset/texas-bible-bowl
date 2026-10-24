package net.markdrew.biblebowl.generation.quiz

import net.markdrew.biblebowl.api.QuestionDto
import kotlin.random.Random

/**
 * One quiz item, display-ready: choices are pre-shuffled (so a submitter's habit of listing the
 * correct answer first can't be gamed) with [correctIndex] tracking where the answer landed.
 * [choices] is empty for self-graded (short-answer) items. When the quiz offers
 * [NONE_OF_THE_ABOVE] it is always the last choice, and [noneIsCorrect] says the real answer
 * (still [QuestionDto.answer]) was left out of the list.
 */
data class QuizItem(
    val question: QuestionDto,
    val choices: List<String>,
    val correctIndex: Int,
    val noneIsCorrect: Boolean = false,
) {
    val isMultipleChoice: Boolean get() = choices.isNotEmpty()
}

/** How many questions a quiz asks, chosen at setup; [ALL] takes the whole pool. */
enum class QuizLength(val label: String, val count: Int) {
    TEN("10", 10),
    TWENTY("20", 20),
    FIFTY("50", 50),
    ALL("All", Int.MAX_VALUE),
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
 * then [next]; when [isFinished], read [result] — and [retryMissed] to drill the misses again.
 *
 * With [noneOfTheAbove], every multiple-choice item keeps its button count but swaps one real
 * choice for a trailing [NONE_OF_THE_ABOVE]; with the fair 1-in-(button count) chance that is the
 * answer, and the real answer is left out of the list.
 */
class QuizEngine(
    questions: List<QuestionDto>,
    private val limit: Int = QuizLength.TWENTY.count,
    private val random: Random = Random.Default,
    private val noneOfTheAbove: Boolean = false,
) {
    val items: List<QuizItem> = questions
        .shuffled(random)
        .take(limit)
        .map { q ->
            when {
                q.choices.isEmpty() -> QuizItem(q, emptyList(), correctIndex = -1)
                noneOfTheAbove -> withNoneOfTheAbove(q)
                else -> {
                    val shuffled = q.choices.shuffled(random)
                    // The correct choice equals the answer text; fall back to 0 if the submitter
                    // didn't include it verbatim among the choices.
                    val idx = shuffled.indexOfFirst { it.trim() == q.answer.trim() }.coerceAtLeast(0)
                    QuizItem(q, shuffled, idx)
                }
            }
        }

    private fun withNoneOfTheAbove(q: QuestionDto): QuizItem {
        val answer = q.choices.firstOrNull { it.trim() == q.answer.trim() } ?: q.answer
        val distractors = q.choices.filterNot { it.trim() == q.answer.trim() }.shuffled(random)
        // distractors.size + 1 buttons either way; an answer-only question can't hide its answer.
        val noneIsCorrect = distractors.isNotEmpty() && random.nextInt(distractors.size + 1) == 0
        return if (noneIsCorrect) {
            QuizItem(q, distractors + NONE_OF_THE_ABOVE, correctIndex = distractors.size, noneIsCorrect = true)
        } else {
            val real = (distractors.dropLast(1) + answer).shuffled(random)
            QuizItem(q, real + NONE_OF_THE_ABOVE, correctIndex = real.indexOf(answer))
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

    /** True once [current] has a recorded answer (and is waiting on [next]). */
    val isCurrentAnswered: Boolean get() = answered.size > index

    /**
     * Answers the current multiple-choice item with the tapped [choiceIndex]; returns whether it
     * was correct. No-op (returns false) when finished, on a self-graded item, or when the item was
     * already answered.
     */
    fun answerChoice(choiceIndex: Int): Boolean {
        val item = current ?: return false
        if (!item.isMultipleChoice || isCurrentAnswered) return false
        val correct = choiceIndex == item.correctIndex
        answered += QuizAnswer(item, correct)
        return correct
    }

    /**
     * Records a verdict for the current item: self-graded ("I got it" / "I missed it"), or graded
     * by the UI (a typed chapter checked against the answer).
     */
    fun answerSelf(gotIt: Boolean) {
        val item = current ?: return
        if (isCurrentAnswered) return
        answered += QuizAnswer(item, gotIt)
    }

    /** Advances to the next item (call after answering). */
    fun next() {
        if (index < items.size) index++
    }

    /** Results; call once [isFinished]. */
    fun result(): QuizResult = QuizResult(answered.toList())

    /** A fresh quiz over just the questions missed so far, with the same options (re-shuffled). */
    fun retryMissed(): QuizEngine {
        val missed = result().missed.map { it.question }
        return QuizEngine(missed, limit = missed.size, random = random, noneOfTheAbove = noneOfTheAbove)
    }

    companion object {
        const val NONE_OF_THE_ABOVE = "None of the above"
    }
}
