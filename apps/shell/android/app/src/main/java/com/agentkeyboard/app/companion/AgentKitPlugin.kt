package com.agentkeyboard.app.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResult
import com.agentkeyboard.app.Ak
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.app.agent.AgentClient
import com.agentkeyboard.app.system.SystemSettings
import com.agentkeyboard.core.agent.AgentRuntime
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
import com.agentkeyboard.core.json.objOrNull
import com.agentkeyboard.core.json.str
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

/**
 * Companion-app transport (ADR-0003; `apps/shell/src/bridge/capacitor-bridge.ts`). Envelopes pass
 * through to `:agent` unchanged, and `:agent` replies with `re` = the shell's own id. The plugin
 * answers only what has no meaning in `:agent`:
 * - `hello`: the companion's capabilities (no editor, no autonomy);
 * - `system.open` / `app.open`: launcher actions that need an Activity context;
 * - `models.import`: the system file picker (needs an Activity); the picked file's descriptor goes to
 *   `:agent`, which verifies it against the signed catalog (ADR-0011);
 * - `models.scan {pick: 'folder' | 'file'}`: the folder picker, or a multi-select file picker (the
 *   only way into Download/ itself on Android 11+ without all-files access); grants are persisted and
 *   `:agent` scans them (ADR-0012);
 * - `system.open {target: 'microphone'}`: the runtime permission dialog (the keyboard can't show one);
 * - editor/UI commands: dropped (there is no text field to edit here).
 *
 * `:agent` addresses replies to this client's origin; they are still matched to the requests this
 * plugin sent, so a late reply to a request it already failed is dropped.
 */
@CapacitorPlugin(name = "AgentKit", permissions = [Permission(strings = [Manifest.permission.RECORD_AUDIO], alias = AgentKitPlugin.MIC)])
class AgentKitPlugin : Plugin(), AgentClient.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val ids = IdGenerator("c")
    /** Insertion-ordered so the cap evicts the oldest request, not an arbitrary one. Main thread only. */
    private val pending = LinkedHashSet<String>()
    private var client: AgentClient? = null

    override fun load() {
        main.post { client = AgentClient(context.applicationContext, this, AgentRuntime.ORIGIN_COMPANION) }
    }

    /** Called on Capacitor's plugin thread; everything else happens on main. */
    @PluginMethod
    fun post(call: PluginCall) {
        val raw = call.getString("message")
        if (raw == null) {
            call.reject("message is required")
            return
        }
        val env = (EnvelopeCodec.decode(raw) as? Decoded.Ok)?.envelope
        val p = env?.payload.objOrNull()
        when {
            env?.type == "models.import" -> {
                // Resolved by onModelPicked once the user has chosen (or dismissed) a file.
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                main.post { startActivityForResult(call, pick, "onModelPicked") }
                return
            }
            env?.type == "models.scan" && p?.get("pick").str() == "folder" -> {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                main.post { startActivityForResult(call, pick, "onFolderPicked") }
                return
            }
            env?.type == "models.scan" && p?.get("pick").str() == "file" -> {
                // Several at once: a speech model is a set of files picked together.
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                main.post { startActivityForResult(call, pick, "onFilesPicked") }
                return
            }
            env?.type == "system.open" && p?.get("target").str() == "microphone" &&
                getPermissionState(MIC) != PermissionState.GRANTED -> {
                main.post { requestPermissionForAlias(MIC, call, "onMicPermission") }
                return
            }
        }
        main.post { handle(raw) }
        call.resolve()
    }

    /** Persists read access to the folder (it survives restarts) and has `:agent` look in it. */
    @ActivityCallback
    private fun onFolderPicked(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                AkLog.w("companion: folder grant not persistable")
            }
            postToAgent("models.scan")
        }
        call.resolve()
    }

    /** Persists read access to each picked file until it is copied in (released by the scanner then). */
    @ActivityCallback
    private fun onFilesPicked(call: PluginCall, result: ActivityResult) {
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val uris = data.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } } ?: listOfNotNull(data.data)
            var kept = 0
            for (uri in uris.take(MAX_PICKED)) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    kept++
                } catch (e: SecurityException) {
                    AkLog.w("companion: file grant not persistable")
                }
            }
            if (kept > 0) postToAgent("models.scan")
        }
        call.resolve()
    }

    @PermissionCallback
    private fun onMicPermission(call: PluginCall) {
        // A denial is final after the second time: from then on, only the app's settings page can grant it.
        if (getPermissionState(MIC) != PermissionState.GRANTED && activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false) {
            SystemSettings.open(activity ?: context, com.agentkeyboard.core.protocol.SystemSettingsTarget.MICROPHONE)
        }
        postToAgent("models.scan") // refreshes `micGranted` on the Models screen
        call.resolve()
    }

    /** A command of the plugin's own (no reply expected). */
    private fun postToAgent(type: String) {
        client?.post(Envelope(PROTOCOL_VERSION, ids.next(), type, System.currentTimeMillis(), null, obj()).encode())
    }

    @ActivityCallback
    private fun onModelPicked(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.resolve()
            return
        }
        val resolver = context.contentResolver
        var name = "model"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        try {
            resolver.openFileDescriptor(uri, "r")?.use { fd ->
                if (client?.importModel(fd, name, size) != true) AkLog.w("companion: model import not delivered")
            }
        } catch (e: java.io.IOException) {
            AkLog.w("companion: couldn't open the picked file")
        } catch (e: SecurityException) {
            AkLog.w("companion: no access to the picked file")
        }
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
            // Requests always get an answer, never a silent 5 s timeout.
            in EDITOR_REQUESTS -> fail(env.id, ErrorCode.UNAVAILABLE, "There is no screen to read in the companion app")
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
        screenContext = false, visualContext = false, autonomy = false, screenCapture = false, screenScan = false,
    )

    private fun reply(re: String, type: String, payload: com.agentkeyboard.core.json.JsonValue) =
        toJs(Envelope(PROTOCOL_VERSION, ids.next(), type, System.currentTimeMillis(), re, payload).encode())

    private fun fail(re: String, code: ErrorCode, message: String) =
        reply(re, "error", obj("code" to code.wire, "message" to message))

    private fun toJs(raw: String) = notifyListeners("message", JSObject().put("message", raw))

    companion object {
        const val MIC = "microphone"
        /** A speech model is ≤ 8 files (ADR-0012); more is a mis-selection. */
        private const val MAX_PICKED = 8
        private const val MAX_PENDING = 256
        private val EDITOR_ONLY = setOf(
            "text.commit", "text.delete", "text.replace", "editor.action", "editor.cursor", "feedback.key",
            "autonomy.set", "autonomy.takeover", "clipboard.paste", "form.fill", "ui.resize",
            "voice.start", "voice.stop", "voice.cancel", "screen.capture",
        )
        private val EDITOR_REQUESTS = setOf("context.snapshot", "context.scan")
    }
}
