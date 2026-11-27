package com.agentkeyboard.app.agent

import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.ParcelFileDescriptor
import com.agentkeyboard.app.Ak
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.app.BuildConfig
import com.agentkeyboard.app.models.AndroidDevice
import com.agentkeyboard.app.models.AndroidHttp
import com.agentkeyboard.app.models.AndroidModelFiles
import com.agentkeyboard.app.models.AndroidModelScanner
import com.agentkeyboard.app.models.AndroidSpecs
import com.agentkeyboard.app.models.SttPointer
import com.agentkeyboard.app.models.AndroidNetwork
import com.agentkeyboard.app.models.LiteRtLmRuntime
import com.agentkeyboard.app.models.PrefsProbeStore
import com.agentkeyboard.app.notify.AkNotificationListener
import com.agentkeyboard.app.storage.AkDatabase
import com.agentkeyboard.app.storage.SqlAgentStore
import com.agentkeyboard.app.storage.SqlAuditStore
import com.agentkeyboard.app.storage.SqlClipboardStore
import com.agentkeyboard.app.storage.SqlSettingsStore
import com.agentkeyboard.app.system.SystemSettings
import com.agentkeyboard.core.agent.AgentOutput
import com.agentkeyboard.core.agent.AgentRuntime
import com.agentkeyboard.core.inference.LocalInferenceEngine
import com.agentkeyboard.core.inference.ModelManager
import com.agentkeyboard.core.models.ManifestException
import com.agentkeyboard.core.models.ManifestVerifier
import com.agentkeyboard.core.models.Capability
import com.agentkeyboard.core.models.ModelService
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Process singleton for `:agent`. It owns the [AgentRuntime] independently of any one component's
 * lifecycle, because `AgentService` (bound by the IME) and `AkNotificationListener` (bound by the
 * system) live and die separately but must share one conversation cache and one database.
 *
 * Threading: all runtime and model-service access happens on [handler]'s thread (`ak-agent`).
 * Inference runs on a single background-priority thread, so at most one model works at a time
 * (ADR-0004); downloads and imports run on their own I/O thread (ADR-0011).
 */
