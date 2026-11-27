package com.agentkeyboard.core.context

import com.agentkeyboard.core.privacy.PrivacyFilter
import com.agentkeyboard.core.protocol.ChatMessage

/** App category heuristics for context (SPEC §10.2 `app.category`). Unknown apps are `other`. */
object AppCategories {
    private val CHAT = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca", "com.facebook.mlite",
        "org.telegram.messenger", "org.thoughtcrime.securesms", "com.google.android.apps.messaging",
        "com.samsung.android.messaging", "com.viber.voip", "com.snapchat.android", "com.discord",
        "com.Slack", "com.microsoft.teams", "jp.naver.line.android", "com.imo.android.imoim",
    )
    private val EMAIL = setOf("com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail", "ch.protonmail.android")
    private val BROWSER = setOf("com.android.chrome", "org.mozilla.firefox", "com.brave.browser", "com.opera.browser", "com.opera.mini.native", "com.sec.android.app.sbrowser")
    private val SOCIAL = setOf("com.instagram.android", "com.twitter.android", "com.zhiliaoapp.musically", "com.linkedin.android", "com.facebook.katana")
    private val DOCS = setOf("com.google.android.apps.docs.editors.docs", "com.microsoft.office.word", "com.google.android.keep", "com.samsung.android.app.notes")

    fun of(pkg: String): String = when (pkg) {
        in CHAT -> "chat"
        in EMAIL -> "email"
        in BROWSER -> "browser"
        in SOCIAL -> "social"
        in DOCS -> "docs"
        else -> "other"
    }

    fun screenTypeFor(category: String): String = when (category) {
        "chat" -> "chat"
        "email" -> "email"
        "social" -> "feed"
        "docs" -> "article"
        else -> "unknown"
    }
}

/**
 * Recent conversation messages collected from notifications (rung 1, SPEC §10.1). Bounded in every
 * dimension (conversations, messages, age) because the IME and the listener share a tight memory
 * budget, and text is redacted on the way in, so no raw OTP or card number is ever retained.
 */
class ConversationCache(
    private val maxConversations: Int = 32,
    private val maxMessages: Int = 20,
    private val ttlMs: Long = 6 * 60 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Conversation(val key: String, val packageName: String, val title: String, val messages: List<ChatMessage>, val updatedAt: Long)

    private val map = object : LinkedHashMap<String, Conversation>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Conversation>) = size > maxConversations
    }

    @Synchronized
    fun record(packageName: String, title: String, incoming: List<ChatMessage>): Conversation? {
        if (PrivacyFilter.isBlockedApp(packageName)) return null
        val key = keyOf(packageName, title)
        val now = clock()
        val existing = map[key]?.messages.orEmpty().filter { now - it.at < ttlMs }
        val seen = existing.mapTo(HashSet()) { Triple(it.author, it.text, it.at) }
        val merged = existing.toMutableList()
        for (m in incoming) {
            val clean = m.copy(text = PrivacyFilter.redact(m.text).take(MAX_MESSAGE_CHARS))
            if (seen.add(Triple(clean.author, clean.text, clean.at))) merged += clean
        }
        merged.sortBy { it.at }
        val conv = Conversation(key, packageName, title, merged.takeLast(maxMessages), now)
        map[key] = conv
        return conv
    }

    @Synchronized
    fun get(key: String): Conversation? = map[key]?.takeIf { clock() - it.updatedAt < ttlMs }

    /** Best match for the focused app: the most recently updated conversation of that package. */
    @Synchronized
    fun latestFor(packageName: String): Conversation? =
        map.values.filter { it.packageName == packageName && clock() - it.updatedAt < ttlMs }.maxByOrNull { it.updatedAt }

    @Synchronized
    fun clear() = map.clear()

    companion object {
        const val MAX_MESSAGE_CHARS = 2_000
        fun keyOf(packageName: String, title: String) = "$packageName|${title.trim().lowercase()}"
    }
}
