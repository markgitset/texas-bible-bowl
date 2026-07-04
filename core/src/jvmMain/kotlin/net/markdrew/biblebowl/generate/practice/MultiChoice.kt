package net.markdrew.biblebowl.generate.practice

import net.markdrew.biblebowl.model.ChapterRef
import net.markdrew.biblebowl.model.VerseRef
import kotlin.random.Random
import kotlin.random.nextInt

/**
 * A practice question whose correct answer is one or more chapters.
 *
 * @param question the question text (usually a heading title or quoted clue)
 * @param answers acceptable correct chapter answers; the first entry is treated as canonical for the
 *   answer key
 * @param answerRefs optional source verse references shown in the answer key
 */
data class Question(
    val question: String,
    val answers: List<ChapterRef>,
    val answerRefs: List<VerseRef>? = null,
)

/**
 * A [Question] presented as multiple choice, with one slot reserved for "none of these".
 *
 * @param choices candidate answers in display order; a `null` element represents the "none of these" choice
 * @param noneIndex position of the "none of these" choice within [choices]
 */
data class MultiChoiceQuestion(val question: Question, val choices: List<ChapterRef?>, val noneIndex: Int) {
    /** Index of the correct choice in [choices]; falls back to [noneIndex] when none of the choices match. */
    val correctChoice: Int = choices.indexOf(question.answers.first()).let { if (it < 0) noneIndex else it }
}

/**
 * Wraps [qAndA] as a multiple-choice question with [nChoices] options, drawing distractors from chapters
 * adjacent to the correct answer in [coveredChapters].
 *
 * One of the [nChoices] is always "none of these" (returned as a `null` element in [MultiChoiceQuestion.choices]);
 * with probability `1/nChoices` it is also the correct answer.
 */
fun multiChoice(
    qAndA: Question,
    coveredChapters: List<ChapterRef>,
    random: Random,
    nChoices: Int = 5,
): MultiChoiceQuestion {
    val nSpecificChoices = nChoices - 1 // nChoices minus 1 for the "none of these" answer
    val answerIsNone = random.nextInt(1..nChoices) == 1 // i.e., 1/nChoices chance of the answer being none of these
    val nCorrectChoices = if (answerIsNone) 0 else 1
    val maxOffset = nSpecificChoices - nCorrectChoices
    val correctAnswers: List<ChapterRef> = qAndA.answers
    val wrongChoicesPool: List<ChapterRef> = correctAnswers
        .flatMap { answer ->
            val i = coveredChapters.indexOf(answer)
            coveredChapters.subList(
                (i - maxOffset).coerceAtLeast(0),
                (i + maxOffset + 1).coerceAtMost(coveredChapters.size),
            )
        }
        .filterNot { it in correctAnswers }
        .distinct()
        .shuffled(random)

    val specificChoices: List<ChapterRef> =
        if (answerIsNone) wrongChoicesPool.take(nSpecificChoices)
        else wrongChoicesPool.take(nSpecificChoices - 1) + correctAnswers.first()

    val allChoices: List<ChapterRef?> = specificChoices.sorted() + null
    return MultiChoiceQuestion(qAndA, allChoices, nSpecificChoices)
}
