package com.agentkeyboard.app.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.agentkeyboard.app.Ak
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.app.BuildConfig
import com.agentkeyboard.app.a11y.ScreenReaderService
import com.agentkeyboard.app.agent.AgentClient
import com.agentkeyboard.app.capture.ScreenCapture
import com.agentkeyboard.core.agent.AgentRuntime
import com.agentkeyboard.core.context.ScrollPolicy
import com.agentkeyboard.core.json.toJson
import com.agentkeyboard.core.protocol.ChatContext
import com.agentkeyboard.core.protocol.ScanInfo
import com.agentkeyboard.core.protocol.ScreenImage
import com.agentkeyboard.app.system.SystemSettings
import com.agentkeyboard.core.autonomy.AutonomyEvent
import com.agentkeyboard.core.autonomy.AutonomyMachine
import com.agentkeyboard.core.autonomy.Effect
import com.agentkeyboard.core.autonomy.Environment
import com.agentkeyboard.core.autonomy.SendPlanner
import com.agentkeyboard.core.autonomy.SendStrategy
import com.agentkeyboard.core.context.AppCategories
import com.agentkeyboard.core.editor.EditOp
import com.agentkeyboard.core.editor.FieldClassification
import com.agentkeyboard.core.editor.FormFill
import com.agentkeyboard.core.json.Json
import com.agentkeyboard.core.json.JsonObj
import com.agentkeyboard.core.json.JsonValue
import com.agentkeyboard.core.json.arrOrNull
import com.agentkeyboard.core.json.bool
import com.agentkeyboard.core.json.obj
import com.agentkeyboard.core.json.objOrNull
import com.agentkeyboard.core.json.str
import com.agentkeyboard.core.privacy.PrivacyFilter
import com.agentkeyboard.core.protocol.AgentDefinition
import com.agentkeyboard.core.protocol.BridgeHost
import com.agentkeyboard.core.protocol.BridgeRouter
import com.agentkeyboard.core.protocol.Capabilities
import com.agentkeyboard.core.protocol.ChatMessage
import com.agentkeyboard.core.protocol.Command
import com.agentkeyboard.core.protocol.CommandEncoder
import com.agentkeyboard.core.protocol.Decoded
import com.agentkeyboard.core.protocol.EditorState
import com.agentkeyboard.core.protocol.Envelope
import com.agentkeyboard.core.protocol.EnvelopeCodec
import com.agentkeyboard.core.protocol.ErrorCode
import com.agentkeyboard.core.protocol.FieldContext
import com.agentkeyboard.core.protocol.IdGenerator
import com.agentkeyboard.core.protocol.ImeActionKind
import com.agentkeyboard.core.protocol.PROTOCOL_VERSION
import com.agentkeyboard.core.protocol.Reply
import com.agentkeyboard.core.protocol.Request
import com.agentkeyboard.core.protocol.ScreenContext
import com.agentkeyboard.core.protocol.SensitivityFlags
import com.agentkeyboard.core.protocol.TokenCoalescer
import com.agentkeyboard.core.json.long

/**
 * The IME's brain, deliberately separate from the Android [AgentImeService] lifecycle so it reads as
 * protocol logic: it is the [BridgeHost] for the shell, the client of `:agent`, and the interpreter of
 * autonomy [Effect]s. Main thread only.
 *
 * Ownership (ADR-0003): editor operations, haptics, autonomy and screen context from the a11y reader
 * are handled here, in-process, for latency. Data and AI traffic (agents, clipboard history, runs,
 * TTS, settings persistence, notification context) is forwarded to `:agent` as v1 envelopes.
 */
class ImeController(private val context: Context, private val ime: ImeHost) : BridgeHost, AgentClient.Listener {

    /** What the controller needs from the Android service. Implemented by [AgentImeService]. */
    interface ImeHost {
        fun classification(): FieldClassification?
        fun editorState(): EditorState
        fun executor(): InputExecutor
        /** Key feedback per the user's separate haptics / key-sound settings (FR-KB-09). */
        fun keyFeedback(kind: String, haptic: Boolean, sound: Boolean)
        /** The keyboard theme setting changed: `light`, `dark` or `system` (native fallback follows it). */
        fun onKeyboardThemeChanged(theme: String)
        fun isSystemDark(): Boolean
        fun switchToNextKeyboard()
        fun setKeyboardHeightDp(dp: Int)
        fun onShellReady()
        fun onShellIncompatible()
        fun postToShell(raw: String)
        /** `SystemClock.uptimeMillis()` of the last finger-down on our own keyboard (0 = never). */
        fun lastUserTouchAt(): Long
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("ak_ime", Context.MODE_PRIVATE)
    val router = BridgeRouter(this, sink = ime::postToShell)
    val agent = AgentClient(context, this, AgentRuntime.ORIGIN_IME)
    private val internalIds = IdGenerator("i")
    private val tokens = TokenCoalescer(32, schedule = { d, b -> main.postDelayed(b, d) }, emit = { id, d ->
        router.emit("agent.token", obj("runId" to id, "delta" to d))
    })

