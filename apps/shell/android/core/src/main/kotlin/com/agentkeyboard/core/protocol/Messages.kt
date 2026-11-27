package com.agentkeyboard.core.protocol

import com.agentkeyboard.core.json.JsonObj
import com.agentkeyboard.core.json.JsonValue
import com.agentkeyboard.core.json.bool
import com.agentkeyboard.core.json.int
import com.agentkeyboard.core.json.objOrNull
import com.agentkeyboard.core.json.str

/**
 * Typed shell → native messages. Decoding is strict: unknown enum values, wrong types and
 * out-of-range numbers are rejected with [BridgeFailure] (`bad_payload`), never coerced.
 */
sealed interface Command {
    data class Commit(val text: String) : Command
    data class Delete(val before: Int, val after: Int) : Command
    data class Replace(val scope: String, val text: String) : Command
    data class Action(val action: ImeActionKind) : Command
    data class Cursor(val delta: Int) : Command
    data class Feedback(val kind: String) : Command
    data class AgentRun(val request: AgentRunRequest) : Command
    data class AgentCancel(val runId: String) : Command
    data class AutonomySet(val enabled: Boolean, val level: AutonomyLevel, val agentId: String) : Command
    data object AutonomyTakeover : Command
    data class VoiceStart(val refine: Boolean) : Command
    data object VoiceStop : Command
    data object VoiceCancel : Command
    data class TtsSpeak(val text: String) : Command
    data object TtsStop : Command
    data class ClipboardPaste(val id: String) : Command
    data class ClipboardPin(val id: String, val pinned: Boolean) : Command
    data class ClipboardDelete(val id: String) : Command
    data class SettingsSet(val key: String, val value: JsonValue) : Command
    data class AppOpen(val route: String) : Command
    data class FormFill(val values: Map<String, String>) : Command
    data class AgentsSave(val agent: AgentDefinition) : Command
    data class AgentsDelete(val id: String) : Command
    data class SystemOpen(val target: SystemSettingsTarget) : Command
    data class Resize(val heightDp: Int) : Command

    /** Traffic the native side must refuse while a sensitive field is focused (FR-KB-14, SPEC §9). */
    val forbiddenInSecureField: Boolean
        get() = this is AgentRun || this is FormFill || (this is AutonomySet && enabled) ||
            (this is VoiceStart && refine)
}

sealed interface Request {
    data class Hello(val shellVersion: String) : Request
    data class ContextSnapshot(val scope: String) : Request
    data object ClipboardList : Request
    data object AgentsList : Request
    data object SystemStatusRequest : Request

    val forbiddenInSecureField: Boolean get() = this is ContextSnapshot
}

/** Max text the shell may push in one command (a full-field replace of a long email). */
const val MAX_TEXT_CHARS = 20_000

/** Sane bounds for `ui.resize` (dp). The tallest shell layout (XL keys + strip + bar) is ≈ 360 dp. */
const val MIN_KEYBOARD_DP = 120
const val MAX_KEYBOARD_DP = 600

/** Max code points deletable per command; the shell deletes at most a word or a selection at a time. */
const val MAX_DELETE = 10_000

private val FEEDBACK_KINDS = setOf("tap", "delete", "space", "return", "mod")
private val REPLACE_SCOPES = setOf("selection", "field", "word")
private val SNAPSHOT_SCOPES = setOf("field", "screen", "visual")
private val SETTINGS_KEY = Regex("^[a-zA-Z][a-zA-Z0-9_.]{0,63}$")

object MessageDecoder {
    private fun bad(field: String): Nothing = throw BridgeFailure(ErrorCode.BAD_PAYLOAD, "invalid '$field'")

    private fun JsonObj.reqStr(k: String, max: Int = MAX_TEXT_CHARS): String =
        this[k].str()?.takeIf { it.length <= max } ?: bad(k)

    private fun JsonObj.reqInt(k: String, range: IntRange): Int = this[k].int()?.takeIf { it in range } ?: bad(k)

    private fun JsonObj.reqBool(k: String): Boolean = this[k].bool() ?: bad(k)

    private fun payloadObj(p: JsonValue): JsonObj = p.objOrNull() ?: bad("payload")

