package com.agentkeyboard.core.models

import com.agentkeyboard.core.agent.SettingsStore
import com.agentkeyboard.core.json.JsonNum
import com.agentkeyboard.core.json.JsonObj
import com.agentkeyboard.core.json.JsonValue
import com.agentkeyboard.core.json.arr
import com.agentkeyboard.core.json.arrOrNull
import com.agentkeyboard.core.json.bool
import com.agentkeyboard.core.json.JsonStr
import com.agentkeyboard.core.json.num
import com.agentkeyboard.core.json.long
import com.agentkeyboard.core.json.obj
import com.agentkeyboard.core.json.objOrNull
import com.agentkeyboard.core.json.str
import com.agentkeyboard.core.protocol.DeviceInfo
import com.agentkeyboard.core.protocol.DeviceTier
import com.agentkeyboard.core.protocol.FoundModelInfo
import com.agentkeyboard.core.protocol.ModelPack
import com.agentkeyboard.core.protocol.ModelsStatus
import com.agentkeyboard.core.protocol.ResidentModel
import com.agentkeyboard.core.protocol.ModelScanInfo
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executor

/** A verified model on disk: its entry and its directory. */
data class InstalledModel(val entry: ModelEntry, val path: String) {
    fun file(name: String): String = "$path/$name"
    /** The primary file (LiteRT-LM models are a single file). */
    val primary: String get() = file(entry.file)
}

/** What the runtimes ask for; implemented by [ModelService]. Thread-safe (read from engine/IME threads). */
fun interface InstalledModels {
    fun pick(capability: Capability): InstalledModel?
}

interface NetworkState {
    fun connected(): Boolean
    /** Wi-Fi/Ethernet, or any network the user has not marked metered. */
    fun unmetered(): Boolean
}

/**
 * Model packs on this phone (SPEC §12.6, ADR-0011, ADR-0012): the signed catalog, what this device
 * should run ([Recommender]), the download queue, models found on the phone, verified installs, the
 * model the user chose for each job, and the `ModelsStatus` the companion renders.
 *
 * Threading: like `AgentRuntime`, every public method runs on [serial]; transfers and scans run on [io]
 * and hop back. [pick] is the one exception: it reads an immutable snapshot from any thread.
 */
