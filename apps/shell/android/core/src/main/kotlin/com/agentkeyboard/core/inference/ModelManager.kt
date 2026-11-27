package com.agentkeyboard.core.inference

import com.agentkeyboard.core.agent.EngineFailure
import com.agentkeyboard.core.models.Backend
import com.agentkeyboard.core.models.Capability
import com.agentkeyboard.core.models.InstalledModel
import com.agentkeyboard.core.protocol.ResidentModel

/*
 * The single resident generative model (SPEC §12.2, ADR-0004/0011). Platform-free: the LiteRT-LM
 * adapter implements [ModelRuntime]; tests use a fake.
 */

data class LoadOptions(
    val backend: Backend,
    /** CPU decode threads: 4, or 2 while the phone is thermally throttled (SPEC §12.2). */
    val threads: Int,
    /** KV-cache size (input + output tokens); the model file's own limit. */
    val maxTokens: Int,
    /** Also initialise the vision encoder (a separate, lazily loaded part of the model). */
    val vision: Boolean,
)

data class GenerationRequest(
    /** Agent persona block. Stable per agent, so the runtime can keep its prefill warm (§12.3). */
    val system: String,
    /** Identity of [system] for the prefix cache. */
    val prefixKey: String,
    val user: String,
    val maxOutputTokens: Int,
    val temperature: Double,
    val topK: Int,
    val topP: Double,
    /** JSON Schema for constrained decoding of structured skills; null = free text. */
    val jsonSchema: String?,
)

data class GenerationResult(val text: String, val truncated: Boolean, val cancelled: Boolean)

interface LoadedModel : AutoCloseable {
    val backend: Backend
    val vision: Boolean

    /**
     * Blocking; on the engine thread. Streams text deltas through [onToken]; polls [isCancelled] and
     * stops promptly (LiteRT-LM `cancelProcess`). Throws on runtime failure.
     */
    fun generate(request: GenerationRequest, onToken: (String) -> Unit, isCancelled: () -> Boolean): GenerationResult
}

fun interface ModelRuntime {
    /** Loads (mmaps) the model. Slow (seconds); throws when the backend can't run it. */
    fun load(model: InstalledModel, options: LoadOptions): LoadedModel
}

/**
 * Persistent probe results, written synchronously (the Android side uses `SharedPreferences.commit`)
 * because the point is to survive a native crash in the very next instruction.
 */
interface ProbeStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

interface DeviceProfile {
    /** SoC/driver on the GPU allowlist, and the user hasn't forced CPU. */
    fun gpuAllowed(): Boolean
    fun npuAllowed(): Boolean
    fun thermalThrottled(): Boolean
    val cpuCores: Int
}

/**
 * Owns residency and backend choice:
 * - **One model in memory.** Asking for a different model (Tier S: text ⇄ VLM) unloads the current one
 *   first, so two never coexist (SPEC §12.2 "unload text → load VLM → run → unload → lazy reload").
 * - **Crash-safe backend probing.** Before the first NPU/GPU load of a model build it records
 *   `pending`; after the first successful generation, `ok`. A process that dies in between (driver
 *   crash, native abort) leaves `pending`, which the next start reads as `failed`, so the phone falls
 *   back to CPU instead of crash-looping. Keys include the app version: an update retries.
 * - **Idle and memory-pressure unload** without racing a running generation.
 *
 * Thread-safety: [use] runs on the engine thread; [trim], [evict], [unloadIfIdle] and [resident] may
 * be called from any thread.
 */