    /** Requests forwarded to `:agent`, keyed by our internal id. Failed fast if the agent drops. */
    private val forwarded = HashMap<String, Pending>()

    /** [onError] replaces the default "fail the shell request" behaviour, e.g. to degrade a snapshot. */
    private class Pending(val reply: Reply, val onOk: (JsonValue) -> Unit, val onError: ((ErrorCode, String) -> Unit)? = null) {
        fun fail(code: ErrorCode, message: String) = onError?.invoke(code, message) ?: reply.fail(code, message)
    }

    private var agents: List<AgentDefinition> = emptyList()
    private val clipboardCache = HashMap<String, String>()
    private val conversationByPackage = HashMap<String, String>()
    private var awaitingDraftRun: String? = null
    /** Conversation key → its notification offers a free-form RemoteInput reply (send strategy 3). */
    private val remoteInputByConversation = HashMap<String, Boolean>()
    private var pendingRemoteSend: String? = null

    /** The last scroll-and-read, reused as run context for the same app for [SCAN_REUSE_MS]. */
    private class LastScan(val packageName: String, val scan: ScreenReaderService.Scan, val at: Long)
    private var lastScan: LastScan? = null
    private var keyboardVisible = false

    private val autonomy = AutonomyMachine(newRunId = { "auto_${internalIds.next().substringAfter(':')}" })

    /** Approved `form.fill` values not yet written, filled as focus reaches their fields (SPEC §11.7). */
    private class PendingFill(val packageName: String, val values: LinkedHashMap<String, String>, val expiresAt: Long)
    private var pendingFill: PendingFill? = null

    // Mirrors of the shell's keyboard settings (`settings.set keyboard.*`), read on every keystroke.
    private var haptics = setting(KEY_HAPTICS).bool() ?: true
    private var keySounds = setting(KEY_SOUNDS).bool() ?: false
    val keyboardTheme: String get() = setting(KEY_THEME).str() ?: "light"

    init {
        if (prefs.getBoolean(KEY_CONSENT, false)) apply(autonomy.dispatch(AutonomyEvent.Consent(true)))
        // Before hello the router drops it (the hello handler replays the current state).
        ScreenCapture.listener = { s -> router.emit("screen.state", screenState(s)) }
    }

    // ───────────── BridgeHost (shell → native) ─────────────

    override fun isSecureField(): Boolean = ime.classification()?.context?.sensitive ?: false

    override fun onProtocolMismatch(version: Long?) {
        AkLog.w("shell protocol v$version, native v$PROTOCOL_VERSION → fallback keyboard")
        ime.onShellIncompatible()
    }

    override fun onRejected(type: String?, code: ErrorCode) {
        AkLog.d("rejected ${type ?: "?"}: ${code.wire}")
    }

    override fun onCommand(command: Command) {
        EditOp.of(command)?.let { op ->
            ime.executor().submit(op); return
        }
        when (command) {
            is Command.Feedback -> keyFeedback(command.kind)
            is Command.Resize -> ime.setKeyboardHeightDp(command.heightDp)
            // Secure fields never get here for enabled=true (router gate); stop is always allowed.
            is Command.ScreenCapture -> if (command.enabled) ScreenCapture.requestStart(context) else ScreenCapture.stop(context)
            is Command.AgentRun -> forwardRun(command)
            is Command.AutonomySet -> onAutonomySet(command)
            Command.AutonomyTakeover -> apply(autonomy.dispatch(AutonomyEvent.UserTouched))
            // No STT engine yet (Capabilities.voice=false, SPEC §13): end at once and say why.
            is Command.VoiceStart -> router.emit("voice.end", obj("detail" to VOICE_UNAVAILABLE))
            Command.VoiceStop, Command.VoiceCancel -> router.emit("voice.end", obj())
            is Command.ClipboardPaste -> clipboardCache[command.id]?.let { ime.executor().submit(EditOp.Commit(it)) }
            is Command.AppOpen -> SystemSettings.openCompanion(context, command.route)
            is Command.SystemOpen -> SystemSettings.open(context, command.target)
            is Command.FormFill -> fillForm(command.values)
            is Command.SettingsSet -> {
                onSetting(command.key, command.value)
                // Keyboard settings are IME-local (their source of truth is the shell's storage); sending
                // them to :agent would bind the second process on every keyboard start for nothing.
                if (!command.key.startsWith("keyboard.")) forward(command)
            }
            is Command.AgentCancel -> { tokens.discard(command.runId); forward(command) }
            // Data/AI traffic owned by :agent.
            is Command.TtsSpeak, Command.TtsStop, is Command.ClipboardPin,
            is Command.ClipboardDelete, is Command.AgentsSave, is Command.AgentsDelete -> forward(command)
            // Model downloads are managed from the companion's Models screen only: a keyboard must never
            // start multi-GB transfers (and :agent only starts them while the companion is visible).
            is Command.ModelsDownload, is Command.ModelsCancel, is Command.ModelsDelete, Command.ModelsImport ->
                AkLog.d("models.* ignored from the keyboard")
            else -> Unit
        }
    }

