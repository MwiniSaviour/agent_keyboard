package com.agentkeyboard.core.protocol

import com.agentkeyboard.core.json.JsonObj
import com.agentkeyboard.core.json.obj
import com.agentkeyboard.core.json.objOrNull
import com.agentkeyboard.core.json.str
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeRouterTest {
    private class FakeHost : BridgeHost {
        var secure = false
        val commands = mutableListOf<Command>()
        val rejected = mutableListOf<Pair<String?, ErrorCode>>()
        var mismatch: Long? = null
        var deferred: Reply? = null
        override fun onCommand(command: Command) { commands += command }
        override fun onRequest(request: Request, reply: Reply) {
            when (request) {
                is Request.Hello -> reply.ok(obj("protocol" to 1))
                is Request.ClipboardList -> deferred = reply // answered later, like an AIDL round trip
                is Request.AgentsList -> throw IllegalStateException("boom")
                else -> reply.ok(obj())
            }
        }
        override fun onProtocolMismatch(version: Long?) { mismatch = version }
        override fun isSecureField() = secure
        override fun onRejected(type: String?, code: ErrorCode) { rejected += type to code }
    }

    private val host = FakeHost()
    private val out = mutableListOf<Envelope>()
    private val router = BridgeRouter(host, sink = { out += (EnvelopeCodec.decode(it) as Decoded.Ok).envelope }, clock = { 1000L })

    private fun env(id: String, type: String, payload: String) = """{"v":1,"id":"$id","type":"$type","ts":1,"payload":$payload}"""

    @Test fun commandsReachTheHostInOrder() {
        router.receive(env("s:1", "text.commit", """{"text":"a"}"""))
        router.receive(env("s:2", "text.delete", """{"before":1,"after":0}"""))
        assertEquals(listOf(Command.Commit("a"), Command.Delete(1, 0)), host.commands)
        assertTrue(out.isEmpty(), "commands are fire-and-forget")
    }

    @Test fun requestsAreAnsweredWithCorrelatedResult() {
        assertFalse(router.shellReady)
        router.receive(env("s:9", "hello", """{"shellVersion":"0.1.0"}"""))
        assertTrue(router.shellReady)
        val r = out.single()
        assertEquals("hello.result", r.type)
        assertEquals("s:9", r.re)
        assertTrue(r.id.startsWith("n:"))
        assertEquals(1000L, r.ts)
    }

    @Test fun asyncRepliesCompleteOnceOnly() {
        router.receive(env("s:3", "clipboard.list", "{}"))
        assertTrue(out.isEmpty())
        host.deferred!!.ok(obj())
        host.deferred!!.fail(ErrorCode.INTERNAL, "late") // ignored
        assertEquals(listOf("clipboard.list.result"), out.map { it.type })
    }

    @Test fun handlerExceptionsBecomeInternalErrorsNotCrashes() {
        router.receive(env("s:4", "agents.list", "{}"))
        val e = out.single()
        assertEquals("error", e.type)
        assertEquals("internal", (e.payload as JsonObj)["code"].str())
    }

    @Test fun secureFieldDropsAiTrafficNatively() {
        host.secure = true
        router.receive(env("s:5", "agent.run", """{"runId":"r","agentId":"general","skill":"refine","target":"field"}"""))
        router.receive(env("s:6", "autonomy.set", """{"enabled":true,"level":"auto","agentId":"general"}"""))
        router.receive(env("s:7", "form.fill", """{"values":{}}"""))
        router.receive(env("s:8", "voice.start", """{"refine":true}"""))
        router.receive(env("s:9", "context.snapshot", """{"scope":"screen"}"""))
        // Typing, turning autonomy OFF and plain dictation still work.
        router.receive(env("s:10", "text.commit", """{"text":"x"}"""))
        router.receive(env("s:11", "autonomy.set", """{"enabled":false,"level":"off","agentId":"general"}"""))
        router.receive(env("s:12", "voice.start", """{"refine":false}"""))
        assertEquals(
            listOf(Command.Commit("x"), Command.AutonomySet(false, AutonomyLevel.OFF, "general"), Command.VoiceStart(false)),
            host.commands,
        )
        assertEquals(5, host.rejected.count { it.second == ErrorCode.SECURE_FIELD })
        assertEquals("secure_field", out.single().payload.objOrNull()!!["code"].str())
    }

    @Test fun versionMismatchRepliesAndNotifiesHost() {
        router.receive("""{"v":2,"id":"s:1","type":"hello","ts":1,"payload":{}}""")
        assertEquals(2L, host.mismatch)
        assertEquals("version", out.single().payload.objOrNull()!!["code"].str())
        assertFalse(router.shellReady)
    }

    @Test fun emitRejectsTypesOutsideTheCatalog() {
        router.receive(env("s:1", "hello", """{"shellVersion":"0.1.0"}"""))
        out.clear()
        router.emit("editor.finish", obj())
        assertEquals("editor.finish", out.single().type)
        assertFailsWith<IllegalArgumentException> { router.emit("editor.explode", obj()) }
    }

    @Test fun eventsBeforeHelloAreHeldThenDeliveredAfterTheReply() {
        router.emit("agent.done", obj("runId" to "r1", "text" to "hi"))
        router.emit("editor.start", obj()) // replayed by the hello handler itself: dropped, not duplicated
        router.emit("agents.changed", obj())
        assertTrue(out.isEmpty(), "nothing reaches a page that is not listening yet")
        router.receive(env("s:1", "hello", """{"shellVersion":"0.1.0"}"""))
        assertEquals(listOf("hello.result", "agent.done", "agents.changed"), out.map { it.type })
        out.clear()
        router.reset() // renderer restart: hold again
        router.emit("agent.error", obj())
        assertTrue(out.isEmpty())
    }

    @Test fun earlyEventBufferIsBounded() {
        repeat(BridgeRouter.MAX_EARLY + 10) { router.emit("agent.token", obj("runId" to "r", "delta" to "$it")) }
        router.receive(env("s:1", "hello", """{"shellVersion":"0.1.0"}"""))
        val tokens = out.filter { it.type == "agent.token" }
        assertEquals(BridgeRouter.MAX_EARLY, tokens.size)
        assertEquals("10", tokens.first().payload.objOrNull()!!["delta"].str(), "oldest are dropped first")
    }

    @Test fun scanAndCaptureAreRefusedInSecureFields() {
        host.secure = true
        router.receive(env("s:1", "context.scan", """{"direction":"up","pages":3}"""))
        router.receive(env("s:2", "screen.capture", """{"enabled":true}"""))
        router.receive(env("s:3", "screen.capture", """{"enabled":false}""")) // stopping is always allowed
        assertEquals(listOf<Command>(Command.ScreenCapture(false)), host.commands)
        assertEquals("secure_field", out.single().payload.objOrNull()!!["code"].str())
    }

    @Test fun oversizedMessagesAreDropped() {
        router.receive(env("s:1", "text.commit", """{"text":"${"a".repeat(EnvelopeCodec.MAX_MESSAGE_CHARS)}"}"""))
        assertTrue(host.commands.isEmpty())
        assertEquals(ErrorCode.BAD_PAYLOAD, host.rejected.single().second)
    }

    @Test fun textLongerThanTheCapIsRejected() {
        router.receive(env("s:1", "text.commit", """{"text":"${"a".repeat(MAX_TEXT_CHARS + 1)}"}"""))
        assertTrue(host.commands.isEmpty())
    }

    @Test fun resetRequiresANewHello() {
        router.receive(env("s:1", "hello", """{"shellVersion":"0.1.0"}"""))
        router.reset()
        assertFalse(router.shellReady)
    }

    @Test fun tokenCoalescerBatchesPerIntervalAndFlushesBeforeDone() {
        val scheduled = mutableListOf<() -> Unit>()
        val emitted = mutableListOf<Pair<String, String>>()
        val c = TokenCoalescer(32, schedule = { _, b -> scheduled += b }, emit = { id, d -> emitted += id to d })
        c.append("r1", "He"); c.append("r1", "llo"); c.append("r2", "x"); c.append("r1", "")
        assertEquals(1, scheduled.size, "one timer per window")
        scheduled.single()()
        assertEquals(listOf("r1" to "Hello", "r2" to "x"), emitted)
        c.append("r1", " world")
        c.flushNow() // agent.done path
        assertEquals("r1" to " world", emitted.last())
        c.append("r3", "zzz"); c.discard("r3"); c.flushNow()
        assertEquals(3, emitted.size)
    }
}
