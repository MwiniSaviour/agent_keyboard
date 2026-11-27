package com.agentkeyboard.core.models

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/*
 * Resumable, verified model installation (SPEC §12.6, ADR-0011). Pure logic over two ports so it is
 * tested on the JVM with fakes; Android implements them with HttpURLConnection and app-private files.
 *
 *   partial file ──(HTTP Range from its length)──▶ append + streaming SHA-256
 *                ──size == expected──▶ digest == manifest sha256 ? atomic rename : delete partial
 *
 * Resume re-hashes the partial file first (sequential read, ~1 GB/s on UFS), so a resumed download
 * is verified exactly like a fresh one and nothing has to be trusted from a previous process.
 */

/** One HTTP response body. [status] 200 = full body, 206 = partial from [rangeStart]. */
class HttpBody(
    val status: Int,
    /** First byte of a 206 body (`Content-Range: bytes <start>-…`); null for 200. */
    val rangeStart: Long?,
    val stream: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        try {
            stream.close()
        } finally {
            onClose()
        }
    }
}

fun interface HttpSource {
    /** GET [url], asking for bytes from [fromByte] when it is > 0. Throws IOException on transport errors. */
    fun get(url: String, fromByte: Long): HttpBody
}

/** App-private model storage. Paths never leave this port except as the final file path for mmap. */
interface ModelFiles {
    fun partialLength(entry: ModelEntry): Long
    fun openPartial(entry: ModelEntry, append: Boolean): OutputStream
    fun readPartial(entry: ModelEntry): InputStream
    fun deletePartial(entry: ModelEntry)
    /** Atomically renames the verified partial to its final name; returns the final absolute path. */
    fun commit(entry: ModelEntry): String
    fun finalPath(entry: ModelEntry): String?
    fun deleteFinal(entry: ModelEntry)
    fun freeBytes(): Long
    /** The last verified signed catalog (kept verbatim so it is re-verified on every start). */
    fun cachedManifest(): String?
    fun cacheManifest(envelope: String)
}

sealed interface InstallOutcome {
    data class Installed(val path: String) : InstallOutcome
    /** Stopped by the caller (cancel, network policy, shutdown); the partial file is kept for resume. */
    data class Paused(val receivedBytes: Long) : InstallOutcome
    data class Failed(val reason: String, val retryable: Boolean) : InstallOutcome
}