    override fun onRequest(request: Request, reply: Reply) {
        when (request) {
            is Request.Hello -> {
                reply.ok(capabilities().toJson())
                ime.onShellReady()
                // Replay the current binding so a (re)loaded shell never misses the field it opened on.
                ime.classification()?.let { emitEditorStart(it) }
                router.emit("autonomy.phase", obj("phase" to autonomy.state.phase.wire, "detail" to autonomy.state.detail, "consent" to autonomy.state.consent))
                router.emit("screen.state", screenState(ScreenCapture.state))
                // Inside an IME the WebView's prefers-color-scheme does not reliably follow night mode.
                router.emit("theme.update", obj("mode" to "system", "systemDark" to ime.isSystemDark()))
            }
            // `titleHint` is native-internal: a shell-supplied value is ignored, never forwarded.
            is Request.ContextSnapshot -> snapshot(request.scope, reply)
            is Request.ContextScan -> scan(request, reply)
            Request.AgentsList -> forwardRequest("agents.list", obj(), reply) { res ->
                agents = res.arrOrNull()?.items?.mapNotNull { AgentDefinition.fromJson(it).first } ?: agents
            }
            Request.ClipboardList -> forwardRequest("clipboard.list", obj(), reply) { res ->
                clipboardCache.clear()
                res.arrOrNull()?.items?.forEach { i -> val o = i.objOrNull(); o?.get("id").str()?.let { id -> clipboardCache[id] = o?.get("text").str().orEmpty() } }
            }
            Request.SystemStatusRequest -> forwardRequest("system.status", obj(), reply) {}
            // Read-only from the keyboard (e.g. "model downloading 40%"); never resumes transfers.
            Request.ModelsStatusRequest -> forwardRequest("models.status", obj(), reply) {}
        }
    }

    // ───────────── :agent → IME ─────────────

    override fun onAgentEnvelope(raw: String) {
        val env = (EnvelopeCodec.decode(raw) as? Decoded.Ok)?.envelope ?: return
        val re = env.re
        if (re != null) {
            val pending = forwarded.remove(re) ?: return
            if (env.type == "error") {
                val p = env.payload.objOrNull()
                val code = ErrorCode.entries.firstOrNull { it.wire == p?.get("code").str() } ?: ErrorCode.INTERNAL
                pending.fail(code, p?.get("message").str() ?: "error")
            } else {
                pending.onOk(env.payload)
                pending.reply.ok(env.payload)
            }
            return
        }
        when (env.type) {
            "agent.token" -> {
                val p = env.payload.objOrNull() ?: return
                tokens.append(p["runId"].str() ?: return, p["delta"].str() ?: return)
            }
            "agent.done", "agent.error" -> {
                tokens.flushNow()
                if (!onAutonomyRunFinished(env)) router.emit(env.type, env.payload)
            }
            "agents.changed" -> {
                router.emit(env.type, env.payload)
                refreshAgents()
            }
            else -> if (router.shellReady) runCatching { router.emit(env.type, env.payload) }
        }
    }

    override fun onAgentInbound(packageName: String, conversationKey: String, messageJson: String, remoteInputAvailable: Boolean) {
        val focused = ime.classification()?.context ?: return
        if (focused.packageName != packageName) return // background replies are :agent's job (FR-AU-09)
        val m = (runCatching { Json.parse(messageJson) }.getOrNull() as? JsonObj) ?: return
        val message = ChatMessage(m["id"].str() ?: return, m["from"].str() == "me", m["author"].str().orEmpty(), m["text"].str().orEmpty(), m["at"].long() ?: System.currentTimeMillis())
        remoteInputByConversation[conversationKey] = remoteInputAvailable
        if (remoteInputByConversation.size > MAX_TRACKED_CONVERSATIONS) remoteInputByConversation.remove(remoteInputByConversation.keys.first())
        if (conversationByPackage[packageName] != conversationKey) {
            conversationByPackage[packageName] = conversationKey
            apply(autonomy.dispatch(AutonomyEvent.EnvironmentChanged(environment())))
        }
        val minute = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
        apply(autonomy.dispatch(AutonomyEvent.Inbound(message, minute)))
    }

    override fun onAgentRemoteReplyResult(requestId: String, ok: Boolean) {
        if (requestId != pendingRemoteSend) return
        pendingRemoteSend = null
        if (ok) { ime.executor().submit(EditOp.ReplaceField("")); ime.executor().flush() } // sent from the notification: clear our copy
        apply(autonomy.dispatch(AutonomyEvent.SendResult(ok)))
    }

    override fun onAgentUnavailable(queued: List<String>) {
        // Fail fast rather than letting the shell wait for its 5 s timeout.
        val pending = forwarded.values.toList()
        forwarded.clear()
        pending.forEach { it.fail(ErrorCode.UNAVAILABLE, "Assistant service restarting") }
    }

