package com.agentkeyboard.app.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.core.protocol.PROTOCOL_VERSION

/**
 * Client side of the IME/companion → `:agent` link (ADR-0003, SPEC §9 "lazy AIDL bind with backoff").
 *
 *  - Binds on first use, not at IME start, so typing never pays for the second process. Focus
 *    changes alone never bind: the latest focus is remembered and delivered first on connect, so
 *    `:agent`'s secure-field gate always sees the current field before any request.
 *  - Envelopes posted while (re)connecting are queued (bounded) and flushed in order on connect.
 *  - On disconnect/binder death it rebinds with exponential backoff (250 ms → 8 s), and reports
 *    [Listener.onAgentUnavailable] so callers can fail pending requests instead of hanging until the
 *    shell's 5 s timeout.
 *  - Callbacks are delivered on the main thread.
 */
class AgentClient(private val context: Context, private val listener: Listener, private val origin: String) {
    interface Listener {
        fun onAgentEnvelope(raw: String)
        fun onAgentInbound(packageName: String, conversationKey: String, messageJson: String, remoteInputAvailable: Boolean)
        fun onAgentRemoteReplyResult(requestId: String, ok: Boolean) {}

        /**
         * The link dropped, or `:agent` speaks another protocol major. Everything waiting on it has
         * been discarded: callers must fail their pending requests now instead of waiting for timeouts.
         */
        fun onAgentUnavailable(queued: List<String>) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var service: IAgentService? = null
    private var binding = false
    private var backoffMs = INITIAL_BACKOFF_MS
    private var closed = false
    private val queue = ArrayDeque<() -> Unit>()
    private var lastFocus: String? = null

    /**
     * Keep the link up even with nothing queued (e.g. while autonomy is armed and must hear inbound
     * messages): binds now, and rebinds after a disconnect instead of waiting for the next request.
     */
    var sticky: Boolean = false
        set(value) {
            field = value
            if (value) ensureBound()
        }

    private val callback = object : IAgentCallback.Stub() {
        override fun onEnvelope(envelopeJson: String) {
            main.post { listener.onAgentEnvelope(envelopeJson) }
        }

        override fun onInbound(packageName: String, conversationKey: String, messageJson: String, remoteInputAvailable: Boolean) {
            main.post { listener.onAgentInbound(packageName, conversationKey, messageJson, remoteInputAvailable) }
        }

        override fun onRemoteReplyResult(requestId: String, ok: Boolean) {
            main.post { listener.onAgentRemoteReplyResult(requestId, ok) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = IAgentService.Stub.asInterface(binder)
            try {
                if (s.protocolVersion() != PROTOCOL_VERSION) {
                    // Can't happen within one APK, but if it ever does: fail everything now and stop
                    // retrying (a rebind would meet the same service) rather than letting requests hang.
                    AkLog.w("agent protocol mismatch"); unbindQuietly(); drain(); return
                }
                s.register(callback, origin)
                lastFocus?.let(s::focusChanged)
            } catch (e: RemoteException) {
                onLost(); return
            }
            service = s
            binding = false
            backoffMs = INITIAL_BACKOFF_MS
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        }

        override fun onServiceDisconnected(name: ComponentName) = onLost()

        override fun onBindingDied(name: ComponentName) {
            unbindQuietly(); onLost()
        }

        override fun onNullBinding(name: ComponentName) {
            AkLog.w("agent returned null binding"); unbindQuietly()
        }
    }

    val isConnected: Boolean get() = service != null

    fun post(envelopeJson: String) = call { it.post(envelopeJson, origin) }

    fun remoteReply(requestId: String, conversationKey: String, text: String) = call { it.remoteReply(requestId, conversationKey, text) }

    fun recordClipboard(text: String, sensitiveSource: Boolean) = call { it.recordClipboard(text, sensitiveSource) }

    fun focusChanged(fieldJson: String) {
        lastFocus = fieldJson
        val s = service ?: return // delivered on connect
        try {
            s.focusChanged(fieldJson)
        } catch (e: RemoteException) {
            onLost()
        }
    }

    fun appendAudit(auditJson: String) = call { it.appendAudit(auditJson) }

    private fun call(block: (IAgentService) -> Unit) {
        if (closed) return
        val s = service
        if (s != null) {
            try {
                block(s); return
            } catch (e: RemoteException) {
                onLost()
            }
        }
        if (queue.size >= MAX_QUEUE) queue.removeFirst()
        queue.addLast { service?.let { svc -> try { block(svc) } catch (_: RemoteException) { onLost() } } }
        ensureBound()
    }

    fun ensureBound() {
        if (closed || service != null || binding) return
        binding = true
        val ok = try {
            context.bindService(Intent(context, AgentService::class.java), connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        } catch (e: SecurityException) {
            false
        }
        if (!ok) {
            binding = false
            scheduleRebind()
        }
    }

    private fun onLost() {
        val hadService = service != null
        service = null
        binding = false
        if (hadService) AkLog.w("agent process disconnected")
        drain()
        scheduleRebind()
    }

    /**
     * Queued calls are dropped, not replayed after a reconnect: their requests are failed right now,
     * and replaying a stale `agent.run` minutes later would surprise the user.
     */
    private fun drain() {
        queue.clear()
        listener.onAgentUnavailable(emptyList())
    }

    private fun scheduleRebind() {
        if (closed) return
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        main.postDelayed({ if (queue.isNotEmpty() || sticky) ensureBound() }, delay)
    }

    private fun unbindQuietly() {
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
        }
        binding = false
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        try {
            service?.unregister(callback)
        } catch (_: RemoteException) {
        }
        service = null
        queue.clear()
        unbindQuietly()
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 250L
        const val MAX_BACKOFF_MS = 8_000L
        const val MAX_QUEUE = 64
    }
}
