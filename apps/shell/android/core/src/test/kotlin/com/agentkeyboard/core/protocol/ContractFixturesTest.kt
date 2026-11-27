package com.agentkeyboard.core.protocol

import com.agentkeyboard.core.Fixtures
import com.agentkeyboard.core.json.JsonObj
import com.agentkeyboard.core.json.arrOrNull
import com.agentkeyboard.core.json.long
import com.agentkeyboard.core.json.obj
import com.agentkeyboard.core.json.objOrNull
import com.agentkeyboard.core.json.str
import com.agentkeyboard.core.json.toJson
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Replays `packages/protocol/fixtures/contract.json` (generated from the typed TS source) against the
 * native codec. Fails when either side changes a message shape without the other (SPEC §19.1).
 */
class ContractFixturesTest {
    private val contract = Fixtures.json("contract.json") as JsonObj

    private fun names(key: String) = contract["catalog"].objOrNull()!![key].arrOrNull()!!.items.map { it.str()!! }.toSet()

    @Test fun catalogMatchesTypeScript() {
        assertEquals(PROTOCOL_VERSION.toLong(), contract["protocol"].long())
        assertEquals(names("commands"), Catalog.COMMANDS)
        assertEquals(names("requests"), Catalog.REQUESTS)
        assertEquals(names("events"), Catalog.EVENTS)
        assertEquals(names("errorCodes"), Catalog.ERROR_CODES)
        assertEquals(Catalog.ERROR_CODES, ErrorCode.entries.map { it.wire }.toSet())
    }

    @Test fun everyCommandDecodesLosslessly() {
        val commands = contract["commands"] as JsonObj
        for ((type, payload) in commands.fields) {
            val cmd = MessageDecoder.command(type, payload)
            assertEquals(payload, CommandEncoder.encode(cmd), "round-trip of $type")
            assertEquals(type, CommandEncoder.typeOf(cmd))
        }
    }

    @Test fun everyRequestDecodes() {
        val requests = contract["requests"] as JsonObj
        for ((type, pair) in requests.fields) {
            MessageDecoder.request(type, (pair as JsonObj)["req"]!!)
        }
    }

    /** Responses and events built from Kotlin domain types must serialise to exactly the TS fixture. */
    @Test fun nativeResponsesMatchFixtures() {
        val res = { t: String -> ((contract["requests"] as JsonObj)[t] as JsonObj)["res"] }
        val hello = Capabilities(
            platform = "android-ime", tier = DeviceTier.S, nativeSuggestions = false, voice = false,
            tts = true, screenContext = true, visualContext = true, autonomy = true, screenCapture = true, screenScan = true,
        )
        assertEquals(res("hello"), hello.toJson())
        val status = SystemStatus(true, false, false, false, "play", DeviceTier.S, 3712, false)
        assertEquals(res("system.status"), status.toJson())
        val clips = listOf(
            ClipboardItem("clip_7", "Kejetia Market, Kumasi", true, 1764200000000),
            ClipboardItem("clip_8", "See you at 5", false, 1764204000000),
        )
        assertEquals(res("clipboard.list"), toJson(clips))

        // Agents: decode the TS fixture into Kotlin and re-encode → identical JSON.
        val agents = res("agents.list").arrOrNull()!!.items
        for (a in agents) {
            val (def, err) = AgentDefinition.fromJson(a)
            assertNotNull(def, "agent decode failed at $err")
            assertEquals(a, def.toJson())
            assertEquals(emptyList(), validateAgent(def))
        }

        val field = FieldContext("com.whatsapp", "WhatsApp", InputKind.MULTILINE, ImeActionKind.NEWLINE, CapsMode.SENTENCES, hint = "Message", sensitive = false)
        val snapshot = ScreenContext(
            appPackage = "com.whatsapp", appLabel = "WhatsApp", category = "chat", screenType = "chat", field = field,
            chat = ChatContext("Kofi", listOf("Kofi"), listOf(ChatMessage("m1", false, "Kofi", "Do you deliver to Kumasi?", 1764205000000)), ChatContext.LIKELY),
            sensitivity = SensitivityFlags(false, false, false), rung = 1, capturedAt = 1764205001000, ttlMs = 30000,
        )
        assertEquals(res("context.snapshot"), snapshot.toJson())

        val chrome = FieldContext("com.android.chrome", "Chrome", InputKind.TEXT, ImeActionKind.GO, CapsMode.NONE, hint = "Search", sensitive = false)
        val scan = ScreenContext(
            appPackage = "com.android.chrome", appLabel = "Chrome", category = "browser", screenType = "article", field = chrome,
            text = "Sunny, 31°C\nLight wind from the south-west", textTitle = "Kumasi weather",
            images = listOf(ScreenImage("f_12", 432, 720)), sensitivity = SensitivityFlags(false, false, false),
            scan = ScanInfo(3, complete = true, restored = true), rung = 3, capturedAt = 1764205001000, ttlMs = 30000,
        )
        assertEquals(res("context.scan"), scan.toJson())
    }