class ModelInstaller(
    private val files: ModelFiles,
    private val http: HttpSource?,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    /**
     * Downloads (or resumes) [entry]. Blocking; call on an I/O thread. [shouldStop] is polled between
     * chunks; [onProgress] receives total bytes on disk at most every [PROGRESS_STEP] bytes.
     */
    fun download(entry: ModelEntry, shouldStop: () -> Boolean, onProgress: (Long) -> Unit): InstallOutcome {
        val source = http ?: return InstallOutcome.Failed("Downloads are not available in this build", retryable = false)
        var attempt = 0
        while (true) {
            if (shouldStop()) return InstallOutcome.Paused(files.partialLength(entry))
            val have = files.partialLength(entry)
            val needed = entry.sizeBytes - have + HEADROOM_BYTES
            if (files.freeBytes() < needed) return InstallOutcome.Failed("Not enough free storage", retryable = false)
            when (val r = attemptOnce(source, entry, shouldStop, onProgress)) {
                is Attempt.Done -> return r.outcome
                is Attempt.Transient -> {
                    if (++attempt > MAX_ATTEMPTS) return InstallOutcome.Failed(r.reason, retryable = true)
                    val wait = (BACKOFF_MS shl (attempt - 1)).coerceAtMost(MAX_BACKOFF_MS)
                    var waited = 0L
                    while (waited < wait) {
                        if (shouldStop()) return InstallOutcome.Paused(files.partialLength(entry))
                        sleep(SLEEP_SLICE_MS)
                        waited += SLEEP_SLICE_MS
                    }
                }
            }
        }
    }

    /**
     * Installs from a local stream (offline flavor import, SPEC §17): the bytes must hash to the
     * manifest entry, exactly like a download.
     */
    fun import(entry: ModelEntry, input: InputStream, shouldStop: () -> Boolean, onProgress: (Long) -> Unit): InstallOutcome {
        if (files.freeBytes() < entry.sizeBytes + HEADROOM_BYTES) return InstallOutcome.Failed("Not enough free storage", retryable = false)
        files.deletePartial(entry)
        val digest = sha256()
        val written = try {
            files.openPartial(entry, append = false).use { out -> copy(input, out, digest, 0, entry.sizeBytes, shouldStop, onProgress) }
        } catch (e: IOException) {
            files.deletePartial(entry)
            return InstallOutcome.Failed("Couldn’t read the file", retryable = true)
        }
        if (written < 0) {
            files.deletePartial(entry)
            return if (written == STOPPED) InstallOutcome.Paused(0) else InstallOutcome.Failed("The file is larger than expected", retryable = false)
        }
        return finish(entry, written, digest)
    }

    private sealed interface Attempt {
        data class Done(val outcome: InstallOutcome) : Attempt
        data class Transient(val reason: String) : Attempt
    }

    private fun attemptOnce(source: HttpSource, entry: ModelEntry, shouldStop: () -> Boolean, onProgress: (Long) -> Unit): Attempt {
        var have = files.partialLength(entry)
        if (have > entry.sizeBytes) {
            files.deletePartial(entry)
            have = 0
        }
        val digest = sha256()
        if (have > 0) {
            try {
                rehash(entry, have, digest)
            } catch (e: IOException) {
                files.deletePartial(entry)
                return Attempt.Transient("Couldn’t read the partial download")
            }
        }
        if (have == entry.sizeBytes) return Attempt.Done(finish(entry, have, digest))
        val body = try {
            source.get(entry.url, have)
        } catch (e: IOException) {
            return Attempt.Transient("Network error")
        }
        body.use {
            val append = when {
                body.status == 206 && body.rangeStart == have -> true
                body.status == 206 -> return Attempt.Transient("Server sent the wrong range")
                body.status == 200 -> false // server ignored Range: start over
                body.status == 416 -> { // our partial is unusable for this server
                    files.deletePartial(entry)
                    return Attempt.Transient("Server refused to resume")
                }
                body.status in 500..599 || body.status == 429 || body.status == 408 -> return Attempt.Transient("Server error ${body.status}")
                else -> return Attempt.Done(InstallOutcome.Failed("Download refused (${body.status})", retryable = false))
            }
            val start = if (append) have else 0L
            val hash = if (append) digest else sha256()
            if (!append) files.deletePartial(entry)
            val total = try {
                files.openPartial(entry, append).use { out -> copy(body.stream, out, hash, start, entry.sizeBytes, shouldStop, onProgress) }
            } catch (e: IOException) {
                return Attempt.Transient("Connection lost")
            }
            return when {
                total == STOPPED -> Attempt.Done(InstallOutcome.Paused(files.partialLength(entry)))
                total == TOO_BIG -> {
                    files.deletePartial(entry)
                    Attempt.Done(InstallOutcome.Failed("The download is larger than expected", retryable = false))
                }
                total < entry.sizeBytes -> Attempt.Transient("Connection closed early")
                else -> Attempt.Done(finish(entry, total, hash))
            }
        }
    }

    private fun finish(entry: ModelEntry, size: Long, digest: MessageDigest): InstallOutcome {
        if (size != entry.sizeBytes) {
            files.deletePartial(entry)
            return InstallOutcome.Failed("The file has the wrong size", retryable = false)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != entry.sha256) {
            files.deletePartial(entry)
            return InstallOutcome.Failed("Verification failed (SHA-256 mismatch)", retryable = true)
        }
        return try {
            InstallOutcome.Installed(files.commit(entry))
        } catch (e: IOException) {
            InstallOutcome.Failed("Couldn’t save the model", retryable = true)
        }
    }

    private fun rehash(entry: ModelEntry, length: Long, digest: MessageDigest) {
        files.readPartial(entry).use { input ->
            val buf = ByteArray(BUFFER)
            var left = length
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("partial shorter than reported")
                digest.update(buf, 0, n)
                left -= n
            }
        }
    }

    /** Returns the new total, [STOPPED] or [TOO_BIG]. */
    private fun copy(
        input: InputStream, out: OutputStream, digest: MessageDigest, start: Long, limit: Long,
        shouldStop: () -> Boolean, onProgress: (Long) -> Unit,
    ): Long {
        val buf = ByteArray(BUFFER)
        var total = start
        var reported = start
        while (true) {
            if (shouldStop()) {
                out.flush()
                return STOPPED
            }
            val n = input.read(buf)
            if (n < 0) break
            if (total + n > limit) return TOO_BIG
            out.write(buf, 0, n)
            digest.update(buf, 0, n)
            total += n
            if (total - reported >= PROGRESS_STEP) {
                reported = total
                onProgress(total)
            }
        }
        out.flush()
        onProgress(total)
        return total
    }

    private fun sha256() = MessageDigest.getInstance("SHA-256")

    companion object {
        const val BUFFER = 256 * 1024
        const val PROGRESS_STEP = 4L * 1024 * 1024
        /** Free space kept beyond the download itself so the phone never fills up (SPEC §12.6). */
        const val HEADROOM_BYTES = 512L * 1024 * 1024
        const val MAX_ATTEMPTS = 5
        const val BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val SLEEP_SLICE_MS = 250L
        private const val STOPPED = -1L
        private const val TOO_BIG = -2L
    }
}
