package com.agentkeyboard.core.inference

import com.agentkeyboard.core.agent.EngineFailure
import com.agentkeyboard.core.agent.InferenceEngine
import com.agentkeyboard.core.agent.InferenceJob
import com.agentkeyboard.core.agent.InferenceOutput
import com.agentkeyboard.core.models.Capability
import com.agentkeyboard.core.models.InstalledModels
import com.agentkeyboard.core.skills.OutputValidators
import com.agentkeyboard.core.skills.PromptBuilder
import com.agentkeyboard.core.skills.SkillSpec
import com.agentkeyboard.core.skills.Skills

/**
 * The on-device [InferenceEngine] (Part 3): skill → installed model → context pack → resident model
 * → validated output. Everything here is platform-free; the only native piece is the [ModelManager]'s
 * runtime (LiteRT-LM on Android).
 */
class LocalInferenceEngine(
    private val models: InstalledModels,
    private val manager: ModelManager,
) : InferenceEngine {

    @Volatile private var lastModel: String? = null

    override val modelId: String
        get() = lastModel ?: models.pick(Capability.TEXT)?.entry?.id ?: "none"

    /** Only real generations take long enough to need the foreground service; "no model" fails fast. */
    override val needsForeground: Boolean
        get() = models.pick(Capability.TEXT) != null

    override fun generate(job: InferenceJob, onToken: (String) -> Unit, isCancelled: () -> Boolean): InferenceOutput {
        val spec = Skills.of(job.request)
        spec.unavailable?.let { throw EngineFailure("unavailable", it) }
        val installed = models.pick(spec.capability) ?: throw EngineFailure("unavailable", NO_MODEL)
        val prompt = PromptBuilder.build(job, spec, installed.entry)
        if (isCancelled()) throw EngineFailure("cancelled", "Cancelled")
        return manager.use(installed, spec.capability) { model ->
            lastModel = installed.entry.id
            val filter = if (spec.stream) ThinkFilter(onToken) else null
            val result = model.generate(PromptBuilder.request(prompt, spec), { delta -> filter?.push(delta) }, isCancelled)
            filter?.flush()
            if (result.cancelled || isCancelled()) throw EngineFailure("cancelled", "Cancelled")
            OutputValidators.validate(
                spec, result.text,
                OutputValidators.Context(material = prompt.user, source = prompt.source, agentName = job.agent.name, truncated = result.truncated),
            )
        }
    }

    companion object {
        const val NO_MODEL = "The on-device AI model isn’t installed yet. Open Agent Keyboard → Models to add it."
    }
}

/**
 * Hides `<think>…</think>` reasoning (Qwen3-style models) from the streamed card. Tags may be split
 * across deltas, so a short tail is held back until it can't be the start of a tag.
 */
class ThinkFilter(private val sink: (String) -> Unit) {
    private val pending = StringBuilder()
    private var thinking = false

    fun push(delta: String) {
        pending.append(delta)
        while (true) {
            if (thinking) {
                val end = pending.indexOf(CLOSE)
                if (end < 0) {
                    // Keep only what could be the start of "</think>".
                    val keep = minOf(pending.length, CLOSE.length - 1)
                    pending.delete(0, pending.length - keep)
                    return
                }
                pending.delete(0, end + CLOSE.length)
                thinking = false
                continue
            }
            val start = pending.indexOf(OPEN)
            if (start >= 0) {
                if (start > 0) sink(pending.substring(0, start))
                pending.delete(0, start + OPEN.length)
                thinking = true
                continue
            }
            // Emit all but a possible partial "<think" at the end.
            var hold = 0
            for (k in minOf(OPEN.length - 1, pending.length) downTo 1) {
                if (OPEN.startsWith(pending.substring(pending.length - k))) {
                    hold = k
                    break
                }
            }
            val emit = pending.length - hold
            if (emit > 0) {
                sink(pending.substring(0, emit))
                pending.delete(0, emit)
            }
            return
        }
    }

    /** End of stream: a held-back partial tag was just text (an unclosed think block stays hidden). */
    fun flush() {
        if (!thinking && pending.isNotEmpty()) sink(pending.toString())
        pending.setLength(0)
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
