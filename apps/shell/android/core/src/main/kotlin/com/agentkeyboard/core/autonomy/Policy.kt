package com.agentkeyboard.core.autonomy

/**
 * Autonomy guardrails (SPEC §11.6.2, FR-AU-04/05). This is the production policy; the web mock's
 * `apps/shell/src/bridge/web-mock/policy.ts` is the reference it was ported from. Both are checked
 * against `packages/protocol/fixtures/policy.json`, so the playground cannot drift from the device.
 *
 * Inbound text is untrusted (SPEC §17.1): it is matched, never interpreted.
 */
sealed interface Verdict {
    data object Ok : Verdict
    data class Blocked(val reason: String) : Verdict
}

object Policy {
    private val I = RegexOption.IGNORE_CASE

    private val SENSITIVE_INBOUND: List<Pair<Regex, String>> = listOf(
        Regex("""\b(pin|password|passcode|otp|one[- ]time code|cvv|security code)\b""", I) to "Sensitive request (credentials)",
        Regex("""\b(bank (details|account)|account number|transfer (me|money)|send (me )?money|mobile money pin)\b""", I) to "Money request",
        Regex("""\bignore (all |your )?(previous|prior) instructions\b""", I) to "Instruction-like message (possible prompt injection)",
        Regex("""\b(lawyer|contract|sign(ed)? (the )?agreement|legal action)\b""", I) to "Legal commitment",
    )

    private val PII_OUTBOUND: List<Pair<Regex, String>> = listOf(
        Regex("""\b\d{4}[ -]?\d{4}[ -]?\d{4}[ -]?\d{4}\b""") to "Card number in reply",
        Regex("""\b\d{6}\b(?=.*\b(code|otp)\b)""", I) to "One-time code in reply",
    )

    fun checkInbound(text: String): Verdict = firstMatch(SENSITIVE_INBOUND, text)

    fun checkOutbound(text: String): Verdict = firstMatch(PII_OUTBOUND, text)

    private fun firstMatch(rules: List<Pair<Regex, String>>, text: String): Verdict {
        for ((re, reason) in rules) if (re.containsMatchIn(text)) return Verdict.Blocked(reason)
        return Verdict.Ok
    }
}

/** Sliding-window limiter; identical semantics to `RateLimiter` in policy.ts. Not thread-safe. */
class RateLimiter(private val max: Int, private val windowMs: Long) {
    private val stamps = ArrayDeque<Long>()

    private fun evict(now: Long) {
        while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) stamps.removeFirst()
    }

    fun canAcquire(now: Long): Boolean {
        evict(now); return stamps.size < max
    }

    fun tryAcquire(now: Long): Boolean {
        if (!canAcquire(now)) return false
        stamps.addLast(now); return true
    }
}

/**
 * FR-AU-05 budget: ≤ 1 reply per inbound burst, ≤ 6 replies/min per conversation, ≤ 60/hour total.
 * Per-conversation windows are bounded (LRU) so a flood of distinct senders cannot grow memory.
 */
class ReplyBudget(
    private val perConversationMax: Int = 6,
    private val perConversationWindowMs: Long = 60_000,
    globalMax: Int = 60,
    globalWindowMs: Long = 3_600_000,
    private val maxTrackedConversations: Int = 64,
) {
    private val global = RateLimiter(globalMax, globalWindowMs)
    private val perConversation = object : LinkedHashMap<String, RateLimiter>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RateLimiter>) = size > maxTrackedConversations
    }

    /** Burst rule: the conversation keys that have an unanswered inbound message. */
    private val awaitingReply = HashSet<String>()

    fun onInbound(conversation: String) {
        awaitingReply += conversation
    }

    fun canReply(conversation: String, now: Long): Boolean =
        conversation in awaitingReply && global.canAcquire(now) && limiter(conversation).canAcquire(now)

    /** Consume budget at send time. Returns false (and consumes nothing) when over budget. */
    fun consume(conversation: String, now: Long): Boolean {
        if (!canReply(conversation, now)) return false
        global.tryAcquire(now)
        limiter(conversation).tryAcquire(now)
        awaitingReply -= conversation
        return true
    }

    private fun limiter(c: String) = perConversation.getOrPut(c) { RateLimiter(perConversationMax, perConversationWindowMs) }
}

/**
 * Bot ping-pong detection (SPEC §11.6.2): ≥ [minExchanges] completed exchanges inside [windowMs]
 * where the other side answered our reply in < [fastReplyMs] each time → likely another bot.
 */
class PingPongDetector(
    private val minExchanges: Int = 3,
    private val windowMs: Long = 60_000,
    private val fastReplyMs: Long = 3_000,
) {
    private var lastSentAt: Long? = null
    private val fastExchanges = ArrayDeque<Long>()

    fun onSent(now: Long) {
        lastSentAt = now
    }

    /** Returns true when the inbound message completes a suspicious pattern. */
    fun onInbound(now: Long): Boolean {
        val sent = lastSentAt ?: return false
        lastSentAt = null
        if (now - sent < fastReplyMs) fastExchanges.addLast(now) else fastExchanges.clear()
        while (fastExchanges.isNotEmpty() && now - fastExchanges.first() >= windowMs) fastExchanges.removeFirst()
        return fastExchanges.size >= minExchanges
    }

    fun reset() {
        lastSentAt = null; fastExchanges.clear()
    }
}

/** Quiet hours as minutes-of-day; supports windows that wrap midnight (e.g. 22:00–07:00). */
data class QuietHours(val startMinute: Int, val endMinute: Int) {
    init {
        require(startMinute in 0 until 1440 && endMinute in 0 until 1440)
    }

    fun contains(minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }
}
