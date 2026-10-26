package net.markdrew.biblebowl.generate.practice

import net.markdrew.biblebowl.model.ChapterRef
import net.markdrew.biblebowl.model.LocationQuestion
import kotlin.random.Random
import kotlin.random.nextInt

/**
 * A [LocationQuestion] presented as multiple choice, with one slot reserved for "none of these".
 *
 * @param choices candidate chapters in display order; a `null` element represents the "none of these" choice
 * @param noneIndex position of the "none of these" choice within [choices]
 */
data class MultiChoiceQuestion(val question: LocationQuestion, val choices: List<ChapterRef?>, val noneIndex: Int) {
    /** Index of the correct choice in [choices]; falls back to [noneIndex] when none of the choices match. */
    val correctChoice: Int =
        choices.indexOfFirst { it != null && question.acceptsChapter(it) }.let { if (it < 0) noneIndex else it }
}

/**
 * Wraps [qAndA] as a multiple-choice question with [nChoices] options, drawing distractors from chapters
 * adjacent to the correct answer in [coveredChapters] — never another chapter the wording also occurs in,
 * so exactly one choice is right. The offered right answer is the first covered answer chapter.
 *
 * One of the [nChoices] is always "none of these" (returned as a `null` element in [MultiChoiceQuestion.choices]);
 * with probability `1/nChoices` it is also the correct answer.
 */
fun multiChoice(
    qAndA: LocationQuestion,
    coveredChapters: List<ChapterRef>,
    random: Random,
    nChoices: Int = 5,
): MultiChoiceQuestion {
    val nSpecificChoices = nChoices - 1 // nChoices minus 1 for the "none of these" answer
    val answerIsNone = random.nextInt(1..nChoices) == 1 // i.e., 1/nChoices chance of the answer being none of these
    val nCorrectChoices = if (answerIsNone) 0 else 1
    val maxOffset = nSpecificChoices - nCorrectChoices
    val correctAnswers: List<ChapterRef> = qAndA.chapters
    val offered: ChapterRef = correctAnswers.firstOrNull { it in coveredChapters } ?: correctAnswers.first()
    // Distractors sit near the covered answers (a twin outside the covered chapters has no neighbors here).
    val wrongChoicesPool: List<ChapterRef> = correctAnswers.filter { it in coveredChapters }.ifEmpty { listOf(offered) }
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
        else wrongChoicesPool.take(nSpecificChoices - 1) + offered

    val allChoices: List<ChapterRef?> = specificChoices.sorted() + null
    return MultiChoiceQuestion(qAndA, allChoices, nSpecificChoices)
}