    fun command(type: String, payload: JsonValue): Command {
        val p = payloadObj(payload)
        return when (type) {
            "text.commit" -> Command.Commit(p.reqStr("text"))
            "text.delete" -> Command.Delete(p.reqInt("before", 0..MAX_DELETE), p.reqInt("after", 0..MAX_DELETE))
            "text.replace" -> Command.Replace(p.reqStr("scope", 16).takeIf { it in REPLACE_SCOPES } ?: bad("scope"), p.reqStr("text"))
            "editor.action" -> Command.Action(
                wireOf<ImeActionKind>(p["action"].str())?.takeIf { it != ImeActionKind.NONE } ?: bad("action"),
            )
            "editor.cursor" -> Command.Cursor(p.reqInt("delta", -MAX_DELETE..MAX_DELETE))
            "feedback.key" -> Command.Feedback(p.reqStr("kind", 16).takeIf { it in FEEDBACK_KINDS } ?: bad("kind"))
            "agent.run" -> Command.AgentRun(
                AgentRunRequest(
                    runId = p.reqStr("runId", 64),
                    agentId = p.reqStr("agentId", 64),
                    skill = p.reqStr("skill", 32).takeIf { it in AgentRunRequest.SKILLS } ?: bad("skill"),
                    target = p.reqStr("target", 32).takeIf { it in AgentRunRequest.TARGETS } ?: bad("target"),
                    params = p["params"]?.let { it.objOrNull() ?: bad("params") },
                ),
            )
            "agent.cancel" -> Command.AgentCancel(p.reqStr("runId", 64))
            "autonomy.set" -> Command.AutonomySet(
                p.reqBool("enabled"),
                wireOf<AutonomyLevel>(p["level"].str()) ?: bad("level"),
                p.reqStr("agentId", 64),
            )
            "autonomy.takeover" -> Command.AutonomyTakeover
            "voice.start" -> Command.VoiceStart(p.reqBool("refine"))
            "voice.stop" -> Command.VoiceStop
            "voice.cancel" -> Command.VoiceCancel
            "tts.speak" -> Command.TtsSpeak(p.reqStr("text"))
            "tts.stop" -> Command.TtsStop
            "clipboard.paste" -> Command.ClipboardPaste(p.reqStr("id", 64))
            "clipboard.pin" -> Command.ClipboardPin(p.reqStr("id", 64), p.reqBool("pinned"))
            "clipboard.delete" -> Command.ClipboardDelete(p.reqStr("id", 64))
            "settings.set" -> Command.SettingsSet(
                p.reqStr("key", 64).takeIf { SETTINGS_KEY.matches(it) } ?: bad("key"),
                p["value"] ?: bad("value"),
            )
            "app.open" -> Command.AppOpen(p.reqStr("route", 256).takeIf { it.startsWith("/") && !it.startsWith("//") } ?: bad("route"))
            "form.fill" -> Command.FormFill(
                (p["values"].objOrNull() ?: bad("values")).fields.mapValues { (k, v) -> v.str()?.takeIf { it.length <= MAX_TEXT_CHARS } ?: bad("values.$k") },
            )
            "agents.save" -> {
                val (agent, err) = AgentDefinition.fromJson(p["agent"])
                Command.AgentsSave(agent ?: bad("agent.$err"))
            }
            "agents.delete" -> Command.AgentsDelete(p.reqStr("id", 64))
            "system.open" -> Command.SystemOpen(wireOf<SystemSettingsTarget>(p["target"].str()) ?: bad("target"))
            "ui.resize" -> Command.Resize(p.reqInt("height", MIN_KEYBOARD_DP..MAX_KEYBOARD_DP))
            else -> throw BridgeFailure(ErrorCode.UNKNOWN_TYPE, "unknown command '$type'")
        }
    }

    fun request(type: String, payload: JsonValue): Request {
        val p = payloadObj(payload)
        return when (type) {
            "hello" -> Request.Hello(p.reqStr("shellVersion", 32))
            "context.snapshot" -> Request.ContextSnapshot(p.reqStr("scope", 16).takeIf { it in SNAPSHOT_SCOPES } ?: bad("scope"))
            "clipboard.list" -> Request.ClipboardList
            "agents.list" -> Request.AgentsList
            "system.status" -> Request.SystemStatusRequest
            else -> throw BridgeFailure(ErrorCode.UNKNOWN_TYPE, "unknown request '$type'")
        }
    }
}
