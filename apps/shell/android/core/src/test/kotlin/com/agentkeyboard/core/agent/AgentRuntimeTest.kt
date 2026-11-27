package com.agentkeyboard.core.agent

import com.agentkeyboard.core.json.Json
import com.agentkeyboard.core.json.JsonArr
import com.agentkeyboard.core.json.JsonValue
import com.agentkeyboard.core.json.arrOrNull
import com.agentkeyboard.core.json.long
import com.agentkeyboard.core.json.obj
import com.agentkeyboard.core.json.objOrNull
import com.agentkeyboard.core.json.str
import com.agentkeyboard.core.protocol.AgentDefinition
import com.agentkeyboard.core.protocol.CapsMode
import com.agentkeyboard.core.protocol.ChatMessage
import com.agentkeyboard.core.protocol.Decoded
import com.agentkeyboard.core.protocol.DeviceTier
import com.agentkeyboard.core.protocol.Envelope
import com.agentkeyboard.core.protocol.EnvelopeCodec
import com.agentkeyboard.core.protocol.FieldContext
import com.agentkeyboard.core.protocol.GENERAL_AGENT_ID
import com.agentkeyboard.core.protocol.ImeActionKind
import com.agentkeyboard.core.protocol.InputKind
import com.agentkeyboard.core.protocol.SystemStatus
import com.agentkeyboard.core.protocol.validateAgent
import org.junit.Test
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentRuntimeTest {
    // ───────── fakes ─────────
    private class MemAgents : AgentStore {
        val map = LinkedHashMap<String, AgentDefinition>()
        override fun all() = map.values.toList()
        override fun put(agent: AgentDefinition) { map[agent.id] = agent }
        override fun delete(id: String) { map.remove(id) }
    }
    private class MemClips : ClipboardStore {
        val map = LinkedHashMap<String, StoredClip>()
        override fun all() = map.values.toList()
        override fun put(clip: StoredClip) { map[clip.item.id] = clip }
        override fun delete(ids: Collection<String>) { ids.forEach(map::remove) }
    }
    private class MemAudit : AuditStore {
        val rows = mutableListOf<AuditRecord>()
        var purgedBefore: Long? = null
        override fun append(record: AuditRecord) { rows += record }
        override fun purgeBefore(at: Long): Int { purgedBefore = at; return 0 }
    }
    private class MemSettings : SettingsStore {
        val map = HashMap<String, JsonValue>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: JsonValue) { map[key] = value }
    }
    private class FakeSpeech : Speech {
        val spoken = mutableListOf<String>()
        var stops = 0
        override fun speak(text: String) { spoken += text }
        override fun stop() { stops++ }
    }
    private class Out : AgentOutput {
        val envelopes = mutableListOf<Envelope>()
        val targets = mutableListOf<String?>()
        val inbound = mutableListOf<Pair<String, String>>()
        val replyResults = mutableListOf<Pair<String, Boolean>>()
        override fun envelope(raw: String, to: String?) { envelopes += (EnvelopeCodec.decode(raw) as Decoded.Ok).envelope; targets += to }
        override fun remoteReplyResult(requestId: String, ok: Boolean) { replyResults += requestId to ok }
        fun targetOf(t: String) = targets[envelopes.indexOfFirst { it.type == t }]
        override fun inbound(packageName: String, conversationKey: String, messageJson: String, remoteInputAvailable: Boolean) {
            inbound += conversationKey to messageJson
        }
        fun ofType(t: String) = envelopes.filter { it.type == t }
    }
    /** Engine thread that only runs when the test says so, to exercise cancellation races. */
    private class ManualExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue += command }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
    private class ScriptedEngine(private val tokens: List<String>, override val needsForeground: Boolean = true) : InferenceEngine {
        override val modelId = "test-1b"
        var lastJob: InferenceJob? = null
        override fun generate(job: InferenceJob, onToken: (String) -> Unit, isCancelled: () -> Boolean): InferenceOutput {
            lastJob = job
            tokens.forEach { if (!isCancelled()) onToken(it) }
            return InferenceOutput(tokens.joinToString(""), mapOf("confidence" to "0.9"))
        }
    }

    private var now = 1_000_000L
    private val agents = MemAgents()
    private val clips = MemClips()
    private val audit = MemAudit()
    private val settings = MemSettings()
    private val speech = FakeSpeech()
    private val out = Out()
    private val busy = mutableListOf<Boolean>()
    private val direct = Executor { it.run() }

    private val sentReplies = mutableListOf<Pair<String, String>>()
    private fun runtime(engine: InferenceEngine = NoModelEngine, engineExec: Executor = direct) = AgentRuntime(
        agents, clips, audit, settings, speech, engine, out,
        status = { SystemStatus(true, true, false, false, "play", DeviceTier.S, 4096, false) },
        engineExecutor = engineExec, serial = direct, onBusyChanged = { busy += it }, clock = { now },
        replies = { key, text -> sentReplies += key to text; true },
    ).also { it.start() }

    private var seq = 0
    private fun env(type: String, payload: String = "{}") = """{"v":1,"id":"n:${++seq}","type":"$type","ts":1,"payload":$payload}"""
    private fun field(pkg: String = "com.whatsapp", sensitive: Boolean = false) =
        Json.write(FieldContext(pkg, "WhatsApp", InputKind.TEXT, ImeActionKind.SEND, CapsMode.SENTENCES, sensitive = sensitive).toJson())
    private fun run(runId: String, agentId: String = GENERAL_AGENT_ID, skill: String = "reply", target: String = "conversation") =
        env("agent.run", """{"runId":"$runId","agentId":"$agentId","skill":"$skill","target":"$target"}""")
    private fun Envelope.p() = payload.objOrNull()!!

    // ───────── seeding & requests ─────────

    @Test fun seedsMatchValidationRulesAndGeneralIsFirst() {
        val seeds = AgentSeeds.load()
        assertEquals(GENERAL_AGENT_ID, seeds.first().id)
        seeds.forEach { assertEquals(emptyList(), validateAgent(it), it.id) }
        val rt = runtime()
        rt.post(env("agents.list"))
        val reply = out.ofType("agents.list.result").single()
        assertEquals("n:1", reply.re)
        val ids = (reply.payload as JsonArr).items.map { it.objOrNull()!!["id"].str() }
        assertEquals(seeds.map { it.id }.toSet(), ids.toSet())
        assertEquals(GENERAL_AGENT_ID, ids.first())
    }

    @Test fun startIsIdempotentAndRestoresAMissingGeneral() {
        runtime()
        val n = agents.map.size
        agents.map.remove(GENERAL_AGENT_ID)
        runtime()
        assertEquals(n, agents.map.size)
        assertNotNull(agents.map[GENERAL_AGENT_ID])
        assertEquals(now - AgentRuntime.AUDIT_RETENTION_MS, audit.purgedBefore)
    }

    @Test fun malformedAndUnknownTrafficGetCorrelatedErrors() {
        val rt = runtime()
        rt.post(env("context.snapshot", """{"scope":"everything"}"""))
        rt.post(env("no.such.type"))
        rt.post("""{"v":9,"id":"n:77","type":"agents.list","ts":1,"payload":{}}""")
        rt.post("not json") // no id → nothing to correlate, dropped
        val errors = out.ofType("error")
        assertEquals(listOf("bad_payload", "unknown_type", "version"), errors.map { it.p()["code"].str() })
        assertEquals(listOf("n:1", "n:2", "n:77"), errors.map { it.re })
    }

    // ───────── runs ─────────

    @Test fun withoutModelsRunsFailFastAsUnavailableAndNeverGoForeground() {
        val rt = runtime()
        rt.post(run("r1"))
        val e = out.ofType("agent.error").single().p()
        assertEquals("r1", e["runId"].str())
        assertEquals("unavailable", e["code"].str())
        assertTrue(busy.isEmpty())
    }

    @Test fun streamsTokensThenDoneWithContractFieldsAndForegroundBracketing() {
        val engine = ScriptedEngine(listOf("Hel", "lo"))
        val rt = runtime(engine)
        rt.focusChanged(field())
        rt.post(run("r1"))
        assertEquals(listOf("Hel", "lo"), out.ofType("agent.token").map { it.p()["delta"].str() })
        val done = out.ofType("agent.done").single().p()
        assertEquals("Hello", done["text"].str())
        assertEquals("test-1b", done["model"].str())
        assertEquals("0.9", done["data"].objOrNull()!!["confidence"].str())
        assertEquals(listOf("apply", "copy", "speak", "retry"), done["actions"].arrOrNull()!!.items.map { it.str() })
        assertEquals("replaceField", done["apply"].objOrNull()!!["mode"].str())
        assertNotNull(done["latencyMs"].objOrNull()!!["total"].long())
        assertEquals(listOf(true, false), busy)
        assertEquals("com.whatsapp", engine.lastJob!!.context.field!!.packageName)
    }

    @Test fun cancelBeatsALateResultAndSupersededRunsAreCancelled() {
        val exec = ManualExecutor()
        val rt = runtime(ScriptedEngine(listOf("x")), exec)
        rt.post(run("r1"))
        rt.post(run("r2")) // newest wins
        rt.post(env("agent.cancel", """{"runId":"r2"}"""))
        exec.runAll() // both engine jobs complete after the fact
        assertTrue(out.ofType("agent.done").isEmpty())
        assertTrue(out.ofType("agent.token").isEmpty())
        assertEquals(listOf("r1" to "cancelled", "r2" to "cancelled"), out.ofType("agent.error").map { it.p()["runId"].str() to it.p()["code"].str() })
        assertEquals(listOf(true, false), busy) // stayed foreground across the hand-over, then released
    }

    @Test fun policyGatesSecureFieldsAndUnknownAgents() {
        val rt = runtime(ScriptedEngine(listOf("x")))
        rt.post(run("r1", agentId = "ghost"))
        rt.focusChanged(field(sensitive = true))
        rt.post(run("r2"))
        rt.post(env("context.snapshot", """{"scope":"screen"}"""))
        assertEquals(listOf("policy", "policy"), out.ofType("agent.error").map { it.p()["code"].str() })
        assertEquals("secure_field", out.ofType("error").single().p()["code"].str())
        assertTrue(out.ofType("agent.done").isEmpty())
    }

    @Test fun malformedFocusRecordFailsClosed() {
        val rt = runtime(ScriptedEngine(listOf("x")))
        rt.focusChanged("""{"package":"com.whatsapp","appLabel":"W","kind":"text","imeAction":"send","capsMode":"none"}""")
        rt.post(run("r1"))
        assertEquals("policy", out.ofType("agent.error").single().p()["code"].str()) // missing `sensitive` ⇒ treated as secure
    }

    // ───────── agents CRUD ─────────

    @Test fun agentSaveValidatesBumpsVersionAndAlwaysAnnounces() {
        val rt = runtime()
        val sales = agents.map["sales"]!!
        rt.post(env("agents.save", """{"agent":${Json.write(sales.copy(name = "Sales Pro", createdAt = 1).toJson())}}"""))
        assertEquals("Sales Pro", agents.map["sales"]!!.name)
        assertEquals(sales.version + 1, agents.map["sales"]!!.version)
        assertEquals(sales.createdAt, agents.map["sales"]!!.createdAt) // createdAt is immutable
        rt.post(env("agents.save", """{"agent":${Json.write(sales.copy(id = "x", name = " ").toJson())}}"""))
        rt.post(env("agents.save", """{"agent":${Json.write(sales.copy(id = "fake", kind = "general").toJson())}}"""))
        assertNull(agents.map["x"])
        assertNull(agents.map["fake"])
        assertEquals(3, out.ofType("agents.changed").size)
    }

    @Test fun generalCanNeverBeDeleted() {
        val rt = runtime()
        rt.post(env("agents.delete", """{"id":"general"}"""))
        rt.post(env("agents.delete", """{"id":"support"}"""))
        assertNotNull(agents.map[GENERAL_AGENT_ID])
        assertNull(agents.map["support"])
        assertEquals(1, out.ofType("agents.changed").size)
    }

    // ───────── clipboard ─────────

    @Test fun clipboardSkipsSecretsDedupesExpiresAndPins() {
        val rt = runtime()
        rt.recordClipboard("hunter2", sensitiveSource = true)
        rt.recordClipboard("card 4111 1111 1111 1111", sensitiveSource = false)
        rt.recordClipboard("hello", false)
        now += 10
        rt.recordClipboard("hello", false)
        assertEquals(1, clips.map.size)
        val id = clips.map.values.single().item.id
        rt.post(env("clipboard.pin", """{"id":"$id","pinned":true}"""))
        rt.recordClipboard("temp", false)
        now += AgentRuntime.CLIP_TTL_MS + 1
        rt.post(env("clipboard.list"))
        val items = (out.ofType("clipboard.list.result").single().payload as JsonArr).items.map { it.objOrNull()!! }
        assertEquals(listOf("hello"), items.map { it["text"].str() }) // pinned survives, unpinned expired
        rt.post(env("clipboard.delete", """{"id":"$id"}"""))
        assertTrue(clips.map.isEmpty())
    }

    @Test fun unpinnedHistoryIsCapped() {
        val rt = runtime()
        repeat(AgentRuntime.MAX_UNPINNED_CLIPS + 5) { now++; rt.recordClipboard("item $it", false) }
        assertEquals(AgentRuntime.MAX_UNPINNED_CLIPS, clips.map.size)
        assertTrue(clips.map.values.any { it.item.text == "item ${AgentRuntime.MAX_UNPINNED_CLIPS + 4}" })
    }

    // ───────── settings, audit, tts ─────────

    @Test fun consentIsPersistedAndAudited() {
        val rt = runtime()
        assertFalse(rt.autonomyConsented())
        rt.post(env("settings.set", """{"key":"autonomy.consent","value":true}"""))
        assertTrue(rt.autonomyConsented())
        rt.post(env("settings.set", """{"key":"autonomy.consent","value":false}"""))
        assertEquals(listOf("consent_granted", "consent_revoked"), audit.rows.map { it.action })
    }

    @Test fun auditRecordsAreRedactedAndMalformedOnesDropped() {
        val rt = runtime()
        rt.appendAudit(Json.write(obj("action" to "sent", "agentId" to "sales", "sent" to "code is 482913", "at" to 5)))
        rt.appendAudit("{oops")
        rt.appendAudit("""{"agentId":"x"}""")
        val row = audit.rows.single()
        assertEquals("sent", row.action)
        assertFalse(row.sent!!.contains("482913"))
        assertEquals(5L, row.at)
    }

    @Test fun ttsIsBoundedAndStoppable() {
        val rt = runtime()
        rt.post(env("tts.speak", """{"text":"${"a".repeat(5_000)}"}"""))
        rt.post(env("tts.stop"))
        assertEquals(AgentRuntime.MAX_TTS_CHARS, speech.spoken.single().length)
        assertEquals(1, speech.stops)
    }

    // ───────── notifications → rung 1 ─────────

    @Test fun inboundNotificationsAreAnnouncedOnceAndFeedRungOneSnapshots() {
        val rt = runtime()
        val m1 = ChatMessage("1", false, "Ama", "Is the bundle still GH₵ 250?", 100)
        rt.onNotification("com.whatsapp", "Ama", listOf(m1), remoteInputAvailable = true)
        rt.onNotification("com.whatsapp", "Ama", listOf(m1, ChatMessage("2", true, "Me", "Yes!", 200)), true) // repost
        rt.onNotification("com.android.vending", "Pay", listOf(m1), true) // not a chat app, still cached but…
        assertEquals(1, out.inbound.count { it.first.startsWith("com.whatsapp") })

        rt.focusChanged(field())
        rt.post(env("context.snapshot", """{"scope":"screen"}"""))
        val ctx = out.ofType("context.snapshot.result").single().p()
        assertEquals(1L, ctx["rung"].long())
        assertEquals("chat", ctx["screenType"].str())
        assertEquals(listOf("Ama"), ctx["chat"].objOrNull()!!["participants"].arrOrNull()!!.items.map { it.str() })

        rt.focusChanged(field(pkg = "com.example.notes"))
        rt.post(env("context.snapshot", """{"scope":"screen"}"""))
        assertEquals("unavailable", out.ofType("error").single().p()["code"].str()) // IME falls back to rung 0
    }

    @Test fun blockedAppsAreNeverCaptured() {
        val rt = runtime()
        val bank = com.agentkeyboard.core.privacy.PrivacyFilter.PRESET_BLOCKED_PACKAGES.first()
        rt.onNotification(bank, "Alert", listOf(ChatMessage("1", false, "Bank", "OTP", 1)), true)
        assertTrue(out.inbound.isEmpty())
        assertNull(rt.conversations.latestFor(bank))
    }

    // ───────── review fixes: origins, consent, chat resolution, remote replies ─────────

    @Test fun consentIsAuditedOnlyWhenItChanges() {
        val rt = runtime()
        rt.post(env("settings.set", """{"key":"autonomy.consent","value":false}""")) // never granted: no-op audit
        rt.post(env("settings.set", """{"key":"autonomy.consent","value":true}"""))
        rt.post(env("settings.set", """{"key":"autonomy.consent","value":true}""")) // shell re-sync
        rt.post(env("system.status"))
        assertEquals(true, out.ofType("system.status.result").single().p()["autonomyConsent"]?.let { (it as com.agentkeyboard.core.json.JsonBool).value })
        rt.post(env("settings.set", """{"key":"autonomy.consent","value":false}"""), AgentRuntime.ORIGIN_COMPANION) // revoke from the companion
        assertFalse(rt.autonomyConsented())
        assertEquals(listOf("consent_granted", "consent_revoked"), audit.rows.map { it.action })
    }

    @Test fun repliesAndRunEventsGoOnlyToTheOriginatingClient() {
        val rt = runtime(ScriptedEngine(listOf("Hi")))
        rt.post(env("agents.list"), AgentRuntime.ORIGIN_COMPANION)
        assertEquals(AgentRuntime.ORIGIN_COMPANION, out.targetOf("agents.list.result"))
        rt.focusChanged(field(sensitive = true)) // the keyboard is in a password field…
        rt.post(run("c1"), AgentRuntime.ORIGIN_COMPANION) // …which must not block a companion run
        assertEquals("c1", out.ofType("agent.done").single().p()["runId"].str())
        assertEquals(AgentRuntime.ORIGIN_COMPANION, out.targetOf("agent.token"))
        assertEquals(AgentRuntime.ORIGIN_COMPANION, out.targetOf("agent.done"))
        rt.post(run("i1")) // an IME run in that field is refused, and only the IME hears about it
        assertEquals("policy", out.ofType("agent.error").single().p()["code"].str())
        assertEquals(AgentRuntime.ORIGIN_IME, out.targetOf("agent.error"))
        rt.post(env("agents.delete", """{"id":"agent_x"}"""))
        assertNull(out.targetOf("agents.changed"), "shared state is broadcast")
        rt.post(env("context.snapshot", """{"scope":"screen"}"""), AgentRuntime.ORIGIN_COMPANION)
        assertEquals(AgentRuntime.ORIGIN_COMPANION, out.targetOf("error"))
    }

    @Test fun rungOneResolvesTheChatOnScreenAndNeverDraftsFromAGuess() {
        val engine = ScriptedEngine(listOf("ok"), needsForeground = false)
        val rt = runtime(engine)
        rt.onNotification("com.whatsapp", "Ama", listOf(ChatMessage("1", false, "Ama", "Price?", 100)), true)
        now += 1_000
        rt.onNotification("com.whatsapp", "Kofi", listOf(ChatMessage("2", false, "Kofi", "Deliver?", 200)), true)
        rt.focusChanged(field())

        rt.post(env("context.snapshot", """{"scope":"screen","titleHint":"‪Ama‬ 🟢"}"""))
        val exact = out.ofType("context.snapshot.result").last().p()["chat"].objOrNull()!!
        assertEquals("Ama", exact["title"].str())
        assertEquals("exact", exact["match"].str())

        rt.post(env("context.snapshot", """{"scope":"screen"}"""))
        val guess = out.ofType("context.snapshot.result").last().p()["chat"].objOrNull()!!
        assertEquals("Kofi", guess["title"].str()) // shown, with its title, but flagged
        assertEquals("uncertain", guess["match"].str())

        rt.post(run("r1"))
        assertNull(engine.lastJob!!.context.conversation, "two recent chats and no title: no conversation context")
        rt.post(env("agent.run", """{"runId":"r2","agentId":"general","skill":"reply","target":"conversation","params":{"conversationTitle":"Ama","screen":{"text":"Ama: Price? card 4111 1111 1111 1111","source":"a11y"}}}"""))
        val ctx = engine.lastJob!!.context
        assertEquals("Ama", ctx.conversation?.title)
        assertEquals("a11y", ctx.screenSource)
        assertFalse(ctx.screenText!!.contains("4111 1111"), "screen text is redacted again in :agent")

        now += 60 * 60 * 1000L // both chats go quiet; then only Ama writes again → she is the likely chat
        rt.onNotification("com.whatsapp", "Ama", listOf(ChatMessage("3", false, "Ama", "Hello?", 300)), true)
        rt.post(env("context.snapshot", """{"scope":"screen"}"""))
        assertEquals("likely", out.ofType("context.snapshot.result").last().p()["chat"].objOrNull()!!["match"].str())
    }

    @Test fun remoteRepliesNeedConsentAndAKnownConversation() {
        val rt = runtime()
        rt.onNotification("com.whatsapp", "Ama", listOf(ChatMessage("1", false, "Ama", "Price?", 100)), true)
        val key = com.agentkeyboard.core.context.ConversationCache.keyOf("com.whatsapp", "Ama")
        rt.remoteReply("q1", key, "GH₵ 250")
        rt.post(env("settings.set", """{"key":"autonomy.consent","value":true}"""))
        rt.remoteReply("q2", "com.whatsapp|stranger", "hi")
        rt.remoteReply("q3", key, "GH₵ 250")
        assertEquals(listOf("q1" to false, "q2" to false, "q3" to true), out.replyResults)
        assertEquals(listOf(key to "GH₵ 250"), sentReplies)
    }

    @Test fun confidenceAcceptsStringsAndNumbersAndFailsLow() {
        fun c(json: String) = AgentRuntime.confidenceOf(Json.parse(json))
        assertEquals(0.9, c("""{"confidence":"0.9"}"""))
        assertEquals(0.75, c("""{"confidence":0.75}"""))
        assertEquals(0.0, c("""{"confidence":"high"}"""))
        assertEquals(0.0, c("""{"confidence":7}"""))
        assertEquals(0.0, c("""{}"""))
        assertEquals(0.0, AgentRuntime.confidenceOf(null))
    }
}
