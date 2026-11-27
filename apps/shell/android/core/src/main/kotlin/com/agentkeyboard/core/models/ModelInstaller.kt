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
 *   for each file of the entry, in order (finished files are skipped):
 *     partial file ──(HTTP Range from its length)──▶ append + streaming SHA-256
 *                  ──size == expected──▶ digest == manifest sha256 ? atomic rename : delete partial
 *   all files final ──▶ Installed (the caller's install record is the commit point for the set)
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

/**
 * App-private model storage, one directory per model id. Paths never leave this port except as the
 * model's directory (for the runtimes to open files in it).
 */
interface ModelFiles {
    fun partialLength(entry: ModelEntry, file: ModelFile): Long
    fun openPartial(entry: ModelEntry, file: ModelFile, append: Boolean): OutputStream
    fun readPartial(entry: ModelEntry, file: ModelFile): InputStream
    fun deletePartial(entry: ModelEntry, file: ModelFile)
    /** Atomically renames the verified partial to its final name inside the model's directory. */
    fun commit(entry: ModelEntry, file: ModelFile)
    /** The final file exists with the expected size. */
    fun hasFinal(entry: ModelEntry, file: ModelFile): Boolean
    /** Absolute path of the model's directory. */
    fun location(entry: ModelEntry): String
    /** Removes the model's directory: final files and partials. */
    fun deleteAll(entry: ModelEntry)
    fun freeBytes(): Long
    /** The last verified signed catalog (kept verbatim so it is re-verified on every start). */
    fun cachedManifest(): String?
    fun cacheManifest(envelope: String)
}

/** Every file is final: the model is complete on disk. */
fun ModelFiles.complete(entry: ModelEntry): Boolean = entry.files.all { hasFinal(entry, it) }

/** Bytes on disk for [entry], finished files plus partials (progress for the Models screen). */
fun ModelFiles.bytesOnDisk(entry: ModelEntry): Long =
    entry.files.sumOf { if (hasFinal(entry, it)) it.sizeBytes else partialLength(entry, it) }

fun ModelFiles.deletePartials(entry: ModelEntry) = entry.files.forEach { deletePartial(entry, it) }

sealed interface InstallOutcome {
    data class Installed(val path: String) : InstallOutcome
    /** Stopped by the caller (cancel, network policy, shutdown); partial files are kept for resume. */
    data class Paused(val receivedBytes: Long) : InstallOutcome
    data class Failed(val reason: String, val retryable: Boolean) : InstallOutcome
    /** An import delivered some of a multi-file model; [missing] are still needed. */
    data class Incomplete(val missing: List<String>) : InstallOutcome
}

/** Measured identity of a file installed without a catalog entry (custom models). */
data class Measured(val sha256: String, val sizeBytes: Long)

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
        for (file in entry.files) {
            if (files.hasFinal(entry, file)) continue
            val before = entry.files.takeWhile { it !== file }.sumOf { it.sizeBytes }
            val outcome = downloadFile(source, entry, file, shouldStop) { n -> onProgress(before + n) }
            if (outcome != null) return outcome
        }
        return InstallOutcome.Installed(files.location(entry))
    }

    /** Returns null once [file] is final, or the outcome that stopped it. */
    private fun downloadFile(
        source: HttpSource, entry: ModelEntry, file: ModelFile, shouldStop: () -> Boolean, onProgress: (Long) -> Unit,
    ): InstallOutcome? {
        var attempt = 0
        while (true) {
            if (shouldStop()) return InstallOutcome.Paused(files.bytesOnDisk(entry))
            val remaining = entry.files.filter { !files.hasFinal(entry, it) }.sumOf { it.sizeBytes } - files.partialLength(entry, file)
            if (files.freeBytes() < remaining + HEADROOM_BYTES) return InstallOutcome.Failed("Not enough free storage", retryable = false)
            when (val r = attemptOnce(source, entry, file, shouldStop, onProgress)) {
                is Attempt.Done -> return r.outcome
                Attempt.Committed -> return null
                is Attempt.Transient -> {
                    if (++attempt > MAX_ATTEMPTS) return InstallOutcome.Failed(r.reason, retryable = true)
                    val wait = (BACKOFF_MS shl (attempt - 1)).coerceAtMost(MAX_BACKOFF_MS)
                    var waited = 0L
                    while (waited < wait) {
                        if (shouldStop()) return InstallOutcome.Paused(files.bytesOnDisk(entry))
                        sleep(SLEEP_SLICE_MS)
                        waited += SLEEP_SLICE_MS
                    }
                }
            }
        }
    }

    /**
     * Installs from local streams (offline import, files found on the phone): every byte must hash to
     * the catalog, exactly like a download. [open] returns the stream for a file, or null if this
     * import doesn't have it ([InstallOutcome.Incomplete] then lists what is still missing).
     */
    fun import(entry: ModelEntry, open: (ModelFile) -> InputStream?, shouldStop: () -> Boolean, onProgress: (Long) -> Unit): InstallOutcome {
        val needed = entry.files.filter { !files.hasFinal(entry, it) }.sumOf { it.sizeBytes }
        if (files.freeBytes() < needed + HEADROOM_BYTES) return InstallOutcome.Failed("Not enough free storage", retryable = false)
        for (file in entry.files) {
            if (files.hasFinal(entry, file)) continue
            val input = try {
                open(file)
            } catch (e: IOException) {
                return InstallOutcome.Failed("Couldn’t read ${file.name}", retryable = true)
            } ?: continue
            val before = entry.files.takeWhile { it !== file }.sumOf { it.sizeBytes }
            val r = input.use { copyIn(entry, file, it, shouldStop) { n -> onProgress(before + n) } }
            when (r) {
                is Attempt.Done -> return r.outcome
                is Attempt.Transient -> return InstallOutcome.Failed(r.reason, retryable = true)
                Attempt.Committed -> Unit
            }
        }
        val missing = entry.files.filter { !files.hasFinal(entry, it) }.map { it.name }
        return if (missing.isEmpty()) InstallOutcome.Installed(files.location(entry)) else InstallOutcome.Incomplete(missing)
    }

    /**
     * Copies a single-file model the user chose to trust although no catalog lists it. Nothing to
     * verify against, so the hash is *measured* and recorded: later loads are tied to these bytes.
     */
    fun importUnverified(entry: ModelEntry, input: InputStream, shouldStop: () -> Boolean, onProgress: (Long) -> Unit): Pair<InstallOutcome, Measured?> {
        val file = entry.files.single()
        if (files.freeBytes() < file.sizeBytes + HEADROOM_BYTES) return InstallOutcome.Failed("Not enough free storage", retryable = false) to null
        files.deletePartial(entry, file)
        val digest = sha256()
        val written = try {
            files.openPartial(entry, file, append = false).use { out -> copy(input, out, digest, 0, file.sizeBytes, shouldStop, onProgress) }
        } catch (e: IOException) {
            files.deletePartial(entry, file)
            return InstallOutcome.Failed("Couldn’t read the file", retryable = true) to null
        }
        if (written != file.sizeBytes) {
            files.deletePartial(entry, file)
            return (if (written == STOPPED) InstallOutcome.Paused(0) else InstallOutcome.Failed("The file changed while it was copied", retryable = true)) to null
        }
        return try {
            files.commit(entry, file)
            InstallOutcome.Installed(files.location(entry)) to Measured(hex(digest), written)
        } catch (e: IOException) {
            files.deletePartial(entry, file)
            InstallOutcome.Failed("Couldn’t save the model", retryable = true) to null
        }
    }

    private fun copyIn(entry: ModelEntry, file: ModelFile, input: InputStream, shouldStop: () -> Boolean, onProgress: (Long) -> Unit): Attempt {
        files.deletePartial(entry, file)
        val digest = sha256()
        val written = try {
            files.openPartial(entry, file, append = false).use { out -> copy(input, out, digest, 0, file.sizeBytes, shouldStop, onProgress) }
        } catch (e: IOException) {
            files.deletePartial(entry, file)
            return Attempt.Transient("Couldn’t read ${file.name}")
        }
        if (written < 0) {
            files.deletePartial(entry, file)
            return Attempt.Done(
                if (written == STOPPED) InstallOutcome.Paused(files.bytesOnDisk(entry))
                else InstallOutcome.Failed("${file.name} is larger than expected", retryable = false),
            )
        }
        return finish(entry, file, written, digest)
    }

    private sealed interface Attempt {
        data class Done(val outcome: InstallOutcome) : Attempt
        data class Transient(val reason: String) : Attempt
        /** The file is verified and final. */
        data object Committed : Attempt
    }

    private fun attemptOnce(source: HttpSource, entry: ModelEntry, file: ModelFile, shouldStop: () -> Boolean, onProgress: (Long) -> Unit): Attempt {
        var have = files.partialLength(entry, file)
        if (have > file.sizeBytes) {
            files.deletePartial(entry, file)
            have = 0
        }
        val digest = sha256()
        if (have > 0) {
            try {
                rehash(entry, file, have, digest)
            } catch (e: IOException) {
                files.deletePartial(entry, file)
                return Attempt.Transient("Couldn’t read the partial download")
            }
        }
        if (have == file.sizeBytes) return finish(entry, file, have, digest)
        val body = try {
            source.get(file.url, have)
        } catch (e: IOException) {
            return Attempt.Transient("Network error")
        }
        body.use {
            val append = when {
                body.status == 206 && body.rangeStart == have -> true
                body.status == 206 -> return Attempt.Transient("Server sent the wrong range")
                body.status == 200 -> false // server ignored Range: start over
                body.status == 416 -> { // our partial is unusable for this server
                    files.deletePartial(entry, file)
                    return Attempt.Transient("Server refused to resume")
                }
                body.status in 500..599 || body.status == 429 || body.status == 408 -> return Attempt.Transient("Server error ${body.status}")
                else -> return Attempt.Done(InstallOutcome.Failed("Download refused (${body.status})", retryable = false))
            }
            val start = if (append) have else 0L
            val hash = if (append) digest else sha256()
            if (!append) files.deletePartial(entry, file)
            val total = try {
                files.openPartial(entry, file, append).use { out -> copy(body.stream, out, hash, start, file.sizeBytes, shouldStop, onProgress) }
            } catch (e: IOException) {
                return Attempt.Transient("Connection lost")
            }
            return when {
                total == STOPPED -> Attempt.Done(InstallOutcome.Paused(files.bytesOnDisk(entry)))
                total == TOO_BIG -> {
                    files.deletePartial(entry, file)
                    Attempt.Done(InstallOutcome.Failed("The download is larger than expected", retryable = false))
                }
                total < file.sizeBytes -> Attempt.Transient("Connection closed early")
                else -> finish(entry, file, total, hash)
            }
        }
    }

    private fun finish(entry: ModelEntry, file: ModelFile, size: Long, digest: MessageDigest): Attempt {
        if (size != file.sizeBytes) {
            files.deletePartial(entry, file)
            return Attempt.Done(InstallOutcome.Failed("The file has the wrong size", retryable = false))
        }
        if (hex(digest) != file.sha256) {
            files.deletePartial(entry, file)
            return Attempt.Done(InstallOutcome.Failed("Verification failed (SHA-256 mismatch)", retryable = true))
        }
        return try {
            files.commit(entry, file)
            Attempt.Committed
        } catch (e: IOException) {
            Attempt.Done(InstallOutcome.Failed("Couldn’t save the model", retryable = true))
        }
    }

    private fun rehash(entry: ModelEntry, file: ModelFile, length: Long, digest: MessageDigest) {
        files.readPartial(entry, file).use { input ->
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

    private fun hex(d: MessageDigest) = d.digest().joinToString("") { "%02x".format(it) }

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
