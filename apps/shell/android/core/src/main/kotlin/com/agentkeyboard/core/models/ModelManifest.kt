package com.agentkeyboard.core.models

import com.agentkeyboard.core.json.Json
import com.agentkeyboard.core.json.JsonException
import com.agentkeyboard.core.json.JsonObj
import com.agentkeyboard.core.json.JsonValue
import com.agentkeyboard.core.json.arrOrNull
import com.agentkeyboard.core.json.int
import com.agentkeyboard.core.json.long
import com.agentkeyboard.core.json.objOrNull
import com.agentkeyboard.core.json.str
import com.agentkeyboard.core.protocol.DeviceTier
import com.agentkeyboard.core.protocol.WireEnum
import com.agentkeyboard.core.protocol.wireOf
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/*
 * Model distribution manifest (SPEC §12.6, ADR-0011).
 *
 * On the wire `models.json` is a *signed envelope*:
 *
 *   { "keyId": "ak-2026-1", "payload": "<base64 of the manifest JSON>", "signature": "<base64 DER>" }
 *
 * The signature is ECDSA P-256 / SHA-256 over the decoded payload bytes, so no JSON canonicalisation
 * is needed: we verify the exact bytes the publisher signed and only then parse them. The public key
 * is pinned in the APK (BuildConfig); the CDN is untrusted. `scripts/models/manifest.mjs` produces
 * the envelope.
 */

enum class ModelKind(override val wire: String) : WireEnum {
    LLM("llm"), VLM("vlm"), EMBEDDING("embedding"), STT("stt"), TTS("tts"),
}

/** What a model can be asked to do; the engine picks the installed model that has the capability. */
enum class Capability(override val wire: String) : WireEnum { TEXT("text"), VISION("vision"), AUDIO("audio"), EMBED("embed") }

/** Inference backends in preference order (SPEC §12.2). */
enum class Backend(override val wire: String) : WireEnum { NPU("npu"), GPU("gpu"), CPU("cpu") }

data class ModelEntry(
    val id: String,
    val kind: ModelKind,
    val name: String,
    /** One line for the Models screen: what the user gets from it. */
    val role: String,
    /** File name inside the app's private models directory. */
    val file: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val license: String,
    val licenseUrl: String?,
    /** Tiers this model may be installed on. */
    val tiers: Set<DeviceTier>,
    /** Tiers for which it is part of the core pack (downloaded by "Download core pack"). */
    val coreFor: Set<DeviceTier>,
    val capabilities: Set<Capability>,
    val backends: List<Backend>,
    /** Runtime that executes it. Only `litertlm` is generative (ADR-0004). */
    val runtime: String,
    /** KV-cache size the file was exported with (input + output tokens). */
    val contextTokens: Int,
    /** Rough peak resident memory, for the planner and the Models screen. */
    val ramMb: Int,
    /** Android versionCode that first understands this entry. */
    val minAppVersion: Int,
) {
    val generative: Boolean get() = kind == ModelKind.LLM || kind == ModelKind.VLM
}

data class ModelManifest(val schema: Int, val issuedAt: Long, val entries: List<ModelEntry>) {
    fun entry(id: String): ModelEntry? = entries.firstOrNull { it.id == id }
    fun bySha(sha256: String): ModelEntry? = entries.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }
}

class ManifestException(message: String) : Exception(message)

object ManifestParser {
    const val SCHEMA = 1
    private val ID = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
    private val FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val SHA = Regex("^[0-9a-f]{64}$")
    private val RUNTIMES = setOf("litertlm", "system")
    /** 8 GB: nothing we ship is larger; a bigger value is a typo or an attack on the storage check. */
    private const val MAX_SIZE = 8L * 1024 * 1024 * 1024

    fun parse(text: String): ModelManifest {
        val root = try {
            Json.parse(text).objOrNull()
        } catch (e: JsonException) {
            null
        } ?: throw ManifestException("manifest is not a JSON object")
        val schema = root["schema"].int() ?: throw ManifestException("missing schema")
        if (schema != SCHEMA) throw ManifestException("unsupported schema $schema")
        val issuedAt = root["issuedAt"].long() ?: throw ManifestException("missing issuedAt")
        val items = root["models"].arrOrNull()?.items ?: throw ManifestException("missing models")
        val entries = items.mapIndexed { i, v -> entry(v.objOrNull() ?: throw ManifestException("models[$i] is not an object"), i) }
        val dupes = entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (dupes.isNotEmpty()) throw ManifestException("duplicate id ${dupes.first()}")
        val files = entries.groupBy { it.file }.filterValues { it.size > 1 }.keys
        if (files.isNotEmpty()) throw ManifestException("duplicate file ${files.first()}")
        return ModelManifest(schema, issuedAt, entries)
    }

