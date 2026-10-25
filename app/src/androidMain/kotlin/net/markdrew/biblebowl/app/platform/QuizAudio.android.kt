package net.markdrew.biblebowl.app.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import net.markdrew.biblebowl.app.AppContext
import net.markdrew.biblebowl.generation.quiz.QuizSound

private const val PREFS = "tbb"
private const val SOUNDS_ON_KEY = "quizSoundsOn"

/** One static track per cue, built on first use and replayed from the start each time. */
private val tracks = mutableMapOf<QuizSound, AudioTrack>()

internal actual fun playQuizSound(sound: QuizSound) {
    runCatching {
        val track = tracks.getOrPut(sound) { staticTrack(sound) }
        track.stop()
        track.reloadStaticData()
        track.play()
    }
}

/** Game usage, so the cue follows the media volume the volume keys control while the app is open. */
private fun staticTrack(sound: QuizSound): AudioTrack {
    val pcm = sound.samples(QUIZ_SOUND_RATE).toPcm16()
    return AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(QUIZ_SOUND_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
        .build()
        .also { it.write(pcm, 0, pcm.size) }
}

private fun prefs() = AppContext.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

internal actual fun loadQuizSoundsOn(): Boolean = prefs().getBoolean(SOUNDS_ON_KEY, true)

internal actual fun saveQuizSoundsOn(on: Boolean) {
    prefs().edit().putBoolean(SOUNDS_ON_KEY, on).apply()
}
