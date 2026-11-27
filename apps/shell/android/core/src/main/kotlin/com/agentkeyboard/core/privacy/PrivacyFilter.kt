package com.agentkeyboard.core.privacy

/**
 * Mandatory native privacy filters (SPEC §10.4, §17.1). Every piece of captured context passes
 * through here before it can reach a model, the database or the WebView:
 *  - blocked apps (password managers, authenticators, wallets/banking, plus user additions) yield
 *    nothing at all;
 *  - secure windows (`FLAG_SECURE`) and password nodes are dropped by the capture layer, which asks
 *    [admitNode];
 *  - card numbers (Luhn-checked) and one-time codes in free text are redacted unless the user
 *    explicitly asked for that exact text.
 */
object PrivacyFilter {
    /** Exact packages known to hold secrets. Kept small and precise; users extend it in settings. */
    val PRESET_BLOCKED_PACKAGES: Set<String> = setOf(
        "com.x8bit.bitwarden",
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.dashlane",
        "keepass2android.keepass2android",
        "com.kunzisoft.keepass.free",
        "com.kunzisoft.keepass.libre",
        "me.proton.android.pass",
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.authy.authy",
        "com.google.android.apps.walletnfcrel",
        "com.paypal.android.p2pmobile",
    )

    /** Segment heuristics for banking/finance apps not on the preset list (e.g. `com.examplebank.mobile`). */
    private val FINANCE_SEGMENT = Regex("""(^|[._])(bank|banking|wallet|momo|mobilemoney|pay)([._]|$)|bank(ing)?([._]|$)""")

    fun isBlockedApp(packageName: String, userBlocked: Set<String> = emptySet(), userAllowed: Set<String> = emptySet()): Boolean {
        if (packageName.isEmpty()) return false
        if (packageName in userAllowed) return false
        return packageName in userBlocked || packageName in PRESET_BLOCKED_PACKAGES || FINANCE_SEGMENT.containsMatchIn(packageName)
    }

    /** Accessibility capture asks this per node; returns false for anything that must never be read. */
    fun admitNode(isPassword: Boolean, inSecureWindow: Boolean): Boolean = !isPassword && !inSecureWindow

    private val CARD_CANDIDATE = Regex("""(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)""")
    private val OTP_CONTEXT = Regex(
        """\b(code|otp|pin|passcode|verification|verify|one[- ]time|2fa|token)\b[^\n]{0,40}?(?<!\d)(\d{4,8})(?!\d)|(?<!\d)(\d{4,8})(?!\d)[^\n]{0,40}?\b(code|otp|pin|passcode|verification|one[- ]time)\b""",
        RegexOption.IGNORE_CASE,
    )

    const val CARD_MASK = "[card]"
    const val CODE_MASK = "[code]"

    /** Redacts PII patterns in captured text. Idempotent. */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = CARD_CANDIDATE.replace(text) { m -> if (luhn(m.value)) CARD_MASK else m.value }
        out = OTP_CONTEXT.replace(out) { m ->
            val digits = m.groups[2] ?: m.groups[3] ?: return@replace m.value
            val start = digits.range.first - m.range.first
            val end = digits.range.last + 1 - m.range.first
            m.value.substring(0, start) + CODE_MASK + m.value.substring(end)
        }
        return out
    }

    fun containsSecret(text: String): Boolean = redact(text) != text

    /** Luhn checksum over the digits of [s] (separators ignored); 13–19 digits. */
    fun luhn(s: String): Boolean {
        var sum = 0
        var count = 0
        var double = false
        for (i in s.indices.reversed()) {
            val c = s[i]
            if (c == ' ' || c == '-') continue
            if (c !in '0'..'9') return false
            var d = c - '0'
            if (double) { d *= 2; if (d > 9) d -= 9 }
            sum += d; count++; double = !double
        }
        return count in 13..19 && sum % 10 == 0
    }
}