    private fun entry(o: JsonObj, i: Int): ModelEntry {
        fun bad(field: String): Nothing = throw ManifestException("models[$i].$field is invalid")
        fun s(k: String, max: Int = 200) = o[k].str()?.takeIf { it.isNotBlank() && it.length <= max } ?: bad(k)
        fun tiers(k: String, required: Boolean): Set<DeviceTier> {
            val raw = o[k] ?: return if (required) bad(k) else emptySet()
            return raw.arrOrNull()?.items?.map { wireOf<DeviceTier>(it.str()) ?: bad(k) }?.toSet() ?: bad(k)
        }
        val id = s("id", 64).takeIf { ID.matches(it) } ?: bad("id")
        val url = s("url", 2_048).takeIf { it.startsWith("https://") } ?: bad("url")
        val tiers = tiers("tiers", required = true).ifEmpty { bad("tiers") }
        val coreFor = tiers("coreFor", required = false)
        if (!tiers.containsAll(coreFor)) bad("coreFor")
        val caps = o["capabilities"].arrOrNull()?.items?.map { wireOf<Capability>(it.str()) ?: bad("capabilities") }?.toSet()
            ?.ifEmpty { null } ?: bad("capabilities")
        val backends = o["backends"]?.let { b ->
            b.arrOrNull()?.items?.map { wireOf<Backend>(it.str()) ?: bad("backends") }?.distinct() ?: bad("backends")
        } ?: listOf(Backend.CPU)
        val runtime = s("runtime", 16).takeIf { it in RUNTIMES } ?: bad("runtime")
        val kind = wireOf<ModelKind>(o["kind"].str()) ?: bad("kind")
        if ((kind == ModelKind.LLM || kind == ModelKind.VLM) && runtime != "litertlm") bad("runtime")
        return ModelEntry(
            id = id,
            kind = kind,
            name = s("name", 80),
            role = s("role", 160),
            file = s("file", 128).takeIf { FILE.matches(it) && !it.contains("..") } ?: bad("file"),
            url = url,
            sha256 = s("sha256", 64).lowercase().takeIf { SHA.matches(it) } ?: bad("sha256"),
            sizeBytes = o["sizeBytes"].long()?.takeIf { it in 1..MAX_SIZE } ?: bad("sizeBytes"),
            license = s("license", 64),
            licenseUrl = o["licenseUrl"]?.let { v -> v.str()?.takeIf { it.startsWith("https://") && it.length <= 2_048 } ?: bad("licenseUrl") },
            tiers = tiers,
            coreFor = coreFor,
            capabilities = caps,
            backends = if (Backend.CPU in backends) backends else backends + Backend.CPU,
            runtime = runtime,
            contextTokens = o["contextTokens"]?.let { it.int()?.takeIf { n -> n in 256..131_072 } ?: bad("contextTokens") } ?: 1_024,
            ramMb = o["ramMb"].int()?.takeIf { it in 1..16_384 } ?: bad("ramMb"),
            minAppVersion = o["minAppVersion"]?.let { it.int()?.takeIf { n -> n >= 1 } ?: bad("minAppVersion") } ?: 1,
        )
    }
}

/**
 * Verifies a signed envelope against the pinned key(s). Keys are X.509 SubjectPublicKeyInfo (base64),
 * indexed by `keyId` so a key can be rotated by shipping the new one before signing with it.
 */
class ManifestVerifier(keys: Map<String, String>) {
    private val keys: Map<String, PublicKey> = keys.mapValues { (id, b64) ->
        try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64.trim())))
        } catch (e: Exception) {
            throw ManifestException("pinned key $id is not a valid EC public key")
        }
    }

    val configured: Boolean get() = keys.isNotEmpty()

    /** Returns the verified manifest JSON text, or throws [ManifestException]. */
    fun open(envelope: String): String {
        val o = try {
            Json.parse(envelope).objOrNull()
        } catch (e: JsonException) {
            null
        } ?: throw ManifestException("signed manifest is not a JSON object")
        val keyId = o["keyId"].str() ?: throw ManifestException("missing keyId")
        val key = keys[keyId] ?: throw ManifestException("unknown signing key $keyId")
        val payload = decode(o["payload"], "payload")
        val sig = decode(o["signature"], "signature")
        val ok = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(payload)
                verify(sig)
            }
        } catch (e: java.security.SignatureException) {
            false // malformed DER
        }
        if (!ok) throw ManifestException("manifest signature does not verify")
        return String(payload, Charsets.UTF_8)
    }

    /** [open] then [ManifestParser.parse]. */
    fun verify(envelope: String): ModelManifest = ManifestParser.parse(open(envelope))

    private fun decode(v: JsonValue?, name: String): ByteArray = try {
        Base64.getDecoder().decode(v.str() ?: throw ManifestException("missing $name"))
    } catch (e: IllegalArgumentException) {
        throw ManifestException("$name is not base64")
    }
}