    // ───────────── IME lifecycle hooks (called by the service) ─────────────

    fun onFieldStarted(c: FieldClassification) {
        // A restart of the same field (restarting=true) has no finish callback first: anything still
        // queued for this frame was typed into this field and must land, not be dropped.
        ime.executor().flush()
        keyboardVisible = true
        if (lastScan?.packageName != c.context.packageName) lastScan = null
        updateCaptureFacts()
        if (router.shellReady) emitEditorStart(c)
        agent.focusChanged(Json.write(c.context.toJson()))
        apply(autonomy.dispatch(AutonomyEvent.EnvironmentChanged(environment())))
        fillFocused(c.context)
    }

    /** Shell `feedback.key` and the native fallback keyboard share the user's settings. */
    fun keyFeedback(kind: String) {
        if (haptics || keySounds) ime.keyFeedback(kind, haptics, keySounds)
    }

    fun onFieldFinished() {
        ime.executor().flush()
        keyboardVisible = false
        updateCaptureFacts()
        if (router.shellReady) router.emit("editor.finish", obj())
        apply(autonomy.dispatch(AutonomyEvent.EnvironmentChanged(environment())))
    }

    fun onEditorState(state: EditorState) {
        if (router.shellReady) router.emit("editor.state", state.toJson())
        if (autonomy.state.phase == com.agentkeyboard.core.protocol.AutonomyPhase.AWAITING_USER &&
            state.before.isEmpty() && state.after.isEmpty()
        ) apply(autonomy.dispatch(AutonomyEvent.DraftResolved)) // the user sent (or cleared) the draft
    }

    fun onClipboardText(text: String, sensitiveSource: Boolean) {
        if (isSecureField() || ime.classification()?.noLearning == true) return // FR-KB-14: no clipboard history
        agent.recordClipboard(text, sensitiveSource || PrivacyFilter.containsSecret(text))
    }

    fun onThemeChanged(systemDark: Boolean) {
        if (router.shellReady) router.emit("theme.update", obj("mode" to "system", "systemDark" to systemDark))
    }

    fun dispose() {
        main.removeCallbacksAndMessages(null)
        ScreenCapture.listener = null
        ScreenCapture.stop(context) // no keyboard, no live screen
        agent.close()
        forwarded.clear()
    }

    // ───────────── internals ─────────────

    private fun capabilities() = Capabilities(
        platform = "android-ime",
        tier = Ak.tier,
        nativeSuggestions = false, // the shell's predictor runs until the native one lands (§12.4)
        voice = false, // Part 3 (sherpa-onnx)
        tts = true, // system TextToSpeech in :agent
        screenContext = true, // rung 0 always; rungs 1–2 when the user grants access
        visualContext = true, // rung 3 frames from live capture (OCR/VLM read them in Part 3)
        autonomy = true,
        screenCapture = true, // MediaProjection, minSdk 29
        screenScan = true, // needs the screen-context service; refused with a reason when it is off
    )

    private fun screenState(s: ScreenCapture.State) = obj("status" to s.status.wire, "detail" to s.detail)

    /** Capture facts the keyboard owns; [ScreenCapture] decides (CaptureGate) and purges immediately. */
    private fun updateCaptureFacts() {
        val c = ime.classification()?.context
        ScreenCapture.updateFacts(
            keyboardVisible = keyboardVisible,
            autonomyArmed = autonomy.state.phase != com.agentkeyboard.core.protocol.AutonomyPhase.OFF,
            secureField = c?.sensitive == true,
            blockedApp = c != null && PrivacyFilter.isBlockedApp(c.packageName),
        )
    }

    /**
     * `:agent` holds the authoritative consent record (the companion's "Withdraw consent" writes it
     * there). Checked when Autopilot is switched on (not at keyboard start, which must not bind the
     * second process): if it says no while this process says yes, withdraw here too and tell the shell.
     */
    private fun withAgentConsent(then: () -> Unit) {
        if (!autonomy.state.consent) return then()
        forwardRequest("system.status", obj(), Reply.detached(), onError = { _, _ -> then() }) { res ->
            if (res.objOrNull()?.get("autonomyConsent").bool() == false) onSetting(KEY_CONSENT_SETTING, com.agentkeyboard.core.json.JsonBool(false))
            then()
        }
    }

