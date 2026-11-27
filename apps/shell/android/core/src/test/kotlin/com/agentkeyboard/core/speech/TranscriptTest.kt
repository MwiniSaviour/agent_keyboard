package com.agentkeyboard.core.speech

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptTest {
    @Test fun upperCaseModelOutputBecomesReadableText() {
        assertEquals("Hello how are you", Transcript.tidy("HELLO  HOW ARE YOU ", sentenceStart = true))
        assertEquals("see you at five", Transcript.tidy("SEE YOU AT FIVE", sentenceStart = false))
        assertEquals("I think I'm late and i.e. not", Transcript.tidy("I THINK I'M LATE AND I.E. NOT", sentenceStart = true))
        assertEquals("yes I", Transcript.tidy("YES I", sentenceStart = false))
        assertEquals("", Transcript.tidy("   ", sentenceStart = true))
    }

    @Test fun casedOutputIsKept() {
        assertEquals("Meet iPhone users in NYC", Transcript.tidy("Meet iPhone users in NYC", sentenceStart = false))
        assertEquals("42", Transcript.tidy("42", sentenceStart = true))
    }

    @Test fun sentenceStartsFollowTheTextBeforeTheCursor() {
        assertTrue(Transcript.startsSentence(""))
        assertTrue(Transcript.startsSentence("Done. "))
        assertTrue(Transcript.startsSentence("Really?"))
        assertTrue(Transcript.startsSentence("Hi\n"))
        assertFalse(Transcript.startsSentence("Hello there"))
        assertFalse(Transcript.startsSentence("e.g, "))
    }
}
