package com.agentkeyboard.core.protocol

import com.agentkeyboard.core.json.JsonValue
import com.agentkeyboard.core.json.obj

/** Native-side handlers. Implemented by the IME host and by the companion plugin. */
interface BridgeHost {
    /** The shell sent a valid command that passed the secure-field gate. */
    fun onCommand(command: Command)

    /** Answer exactly once through [reply]; may complete asynchronously (e.g. after an AIDL call). */
    fun onRequest(request: Request, reply: Reply)

    /** The shell speaks another protocol major. The IME host swaps to the fallback keyboard (SPEC §8.1). */
    fun onProtocolMismatch(version: Long?)

    /** Evaluated per message so a focus change applies to the very next message. */
    fun isSecureField(): Boolean

    /** Observability hook (SPEC §18): dropped/rejected traffic, never message content. */
    fun onRejected(type: String?, code: ErrorCode) {}
}

/** One-shot responder. A second completion is a programming error, and is ignored rather than resent. */
class Reply internal constructor(private val send: (Envelope) -> Unit, private val ids: IdGenerator, private val clock: () -> Long, private val type: String, private val re: String) {
    @Volatile private var done = false
    val isDone: Boolean get() = done

    fun ok(result: JsonValue) = complete("$type.result", result)

    fun fail(code: ErrorCode, message: String) = complete("error", obj("code" to code.wire, "message" to message))

    private fun complete(replyType: String, payload: JsonValue) {
        synchronized(this) { if (done) return; done = true }
        send(Envelope(PROTOCOL_VERSION, ids.next(), replyType, clock(), re, payload))
    }

    companion object {
        /** A reply that goes nowhere: for host-internal requests whose result is consumed natively. */
        fun detached(): Reply = Reply({}, IdGenerator("x"), { 0L }, "internal", "internal")
    }
}

/**
 * Transport-agnostic router for the v1 envelope protocol (SPEC §8).
 *
 * Contract:
 *  - Never throws into the transport. Malformed input is dropped (and counted); a request that can be
 *    correlated always gets a `<type>.result` or `error` reply.
 *  - Version mismatch → `error{code:"version"}` + [BridgeHost.onProtocolMismatch].
 *  - Secure-field enforcement happens here, natively, before any handler runs (FR-KB-14).
 *  - Confined to one thread (the IME main thread); [emit] callers on other threads must hop first.
 */