    /**
     * `agent.run`: strip native-only params the shell must not forge, then attach what the keyboard
     * saw on screen: the chat title (rung-1 resolution, finding A) and the screen text, preferring a
     * fresh scroll-and-read of the same app. Skipped in secure fields and blocked apps.
     */
    private fun forwardRun(c: Command.AgentRun) {
        val r = c.request
        val base = LinkedHashMap(r.params?.fields.orEmpty())
        NATIVE_RUN_PARAMS.forEach(base::remove)
        val field = ime.classification()?.context
        fun send(title: String?, text: String?, source: String?) {
            title?.takeIf { it.isNotBlank() }?.let { base["conversationTitle"] = toJson(it) }
            text?.takeIf { it.isNotBlank() }?.let { base["screen"] = obj("text" to it, "source" to source) }
            forward(Command.AgentRun(r.copy(params = JsonObj(base))))
        }
        if (field == null || field.sensitive || PrivacyFilter.isBlockedApp(field.packageName)) return send(null, null, null)
        val pkg = field.packageName
        lastScan?.takeIf { it.packageName == pkg && System.currentTimeMillis() - it.at < SCAN_REUSE_MS }?.let { ls ->
            return send(ls.scan.snapshot.title, transcript(ls.scan.snapshot), "a11y_scan")
        }
        if (!ScreenReaderService.isRunning) return send(null, null, null)
        ScreenReaderService.snapshotAsync(pkg) { s ->
            val now = ime.classification()?.context
            if (s == null || now == null || now.sensitive || now.packageName != pkg) send(null, null, null)
            else send(s.title, transcript(s), "a11y")
        }
    }

    private fun transcript(s: ScreenReaderService.Snapshot): String =
        if (s.messages.isNotEmpty()) s.messages.joinToString("\n") { (if (it.fromMe) "Me: " else "${it.author.ifBlank { "Them" }}: ") + it.text } else s.text

    /**
     * `context.scan` (ADR-0010): scroll the app under the keyboard, read up to `pages` screens, scroll
     * back. Only a user tap on the keyboard may start it (Play accessibility policy): the request must
     * follow a touch on our own keyboard within [USER_TAP_WINDOW_MS], which the shell cannot fake.
     */
    private fun scan(req: Request.ContextScan, reply: Reply) {
        val c = ime.classification() ?: return reply.fail(ErrorCode.UNAVAILABLE, "No focused field")
        val pkg = c.context.packageName
        if (PrivacyFilter.isBlockedApp(pkg)) return reply.fail(ErrorCode.UNAVAILABLE, "Not available in this app")
        val userTap = android.os.SystemClock.uptimeMillis() - ime.lastUserTouchAt() <= USER_TAP_WINDOW_MS
        val initiator = if (userTap) ScrollPolicy.Initiator.USER else ScrollPolicy.Initiator.AGENT
        if (!ScrollPolicy.allowed(initiator, BuildConfig.A11Y_ACTIONS, autonomy.state.consent)) {
            return reply.fail(ErrorCode.UNAVAILABLE, "Reading more of the screen starts from a tap on the keyboard")
        }
        if (!ScreenReaderService.isRunning) return reply.fail(ErrorCode.UNAVAILABLE, "Turn on screen context in Settings to read more")
        val field = c.context
        updateCaptureFacts()
        ScreenReaderService.scanAsync(pkg, req.direction, req.pages, cancelled = {
            // Polled on the reader thread: plain reads of main-thread state; a stale answer costs one page.
            val now = ime.classification()?.context
            now == null || now.sensitive || now.packageName != pkg || !keyboardVisible
        }) { result ->
            val now = ime.classification() ?: return@scanAsync reply.fail(ErrorCode.UNAVAILABLE, "No focused field")
            if (now.context.sensitive) return@scanAsync reply.fail(ErrorCode.SECURE_FIELD, "Not available in secure fields")
            if (result == null) return@scanAsync reply.fail(ErrorCode.UNAVAILABLE, "Couldn’t read this screen")
            lastScan = LastScan(pkg, result, System.currentTimeMillis())
            val ctx = a11yContext(field, result.snapshot, rung = 2, scan = ScanInfo(result.pages, result.complete, result.restored))
            reply.ok(withFrame(ctx).toJson())
        }
    }

    private fun a11yContext(field: FieldContext, s: ScreenReaderService.Snapshot, rung: Int, scan: ScanInfo? = null): ScreenContext {
        val pkg = field.packageName
        return ScreenContext(
            appPackage = pkg, appLabel = field.appLabel, category = AppCategories.of(pkg),
            screenType = if (s.messages.isNotEmpty()) "chat" else AppCategories.screenTypeFor(AppCategories.of(pkg)),
            field = field,
            // Read off the screen itself, so it is the chat on screen by construction.
            chat = s.messages.takeIf { it.isNotEmpty() }?.let { ChatContext(s.title, s.participants, it, ChatContext.EXACT) },
            text = s.text.takeIf { s.messages.isEmpty() && it.isNotBlank() },
            sensitivity = SensitivityFlags(s.secureWindow, field.sensitive, false),
            scan = scan,
            rung = rung, capturedAt = System.currentTimeMillis(), ttlMs = SNAPSHOT_TTL_MS,
        )
    }

    /** Rung 3: attach the newest live frame (handle + Lens preview) when capture is live. */
    private fun withFrame(ctx: ScreenContext): ScreenContext {
        if (ScreenCapture.state.status != ScreenCapture.Status.LIVE) return ctx
        val f = ScreenCapture.ring.latest(FRAME_MAX_AGE_MS) ?: return ctx
        return ctx.copy(rung = 3, images = listOf(ScreenImage(f.handle, f.width, f.height, ScreenCapture.preview(f))))
    }

