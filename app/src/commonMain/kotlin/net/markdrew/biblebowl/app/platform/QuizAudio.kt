package net.markdrew.biblebowl.app.platform

import androidx.compose.runtime.mutableStateOf
import net.markdrew.biblebowl.generation.quiz.QuizSound

/** The quiz's answer cues, with the on/off choice remembered on this device. */
object QuizAudio {
    private val on = mutableStateOf(loadQuizSoundsOn())

    var enabled: Boolean
        get() = on.value
        set(value) {
            on.value = value
            saveQuizSoundsOn(value)
        }

    fun play(sound: QuizSound) {
        if (enabled) playQuizSound(sound)
    }
}

/** The rate every platform renders [QuizSound.samples] at. */
internal const val QUIZ_SOUND_RATE = 44_100

/** Plays [sound] without blocking; never throws — a device with no audio output just stays quiet. */
internal expect fun playQuizSound(sound: QuizSound)

internal expect fun loadQuizSoundsOn(): Boolean

internal expect fun saveQuizSoundsOn(on: Boolean)

/** 16-bit signed PCM, the format both AudioTrack and javax.sound take. */
internal fun FloatArray.toPcm16(): ShortArray =
    ShortArray(size) { (this[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
