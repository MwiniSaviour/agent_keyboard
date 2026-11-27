package com.agentkeyboard.app.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.app.models.SttPointer
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.sqrt

/**
 * Voice typing in the keyboard process (SPEC §13, ADR-0012): sherpa-onnx streaming recognition with
 * Silero VAD over 16 kHz microphone audio. Runs where the user is looking: an IME that is showing
 * counts as in use for microphone access, and starting the keyboard never binds `:agent`.
 *
 * Privacy: audio lives only in 100 ms buffers on [thread]; nothing is written to disk or sent
 * anywhere. The recogniser is loaded on first use and released after [IDLE_RELEASE_MS] idle.
 *
 * Main thread API; [Listener] callbacks arrive on the main thread.
 */
class VoiceTyping(private val context: Context, private val main: Handler, private val out: Listener) {

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onLevel(rms: Float)
        /** Ended; [detail] is a user-presentable reason when it ended on a problem. */
        fun onEnd(detail: String?)
    }

    private class Session {
        @Volatile var stop = false
        @Volatile var cancelled = false
    }

    /** Loaded models, owned by [thread]. */
    private class Engine(val id: String, val recognizer: OnlineRecognizer, val vad: Vad?) {
        fun release() {
            recognizer.release()
            vad?.release()
        }
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var session: Session? = null
    private var engine: Engine? = null
    private val releaseEngine = Runnable {
        engine?.release()
        engine = null
        AkLog.d("voice: model released")
    }

    val active: Boolean get() = session != null

    /** A voice model is installed (cheap: one small app-private file). */
    fun installed(): Boolean = SttPointer.read(context) != null

    fun start() {
        if (session != null) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            out.onEnd(NO_MIC)
            return
        }
        val s = Session().also { session = it }
        val h = handler ?: HandlerThread("ak-voice", Process.THREAD_PRIORITY_URGENT_AUDIO).let { t ->
            t.start()
            thread = t
            Handler(t.looper).also { handler = it }
        }
        h.removeCallbacks(releaseEngine)
        h.post { run(s) }
    }

    /** Stop listening and insert what was heard. */
    fun stop() {
        session?.stop = true
    }

    /** Stop listening and drop what was heard. */
    fun cancel() {
        session?.let {
            it.cancelled = true
            it.stop = true
        }
    }

    fun dispose() {
        cancel()
        val h = handler ?: return
        h.post {
            engine?.release()
            engine = null
        }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // ───────────── voice thread ─────────────

    private fun run(s: Session) {
        var detail: String? = null
        try {
            detail = listen(s)
        } catch (e: Throwable) { // native load failures surface as UnsatisfiedLinkError / IllegalArgumentException
            AkLog.w("voice failed: ${e.javaClass.simpleName}")
            engine?.release()
            engine = null
            detail = "Voice typing stopped unexpectedly"
        }
        handler?.postDelayed(releaseEngine, IDLE_RELEASE_MS)
        main.post {
            if (session === s) session = null
            out.onEnd(detail)
        }
    }

    /** Returns a reason when it couldn't run, null after a normal stop. */
    private fun listen(s: Session): String? {
        val pointer = SttPointer.read(context) ?: return NO_MODEL
        val eng = engine?.takeIf { it.id == pointer.id } ?: run {
            engine?.release()
            engine = null
            val t0 = SystemClock.elapsedRealtime()
            load(pointer).also {
                engine = it
                AkLog.i("voice: ${pointer.id} loaded in ${SystemClock.elapsedRealtime() - t0} ms")
            }
        }
        if (s.stop) return null
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return MIC_BUSY
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 4))
        } catch (e: SecurityException) {
            return NO_MIC
        } catch (e: IllegalArgumentException) {
            return MIC_BUSY
        }
        val stream = eng.recognizer.createStream()
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) return MIC_BUSY
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return MIC_BUSY
            val pcm = ShortArray(CHUNK)
            val started = SystemClock.elapsedRealtime()
            var lastSpeech = started
            var shown = ""
            while (!s.stop) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) return MIC_BUSY
                val samples = FloatArray(n) { pcm[it] / 32768f }
                var sum = 0.0
                for (x in samples) sum += x * x
                val rms = sqrt(sum / n).toFloat()
                main.post { if (!s.cancelled) out.onLevel((rms * LEVEL_GAIN).coerceIn(0f, 1f)) }

                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (eng.recognizer.isReady(stream)) eng.recognizer.decode(stream)
                val text = eng.recognizer.getResult(stream).text.trim()
                if (text != shown) {
                    shown = text
                    lastSpeech = SystemClock.elapsedRealtime()
                    main.post { if (!s.cancelled) out.onPartial(text) }
                }
                eng.vad?.let { vad ->
                    vad.acceptWaveform(samples)
                    if (vad.isSpeechDetected()) lastSpeech = SystemClock.elapsedRealtime()
                    while (!vad.empty()) vad.pop() // segments aren't needed; keep its queue empty
                }
                if (eng.recognizer.isEndpoint(stream)) {
                    if (text.isNotEmpty()) main.post { if (!s.cancelled) out.onFinal(text) }
                    eng.recognizer.reset(stream)
                    shown = ""
                }
                val now = SystemClock.elapsedRealtime()
                // Nobody speaking for a while, or a very long session: stop on our own, like Gboard does.
                if (now - lastSpeech > SILENCE_STOP_MS || now - started > MAX_SESSION_MS) break
            }
            if (!s.cancelled) {
                stream.inputFinished()
                while (eng.recognizer.isReady(stream)) eng.recognizer.decode(stream)
                val tail = eng.recognizer.getResult(stream).text.trim()
                if (tail.isNotEmpty()) main.post { out.onFinal(tail) }
            }
            return null
        } finally {
            runCatching { rec.stop() }
            rec.release()
            stream.release()
            eng.vad?.reset()
        }
    }

    private fun load(p: SttPointer): Engine {
        val threads = if (Runtime.getRuntime().availableProcessors() >= 6) 2 else 1
        val recognizer = OnlineRecognizer(
            assetManager = null,
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(encoder = p.encoder, decoder = p.decoder, joiner = p.joiner),
                    tokens = p.tokens,
                    numThreads = threads,
                    provider = "cpu",
                    modelType = p.modelType,
                ),
                // Segment at natural pauses: each segment is inserted as soon as it ends.
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.0f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            ),
        )
        val vad = p.vad?.let { model ->
            try {
                Vad(assetManager = null, config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = model), sampleRate = SAMPLE_RATE, numThreads = 1))
            } catch (e: IllegalArgumentException) {
                AkLog.w("voice: VAD unavailable, using silence timing only")
                null
            }
        }
        return Engine(p.id, recognizer, vad)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 100 ms of audio per read. */
        private const val CHUNK = 1_600
        private const val LEVEL_GAIN = 6f
        private const val SILENCE_STOP_MS = 6_000L
        private const val MAX_SESSION_MS = 120_000L
        private const val IDLE_RELEASE_MS = 60_000L
        const val NO_MODEL = "Download a voice model in Agent Keyboard → Models to type by voice."
        const val NO_MIC = "Allow the microphone for Agent Keyboard (open the app → Models) to type by voice."
        const val MIC_BUSY = "The microphone is in use by another app."
    }
}
