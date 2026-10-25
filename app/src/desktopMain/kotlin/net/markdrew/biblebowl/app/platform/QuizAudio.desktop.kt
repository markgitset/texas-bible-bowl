package net.markdrew.biblebowl.app.platform

import java.util.concurrent.Executors
import java.util.prefs.Preferences
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import net.markdrew.biblebowl.generation.quiz.QuizSound

private const val SOUNDS_ON_KEY = "quizSoundsOn"

private val prefs: Preferences? = runCatching { Preferences.userRoot().node("net/markdrew/biblebowl/app") }.getOrNull()

/** Opening a line can take a moment, so clips open and play off the UI thread — one thread, so [clips] needs no lock. */
private val audioThread = Executors.newSingleThreadExecutor { Thread(it, "quiz-audio").apply { isDaemon = true } }
private val clips = mutableMapOf<QuizSound, Clip>()

internal actual fun playQuizSound(sound: QuizSound) {
    audioThread.execute {
        runCatching {
            val clip = clips.getOrPut(sound) { openClip(sound) }
            clip.stop()
            clip.framePosition = 0
            clip.start()
        }
    }
}

private fun openClip(sound: QuizSound): Clip {
    val pcm = sound.samples(QUIZ_SOUND_RATE).toPcm16()
    val bytes = ByteArray(pcm.size * 2)
    pcm.forEachIndexed { i, s ->
        bytes[2 * i] = s.toByte()
        bytes[2 * i + 1] = (s.toInt() shr 8).toByte()
    }
    val format = AudioFormat(QUIZ_SOUND_RATE.toFloat(), 16, 1, true, false)
    return AudioSystem.getClip().apply { open(format, bytes, 0, bytes.size) }
}

internal actual fun loadQuizSoundsOn(): Boolean = prefs?.getBoolean(SOUNDS_ON_KEY, true) ?: true

internal actual fun saveQuizSoundsOn(on: Boolean) {
    runCatching { prefs?.putBoolean(SOUNDS_ON_KEY, on) }
}
