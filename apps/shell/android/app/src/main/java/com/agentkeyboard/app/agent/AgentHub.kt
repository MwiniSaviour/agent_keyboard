package com.agentkeyboard.app.agent

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.RemoteCallbackList
import android.os.RemoteException
import com.agentkeyboard.app.Ak
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.app.storage.AkDatabase
import com.agentkeyboard.app.storage.SqlAgentStore
import com.agentkeyboard.app.storage.SqlAuditStore
import com.agentkeyboard.app.storage.SqlClipboardStore
import com.agentkeyboard.app.storage.SqlSettingsStore
import com.agentkeyboard.app.system.SystemSettings
import com.agentkeyboard.core.agent.AgentOutput
import com.agentkeyboard.core.agent.AgentRuntime
import com.agentkeyboard.core.agent.NoModelEngine
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Process singleton for `:agent`. It owns the [AgentRuntime] independently of any one component's
 * lifecycle, because `AgentService` (bound by the IME) and `AkNotificationListener` (bound by the
 * system) live and die separately but must share one conversation cache and one database.
 *
 * Threading: all runtime access happens on [handler]'s thread (`ak-agent`). Inference runs on a
 * single background-priority thread, so at most one model works at a time (ADR-0004).
 */
object AgentHub {
    private val thread = HandlerThread("ak-agent", Process.THREAD_PRIORITY_DEFAULT).apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val serial = Executor { handler.post(it) }
    private val engineThread = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "ak-engine")
    }

    val callbacks = RemoteCallbackList<IAgentCallback>()

    /** Set by `AgentService` while it exists; receives foreground on/off on the main thread. */
    @Volatile var foreground: ((Boolean) -> Unit)? = null

    private var runtime: AgentRuntime? = null // ak-agent thread only
    private var speech: PlatformSpeech? = null

    /** Runs [block] on the agent thread with a started runtime. Never blocks the caller. */
    fun post(block: (AgentRuntime) -> Unit) {
        handler.post {
            val rt = runtime ?: create().also { runtime = it }
            try {
                block(rt)
            } catch (e: RuntimeException) {
                AkLog.w("agent task failed: ${e::class.java.simpleName}")
            }
        }
    }

    fun onTrimMemory() = handler.post { speech?.shutdown() }

    private fun create(): AgentRuntime {
        val t0 = System.nanoTime()
        val ctx = Ak.app
        val db = AkDatabase.open(ctx).writableDatabase
        val sp = PlatformSpeech(ctx, handler).also { speech = it }
        val output = object : AgentOutput {
            override fun envelope(raw: String) = broadcast { it.onEnvelope(raw) }
            override fun inbound(packageName: String, conversationKey: String, messageJson: String, remoteInputAvailable: Boolean) =
                broadcast { it.onInbound(packageName, conversationKey, messageJson, remoteInputAvailable) }
        }
        return AgentRuntime(
            agents = SqlAgentStore(db),
            clipboard = SqlClipboardStore(db),
            audit = SqlAuditStore(db),
            settings = SqlSettingsStore(db),
            speech = sp,
            engine = NoModelEngine, // Part 3: LiteRT-LM engine chosen by Ak.tier
            output = output,
            status = { SystemSettings.status(ctx, modelsInstalled = false) },
            engineExecutor = engineThread,
            serial = serial,
            onBusyChanged = { busy ->
                main.post {
                    val service = foreground
                    when {
                        service != null -> service(busy)
                        busy -> AgentService.startForegroundCompat(ctx) // its onStartCommand promotes it
                    }
                }
            },
            log = AkLog::d,
        ).also {
            it.start()
            AkLog.i("agent runtime ready in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    /** Callbacks are `oneway`, so a slow or dead IME can never stall this thread. */
    private inline fun broadcast(crossinline send: (IAgentCallback) -> Unit) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    send(callbacks.getBroadcastItem(i))
                } catch (_: RemoteException) {
                    // Dead client: RemoteCallbackList prunes it via its death recipient.
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }
}
