package com.agentkeyboard.core.editor

import com.agentkeyboard.core.protocol.FieldContext
import com.agentkeyboard.core.protocol.InputKind

/**
 * Deterministic "which value belongs in the focused field" for `form.fill` (SPEC §11.7, FR-AU-08).
 *
 * The IME can only write to the focused field, so the native side fills fields one at a time as focus
 * reaches them. This decides, for one field, which of the approved values (keyed by field id, label or
 * profile concept such as `email` / `givenName`) applies. It is deliberately conservative: when
 * nothing matches it returns null and the field is left alone. Writing "whatever value comes first"
 * into "whatever field is focused" is never acceptable, and sensitive fields are never filled.
 */
object FormFill {

    /** The key in [keys] whose value belongs in [field], or null. */
    fun keyFor(field: FieldContext, keys: Collection<String>): String? {
        if (field.sensitive || keys.isEmpty()) return null
        val byNorm = keys.associateBy(::compact)

        // 1. Exact identity: an autofill hint, the label or the hint text equals a key.
        for (id in field.autofillHints.orEmpty() + listOfNotNull(field.label, field.hint)) {
            byNorm[compact(id)]?.let { return it }
        }

        // 2. Profile concept: from the field's semantic kind, autofill hints, then its label/hint phrase.
        val concept = conceptOf(field) ?: return null
        return concept.aliases.firstNotNullOfOrNull { byNorm[compact(it)] }
    }

    internal data class Concept(val name: String, val kind: InputKind?, val aliases: List<String>)

    /** Most specific first: "first name" must win over "name". */
    internal val CONCEPTS = listOf(
        Concept("givenName", null, listOf("givenName", "given name", "first name", "firstname", "forename")),
        Concept("familyName", null, listOf("familyName", "family name", "last name", "lastname", "surname")),
        Concept("email", InputKind.EMAIL, listOf("email", "e-mail", "email address", "mail")),
        Concept("phone", InputKind.PHONE, listOf("phone", "tel", "telephone", "phone number", "mobile", "mobile number")),
        Concept("name", InputKind.NAME, listOf("name", "full name", "fullName")),
        Concept("streetAddress", null, listOf("streetAddress", "street address", "address", "address line 1", "street")),
        Concept("city", null, listOf("city", "town", "locality")),
        Concept("postalCode", null, listOf("postalCode", "postal code", "postcode", "zip", "zip code")),
        Concept("country", null, listOf("country")),
        Concept("url", InputKind.URL, listOf("url", "website", "web site")),
    )

    private val FILLER = setOf("your", "enter", "the", "please", "a", "an", "optional", "required")

    private fun conceptOf(field: FieldContext): Concept? {
        CONCEPTS.firstOrNull { it.kind != null && it.kind == field.kind }?.let { return it }
        for (hint in field.autofillHints.orEmpty()) {
            val h = compact(hint)
            CONCEPTS.firstOrNull { c -> c.aliases.any { compact(it) == h } }?.let { return it }
        }
        // Text match is exact on the meaningful words, so "Company name" or "Username" never become
        // the person's name and "Email me updates" never becomes the email field.
        for (text in listOfNotNull(field.label, field.hint)) {
            val phrase = compact(words(text).filterNot { it in FILLER }.joinToString(""))
            if (phrase.isEmpty()) continue
            CONCEPTS.firstOrNull { c -> c.aliases.any { compact(it) == phrase } }?.let { return it }
        }
        return null
    }

    /** Lowercase alphanumerics only: `E-mail address` → `emailaddress`, `givenName` → `givenname`. */
    internal fun compact(s: String): String = buildString { s.forEach { if (it.isLetterOrDigit()) append(it.lowercaseChar()) } }

    private fun words(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
}