class BridgeRouter(
    private val host: BridgeHost,
    private val sink: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: IdGenerator = IdGenerator(),
) {
    /** True once the shell has sent `hello`; the IME keeps the fallback keyboard up until then. */
    var shellReady: Boolean = false
        private set

    private val sendEnvelope: (Envelope) -> Unit = { env -> sink(env.encode()) }

    fun receive(raw: String) {
        val env = when (val d = EnvelopeCodec.decode(raw)) {
            is Decoded.Ok -> d.envelope
            is Decoded.Malformed -> { host.onRejected(null, ErrorCode.BAD_PAYLOAD); return }
            is Decoded.VersionMismatch -> {
                host.onRejected(null, ErrorCode.VERSION)
                d.id?.let { sendEnvelope(errorEnvelope(it, ErrorCode.VERSION, "native speaks protocol v$PROTOCOL_VERSION")) }
                host.onProtocolMismatch(d.version)
                return
            }
        }
        val type = env.type
        when (type) {
            in Catalog.REQUESTS -> handleRequest(env)
            in Catalog.COMMANDS -> handleCommand(env)
            else -> {
                host.onRejected(type, ErrorCode.UNKNOWN_TYPE)
                // Commands are fire-and-forget, but answering lets a newer shell detect an older host.
                sendEnvelope(errorEnvelope(env.id, ErrorCode.UNKNOWN_TYPE, "unknown type '$type'"))
            }
        }
    }

    private fun handleCommand(env: Envelope) {
        val cmd = try {
            MessageDecoder.command(env.type, env.payload)
        } catch (f: BridgeFailure) {
            host.onRejected(env.type, f.code); return
        }
        if (cmd.forbiddenInSecureField && host.isSecureField()) {
            host.onRejected(env.type, ErrorCode.SECURE_FIELD); return
        }
        try {
            host.onCommand(cmd)
        } catch (e: RuntimeException) {
            host.onRejected(env.type, ErrorCode.INTERNAL)
        }
    }

    private fun handleRequest(env: Envelope) {
        val reply = Reply(sendEnvelope, ids, clock, env.type, env.id)
        val req = try {
            MessageDecoder.request(env.type, env.payload)
        } catch (f: BridgeFailure) {
            host.onRejected(env.type, f.code)
            reply.fail(f.code, f.message ?: f.code.wire); return
        }
        if (req.forbiddenInSecureField && host.isSecureField()) {
            host.onRejected(env.type, ErrorCode.SECURE_FIELD)
            reply.fail(ErrorCode.SECURE_FIELD, "Not available in secure fields"); return
        }
        if (req is Request.Hello) shellReady = true
        try {
            host.onRequest(req, reply)
        } catch (e: RuntimeException) {
            host.onRejected(env.type, ErrorCode.INTERNAL)
            reply.fail(ErrorCode.INTERNAL, "internal error")
        }
        // Events held back while the page was loading go out after the hello reply and its replay.
        if (req is Request.Hello) flushEarly()
    }

    /** Events that arrived before `hello`, oldest first (bounded; see [emit]). */
    private val early = ArrayDeque<Envelope>()

    /**
     * Push a native event. Unknown types are a programming error and fail fast in tests.
     *
     * Before the shell's `hello` the page may not be listening yet, so an event posted now could be
     * lost (e.g. an `agent.done` that lands while the renderer restarts). State the `hello` handler
     * replays anyway ([REPLAYED_ON_HELLO]) is dropped; everything else is held (≤ [MAX_EARLY]) and
     * delivered right after the `hello` reply.
     */
    fun emit(type: String, payload: JsonValue) {
        require(type in Catalog.EVENTS) { "not a v1 event: $type" }
        val env = Envelope(PROTOCOL_VERSION, ids.next(), type, clock(), null, payload)
        if (shellReady) return sendEnvelope(env)
        if (type in REPLAYED_ON_HELLO) return
        if (early.size >= MAX_EARLY) early.removeFirst()
        early.addLast(env)
    }

    private fun flushEarly() {
        while (early.isNotEmpty()) sendEnvelope(early.removeFirst())
    }

    /** Called when the WebView renderer is recreated: the new page must say `hello` again. */
    fun reset() {
        shellReady = false
    }

    companion object {
        /** The IME re-sends these from current state on every `hello` (editor binding, phase, theme). */
        val REPLAYED_ON_HELLO = setOf("editor.start", "editor.state", "editor.finish", "autonomy.phase", "theme.update", "screen.state")
        const val MAX_EARLY = 64
    }

    private fun errorEnvelope(re: String, code: ErrorCode, message: String) =
        Envelope(PROTOCOL_VERSION, ids.next(), "error", clock(), re, obj("code" to code.wire, "message" to message))
}

/**
 * Coalesces `agent.token` deltas to ≤ 1 event per [intervalMs] per run (SPEC §8.3), so a fast model
 * cannot flood the WebView's main thread. [flushNow] must be called before `agent.done`/`agent.error`
 * so the final text never overtakes its tokens.
 */
class TokenCoalescer(
    private val intervalMs: Long = 32,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    private val emit: (runId: String, delta: String) -> Unit,
) {
    private val pending = LinkedHashMap<String, StringBuilder>()
    private var scheduled = false

    fun append(runId: String, delta: String) {
        if (delta.isEmpty()) return
        pending.getOrPut(runId) { StringBuilder() }.append(delta)
        if (!scheduled) {
            scheduled = true
            schedule(intervalMs) { scheduled = false; flushNow() }
        }
    }

    fun flushNow() {
        if (pending.isEmpty()) return
        val drained = pending.entries.map { it.key to it.value.toString() }
        pending.clear()
        drained.forEach { (id, d) -> emit(id, d) }
    }

    fun discard(runId: String) {
        pending.remove(runId)
    }
}
