package com.agentkeyboard.app.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.agentkeyboard.core.privacy.PrivacyFilter
import com.agentkeyboard.core.protocol.ChatMessage

/**
 * Rung-2 context source (SPEC §10.1, ADR-0005): reads the text of the app being typed into.
 *
 * Read-only by construction in the `play` flavor (ADR-0006, Play accessibility policy): this class
 * never calls `performAction`/`dispatchGesture`; the only action code lives in the `full` flavor's
 * `A11yActions`. Reads are lazy (computed when the keyboard asks, not on every event), bounded
 * (node/depth/char caps) and filtered (password nodes, blocked apps, PII redaction) before leaving
 * this class.
 *
 * Note: accessibility cannot observe `FLAG_SECURE`; secure windows are enforced where they are
 * detectable (screenshots, Part 3). Here, blocked apps and password nodes are the guard.
 */
class ScreenReaderService : AccessibilityService() {

    data class Snapshot(
        val packageName: String,
        val title: String,
        val participants: List<String>,
        val messages: List<ChatMessage>,
        val text: String,
        val secureWindow: Boolean,
        val capturedAt: Long,
    )

    // Written on main (events) and on the reader thread (snapshots); races only cost a re-walk.
    @Volatile private var dirty = true
    @Volatile private var cached: Snapshot? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Cheap: only invalidate. Tree walks happen on demand in snapshot().
        dirty = true
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        cached = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** The app window under the keyboard (not our IME window, not system UI). */
    internal fun appRoot(): AccessibilityNodeInfo? {
        val win = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
        return win?.root ?: rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }
    }

    private fun snapshot(pkg: String): Snapshot? {
        val now = SystemClock.elapsedRealtime()
        cached?.let { if (!dirty && it.packageName == pkg && now - it.capturedAt < MAX_AGE_MS) return it }
        if (PrivacyFilter.isBlockedApp(pkg)) return null
        val root = appRoot() ?: return null
        if (root.packageName?.toString() != pkg) return null
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val texts = ArrayList<Pair<Rect, String>>(64)
        var visited = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (visited++ > MAX_NODES || depth > MAX_DEPTH) return
            if (!PrivacyFilter.admitNode(n.isPassword, inSecureWindow = false)) return
            if (!n.isEditable && n.isVisibleToUser) {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()
                if (!t.isNullOrEmpty() && t.length > 1) texts += Rect().also(n::getBoundsInScreen) to t.take(MAX_NODE_CHARS)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        texts.sortWith(compareBy({ it.first.top }, { it.first.left }))

        val headerBottom = screen.top + screen.height() * HEADER_FRACTION
        val title = texts.firstOrNull { it.first.bottom <= headerBottom }?.second.orEmpty()
        val body = texts.filter { it.first.top > headerBottom }
        // Chat heuristic: bubbles hugging the right edge are mine, left edge theirs.
        val messages = body.takeLast(MAX_MESSAGES).mapIndexed { i, (r, t) ->
            val mine = r.centerX() > screen.centerX() + screen.width() * 0.1
            ChatMessage("a11y_$i", mine, if (mine) "Me" else title, PrivacyFilter.redact(t), 0)
        }
        val looksLikeChat = messages.count { it.fromMe } in 1 until messages.size
        val snap = Snapshot(
            packageName = pkg,
            title = title,
            participants = listOfNotNull(title.takeIf { it.isNotEmpty() }),
            messages = if (looksLikeChat) messages else emptyList(),
            text = if (looksLikeChat) "" else PrivacyFilter.redact(body.joinToString("\n") { it.second }).take(MAX_TEXT_CHARS),
            secureWindow = false,
            capturedAt = now,
        )
        cached = snap
        dirty = false
        return snap
    }

    companion object {
        @Volatile internal var instance: ScreenReaderService? = null
            private set

        val isRunning: Boolean get() = instance != null

        private val reader = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ak-a11y-read") }
        private val main = android.os.Handler(android.os.Looper.getMainLooper())

        /**
         * Reads the app window off the main thread: every node access is a binder call into the host
         * app, and a large tree walk must never stall the IME's touch handling (NFR-P-01/02). [done]
         * runs on the main thread with null when the service is off, the app is blocked, or the window
         * isn't readable.
         */
        fun snapshotAsync(packageName: String, done: (Snapshot?) -> Unit) {
            val svc = instance ?: return done(null)
            reader.execute {
                val s = try { svc.snapshot(packageName) } catch (e: RuntimeException) { null } // window vanished mid-walk
                main.post { done(s) }
            }
        }

        private const val MAX_NODES = 600
        private const val MAX_DEPTH = 40
        private const val MAX_NODE_CHARS = 1_000
        private const val MAX_TEXT_CHARS = 6_000
        private const val MAX_MESSAGES = 20
        private const val MAX_AGE_MS = 1_500L
        private const val HEADER_FRACTION = 0.12f
    }
}
