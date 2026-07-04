package net.markdrew.biblebowl.generate.practice

import net.markdrew.biblebowl.model.PracticeContent
import net.markdrew.biblebowl.model.StudySet
import kotlin.random.Random
import kotlin.random.nextInt

/**
 * One practice test instance: a [round], its source [content], and a fixed [randomSeed] for reproducibility.
 *
 * Defaulting [numQuestions] from the round and using a deterministic [randomSeed] means generating the same
 * test twice (same seed, same content) yields identical questions. Unlike bible-bowl's CLI version this
 * carries no filesystem output path — the server renders the Typst source to a string and compiles it in
 * memory.
 *
 * @param numQuestions overrides [Round.questions] when fewer questions are wanted
 * @param randomSeed seed used to drive question selection and shuffling
 */
data class PracticeTest(
    val round: Round,
    val content: PracticeContent,
    val numQuestions: Int = round.questions,
    val randomSeed: Int = Random.nextInt(1..9_999),
) {
    /** [Random] instance keyed by [randomSeed]; reusable for any sampling needed by the generator. */
    val random = Random(randomSeed)

    /** Convenience accessor for `content.studyData.studySet`. */
    val studySet: StudySet = content.studyData.studySet
}