    @Test fun nativeEventsMatchFixtures() {
        val ev = contract["events"] as JsonObj
        val field = FieldContext(
            "com.android.chrome", "Chrome", InputKind.EMAIL, ImeActionKind.NEXT, CapsMode.NONE,
            hint = "Email", autofillHints = listOf("emailAddress"), sensitive = false,
        )
        assertEquals(ev["editor.start"], obj("field" to field, "state" to EditorState("ama@", "", "")))
        assertEquals(ev["editor.state"], EditorState("Hello wor", "", "").toJson())
        assertEquals(ev["editor.finish"], obj())
        assertEquals(
            ev["suggestions.update"],
            obj("items" to listOf(Suggestion("world", "completion", true), Suggestion("word", "correction"))),
        )
        assertEquals(ev["autonomy.phase"], obj("phase" to AutonomyPhase.PAUSED.wire, "detail" to "Off in secure fields", "consent" to true))
        assertEquals(ev["screen.state"], obj("status" to "paused", "detail" to "Paused in protected fields"))
        assertEquals(ev["agent.error"], obj("runId" to "run_42", "code" to "unavailable", "message" to "AI models are not installed yet."))
        assertEquals(ev["agent.token"], obj("runId" to "run_42", "delta" to "Dear "))
        assertEquals(ev["voice.end"], obj())
        // Every event name the router can emit is in the TS catalog.
        for (type in ev.fields.keys) assertTrue(type in Catalog.EVENTS)
    }

    @Test fun rejectedFixturesAreRejectedWithTheirCode() {
        for (case in contract["rejected"].arrOrNull()!!.items) {
            val c = case as JsonObj
            val name = c["name"].str()!!
            val expected = c["code"].str()!!
            val sent = mutableListOf<String>()
            val rejected = mutableListOf<ErrorCode>()
            var commands = 0
            val router = BridgeRouter(
                object : BridgeHost {
                    override fun onCommand(command: Command) { commands++ }
                    override fun onRequest(request: Request, reply: Reply) = reply.ok(obj())
                    override fun onProtocolMismatch(version: Long?) {}
                    override fun isSecureField() = false
                    override fun onRejected(type: String?, code: ErrorCode) { rejected += code }
                },
                sink = { sent += it },
            )
            router.receive(c["raw"].str()!!)
            assertEquals(0, commands, "$name must not reach a handler")
            when (expected) {
                "dropped" -> assertEquals(listOf(ErrorCode.BAD_PAYLOAD), rejected, name)
                else -> assertEquals(expected, rejected.single().wire, name)
            }
            // A correlatable *request* must always be answered; commands are logged, not answered (except unknown types).
            sent.forEach { raw ->
                val env = (EnvelopeCodec.decode(raw) as Decoded.Ok).envelope
                assertEquals("error", env.type, name)
                assertEquals(expected, env.payload.objOrNull()!!["code"].str(), name)
            }
        }
    }
}
