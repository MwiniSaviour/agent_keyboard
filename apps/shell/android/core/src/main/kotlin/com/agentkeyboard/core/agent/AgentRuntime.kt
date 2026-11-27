package com.agentkeyboard.core.agent

import com.agentkeyboard.core.context.AppCategories
import com.agentkeyboard.core.context.ConversationCache
import com.agentkeyboard.core.inference.InferenceScheduler
import com.agentkeyboard.core.inference.Priority
import com.agentkeyboard.core.models.ModelService
import com.agentkeyboard.core.skills.Skills
import com.agentkeyboard.core.json.Json
import com.agentkeyboard.core.json.JsonValue
import com.agentkeyboard.core.json.arr
import com.agentkeyboard.core.json.bool
import com.agentkeyboard.core.json.long
import com.agentkeyboard.core.json.num
import com.agentkeyboard.core.json.obj
import com.agentkeyboard.core.json.objOrNull
import com.agentkeyboard.core.json.str
import com.agentkeyboard.core.privacy.PrivacyFilter
import com.agentkeyboard.core.protocol.AgentDefinition
import com.agentkeyboard.core.protocol.AgentRunRequest
import com.agentkeyboard.core.protocol.BridgeFailure
import com.agentkeyboard.core.protocol.Catalog
import com.agentkeyboard.core.json.JsonBool
import com.agentkeyboard.core.protocol.ChatContext
import com.agentkeyboard.core.protocol.ChatMessage
import com.agentkeyboard.core.protocol.ClipboardItem
import com.agentkeyboard.core.protocol.Command
import com.agentkeyboard.core.protocol.Decoded
import com.agentkeyboard.core.protocol.Envelope
import com.agentkeyboard.core.protocol.EnvelopeCodec
import com.agentkeyboard.core.protocol.ErrorCode
import com.agentkeyboard.core.protocol.FieldContext
import com.agentkeyboard.core.protocol.GENERAL_AGENT_ID
import com.agentkeyboard.core.protocol.IdGenerator
import com.agentkeyboard.core.protocol.MAX_TEXT_CHARS
import com.agentkeyboard.core.protocol.MessageDecoder
import com.agentkeyboard.core.protocol.PROTOCOL_VERSION
import com.agentkeyboard.core.protocol.Request
import com.agentkeyboard.core.protocol.ScreenContext
import com.agentkeyboard.core.protocol.SensitivityFlags
import com.agentkeyboard.core.protocol.SystemStatus
import com.agentkeyboard.core.protocol.validateAgent
import java.util.concurrent.Executor

/**
 * The `:agent` process brain (SPEC §8.3, ADR-0003): answers the requests the IME forwards, owns
 * agents/clipboard/settings/audit persistence, the conversation cache (rung 1) and the single-flight
 * inference scheduler. Platform-free; `AgentService` is a thin Android adapter around it.
 *
 * Threading: every public method must be called on the [serial] executor's thread; engine work runs on
 * [engineExecutor] and hops back to [serial] before touching state. No locks are needed.
 */
