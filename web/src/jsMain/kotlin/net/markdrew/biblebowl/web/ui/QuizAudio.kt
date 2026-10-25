package net.markdrew.biblebowl.web.ui

import kotlinx.browser.localStorage
import net.markdrew.biblebowl.generation.quiz.QuizSound

/**
 * Plays the quiz's answer cues through Web Audio, with the on/off choice remembered per browser.
 * The context is created on the first [play], which always follows an answer click or key press —
 * browsers only let a page start audio from a user gesture.
 */
object QuizAudio {
    private const val OFF_KEY = "tbb.quizSoundOff"

    var enabled: Boolean = runCatching { localStorage.getItem(OFF_KEY) == null }.getOrDefault(true)
        set(value) {
            field = value
            runCatching { if (value) localStorage.removeItem(OFF_KEY) else localStorage.setItem(OFF_KEY, "1") }
        }

    private var context: dynamic = null
    private val buffers = mutableMapOf<QuizSound, dynamic>()

    fun play(sound: QuizSound) {
        if (!enabled) return
        // Sound is a nicety: a browser without Web Audio (or one that refuses it) just stays quiet.
        // No Kotlin extension calls on the `dynamic` values here — those compile to JS method calls.
        runCatching {
            if (context == null) context = newContext()
            val ctx = context ?: return
            if (ctx.state == "suspended") ctx.resume()
            val source = ctx.createBufferSource()
            source.buffer = buffers.getOrPut(sound) { buffer(ctx, sound) }
            source.connect(ctx.destination)
            source.start()
        }
    }

    /** Rendered at the context's own rate, so the browser never resamples. */
    private fun buffer(ctx: dynamic, sound: QuizSound): dynamic {
        val rate = (ctx.sampleRate as Number).toInt()
        val samples = sound.samples(rate)
        val buffer = ctx.createBuffer(1, samples.size, rate)
        buffer.getChannelData(0).set(samples)
        return buffer
    }

    private fun newContext(): dynamic =
        js("(window.AudioContext || window.webkitAudioContext) ? new (window.AudioContext || window.webkitAudioContext)() : null")
}
