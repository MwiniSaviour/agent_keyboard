package com.agentkeyboard.core.agent

import com.agentkeyboard.core.context.ConversationCache
import com.agentkeyboard.core.json.JsonValue
import com.agentkeyboard.core.protocol.AgentDefinition
import com.agentkeyboard.core.protocol.AgentRunRequest
import com.agentkeyboard.core.protocol.ClipboardItem
import com.agentkeyboard.core.protocol.FieldContext

/*
 * Ports of the `:agent` runtime (SPEC §8.3, ADR-0003). The Android process implements them with
 * SQLCipher, TextToSpeech and a foreground service; tests use the in-memory versions in
 * `InMemoryStores.kt`. Every port is called from the runtime's single serial thread, except
 * [InferenceEngine.generate], which runs on the engine thread.
 */

interface AgentStore {
    fun all(): List<AgentDefinition>
    fun put(agent: AgentDefinition)
    fun delete(id: String)
}

data class StoredClip(val item: ClipboardItem, val expiresAt: Long?)

interface ClipboardStore {
    fun all(): List<StoredClip>
    fun put(clip: StoredClip)
    fun delete(ids: Collection<String>)
}

data class AuditRecord(
    val at: Long,
    val action: String,
    val agentId: String?,
    val packageName: String?,
    val conversation: String?,
    val seen: String?,
    val sent: String?,
    val reason: String?,
    val model: String?,
)

/** Append-only (enforced again by the `audit_log_no_update` trigger). */
interface AuditStore {
    fun append(record: AuditRecord)
    fun purgeBefore(at: Long): Int
}

interface SettingsStore {
    fun get(key: String): JsonValue?
    fun put(key: String, value: JsonValue)
}

interface Speech {
    fun speak(text: String)
    fun stop()
}

/** Outbound traffic to every bound IME (AIDL `IAgentCallback`). */
interface AgentOutput {
    fun envelope(raw: String)
    fun inbound(packageName: String, conversationKey: String, messageJson: String, remoteInputAvailable: Boolean)
}

// ───────────────────────────── Inference (Part 3 seam) ─────────────────────────────

data class RunContext(
    val field: FieldContext?,
    /** Rung-1 conversation for the focused app; null in blocked apps or when nothing was captured. */
    val conversation: ConversationCache.Conversation?,
)

data class InferenceJob(val request: AgentRunRequest, val agent: AgentDefinition, val context: RunContext)

data class InferenceOutput(val text: String, val data: Map<String, String>? = null)

/** [code] is the protocol's `agent.error` code: `cancelled | unavailable | policy | internal`. */
class EngineFailure(val code: String, message: String) : Exception(message)

/**
 * The single resident generative model (ADR-0004, SPEC §12.2). Part 3 plugs LiteRT-LM in here; the
 * runtime already owns scheduling, cancellation, streaming, policy and foreground lifetime.
 */
interface InferenceEngine {
    /** Reported as `AgentRunResult.model` and in the audit log. */
    val modelId: String

    /** True when a run can take long enough that the process must be foreground (FGS specialUse). */
    val needsForeground: Boolean

    /**
     * Blocking; called on the engine thread. Streams deltas through [onToken] and polls
     * [isCancelled] between tokens. Throws [EngineFailure] for expected failures.
     */
    fun generate(job: InferenceJob, onToken: (String) -> Unit, isCancelled: () -> Boolean): InferenceOutput
}

/** Part 2 engine: no model is installed, so every run fails fast and honestly with `unavailable`. */
object NoModelEngine : InferenceEngine {
    override val modelId = "none"
    override val needsForeground = false
    override fun generate(job: InferenceJob, onToken: (String) -> Unit, isCancelled: () -> Boolean): InferenceOutput =
        throw EngineFailure("unavailable", "On-device models aren’t installed yet. Open Agent Keyboard → Models to add them.")
}
