package com.agentkeyboard.core.editor

import com.agentkeyboard.core.protocol.CapsMode
import com.agentkeyboard.core.protocol.FieldContext
import com.agentkeyboard.core.protocol.ImeActionKind
import com.agentkeyboard.core.protocol.InputKind

/**
 * Mirrors of the `android.text.InputType` / `android.view.inputmethod.EditorInfo` constants used for
 * classification. They are copied here so the classifier is pure JVM and unit-testable.
 * `tools/parity/AndroidConstParity.kt` compiles against the real android.jar (the compiler inlines
 * both sides' constants) and fails if any value drifts; `tools/verify-core.sh` runs it.
 */
object AndroidConst {
    const val TYPE_MASK_CLASS = 0x0000000f
    const val TYPE_MASK_VARIATION = 0x00000ff0
    const val TYPE_CLASS_TEXT = 1
    const val TYPE_CLASS_NUMBER = 2
    const val TYPE_CLASS_PHONE = 3
    const val TYPE_CLASS_DATETIME = 4
    const val TYPE_TEXT_FLAG_CAP_CHARACTERS = 0x1000
    const val TYPE_TEXT_FLAG_CAP_WORDS = 0x2000
    const val TYPE_TEXT_FLAG_CAP_SENTENCES = 0x4000
    const val TYPE_TEXT_FLAG_MULTI_LINE = 0x20000
    const val TYPE_TEXT_FLAG_IME_MULTI_LINE = 0x40000
    const val TYPE_TEXT_FLAG_NO_SUGGESTIONS = 0x80000
    const val TYPE_TEXT_VARIATION_URI = 0x10
    const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x20
    const val TYPE_TEXT_VARIATION_SHORT_MESSAGE = 0x40
    const val TYPE_TEXT_VARIATION_LONG_MESSAGE = 0x50
    const val TYPE_TEXT_VARIATION_PERSON_NAME = 0x60
    const val TYPE_TEXT_VARIATION_PASSWORD = 0x80
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x90
    const val TYPE_TEXT_VARIATION_FILTER = 0xb0
    const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0xd0
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0xe0
    const val TYPE_NUMBER_VARIATION_PASSWORD = 0x10

    const val IME_MASK_ACTION = 0x000000ff
    const val IME_ACTION_UNSPECIFIED = 0
    const val IME_ACTION_NONE = 1
    const val IME_ACTION_GO = 2
    const val IME_ACTION_SEARCH = 3
    const val IME_ACTION_SEND = 4
    const val IME_ACTION_NEXT = 5
    const val IME_ACTION_DONE = 6
    const val IME_ACTION_PREVIOUS = 7
    const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000
    const val IME_FLAG_NO_ENTER_ACTION = 0x40000000
}

/** Plain snapshot of the `EditorInfo` fields we classify on (built by the IME from the real object). */
data class EditorInfoSnapshot(
    val inputType: Int,
    val imeOptions: Int,
    val packageName: String,
    val appLabel: String = packageName,
    val hintText: String? = null,
    val label: String? = null,
    val autofillHints: List<String>? = null,
)

/** What the IME needs beyond the wire-level [FieldContext]. */
data class FieldClassification(
    val context: FieldContext,
    /** Host asked for no personalised learning (incognito tabs etc.), or the field is sensitive. */
    val noLearning: Boolean,
    /** Enter inserts a newline instead of performing [FieldContext.imeAction]. */
    val enterIsNewline: Boolean,
    /** Raw action id to pass to `performEditorAction` (0 when none). */
    val actionId: Int,
)

/**
 * Derives semantic field kind, action and sensitivity from `EditorInfo` (SPEC §9, FR-KB-14).
 *
 * Sensitivity is deliberately over-inclusive: a false positive costs the user AI help in one field;
 * a false negative leaks a credential into context or learning.
 */
object FieldClassifier {
    private val OTP_HINTS = setOf("smsOTPCode", "oneTimeCode", "2faAppOTPCode", "emailOTPCode", "smsOTPCode1")
    private val PAYMENT_HINTS = setOf(
        "creditCardNumber", "creditCardSecurityCode", "creditCardExpirationDate",
        "creditCardExpirationMonth", "creditCardExpirationYear", "creditCardExpirationDay",
    )
    private val PASSWORD_HINTS = setOf("password", "newPassword", "currentPassword")
    private val OTP_TEXT = Regex("""\b(otp|one[- ]?time|verification code|security code|2fa|passcode|pin)\b""", RegexOption.IGNORE_CASE)
    private val PAYMENT_TEXT = Regex("""\b(card number|cvv|cvc|csc|expiry|expiration)\b""", RegexOption.IGNORE_CASE)
    private val PASSWORD_TEXT = Regex("""\b(password|passphrase)\b""", RegexOption.IGNORE_CASE)

