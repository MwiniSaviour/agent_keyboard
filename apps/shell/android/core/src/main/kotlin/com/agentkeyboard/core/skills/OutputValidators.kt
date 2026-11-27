package com.agentkeyboard.core.skills

import com.agentkeyboard.core.agent.EngineFailure
import com.agentkeyboard.core.agent.InferenceOutput
import com.agentkeyboard.core.autonomy.Policy
import com.agentkeyboard.core.autonomy.Verdict
import com.agentkeyboard.core.json.Json
import com.agentkeyboard.core.json.JsonException
import com.agentkeyboard.core.json.JsonObj
import com.agentkeyboard.core.json.arrOrNull
import com.agentkeyboard.core.json.num
import com.agentkeyboard.core.json.objOrNull
import com.agentkeyboard.core.json.str
import com.agentkeyboard.core.privacy.PrivacyFilter
import com.agentkeyboard.core.protocol.MAX_TEXT_CHARS
import java.util.Locale

/**
 * Output validators (SPEC §11.3): small models wrap answers in chatter, quotes, code fences and
 * thinking blocks; structured skills must parse; nothing may leak a secret the input didn't contain;
 * autonomy drafts get a confidence the guardrails can trust (self-rating capped by heuristics).
 */
object OutputValidators {

    data class Context(
        val material: String,
        /** The text being transformed (refine/translate/dictation), empty for generative skills. */
        val source: String,
        val agentName: String,
        val truncated: Boolean,
    )

    fun validate(spec: SkillSpec, raw: String, ctx: Context): InferenceOutput {
        val out = when (spec.output) {
            SkillSpec.Output.TEXT -> InferenceOutput(prose(spec.id, raw, ctx))
            SkillSpec.Output.DEFINITION -> definition(raw)
            SkillSpec.Output.AUTONOMY_REPLY -> autonomy(raw, ctx)
        }
        if (out.text.isBlank()) throw EngineFailure("internal", "The model returned nothing. Try again.")
        // PII post-check: a card number or code may appear only if the user's own material had it.
        val text = if (PrivacyFilter.containsSecret(out.text) && !PrivacyFilter.containsSecret(ctx.material)) PrivacyFilter.redact(out.text) else out.text
        return out.copy(text = text.take(MAX_TEXT_CHARS))
    }

    // ───────────────────────────── prose ─────────────────────────────