class AgentRuntime(
    private val agents: AgentStore,
    private val clipboard: ClipboardStore,
    private val audit: AuditStore,
    private val settings: SettingsStore,
    private val speech: Speech,
    private val engine: InferenceEngine,
    private val output: AgentOutput,
    private val status: () -> SystemStatus,
    private val engineExecutor: Executor,
    private val serial: Executor,
    private val onBusyChanged: (Boolean) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    val conversations: ConversationCache = ConversationCache(clock = clock),
    private val replies: ReplyChannel? = null,
    /** Model packs (Part 3). Null in tests that don't exercise them. */
    private val models: ModelService? = null,
    /** Memory pressure hook for the host: unload the resident model (SPEC §12.2). */
    private val onTrim: () -> Unit = {},
) {
    private val ids = IdGenerator("a")
    private var focused: FieldContext? = null
    private val scheduler = InferenceScheduler<PendingRun>()
    private var active: ActiveRun? = null
    private var busy = false

    /** A scheduled run; restarted as a fresh [ActiveRun] if it is preempted and requeued. */
    /**
     * [stream]: only P0/P1 work streams tokens to its client. Lower priorities may be preempted and
     * restarted from scratch (requeue), so streaming them would show the client the text twice.
     */
    private class PendingRun(val request: AgentRunRequest, val origin: String, val job: InferenceJob, val submittedAt: Long, val stream: Boolean)

    private class ActiveRun(val pending: PendingRun) {
        val request: AgentRunRequest get() = pending.request
        val origin: String get() = pending.origin
        @Volatile var cancelled = false
        @Volatile var firstTokenAt = -1L
    }

    /** Seeds built-in agents and applies retention. Idempotent; call once per process start. */
    fun start() {
        val existing = agents.all().mapTo(HashSet()) { it.id }
        if (existing.isEmpty()) AgentSeeds.load().forEach(agents::put)
        else if (GENERAL_AGENT_ID !in existing) AgentSeeds.load().first { it.id == GENERAL_AGENT_ID }.let(agents::put)
        purgeExpiredClips()
        val purged = audit.purgeBefore(clock() - AUDIT_RETENTION_MS)
        if (purged > 0) log("audit retention purged $purged")
    }

    fun shutdown() {
        apply(scheduler.clear("Assistant service stopped"))
        models?.shutdown()
        speech.stop()
    }

    /** `onTrimMemory` (SPEC §12.2): drop background inference, then let the host unload the model. */
    fun onTrimMemory() {
        apply(scheduler.trim())
        onTrim()
    }

    /** `models.changed` to every client (the companion's Models screen and the IME's status). */
    fun modelsChanged(status: com.agentkeyboard.core.protocol.ModelsStatus) = emit("models.changed", status.toJson())

    // ───────────────────────────── IME → :agent ─────────────────────────────

    /**
     * One envelope from a client. [origin] is set by the binder front from the caller's registration
     * (never from the payload): replies and run events go back to that client only, and the IME's
     * focused-field gates apply only to IME-originated work (the companion has no focused field).
     */
    fun post(raw: String, origin: String = ORIGIN_IME) {
        val env = when (val d = EnvelopeCodec.decode(raw)) {
            is Decoded.Ok -> d.envelope
            is Decoded.VersionMismatch -> return d.id?.let { replyError(it, ErrorCode.VERSION, "protocol v${d.version}", origin) } ?: Unit
            is Decoded.Malformed -> return log("malformed envelope: ${d.reason}")
        }
        try {
            when (env.type) {
                in Catalog.REQUESTS -> onRequest(env, MessageDecoder.request(env.type, env.payload), origin)
                in Catalog.COMMANDS -> onCommand(MessageDecoder.command(env.type, env.payload), origin)
                else -> throw BridgeFailure(ErrorCode.UNKNOWN_TYPE, "unknown type '${env.type}'")
            }
        } catch (f: BridgeFailure) {
            log("rejected ${env.type}: ${f.code.wire}")
            replyError(env.id, f.code, f.message ?: f.code.wire, origin)
        } catch (e: RuntimeException) {
            // Message text is never logged: it can echo user content.
            log("internal error on ${env.type}: ${e::class.java.simpleName}")
            replyError(env.id, ErrorCode.INTERNAL, "Something went wrong", origin)
        }
    }

    fun focusChanged(fieldJson: String) {
        focused = runCatching { FieldContext.fromJson(Json.parse(fieldJson)) }.getOrNull()
    }

    /** FR-KB-11/14: never stores secrets or password-field copies; unpinned items expire after 1 h. */
    fun recordClipboard(text: String, sensitiveSource: Boolean) {
        if (sensitiveSource || text.isBlank() || PrivacyFilter.containsSecret(text)) return
        val clipped = text.take(MAX_TEXT_CHARS)
        val now = clock()
        val all = clipboard.all()
        val same = all.firstOrNull { it.item.text == clipped }
        // Ids must be unique across process restarts (rows outlive the in-memory sequence).
        val item = same?.item?.copy(at = now) ?: ClipboardItem(newClipId(), clipped, false, now)
        clipboard.put(StoredClip(item, if (item.pinned) null else now + CLIP_TTL_MS))
        val overflow = all.filter { !it.item.pinned && it.item.id != item.id }
            .sortedByDescending { it.item.at }.drop(MAX_UNPINNED_CLIPS - 1).map { it.item.id }
        if (overflow.isNotEmpty()) clipboard.delete(overflow)
    }

    fun appendAudit(json: String) {
        val o = runCatching { Json.parse(json) }.getOrNull().objOrNull() ?: return log("audit: malformed")
        fun s(k: String, max: Int = 2_000) = o[k].str()?.take(max)
        val action = s("action", 32) ?: return log("audit: missing action")
        audit.append(
            AuditRecord(
                at = o["at"].long() ?: clock(), action = action, agentId = s("agentId", 64),
                packageName = s("package", 256), conversation = s("conversation", 256),
                seen = s("seen")?.let(PrivacyFilter::redact), sent = s("sent")?.let(PrivacyFilter::redact),
                reason = s("reason", 256), model = s("model", 64),
            ),
        )
    }

    /**
     * Rung-1 capture from `AkNotificationListener` (SPEC §10.1). Messages are redacted by the cache;
     * IMEs are told about the newest incoming one so autonomy can react while the keyboard is open.
     */
    fun onNotification(packageName: String, title: String, messages: List<ChatMessage>, remoteInputAvailable: Boolean) {
        val before = conversations.get(ConversationCache.keyOf(packageName, title))?.messages?.lastOrNull { !it.fromMe }
        val conv = conversations.record(packageName, title, messages) ?: return
        val newest = conv.messages.lastOrNull { !it.fromMe } ?: return
        if (newest == before) return // a repost (e.g. our own reply appended): nothing new from them
        output.inbound(packageName, conv.key, Json.write(newest.toJson()), remoteInputAvailable)
    }

    // ───────────────────────────── requests ─────────────────────────────

    private fun onRequest(env: Envelope, request: Request, origin: String) {
        val result: JsonValue = when (request) {
            is Request.Hello -> throw BridgeFailure(ErrorCode.UNKNOWN_TYPE, "hello is answered by the IME")
            Request.AgentsList -> arr(sortedAgents())
            Request.ClipboardList -> {
                purgeExpiredClips()
                arr(clipboard.all().map { it.item }.sortedWith(compareByDescending<ClipboardItem> { it.pinned }.thenByDescending { it.at }))
            }
            Request.SystemStatusRequest -> status().let { s ->
                s.copy(autonomyConsent = autonomyConsented(), modelsInstalled = models?.hasTextModel() ?: s.modelsInstalled)
            }.toJson()
            Request.ModelsStatusRequest -> {
                val m = models ?: throw BridgeFailure(ErrorCode.UNAVAILABLE, "Model management isn’t available")
                // The Models screen is open: refresh a stale catalog, resume an interrupted queue.
                if (origin == ORIGIN_COMPANION) m.onVisible()
                m.status().toJson()
            }
            is Request.ContextSnapshot -> if (origin == ORIGIN_IME) snapshot(request.scope, request.titleHint)
            else throw BridgeFailure(ErrorCode.UNAVAILABLE, "No screen in the companion app")
            is Request.ContextScan -> throw BridgeFailure(ErrorCode.UNAVAILABLE, "Screen scans run in the keyboard")
        }
        emit("${env.type}.result", result, re = env.id, to = origin)
    }

    /**
     * Rung 1 only: the IME answers rungs 0, 2 and 3 itself and forwards here when accessibility is
     * off. `unavailable` tells it to fall back to its local rung 0. The chat is resolved against the
     * title the IME saw on screen ([titleHint]); without one the result says how sure it is
     * (`chat.match`), so the Lens can show "latest notification" instead of pretending.
     */
    private fun snapshot(scope: String, titleHint: String?): JsonValue {
        if (scope == "visual") throw BridgeFailure(ErrorCode.UNAVAILABLE, "Screen pixels are captured by the keyboard")
        val field = focused ?: throw BridgeFailure(ErrorCode.UNAVAILABLE, "No focused field")
        if (field.sensitive) throw BridgeFailure(ErrorCode.SECURE_FIELD, "Secure field")
        val pkg = field.packageName
        if (PrivacyFilter.isBlockedApp(pkg)) throw BridgeFailure(ErrorCode.UNAVAILABLE, "Blocked app")
        val res = conversations.resolve(pkg, titleHint) ?: throw BridgeFailure(ErrorCode.UNAVAILABLE, "No conversation captured")
        val conv = res.conversation
        val people = conv.messages.filter { !it.fromMe }.map { it.author }.distinct()
        return ScreenContext(
            appPackage = pkg, appLabel = field.appLabel, category = AppCategories.of(pkg), screenType = "chat",
            field = field, chat = ChatContext(conv.title, people, conv.messages, res.match),
            sensitivity = SensitivityFlags(secureWindow = false, sensitiveField = false, blockedApp = false),
            rung = 1, capturedAt = conv.updatedAt, ttlMs = SNAPSHOT_TTL_MS,
        ).toJson()
    }

    // ───────────────────────────── commands ─────────────────────────────

    private fun onCommand(command: Command, origin: String) {
        when (command) {
            is Command.AgentRun -> startRun(command.request, origin)
            is Command.AgentCancel -> apply(scheduler.cancel(command.runId, "Cancelled"))
            is Command.ModelsDownload -> models?.download(command.ids)
            is Command.ModelsCancel -> models?.cancel(command.id)
            is Command.ModelsDelete -> models?.delete(command.id)
            is Command.TtsSpeak -> speech.speak(command.text.take(MAX_TTS_CHARS))
            Command.TtsStop -> speech.stop()
            is Command.ClipboardPin -> clipboard.all().firstOrNull { it.item.id == command.id }?.let {
                clipboard.put(StoredClip(it.item.copy(pinned = command.pinned), if (command.pinned) null else clock() + CLIP_TTL_MS))
            }
            is Command.ClipboardDelete -> clipboard.delete(listOf(command.id))
            is Command.AgentsSave -> saveAgent(command.agent)
            is Command.AgentsDelete -> deleteAgent(command.id)
            is Command.SettingsSet -> setSetting(command.key, command.value)
            // Editor/UI commands are executed by the IME and never forwarded; ignore defensively.
            else -> log("ignored command ${command::class.java.simpleName}")
        }
    }

    private fun saveAgent(agent: AgentDefinition) {
        val problems = validateAgent(agent).toMutableList()
        if (agent.kind == "general" && agent.id != GENERAL_AGENT_ID) problems += "Only one General agent can exist."
        val existing = agents.all().firstOrNull { it.id == agent.id }
        if (problems.isEmpty()) {
            val now = clock()
            agents.put(
                agent.copy(
                    createdAt = existing?.createdAt ?: agent.createdAt.takeIf { it > 0 } ?: now,
                    updatedAt = now,
                    version = (existing?.version ?: 0) + 1,
                ),
            )
        } else {
            log("agents.save rejected (${problems.size} problems)")
        }
        // Always announce: on rejection the shell re-lists and converges on the persisted truth.
        emit("agents.changed", obj())
    }

    private fun deleteAgent(id: String) {
        if (id == GENERAL_AGENT_ID) return log("agents.delete refused for General (FR-AG-01)")
        agents.delete(id)
        emit("agents.changed", obj())
    }

    private fun setSetting(key: String, value: JsonValue) {
        if (key == KEY_CONSENT) {
            // Only real changes are audited: the shell re-syncs settings on every start.
            val granted = value.bool() == true
            val changed = granted != autonomyConsented()
            settings.put(key, JsonBool(granted))
            if (changed) audit.append(AuditRecord(clock(), if (granted) "consent_granted" else "consent_revoked", null, null, null, null, null, null, null))
            return
        }
        if (key.startsWith("models.")) {
            // Install records and the queue are owned by ModelService; the shell may only toggle Wi-Fi-only.
            if (key != ModelService.KEY_WIFI_ONLY || value.bool() == null) return log("settings.set refused for $key")
            settings.put(key, value)
            models?.onSetting(key)
            return
        }
        settings.put(key, value)
    }

    /**
     * Foreground RemoteInput send for Autopilot (SPEC §11.6.3 strategy 3): the IME chose this path
     * because the field itself can't send. Refused without consent, in blocked apps, and for
     * conversations this process never saw (the key must come from an inbound message).
     */
    fun remoteReply(requestId: String, conversationKey: String, text: String) {
        val conv = conversations.get(conversationKey)
        val ok = replies != null && autonomyConsented() && conv != null && !PrivacyFilter.isBlockedApp(conv.packageName) &&
            text.isNotBlank() && text.length <= MAX_TEXT_CHARS && replies.send(conversationKey, text)
        output.remoteReplyResult(requestId, ok)
    }

    /** Background autonomy (FR-AU-09, Part 3) must check this before acting without the keyboard open. */
    fun autonomyConsented(): Boolean = settings.get(KEY_CONSENT).bool() == true

    // ───────────────────────────── runs ─────────────────────────────

    private fun startRun(request: AgentRunRequest, origin: String) {
        val agent = agents.all().firstOrNull { it.id == request.agentId }
            ?: return runError(request.runId, "policy", "This agent no longer exists.", origin)
        // The focused field belongs to the keyboard; a companion run (e.g. testing an agent) isn't typing into it.
        val ime = origin == ORIGIN_IME
        if (ime && focused?.sensitive == true) return runError(request.runId, "policy", "AI is off in secure fields.", origin)
        val job = InferenceJob(request, agent, if (ime) imeContext(request) else RunContext(null, null))
        // One resident model (ADR-0004): the scheduler decides what runs now, what waits and what is
        // replaced (SPEC §11.3: P0 voice > P1 card > P2 autonomy > P3/P4 background).
        val priority = Skills.of(request).priority
        val pending = PendingRun(request, origin, job, clock(), stream = priority <= Priority.P1)
        apply(scheduler.submit(InferenceScheduler.Item(request.runId, priority, pending)))
    }

    /** Executes scheduler decisions. Busy (and so the FGS) is re-evaluated once, after all of them. */
    private fun apply(effects: List<InferenceScheduler.Effect<PendingRun>>) {
        for (e in effects) when (e) {
            is InferenceScheduler.Effect.Start -> launch(e.item.payload)
            is InferenceScheduler.Effect.Cancel -> {
                val run = active
                if (run != null && run.pending === e.item.payload) {
                    run.cancelled = true
                    active = null
                }
                // A requeued run will start again; its client only ever hears how it finally ends.
                if (!e.requeued) runError(e.item.payload.request.runId, "cancelled", e.reason, e.item.payload.origin)
            }
        }
        updateBusy()
    }

    private fun launch(pending: PendingRun) {
        val run = ActiveRun(pending)
        active = run
        updateBusy() // before execute: a synchronous executor may finish the run inside it
        val request = pending.request
        engineExecutor.execute {
            val outcome: Result<InferenceOutput> = if (run.cancelled) {
                Result.failure(EngineFailure("cancelled", "Cancelled")) // superseded while waiting for the engine thread
            } else {
                try {
                    Result.success(
                        engine.generate(
                            pending.job,
                            onToken = { delta ->
                                if (pending.stream && !run.cancelled && delta.isNotEmpty()) {
                                    if (run.firstTokenAt < 0) run.firstTokenAt = clock()
                                    serial.execute { if (active === run) emit("agent.token", obj("runId" to request.runId, "delta" to delta), to = run.origin) }
                                }
                            },
                            isCancelled = { run.cancelled },
                        ),
                    )
                } catch (f: EngineFailure) {
                    Result.failure(f)
                } catch (e: Exception) {
                    Result.failure(EngineFailure("internal", "The model failed. Try again."))
                } catch (e: OutOfMemoryError) {
                    Result.failure(EngineFailure("unavailable", "Not enough memory for the model right now."))
                }
            }
            serial.execute { finish(run, outcome) }
        }
    }

    private fun finish(run: ActiveRun, outcome: Result<InferenceOutput>) {
        if (active !== run) return // cancelled, superseded or requeued: already accounted for
        active = null
        val req = run.request
        outcome.fold(
            onSuccess = { out ->
                val now = clock()
                val start = run.pending.submittedAt
                emit(
                    "agent.done",
                    obj(
                        "runId" to req.runId, "text" to out.text, "data" to out.data,
                        "actions" to actionsFor(req), "apply" to applyModeFor(req)?.let { obj("mode" to it) },
                        "model" to engine.modelId,
                        "latencyMs" to obj("ttft" to ((if (run.firstTokenAt >= 0) run.firstTokenAt else now) - start), "total" to (now - start)),
                    ),
                    to = run.origin,
                )
            },
            onFailure = { e ->
                val f = e as? EngineFailure ?: EngineFailure("internal", "The model failed. Try again.")
                runError(req.runId, f.code, f.message ?: "error", run.origin)
            },
        )
        apply(scheduler.finished(req.runId))
    }

    /**
     * Context for an IME run: rung-1 chat resolved against the title the IME read on screen
     * (`params.conversationTitle`), plus the screen text the IME attached (`params.screen`). Both
     * params are written by the IME itself, which strips any shell-provided values first.
     */
    private fun imeContext(request: AgentRunRequest): RunContext {
        val pkg = focused?.packageName?.takeUnless(PrivacyFilter::isBlockedApp)
        val p = request.params
        val hint = p?.get("conversationTitle").str()
        // Autopilot names the conversation it is drafting for (its key, from an inbound message of this app).
        val named = p?.get("conversation").str()?.let(conversations::get)?.takeIf { it.packageName == pkg }
        val conversation = named ?: pkg?.let { conversations.resolve(it, hint) }?.takeIf { it.usableForDrafting }?.conversation
        val screen = p?.get("screen").objOrNull()
        val text = screen?.get("text").str()?.take(MAX_SCREEN_CHARS)?.let(PrivacyFilter::redact)?.takeIf { pkg != null && it.isNotBlank() }
        return RunContext(focused, conversation, text, text?.let { screen?.get("source").str() })
    }

    private fun updateBusy() {
        val now = active != null && engine.needsForeground
        if (now != busy) {
            busy = now
            onBusyChanged(now)
        }
    }

    private fun runError(runId: String, code: String, message: String, to: String?) =
        emit("agent.error", obj("runId" to runId, "code" to code, "message" to message), to = to)

    // ───────────────────────────── helpers ─────────────────────────────

    private fun sortedAgents(): List<AgentDefinition> =
        agents.all().sortedWith(compareByDescending<AgentDefinition> { it.id == GENERAL_AGENT_ID }.thenBy { it.createdAt }.thenBy { it.name })

    private fun purgeExpiredClips() {
        val now = clock()
        val expired = clipboard.all().filter { c -> c.expiresAt != null && c.expiresAt <= now }.map { it.item.id }
        if (expired.isNotEmpty()) clipboard.delete(expired)
    }

    private fun newClipId(): String = "c_" + java.lang.Long.toString(clock(), 36) + "_" + java.util.UUID.randomUUID().toString().substring(0, 8)

    /** [to] = one client origin; null = every client (shared state such as `agents.changed`). */
    private fun emit(type: String, payload: JsonValue, re: String? = null, to: String? = null) {
        output.envelope(Envelope(PROTOCOL_VERSION, ids.next(), type, clock(), re, payload).encode(), to)
    }

    private fun replyError(re: String, code: ErrorCode, message: String, to: String) =
        emit("error", obj("code" to code.wire, "message" to message), re, to)

    companion object {
        const val KEY_CONSENT = "autonomy.consent"
        const val CLIP_TTL_MS = 60 * 60 * 1000L // FR-KB-11
        const val MAX_UNPINNED_CLIPS = 50
        const val MAX_TTS_CHARS = 4_000 // TextToSpeech.getMaxSpeechInputLength()
        const val AUDIT_RETENTION_MS = 90L * 24 * 60 * 60 * 1000 // SPEC §16
        const val SNAPSHOT_TTL_MS = 30_000L
        const val MAX_SCREEN_CHARS = 12_000
        const val ORIGIN_IME = "ime"
        const val ORIGIN_COMPANION = "companion"
        val ORIGINS = setOf(ORIGIN_IME, ORIGIN_COMPANION)

        private val INFORMATIONAL = setOf("explain", "define", "summarize", "describe_image")

        /** Mirrors the web mock (`apps/shell/src/bridge/web-mock/runtime.ts`) so the UI behaves the same. */
        fun actionsFor(r: AgentRunRequest): List<String> = when {
            r.skill == "fill_form" -> listOf("apply", "retry")
            r.skill in INFORMATIONAL -> listOf("insert", "copy", "speak")
            else -> listOf("apply", "copy", "speak", "retry")
        }

        /**
         * `AgentRunResult.data.confidence` (domain.ts): a decimal string by contract, but a number is
         * accepted too. Missing, malformed or out-of-range → 0.0, i.e. low confidence → escalate.
         */
        fun confidenceOf(data: JsonValue?): Double {
            val v = data.objOrNull()?.get("confidence")
            val d = v.num() ?: v.str()?.trim()?.toDoubleOrNull() ?: return 0.0
            return if (d.isNaN() || d < 0.0 || d > 1.0) 0.0 else d
        }

        fun applyModeFor(r: AgentRunRequest): String? = when {
            r.skill == "fill_form" -> "fillForm"
            r.skill in INFORMATIONAL -> "insert"
            r.target == "selection" || r.target == "word" -> "replaceSelection"
            else -> "replaceField"
        }
    }
}

/** Built-in agents, generated from `apps/shell/src/agents/defaults.ts` (drift-checked by vitest). */
object AgentSeeds {
    fun load(): List<AgentDefinition> {
        val raw = AgentSeeds::class.java.getResourceAsStream("/agents/defaults.json")?.bufferedReader()?.use { it.readText() }
            ?: error("missing agents/defaults.json resource")
        val items = (Json.parse(raw) as? com.agentkeyboard.core.json.JsonArr)?.items ?: error("defaults.json must be an array")
        return items.map { v -> AgentDefinition.fromJson(v).let { (a, err) -> a ?: error("bad seed agent: $err") } }
    }
}
