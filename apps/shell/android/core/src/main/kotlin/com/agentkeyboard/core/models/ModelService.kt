package com.agentkeyboard.core.models

import com.agentkeyboard.core.agent.SettingsStore
import com.agentkeyboard.core.json.JsonObj
import com.agentkeyboard.core.json.JsonValue
import com.agentkeyboard.core.json.arr
import com.agentkeyboard.core.json.arrOrNull
import com.agentkeyboard.core.json.bool
import com.agentkeyboard.core.json.long
import com.agentkeyboard.core.json.obj
import com.agentkeyboard.core.json.objOrNull
import com.agentkeyboard.core.json.str
import com.agentkeyboard.core.protocol.DeviceTier
import com.agentkeyboard.core.protocol.ModelPack
import com.agentkeyboard.core.protocol.ModelsStatus
import com.agentkeyboard.core.protocol.ResidentModel
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executor

/** A verified model file on disk, with the manifest entry that describes it. */
data class InstalledModel(val entry: ModelEntry, val path: String)

/** What the engine asks for; implemented by [ModelService]. Thread-safe (read from the engine thread). */
fun interface InstalledModels {
    fun pick(capability: Capability): InstalledModel?
}

interface NetworkState {
    fun connected(): Boolean
    /** Wi-Fi/Ethernet, or any network the user has not marked metered. */
    fun unmetered(): Boolean
}

