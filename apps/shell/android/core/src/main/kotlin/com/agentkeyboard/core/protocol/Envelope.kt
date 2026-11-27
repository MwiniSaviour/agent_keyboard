package com.agentkeyboard.core.protocol

import com.agentkeyboard.core.json.Json
import com.agentkeyboard.core.json.JsonException
import com.agentkeyboard.core.json.JsonObj
import com.agentkeyboard.core.json.JsonValue
import com.agentkeyboard.core.json.long
import com.agentkeyboard.core.json.obj
import com.agentkeyboard.core.json.str
import java.util.concurrent.atomic.AtomicLong

const val PROTOCOL_VERSION = 1

/** `{v, id, type, ts, re?, payload}` — SPEC §8.1, `Envelope` in messages.ts. */
data class Envelope(
    val v: Int,
    val id: String,
    val type: String,
    val ts: Long,
    val re: String? = null,
    val payload: JsonValue,
) {
    fun encode(): String = Json.write(
        obj("v" to v, "id" to id, "type" to type, "ts" to ts, "re" to re, "payload" to payload),
    )
}

sealed interface Decoded {
    data class Ok(val envelope: Envelope) : Decoded
    /** The sender speaks another major version. `id` is echoed so the reply can correlate. */
    data class VersionMismatch(val version: Long?, val id: String?) : Decoded
    data class Malformed(val reason: String) : Decoded
}

object EnvelopeCodec {
    /** Hard cap on one message; the largest legitimate payload is an agent with a 15k-char role. */
    const val MAX_MESSAGE_CHARS = 256 * 1024

    fun decode(raw: String): Decoded {
        if (raw.length > MAX_MESSAGE_CHARS) return Decoded.Malformed("message too large")
        val root = try {
            Json.parse(raw)
        } catch (e: JsonException) {
            return Decoded.Malformed(e.message ?: "invalid json")
        }
        val o = root as? JsonObj ?: return Decoded.Malformed("envelope must be an object")
        val id = o["id"].str()
        val v = o["v"].long()
        if (v != PROTOCOL_VERSION.toLong()) return Decoded.VersionMismatch(v, id)
        if (id.isNullOrEmpty() || id.length > 32) return Decoded.Malformed("id")
        val type = o["type"].str()?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return Decoded.Malformed("type")
        val ts = o["ts"].long() ?: return Decoded.Malformed("ts")
        val payload = o["payload"] ?: return Decoded.Malformed("payload")
        val re = o["re"]?.let { it.str() ?: return Decoded.Malformed("re") }
        return Decoded.Ok(Envelope(PROTOCOL_VERSION, id, type, ts, re, payload))
    }
}

/** Native ids are `n:<n>`, monotonic per process (shell ids are `s:<n>`). Thread-safe. */
class IdGenerator(private val prefix: String = "n") {
    private val seq = AtomicLong()
    fun next(): String = "$prefix:${seq.incrementAndGet()}"
}

/**
 * The v1 message catalog, mirroring the keys of `ShellCommands`, `ShellRequests` and `NativeEvents`
 * in messages.ts. `packages/protocol/fixtures/contract.json` is the shared source both test suites
 * compare against, so a message added on one side only fails CI on the other.
 */
object Catalog {
    val COMMANDS: Set<String> = linkedSetOf(
        "text.commit", "text.delete", "text.replace", "editor.action", "editor.cursor", "feedback.key",
        "agent.run", "agent.cancel", "autonomy.set", "autonomy.takeover",
        "voice.start", "voice.stop", "voice.cancel", "tts.speak", "tts.stop",
        "clipboard.paste", "clipboard.pin", "clipboard.delete", "settings.set", "app.open", "form.fill",
        "agents.save", "agents.delete", "system.open", "ui.resize", "screen.capture",
        "models.download", "models.cancel", "models.delete", "models.import", "models.use", "models.scan", "models.adopt",
    )
    val REQUESTS: Set<String> = linkedSetOf("hello", "context.snapshot", "context.scan", "clipboard.list", "agents.list", "system.status", "models.status")
    val EVENTS: Set<String> = linkedSetOf(
        "editor.start", "editor.state", "editor.finish", "suggestions.update", "insight.update",
        "agent.token", "agent.done", "agent.error", "autonomy.phase",
        "voice.partial", "voice.final", "voice.level", "voice.end", "theme.update", "agents.changed",
        "screen.state", "models.changed",
    )
    val ERROR_CODES: Set<String> = linkedSetOf("unknown_type", "bad_payload", "secure_field", "unavailable", "internal", "version")
}

/** Stable error codes (`ErrorPayload['code']` in messages.ts). */
enum class ErrorCode(override val wire: String) : WireEnum {
    UNKNOWN_TYPE("unknown_type"), BAD_PAYLOAD("bad_payload"), SECURE_FIELD("secure_field"),
    UNAVAILABLE("unavailable"), INTERNAL("internal"), VERSION("version"),
}

class BridgeFailure(val code: ErrorCode, message: String) : Exception(message)
