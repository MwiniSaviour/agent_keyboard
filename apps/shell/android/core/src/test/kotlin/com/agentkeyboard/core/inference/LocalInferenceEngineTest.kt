package com.agentkeyboard.core.inference

import com.agentkeyboard.core.agent.EngineFailure
import com.agentkeyboard.core.json.obj
import com.agentkeyboard.core.models.Backend
import com.agentkeyboard.core.models.Capability
import com.agentkeyboard.core.models.InstalledModel
import com.agentkeyboard.core.models.InstalledModels
import com.agentkeyboard.core.models.entryFor
import com.agentkeyboard.core.skills.SkillTestData.chat
import com.agentkeyboard.core.skills.SkillTestData.editor
import com.agentkeyboard.core.skills.SkillTestData.job
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThinkFilterTest {
    private fun run(vararg deltas: String): String {
        val out = StringBuilder()
        val f = ThinkFilter { out.append(it) }
        deltas.forEach(f::push)
        f.flush()
        return out.toString()
    }

    @Test fun hidesThinkBlocksEvenWhenTagsAreSplit() {
        assertEquals("Hello world", run("Hello", " world"))
        assertEquals("Answer", run("<th", "ink>reason", "ing</th", "ink>Answer"))
        assertEquals("a b", run("a <think>x</think>", "b"))
        assertEquals("cost < 5 and x<", run("cost < 5", " and x<"))
        assertEquals("ok", run("ok<think>still thinking when the stream ended"))
    }

    @Test fun neverEmitsAPartialOpenTag() {
        val seen = mutableListOf<String>()
        val f = ThinkFilter { seen += it }
        f.push("Hi <thi")
        assertEquals(listOf("Hi "), seen)
        f.push("nk>")
        assertEquals(listOf("Hi "), seen)
    }
}

class LocalInferenceEngineTest {
    private val runtime = FakeRuntime()
    private val manager = ModelManager(runtime, MemProbes(), FakeDevice(gpu = false), appVersion = 1)
    private var installed: InstalledModel? = InstalledModel(entryFor(byteArrayOf(1), id = "gemma").copy(backends = listOf(Backend.CPU)), "/m/gemma")
    private val models = InstalledModels { cap -> installed?.takeIf { cap in it.entry.capabilities } }
    private val engine = LocalInferenceEngine(models, manager)

    @Test fun noModelFailsFastWithoutTheForegroundService() {
        installed = null
        assertFalse(engine.needsForeground)
        val e = assertFailsWith<EngineFailure> { engine.generate(job("refine", editor(before = "hi")), {}, { false }) }
        assertEquals("unavailable", e.code)
        assertEquals(LocalInferenceEngine.NO_MODEL, e.message)
    }

    @Test fun streamsFilteredTokensAndReturnsTheCleanedText() {
        runtime.onLoad = { it.reply = "<think>plan</think> Please send the invoice tomorrow." }
        val tokens = StringBuilder()
        val out = engine.generate(job("refine", editor(before = "pls send invoice tmrw")), { tokens.append(it) }, { false })
        assertEquals("Please send the invoice tomorrow.", out.text)
        assertFalse("plan" in tokens.toString())
        assertTrue("invoice" in tokens.toString())
        assertEquals("gemma", engine.modelId)
        assertTrue(engine.needsForeground)
    }

    @Test fun structuredSkillsDoNotStreamRawJson() {
        runtime.onLoad = { it.reply = """{"explanation":"Brief.","example":"A brief note.","synonyms":["short"],"antonyms":["long"]}""" }
        val tokens = StringBuilder()
        val out = engine.generate(job("define", obj("word" to "brief")), { tokens.append(it) }, { false })
        assertEquals("", tokens.toString())
        assertTrue(out.text.startsWith("Explanation: Brief."))
    }

    @Test fun cancellationAndUnavailableSkills() {
        runtime.onLoad = { it.reply = "one two three" }
        var n = 0
        val e = assertFailsWith<EngineFailure> { engine.generate(job("reply", conversation = chat(false to "hi")), {}, { n++ > 1 }) }
        assertEquals("cancelled", e.code)
        assertEquals("unavailable", assertFailsWith<EngineFailure> { engine.generate(job("describe_image"), {}, { false }) }.code)
        assertEquals("unavailable", assertFailsWith<EngineFailure> { engine.generate(job("fill_form"), {}, { false }) }.code)
        // A Tier S phone without the VLM can still do text.
        assertTrue(Capability.VISION !in installed!!.entry.capabilities)
    }
}