/**
 * Model packs on this phone (SPEC §12.6, ADR-0011): the signed catalog, which packs the tier needs,
 * the download queue, verified installs, and the `ModelsStatus` the companion renders.
 *
 * Threading: like `AgentRuntime`, every public method runs on [serial]; transfers run on [io] and hop
 * back. [pick] is the one exception: it reads an immutable snapshot and may be called from any thread.
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
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val installer: ModelInstaller = ModelInstaller(files, http),
) : InstalledModels {

    private class Job(val entry: ModelEntry, val import: Boolean) {
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
    private val notes = HashMap<String, Note>()
    private val queue = ArrayList<String>()
    private var running: Job? = null
    private var transferring = false
    /** Non-null: transfers are held (FGS time limit, shutdown) until the Models screen is opened again. */
    private var suspended: String? = null

    /** Outcome of the last import that didn't start (shown on the Models screen until the next one). */
    private var importNote: String? = null

    @Volatile private var snapshot: List<InstalledModel> = emptyList()
    @Volatile private var wifiOnly = true

    // ───────────────────────────── lifecycle ─────────────────────────────

    /** Loads the cached catalog and install records. No network. Call once on the serial thread. */
    fun start() {
        wifiOnly = settings.get(KEY_WIFI_ONLY).bool() ?: true
        settings.get(KEY_INSTALLED).objOrNull()?.fields?.forEach { (id, v) ->
            val o = v.objOrNull() ?: return@forEach
            val sha = o["sha256"].str() ?: return@forEach
            installed[id] = Record(sha, o["at"].long() ?: 0L)
        }
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
     * The companion is showing the Models screen: refresh a stale catalog and resume a queue left by
     * a previous process. Starting transfers only from here keeps the dataSync FGS start legal (the
     * app has a visible activity) and never surprises the user with background traffic.
     */
    fun onVisible() {
        suspended = null
        refreshCatalog(force = false)
        kick()
    }

    /**
     * Holds all transfers (Android 15's dataSync time limit, process shutdown). The running one stops
     * at the next chunk and keeps its partial file; the queue resumes from [onVisible].
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

    override fun pick(capability: Capability): InstalledModel? {
        val candidates = snapshot.filter { it.entry.generative && capability in it.entry.capabilities }
        // Text prefers a plain LLM over a VLM (Tier S keeps the VLM for on-demand vision only); within
        // a kind, the largest model allowed on this tier wins (an installed optional E4B beats E2B).
        val primary = if (capability == Capability.VISION) ModelKind.VLM else ModelKind.LLM
        return candidates.sortedWith(
            compareByDescending<InstalledModel> { tier in it.entry.tiers }
                .thenByDescending { it.entry.kind == primary || capability == Capability.VISION }
                .thenByDescending { it.entry.ramMb },
        ).firstOrNull()
    }

    /** A generative model for this tier is installed (`SystemStatus.modelsInstalled`). */
    fun hasTextModel(): Boolean = pick(Capability.TEXT) != null

    fun status(): ModelsStatus {
        val m = manifest
        val entries = m?.entries.orEmpty().filter { e ->
            (tier in e.tiers && e.minAppVersion <= appVersion) || e.id in installed
        }
        val packs = entries.map { pack(it) }.sortedWith(compareByDescending<ModelPack> { it.core }.thenBy { KIND_ORDER.indexOf(it.kind) })
        return ModelsStatus(
            tier = tier, source = source, catalog = catalog, catalogDetail = catalogDetail ?: importNote, packs = packs,
            freeBytes = files.freeBytes(), wifiOnly = wifiOnly, resident = residency(),
        )
    }

    private fun pack(e: ModelEntry): ModelPack {
        val rec = installed[e.id]
        val job = running?.takeIf { it.entry.id == e.id }
        val note = notes[e.id]
        val (state, received, detail) = when {
            job != null -> Triple(ModelPack.DOWNLOADING, job.received, if (job.import) "Installing from file" else null)
            e.id in queue -> Triple(ModelPack.QUEUED, files.partialLength(e), note?.detail)
            note?.state == ModelPack.FAILED -> Triple(ModelPack.FAILED, files.partialLength(e), note.detail)
            rec != null -> Triple(ModelPack.INSTALLED, e.sizeBytes, null)
            files.partialLength(e) > 0 -> Triple(ModelPack.PAUSED, files.partialLength(e), note?.detail ?: "Paused")
            else -> Triple(ModelPack.AVAILABLE, 0L, null)
        }
        return ModelPack(
            id = e.id, name = e.name, kind = e.kind.wire, role = e.role, sizeBytes = e.sizeBytes, license = e.license,
            licenseUrl = e.licenseUrl, core = tier in e.coreFor, state = state, receivedBytes = received, detail = detail,
            updateAvailable = if (rec != null && rec.sha256 != e.sha256) true else null,
        )
    }

    // ───────────────────────────── commands ─────────────────────────────

    /** Queues [ids] (in order). Unknown ids, other tiers' packs and up-to-date installs are skipped. */
    fun download(ids: List<String>) {
        val m = manifest ?: return emit()
        if (source != SOURCE_DOWNLOAD || http == null) return emit()
        suspended = null // the user asked for it, from the Models screen
        for (id in ids) {
            val e = m.entry(id) ?: continue
            if (tier !in e.tiers || e.minAppVersion > appVersion) continue
            if (installed[id]?.sha256 == e.sha256) continue
            if (id !in queue) queue += id
            notes.remove(id)
        }
        persistQueue()
        kick()
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
        val m = manifest
        running?.takeIf { it.entry.id == id }?.let {
            it.userCancelled = true
            it.deleted = true
            it.stop = true
        }
        queue.remove(id)
        persistQueue()
        notes.remove(id)
        val e = m?.entry(id)
        if (e != null) {
            files.deletePartial(e)
            if (running?.entry?.id != id) files.deleteFinal(e)
        }
        if (installed.remove(id) != null) {
            persistInstalled()
            onRemoved(id)
        }
        publishSnapshot()
        emit()
    }

    /**
     * Installs one file picked by the user. A small JSON file is treated as a signed catalog; anything
     * else must match a catalog entry by file name and then hash to it exactly ([ModelInstaller.import]).
     * Returns true when [open] will be called (the caller owns the underlying descriptor otherwise).
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
        val e = m.entries.firstOrNull { it.file.equals(name, ignoreCase = true) }
            ?: m.entries.singleOrNull { it.sizeBytes == size && tier in it.tiers }
        if (e == null || running != null) {
            importNote = if (e == null) "That file isn’t a model in the catalog" else "Wait for the current download to finish"
            emit()
            return false
        }
        val job = Job(e, import = true)
        running = job
        notes.remove(e.id)
        transfer(true)
        emit()
        io.execute {
            val outcome = try {
                open().use { input -> installer.import(e, input, shouldStop = { job.stop }, onProgress = { n -> progress(job, n) }) }
            } catch (ex: IOException) {
                InstallOutcome.Failed("Couldn’t read the file", retryable = true)
            }
            serial.execute { finished(job, outcome) }
        }
        return true
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

    /** Drops records whose file vanished and deletes files the catalog no longer lists. */
    private fun reconcile() {
        val m = manifest
        var changed = false
        val it = installed.entries.iterator()
        while (it.hasNext()) {
            val (id, _) = it.next()
            val e = m?.entry(id)
            if (m != null && e == null) {
                // Withdrawn from the signed catalog (licence, safety): remove unless it is in use.
                if (residency()?.id == id) continue
                log("removing withdrawn model $id")
                it.remove()
                changed = true
                continue
            }
            if (e != null && files.finalPath(e) == null) {
                it.remove()
                changed = true
            }
        }
        if (changed) persistInstalled()
        queue.retainAll { id -> m == null || m.entry(id) != null }
        publishSnapshot()
    }

    private fun publishSnapshot() {
        val m = manifest ?: return run { snapshot = emptyList() }
        snapshot = installed.keys.mapNotNull { id ->
            val e = m.entry(id) ?: return@mapNotNull null
            files.finalPath(e)?.let { InstalledModel(e, it) }
        }
    }

    // ───────────────────────────── transfers ─────────────────────────────

    private fun allowedNow(): Boolean = network.connected() && (!wifiOnly || network.unmetered())

    private fun waitingReason(): String = if (!network.connected()) "Waiting for a connection" else "Waiting for Wi-Fi"

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
        val job = Job(e, import = false).apply { received = files.partialLength(e) }
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
        val id = job.entry.id
        var resume = true
        if (job.deleted) {
            // delete() ran while the transfer was stopping: drop whatever it wrote, even a complete file.
            queue.remove(id)
            notes.remove(id)
            files.deletePartial(job.entry)
            if (outcome is InstallOutcome.Installed) files.deleteFinal(job.entry)
            persistQueue()
            publishSnapshot()
            kick()
            return emit()
        }
        when (outcome) {
            is InstallOutcome.Installed -> {
                queue.remove(id)
                installed[id] = Record(job.entry.sha256, clock())
                persistInstalled()
                log("installed $id")
            }
            is InstallOutcome.Paused -> if (suspended != null && !job.userCancelled && !job.import) {
                notes[id] = Note(ModelPack.PAUSED, suspended)
                resume = false
            } else if (job.userCancelled || job.import) {
                queue.remove(id)
                notes[id] = Note(ModelPack.PAUSED, "Paused")
            } else {
                notes[id] = Note(ModelPack.PAUSED, waitingReason()) // network policy: resume on networkChanged
                resume = false
            }
            is InstallOutcome.Failed -> {
                queue.remove(id)
                notes[id] = Note(ModelPack.FAILED, outcome.reason)
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

    private fun emit() = onChanged(status())

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
        const val KEY_WIFI_ONLY = "models.wifiOnly"
        const val KEY_INSTALLED = "models.installed"
        const val KEY_QUEUE = "models.queue"
        const val CATALOG_TTL_MS = 6 * 60 * 60 * 1000L
        const val PROGRESS_EMIT_MS = 500L
        const val MAX_MANIFEST_BYTES = 256L * 1024
        private val KIND_ORDER = listOf("llm", "vlm", "embedding", "stt", "tts")
    }
}
