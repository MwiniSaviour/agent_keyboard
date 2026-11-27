package com.agentkeyboard.app.notify

import android.app.Notification
import android.app.Person
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.app.agent.AgentHub
import com.agentkeyboard.core.context.AppCategories
import com.agentkeyboard.core.context.ConversationCache
import com.agentkeyboard.core.privacy.PrivacyFilter
import com.agentkeyboard.core.protocol.ChatMessage

/**
 * Rung-1 context (SPEC §10.1) and the only Play-compliant background reply path (FR-AU-09,
 * ADR-0006): chat notifications are parsed into messages, redacted by the conversation cache, and
 * announced to the IME. Reply actions that accept free-form `RemoteInput` are kept in memory, so a
 * consented, per-contact background reply (Part 3) can answer without opening the app.
 *
 * Runs in `:agent`. Nothing here is persisted; message capture to the DB is a separate opt-in.
 */
class AkNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        // Warm the cache from what is already in the shade (bounded; cheap).
        runCatching { activeNotifications }.getOrNull()?.take(MAX_WARM)?.forEach(::ingest)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = ingest(sbn)

    /**
     * A dismissed or answered notification takes its reply action with it: the PendingIntent may
     * still fire, but would answer a thread the user already closed (or the app already cancelled).
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.trim()?.take(MAX_TITLE)?.takeIf { it.isNotEmpty() } ?: return
        val key = ConversationCache.keyOf(sbn.packageName, title)
        synchronized(replies) { replies.remove(key) }
    }

    private fun ingest(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName || PrivacyFilter.isBlockedApp(pkg)) return
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return
        val extras = n.extras ?: return
        val messaging = extras.getString(Notification.EXTRA_TEMPLATE) == MESSAGING_TEMPLATE
        if (!messaging && AppCategories.of(pkg) != "chat") return

        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.trim()?.take(MAX_TITLE)?.takeIf { it.isNotEmpty() } ?: return
        val messages = if (messaging) messagingStyle(extras, sbn.postTime) else plain(extras, title, sbn.postTime)
        if (messages.isEmpty()) return

        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val key = ConversationCache.keyOf(pkg, title)
        synchronized(replies) { if (reply != null) replies[key] = reply else replies.remove(key) }

        AgentHub.post { it.onNotification(pkg, title, messages, remoteInputAvailable = reply != null) }
    }

    /**
     * `Notification.MessagingStyle` extras. The device owner's own messages have a null sender, or
     * (common in practice) a sender equal to the style's user Person (`EXTRA_MESSAGING_PERSON`). Getting
     * this wrong would make autonomy answer the user's own replies (FR-AU-04/05 loop protection).
     */
    private fun messagingStyle(extras: Bundle, postTime: Long): List<ChatMessage> {
        @Suppress("DEPRECATION")
        val raw: Array<Parcelable> = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        @Suppress("DEPRECATION")
        val me: Person? = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON)
        @Suppress("DEPRECATION") // still written by apps targeting < 28 MessagingStyle(CharSequence)
        val myName = (me?.name ?: extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME))?.toString()?.takeIf { it.isNotBlank() }
        return raw.takeLast(MAX_MESSAGES).mapIndexedNotNull { i, p ->
            val b = p as? Bundle ?: return@mapIndexedNotNull null
            val text = b.getCharSequence(KEY_TEXT)?.toString()?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            @Suppress("DEPRECATION")
            val person = b.getParcelable<Person>(KEY_SENDER_PERSON)
            val sender = (person?.name ?: b.getCharSequence(KEY_SENDER))?.toString()
            val fromMe = sender == null ||
                (me?.key != null && person?.key == me.key) ||
                (person?.key == null && myName != null && sender == myName)
            val at = b.getLong(KEY_TIME).takeIf { it > 0 } ?: postTime // some apps omit the per-message time
            ChatMessage("n_${at}_$i", fromMe = fromMe, author = if (fromMe) "Me" else sender.orEmpty(), text = text, at = at)
        }
    }

    /** SMS-style notifications: one line, title = sender. */
    private fun plain(extras: Bundle, title: String, postTime: Long): List<ChatMessage> {
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(ChatMessage("n_$postTime", fromMe = false, author = title, text = text, at = postTime))
    }

    companion object {
        private const val MESSAGING_TEMPLATE = "android.app.Notification\$MessagingStyle"
        // Bundle keys written by Notification.MessagingStyle.Message#toBundle (stable since API 24).
        private const val KEY_TEXT = "text"
        private const val KEY_TIME = "time"
        private const val KEY_SENDER = "sender"
        private const val KEY_SENDER_PERSON = "sender_person"
        private const val MAX_MESSAGES = 20
        private const val MAX_TITLE = 128
        private const val MAX_WARM = 50
        private const val MAX_REPLY_ACTIONS = 32

        /** Conversation key → reply action. In-memory only; actions die with their notification. */
        private val replies = object : LinkedHashMap<String, Notification.Action>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Notification.Action>) = size > MAX_REPLY_ACTIONS
        }

        /**
         * Sends [text] through the conversation's RemoteInput. The caller (Part 3 background autonomy)
         * must already have checked consent, the per-contact opt-in and the rate limits (ADR-0006).
         */
        fun replyVia(service: android.content.Context, conversationKey: String, text: String): Boolean {
            val action = synchronized(replies) { replies[conversationKey] } ?: return false
            val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return false
            val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(arrayOf(input), fill, Bundle().apply { putCharSequence(input.resultKey, text) })
            return try {
                action.actionIntent.send(service, 0, fill)
                true
            } catch (e: android.app.PendingIntent.CanceledException) {
                AkLog.w("reply action expired")
                synchronized(replies) { replies.remove(conversationKey) }
                false
            }
        }
    }
}