    private fun emitEditorStart(c: FieldClassification) {
        router.emit("editor.start", obj("field" to c.context, "state" to ime.editorState()))
    }

    private fun forward(command: Command) {
        val env = Envelope(PROTOCOL_VERSION, internalIds.next(), CommandEncoder.typeOf(command), System.currentTimeMillis(), null, CommandEncoder.encode(command))
        agent.post(env.encode())
    }

    private fun forwardRequest(
        type: String,
        payload: JsonValue,
        reply: Reply,
        onError: ((ErrorCode, String) -> Unit)? = null,
        onOk: (JsonValue) -> Unit,
    ) {
        val id = internalIds.next()
        forwarded[id] = Pending(reply, onOk, onError)
        agent.post(Envelope(PROTOCOL_VERSION, id, type, System.currentTimeMillis(), null, payload).encode())
        main.postDelayed({ forwarded.remove(id)?.fail(ErrorCode.UNAVAILABLE, "Assistant service did not answer") }, FORWARD_TIMEOUT_MS)
    }

    /** Internal request with no shell reply waiting on it (still bounded by the forward timeout). */
    private fun refreshAgents(then: () -> Unit = {}) =
        forwardRequest("agents.list", obj(), Reply.detached(), onError = { _, _ -> then() }) { res ->
            agents = res.arrOrNull()?.items?.mapNotNull { AgentDefinition.fromJson(it).first } ?: agents
            then()
        }

    /** Rung 2 (a11y) is assembled in-process; rung 1 (notifications) and rung 0 come from :agent. */
    private fun snapshot(scope: String, reply: Reply) {
        val c = ime.classification() ?: return reply.fail(ErrorCode.UNAVAILABLE, "No focused field")
        val pkg = c.context.packageName
        val blocked = PrivacyFilter.isBlockedApp(pkg)
        if (scope == "visual") {
            // Live frames only: never start capture from a request (the user turns it on, with the system dialog).
            if (blocked || ScreenCapture.state.status != ScreenCapture.Status.LIVE || ScreenCapture.ring.latest(FRAME_MAX_AGE_MS) == null) {
                return reply.fail(ErrorCode.UNAVAILABLE, "Live screen is off")
            }
            ScreenReaderService.snapshotAsync(pkg) { s ->
                val now = ime.classification() ?: return@snapshotAsync reply.fail(ErrorCode.UNAVAILABLE, "No focused field")
                if (now.context.sensitive) return@snapshotAsync reply.fail(ErrorCode.SECURE_FIELD, "Not available in secure fields")
                val base = if (s != null) a11yContext(c.context, s, rung = 2) else localSnapshotModel(now, false)
                val framed = withFrame(base)
                if (framed.rung != 3) reply.fail(ErrorCode.UNAVAILABLE, "Live screen is off") else reply.ok(framed.toJson())
            }
            return
        }
        if (scope == "screen" && !blocked) {
            ScreenReaderService.snapshotAsync(pkg) { s ->
                // Focus may have moved while reading: never answer for a field that is now secure.
                val now = ime.classification() ?: return@snapshotAsync reply.fail(ErrorCode.UNAVAILABLE, "No focused field")
                if (now.context.sensitive) return@snapshotAsync reply.fail(ErrorCode.SECURE_FIELD, "Not available in secure fields")
                if (s != null) return@snapshotAsync reply.ok(a11yContext(c.context, s, rung = 2).toJson())
                // Rung 1 from :agent's notification cache; anything else degrades to the local rung 0.
                forwardRequest("context.snapshot", obj("scope" to scope), reply, onError = { code, message ->
                    if (code == ErrorCode.SECURE_FIELD) reply.fail(code, message) else reply.ok(localSnapshot(now, PrivacyFilter.isBlockedApp(now.context.packageName)))
                }) {}
            }
            return
        }
        reply.ok(localSnapshot(c, blocked))
    }

    /** Rung 0: the field itself (text redacted), never anything from other apps. */
    private fun localSnapshot(c: FieldClassification, blocked: Boolean): JsonValue = localSnapshotModel(c, blocked).toJson()

    private fun localSnapshotModel(c: FieldClassification, blocked: Boolean): ScreenContext {
        val pkg = c.context.packageName
        return ScreenContext(
            appPackage = pkg, appLabel = c.context.appLabel, category = AppCategories.of(pkg),
            screenType = AppCategories.screenTypeFor(AppCategories.of(pkg)), field = c.context,
            text = if (blocked) null else ime.editorState().let { (it.before + it.selected + it.after).takeIf(String::isNotBlank)?.let(PrivacyFilter::redact) },
            sensitivity = SensitivityFlags(false, c.context.sensitive, blocked),
            rung = 0, capturedAt = System.currentTimeMillis(), ttlMs = SNAPSHOT_TTL_MS,
        )
    }

