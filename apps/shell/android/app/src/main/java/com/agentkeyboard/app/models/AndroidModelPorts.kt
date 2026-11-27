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
 * Model files live in `noBackupFilesDir/models`: app-private, never in backups or the APK
 * (SPEC §12.6), and memory-mapped by LiteRT-LM from there. A download is written to `<file>.part`,
 * fsynced, then renamed over the final name, which is atomic within one directory: a crash leaves
 * either the old state or the new one, never a half-written model under the real name.
 */
class AndroidModelFiles(context: Context) : ModelFiles {
    private val dir = File(context.noBackupFilesDir, "models").apply { mkdirs() }
    private val storage = context.getSystemService(StorageManager::class.java)

    private fun part(e: ModelEntry) = File(dir, e.file + ".part")
    private fun final(e: ModelEntry) = File(dir, e.file)

    override fun partialLength(entry: ModelEntry): Long = part(entry).length()

    override fun openPartial(entry: ModelEntry, append: Boolean): OutputStream {
        val out = FileOutputStream(part(entry), append)
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

    override fun readPartial(entry: ModelEntry): InputStream = FileInputStream(part(entry))

    override fun deletePartial(entry: ModelEntry) {
        part(entry).delete()
    }

    override fun commit(entry: ModelEntry): String {
        val target = final(entry)
        if (!part(entry).renameTo(target)) throw IOException("rename failed")
        return target.absolutePath
    }

    override fun finalPath(entry: ModelEntry): String? = final(entry).takeIf { it.isFile && it.length() == entry.sizeBytes }?.absolutePath

    override fun deleteFinal(entry: ModelEntry) {
        final(entry).delete()
    }

    /** Allocatable (may free cache files) rather than merely free, as the platform recommends. */
    override fun freeBytes(): Long = try {
        storage.getAllocatableBytes(storage.getUuidForPath(dir))
    } catch (e: IOException) {
        dir.usableSpace
    }

    override fun cachedManifest(): String? = File(dir, MANIFEST).takeIf { it.isFile }?.readText()

    override fun cacheManifest(envelope: String) {
        val tmp = File(dir, "$MANIFEST.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(envelope.toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(File(dir, MANIFEST))) tmp.delete()
    }

    private companion object {
        const val MANIFEST = "models.json"
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
