package com.agentkeyboard.core.editor

import com.agentkeyboard.core.protocol.CapsMode
import com.agentkeyboard.core.protocol.FieldContext
import com.agentkeyboard.core.protocol.ImeActionKind
import com.agentkeyboard.core.protocol.InputKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormFillTest {
    private fun field(kind: InputKind = InputKind.TEXT, label: String? = null, hint: String? = null, hints: List<String>? = null, sensitive: Boolean = false) =
        FieldContext("com.example.shop", "Shop", kind, ImeActionKind.NEXT, CapsMode.NONE, hint, label, hints, sensitive)

    private val profile = listOf("givenName", "familyName", "email", "phone", "city")

    @Test fun semanticKindPicksTheMatchingValue() {
        assertEquals("email", FormFill.keyFor(field(InputKind.EMAIL), profile))
        assertEquals("phone", FormFill.keyFor(field(InputKind.PHONE), profile))
    }

    @Test fun labelsAndHintsMapToProfileConcepts() {
        assertEquals("givenName", FormFill.keyFor(field(hint = "First name"), profile))
        assertEquals("familyName", FormFill.keyFor(field(label = "Surname"), profile))
        assertEquals("email", FormFill.keyFor(field(hint = "Enter your e-mail address"), profile))
        assertEquals("city", FormFill.keyFor(field(hint = "Town"), profile))
    }

    @Test fun exactIdentityWinsOverConcepts() {
        // Keys produced from the field's own label/id (e.g. by the LLM for free-text fields).
        val keys = listOf("email", "Delivery notes")
        assertEquals("Delivery notes", FormFill.keyFor(field(kind = InputKind.MULTILINE, label = "Delivery notes"), keys))
        assertEquals("email", FormFill.keyFor(field(hints = listOf("emailAddress"), label = "Contact"), listOf("email")), "autofill hint → concept")
        assertEquals("emailAddress", FormFill.keyFor(field(hints = listOf("emailAddress")), listOf("email", "emailAddress")))
    }

    @Test fun neverGuessesWhenNothingMatches() {
        // The regression this guards: the first value must never land in an unrelated field.
        assertNull(FormFill.keyFor(field(hint = "Promo code"), profile))
        assertNull(FormFill.keyFor(field(label = "Company name"), profile), "not the person's name")
        assertNull(FormFill.keyFor(field(label = "Username"), listOf("name")), "not the person's name")
        assertNull(FormFill.keyFor(field(label = "Email me offers"), profile), "a sentence is not the email field")
        assertNull(FormFill.keyFor(field(), profile), "no label, no kind")
        assertNull(FormFill.keyFor(field(InputKind.EMAIL), emptyList()))
    }

    @Test fun sensitiveFieldsAreNeverFilled() {
        assertNull(FormFill.keyFor(field(InputKind.PASSWORD, label = "password", sensitive = true), listOf("password")))
        assertNull(FormFill.keyFor(field(InputKind.EMAIL, sensitive = true), profile))
    }
}
