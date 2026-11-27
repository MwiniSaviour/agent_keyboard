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

/** In-memory [ModelFiles]: partial and final "files" are byte buffers. */
class MemFiles(var free: Long = 64L * 1024 * 1024 * 1024) : ModelFiles {
    val partial = HashMap<String, ByteArrayOutputStream>()
    val final = HashMap<String, ByteArray>()
    var manifest: String? = null
    var failCommit = false

    override fun partialLength(entry: ModelEntry) = partial[entry.id]?.size()?.toLong() ?: 0L
    override fun openPartial(entry: ModelEntry, append: Boolean): OutputStream {
        val buf = if (append) partial.getOrPut(entry.id) { ByteArrayOutputStream() } else ByteArrayOutputStream().also { partial[entry.id] = it }
        return buf
    }
    override fun readPartial(entry: ModelEntry): InputStream = ByteArrayInputStream(partial[entry.id]?.toByteArray() ?: ByteArray(0))
    override fun deletePartial(entry: ModelEntry) { partial.remove(entry.id) }
    override fun commit(entry: ModelEntry): String {
        if (failCommit) throw IOException("disk")
        final[entry.id] = partial.remove(entry.id)!!.toByteArray()
        return "/models/${entry.file}"
    }
    override fun finalPath(entry: ModelEntry) = if (entry.id in final) "/models/${entry.file}" else null
    override fun deleteFinal(entry: ModelEntry) { final.remove(entry.id) }
    override fun freeBytes() = free
    override fun cachedManifest() = manifest
    override fun cacheManifest(envelope: String) { manifest = envelope }
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
    val bodies get() = mapOf(
        "https://example.invalid/gemma3-1b-it-int4.litertlm" to gemmaBytes,
        "https://example.invalid/gemma-4-E2B-it.litertlm" to e2bBytes,
    )
}

/** A manifest entry with arbitrary bytes, for installer tests. */
fun entryFor(bytes: ByteArray, id: String = "m1", url: String = "https://cdn.example/m1.litertlm"): ModelEntry {
    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return ModelEntry(
        id = id, kind = ModelKind.LLM, name = "Test", role = "Tests", file = "$id.litertlm", url = url, sha256 = sha,
        sizeBytes = bytes.size.toLong(), license = "Apache-2.0", licenseUrl = null,
        tiers = setOf(com.agentkeyboard.core.protocol.DeviceTier.S), coreFor = setOf(com.agentkeyboard.core.protocol.DeviceTier.S),
        capabilities = setOf(Capability.TEXT), backends = listOf(Backend.CPU), runtime = "litertlm", contextTokens = 1024,
        ramMb = 900, minAppVersion = 1,
    )
}
