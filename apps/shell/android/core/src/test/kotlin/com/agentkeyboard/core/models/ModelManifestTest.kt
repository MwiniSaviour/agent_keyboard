package com.agentkeyboard.core.models

import com.agentkeyboard.core.protocol.DeviceTier
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelManifestTest {
    /** Cross-language vector: signed by Node (`scripts/models/manifest.mjs fixture`), verified by java.security. */
    @Test fun nodeSignedFixtureVerifiesOnTheJvm() {
        val m = ManifestFixture.manifest
        assertEquals(1, m.schema)
        assertEquals(listOf("gemma-3-1b-it-int4", "gemma-4-e2b-it"), m.entries.map { it.id })
        val g = m.entry("gemma-3-1b-it-int4")!!
        assertEquals(setOf(DeviceTier.S), g.coreFor)
        assertEquals(listOf(Backend.GPU, Backend.CPU).toSet(), g.backends.toSet())
        assertEquals(16L, g.sizeBytes)
        assertEquals(1024, g.contextTokens)
        assertTrue(Capability.VISION in m.entry("gemma-4-e2b-it")!!.capabilities)
    }

    @Test fun tamperedPayloadOrSignatureIsRejected() {
        val env = ManifestFixture.envelope
        val payload = Regex("\"payload\": \"([^\"]+)\"").find(env)!!.groupValues[1]
        val bytes = Base64.getDecoder().decode(payload)
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        val tampered = env.replace(payload, Base64.getEncoder().encodeToString(bytes))
        assertFailsWith<ManifestException> { ManifestFixture.verifier.verify(tampered) }
        val sig = Regex("\"signature\": \"([^\"]+)\"").find(env)!!.groupValues[1]
        assertFailsWith<ManifestException> { ManifestFixture.verifier.verify(env.replace(sig, "AAAA")) }
        assertFailsWith<ManifestException> { ManifestFixture.verifier.verify(env.replace("ak-test", "ak-other")) }
        assertFailsWith<ManifestException> { ManifestFixture.verifier.verify("not json") }
    }

    @Test fun unknownOrRotatedKeysAreHandledByKeyId() {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val otherB64 = Base64.getEncoder().encodeToString(other.public.encoded)
        // Only the wrong key pinned → rejected; both pinned (rotation window) → accepted.
        assertFailsWith<ManifestException> { ManifestVerifier(mapOf("ak-test" to otherB64)).verify(ManifestFixture.envelope) }
        ManifestVerifier(ManifestFixture.keys + ("ak-next" to otherB64)).verify(ManifestFixture.envelope)
        assertFailsWith<ManifestException> { ManifestVerifier(mapOf("bad" to "bm90IGEga2V5")) }
    }

    @Test fun jvmSignedEnvelopeRoundTrips() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val payload = manifest(entryJson()).toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(payload); sign() }
        val env = """{"keyId":"k","payload":"${b64(payload)}","signature":"${b64(sig)}"}"""
        val m = ManifestVerifier(mapOf("k" to b64(kp.public.encoded))).verify(env)
        assertEquals("m1", m.entries.single().id)
    }

    @Test fun parserRejectsUnsafeOrInconsistentEntries() {
        fun bad(entry: String) = assertFailsWith<ManifestException>(entry) { ManifestParser.parse(manifest(entry)) }
        bad(entryJson(id = "../../db"))
        bad(entryJson(file = "../ak.db"))
        bad(entryJson(url = "http://cdn.example/m1"))
        bad(entryJson(sha = "abc"))
        bad(entryJson(size = "0"))
        bad(entryJson(size = "99999999999"))
        bad(entryJson(coreFor = "[\"M\"]")) // core for a tier it can't be installed on
        bad(entryJson(runtime = "system")) // generative models run on LiteRT-LM only (ADR-0004)
        bad(entryJson(caps = "[]"))
        assertFailsWith<ManifestException> { ManifestParser.parse("""{"schema":2,"issuedAt":1,"models":[]}""") }
        assertFailsWith<ManifestException> { ManifestParser.parse(manifest(entryJson() + "," + entryJson())) } // duplicate id
        // Optional fields default sensibly; CPU is always a fallback backend.
        val e = ManifestParser.parse(manifest(entryJson(backends = "[\"gpu\"]"))).entries.single()
        assertEquals(listOf(Backend.GPU, Backend.CPU), e.backends)
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun manifest(entries: String) = """{"schema":1,"issuedAt":1764201600000,"models":[$entries]}"""
    private fun entryJson(
        id: String = "m1", file: String = "m1.litertlm", url: String = "https://cdn.example/m1.litertlm",
        sha: String = "a".repeat(64), size: String = "1024", coreFor: String = "[\"S\"]", runtime: String = "litertlm",
        caps: String = "[\"text\"]", backends: String = "[\"cpu\"]",
    ) = """{"id":"$id","kind":"llm","name":"M","role":"r","file":"$file","url":"$url","sha256":"$sha","sizeBytes":$size,
        "license":"Apache-2.0","tiers":["S"],"coreFor":$coreFor,"capabilities":$caps,"backends":$backends,"runtime":"$runtime","ramMb":900}"""
}