class ModelService(
    private val tier: DeviceTier,
    private val appVersion: Int,
    /** `download` (online flavor) or `import` (offline flavor, no INTERNET permission). */
    private val source: String,
    private val manifestUrl: String?,
    private val verifier: ManifestVerifier,
    private val files: ModelFiles,
    private val http: HttpSource?,
    private val settings: SettingsStore,
    private val network: NetworkState,
    private val io: Executor,
    private val serial: Executor,
    private val onChanged: (ModelsStatus) -> Unit,
    /** True while bytes are moving: the host keeps a dataSync foreground service up. */
    private val onTransfer: (Boolean) -> Unit = {},
    /** An installed model was deleted; the engine unloads it when idle. */
    private val onRemoved: (String) -> Unit = {},
    private val residency: () -> ResidentModel? = { null },
    /** The real hardware; drives recommendations instead of the tier table. */
    private val specs: DeviceSpecs = DeviceSpecs(totalRamMb = 6_144, cpuCores = 8, gpu = false),
    /** Lists model files already on the phone; null where the host can't (tests, web). */
    private val scanner: ModelScanner? = null,
    /** RECORD_AUDIO is granted (voice typing needs it; the IME can't ask). */
    private val micGranted: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val installer: ModelInstaller = ModelInstaller(files, http),
) : InstalledModels {

    private class Job(val entry: ModelEntry, val import: Boolean, val found: FoundModel? = null) {
        @Volatile var stop = false
        @Volatile var userCancelled = false
        /** Deleted while running: whatever it wrote is discarded when it stops. */
        @Volatile var deleted = false
        @Volatile var received = 0L
        var lastEmit = 0L
    }

    private data class Note(val state: String, val detail: String?)
    private data class Record(val sha256: String, val at: Long)

    private var manifest: ModelManifest? = null
    private var catalog = CATALOG_LOADING
    private var catalogDetail: String? = null
    private var fetching = false
    private var lastFetch = 0L
    private val installed = LinkedHashMap<String, Record>()
    /** Unverified models the user chose to trust, by id (persisted; not in any catalog). */
    private val custom = LinkedHashMap<String, ModelEntry>()
    private val notes = HashMap<String, Note>()
    private val queue = ArrayList<String>()
    private var running: Job? = null
    private var transferring = false
    /** Non-null: transfers are held (FGS time limit, shutdown) until the Models screen is opened again. */
    private var suspended: String? = null
    /** Outcome of the last import/adopt that didn't start (shown on the Models screen until the next one). */
    private var importNote: String? = null

    private var found: List<FoundModel> = emptyList()
    private var scanning = false
    private var lastScan = 0L
    private var scanAccess = ACCESS_APP
    private var scanFolders = 0
    private var scanAllOption = false

    /** Decode tokens/s measured on this phone, by model id (smoothed). */
    private val speed = HashMap<String, Double>()
    /** The model the user chose per job (`text`, `transcribe`); otherwise the best installed one is used. */
    private val active = HashMap<String, String>()
    private var advice: Map<String, Advice> = emptyMap()

    @Volatile private var snapshot: List<InstalledModel> = emptyList()
    @Volatile private var wifiOnly = true

    // ───────────────────────────── lifecycle ─────────────────────────────

    /** Loads the cached catalog and records. No network, no scan. Call once on the serial thread. */
    fun start() {
        wifiOnly = settings.get(KEY_WIFI_ONLY).bool() ?: true
        settings.get(KEY_INSTALLED).objOrNull()?.fields?.forEach { (id, v) ->
            val o = v.objOrNull() ?: return@forEach
            val sha = o["sha256"].str() ?: return@forEach
            installed[id] = Record(sha, o["at"].long() ?: 0L)
        }
        settings.get(KEY_CUSTOM).arrOrNull()?.items?.forEach { v -> decodeCustom(v)?.let { custom[it.id] = it } }
        settings.get(KEY_SPEED).objOrNull()?.fields?.forEach { (id, v) -> v.num()?.let { speed[id] = it } }
        settings.get(KEY_ACTIVE).objOrNull()?.fields?.forEach { (job, v) -> v.str()?.let { active[job] = it } }
        settings.get(KEY_QUEUE).arrOrNull()?.items?.mapNotNullTo(queue) { it.str() }
        val cached = files.cachedManifest()
        when {
            cached != null -> applyEnvelope(cached, persist = false)
            source == SOURCE_DOWNLOAD && (manifestUrl.isNullOrBlank() || !verifier.configured) ->
                setCatalog(CATALOG_UNCONFIGURED, "This build has no model catalog configured")
            source == SOURCE_IMPORT -> setCatalog(CATALOG_UNAVAILABLE, "Import the signed catalog (models.json) to begin")
            else -> setCatalog(CATALOG_UNAVAILABLE, "Connect to the internet to load the model catalog")
        }
        reconcile()
    }

    /**
     * The companion is showing the Models screen: refresh a stale catalog, look for models already on
     * the phone, and resume a queue left by a previous process. Starting transfers only from here keeps
     * the dataSync FGS start legal (the app has a visible activity) and never surprises the user.
     */
    fun onVisible() {
        suspended = null
        refreshCatalog(force = false)
        if (clock() - lastScan >= RESCAN_MS || lastScan == 0L) scan()
        kick()
    }

    /**
     * Holds all transfers (Android 15's dataSync time limit, process shutdown). The running one stops
     * at the next chunk and keeps its partial files; the queue resumes from [onVisible].
     */
    fun suspend(reason: String) {
        suspended = reason
        running?.stop = true
        emit()
    }

    fun networkChanged() = kick()

    fun onSetting(key: String) {
        if (key != KEY_WIFI_ONLY) return
        wifiOnly = settings.get(KEY_WIFI_ONLY).bool() ?: true
        kick()
        emit()
    }

    // ───────────────────────────── queries ─────────────────────────────

    /**
     * The model to run for [capability]: the one the user chose for that job if installed and able,
     * else the best installed one for this phone (recommended first, then smartest that fits).
     */
    override fun pick(capability: Capability): InstalledModel? =
        snapshot.firstOrNull { capability in it.entry.capabilities && (capability == Capability.TRANSCRIBE || it.entry.generative) }

    /** A generative model is installed (`SystemStatus.modelsInstalled`). */
    fun hasTextModel(): Boolean = pick(Capability.TEXT) != null

    /** Every entry the screen may list: the catalog (for this app version) plus trusted custom models. */
    private fun entries(): List<ModelEntry> =
        manifest?.entries.orEmpty().filter { it.minAppVersion <= appVersion || it.id in installed } + custom.values

    private fun entry(id: String): ModelEntry? = custom[id] ?: manifest?.entry(id)

    private fun jobOf(e: ModelEntry) = if (e.kind == ModelKind.STT) JOB_TRANSCRIBE else JOB_TEXT

    fun status(): ModelsStatus {
        val packs = entries().map { pack(it) }.sortedWith(
            compareBy<ModelPack> { FIT_ORDER.indexOf(it.fit) }.thenBy { KIND_ORDER.indexOf(it.kind) }
                .thenByDescending { entry(it.id)?.quality ?: 0 },
        )
        return ModelsStatus(
            tier = tier, source = source, catalog = catalog, catalogDetail = catalogDetail ?: importNote, packs = packs,
            freeBytes = files.freeBytes(), wifiOnly = wifiOnly, resident = residency(),
            device = DeviceInfo(specs.totalRamMb, Recommender.marketedGb(specs.totalRamMb), specs.gpu, specs.soc),
            found = found.filter { it.id != running?.found?.id }.map { f ->
                FoundModelInfo(f.id, f.name, f.kind.wire, f.sizeBytes, f.where, verified = f.entryId != null, entryId = f.entryId)
            },
            scan = if (scanner == null) null else ModelScanInfo(if (scanning) "scanning" else "idle", scanAccess, scanFolders, scanAllOption),
            micGranted = micGranted(),
        )
    }

    private fun pack(e: ModelEntry): ModelPack {
        val rec = installed[e.id]
        val job = running?.takeIf { it.entry.id == e.id }
        val note = notes[e.id]
        val onDisk = files.bytesOnDisk(e)
        val (state, received, detail) = when {
            job != null -> Triple(ModelPack.DOWNLOADING, job.received, if (job.import) "Copying and verifying" else null)
            e.id in queue -> Triple(ModelPack.QUEUED, onDisk, note?.detail)
            note?.state == ModelPack.FAILED -> Triple(ModelPack.FAILED, onDisk, note.detail)
            rec != null -> Triple(ModelPack.INSTALLED, e.sizeBytes, null)
            onDisk > 0 -> Triple(ModelPack.PAUSED, onDisk, note?.detail ?: "Paused")
            else -> Triple(ModelPack.AVAILABLE, 0L, null)
        }
        val a = advice[e.id]
        val chosen = rec != null && snapshot.firstOrNull { jobOf(it.entry) == jobOf(e) }?.entry?.id == e.id
        return ModelPack(
            id = e.id, name = e.name, kind = e.kind.wire, role = e.role, sizeBytes = e.sizeBytes, license = e.license,
            licenseUrl = e.licenseUrl, core = a?.fit == Fit.RECOMMENDED, state = state, receivedBytes = received, detail = detail,
            updateAvailable = if (rec != null && !e.custom && rec.sha256 != e.sha256) true else null,
            fit = a?.fit?.wire, fitDetail = a?.detail, active = if (chosen) true else null, custom = if (e.custom) true else null,
        )
    }

    // ───────────────────────────── commands ─────────────────────────────

    /** Queues [ids] (in order). Unknown ids, models too big for this phone and up-to-date installs are skipped. */
    fun download(ids: List<String>) {
        val m = manifest ?: return emit()
        if (source != SOURCE_DOWNLOAD || http == null) return emit()
        suspended = null // the user asked for it, from the Models screen
        for (id in ids) {
            val e = m.entry(id) ?: continue
            if (e.minAppVersion > appVersion || advice[id]?.fit == Fit.TOO_BIG) continue
            if (installed[id]?.sha256 == e.sha256) continue
            if (id !in queue) queue += id
            notes.remove(id)
        }
        persistQueue()
        kick()
        emit()
    }

    /** The user picked [id] for its job (text or voice). Takes effect on the next run. */
    fun use(id: String) {
        val e = entry(id) ?: return
        if (id !in installed) return
        active[jobOf(e)] = id
        persistActive()
        publishSnapshot()
        emit()
    }

    fun cancel(id: String) {
        running?.takeIf { it.entry.id == id }?.let {
            it.userCancelled = true
            it.stop = true
            return // finished() updates the state
        }
        if (queue.remove(id)) {
            persistQueue()
            notes[id] = Note(ModelPack.PAUSED, "Paused")
            emit()
        }
    }

    fun delete(id: String) {
        running?.takeIf { it.entry.id == id }?.let {
            it.userCancelled = true
            it.deleted = true
            it.stop = true
        }
        queue.remove(id)
        persistQueue()
        notes.remove(id)
        val e = entry(id)
        if (e != null) {
            if (running?.entry?.id != id) files.deleteAll(e) else files.deletePartials(e)
        }
        if (installed.remove(id) != null) {
            persistInstalled()
            onRemoved(id)
        }
        if (custom.remove(id) != null) persistCustom()
        if (active.values.remove(id)) persistActive()
        refreshAdvice()
        publishSnapshot()
        emit()
    }

    /**
     * Installs one file picked by the user. A small JSON file is treated as a signed catalog; anything
     * else must be a file of a catalog entry (by name, or by exact size for a renamed single-file
     * model) and then hash to it exactly. Returns true when [open] will be called (the caller owns the
     * underlying descriptor otherwise).
     */
    fun import(name: String, size: Long, open: () -> InputStream): Boolean {
        if (name.endsWith(".json", ignoreCase = true) && size in 1..MAX_MANIFEST_BYTES) {
            io.execute {
                val text = try {
                    open().use { readCapped(it) }
                } catch (e: IOException) {
                    null
                }
                serial.execute {
                    if (text == null) {
                        if (manifest == null) setCatalog(CATALOG_INVALID, "Couldn’t read $name") else importNote = "Couldn’t read $name"
                    } else {
                        importNote = applyEnvelope(text, persist = true)
                    }
                    reconcile()
                    emit()
                }
            }
            return true
        }
        importNote = null
        val m = manifest ?: run {
            setCatalog(CATALOG_UNAVAILABLE, "Import the signed catalog (models.json) first")
            emit()
            return false
        }
        val byName = m.entries.firstNotNullOfOrNull { e -> e.files.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { e to it } }
        val target = byName ?: m.entries.singleOrNull { it.files.size == 1 && it.sizeBytes == size }?.let { it to it.files[0] }
        if (target == null || running != null) {
            importNote = if (target == null) "That file isn’t a model in the catalog" else "Wait for the current download to finish"
            emit()
            return false
        }
        val (e, file) = target
        start(Job(e, import = true)) { job ->
            installer.import(e, { f -> if (f === file) open() else null }, shouldStop = { job.stop }, onProgress = { n -> progress(job, n) })
        }
        return true
    }

    // ───────────────────────────── models already on the phone ─────────────────────────────

    /** Looks for model files in the places this app may read (bounded; runs on [io]). */
    fun scan() {
        val s = scanner ?: return
        if (scanning) return
        scanning = true
        emit()
        io.execute {
            val r = try {
                s.scan()
            } catch (e: Exception) { // a provider misbehaving must not take the service down
                log("scan failed: ${e.javaClass.simpleName}")
                null
            }
            serial.execute {
                scanning = false
                lastScan = clock()
                if (r != null) {
                    scanAccess = r.access
                    scanFolders = r.folders
                    scanAllOption = r.allFilesOption
                    found = ModelMatcher.match(manifest?.entries.orEmpty(), r.files, installed.mapValues { it.value.sha256 })
                    log("scan: ${r.files.size} candidate files, ${found.size} models")
                }
                emit()
            }
        }
    }

    /**
     * Installs a model found by [scan]. Catalog matches are copied through the verifier (used only if
     * every byte matches the signed hash). An unverified model needs [trust]: the user confirmed that
     * they want to run a file no catalog vouches for.
     */
    fun adopt(id: String, trust: Boolean) {
        importNote = null
        val f = found.firstOrNull { it.id == id } ?: return emit()
        val s = scanner ?: return emit()
        if (running != null) {
            importNote = "Wait for the current download to finish"
            return emit()
        }
        val entryId = f.entryId
        if (entryId != null) {
            val e = manifest?.entry(entryId) ?: return emit()
            start(Job(e, import = true, found = f)) { job ->
                installer.import(e, { mf -> f.refs[mf.name]?.let(s::open) }, shouldStop = { job.stop }, onProgress = { n -> progress(job, n) })
            }
            return
        }
        if (!trust) {
            importNote = "Confirm that you trust ${f.name} to use it"
            return emit()
        }
        val provisional = ModelMatcher.customEntry(f, null)
        custom[provisional.id] = provisional // listed (with progress) while it copies; persisted once measured
        refreshAdvice()
        start(Job(provisional, import = true, found = f)) { job ->
            val (outcome, measured) = s.open(f.refs.values.single()).use { input ->
                installer.importUnverified(provisional, input, shouldStop = { job.stop }, onProgress = { n -> progress(job, n) })
            }
            if (measured != null) pendingCustom = ModelMatcher.customEntry(f, measured)
            outcome
        }
    }

    /** Written on [io] by an unverified adopt, read on [serial] in [finished] (ordered by the executor hop). */
    @Volatile private var pendingCustom: ModelEntry? = null

    // ───────────────────────────── speed ─────────────────────────────

    /** Decode speed observed by the engine. Smoothed, persisted, and fed back into the advice. */
    fun recordSpeed(id: String, tokensPerSec: Double) {
        if (tokensPerSec.isNaN() || tokensPerSec <= 0 || tokensPerSec > 500) return
        val prev = speed[id]
        speed[id] = if (prev == null) tokensPerSec else prev * 0.7 + tokensPerSec * 0.3
        settings.put(KEY_SPEED, JsonObj(speed.mapValues { (_, v) -> JsonNum(Math.round(v * 10) / 10.0) as JsonValue }))
        val before = advice[id]?.fit
        refreshAdvice()
        if (advice[id]?.fit != before) {
            publishSnapshot()
            emit()
        }
    }

    // ───────────────────────────── catalog ─────────────────────────────

    fun refreshCatalog(force: Boolean) {
        val url = manifestUrl
        if (source != SOURCE_DOWNLOAD || http == null || url.isNullOrBlank() || !verifier.configured || fetching) return
        if (!force && manifest != null && clock() - lastFetch < CATALOG_TTL_MS) return
        if (!network.connected()) {
            if (manifest == null) setCatalog(CATALOG_UNAVAILABLE, "Connect to the internet to load the model catalog")
            return emit()
        }
        fetching = true
        if (manifest == null) setCatalog(CATALOG_LOADING, null)
        io.execute {
            val text: String? = try {
                http.get(url, 0).use { body -> if (body.status == 200) readCapped(body.stream) else null }
            } catch (e: IOException) {
                null
            }
            serial.execute {
                fetching = false
                lastFetch = clock()
                if (text != null) applyEnvelope(text, persist = true)
                else if (manifest == null) setCatalog(CATALOG_UNAVAILABLE, "Couldn’t load the model catalog")
                reconcile()
                // A new catalog may recognise files the last scan couldn't.
                if (text != null && scanner != null && lastScan > 0) scan()
                emit()
            }
        }
    }

    /** Returns null on success, or a short reason the catalog was not applied. */
    private fun applyEnvelope(text: String, persist: Boolean): String? {
        val m = try {
            verifier.verify(text)
        } catch (e: ManifestException) {
            log("catalog rejected: ${e.message}")
            // A bad download never replaces a good cached catalog.
            if (manifest == null) setCatalog(CATALOG_INVALID, "The model catalog failed verification")
            return "The model catalog failed verification"
        }
        val current = manifest
        if (current != null && m.issuedAt < current.issuedAt) {
            log("catalog older than cached; ignored")
            return "That catalog is older than the one installed"
        }
        manifest = m
        setCatalog(CATALOG_READY, null)
        if (persist) files.cacheManifest(text)
        return null
    }

    private fun setCatalog(state: String, detail: String?) {
        catalog = state
        catalogDetail = detail
    }

    /** Drops records whose files vanished and models the catalog withdrew; recomputes the advice. */
    private fun reconcile() {
        val m = manifest
        var changed = false
        val it = installed.entries.iterator()
        while (it.hasNext()) {
            val (id, _) = it.next()
            val e = entry(id)
            if (m != null && e == null) {
                // Withdrawn from the signed catalog (licence, safety): remove unless it is in use.
                if (residency()?.id == id) continue
                log("removing withdrawn model $id")
                it.remove()
                changed = true
                continue
            }
            if (e != null && !files.complete(e)) {
                it.remove()
                changed = true
            }
        }
        if (changed) persistInstalled()
        queue.retainAll { id -> m == null || m.entry(id) != null }
        refreshAdvice()
        publishSnapshot()
    }

    private fun refreshAdvice() {
        advice = Recommender.advise(entries(), specs, speed)
    }

    /** Installed models in preference order: the user's choice per job, then the advice, then quality. */
    private fun publishSnapshot() {
        val list = installed.keys.mapNotNull { id ->
            val e = entry(id) ?: return@mapNotNull null
            if (e.minAppVersion > appVersion || !files.complete(e)) null else InstalledModel(e, files.location(e))
        }
        snapshot = list.sortedWith(
            compareByDescending<InstalledModel> { active[jobOf(it.entry)] == it.entry.id }
                .thenBy { FIT_ORDER.indexOf(advice[it.entry.id]?.fit?.wire) }
                .thenByDescending { it.entry.quality },
        )
    }

    // ───────────────────────────── transfers ─────────────────────────────

    private fun allowedNow(): Boolean = network.connected() && (!wifiOnly || network.unmetered())

    private fun waitingReason(): String = if (!network.connected()) "Waiting for a connection" else "Waiting for Wi-Fi"

    /** Runs a local install (import or adopt) as the single active job. */
    private fun start(job: Job, work: (Job) -> InstallOutcome) {
        running = job
        notes.remove(job.entry.id)
        transfer(true)
        emit()
        io.execute {
            val outcome = try {
                work(job)
            } catch (ex: IOException) {
                InstallOutcome.Failed("Couldn’t read the file", retryable = true)
            }
            serial.execute { finished(job, outcome) }
        }
    }

    private fun kick() {
        if (running != null) return
        if (suspended != null) {
            queue.firstOrNull()?.let { notes[it] = Note(ModelPack.PAUSED, suspended) }
            return transfer(false)
        }
        val m = manifest
        while (queue.isNotEmpty() && m?.entry(queue[0]) == null) queue.removeAt(0)
        val id = queue.firstOrNull() ?: return transfer(false)
        val e = m!!.entry(id)!!
        if (http == null || source != SOURCE_DOWNLOAD) return transfer(false)
        if (!allowedNow()) {
            notes[id] = Note(ModelPack.PAUSED, waitingReason())
            transfer(false)
            return emit()
        }
        val job = Job(e, import = false).apply { received = files.bytesOnDisk(e) }
        running = job
        notes.remove(id)
        transfer(true)
        emit()
        io.execute {
            val outcome = installer.download(e, shouldStop = { job.stop || !allowedNow() }, onProgress = { n -> progress(job, n) })
            serial.execute { finished(job, outcome) }
        }
    }

    private fun progress(job: Job, bytes: Long) {
        job.received = bytes
        serial.execute {
            val now = clock()
            if (running === job && now - job.lastEmit >= PROGRESS_EMIT_MS) {
                job.lastEmit = now
                emit()
            }
        }
    }

    private fun finished(job: Job, outcome: InstallOutcome) {
        if (running === job) running = null
        var entry = job.entry
        val id = entry.id
        var resume = true
        if (job.deleted) {
            // delete() ran while the transfer was stopping: drop whatever it wrote, even a complete model.
            queue.remove(id)
            notes.remove(id)
            files.deleteAll(entry)
            pendingCustom = null
            if (entry.custom) custom.remove(id)
            persistQueue()
            refreshAdvice()
            publishSnapshot()
            kick()
            return emit()
        }
        when (outcome) {
            is InstallOutcome.Installed -> {
                if (entry.custom) {
                    entry = pendingCustom ?: run {
                        custom.remove(id)
                        files.deleteAll(job.entry)
                        importNote = "Couldn’t use ${job.entry.name}"
                        refreshAdvice()
                        return emit()
                    }
                    pendingCustom = null
                    custom[id] = entry
                    persistCustom()
                }
                queue.remove(id)
                installed[id] = Record(entry.sha256, clock())
                persistInstalled()
                job.found?.let { f ->
                    found = found - f
                    importNote = "Installed ${entry.name} from ${f.where}. You can delete the original file to free ${gb(entry.sizeBytes)}."
                }
                // Something new to choose from: e.g. the first voice model makes voice typing work.
                refreshAdvice()
                log("installed $id")
            }
            is InstallOutcome.Incomplete -> {
                notes[id] = Note(ModelPack.PAUSED, "Still needed: ${outcome.missing.joinToString(", ")}")
            }
            is InstallOutcome.Paused -> if (suspended != null && !job.userCancelled && !job.import) {
                notes[id] = Note(ModelPack.PAUSED, suspended)
                resume = false
            } else if (job.userCancelled || job.import) {
                queue.remove(id)
                notes[id] = Note(ModelPack.PAUSED, "Paused")
                if (entry.custom) { // an unverified copy can't resume: start over next time
                    files.deleteAll(entry)
                    custom.remove(id)
                }
            } else {
                notes[id] = Note(ModelPack.PAUSED, waitingReason()) // network policy: resume on networkChanged
                resume = false
            }
            is InstallOutcome.Failed -> {
                queue.remove(id)
                if (entry.custom) {
                    files.deleteAll(entry)
                    custom.remove(id)
                    importNote = "Couldn’t use ${entry.name}: ${outcome.reason}"
                } else {
                    notes[id] = Note(ModelPack.FAILED, outcome.reason)
                }
                log("install failed for $id")
            }
        }
        persistQueue()
        publishSnapshot()
        if (resume) kick() else transfer(false)
        emit()
    }

    private fun transfer(on: Boolean) {
        if (on == transferring) return
        transferring = on
        onTransfer(on)
    }

    /** Stops transfers (process shutdown); partial files stay for the next resume. */
    fun shutdown() {
        suspended = "Paused"
        running?.stop = true
    }

    // ───────────────────────────── persistence ─────────────────────────────

    private fun persistInstalled() {
        settings.put(KEY_INSTALLED, JsonObj(installed.mapValues { (_, r) -> obj("sha256" to r.sha256, "at" to r.at) as JsonValue }))
    }

    private fun persistQueue() = settings.put(KEY_QUEUE, arr(queue))

    private fun persistActive() = settings.put(KEY_ACTIVE, JsonObj(active.mapValues { (_, v) -> JsonStr(v) as JsonValue }))

    private fun persistCustom() = settings.put(
        KEY_CUSTOM,
        arr(
            custom.values.map { e ->
                obj("id" to e.id, "name" to e.name, "file" to e.file, "sha256" to e.sha256, "sizeBytes" to e.sizeBytes)
            },
        ),
    )

    private fun decodeCustom(v: JsonValue): ModelEntry? {
        val o = v.objOrNull() ?: return null
        val id = o["id"].str()?.takeIf { it.startsWith(ModelMatcher.CUSTOM_PREFIX) } ?: return null
        val file = o["file"].str() ?: return null
        val sha = o["sha256"].str() ?: return null
        val size = o["sizeBytes"].long() ?: return null
        val found = FoundModel(id, o["name"].str() ?: "Model", ModelKind.LLM, size, "", null, mapOf(file to ""))
        return ModelMatcher.customEntry(found, Measured(sha, size))
    }

    private fun emit() = onChanged(status())

    private fun gb(bytes: Long): String = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"

    private fun readCapped(input: InputStream): String {
        // InputStream.readNBytes is API 33; minSdk is 29.
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_MANIFEST_BYTES) throw IOException("catalog too large")
        }
        return out.toString("UTF-8")
    }

    companion object {
        const val SOURCE_DOWNLOAD = "download"
        const val SOURCE_IMPORT = "import"
        const val CATALOG_READY = "ready"
        const val CATALOG_LOADING = "loading"
        const val CATALOG_UNCONFIGURED = "unconfigured"
        const val CATALOG_UNAVAILABLE = "unavailable"
        const val CATALOG_INVALID = "invalid"
        const val ACCESS_APP = "app"
        const val ACCESS_FOLDERS = "folders"
        const val ACCESS_ALL = "all"
        const val JOB_TEXT = "text"
        const val JOB_TRANSCRIBE = "transcribe"
        const val KEY_WIFI_ONLY = "models.wifiOnly"
        const val KEY_INSTALLED = "models.installed"
        const val KEY_QUEUE = "models.queue"
        const val KEY_CUSTOM = "models.custom"
        const val KEY_SPEED = "models.speed"
        const val KEY_ACTIVE = "models.active"
        const val CATALOG_TTL_MS = 6 * 60 * 60 * 1000L
        const val RESCAN_MS = 2 * 60 * 1000L
        const val PROGRESS_EMIT_MS = 500L
        const val MAX_MANIFEST_BYTES = 256L * 1024
        private val KIND_ORDER = listOf("llm", "vlm", "stt", "embedding", "tts")
        private val FIT_ORDER = listOf("recommended", "good", "slow", null, "too_big")
    }
}
