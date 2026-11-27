package com.agentkeyboard.core.agent

import com.agentkeyboard.core.context.AppCategories
import com.agentkeyboard.core.context.ConversationCache
import com.agentkeyboard.core.json.Json
import com.agentkeyboard.core.json.JsonValue
import com.agentkeyboard.core.json.arr
import com.agentkeyboard.core.json.bool
import com.agentkeyboard.core.json.long
import com.agentkeyboard.core.json.obj
import com.agentkeyboard.core.json.objOrNull
import com.agentkeyboard.core.json.str
import com.agentkeyboard.core.privacy.PrivacyFilter
import com.agentkeyboard.core.protocol.AgentDefinition
import com.agentkeyboard.core.protocol.AgentRunRequest
import com.agentkeyboard.core.protocol.BridgeFailure
import com.agentkeyboard.core.protocol.Catalog
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
) {
    private val ids = IdGenerator("a")
    private var focused: FieldContext? = null
    private var active: ActiveRun? = null
    private var busy = false

    private class ActiveRun(val request: AgentRunRequest, val startedAt: Long) {
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
        active?.let { cancel(it, "Assistant service stopped") }
        speech.stop()
    }

    // ───────────────────────────── IME → :agent ─────────────────────────────

    fun post(raw: String) {
        val env = when (val d = EnvelopeCodec.decode(raw)) {
            is Decoded.Ok -> d.envelope
            is Decoded.VersionMismatch -> return d.id?.let { replyError(it, ErrorCode.VERSION, "protocol v${d.version}") } ?: Unit
            is Decoded.Malformed -> return log("malformed envelope: ${d.reason}")
        }
        try {
            when (env.type) {
                in Catalog.REQUESTS -> onRequest(env, MessageDecoder.request(env.type, env.payload))
                in Catalog.COMMANDS -> onCommand(MessageDecoder.command(env.type, env.payload))
                else -> throw BridgeFailure(ErrorCode.UNKNOWN_TYPE, "unknown type '${env.type}'")
            }
        } catch (f: BridgeFailure) {
            log("rejected ${env.type}: ${f.code.wire}")
            replyError(env.id, f.code, f.message ?: f.code.wire)
        } catch (e: RuntimeException) {
            // Message text is never logged: it can echo user content.
            log("internal error on ${env.type}: ${e::class.java.simpleName}")
            replyError(env.id, ErrorCode.INTERNAL, "Something went wrong")
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

    private fun onRequest(env: Envelope, request: Request) {
        val result: JsonValue = when (request) {
            is Request.Hello -> throw BridgeFailure(ErrorCode.UNKNOWN_TYPE, "hello is answered by the IME")
            Request.AgentsList -> arr(sortedAgents())
            Request.ClipboardList -> {
                purgeExpiredClips()
                arr(clipboard.all().map { it.item }.sortedWith(compareByDescending<ClipboardItem> { it.pinned }.thenByDescending { it.at }))
            }
            Request.SystemStatusRequest -> status().toJson()
            is Request.ContextSnapshot -> snapshot(request.scope)
        }
        emit("${env.type}.result", result, re = env.id)
    }

    /**
     * Rung 1 only: the IME answers rungs 0 and 2 itself and forwards here when accessibility is off.
     * `unavailable` tells it to fall back to its local rung 0.
     */
    private fun snapshot(scope: String): JsonValue {
        if (scope == "visual") throw BridgeFailure(ErrorCode.UNAVAILABLE, "Visual context arrives with on-device vision models")
        val field = focused ?: throw BridgeFailure(ErrorCode.UNAVAILABLE, "No focused field")
        if (field.sensitive) throw BridgeFailure(ErrorCode.SECURE_FIELD, "Secure field")
        val pkg = field.packageName
        if (PrivacyFilter.isBlockedApp(pkg)) throw BridgeFailure(ErrorCode.UNAVAILABLE, "Blocked app")
        val conv = conversations.latestFor(pkg) ?: throw BridgeFailure(ErrorCode.UNAVAILABLE, "No conversation captured")
        val people = conv.messages.filter { !it.fromMe }.map { it.author }.distinct()
        return ScreenContext(
            appPackage = pkg, appLabel = field.appLabel, category = AppCategories.of(pkg), screenType = "chat",
            field = field, chat = Triple(conv.title, people, conv.messages),
            sensitivity = SensitivityFlags(secureWindow = false, sensitiveField = false, blockedApp = false),
            rung = 1, capturedAt = conv.updatedAt, ttlMs = SNAPSHOT_TTL_MS,
        ).toJson()
    }

    // ───────────────────────────── commands ─────────────────────────────

    private fun onCommand(command: Command) {
        when (command) {
            is Command.AgentRun -> startRun(command.request)
            is Command.AgentCancel -> active?.takeIf { it.request.runId == command.runId }?.let { cancel(it, "Cancelled") }
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
        settings.put(key, value)
        if (key == KEY_CONSENT) {
            audit.append(AuditRecord(clock(), if (value.bool() == true) "consent_granted" else "consent_revoked", null, null, null, null, null, null, null))
        }
    }

    /** Background autonomy (FR-AU-09, Part 3) must check this before acting without the keyboard open. */
    fun autonomyConsented(): Boolean = settings.get(KEY_CONSENT).bool() == true

    // ───────────────────────────── runs ─────────────────────────────

    private fun startRun(request: AgentRunRequest) {
        val agent = agents.all().firstOrNull { it.id == request.agentId }
            ?: return runError(request.runId, "policy", "This agent no longer exists.")
        if (focused?.sensitive == true) return runError(request.runId, "policy", "AI is off in secure fields.")
        // One resident model (ADR-0004): the newest request wins; the user has moved on.
        // Hand over without releasing foreground: stopping and restarting the FGS between runs is
        // wasteful and can be refused from the background on Android 12+.
        active?.let { cancel(it, "Replaced by a newer request", release = false) }
        val run = ActiveRun(request, clock())
        active = run
        updateBusy()
        val pkg = focused?.packageName?.takeUnless(PrivacyFilter::isBlockedApp)
        val job = InferenceJob(request, agent, RunContext(focused, pkg?.let(conversations::latestFor)))
        engineExecutor.execute {
            val outcome: Result<InferenceOutput> = try {
                Result.success(
                    engine.generate(
                        job,
                        onToken = { delta ->
                            if (!run.cancelled && delta.isNotEmpty()) {
                                if (run.firstTokenAt < 0) run.firstTokenAt = clock()
                                serial.execute { if (active === run) emit("agent.token", obj("runId" to request.runId, "delta" to delta)) }
                            }
                        },
                        isCancelled = { run.cancelled },
                    ),
                )
            } catch (f: EngineFailure) {
                Result.failure(f)
            } catch (e: Exception) {
                Result.failure(EngineFailure("internal", "The model failed. Try again."))
            }
            serial.execute { finish(run, outcome) }
        }
    }

    private fun finish(run: ActiveRun, outcome: Result<InferenceOutput>) {
        if (active !== run) return // cancelled or superseded: its error was already emitted
        active = null
        updateBusy()
        val req = run.request
        outcome.fold(
            onSuccess = { out ->
                val now = clock()
                emit(
                    "agent.done",
                    obj(
                        "runId" to req.runId, "text" to out.text, "data" to out.data,
                        "actions" to actionsFor(req), "apply" to applyModeFor(req)?.let { obj("mode" to it) },
                        "model" to engine.modelId,
                        "latencyMs" to obj("ttft" to ((if (run.firstTokenAt >= 0) run.firstTokenAt else now) - run.startedAt), "total" to (now - run.startedAt)),
                    ),
                )
            },
            onFailure = { e ->
                val f = e as? EngineFailure ?: EngineFailure("internal", "The model failed. Try again.")
                runError(req.runId, f.code, f.message ?: "error")
            },
        )
    }

    private fun cancel(run: ActiveRun, message: String, release: Boolean = true) {
        run.cancelled = true
        if (active === run) active = null
        if (release) updateBusy()
        runError(run.request.runId, "cancelled", message)
    }

    private fun updateBusy() {
        val now = active != null && engine.needsForeground
        if (now != busy) {
            busy = now
            onBusyChanged(now)
        }
    }

    private fun runError(runId: String, code: String, message: String) =
        emit("agent.error", obj("runId" to runId, "code" to code, "message" to message))

    // ───────────────────────────── helpers ─────────────────────────────

    private fun sortedAgents(): List<AgentDefinition> =
        agents.all().sortedWith(compareByDescending<AgentDefinition> { it.id == GENERAL_AGENT_ID }.thenBy { it.createdAt }.thenBy { it.name })

    private fun purgeExpiredClips() {
        val now = clock()
        val expired = clipboard.all().filter { c -> c.expiresAt != null && c.expiresAt <= now }.map { it.item.id }
        if (expired.isNotEmpty()) clipboard.delete(expired)
    }

    private fun newClipId(): String = "c_" + java.lang.Long.toString(clock(), 36) + "_" + java.util.UUID.randomUUID().toString().substring(0, 8)

    private fun emit(type: String, payload: JsonValue, re: String? = null) {
        output.envelope(Envelope(PROTOCOL_VERSION, ids.next(), type, clock(), re, payload).encode())
    }

    private fun replyError(re: String, code: ErrorCode, message: String) =
        emit("error", obj("code" to code.wire, "message" to message), re)

    companion object {
        const val KEY_CONSENT = "autonomy.consent"
        const val CLIP_TTL_MS = 60 * 60 * 1000L // FR-KB-11
        const val MAX_UNPINNED_CLIPS = 50
        const val MAX_TTS_CHARS = 4_000 // TextToSpeech.getMaxSpeechInputLength()
        const val AUDIT_RETENTION_MS = 90L * 24 * 60 * 60 * 1000 // SPEC §16
        const val SNAPSHOT_TTL_MS = 30_000L

        private val INFORMATIONAL = setOf("explain", "define", "summarize", "describe_image")

        /** Mirrors the web mock (`apps/shell/src/bridge/web-mock/runtime.ts`) so the UI behaves the same. */
        fun actionsFor(r: AgentRunRequest): List<String> = when {
            r.skill == "fill_form" -> listOf("apply", "retry")
            r.skill in INFORMATIONAL -> listOf("insert", "copy", "speak")
            else -> listOf("apply", "copy", "speak", "retry")
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
