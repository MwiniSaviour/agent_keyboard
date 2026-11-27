package com.agentkeyboard.core.speech

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceGateTest {
    @Test fun protectedFieldsNeverStartDictation() {
        assertEquals(VoiceGate.SECURE_FIELD, VoiceGate.refuseStart(sensitive = true))
        assertNull(VoiceGate.refuseStart(sensitive = false))
    }

    @Test fun dictationEndsWhenFocusMovesOrTheFieldBecomesProtected() {
        assertTrue(VoiceGate.keepOnFieldStart(restarting = true, sensitive = false)) // same field restarted
        assertFalse(VoiceGate.keepOnFieldStart(restarting = false, sensitive = false)) // another field
        assertFalse(VoiceGate.keepOnFieldStart(restarting = true, sensitive = true)) // now a password field
        assertFalse(VoiceGate.keepOnFieldStart(restarting = false, sensitive = true))
    }
}