class ModelManager(
    private val runtime: ModelRuntime,
    private val probes: ProbeStore,
    private val device: DeviceProfile,
    private val appVersion: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** Unload after this long without use (Tier S keeps memory free for the foreground app). */
    private val idleUnloadMs: Long = 5 * 60_000L,
) {
    private val lock = Any()
    private var model: LoadedModel? = null
    private var modelId: String? = null
    private var inUse = false
    private var unloadWhenDone = false
    private var lastUsed = 0L
    /** Probe key of a GPU/NPU model that has loaded but not yet generated successfully. */
    private var unproven: String? = null

    @Volatile var resident: ResidentModel? = null
        private set

    fun <R> use(installed: InstalledModel, capability: Capability, block: (LoadedModel) -> R): R {
        val wantVision = capability == Capability.VISION && Capability.VISION in installed.entry.capabilities
        val loaded = synchronized(lock) {
            check(!inUse) { "ModelManager.use is not re-entrant" }
            val current = model
            if (current != null && modelId == installed.entry.id && (!wantVision || current.vision)) {
                inUse = true
                current
            } else {
                unloadLocked()
                inUse = true
                null
            }
        } ?: try {
            load(installed, wantVision).also { m ->
                synchronized(lock) {
                    model = m
                    modelId = installed.entry.id
                    resident = ResidentModel(installed.entry.id, m.backend.wire)
                }
            }
        } catch (e: Throwable) {
            synchronized(lock) { inUse = false }
            throw e
        }
        try {
            val result = block(loaded)
            unproven?.let {
                probes.put(it, OK)
                unproven = null
            }
            return result
        } catch (t: Throwable) {
            // A GPU/NPU build that loads but can't generate is as unusable as one that crashes.
            val key = unproven
            if (key != null && !(t is EngineFailure && t.code == "cancelled")) {
                probes.put(key, FAILED)
                unproven = null
                synchronized(lock) { unloadWhenDone = true }
            }
            throw t
        } finally {
            synchronized(lock) {
                inUse = false
                lastUsed = clock()
                if (unloadWhenDone) unloadLocked()
            }
        }
    }

    /** Memory pressure: unload now, or right after the running generation. Returns true if unloaded now. */
    fun trim(): Boolean = synchronized(lock) {
        if (model == null) return false
        if (inUse) {
            unloadWhenDone = true
            return false
        }
        unloadLocked()
        true
    }

    /** The model's file was deleted: unload it if resident (now or after the current run). */
    fun evict(id: String) {
        synchronized(lock) { if (modelId != id) return }
        trim()
    }

    fun unloadIfIdle(): Boolean = synchronized(lock) {
        if (model == null || inUse || clock() - lastUsed < idleUnloadMs) return false
        log("unloading idle model")
        unloadLocked()
        true
    }

    private fun unloadLocked() {
        unloadWhenDone = false
        val m = model ?: return
        model = null
        modelId = null
        resident = null
        try {
            m.close()
        } catch (e: Exception) {
            log("model close failed: ${e::class.java.simpleName}")
        }
    }

    private fun load(installed: InstalledModel, vision: Boolean): LoadedModel {
        val e = installed.entry
        val threads = (if (device.thermalThrottled()) 2 else 4).coerceAtMost(device.cpuCores.coerceAtLeast(1))
        val candidates = e.backends.filter { b ->
            when (b) {
                Backend.NPU -> device.npuAllowed()
                Backend.GPU -> device.gpuAllowed()
                Backend.CPU -> true
            }
        }.sortedBy { it.ordinal }
        var last: Throwable? = null
        for (b in candidates) {
            val key = probeKey(installed, b)
            if (b != Backend.CPU) {
                when (probes.get(key)) {
                    FAILED -> continue
                    PENDING -> { // the previous process died while proving this backend
                        log("backend ${b.wire} crashed earlier for ${e.id}; skipping")
                        probes.put(key, FAILED)
                        continue
                    }
                    null -> probes.put(key, PENDING)
                }
            }
            val t0 = clock()
            try {
                val m = runtime.load(installed, LoadOptions(b, threads, e.contextTokens, vision))
                log("loaded ${e.id} on ${b.wire} in ${clock() - t0} ms")
                if (b != Backend.CPU && probes.get(key) == PENDING) unproven = key
                return m
            } catch (ex: Throwable) {
                if (ex is VirtualMachineError && ex !is OutOfMemoryError) throw ex
                last = ex
                log("load on ${b.wire} failed: ${ex::class.java.simpleName}")
                if (b != Backend.CPU) probes.put(key, FAILED)
            }
        }
        if (last is OutOfMemoryError) throw EngineFailure("unavailable", "Not enough memory to load the model. Close some apps and try again.")
        throw EngineFailure("unavailable", "Couldn’t load ${e.name} on this phone.")
    }

    private fun probeKey(m: InstalledModel, b: Backend) = "v$appVersion:${m.entry.id}:${m.entry.sha256.take(12)}:${b.wire}"

    companion object {
        const val PENDING = "pending"
        const val OK = "ok"
        const val FAILED = "failed"
    }
}
