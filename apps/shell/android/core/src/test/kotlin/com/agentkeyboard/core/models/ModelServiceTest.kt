package com.agentkeyboard.core.models

import com.agentkeyboard.core.json.JsonArr
import com.agentkeyboard.core.json.JsonBool
import com.agentkeyboard.core.json.JsonObj
import com.agentkeyboard.core.json.str
import com.agentkeyboard.core.protocol.DeviceTier
import com.agentkeyboard.core.protocol.ModelPack
import com.agentkeyboard.core.protocol.ModelsStatus
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelServiceTest {
    private val gemma = "gemma-3-1b-it-int4"
    private val e2b = "gemma-4-e2b-it"
    private val stt = "zipformer-en-20m"
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
    private var clock = 1_000_000L

    private fun service(
        specs: DeviceSpecs = Phones.SMALL,
        source: String = ModelService.SOURCE_DOWNLOAD,
        withHttp: Boolean = true,
        scanner: ModelScanner? = null,
    ) = ModelService(
        tier = DeviceTier.S, appVersion = 1, source = source, manifestUrl = if (withHttp) manifestUrl else null, verifier = ManifestFixture.verifier,
        files = files, http = if (withHttp) http else null, settings = settings, network = network, io = direct, serial = direct,
        onChanged = { emitted += it }, onTransfer = { transfers += it }, onRemoved = { removed += it },
        specs = specs, scanner = scanner, micGranted = { true }, clock = { clock },
        installer = ModelInstaller(files, if (withHttp) http else null, sleep = {}),
    ).also { it.start() }

    private fun ModelsStatus.pack(id: String) = packs.single { it.id == id }

    @Test fun catalogLoadsOnFirstVisitWithAdviceForThisPhone() {
        val s = service()
        assertEquals(ModelService.CATALOG_UNAVAILABLE, s.status().catalog)
        s.onVisible()
        val st = emitted.last()
        assertEquals(ModelService.CATALOG_READY, st.catalog)
        // Every model is listed; the phone decides which one is the pick.
        assertEquals(listOf(gemma, stt, e2b), st.packs.map { it.id })
        assertEquals("recommended", st.pack(gemma).fit)
        assertTrue(st.pack(gemma).core)
        assertTrue(st.pack(gemma).fitDetail!!.startsWith("The lightest model that fits this phone"))
        assertEquals("too_big", st.pack(e2b).fit)
        assertEquals("Needs more memory than this phone can spare (for phones with 4 GB+ RAM)", st.pack(e2b).fitDetail)
        assertEquals("recommended", st.pack(stt).fit)
        assertEquals("3 GB", st.device!!.ramLabel)
        assertEquals(true, st.micGranted)
        assertEquals("Gemma Terms of Use", st.pack(gemma).license)
        assertNotNull(files.manifest) // cached for offline starts
        // A fresh catalog is not refetched within the TTL.
        s.onVisible()
        assertEquals(1, http.requests.count { it.first == manifestUrl })
    }

    @Test fun aBiggerPhoneGetsTheSmarterModel() {
        val st = service(Phones.BIG).apply { onVisible() }.status()
        assertEquals("recommended", st.pack(e2b).fit)
        assertTrue(st.pack(e2b).fitDetail!!.contains("with 8 GB of RAM"), st.pack(e2b).fitDetail)
        assertEquals("good", st.pack(gemma).fit)
        assertTrue(st.pack(gemma).fitDetail!!.startsWith("Faster and lighter, less capable than Gemma 4 E2B"))
        assertEquals(e2b, st.packs.first().id)
        assertEquals("SM8550", st.device!!.soc)
    }

    @Test fun downloadInstallsVerifiesAndSkipsModelsTooBigForThePhone() {
        val s = service()
        s.onVisible()
        assertNull(s.pick(Capability.TEXT))
        s.download(listOf(gemma, e2b, "nope"))
        assertEquals(ModelPack.INSTALLED, s.status().pack(gemma).state)
        assertEquals(ModelPack.AVAILABLE, s.status().pack(e2b).state)
        assertEquals("/models/$gemma/gemma3-1b-it-int4.litertlm", s.pick(Capability.TEXT)?.primary)
        assertTrue(s.hasTextModel())
        assertEquals(true, s.status().pack(gemma).active)
        assertEquals(listOf(true, false), transfers) // dataSync FGS up for the transfer only
        assertTrue((settings.map[ModelService.KEY_INSTALLED] as JsonObj).fields.containsKey(gemma))
        assertEquals(1, http.requests.count { it.first.endsWith(".litertlm") })
        // Survives a restart from the persisted record + cached catalog, without network.
        network.connected = false
        assertNotNull(service().pick(Capability.TEXT))
    }

    @Test fun speechModelsDownloadEveryFileAndServeTranscriptionOnly() {
        val s = service()
        s.onVisible()
        s.download(listOf(stt))
        assertEquals(ModelPack.INSTALLED, s.status().pack(stt).state)
        assertEquals(ManifestFixture.sttFiles.map { "https://example.invalid/stt/$it" to 0L }, http.requests.filter { "/stt/" in it.first })
        val m = s.pick(Capability.TRANSCRIBE)!!
        assertEquals("/models/$stt/tokens.txt", m.file(m.entry.config.getValue("tokens")))
        assertNull(s.pick(Capability.TEXT))
        assertFalse(s.hasTextModel())
        // A missing file on disk (cleared by the user, say) drops the install on the next start.
        files.final.remove("$stt/joiner.int8.onnx")
        assertNull(service().pick(Capability.TRANSCRIBE))
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

    @Test fun theUsersChoiceWinsOverTheRecommendationAndPersists() {
        val s = service(Phones.BIG)
        s.onVisible()
        s.download(listOf(gemma, e2b))
        assertEquals(e2b, s.pick(Capability.TEXT)?.entry?.id) // recommended for this phone
        s.use(gemma)
        assertEquals(gemma, s.pick(Capability.TEXT)?.entry?.id)
        assertEquals(true, s.status().pack(gemma).active)
        assertNull(s.status().pack(e2b).active)
        assertEquals(gemma, service(Phones.BIG).pick(Capability.TEXT)?.entry?.id)
        s.use("nope") // unknown or not installed: ignored
        s.use(stt)
        assertEquals(gemma, s.pick(Capability.TEXT)?.entry?.id)
        s.delete(gemma)
        assertEquals(e2b, s.pick(Capability.TEXT)?.entry?.id)
    }

    @Test fun measuredSpeedReplacesTheEstimate() {
        val s = service(Phones.MID)
        s.onVisible()
        assertEquals("recommended", s.status().pack(e2b).fit) // ~12 tok/s estimated
        val before = emitted.size
        s.recordSpeed(e2b, 6.0) // this phone is slower than the estimate
        assertTrue(emitted.size > before)
        assertEquals("slow", s.status().pack(e2b).fit)
        assertEquals("Smarter, but slow on this phone (about 5 words a second)", s.status().pack(e2b).fitDetail)
        assertEquals("recommended", s.status().pack(gemma).fit)
        assertTrue((settings.map[ModelService.KEY_SPEED] as JsonObj).fields.containsKey(e2b))
        assertEquals("slow", service(Phones.MID).status().pack(e2b).fit) // remembered across restarts
        s.recordSpeed(e2b, Double.NaN)
        s.recordSpeed(e2b, -1.0)
        assertEquals("slow", s.status().pack(e2b).fit)
    }

    @Test fun aTamperedCatalogNeverReplacesTheCachedOne() {
        val s = service()
        s.onVisible()
        val ids = s.status().packs.map { it.id }
        bodies[manifestUrl] = ManifestFixture.envelope.replace("ak-test", "ak-evil").toByteArray()
        s.refreshCatalog(force = true)
        assertEquals(ModelService.CATALOG_READY, s.status().catalog)
        assertEquals(ids, s.status().packs.map { it.id })
        // With nothing cached, the same bytes are reported as invalid.
        files.manifest = null
        val fresh = service()
        fresh.refreshCatalog(force = true)
        assertEquals(ModelService.CATALOG_INVALID, fresh.status().catalog)
    }

    @Test fun offlineFlavorInstallsByImportingTheCatalogThenTheFiles() {
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
        // A multi-file model is imported one file at a time; the pack says what is still needed.
        s.import("tokens.txt", 14) { ByteArrayInputStream("stt-tokens.txt".toByteArray()) }
        val partial = s.status().pack(stt)
        assertEquals(ModelPack.PAUSED, partial.state)
        assertEquals("Still needed: encoder.int8.onnx, decoder.onnx, joiner.int8.onnx", partial.detail)
        for (name in ManifestFixture.sttFiles.dropLast(1)) {
            val b = "stt-$name".toByteArray()
            s.import(name, b.size.toLong()) { ByteArrayInputStream(b) }
        }
        assertEquals(ModelPack.INSTALLED, s.status().pack(stt).state)
        assertNotNull(s.pick(Capability.TRANSCRIBE))
        assertTrue(http.requests.isEmpty())
    }

    @Test fun unconfiguredBuildsSaySo() {
        val s = ModelService(
            tier = DeviceTier.M, appVersion = 1, source = ModelService.SOURCE_DOWNLOAD, manifestUrl = null, verifier = ManifestVerifier(emptyMap()),
            files = MemFiles(), http = http, settings = settings, network = network, io = direct, serial = direct, onChanged = {},
        )
        s.start()
        assertEquals(ModelService.CATALOG_UNCONFIGURED, s.status().catalog)
        assertNull(s.status().scan) // no scanner on this host
    }

    @Test fun suspendHoldsTheQueueUntilTheModelsScreenIsOpenedAgain() {
        val io = java.util.ArrayDeque<Runnable>()
        val s = ModelService(
            tier = DeviceTier.S, appVersion = 1, source = ModelService.SOURCE_DOWNLOAD, manifestUrl = manifestUrl, verifier = ManifestFixture.verifier,
            files = files.apply { manifest = ManifestFixture.envelope }, http = http, settings = settings, network = network,
            io = { io.add(it) }, serial = direct, onChanged = { emitted += it }, onTransfer = { transfers += it },
            specs = Phones.SMALL, installer = ModelInstaller(files, http, sleep = {}),
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

    // ───────────────────────────── models already on the phone ─────────────────────────────

    private val customBytes = ByteArray((ModelMatcher.MIN_CUSTOM_BYTES + 1_000).toInt()) { (it % 97).toByte() }

    @Test fun theModelsScreenFindsCatalogModelsOnThePhoneAndInstallsThemVerified() {
        val scanner = FakeScanner().apply {
            add("gemma3-1b-it-int4.litertlm", ManifestFixture.gemmaBytes)
            add("notes.txt", "hello".toByteArray())
        }
        val s = service(scanner = scanner)
        s.onVisible()
        val found = s.status().found.single()
        assertEquals("found-$gemma", found.id)
        assertTrue(found.verified)
        assertEquals("Download", found.where)
        assertEquals("folders", s.status().scan!!.access)
        s.adopt(found.id, trust = false) // catalog matches need no trust: the hash decides
        assertEquals(ModelPack.INSTALLED, s.status().pack(gemma).state)
        assertTrue(http.requests.none { it.first.endsWith(".litertlm") }) // nothing downloaded
        assertTrue(s.status().found.isEmpty())
        assertTrue(s.status().catalogDetail!!.startsWith("Installed Gemma 3 1B from Download"))
        // Rescans are throttled, and an installed model isn't offered again.
        s.onVisible()
        assertEquals(1, scanner.scans)
        clock += ModelService.RESCAN_MS
        s.onVisible()
        assertEquals(2, scanner.scans)
        assertTrue(s.status().found.isEmpty())
    }

    @Test fun aFileThatClaimsToBeACatalogModelButIsNotIsRefused() {
        val scanner = FakeScanner().apply { add("gemma3-1b-it-int4.litertlm", "gemma-test-bytez".toByteArray()) }
        val s = service(scanner = scanner)
        s.onVisible()
        s.adopt("found-$gemma", trust = false)
        assertEquals(ModelPack.FAILED, s.status().pack(gemma).state)
        assertEquals("Verification failed (SHA-256 mismatch)", s.status().pack(gemma).detail)
        assertNull(s.pick(Capability.TEXT))
        assertTrue(files.final.isEmpty())
    }

    @Test fun unknownModelsNeedTheUsersTrustAndAreTiedToTheBytesCopied() {
        val scanner = FakeScanner().apply { add("My_Finetune.litertlm", customBytes, folder = "Documents") }
        val s = service(Phones.MID, scanner = scanner)
        s.onVisible()
        val f = s.status().found.single()
        assertFalse(f.verified)
        assertEquals("My Finetune", f.name)
        assertTrue(f.id.startsWith(ModelMatcher.CUSTOM_PREFIX))
        s.adopt(f.id, trust = false)
        assertEquals("Confirm that you trust My Finetune to use it", s.status().catalogDetail)
        assertTrue(files.final.isEmpty())

        s.adopt(f.id, trust = true)
        val pack = s.status().pack(f.id)
        assertEquals(ModelPack.INSTALLED, pack.state)
        assertEquals(true, pack.custom)
        assertEquals("good", pack.fit) // judged, never recommended
        assertNull(pack.core.takeIf { it })
        // The only installed text model, so it is used; the record holds the measured hash.
        assertEquals(f.id, s.pick(Capability.TEXT)?.entry?.id)
        val rec = (settings.map[ModelService.KEY_CUSTOM] as JsonArr).items.single() as JsonObj
        assertEquals(sha256Hex(customBytes), rec["sha256"].str())
        // Survives a restart; a rescan of the same file doesn't offer it again.
        val again = service(Phones.MID, scanner = scanner)
        assertEquals(f.id, again.pick(Capability.TEXT)?.entry?.id)
        again.onVisible()
        assertTrue(again.status().found.isEmpty())
        // Deleting forgets it entirely.
        again.delete(f.id)
        assertTrue(again.status().packs.none { it.id == f.id })
        assertNull(again.pick(Capability.TEXT))
    }

    @Test fun aCustomCopyThatFailsLeavesNothingBehind() {
        val scanner = object : ModelScanner {
            override fun scan() = ScanResult(listOf(FoundFile("r1", "x.litertlm", customBytes.size.toLong(), "D", "Download")), "app", 0)
            override fun open(ref: String) = ByteArrayInputStream(customBytes.copyOf(1_000)) // truncated meanwhile
        }
        val s = service(Phones.MID, scanner = scanner)
        s.onVisible()
        val id = s.status().found.single().id
        s.adopt(id, trust = true)
        assertTrue(s.status().packs.none { it.id == id })
        assertEquals("Couldn’t use x: The file changed while it was copied", s.status().catalogDetail)
        assertTrue(files.final.isEmpty() && files.partial.isEmpty())
        assertNull(settings.map[ModelService.KEY_CUSTOM])
    }
}