    private val THINK = Regex("""<think>[\s\S]*?(</think>|$)""", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("""^```[a-zA-Z]*\s*\n?([\s\S]*?)\n?```\s*$""")
    private val PREAMBLE = Regex(
        """^(sure|okay|ok|certainly|of course|absolutely|here(?:'|’)?s|here is|here are|below is)\b[^\n]{0,80}[:：]\s*\n?""",
        RegexOption.IGNORE_CASE,
    )
    private val LABEL = Regex(
        """^(rewritten( text)?|revised( text)?|refined( text)?|corrected( text)?|translation|translated text|reply|response|answer|message|summary|explanation|output|assistant|continuation)\s*[:：]\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val QUOTES = listOf("\"" to "\"", "“" to "”", "'" to "'", "‘" to "’", "«" to "»")

    /** Removes model chatter around the answer. Exposed for tests. */
    fun clean(raw: String): String {
        var s = THINK.replace(raw, "").trim()
        FENCE.find(s)?.let { s = it.groupValues[1].trim() }
        repeat(2) {
            s = PREAMBLE.replaceFirst(s, "").trim()
            s = LABEL.replaceFirst(s, "").trim()
        }
        for ((open, close) in QUOTES) {
            if (s.length >= 2 && s.startsWith(open) && s.endsWith(close) && s.count { it.toString() == open } <= 2) {
                s = s.substring(open.length, s.length - close.length).trim()
                break
            }
        }
        return s.replace(Regex("\n{3,}"), "\n\n")
    }

    private fun prose(skill: String, raw: String, ctx: Context): String {
        var s = clean(raw)
        when (skill) {
            "reply" -> s = stripSpeaker(s, ctx.agentName)
            "complete" -> {
                // A continuation, not a rewrite: drop an echoed draft and keep one sentence at most.
                val tail = ctx.source.takeLast(40).trim()
                if (tail.isNotEmpty() && s.startsWith(tail)) s = s.removePrefix(tail)
                s = s.lineSequence().first().let { line -> Regex("""^.*?[.!?](\s|$)""").find(line)?.value ?: line }.trimEnd()
                // Keep a leading space decision with the shell (insertSmart); never start with a newline.
                s = s.trimStart('\n')
            }
            "refine", "translate", "transcribe_refine" -> {
                // A rewrite should be about as long as its source; a far longer answer is the model chatting.
                if (ctx.source.isNotBlank() && s.length > ctx.source.length * 4 + 200) s = s.substring(0, ctx.source.length * 2 + 100).trimEnd() + "…"
            }
        }
        return s
    }

    private fun stripSpeaker(s: String, agentName: String): String {
        val prefixes = listOf("Me:", "Me -", "You:", "$agentName:")
        var out = s
        for (p in prefixes) if (out.startsWith(p, ignoreCase = true)) out = out.substring(p.length).trim()
        return out
    }

    // ───────────────────────────── structured ─────────────────────────────

    /** First balanced JSON object in [raw] (models sometimes add text around constrained output). */
    fun jsonObject(raw: String): JsonObj? {
        val s = THINK.replace(raw, "")
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return try {
                    Json.parse(s.substring(start, i + 1)).objOrNull()
                } catch (e: JsonException) {
                    null
                }
            }
        }
        return null
    }

    private fun definition(raw: String): InferenceOutput {
        val o = jsonObject(raw)
        if (o == null) {
            val text = clean(raw)
            return InferenceOutput(text) // still useful prose; the card shows it as-is
        }
        fun list(k: String, max: Int) = o[k].arrOrNull()?.items?.mapNotNull { it.str()?.trim()?.takeIf(String::isNotEmpty) }?.distinct()?.take(max).orEmpty()
        val explanation = o["explanation"].str()?.trim().orEmpty()
        val example = o["example"].str()?.trim().orEmpty()
        val synonyms = list("synonyms", 4)
        val antonyms = list("antonyms", 3)
        if (explanation.isEmpty()) throw EngineFailure("internal", "The model returned nothing. Try again.")
        // Same layout as the web mock's define card (apps/shell/src/bridge/web-mock/runtime.ts).
        val text = buildString {
            append("Explanation: ").append(explanation)
            if (example.isNotEmpty()) append("\nExample: ").append(example)
            if (synonyms.isNotEmpty()) append("\nSynonyms: ").append(synonyms.joinToString(", "))
            if (antonyms.isNotEmpty()) append("\nAntonyms: ").append(antonyms.joinToString(", "))
        }
        val data = linkedMapOf("explanation" to explanation, "example" to example)
        if (synonyms.isNotEmpty()) data["synonyms"] = synonyms.joinToString(", ")
        if (antonyms.isNotEmpty()) data["antonyms"] = antonyms.joinToString(", ")
        return InferenceOutput(text, data)
    }

    private val NUMBER = Regex("""\d+(?:[.,]\d+)*""")
    private val CURRENCY = Regex("""(GH₵|GHS|₵|\$|€|£|₦|NGN|KES|USD|cedis?)""", RegexOption.IGNORE_CASE)

    /**
     * {reply, confidence}. The model's self-rating is an upper bound, never trusted alone:
     * - a reply the outbound policy blocks → 0 (never auto-sent);
     * - numbers or money in the reply that appear nowhere in the material (invented prices, times,
     *   quantities) → at most 0.4;
     * - cut off by the token limit → at most 0.3; unusually long → ×0.8;
     * - missing/invalid JSON → the prose is kept as a draft with 0.0 confidence (escalates, FR-AU-04).
     */
    private fun autonomy(raw: String, ctx: Context): InferenceOutput {
        val o = jsonObject(raw)
        val reply = stripSpeaker(clean(o?.get("reply").str() ?: if (o == null) raw else ""), ctx.agentName)
        var confidence = o?.get("confidence").num()?.takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0) ?: 0.0
        if (Policy.checkOutbound(reply) is Verdict.Blocked) confidence = 0.0
        val grounded = NUMBER.findAll(ctx.material).map { it.value }.toSet()
        if (NUMBER.findAll(reply).any { it.value !in grounded } || (CURRENCY.containsMatchIn(reply) && !CURRENCY.containsMatchIn(ctx.material))) {
            confidence = minOf(confidence, 0.4)
        }
        if (ctx.truncated) confidence = minOf(confidence, 0.3)
        if (reply.length > 600) confidence *= 0.8
        return InferenceOutput(reply, mapOf("confidence" to String.format(Locale.ROOT, "%.2f", confidence)))
    }
}
