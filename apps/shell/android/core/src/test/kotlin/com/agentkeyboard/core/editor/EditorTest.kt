package com.agentkeyboard.core.editor

import com.agentkeyboard.core.editor.AndroidConst as A
import com.agentkeyboard.core.protocol.CapsMode
import com.agentkeyboard.core.protocol.ImeActionKind
import com.agentkeyboard.core.protocol.InputKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldClassifierTest {
    private fun classify(inputType: Int, imeOptions: Int = 0, hint: String? = null, hints: List<String>? = null, pkg: String = "com.example") =
        FieldClassifier.classify(EditorInfoSnapshot(inputType, imeOptions, pkg, hintText = hint, autofillHints = hints))

    private val text = A.TYPE_CLASS_TEXT

    @Test fun whatsappStyleMessageField() {
        val c = classify(text or A.TYPE_TEXT_FLAG_MULTI_LINE or A.TYPE_TEXT_FLAG_CAP_SENTENCES or A.TYPE_TEXT_VARIATION_SHORT_MESSAGE, A.IME_ACTION_SEND or A.IME_FLAG_NO_ENTER_ACTION, hint = "Message")
        assertEquals(InputKind.MULTILINE, c.context.kind)
        assertEquals(ImeActionKind.NEWLINE, c.context.imeAction, "NO_ENTER_ACTION on multiline → Enter inserts newline")
        assertTrue(c.enterIsNewline)
        assertEquals(CapsMode.SENTENCES, c.context.capsMode)
        assertFalse(c.context.sensitive)
        assertEquals("Message", c.context.hint)
    }

    @Test fun singleLineSendField() {
        val c = classify(text or A.TYPE_TEXT_FLAG_CAP_SENTENCES, A.IME_ACTION_SEND)
        assertEquals(ImeActionKind.SEND, c.context.imeAction)
        assertEquals(A.IME_ACTION_SEND, c.actionId)
        assertFalse(c.enterIsNewline)
    }

    @Test fun passwordVariantsAreSensitive() {
        for (v in listOf(A.TYPE_TEXT_VARIATION_PASSWORD, A.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, A.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            val c = classify(text or v, hint = "Password for ama@example.com")
            assertEquals(InputKind.PASSWORD, c.context.kind)
            assertTrue(c.context.sensitive)
            assertTrue(c.noLearning)
            assertNull(c.context.hint, "sensitive hints are not forwarded")
            assertEquals(CapsMode.NONE, c.context.capsMode)
        }
        assertTrue(classify(A.TYPE_CLASS_NUMBER or A.TYPE_NUMBER_VARIATION_PASSWORD).context.sensitive)
    }

    @Test fun otpAndPaymentAreDetectedFromAutofillHintsAndLabels() {
        assertEquals(InputKind.OTP, classify(A.TYPE_CLASS_NUMBER, hints = listOf("smsOTPCode")).context.kind)
        assertEquals(InputKind.OTP, classify(A.TYPE_CLASS_NUMBER, hint = "Enter verification code").context.kind)
        assertEquals(InputKind.OTP, classify(text, hint = "6-digit PIN").context.kind)
        assertTrue(classify(A.TYPE_CLASS_NUMBER, hints = listOf("creditCardNumber")).context.sensitive)
        assertTrue(classify(text, hint = "CVV").context.sensitive)
        assertTrue(classify(text, hints = listOf("password")).context.sensitive, "password hint on a plain text field")
    }

    @Test fun ordinaryFieldsAreNotSensitive() {
        assertFalse(classify(text, hint = "Search messages").context.sensitive)
        assertFalse(classify(text or A.TYPE_TEXT_FLAG_MULTI_LINE, hint = "Write a pinned note, pinning is fine").context.sensitive)
        assertFalse(classify(A.TYPE_CLASS_PHONE).context.sensitive)
    }

    @Test fun semanticKinds() {
        assertEquals(InputKind.EMAIL, classify(text or A.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).context.kind)
        assertEquals(InputKind.EMAIL, classify(text or A.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS).context.kind)
        assertEquals(InputKind.URL, classify(text or A.TYPE_TEXT_VARIATION_URI, A.IME_ACTION_GO).context.kind)
        assertEquals(InputKind.NAME, classify(text or A.TYPE_TEXT_VARIATION_PERSON_NAME).context.kind)
        assertEquals(CapsMode.WORDS, classify(text or A.TYPE_TEXT_VARIATION_PERSON_NAME).context.capsMode)
        assertEquals(InputKind.SEARCH, classify(text or A.TYPE_TEXT_VARIATION_FILTER).context.kind)
        assertEquals(ImeActionKind.SEARCH, classify(text or A.TYPE_TEXT_VARIATION_FILTER).context.imeAction)
        assertEquals(InputKind.PHONE, classify(A.TYPE_CLASS_PHONE).context.kind)
        assertEquals(InputKind.NUMBER, classify(A.TYPE_CLASS_DATETIME).context.kind)
        assertEquals(InputKind.TEXT, classify(0).context.kind, "TYPE_NULL")
        assertEquals(CapsMode.NONE, classify(text or A.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or A.TYPE_TEXT_FLAG_CAP_SENTENCES).context.capsMode)
        assertEquals(CapsMode.CHARACTERS, classify(text or A.TYPE_TEXT_FLAG_CAP_CHARACTERS).context.capsMode)
    }

    @Test fun incognitoFlagDisablesLearningWithoutBlockingAi() {
        val c = classify(text, A.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(c.noLearning)
        assertFalse(c.context.sensitive)
    }

    @Test fun previousActionMapsToNone() {
        assertEquals(ImeActionKind.NONE, classify(text, A.IME_ACTION_PREVIOUS).context.imeAction)
        assertEquals(0, classify(text, A.IME_ACTION_PREVIOUS).actionId)
    }

    @Test fun actionIdRoundTrip() {
        for (a in listOf(ImeActionKind.SEND, ImeActionKind.GO, ImeActionKind.NEXT, ImeActionKind.DONE, ImeActionKind.SEARCH)) {
            assertEquals(a, classify(text, FieldClassifier.actionIdOf(a)).context.imeAction)
        }
    }
}

class EditBatcherTest {
    private fun c(t: String) = EditOp.Commit(t)
    private fun d(b: Int, a: Int = 0) = EditOp.Delete(b, a)

    @Test fun mergesConsecutiveCommits() {
        assertEquals(listOf(c("hello")), EditBatcher.coalesce(listOf(c("he"), c("l"), c("lo"))))
    }

    @Test fun typeThenBackspaceInOneFrameCancels() {
        assertEquals(listOf(c("he")), EditBatcher.coalesce(listOf(c("hey"), d(1))))
        assertEquals(listOf(c("")), EditBatcher.coalesce(listOf(c("a"), d(1))))
        assertEquals(listOf(c("👋")), EditBatcher.coalesce(listOf(c("👋🏽"), d(1))), "code points, not chars")
    }

    @Test fun backspaceBeyondPendingCommitIsKept() {
        assertEquals(listOf(c("ab"), d(3)), EditBatcher.coalesce(listOf(c("ab"), d(3))))
    }

    @Test fun deletesMergeOnlyInTheSameDirection() {
        assertEquals(listOf(d(3)), EditBatcher.coalesce(listOf(d(1), d(2))))
        assertEquals(listOf(d(0, 3)), EditBatcher.coalesce(listOf(d(0, 1), d(0, 2))))
        assertEquals(listOf(d(0, 1), d(1)), EditBatcher.coalesce(listOf(d(0, 1), d(1))))
    }

    @Test fun actionsAreBarriers() {
        val ops = listOf(c("hi"), EditOp.Action(ImeActionKind.SEND), c("x"))
        assertEquals(ops, EditBatcher.coalesce(ops))
    }

    @Test fun cursorMovesSumAndZeroIsDropped() {
        assertEquals(emptyList(), EditBatcher.coalesce(listOf(EditOp.Cursor(2), EditOp.Cursor(-2))))
        assertEquals(listOf(EditOp.Cursor(-3)), EditBatcher.coalesce(listOf(EditOp.Cursor(-1), EditOp.Cursor(-2))))
    }

    @Test fun replacesAreNeverMerged() {
        val ops = listOf(EditOp.ReplaceWord("exigent"), c(" "))
        assertEquals(ops, EditBatcher.coalesce(ops))
    }
}

class TextWindowTest {
    @Test fun boundsEachSideWithoutSplittingSurrogates() {
        val emoji = "😀"
        val before = "a" + emoji.repeat(TextWindow.SIDE_CHARS) // 1 + 4000 chars
        val s = TextWindow.bound(before, "x".repeat(5000), null)
        assertTrue(s.before.length <= TextWindow.SIDE_CHARS)
        assertFalse(Character.isLowSurrogate(s.before[0]))
        assertEquals(TextWindow.SIDE_CHARS, s.after.length)
        assertEquals("", s.selected)
        val head = TextWindow.head("x" + emoji.repeat(1500), TextWindow.SIDE_CHARS)
        assertFalse(Character.isHighSurrogate(head.last()))
    }

    @Test fun wordBeforeCursor() {
        assertEquals(4, TextWindow.wordBeforeCursorCodePoints("hello exig"))
        assertEquals(0, TextWindow.wordBeforeCursorCodePoints("hello "))
        assertEquals(5, TextWindow.wordBeforeCursorCodePoints("I'm don't"))
        assertEquals(4, TextWindow.wordBeforeCursorCodePoints("Me pɛ Ɛyɛd"), "Twi letters (non-Latin-1)")
        // Parity with the shell's [\p{L}\p{M}\p{N}'’-]: combining marks are part of the word.
        assertEquals(6, TextWindow.wordBeforeCursorCodePoints("कहो नमस्ते"), "Devanagari vowel signs/virama are marks")
        assertEquals(5, TextWindow.wordBeforeCursorCodePoints("un cafe\u0301"), "decomposed accent")
        assertEquals(10, TextWindow.wordBeforeCursorCodePoints("a well-known"), "hyphenated")
        assertEquals(0, TextWindow.wordBeforeCursorCodePoints("hi 👍"), "emoji is not a word character")
    }

    @Test fun lastGraphemeForFallbackBackspace() {
        assertEquals(0, TextWindow.lastGraphemeCodePoints(""))
        assertEquals(1, TextWindow.lastGraphemeCodePoints("ab"))
        assertEquals(1, TextWindow.lastGraphemeCodePoints("a👍"))
        assertEquals(2, TextWindow.lastGraphemeCodePoints("a👍🏽"), "skin tone modifier")
        assertEquals(5, TextWindow.lastGraphemeCodePoints("a👨‍👩‍👧"), "ZWJ family")
        assertEquals(2, TextWindow.lastGraphemeCodePoints("cafe\u0301"), "e + combining acute")
    }
}
