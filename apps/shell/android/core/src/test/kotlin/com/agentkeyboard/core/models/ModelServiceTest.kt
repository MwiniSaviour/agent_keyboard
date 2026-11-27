package com.agentkeyboard.core.models

import com.agentkeyboard.core.json.JsonBool
import com.agentkeyboard.core.json.JsonObj
import com.agentkeyboard.core.protocol.DeviceTier
import com.agentkeyboard.core.protocol.ModelPack
import com.agentkeyboard.core.protocol.ModelsStatus
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelServiceTest {
    private val gemma = "gemma-3-1b-it-int4"
    private val manifestUrl = "https://models.example/models.json"
    private val files = MemFiles()
    private val settings = MemSettings()
    private val network = FakeNetwork()
    private val bodies = HashMap(ManifestFixture.bodies).apply { put(manifestUrl, ManifestFixture.envelope.toByteArray()) }
    private val http = FakeHttp(bodies)
    private val direct = Executor { it.run() }
    private val emitted = mutableListOf<ModelsStatus>()
    private val transfers = mutableListOf<Boolean>()
    private val removed = mutableListOf<String>()

    private fun service(tier: DeviceTier = DeviceTier.S, source: String = ModelService.SOURCE_DOWNLOAD, withHttp: Boolean = true) = ModelService(
        tier = tier, appVersion = 1, source = source, manifestUrl = if (withHttp) manifestUrl else null, verifier = ManifestFixture.verifier,
        files = files, http = if (withHttp) http else null, settings = settings, network = network, io = direct, serial = direct,
        onChanged = { emitted += it }, onTransfer = { transfers += it }, onRemoved = { removed += it },
        installer = ModelInstaller(files, if (withHttp) http else null, sleep = {}),
    ).also { it.start() }

    private fun ModelsStatus.pack(id: String) = packs.single { it.id == id }

    @Test fun catalogLoadsOnFirstVisitAndOnlyThisTiersPacksShow() {
        val s = service()
        assertEquals(ModelService.CATALOG_UNAVAILABLE, s.status().catalog)
        s.onVisible()
        val st = emitted.last()
        assertEquals(ModelService.CATALOG_READY, st.catalog)
        assertEquals(listOf(gemma), st.packs.map { it.id })
        assertTrue(st.pack(gemma).core)
        assertEquals("Gemma Terms of Use", st.pack(gemma).license)
        assertNotNull(files.manifest) // cached for offline starts
        // A fresh catalog is not refetched within the TTL.
        s.onVisible()
        assertEquals(1, http.requests.count { it.first == manifestUrl })
    }

    @Test fun downloadInstallsVerifiesAndPublishesTheModel() {
        val s = service()
        s.onVisible()
        assertNull(s.pick(Capability.TEXT))
        s.download(listOf(gemma, "gemma-4-e2b-it", "nope"))
        assertEquals(ModelPack.INSTALLED, s.status().pack(gemma).state)
        assertEquals("/models/gemma3-1b-it-int4.litertlm", s.pick(Capability.TEXT)?.path)
        assertTrue(s.hasTextModel())
        assertEquals(listOf(true, false), transfers) // dataSync FGS up for the transfer only
        assertTrue((settings.map[ModelService.KEY_INSTALLED] as JsonObj).fields.containsKey(gemma))
        assertEquals(1, http.requests.count { it.first.endsWith(".litertlm") }) // other-tier pack skipped
        // Survives a restart from the persisted record + cached catalog, without network.
        network.connected = false
        assertNotNull(service().pick(Capability.TEXT))
    }

    @Test fun wifiOnlyWaitsOnMeteredNetworksAndResumesWhenAllowed() {
        network.unmetered = false
        val s = service()
        s.onVisible()
        s.download(listOf(gemma))
        val waiting = s.status().pack(gemma)
        assertEquals(ModelPack.QUEUED, waiting.state)
        assertEquals("Waiting for Wi-Fi", waiting.detail)
        assertTrue(transfers.isEmpty())
        settings.map[ModelService.KEY_WIFI_ONLY] = JsonBool(false)
        s.onSetting(ModelService.KEY_WIFI_ONLY)
        assertEquals(ModelPack.INSTALLED, s.status().pack(gemma).state)
        assertTrue(!emitted.last().wifiOnly)
    }

    @Test fun badBytesFailWithAReasonAndDeleteRemovesEverything() {
        bodies["https://example.invalid/gemma3-1b-it-int4.litertlm"] = "gemma-test-bytez".toByteArray()
        val s = service()
        s.onVisible()
        s.download(listOf(gemma))
        val failed = s.status().pack(gemma)
        assertEquals(ModelPack.FAILED, failed.state)
        assertEquals("Verification failed (SHA-256 mismatch)", failed.detail)
        assertNull(s.pick(Capability.TEXT))

        bodies["https://example.invalid/gemma3-1b-it-int4.litertlm"] = ManifestFixture.gemmaBytes
        s.download(listOf(gemma))
        assertNotNull(s.pick(Capability.TEXT))
        s.delete(gemma)
        assertNull(s.pick(Capability.TEXT))
        assertEquals(listOf(gemma), removed)
        assertTrue(files.final.isEmpty())
        assertEquals(ModelPack.AVAILABLE, s.status().pack(gemma).state)
    }

    @Test fun aTamperedCatalogNeverReplacesTheCachedOne() {
        val s = service()
        s.onVisible()
        bodies[manifestUrl] = ManifestFixture.envelope.replace("ak-test", "ak-evil").toByteArray()
        s.refreshCatalog(force = true)
        assertEquals(ModelService.CATALOG_READY, s.status().catalog)
        assertEquals(listOf(gemma), s.status().packs.map { it.id })
        // With nothing cached, the same bytes are reported as invalid.
        files.manifest = null
        val fresh = service()
        fresh.refreshCatalog(force = true)
        assertEquals(ModelService.CATALOG_INVALID, fresh.status().catalog)
    }

    @Test fun offlineFlavorInstallsByImportingTheCatalogThenTheFile() {
        val s = service(source = ModelService.SOURCE_IMPORT, withHttp = false)
        assertEquals(ModelService.CATALOG_UNAVAILABLE, s.status().catalog)
        s.download(listOf(gemma)) // no network path in this build: ignored
        assertTrue(http.requests.isEmpty())
        val env = ManifestFixture.envelope.toByteArray()
        s.import("models.json", env.size.toLong()) { ByteArrayInputStream(env) }
        assertEquals(ModelService.CATALOG_READY, s.status().catalog)
        s.import("gemma3-1b-it-int4.litertlm", 16) { ByteArrayInputStream(ManifestFixture.gemmaBytes) }
        assertEquals(ModelPack.INSTALLED, s.status().pack(gemma).state)
        assertEquals(ModelService.SOURCE_IMPORT, s.status().source)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun unconfiguredBuildsSaySo() {
        val s = ModelService(
            tier = DeviceTier.M, appVersion = 1, source = ModelService.SOURCE_DOWNLOAD, manifestUrl = null, verifier = ManifestVerifier(emptyMap()),
            files = MemFiles(), http = http, settings = settings, network = network, io = direct, serial = direct, onChanged = {},
        )
        s.start()
        assertEquals(ModelService.CATALOG_UNCONFIGURED, s.status().catalog)
    }

    @Test fun suspendHoldsTheQueueUntilTheModelsScreenIsOpenedAgain() {
        val io = java.util.ArrayDeque<Runnable>()
        val s = ModelService(
            tier = DeviceTier.S, appVersion = 1, source = ModelService.SOURCE_DOWNLOAD, manifestUrl = manifestUrl, verifier = ManifestFixture.verifier,
            files = files.apply { manifest = ManifestFixture.envelope }, http = http, settings = settings, network = network,
            io = { io.add(it) }, serial = direct, onChanged = { emitted += it }, onTransfer = { transfers += it },
            installer = ModelInstaller(files, http, sleep = {}),
        ).also { it.start() }
        s.download(listOf(gemma))
        assertEquals(ModelPack.DOWNLOADING, s.status().pack(gemma).state)
        s.suspend("Paused by Android’s background time limit")
        while (io.isNotEmpty()) io.poll().run()
        val held = s.status().pack(gemma)
        assertEquals(ModelPack.QUEUED, held.state)
        assertEquals("Paused by Android’s background time limit", held.detail)
        assertEquals(listOf(true, false), transfers)
        s.networkChanged() // background signals don't lift the hold
        assertTrue(io.isEmpty())
        s.onVisible()
        while (io.isNotEmpty()) io.poll().run()
        assertEquals(ModelPack.INSTALLED, s.status().pack(gemma).state)
    }

    @Test fun importsThatCannotStartSayWhyAndLeaveTheFileToTheCaller() {
        val s = service()
        s.onVisible()
        var opened = false
        assertEquals(false, s.import("holiday.jpg", 1234) { opened = true; ByteArrayInputStream(ByteArray(0)) })
        assertEquals(false, opened)
        assertEquals("That file isn’t a model in the catalog", s.status().catalogDetail)
        assertEquals(true, s.import("gemma3-1b-it-int4.litertlm", 16) { ByteArrayInputStream(ManifestFixture.gemmaBytes) })
        assertNull(s.status().catalogDetail)
        assertEquals(ModelPack.INSTALLED, s.status().pack(gemma).state)
    }
}
