package com.agentkeyboard.app.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.storage.StorageManager
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.core.inference.DeviceProfile
import com.agentkeyboard.core.inference.ProbeStore
import com.agentkeyboard.core.models.HttpBody
import com.agentkeyboard.core.models.HttpSource
import com.agentkeyboard.core.models.ModelEntry
import com.agentkeyboard.core.models.ModelFile
import com.agentkeyboard.core.models.ModelFiles
import com.agentkeyboard.core.models.NetworkState
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/*
 * Android adapters for the platform-free model ports in `core.models` / `core.inference` (ADR-0011).
 */

/**
 * Model files live in `noBackupFilesDir/models/<id>/`: app-private, never in backups or the APK
 * (SPEC §12.6), memory-mapped by LiteRT-LM and opened by sherpa-onnx from there. A download is written
 * to `<file>.part`, fsynced, then renamed over the final name, which is atomic within one directory: a
 * crash leaves either the old state or the new one, never a half-written file under the real name.
 * Every file of a multi-file model shares the model's directory, so the runtime gets one path.
 */
class AndroidModelFiles(context: Context) : ModelFiles {
    private val root = File(context.noBackupFilesDir, "models").apply { mkdirs() }
    private val storage = context.getSystemService(StorageManager::class.java)

    // Ids and file names are validated by ManifestParser (no separators, no "..").
    private fun dirOf(e: ModelEntry) = File(root, e.id)
    private fun part(e: ModelEntry, f: ModelFile) = File(dirOf(e), f.name + ".part")
    private fun final(e: ModelEntry, f: ModelFile) = File(dirOf(e), f.name)

    override fun partialLength(entry: ModelEntry, file: ModelFile): Long = part(entry, file).length()

    override fun openPartial(entry: ModelEntry, file: ModelFile, append: Boolean): OutputStream {
        dirOf(entry).mkdirs()
        val out = FileOutputStream(part(entry, file), append)
        // fsync on close so a committed file's bytes are on disk before the rename that publishes it.
        return object : FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                try {
                    out.flush()
                    out.fd.sync()
                } finally {
                    out.close()
                }
            }
        }
    }

    override fun readPartial(entry: ModelEntry, file: ModelFile): InputStream = FileInputStream(part(entry, file))

    override fun deletePartial(entry: ModelEntry, file: ModelFile) {
        part(entry, file).delete()
    }

    override fun commit(entry: ModelEntry, file: ModelFile) {
        if (!part(entry, file).renameTo(final(entry, file))) throw IOException("rename failed")
    }

    override fun hasFinal(entry: ModelEntry, file: ModelFile): Boolean {
        val f = final(entry, file)
        if (f.isFile) return f.length() == file.sizeBytes
        // Part 3 kept single-file models flat in models/; move one into its directory once.
        val legacy = File(root, file.name)
        if (entry.files.size == 1 && legacy.isFile && legacy.length() == file.sizeBytes) {
            dirOf(entry).mkdirs()
            return legacy.renameTo(f)
        }
        return false
    }

    override fun location(entry: ModelEntry): String = dirOf(entry).absolutePath

    override fun deleteAll(entry: ModelEntry) {
        dirOf(entry).listFiles()?.forEach { it.delete() }
        dirOf(entry).delete()
    }

    /** Allocatable (may free cache files) rather than merely free, as the platform recommends. */
    override fun freeBytes(): Long = try {
        storage.getAllocatableBytes(storage.getUuidForPath(root))
    } catch (e: IOException) {
        root.usableSpace
    }

    override fun cachedManifest(): String? = File(root, MANIFEST).takeIf { it.isFile }?.readText()

    override fun cacheManifest(envelope: String) = writeAtomically(File(root, MANIFEST), envelope)

    companion object {
        private const val MANIFEST = "models.json"
        /** Read by the keyboard process to find the voice model (see [SttPointer]). */
        const val STT_POINTER = "active-stt.json"

        fun root(context: Context) = File(context.noBackupFilesDir, "models")

        fun writeAtomically(target: File, text: String) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("couldn't replace ${target.name}") // callers retry; never report a stale file as written
            }
        }
    }
}

/**
 * HTTPS with `Range` resume. Redirects (Hugging Face → its CDN) are followed by HttpURLConnection
 * within https. Only constructed in the `online` flavor; the `offline` one has no INTERNET permission.
 */
class AndroidHttp : HttpSource {
    override fun get(url: String, fromByte: Long): HttpBody {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept-Encoding", "identity") // byte offsets must match the file
        if (fromByte > 0) c.setRequestProperty("Range", "bytes=$fromByte-")
        val status = c.responseCode
        val start = if (status == 206) RANGE.find(c.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull() else null
        val stream = if (status in 200..299) c.inputStream else (c.errorStream ?: ByteArrayInputStream(ByteArray(0)))
        return HttpBody(status, start, stream) { c.disconnect() }
    }

    private companion object {
        val RANGE = Regex("""bytes (\d+)-""")
    }
}

/** Connectivity for the Wi-Fi-only rule; [onChange] fires on the default network's changes. */
class AndroidNetwork(context: Context, private val onChange: () -> Unit) : NetworkState {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChange()
        override fun onLost(network: Network) = onChange()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange()
    }

    init {
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: RuntimeException) { // e.g. the per-app callback limit
            AkLog.w("network callback unavailable")
        }
    }

    private fun caps(): NetworkCapabilities? = cm.activeNetwork?.let(cm::getNetworkCapabilities)

    override fun connected(): Boolean = caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    override fun unmetered(): Boolean = caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
}

/** Backend probe results. `commit()`, not `apply()`: the write must land before a possible native crash. */
class PrefsProbeStore(context: Context) : ProbeStore {
    private val prefs = context.getSharedPreferences("ak_model_probes", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }
}

/**
 * GPU is allowed everywhere and proven per model build by ModelManager's crash-safe probe. NPU needs
 * vendor libraries this build doesn't ship, so it stays off. Throttling follows the thermal API.
 */
class AndroidDevice(context: Context) : DeviceProfile {
    private val power = context.getSystemService(PowerManager::class.java)
    override fun gpuAllowed(): Boolean = true
    override fun npuAllowed(): Boolean = false
    override fun thermalThrottled(): Boolean = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
    override val cpuCores: Int = Runtime.getRuntime().availableProcessors()
}
