package net.markdrew.biblebowl.generation.quiz

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The cue a machine-graded quiz answer plays: a rising fifth for right, the same tone falling a
 * major third for wrong. Self-graded items play nothing — the student already knows.
 *
 * Synthesized rather than shipped as audio files, so there is nothing to license and every app
 * plays identical samples from [samples]. [CORRECT] must finish well inside the 800 ms ✓ flash.
 */
enum class QuizSound(internal val notes: List<Note>) {
    CORRECT(listOf(Note(1046.5, startS = 0.0, durS = 0.18), Note(1568.0, startS = 0.09, durS = 0.24))),
    WRONG(listOf(Note(659.3, startS = 0.0, durS = 0.20), Note(523.3, startS = 0.14, durS = 0.28))),
    ;

    /** Seconds from the first sample to the end of the last note's decay. */
    val durationS: Double get() = notes.maxOf { it.startS + it.durS }

    /** Mono samples in −1..1 at [sampleRate]: each [Note] summed, scaled by [SOUND_VOLUME]. */
    fun samples(sampleRate: Int): FloatArray {
        val out = FloatArray((durationS * sampleRate).toInt() + 1)
        for (note in notes) {
            val first = (note.startS * sampleRate).toInt()
            val count = (note.durS * sampleRate).toInt()
            for (i in 0 until count) {
                val t = i.toDouble() / sampleRate
                out[first + i] += (note.envelope(t) * sin(2 * PI * note.freqHz * t) * SOUND_VOLUME).toFloat()
            }
        }
        return out
    }
}

/** Overall loudness: quiet next to media, since it plays after every answer. */
private const val SOUND_VOLUME = 0.6

/** Envelope floor; an exponential decay can't reach zero, so it ends here (−80 dB). */
private const val SILENT = 0.0001

/**
 * A sine note with a short linear attack to [peak], then an exponential decay to silence at
 * [durS] — the shape of a struck chime.
 */
internal data class Note(
    val freqHz: Double,
    val startS: Double,
    val durS: Double,
    val peak: Double = 0.28,
    val attackS: Double = 0.008,
) {
    fun envelope(t: Double): Double = when {
        t < attackS -> SILENT + (peak - SILENT) * t / attackS
        else -> peak * (SILENT / peak).pow((t - attackS) / (durS - attackS))
    }
}
