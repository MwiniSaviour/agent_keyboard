package com.agentkeyboard.core.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelMatcherTest {
    private val entries = ManifestFixture.manifest.entries
    private fun f(name: String, size: Long, folder: String = "Download", ref: String = "$folder/$name") = FoundFile(ref, name, size, folder, folder)
    private val big = ModelMatcher.MIN_CUSTOM_BYTES

    @Test fun catalogFilesAreRecognisedByNameAndExactSize() {
        val found = ModelMatcher.match(entries, listOf(f("gemma3-1b-it-int4.litertlm", 16), f("gemma-4-E2B-it.litertlm", 15)), emptyMap())
        assertEquals(listOf("found-gemma-3-1b-it-int4"), found.map { it.id }) // E2B: wrong size, not claimed
        assertEquals("gemma-3-1b-it-int4", found.single().entryId)
        assertEquals(mapOf("gemma3-1b-it-int4.litertlm" to "Download/gemma3-1b-it-int4.litertlm"), found.single().refs)
    }

    @Test fun aRenamedSingleFileModelIsFoundByItsUniqueSize() {
        val found = ModelMatcher.match(entries, listOf(f("gemma (1).litertlm", 16)), emptyMap())
        assertEquals("gemma-3-1b-it-int4", found.single().entryId)
        // Two candidates of that size: ambiguous, so neither is claimed.
        assertTrue(ModelMatcher.match(entries, listOf(f("a.litertlm", 16), f("b.litertlm", 16)), emptyMap()).isEmpty())
    }

    @Test fun multiFileModelsMustBeCompleteInOneFolder() {
        val parts = ManifestFixture.sttFiles.map { it to "stt-$it".length.toLong() }
        val split = parts.mapIndexed { i, (n, s) -> f(n, s, folder = if (i < 2) "A" else "B") }
        assertTrue(ModelMatcher.match(entries, split, emptyMap()).none { it.entryId == "zipformer-en-20m" })
        val together = parts.map { (n, s) -> f(n, s, folder = "Documents/stt") }
        val m = ModelMatcher.match(entries, together, emptyMap()).single()
        assertEquals("zipformer-en-20m", m.entryId)
        assertEquals("Documents/stt", m.where)
        assertEquals(4, m.refs.size)
    }

    @Test fun otherLitertlmFilesAreOfferedAsCustomAndSmallOnesIgnored() {
        val found = ModelMatcher.match(entries, listOf(f("Qwen3_0.6B_q8.litertlm", big + 5), f("tiny.litertlm", 1_000), f("model.onnx", big * 2)), emptyMap())
        val c = found.single()
        assertNull(c.entryId)
        assertEquals("Qwen3 0.6B q8", c.name)
        assertEquals(ModelMatcher.customId("Qwen3_0.6B_q8.litertlm", big + 5), c.id)
        assertTrue(Regex("^custom-[0-9a-z]+-qwen3-0-6b-q8$").matches(c.id), c.id)
        // Stable across rescans, and not offered once installed.
        assertTrue(ModelMatcher.match(entries, listOf(f("Qwen3_0.6B_q8.litertlm", big + 5)), mapOf(c.id to "x")).isEmpty())
    }

    @Test fun whatIsAlreadyInstalledIsNotOfferedAgain() {
        val sha = entries.first().sha256
        assertTrue(ModelMatcher.match(entries, listOf(f("gemma3-1b-it-int4.litertlm", 16)), mapOf("gemma-3-1b-it-int4" to sha)).isEmpty())
        // A different (older) build is installed: the found file may be the update.
        assertEquals(1, ModelMatcher.match(entries, listOf(f("gemma3-1b-it-int4.litertlm", 16)), mapOf("gemma-3-1b-it-int4" to "old")).size)
    }

    @Test fun customEntriesAreConservative() {
        val fm = FoundModel("custom-1-x", "X", ModelKind.LLM, 2_000L * 1024 * 1024, "Download", null, mapOf("x.litertlm" to "r"))
        val e = ModelMatcher.customEntry(fm, Measured("c".repeat(64), fm.sizeBytes))
        assertTrue(e.custom)
        assertEquals(setOf(Capability.TEXT), e.capabilities)
        assertEquals(1_024, e.contextTokens)
        assertEquals(2_500, e.ramMb)
        assertEquals("c".repeat(64), e.sha256)
        assertEquals(listOf(Backend.GPU, Backend.CPU), e.backends)
    }
}
