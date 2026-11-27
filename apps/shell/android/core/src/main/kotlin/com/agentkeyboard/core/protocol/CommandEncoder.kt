package com.agentkeyboard.core.protocol

import com.agentkeyboard.core.json.JsonValue
import com.agentkeyboard.core.json.obj

/**
 * Inverse of [MessageDecoder.command]: re-encodes a validated command, e.g. when the IME forwards it to
 * the `:agent` process. `ContractFixturesTest` proves `encode(decode(x)) == x` for every fixture.
 */
object CommandEncoder {
    fun typeOf(c: Command): String = when (c) {
        is Command.Commit -> "text.commit"
        is Command.Delete -> "text.delete"
        is Command.Replace -> "text.replace"
        is Command.Action -> "editor.action"
        is Command.Cursor -> "editor.cursor"
        is Command.Feedback -> "feedback.key"
        is Command.AgentRun -> "agent.run"
        is Command.AgentCancel -> "agent.cancel"
        is Command.AutonomySet -> "autonomy.set"
        Command.AutonomyTakeover -> "autonomy.takeover"
        is Command.VoiceStart -> "voice.start"
        Command.VoiceStop -> "voice.stop"
        Command.VoiceCancel -> "voice.cancel"
        is Command.TtsSpeak -> "tts.speak"
        Command.TtsStop -> "tts.stop"
        is Command.ClipboardPaste -> "clipboard.paste"
        is Command.ClipboardPin -> "clipboard.pin"
        is Command.ClipboardDelete -> "clipboard.delete"
        is Command.SettingsSet -> "settings.set"
        is Command.AppOpen -> "app.open"
        is Command.FormFill -> "form.fill"
        is Command.AgentsSave -> "agents.save"
        is Command.AgentsDelete -> "agents.delete"
        is Command.SystemOpen -> "system.open"
        is Command.Resize -> "ui.resize"
        is Command.ScreenCapture -> "screen.capture"
        is Command.ModelsDownload -> "models.download"
        is Command.ModelsCancel -> "models.cancel"
        is Command.ModelsDelete -> "models.delete"
        Command.ModelsImport -> "models.import"
        is Command.ModelsUse -> "models.use"
        is Command.ModelsScan -> "models.scan"
        is Command.ModelsAdopt -> "models.adopt"
    }

    fun encode(c: Command): JsonValue = when (c) {
        is Command.Commit -> obj("text" to c.text)
        is Command.Delete -> obj("before" to c.before, "after" to c.after)
        is Command.Replace -> obj("scope" to c.scope, "text" to c.text)
        is Command.Action -> obj("action" to c.action.wire)
        is Command.Cursor -> obj("delta" to c.delta)
        is Command.Feedback -> obj("kind" to c.kind)
        is Command.AgentRun -> c.request.toJson()
        is Command.AgentCancel -> obj("runId" to c.runId)
        is Command.AutonomySet -> obj("enabled" to c.enabled, "level" to c.level.wire, "agentId" to c.agentId)
        Command.AutonomyTakeover, Command.VoiceStop, Command.VoiceCancel, Command.TtsStop -> obj()
        is Command.VoiceStart -> obj("refine" to c.refine)
        is Command.TtsSpeak -> obj("text" to c.text)
        is Command.ClipboardPaste -> obj("id" to c.id)
        is Command.ClipboardPin -> obj("id" to c.id, "pinned" to c.pinned)
        is Command.ClipboardDelete -> obj("id" to c.id)
        is Command.SettingsSet -> obj("key" to c.key, "value" to c.value)
        is Command.AppOpen -> obj("route" to c.route)
        is Command.FormFill -> obj("values" to c.values)
        is Command.AgentsSave -> obj("agent" to c.agent)
        is Command.AgentsDelete -> obj("id" to c.id)
        is Command.SystemOpen -> obj("target" to c.target.wire)
        is Command.Resize -> obj("height" to c.heightDp)
        is Command.ScreenCapture -> obj("enabled" to c.enabled)
        is Command.ModelsDownload -> obj("ids" to c.ids)
        is Command.ModelsCancel -> obj("id" to c.id)
        is Command.ModelsDelete -> obj("id" to c.id)
        Command.ModelsImport -> obj()
        is Command.ModelsUse -> obj("id" to c.id)
        is Command.ModelsScan -> obj("pick" to c.pick)
        is Command.ModelsAdopt -> obj("id" to c.id, "trust" to if (c.trust) true else null)
    }
}
