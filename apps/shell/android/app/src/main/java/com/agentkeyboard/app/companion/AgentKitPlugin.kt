package com.agentkeyboard.app.companion

import android.os.Handler
import android.os.Looper
import com.agentkeyboard.app.Ak
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.app.agent.AgentClient
import com.agentkeyboard.app.system.SystemSettings
import com.agentkeyboard.core.json.obj
import com.agentkeyboard.core.protocol.BridgeFailure
import com.agentkeyboard.core.protocol.Capabilities
import com.agentkeyboard.core.protocol.Command
import com.agentkeyboard.core.protocol.Decoded
import com.agentkeyboard.core.protocol.Envelope
import com.agentkeyboard.core.protocol.EnvelopeCodec
import com.agentkeyboard.core.protocol.ErrorCode
import com.agentkeyboard.core.protocol.IdGenerator
import com.agentkeyboard.core.protocol.MessageDecoder
import com.agentkeyboard.core.protocol.PROTOCOL_VERSION
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Companion-app transport (ADR-0003; `apps/shell/src/bridge/capacitor-bridge.ts`). Envelopes pass
 * through to `:agent` unchanged, and `:agent` replies with `re` = the shell's own id. The plugin
 * answers only what has no meaning in `:agent`:
 * - `hello`: the companion's capabilities (no editor, no autonomy);
 * - `system.open` / `app.open`: launcher actions that need an Activity context;
 * - editor/UI commands: dropped (there is no text field to edit here).
 *
 * `:agent` broadcasts to every client, so replies are filtered to the requests this plugin sent.
 */
@CapacitorPlugin(name = "AgentKit")
class AgentKitPlugin : Plugin(), AgentClient.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val ids = IdGenerator("c")
    private val pending = HashSet<String>() // main thread only
    private var client: AgentClient? = null

    override fun load() {
        main.post { client = AgentClient(context.applicationContext, this) }
    }

    /** Called on Capacitor's plugin thread; everything else happens on main. */
    @PluginMethod
    fun post(call: PluginCall) {
        val raw = call.getString("message")
        if (raw == null) {
            call.reject("message is required")
            return
        }
        main.post { handle(raw) }
        call.resolve()
    }

    private fun handle(raw: String) {
        val env = when (val d = EnvelopeCodec.decode(raw)) {
            is Decoded.Ok -> d.envelope
            is Decoded.VersionMismatch -> return d.id?.let { fail(it, ErrorCode.VERSION, "protocol v${d.version}") } ?: Unit
            is Decoded.Malformed -> return AkLog.d("companion: malformed envelope")
        }
        when (env.type) {
            "hello" -> reply(env.id, "hello.result", capabilities().toJson())
            "system.open", "app.open" -> try {
                when (val c = MessageDecoder.command(env.type, env.payload)) {
                    is Command.SystemOpen -> SystemSettings.open(activity ?: context, c.target)
                    is Command.AppOpen -> SystemSettings.openCompanion(context, c.route)
                    else -> Unit
                }
            } catch (f: BridgeFailure) {
                fail(env.id, f.code, f.message ?: f.code.wire)
            }
            in EDITOR_ONLY -> Unit
            else -> {
                pending += env.id
                if (pending.size > MAX_PENDING) pending.remove(pending.first())
                client?.post(raw) ?: fail(env.id, ErrorCode.UNAVAILABLE, "Assistant service is starting")
            }
        }
    }

    override fun onAgentEnvelope(raw: String) {
        val env = (EnvelopeCodec.decode(raw) as? Decoded.Ok)?.envelope ?: return
        val re = env.re
        if (re != null && !pending.remove(re)) return // a reply to the IME, not to us
        toJs(raw)
    }

    override fun onAgentInbound(packageName: String, conversationKey: String, messageJson: String, remoteInputAvailable: Boolean) = Unit

    override fun onAgentUnavailable(queued: List<String>) {
        val failed = pending.toList()
        pending.clear()
        failed.forEach { fail(it, ErrorCode.UNAVAILABLE, "Assistant service restarting") }
    }

    override fun handleOnDestroy() {
        main.post { client?.close(); client = null }
    }

    private fun capabilities() = Capabilities(
        platform = "android", tier = Ak.tier, nativeSuggestions = false, voice = false, tts = true,
        screenContext = false, visualContext = false, autonomy = false,
    )

    private fun reply(re: String, type: String, payload: com.agentkeyboard.core.json.JsonValue) =
        toJs(Envelope(PROTOCOL_VERSION, ids.next(), type, System.currentTimeMillis(), re, payload).encode())

    private fun fail(re: String, code: ErrorCode, message: String) =
        reply(re, "error", obj("code" to code.wire, "message" to message))

    private fun toJs(raw: String) = notifyListeners("message", JSObject().put("message", raw))

    private companion object {
        const val MAX_PENDING = 256
        val EDITOR_ONLY = setOf(
            "text.commit", "text.delete", "text.replace", "editor.action", "editor.cursor", "feedback.key",
            "autonomy.set", "autonomy.takeover", "clipboard.paste", "form.fill", "ui.resize",
            "voice.start", "voice.stop", "voice.cancel", "context.snapshot",
        )
    }
}