object AgentHub {
    private val thread = HandlerThread("ak-agent", Process.THREAD_PRIORITY_DEFAULT).apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val serial = Executor { handler.post(it) }
    private val engineThread = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "ak-engine")
    }

    private val ioThread = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "ak-models-io")
    }

    val callbacks = RemoteCallbackList<IAgentCallback>()

    const val WORK_RUN = 1
    const val WORK_TRANSFER = 2

    /** Set by `AgentService` while it exists; receives the foreground work bits on the main thread. */
    @Volatile var foreground: ((Int) -> Unit)? = null

    /** Latest work bits (main thread): lets a late `onStartCommand` demote work that already ended. */
    @Volatile var work: Int = 0
        private set

    private var runtime: AgentRuntime? = null // ak-agent thread only
    private var models: ModelService? = null // ak-agent thread only
    private var manager: ModelManager? = null
    private var speech: PlatformSpeech? = null
    private val idleCheck = Runnable { manager?.unloadIfIdle() }

    /** Runs [block] on the agent thread with a started runtime. Never blocks the caller. */
    fun post(block: (AgentRuntime) -> Unit) {
        handler.post {
            val rt = runtime ?: try {
                create().also { runtime = it }
            } catch (e: Exception) {
                // e.g. a transient Keystore failure opening the DB. Drop this task (its caller times out
                // with `unavailable`) and retry on the next one, rather than crash-looping :agent.
                AkLog.w("agent runtime unavailable: ${e::class.java.simpleName}")
                return@post
            }
            try {
                block(rt)
            } catch (e: RuntimeException) {
                AkLog.w("agent task failed: ${e::class.java.simpleName}")
            }
        }
    }

    /** Memory pressure: release TTS, drop background inference, unload the model (SPEC §12.2). */
    fun onTrimMemory() = handler.post {
        speech?.shutdown()
        runtime?.onTrimMemory()
    }

    /** From the companion's file picker (offline flavor). Closes [file] if the import can't start. */
    fun importModel(file: ParcelFileDescriptor, name: String, size: Long) = post {
        val m = models
        val accepted = m != null && m.import(name, size) { ParcelFileDescriptor.AutoCloseInputStream(file) }
        if (!accepted) runCatching { file.close() }
    }

    /** dataSync budget exhausted (Android 15+): hold transfers until the Models screen is opened again. */
    fun transfersTimedOut() = post {
        models?.suspend("Paused by Android’s background download limit. Open Models to continue.")
    }

    private fun setWork(bit: Int, on: Boolean) = main.post {
        val next = if (on) work or bit else work and bit.inv()
        if (next == work) return@post
        work = next
        val service = foreground
        when {
            service != null -> service(next)
            on -> AgentService.startForegroundCompat(Ak.app) // its onStartCommand promotes it
        }
    }

    private fun create(): AgentRuntime {
        val t0 = System.nanoTime()
        val ctx = Ak.app
        val db = AkDatabase.open(ctx).writableDatabase
        val sp = PlatformSpeech(ctx, handler).also { speech = it }
        val settings = SqlSettingsStore(db)
        var rt: AgentRuntime? = null
        val modelManager = ModelManager(
            runtime = LiteRtLmRuntime(File(ctx.cacheDir, "litertlm"), ctx.applicationInfo.nativeLibraryDir),
            probes = PrefsProbeStore(ctx),
            device = AndroidDevice(ctx),
            appVersion = BuildConfig.VERSION_CODE,
            log = AkLog::d,
        ).also { manager = it }
        val online = BuildConfig.ONLINE
        lateinit var service: ModelService
        service = ModelService(
            tier = Ak.tier,
            appVersion = BuildConfig.VERSION_CODE,
            source = if (online) ModelService.SOURCE_DOWNLOAD else ModelService.SOURCE_IMPORT,
            manifestUrl = BuildConfig.MODEL_MANIFEST_URL.ifBlank { null },
            verifier = verifier(BuildConfig.MODEL_MANIFEST_KEYS),
            files = AndroidModelFiles(ctx),
            http = if (online) AndroidHttp() else null,
            settings = settings,
            network = AndroidNetwork(ctx) { handler.post { service.networkChanged() } },
            io = ioThread,
            serial = serial,
            onChanged = { status ->
                rt?.modelsChanged(status)
                publishVoiceModel(service)
            },
            onTransfer = { on -> setWork(WORK_TRANSFER, on) },
            onRemoved = { id -> modelManager.evict(id) },
            residency = { modelManager.resident },
            specs = AndroidSpecs.read(ctx).also { AkLog.i("device: ${it.totalRamMb} MB, ${it.cpuCores} cores, gpu=${it.gpu}, lowRam=${it.lowRam}") },
            scanner = AndroidModelScanner(ctx, allFilesOption = BuildConfig.ALL_FILES),
            micGranted = { ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
            log = AkLog::d,
        ).also {
            it.start()
            models = it
            publishVoiceModel(it)
        }
        val output = object : AgentOutput {
            override fun envelope(raw: String, to: String?) = broadcast(to) { it.onEnvelope(raw) }
            override fun inbound(packageName: String, conversationKey: String, messageJson: String, remoteInputAvailable: Boolean) =
                broadcast(AgentRuntime.ORIGIN_IME) { it.onInbound(packageName, conversationKey, messageJson, remoteInputAvailable) }
            override fun remoteReplyResult(requestId: String, ok: Boolean) =
                broadcast(AgentRuntime.ORIGIN_IME) { it.onRemoteReplyResult(requestId, ok) }
        }
        return AgentRuntime(
            agents = SqlAgentStore(db),
            clipboard = SqlClipboardStore(db),
            audit = SqlAuditStore(db),
            settings = settings,
            speech = sp,
            // Measured decode speed refines the per-phone advice (ADR-0012); recorded on the serial thread.
            engine = LocalInferenceEngine(service, modelManager, onSpeed = { id, tps -> serial.execute { service.recordSpeed(id, tps) } }),
            output = output,
            status = { SystemSettings.status(ctx, modelsInstalled = service.hasTextModel()) },
            engineExecutor = engineThread,
            serial = serial,
            onBusyChanged = { busy ->
                setWork(WORK_RUN, busy)
                // Unload an idle model after its timeout (Tier S keeps RAM for the foreground app).
                handler.removeCallbacks(idleCheck)
                if (!busy) handler.postDelayed(idleCheck, IDLE_UNLOAD_MS + 1_000)
            },
            log = AkLog::d,
            replies = { key, text -> AkNotificationListener.replyVia(ctx, key, text) },
            models = service,
            onTrim = { modelManager.trim() },
        ).also {
            rt = it
            it.start()
            AkLog.i("agent runtime ready in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    /** Last voice model published for the keyboard process (serial thread). */
    private var voicePointer: String? = "?"

    /** Keeps [SttPointer] in step with the active speech model; rewrites only on change. */
    private fun publishVoiceModel(service: ModelService) {
        val m = service.pick(Capability.TRANSCRIBE)
        val key = m?.let { "${it.entry.id}:${it.entry.sha256}" }
        if (key == voicePointer) return
        voicePointer = key
        try {
            SttPointer.publish(Ak.app, m)
        } catch (e: java.io.IOException) {
            AkLog.w("couldn't publish the voice model")
            voicePointer = "?"
        }
    }

    /**
     * `ak.modelManifestKeys` = `keyId:base64Spki[,keyId:base64Spki…]` (ADR-0011 key rotation). A bad
     * value disables the catalog ("unconfigured") instead of taking the whole runtime down.
     */
    private fun verifier(raw: String): ManifestVerifier = try {
        ManifestVerifier(
            raw.split(',').mapNotNull { pair ->
                val i = pair.indexOf(':')
                if (i <= 0) null else pair.substring(0, i).trim() to pair.substring(i + 1).trim()
            }.toMap(),
        )
    } catch (e: ManifestException) {
        AkLog.w("model manifest key misconfigured")
        ManifestVerifier(emptyMap())
    }

    private const val IDLE_UNLOAD_MS = 5 * 60_000L

    /**
     * Callbacks are `oneway`, so a slow or dead IME can never stall this thread. [to] = one client
     * origin (the registration cookie); null = every client.
     */
    private inline fun broadcast(to: String?, crossinline send: (IAgentCallback) -> Unit) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                if (to != null && callbacks.getBroadcastCookie(i) != to) continue
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
