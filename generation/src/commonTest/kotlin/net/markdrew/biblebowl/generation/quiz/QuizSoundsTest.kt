package net.markdrew.biblebowl.generation.quiz

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuizSoundsTest {

    @Test
    fun correctSoundEndsWellInsideTheCorrectFlash() {
        // The ✓ shows for 800 ms before the quiz moves on; the cue should be long over by then.
        assertTrue(QuizSound.CORRECT.durationS < 0.4)
    }

    @Test
    fun samplesCoverTheWholeSoundAndNeverClip() {
        QuizSound.entries.forEach { sound ->
            val samples = sound.samples(44_100)
            assertEquals((sound.durationS * 44_100).toInt() + 1, samples.size)
            assertTrue(samples.all { abs(it) < 1f }, "$sound clips")
            assertTrue(samples.any { abs(it) > 0.1f }, "$sound is silent")
        }
    }

    @Test
    fun soundsStartAndEndSilentSoThereIsNoClick() {
        QuizSound.entries.forEach { sound ->
            val samples = sound.samples(44_100)
            assertTrue(abs(samples.first()) < 0.001f, "$sound starts with a click")
            assertTrue(samples.takeLast(10).all { abs(it) < 0.001f }, "$sound ends with a click")
        }
    }
}