    fun classify(info: EditorInfoSnapshot): FieldClassification {
        val cls = info.inputType and AndroidConst.TYPE_MASK_CLASS
        val variation = info.inputType and AndroidConst.TYPE_MASK_VARIATION
        val flags = info.inputType
        val hints = info.autofillHints.orEmpty()
        val text = listOfNotNull(info.hintText, info.label).joinToString(" ")

        val isPasswordType = when (cls) {
            AndroidConst.TYPE_CLASS_TEXT -> variation == AndroidConst.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == AndroidConst.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == AndroidConst.TYPE_TEXT_VARIATION_WEB_PASSWORD
            AndroidConst.TYPE_CLASS_NUMBER -> variation == AndroidConst.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        val isOtp = hints.any { it in OTP_HINTS } || (cls != AndroidConst.TYPE_CLASS_TEXT || !isMultiLine(flags)) && OTP_TEXT.containsMatchIn(text)
        val isPayment = hints.any { it in PAYMENT_HINTS } || PAYMENT_TEXT.containsMatchIn(text)
        val isPasswordHint = hints.any { it in PASSWORD_HINTS } || PASSWORD_TEXT.containsMatchIn(text)

        val kind = when {
            isPasswordType || isPasswordHint -> InputKind.PASSWORD
            isOtp -> InputKind.OTP
            cls == AndroidConst.TYPE_CLASS_NUMBER || cls == AndroidConst.TYPE_CLASS_DATETIME -> InputKind.NUMBER
            cls == AndroidConst.TYPE_CLASS_PHONE -> InputKind.PHONE
            cls != AndroidConst.TYPE_CLASS_TEXT -> InputKind.TEXT // TYPE_NULL: raw key events, treat as text
            variation == AndroidConst.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == AndroidConst.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> InputKind.EMAIL
            variation == AndroidConst.TYPE_TEXT_VARIATION_URI -> InputKind.URL
            variation == AndroidConst.TYPE_TEXT_VARIATION_PERSON_NAME -> InputKind.NAME
            variation == AndroidConst.TYPE_TEXT_VARIATION_FILTER -> InputKind.SEARCH
            isMultiLine(flags) || variation == AndroidConst.TYPE_TEXT_VARIATION_LONG_MESSAGE -> InputKind.MULTILINE
            else -> InputKind.TEXT
        }
        val sensitive = kind == InputKind.PASSWORD || kind == InputKind.OTP || isPayment

        val actionId = info.imeOptions and AndroidConst.IME_MASK_ACTION
        val noEnterAction = info.imeOptions and AndroidConst.IME_FLAG_NO_ENTER_ACTION != 0
        val multiline = cls == AndroidConst.TYPE_CLASS_TEXT && isMultiLine(flags)
        var action = when (actionId) {
            AndroidConst.IME_ACTION_GO -> ImeActionKind.GO
            AndroidConst.IME_ACTION_SEARCH -> ImeActionKind.SEARCH
            AndroidConst.IME_ACTION_SEND -> ImeActionKind.SEND
            AndroidConst.IME_ACTION_NEXT -> ImeActionKind.NEXT
            AndroidConst.IME_ACTION_DONE -> ImeActionKind.DONE
            else -> ImeActionKind.NONE // UNSPECIFIED / NONE / PREVIOUS
        }
        val enterIsNewline = multiline && (noEnterAction || action == ImeActionKind.NONE)
        if (enterIsNewline) action = ImeActionKind.NEWLINE
        if (kind == InputKind.SEARCH && action == ImeActionKind.NONE) action = ImeActionKind.SEARCH

        val caps = when {
            cls != AndroidConst.TYPE_CLASS_TEXT || sensitive -> CapsMode.NONE
            kind == InputKind.EMAIL || kind == InputKind.URL -> CapsMode.NONE
            flags and AndroidConst.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 -> CapsMode.CHARACTERS
            flags and AndroidConst.TYPE_TEXT_FLAG_CAP_WORDS != 0 || kind == InputKind.NAME -> CapsMode.WORDS
            flags and AndroidConst.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 -> CapsMode.SENTENCES
            else -> CapsMode.NONE
        }

        val context = FieldContext(
            packageName = info.packageName,
            appLabel = info.appLabel,
            kind = kind,
            imeAction = action,
            capsMode = caps,
            // Hints/labels of sensitive fields can themselves be revealing ("PIN for card •••• 4242").
            hint = info.hintText?.takeUnless { sensitive }?.take(120),
            label = info.label?.takeUnless { sensitive }?.take(120),
            autofillHints = hints.takeIf { it.isNotEmpty() },
            sensitive = sensitive,
        )
        val noLearning = sensitive || info.imeOptions and AndroidConst.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        return FieldClassification(context, noLearning, enterIsNewline, if (action == ImeActionKind.NEWLINE || action == ImeActionKind.NONE) 0 else actionId)
    }

    private fun isMultiLine(flags: Int) =
        flags and AndroidConst.TYPE_TEXT_FLAG_MULTI_LINE != 0 || flags and AndroidConst.TYPE_TEXT_FLAG_IME_MULTI_LINE != 0

    /** Wire action → `EditorInfo.IME_ACTION_*`. */
    fun actionIdOf(action: ImeActionKind): Int = when (action) {
        ImeActionKind.GO -> AndroidConst.IME_ACTION_GO
        ImeActionKind.SEARCH -> AndroidConst.IME_ACTION_SEARCH
        ImeActionKind.SEND -> AndroidConst.IME_ACTION_SEND
        ImeActionKind.NEXT -> AndroidConst.IME_ACTION_NEXT
        ImeActionKind.DONE -> AndroidConst.IME_ACTION_DONE
        ImeActionKind.NONE, ImeActionKind.NEWLINE -> AndroidConst.IME_ACTION_UNSPECIFIED
    }
}
