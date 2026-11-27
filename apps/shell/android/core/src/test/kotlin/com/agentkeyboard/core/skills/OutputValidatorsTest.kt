package com.agentkeyboard.core.skills

import com.agentkeyboard.core.agent.EngineFailure
import com.agentkeyboard.core.json.obj
import com.agentkeyboard.core.skills.SkillTestData.job
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputValidatorsTest {
    private fun spec(skill: String, instruction: String? = null) =
        Skills.of(job(skill, instruction?.let { obj("instruction" to it) }).request)
    private fun ctx(material: String = "", source: String = "", truncated: Boolean = false) =
        OutputValidators.Context(material, source, agentName = "Kofi", truncated = truncated)

    @Test fun cleanStripsChatterAroundTheAnswer() {
        assertEquals("Please send the invoice tomorrow.", OutputValidators.clean("Sure! Here's the rewritten text:\n\"Please send the invoice tomorrow.\""))
        assertEquals("Bonjour à tous", OutputValidators.clean("<think>user wants French</think>\nTranslation: Bonjour à tous"))
        assertEquals("Hi there", OutputValidators.clean("```\nHi there\n```"))
        assertEquals("He said \"no\" and \"yes\"", OutputValidators.clean("He said \"no\" and \"yes\"")) // inner quotes kept
        assertEquals("", OutputValidators.clean("<think>never closed"))
    }

    @Test fun completionIsAContinuationOfOneSentence() {
        val out = OutputValidators.validate(spec("complete"), "see you at the shop. Then we can talk about the price.", ctx(source = "I will"))
        assertEquals("see you at the shop.", out.text)
    }

    @Test fun replyDropsSpeakerLabels() {
        assertEquals("Yes, we have it in blue.", OutputValidators.validate(spec("reply"), "Kofi: Yes, we have it in blue.", ctx()).text)
        assertEquals("Yes.", OutputValidators.validate(spec("reply"), "Me: Yes.", ctx()).text)
    }

    @Test fun runawayRewritesAreCapped() {
        val out = OutputValidators.validate(spec("refine"), "x".repeat(2_000), ctx(source = "short text"))
        assertTrue(out.text.length <= "short text".length * 2 + 101)
        assertTrue(out.text.endsWith("…"))
    }

    @Test fun definitionJsonBecomesTheCardLayout() {
        val raw = """{"explanation":"Lasting a very short time.","example":"Fame can be ephemeral.","synonyms":["fleeting","brief","fleeting"],"antonyms":[]}"""
        val out = OutputValidators.validate(spec("define"), raw, ctx())
        assertEquals("Explanation: Lasting a very short time.\nExample: Fame can be ephemeral.\nSynonyms: fleeting, brief", out.text)
        assertEquals("fleeting, brief", out.data!!["synonyms"])
        assertFalse("antonyms" in out.data!!)
        // Chatter around constrained JSON still parses; prose without JSON is kept as-is.
        assertTrue(OutputValidators.validate(spec("define"), "Here you go: $raw Hope that helps", ctx()).text.startsWith("Explanation:"))
        assertEquals("A short-lived thing.", OutputValidators.validate(spec("define"), "A short-lived thing.", ctx()).text)
        assertFailsWith<EngineFailure> { OutputValidators.validate(spec("define"), """{"explanation":""}""", ctx()) }
    }

    @Test fun autonomyConfidenceIsCappedByGrounding() {
        val s = spec("reply", Skills.INSTRUCTION_AUTONOMY)
        fun conf(raw: String, material: String = "Ama: Is the shop open today?", truncated: Boolean = false) =
            OutputValidators.validate(s, raw, ctx(material = material, truncated = truncated)).data!!["confidence"]
        assertEquals("0.90", conf("""{"reply":"Yes, we are open today.","confidence":0.9}"""))
        // An invented price is not grounded in the chat.
        assertEquals("0.40", conf("""{"reply":"It costs GH₵ 1,200.","confidence":0.95}"""))
        assertEquals("0.95", conf("""{"reply":"It costs GH₵ 1,200.","confidence":0.95}""", material = "Ama: Is it still GH₵ 1,200?"))
        assertEquals("0.30", conf("""{"reply":"Yes we are","confidence":0.9}""", truncated = true))
        assertEquals("0.00", conf("Yes we're open")) // no JSON → draft only
        assertEquals("0.00", conf("""{"reply":"My card is 4111 1111 1111 1111","confidence":1.0}""", material = "4111 1111 1111 1111"))
        assertEquals("1.00", conf("""{"reply":"Yes","confidence":7}"""))
        assertEquals("Yes we're open", OutputValidators.validate(s, "Yes we're open", ctx()).text)
    }

    @Test fun secretsTheMaterialDidNotContainAreRedacted() {
        val out = OutputValidators.validate(spec("reply"), "Pay with 4111 1111 1111 1111 please", ctx(material = "Ama: how do I pay?"))
        assertFalse("4111" in out.text)
        val own = OutputValidators.validate(spec("refine"), "Card: 4111 1111 1111 1111", ctx(material = "card 4111 1111 1111 1111", source = "card 4111 1111 1111 1111"))
        assertTrue("4111 1111 1111 1111" in own.text)
    }

    @Test fun emptyOutputIsAnError() {
        assertEquals("internal", assertFailsWith<EngineFailure> { OutputValidators.validate(spec("reply"), "<think>hmm</think>  ", ctx()) }.code)
    }
}
