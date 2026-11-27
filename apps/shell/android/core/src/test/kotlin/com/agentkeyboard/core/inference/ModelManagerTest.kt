package com.agentkeyboard.core.inference

import com.agentkeyboard.core.agent.EngineFailure
import com.agentkeyboard.core.models.Backend
import com.agentkeyboard.core.models.Capability
import com.agentkeyboard.core.models.InstalledModel
import com.agentkeyboard.core.models.entryFor
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeLoaded(override val backend: Backend, override val vision: Boolean, val id: String) : LoadedModel {
    var closed = false
    var failGenerate: Throwable? = null
    var reply = "ok"
    override fun generate(request: GenerationRequest, onToken: (String) -> Unit, isCancelled: () -> Boolean): GenerationResult {
        failGenerate?.let { throw it }
        val out = StringBuilder()
        for (word in reply.split(" ")) {
            if (isCancelled()) return GenerationResult(out.toString(), truncated = false, cancelled = true)
            val d = if (out.isEmpty()) word else " $word"
            out.append(d)
            onToken(d)
        }
        return GenerationResult(out.toString(), truncated = false, cancelled = false)
    }
    override fun close() { closed = true }
}

class FakeRuntime : ModelRuntime {
    val loads = mutableListOf<Pair<String, LoadOptions>>()
    val failOn = mutableSetOf<Backend>()
    var oom = false
    val models = mutableListOf<FakeLoaded>()
    var onLoad: (FakeLoaded) -> Unit = {}
    override fun load(model: InstalledModel, options: LoadOptions): LoadedModel {
        loads += model.entry.id to options
        if (oom) throw OutOfMemoryError()
        if (options.backend in failOn) throw IllegalStateException("backend")
        return FakeLoaded(options.backend, options.vision, model.entry.id).also { models += it; onLoad(it) }
    }
}

class MemProbes : ProbeStore {
    val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
}

class FakeDevice(var gpu: Boolean = true, var npu: Boolean = false, var hot: Boolean = false) : DeviceProfile {
    override fun gpuAllowed() = gpu
    override fun npuAllowed() = npu
    override fun thermalThrottled() = hot
    override val cpuCores = 8
}

class ModelManagerTest {
    private val runtime = FakeRuntime()
    private val probes = MemProbes()
    private val device = FakeDevice()
    private var now = 0L
    private val manager = ModelManager(runtime, probes, device, appVersion = 7, clock = { now })
    private val text = InstalledModel(entryFor(byteArrayOf(1), id = "text").copy(backends = listOf(Backend.GPU, Backend.CPU)), "/m/text")
    private val vlm = InstalledModel(entryFor(byteArrayOf(2), id = "vlm").copy(capabilities = setOf(Capability.TEXT, Capability.VISION)), "/m/vlm")
    private val gpuKey = "v7:text:${text.entry.sha256.take(12)}:gpu"

    private fun run(m: InstalledModel, cap: Capability = Capability.TEXT) =
        manager.use(m, cap) { it.generate(GenerationRequest("s", "k", "u", 16, 0.7, 40, 0.95, null), {}, { false }).text }

    @Test fun firstGpuLoadIsProvenByASuccessfulGeneration() {
        assertEquals("ok", run(text))
        assertEquals(Backend.GPU, runtime.loads.single().second.backend)
        assertEquals(ModelManager.OK, probes.map[gpuKey])
        assertEquals("gpu", manager.resident?.backend)
        run(text)
        assertEquals(1, runtime.loads.size) // stays resident
    }

    @Test fun aPendingProbeFromACrashedProcessFallsBackToCpu() {
        probes.map[gpuKey] = ModelManager.PENDING // the previous process died mid-probe
        run(text)
        assertEquals(Backend.CPU, runtime.loads.single().second.backend)
        assertEquals(ModelManager.FAILED, probes.map[gpuKey])
    }

    @Test fun backendThatLoadsButCannotGenerateIsMarkedFailedAndUnloaded() {
        runtime.onLoad = { it.failGenerate = IllegalStateException("kernel") }
        assertFailsWith<IllegalStateException> { run(text) }
        assertEquals(ModelManager.FAILED, probes.map[gpuKey])
        assertTrue(runtime.models.single().closed)
        assertNull(manager.resident)
        runtime.onLoad = {}
        run(text)
        assertEquals(Backend.CPU, runtime.loads.last().second.backend)
    }

    @Test fun loadFailureFallsThroughToCpuAndOomBecomesUnavailable() {
        runtime.failOn += Backend.GPU
        run(text)
        assertEquals(listOf(Backend.GPU, Backend.CPU), runtime.loads.map { it.second.backend })
        assertEquals(ModelManager.FAILED, probes.map[gpuKey])
        manager.trim()
        runtime.oom = true
        val e = assertFailsWith<EngineFailure> { run(text) }
        assertEquals("unavailable", e.code)
    }

    @Test fun onlyOneModelIsEverResident() {
        device.gpu = false
        run(text)
        run(vlm, Capability.VISION)
        assertTrue(runtime.models[0].closed)
        assertFalse(runtime.models[1].closed)
        assertTrue(runtime.loads.last().second.vision)
        assertEquals("vlm", manager.resident?.id)
    }

    @Test fun trimDuringAGenerationUnloadsAfterIt() {
        device.gpu = false
        manager.use(text, Capability.TEXT) {
            assertFalse(manager.trim())
            assertFalse((it as FakeLoaded).closed)
        }
        assertTrue(runtime.models.single().closed)
        assertNull(manager.resident)
    }

    @Test fun throttledPhonesUseTwoThreadsAndIdleModelsUnload() {
        device.gpu = false
        device.hot = true
        run(text)
        assertEquals(2, runtime.loads.single().second.threads)
        now = 4 * 60_000L
        assertFalse(manager.unloadIfIdle())
        now = 6 * 60_000L
        assertTrue(manager.unloadIfIdle())
        manager.evict("text")
        assertTrue(runtime.models.single().closed)
    }
}