    private fun onSetting(key: String, value: JsonValue) {
        if (key == KEY_CONSENT_SETTING) {
            val granted = value.bool() == true
            if (granted == prefs.getBoolean(KEY_CONSENT, false) && granted == autonomy.state.consent) return // no change, no new timestamp
            prefs.edit().putBoolean(KEY_CONSENT, granted).putLong(KEY_CONSENT_AT, System.currentTimeMillis()).apply()
            apply(autonomy.dispatch(AutonomyEvent.Consent(granted)))
            // The shell mirrors the native record (FR-AU-02): a withdrawal must bring its consent sheet back.
            router.emit("autonomy.phase", obj("phase" to autonomy.state.phase.wire, "detail" to autonomy.state.detail, "consent" to granted))
        } else if (key.startsWith("autonomy.") || key.startsWith("keyboard.")) {
            val previous = setting(key)
            prefs.edit().putString("s:$key", Json.write(value)).apply()
            when (key) {
                KEY_HAPTICS -> haptics = value.bool() ?: true
                KEY_SOUNDS -> keySounds = value.bool() ?: false
                KEY_THEME -> if (previous != value) ime.onKeyboardThemeChanged(keyboardTheme)
            }
            if (key.startsWith("autonomy.")) apply(autonomy.dispatch(AutonomyEvent.EnvironmentChanged(environment())))
        }
    }

    private fun setting(key: String): JsonValue? = prefs.getString("s:$key", null)?.let { runCatching { Json.parse(it) }.getOrNull() }

    private fun onAutonomySet(c: Command.AutonomySet) {
        if (c.enabled) withAgentConsent { toggleAutonomy(c) } else toggleAutonomy(c)
    }

    private fun toggleAutonomy(c: Command.AutonomySet) {
        val known = agents.firstOrNull { it.id == c.agentId }
        if (known != null || !c.enabled) {
            apply(autonomy.dispatch(AutonomyEvent.Toggle(c.enabled, c.level, known)))
            return
        }
        // Unknown agent (fresh process): fetch definitions first; the ceiling must come from :agent, not JS.
        // If :agent can't answer, the agent stays unknown and the machine refuses to arm (fail closed).
        refreshAgents { apply(autonomy.dispatch(AutonomyEvent.Toggle(true, c.level, agents.firstOrNull { it.id == c.agentId }))) }
    }

    private fun environment(): Environment {
        val c = ime.classification()?.context
        val pkg = c?.packageName
        val allowedApps = setting("autonomy.allowedApps").arrOrNull()?.items?.mapNotNull { it.str() }?.toSet()
        val appAllowed = pkg != null && !PrivacyFilter.isBlockedApp(pkg) &&
            (allowedApps?.contains(pkg) ?: (AppCategories.of(pkg) == "chat"))
        val conversation = pkg?.let { conversationByPackage[it] }
        val denied = setting("autonomy.deniedConversations").arrOrNull()?.items?.mapNotNull { it.str() }?.toSet().orEmpty()
        val enterIsSend = pkg != null && setting("autonomy.enterIsSend.$pkg").bool() == true
        val remoteInput = conversation != null && remoteInputByConversation[conversation] == true
        val strategy = SendPlanner.choose(c, enterIsSend, remoteInputAvailable = remoteInput, a11yActionsAvailable = BuildConfig.A11Y_ACTIONS && ScreenReaderService.isRunning)
        return Environment(c, conversation, appAllowed, conversation == null || conversation !in denied, strategy)
    }

    /** Interprets autonomy effects. Every branch is a thin Android/IPC call; decisions live in core. */
    private fun apply(effects: List<Effect>) {
        for (e in effects) when (e) {
            is Effect.Phase -> {
                // Armed autonomy reacts to inbound messages from :agent, so the link must stay up.
                agent.sticky = e.phase != com.agentkeyboard.core.protocol.AutonomyPhase.OFF
                updateCaptureFacts()
                if (router.shellReady) router.emit("autonomy.phase", obj("phase" to e.phase.wire, "detail" to e.detail))
            }
            is Effect.RequestDraft -> {
                awaitingDraftRun = e.runId
                forward(Command.AgentRun(com.agentkeyboard.core.protocol.AgentRunRequest(e.runId, e.agentId, "reply", "conversation", obj("instruction" to "autonomy", "conversation" to e.conversation))))
            }
            is Effect.CancelRun -> forward(Command.AgentCancel(e.runId))
            is Effect.TypeDraft -> {
                ime.executor().submit(EditOp.ReplaceField(e.text))
                ime.executor().flush()
                main.post { apply(autonomy.dispatch(AutonomyEvent.Typed)) }
            }
            is Effect.Send -> if (e.strategy == SendStrategy.REMOTE_INPUT) sendRemote()
            else main.post { apply(autonomy.dispatch(AutonomyEvent.SendResult(send(e.strategy)))) }
            is Effect.Suggest -> if (router.shellReady) router.emit(
                "insight.update",
                obj("insight" to obj("id" to "autonomy", "text" to e.text, "run" to obj("skill" to "reply", "target" to "conversation"), "score" to 1.0)),
            )
            is Effect.ScheduleCooldown -> main.postDelayed({ apply(autonomy.dispatch(AutonomyEvent.CooldownElapsed)) }, e.ms)
            is Effect.NeedsYou -> AkLog.i("autonomy escalated")
            is Effect.Audit -> agent.appendAudit(
                Json.write(obj("action" to e.action, "agentId" to e.agentId, "package" to e.app, "conversation" to e.conversation, "sent" to e.sent, "reason" to e.reason, "model" to e.model, "at" to System.currentTimeMillis())),
            )
        }
    }

