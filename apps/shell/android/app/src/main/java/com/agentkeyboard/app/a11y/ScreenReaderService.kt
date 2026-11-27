package com.agentkeyboard.app.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import com.agentkeyboard.core.context.ScanMerger
import com.agentkeyboard.core.context.ScanPlanner
import com.agentkeyboard.core.privacy.PrivacyFilter
import com.agentkeyboard.core.protocol.ChatMessage

/**
 * Rung-2 context source (SPEC §10.1, ADR-0005): reads the text of the app being typed into, and
 * (ADR-0010) can read more than one screen of it by scrolling and scrolling back.
 *
 * Actions: the only action this class performs is a scroll on the app's main list, and only through
 * [scanAsync], which the keyboard calls when the user taps "Read earlier messages / whole page"
 * (Play accessibility policy: a deterministic routine the user starts, no model in the loop). It
 * never clicks, types, or gestures; Autopilot's send click lives in the `full` flavor's
 * `A11yActions` (ADR-0006). Reads are lazy (computed when the keyboard asks, not on every event),
 * bounded (node/depth/char caps) and filtered (password nodes, blocked apps, PII redaction) before
 * leaving this class.
 *
 * Note: accessibility cannot observe `FLAG_SECURE`; secure windows show up as black frames in live
 * capture (ScreenCaptureService). Here, blocked apps and password nodes are the guard.
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

    /** A scroll-and-read result: the merged transcript plus how the scan went. */
    data class Scan(val snapshot: Snapshot, val pages: Int, val complete: Boolean, val restored: Boolean)

    /** One screen of visible text, top-to-bottom, header split off. */
    private class Page(val title: String, val body: List<Pair<Rect, String>>, val screen: Rect)

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

    private fun readPage(pkg: String): Page? {
        if (PrivacyFilter.isBlockedApp(pkg)) return null
        val root = appRoot() ?: return null
        if (root.packageName?.toString() != pkg) return null.also { release(root) }
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
            for (i in 0 until n.childCount) n.getChild(i)?.let { c -> walk(c, depth + 1); release(c) }
        }
        walk(root, 0)
        release(root)
        texts.sortWith(compareBy({ it.first.top }, { it.first.left }))
        val headerBottom = screen.top + screen.height() * HEADER_FRACTION
        val title = texts.firstOrNull { it.first.bottom <= headerBottom }?.second.orEmpty()
        return Page(title, texts.filter { it.first.top > headerBottom }, screen)
    }

    /** Chat heuristic: bubbles hugging the right edge are mine, left edge theirs. Tagged for merging. */
    private fun tagged(page: Page): List<String> = page.body.map { (r, t) ->
        val mine = r.centerX() > page.screen.centerX() + page.screen.width() * 0.1
        (if (mine) TAG_ME else TAG_THEM) + t
    }

    private fun build(pkg: String, title: String, lines: List<String>, maxMessages: Int, maxText: Int): Snapshot {
        val now = System.currentTimeMillis()
        val messages = lines.takeLast(maxMessages).mapIndexed { i, line ->
            val mine = line.startsWith(TAG_ME)
            // Screen-read messages carry no timestamp; `at` is when they were read (never 0).
            ChatMessage("a11y_$i", mine, if (mine) "Me" else title, PrivacyFilter.redact(line.substring(TAG_ME.length)), now)
        }
        val looksLikeChat = messages.count { it.fromMe } in 1 until messages.size
        return Snapshot(
            packageName = pkg,
            title = title,
            participants = listOfNotNull(title.takeIf { it.isNotEmpty() }),
            messages = if (looksLikeChat) messages else emptyList(),
            text = if (looksLikeChat) "" else PrivacyFilter.redact(lines.joinToString("\n") { it.substring(TAG_ME.length) }).take(maxText),
            secureWindow = false,
            capturedAt = SystemClock.elapsedRealtime(),
        )
    }

    private fun snapshot(pkg: String): Snapshot? {
        val now = SystemClock.elapsedRealtime()
        cached?.let { if (!dirty && it.packageName == pkg && now - it.capturedAt < MAX_AGE_MS) return it }
        val page = readPage(pkg) ?: return null
        val snap = build(pkg, page.title, tagged(page), MAX_MESSAGES, MAX_TEXT_CHARS)
        cached = snap
        dirty = false
        return snap
    }

    /**
     * Scroll-and-read: read the visible page, scroll the app's main list [direction] ("up" = older
     * chat messages, "down" = rest of a page), read again, … as [ScanPlanner] decides, then scroll
     * back the same number of steps. Reader thread only (sleeps between steps while content settles).
     * Stops early when [cancelled] says so (focus moved, a secure field appeared, the keyboard closed).
     */
    private fun scan(pkg: String, direction: String, maxPages: Int, cancelled: () -> Boolean): Scan? {
        val first = readPage(pkg) ?: return null
        val merger = ScanMerger(MAX_SCAN_CHARS)
        merger.add(tagged(first), direction)
        val planner = ScanPlanner(maxPages)
        val forward = direction == "down"
        var next = planner.start(scrollable = findScrollable(pkg) != null)
        while (next is ScanPlanner.Next.Scroll) {
            val ok = !cancelled() && scrollOnce(pkg, forward)
            if (ok) SystemClock.sleep(SETTLE_MS)
            val page = if (ok) readPage(pkg) else null
            val fresh = page?.let { merger.add(tagged(it), direction) } ?: 0
            next = planner.afterScroll(ok && page != null, fresh, merger.full, cancelled())
        }
        val done = next as ScanPlanner.Next.Done
        // Put the app back where the user left it. Each step is undone with the opposite scroll.
        var restored = true
        repeat(done.restoreSteps) {
            if (!scrollOnce(pkg, !forward)) restored = false else SystemClock.sleep(RESTORE_SETTLE_MS)
        }
        dirty = true
        cached = null
        val lines = merger.text.split('\n').filter { it.length > TAG_ME.length }
        return Scan(build(pkg, first.title, lines, MAX_SCAN_MESSAGES, MAX_SCAN_CHARS), planner.pages, ScanPlanner.complete(done.stop), restored)
    }

    /** The largest visible scrollable node: the conversation list or the page body. */
    private fun findScrollable(pkg: String): AccessibilityNodeInfo? {
        val root = appRoot() ?: return null
        if (root.packageName?.toString() != pkg) return null.also { release(root) }
        // Scrollable nodes are held until the end and the losers released then: releasing a node
        // while its subtree is still being walked would break pre-33 node pooling.
        val held = ArrayList<Pair<AccessibilityNodeInfo, Int>>(4)
        var visited = 0
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo, depth: Int): Boolean { // true = n is held
            if (visited++ > MAX_NODES || depth > MAX_DEPTH) return false
            val hold = n.isScrollable && n.isVisibleToUser
            if (hold) {
                n.getBoundsInScreen(r)
                held += n to r.width() * r.height()
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { c -> if (!walk(c, depth + 1)) release(c) }
            return hold
        }
        if (!walk(root, 0)) release(root)
        val best = held.maxByOrNull { it.second }?.first
        held.forEach { (n, _) -> if (n !== best) release(n) }
        return best
    }

    private fun scrollOnce(pkg: String, forward: Boolean): Boolean {
        val node = findScrollable(pkg) ?: return false
        return try {
            // Prefer the directional actions (API 23+) when the list exposes them: in reversed chat
            // lists "backward" and "up" can differ.
            val directional = if (forward) AccessibilityAction.ACTION_SCROLL_DOWN else AccessibilityAction.ACTION_SCROLL_UP
            if (node.actionList.any { it.id == directional.id }) node.performAction(directional.id)
            else node.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        } finally {
            release(node)
        }
    }

    companion object {
        @Volatile internal var instance: ScreenReaderService? = null
            private set

        val isRunning: Boolean get() = instance != null

        private val reader = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ak-a11y-read") }
        private val main = android.os.Handler(android.os.Looper.getMainLooper())

        /** True while a scan is scrolling the app (live capture keeps analysing frames meanwhile). */
        @Volatile var scanning: Boolean = false
            private set

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

        /**
         * Scroll-and-read on the reader thread (serialised with snapshots, so a scan and a read never
         * interleave). [cancelled] is polled between steps from that thread; [done] runs on main.
         */
        fun scanAsync(packageName: String, direction: String, maxPages: Int, cancelled: () -> Boolean, done: (Scan?) -> Unit) {
            val svc = instance ?: return done(null)
            reader.execute {
                scanning = true
                val s = try {
                    svc.scan(packageName, direction, maxPages, cancelled)
                } catch (e: RuntimeException) {
                    null
                } finally {
                    scanning = false
                }
                main.post { done(s) }
            }
        }

        /** Node objects are pooled before API 33 and must be handed back; later they are plain objects. */
        @Suppress("DEPRECATION")
        private fun release(n: AccessibilityNodeInfo) {
            if (Build.VERSION.SDK_INT < 33) n.recycle()
        }

        private const val TAG_ME = "M|"
        private const val TAG_THEM = "T|"
        private const val MAX_NODES = 600
        private const val MAX_DEPTH = 40
        private const val MAX_NODE_CHARS = 1_000
        private const val MAX_TEXT_CHARS = 6_000
        private const val MAX_MESSAGES = 20
        private const val MAX_SCAN_MESSAGES = 60
        private const val MAX_SCAN_CHARS = 12_000
        private const val MAX_AGE_MS = 1_500L
        private const val SETTLE_MS = 260L
        private const val RESTORE_SETTLE_MS = 120L
        private const val HEADER_FRACTION = 0.12f
    }
}
