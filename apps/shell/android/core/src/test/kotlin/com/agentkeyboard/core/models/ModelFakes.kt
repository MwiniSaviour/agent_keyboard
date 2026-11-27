package com.agentkeyboard.core.models

import com.agentkeyboard.core.Fixtures
import com.agentkeyboard.core.agent.SettingsStore
import com.agentkeyboard.core.json.JsonObj
import com.agentkeyboard.core.json.JsonValue
import com.agentkeyboard.core.json.str
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** In-memory [ModelFiles]: partial and final "files" are byte buffers keyed by `id/name`. */
class MemFiles(var free: Long = 64L * 1024 * 1024 * 1024) : ModelFiles {
    val partial = HashMap<String, ByteArrayOutputStream>()
    val final = HashMap<String, ByteArray>()
    var manifest: String? = null
    var failCommit = false

    private fun key(e: ModelEntry, f: ModelFile) = "${e.id}/${f.name}"

    override fun partialLength(entry: ModelEntry, file: ModelFile) = partial[key(entry, file)]?.size()?.toLong() ?: 0L
    override fun openPartial(entry: ModelEntry, file: ModelFile, append: Boolean): OutputStream {
        val k = key(entry, file)
        return if (append) partial.getOrPut(k) { ByteArrayOutputStream() } else ByteArrayOutputStream().also { partial[k] = it }
    }
    override fun readPartial(entry: ModelEntry, file: ModelFile): InputStream =
        ByteArrayInputStream(partial[key(entry, file)]?.toByteArray() ?: ByteArray(0))
    override fun deletePartial(entry: ModelEntry, file: ModelFile) { partial.remove(key(entry, file)) }
    override fun commit(entry: ModelEntry, file: ModelFile) {
        if (failCommit) throw IOException("disk")
        final[key(entry, file)] = partial.remove(key(entry, file))!!.toByteArray()
    }
    override fun hasFinal(entry: ModelEntry, file: ModelFile) = final[key(entry, file)]?.size?.toLong() == file.sizeBytes
    override fun location(entry: ModelEntry) = "/models/${entry.id}"
    override fun deleteAll(entry: ModelEntry) {
        final.keys.removeIf { it.startsWith("${entry.id}/") }
        partial.keys.removeIf { it.startsWith("${entry.id}/") }
    }
    override fun freeBytes() = free
    override fun cachedManifest() = manifest
    override fun cacheManifest(envelope: String) { manifest = envelope }

    /** The single final file of a one-file model. */
    fun finalOf(id: String): ByteArray? = final.entries.firstOrNull { it.key.startsWith("$id/") }?.value
}

/** Scripted HTTP: serves [bodies] by URL, honouring Range unless [ignoreRange]; [failures] fail first. */
class FakeHttp(private val bodies: Map<String, ByteArray>) : HttpSource {
    var ignoreRange = false
    var failures = 0
    var status: Int? = null
    /** Simulates a dropped connection after this many bytes of the next response. */
    var cutAfter: Int? = null
    val requests = mutableListOf<Pair<String, Long>>()

    override fun get(url: String, fromByte: Long): HttpBody {
        requests += url to fromByte
        if (failures > 0) { failures--; throw IOException("reset") }
        status?.let { return HttpBody(it, null, ByteArrayInputStream(ByteArray(0))) }
        val all = bodies[url] ?: return HttpBody(404, null, ByteArrayInputStream(ByteArray(0)))
        val ranged = fromByte > 0 && !ignoreRange
        var bytes = if (ranged) all.copyOfRange(fromByte.toInt(), all.size) else all
        val cut = cutAfter
        if (cut != null) { cutAfter = null; bytes = bytes.copyOfRange(0, minOf(cut, bytes.size)) }
        return HttpBody(if (ranged) 206 else 200, if (ranged) fromByte else null, ByteArrayInputStream(bytes))
    }
}

class MemSettings : SettingsStore {
    val map = HashMap<String, JsonValue>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: JsonValue) { map[key] = value }
}

class FakeNetwork(var connected: Boolean = true, var unmetered: Boolean = true) : NetworkState {
    override fun connected() = connected
    override fun unmetered() = unmetered
}

object ManifestFixture {
    val envelope: String by lazy { Fixtures.text("models.signed.json") }
    val keys: Map<String, String> by lazy {
        val o = Fixtures.json("models.public.json") as JsonObj
        mapOf(o["keyId"].str()!! to o["publicKey"].str()!!)
    }
    val verifier get() = ManifestVerifier(keys)
    val manifest: ModelManifest get() = verifier.verify(envelope)
    val gemmaBytes = "gemma-test-bytes".toByteArray()
    val e2bBytes = "e2b-test-bytes".toByteArray()
    val sttFiles = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
    val bodies get() = mapOf(
        "https://example.invalid/gemma3-1b-it-int4.litertlm" to gemmaBytes,
        "https://example.invalid/gemma-4-E2B-it.litertlm" to e2bBytes,
    ) + sttFiles.associate { "https://example.invalid/stt/$it" to "stt-$it".toByteArray() }
}

fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** A manifest entry with arbitrary bytes, for installer tests. Several [parts] make a multi-file model. */
fun entryFor(
    bytes: ByteArray, id: String = "m1", url: String = "https://cdn.example/m1.litertlm",
    parts: List<Pair<String, ByteArray>> = emptyList(), ramMb: Int = 900, quality: Int = 50,
    kind: ModelKind = ModelKind.LLM,
): ModelEntry {
    val files = if (parts.isEmpty()) listOf(ModelFile("$id.litertlm", url, sha256Hex(bytes), bytes.size.toLong()))
    else parts.map { (name, b) -> ModelFile(name, "https://cdn.example/$id/$name", sha256Hex(b), b.size.toLong()) }
    return ModelEntry(
        id = id, kind = kind, name = "Test $id", role = "Tests", files = files, license = "Apache-2.0", licenseUrl = null,
        tiers = setOf(com.agentkeyboard.core.protocol.DeviceTier.S), coreFor = setOf(com.agentkeyboard.core.protocol.DeviceTier.S),
        capabilities = setOf(if (kind == ModelKind.STT) Capability.TRANSCRIBE else Capability.TEXT), backends = listOf(Backend.CPU),
        runtime = if (kind == ModelKind.STT) "sherpa-onnx" else "litertlm", contextTokens = 1024, ramMb = ramMb, minAppVersion = 1,
        quality = quality,
    )
}

/** [ModelScanner] over in-memory files. */
class FakeScanner(val files: MutableList<Pair<FoundFile, ByteArray>> = mutableListOf(), var access: String = "folders") : ModelScanner {
    var scans = 0
    override fun scan(): ScanResult {
        scans++
        return ScanResult(files.map { it.first }, access, folders = 1)
    }
    override fun open(ref: String): InputStream = ByteArrayInputStream(files.first { it.first.ref == ref }.second)

    fun add(name: String, bytes: ByteArray, folder: String = "Download") {
        files += FoundFile("content://$folder/$name", name, bytes.size.toLong(), folder, folder) to bytes
    }
}

object Phones {
    /** "3 GB": only the 1B model fits, and not comfortably. */
    val SMALL = DeviceSpecs(totalRamMb = 2_800, cpuCores = 8, gpu = false)
    /** "6 GB", no usable GPU. */
    val MID = DeviceSpecs(totalRamMb = 5_600, cpuCores = 8, gpu = false)
    /** "8 GB" with an OpenCL GPU. */
    val BIG = DeviceSpecs(totalRamMb = 7_400, cpuCores = 8, gpu = true, soc = "SM8550")
}