    private fun send(strategy: SendStrategy): Boolean = when (strategy) {
        SendStrategy.IME_ACTION_SEND -> { ime.executor().submit(EditOp.Action(ImeActionKind.SEND)); ime.executor().flush(); true }
        SendStrategy.ENTER_IS_SEND -> { ime.executor().flush(); ime.executor().sendEnterKey() }
        SendStrategy.A11Y_CLICK -> com.agentkeyboard.app.a11y.A11yActions.clickSend()
        SendStrategy.REMOTE_INPUT, SendStrategy.NONE -> false
    }

    /** Strategy 3: the field can't send, the conversation's notification can. `:agent` re-checks consent. */
    private fun sendRemote() {
        val pkg = ime.classification()?.context?.packageName
        val key = pkg?.let { conversationByPackage[it] }
        val text = autonomy.state.draft
        if (key == null || text.isNullOrBlank()) return apply(autonomy.dispatch(AutonomyEvent.SendResult(false)))
        val id = internalIds.next()
        pendingRemoteSend = id
        agent.remoteReply(id, key, text)
        main.postDelayed({ if (pendingRemoteSend == id) onAgentRemoteReplyResult(id, false) }, FORWARD_TIMEOUT_MS)
    }

    /** Autonomy drafts are consumed here and never shown as a shell result card. */
    private fun onAutonomyRunFinished(env: Envelope): Boolean {
        val p = env.payload.objOrNull() ?: return false
        val runId = p["runId"].str()
        if (runId == null || runId != awaitingDraftRun) return false
        awaitingDraftRun = null
        if (env.type == "agent.done") {
            val confidence = AgentRuntime.confidenceOf(p["data"]) // string or number; anything else → 0.0 → escalate
            apply(autonomy.dispatch(AutonomyEvent.DraftReady(runId, p["text"].str().orEmpty(), confidence, p["model"].str().orEmpty())))
        } else {
            apply(autonomy.dispatch(AutonomyEvent.DraftFailed(runId, p["message"].str() ?: "error")))
        }
        return true
    }

    /**
     * `form.fill` (SPEC §11.7, FR-AU-08): the user approved [values] in the review sheet. The IME can
     * only write to the focused field, so values are matched to fields deterministically
     * ([FormFill.keyFor]) and written as focus reaches each one: now for the focused field, then on
     * every field start in the same app within [FILL_WINDOW_MS]. Focus is advanced only where the
     * field itself declares `next`; a field nothing matches is left untouched, and nothing is ever
     * submitted.
     */
    private fun fillForm(values: Map<String, String>) {
        val c = ime.classification()?.context ?: return
        pendingFill = PendingFill(c.packageName, LinkedHashMap(values), System.currentTimeMillis() + FILL_WINDOW_MS)
        fillFocused(c)
    }

    private fun fillFocused(c: FieldContext) {
        val p = pendingFill ?: return
        if (p.packageName != c.packageName || System.currentTimeMillis() > p.expiresAt) {
            pendingFill = null; return
        }
        val key = FormFill.keyFor(c, p.values.keys) ?: return // sensitive or unmatched: leave it alone
        val text = p.values.remove(key) ?: return
        ime.executor().submit(EditOp.ReplaceField(text))
        if (p.values.isEmpty()) {
            pendingFill = null
        } else if (c.imeAction == ImeActionKind.NEXT) {
            ime.executor().submit(EditOp.Action(ImeActionKind.NEXT))
        }
    }

    private companion object {
        const val VOICE_UNAVAILABLE = "Voice typing isn’t available on this build yet."
        const val KEY_CONSENT = "autonomy.consent"
        const val KEY_CONSENT_AT = "autonomy.consent.at"
        const val KEY_CONSENT_SETTING = "autonomy.consent"
        const val FORWARD_TIMEOUT_MS = 4_000L
        const val SNAPSHOT_TTL_MS = 30_000L
        const val FILL_WINDOW_MS = 60_000L
        const val KEY_HAPTICS = "keyboard.haptics"
        const val KEY_SOUNDS = "keyboard.keySounds"
        const val KEY_THEME = "keyboard.theme"
        const val SCAN_REUSE_MS = 60_000L
        const val USER_TAP_WINDOW_MS = 3_000L
        const val FRAME_MAX_AGE_MS = 10_000L
        const val MAX_TRACKED_CONVERSATIONS = 64
        /** Run params only the IME may write (AgentRuntime.imeContext); shell values are dropped. */
        val NATIVE_RUN_PARAMS = listOf("conversationTitle", "screen")
    }
}
