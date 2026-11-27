package com.agentkeyboard.app.models

import com.agentkeyboard.app.AkLog
import com.agentkeyboard.core.inference.GenerationRequest
import com.agentkeyboard.core.inference.GenerationResult
import com.agentkeyboard.core.inference.LoadOptions
import com.agentkeyboard.core.inference.LoadedModel
import com.agentkeyboard.core.inference.ModelRuntime
import com.agentkeyboard.core.models.Backend
import com.agentkeyboard.core.models.InstalledModel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.google.ai.edge.litertlm.Backend as LBackend

/**
 * LiteRT-LM (ADR-0004, ADR-0011), the only generative runtime. Pinned to `litertlm-android:0.20.0`;
 * every call here exists in that release.
 *
 * Prefix cache (SPEC §12.3): the agent's persona is the conversation's system instruction, prefilled
 * once (`prefillPrefaceOnInit`) into a *base* conversation. Each run clones the base, so its KV state
 * starts after the persona and only the run's own prompt is prefilled. One base is kept (the last
 * persona + sampler); a different agent or skill temperature rebuilds it.
 */
class LiteRtLmRuntime(private val cacheDir: File, private val nativeLibraryDir: String) : ModelRuntime {

    override fun load(model: InstalledModel, options: LoadOptions): LoadedModel {
        val backend = backendOf(options.backend, options.threads)
        cacheDir.mkdirs()
        val engine = Engine(
            EngineConfig(
                modelPath = model.primary,
                backend = backend,
                // Vision encoder on the same accelerator (CPU when the text model is on CPU).
                visionBackend = if (options.vision) backendOf(options.backend, options.threads) else null,
                maxNumTokens = options.maxTokens,
                // Compiled-kernel / weight cache: makes the second load much faster (NFR-P-13).
                cacheDir = cacheDir.absolutePath,
            ),
        )
        try {
            engine.initialize()
        } catch (t: Throwable) {
            runCatching { engine.close() }
            throw t
        }
        return LiteRtLmModel(engine, options.backend, options.vision)
    }

    private fun backendOf(b: Backend, threads: Int): LBackend = when (b) {
        Backend.CPU -> LBackend.CPU(threadCount = threads)
        Backend.GPU -> LBackend.GPU()
        Backend.NPU -> LBackend.NPU(nativeLibraryDir = nativeLibraryDir)
    }
}

private class LiteRtLmModel(
    private val engine: Engine,
    override val backend: Backend,
    override val vision: Boolean,
) : LoadedModel {

    private data class BaseKey(val prefix: String, val temperature: Double, val topK: Int, val topP: Double)

    private var base: Conversation? = null
    private var baseKey: BaseKey? = null
    /** Some model builds can't clone; then every run gets a fresh conversation. */
    private var cloneWorks = true
    /** Constrained decoding isn't supported by every model; fall back to plain text + our JSON parser. */
    private var formatWorks = true

    override fun generate(request: GenerationRequest, onToken: (String) -> Unit, isCancelled: () -> Boolean): GenerationResult {
        val conversation = conversationFor(request)
        var abandoned = false
        try {
            val format = request.jsonSchema?.takeIf { formatWorks }?.let { ResponseFormat.json(it) }
            val first = send(conversation, request, format, onToken, isCancelled)
            if (first.error != null && format != null && first.chunks == 0 && !first.cancelled) {
                AkLog.w("constrained decoding unavailable; retrying as text")
                formatWorks = false
                return generate(request, onToken, isCancelled)
            }
            abandoned = first.abandoned
            first.error?.let { throw it }
            return GenerationResult(first.text, truncated = first.chunks >= request.maxOutputTokens - 1, cancelled = first.cancelled)
        } finally {
            // Never free a conversation native code may still be decoding into.
            if (!abandoned) runCatching { conversation.close() }
        }
    }

    private class Outcome(val text: String, val chunks: Int, val cancelled: Boolean, val error: Throwable?, val abandoned: Boolean = false)

    private fun send(
        conversation: Conversation, request: GenerationRequest, format: ResponseFormat?,
        onToken: (String) -> Unit, isCancelled: () -> Boolean,
    ): Outcome {
        val done = CountDownLatch(1)
        val out = StringBuilder()
        var chunks = 0
        val error = AtomicReference<Throwable?>(null)
        conversation.sendMessageAsync(
            request.user,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    val delta = message.toString()
                    if (delta.isEmpty()) return
                    synchronized(out) {
                        out.append(delta)
                        chunks++ // one decode step per streamed chunk
                    }
                    onToken(delta)
                }
                override fun onDone() = done.countDown()
                override fun onError(throwable: Throwable) {
                    error.set(throwable)
                    done.countDown()
                }
            },
            maxOutputToken = request.maxOutputTokens,
            responseFormat = format,
        )
        var cancelled = false
        var cancelledAt = 0L
        var abandoned = false
        while (!done.await(POLL_MS, TimeUnit.MILLISECONDS)) {
            if (!cancelled && isCancelled()) {
                cancelled = true
                cancelledAt = System.nanoTime()
                runCatching { conversation.cancelProcess() }
            }
            if (cancelled && System.nanoTime() - cancelledAt > CANCEL_GRACE_NS) {
                AkLog.w("cancel did not complete in time")
                abandoned = true
                break
            }
        }
        val (text, n) = synchronized(out) { out.toString() to chunks }
        // A cancel surfaces as onError from the native side; it is not a failure.
        return Outcome(text, n, cancelled, if (cancelled) null else error.get(), abandoned)
    }

    private fun conversationFor(r: GenerationRequest): Conversation {
        val key = BaseKey(r.prefixKey, r.temperature, r.topK, r.topP)
        if (cloneWorks) {
            try {
                val b = base?.takeIf { baseKey == key && it.isAlive } ?: rebuildBase(r, key)
                return b.clone()
            } catch (e: Exception) {
                AkLog.w("conversation clone unavailable: ${e::class.java.simpleName}")
                cloneWorks = false
                dropBase()
            }
        }
        return engine.createConversation(config(r, prefill = false))
    }

    private fun rebuildBase(r: GenerationRequest, key: BaseKey): Conversation {
        dropBase()
        return engine.createConversation(config(r, prefill = true)).also {
            base = it
            baseKey = key
        }
    }

    private fun config(r: GenerationRequest, prefill: Boolean) = ConversationConfig(
        systemInstruction = Contents.of(r.system),
        samplerConfig = SamplerConfig(topK = r.topK, topP = r.topP, temperature = r.temperature),
        prefillPrefaceOnInit = prefill,
        // Keyboard answers are short: thinking only costs latency (NFR-P-07) and output budget.
        thinkingConfig = ThinkingConfig(enableThinking = false),
        enableResponseFormat = true,
    )

    private fun dropBase() {
        base?.let { runCatching { it.close() } }
        base = null
        baseKey = null
    }

    override fun close() {
        dropBase()
        engine.close()
    }

    private companion object {
        const val POLL_MS = 40L
        const val CANCEL_GRACE_NS = 3_000_000_000L
    }
}
