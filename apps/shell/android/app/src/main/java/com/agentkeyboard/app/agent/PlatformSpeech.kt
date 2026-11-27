package com.agentkeyboard.app.agent

import android.content.Context
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.core.agent.Speech
import java.util.Locale

/**
 * Part 2 TTS (`Capabilities.tts = true`): the platform engine, which works offline once a voice is
 * installed. Part 3 can swap in Piper/VITS behind the same port.
 *
 * The engine is created on first use and released after [IDLE_MS] without speech, or on memory trim,
 * to keep `:agent` within its idle budget (NFR-M: ≤ 40 MB).
 */
class PlatformSpeech(private val context: Context, private val handler: Handler) : Speech {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private val release = Runnable { shutdown() }

    override fun speak(text: String) {
        handler.removeCallbacks(release)
        if (ready) say(text) else {
            pending = text // only the latest request matters
            if (tts == null) {
                tts = TextToSpeech(context) { status ->
                    handler.post {
                        ready = status == TextToSpeech.SUCCESS
                        if (!ready) { AkLog.w("tts init failed ($status)"); shutdown(); return@post }
                        tts?.language = Locale.getDefault()
                        tts?.setOnUtteranceProgressListener(idleTimer)
                        pending?.let(::say)
                        pending = null
                    }
                }
            }
        }
    }

    override fun stop() {
        pending = null
        tts?.stop()
        if (tts != null) {
            handler.removeCallbacks(release)
            handler.postDelayed(release, IDLE_MS)
        }
    }

    private fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ak")
    }

    /** The idle countdown starts when speech ends, never mid-utterance. Callbacks arrive on a TTS thread. */
    private val idleTimer = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { handler.removeCallbacks(release) }
        override fun onDone(utteranceId: String?) { handler.postDelayed(release, IDLE_MS) }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { handler.postDelayed(release, IDLE_MS) }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { handler.postDelayed(release, IDLE_MS) }
    }

    fun shutdown() {
        handler.removeCallbacks(release)
        tts?.shutdown()
        tts = null
        ready = false
        pending = null
    }

    private companion object {
        const val IDLE_MS = 90_000L
    }
}
