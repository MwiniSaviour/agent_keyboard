package com.agentkeyboard.core.context

/**
 * Scroll-and-read (ADR-0010): the agent reads more than one screen of the app under the keyboard by
 * scrolling it with accessibility scroll actions, reading each page, and scrolling back.
 *
 * Everything that decides *whether* and *how far* to scroll is here, platform-free and tested; the
 * Android `ScreenScanner` only performs the steps it is told to.
 */
object ScrollPolicy {
    enum class Initiator {
        /** The user tapped "Read whole screen" (a deterministic, user-triggered routine). */
        USER,

        /** Native code decided to read more before an AI run (no model in the loop, but not a tap). */
        AGENT,
    }

    /**
     * Google Play's Accessibility API policy forbids using the API to autonomously initiate actions.
     * So in the Play build only the user can trigger a scan; the `full` (sideload) build may also scan
     * on the agent's behalf, and only after the user granted Autopilot consent (ADR-0006, ADR-0010).
     */
    fun allowed(initiator: Initiator, agentActionsBuild: Boolean, consent: Boolean): Boolean = when (initiator) {
        Initiator.USER -> true
        Initiator.AGENT -> agentActionsBuild && consent
    }

    /** Skills that profit from more than one screen, and the direction that holds the missing part. */
    fun directionFor(skill: String, screenType: String): String? = when {
        screenType == "chat" -> if (skill in READS_HISTORY) "up" else null // older messages are above
        skill in READS_DOCUMENT -> "down" // the rest of an article/email is below
        else -> null
    }

    private val READS_HISTORY = setOf("reply", "summarize", "explain")
    private val READS_DOCUMENT = setOf("summarize", "explain")
}

/**
 * Merges the text of successive pages into one ordered transcript, de-duplicating the overlap between
 * neighbouring pages (scrolls move less than a full page, and sticky headers repeat on every page).
 *
 * Scrolling **up** reveals older content, which is prepended; scrolling **down** appends.
 */
class ScanMerger(private val maxChars: Int = 12_000) {
    private val lines = ArrayDeque<String>()
    private val seen = HashSet<String>()
    private var chars = 0

    val text: String get() = lines.joinToString("\n")
    val lineCount: Int get() = lines.size
    val full: Boolean get() = chars >= maxChars

    /** Adds one page (top-to-bottom order). Returns how many new lines it contributed. */
    fun add(page: List<String>, direction: String): Int {
        val clean = page.map(::normalize).filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            clean.forEach { push(it, append = true) }
            return lines.size
        }
        val fresh = if (direction == "up") {
            val k = overlap(clean, lines.take(clean.size))
            clean.dropLast(k).filter { it !in seen }
        } else {
            val k = overlap(lines.takeLast(clean.size), clean)
            clean.drop(k).filter { it !in seen }
        }
        if (direction == "up") fresh.asReversed().forEach { push(it, append = false) } else fresh.forEach { push(it, append = true) }
        return fresh.size
    }

    private fun push(line: String, append: Boolean) {
        if (full) return
        val l = line.take(maxChars - chars)
        if (append) lines.addLast(l) else lines.addFirst(l)
        seen += l
        chars += l.length + 1
    }

    companion object {
        fun normalize(s: String): String = s.trim().replace(WS, " ")
        private val WS = Regex("\\s+")

        /** Largest k such that the last k lines of [head] equal the first k lines of [tail]. */
        fun overlap(head: List<String>, tail: List<String>): Int {
            for (k in minOf(head.size, tail.size) downTo 1) {
                var ok = true
                for (i in 0 until k) if (head[head.size - k + i] != tail[i]) { ok = false; break }
                if (ok) return k
            }
            return 0
        }
    }
}

/**
 * The scan loop as a state machine: after each page the host reports whether the scroll action
 * succeeded and how many lines were new; the planner says whether to scroll again and, at the end,
 * how many steps to scroll back.
 */
class ScanPlanner(private val maxPages: Int) {
    enum class Stop { END, MAX_PAGES, BUDGET, NOT_SCROLLABLE, CANCELLED }

    sealed interface Next {
        data object Scroll : Next
        data class Done(val stop: Stop, val restoreSteps: Int) : Next
    }

    var pages = 1 // the visible page is read before the first decision
        private set
    private var scrolled = 0
    private var dryPages = 0

    init {
        require(maxPages >= 1)
    }

    /** Before the first scroll. */
    fun start(scrollable: Boolean): Next = when {
        !scrollable -> Next.Done(Stop.NOT_SCROLLABLE, 0)
        maxPages == 1 -> Next.Done(Stop.MAX_PAGES, 0)
        else -> Next.Scroll
    }

    /** After a scroll attempt and the read of the page it revealed. */
    fun afterScroll(scrollSucceeded: Boolean, newLines: Int, budgetFull: Boolean, cancelled: Boolean): Next {
        if (scrollSucceeded) scrolled++
        if (cancelled) return Next.Done(Stop.CANCELLED, scrolled)
        if (!scrollSucceeded) return Next.Done(Stop.END, scrolled)
        pages++
        // Some lists report success at their end without moving; two dry pages in a row means the end.
        dryPages = if (newLines == 0) dryPages + 1 else 0
        return when {
            dryPages >= 2 || (newLines == 0 && pages >= maxPages) -> Next.Done(Stop.END, scrolled)
            budgetFull -> Next.Done(Stop.BUDGET, scrolled)
            pages >= maxPages -> Next.Done(Stop.MAX_PAGES, scrolled)
            else -> Next.Scroll
        }
    }

    companion object {
        /** True when the scan saw everything in that direction. */
        fun complete(stop: Stop): Boolean = stop == Stop.END || stop == Stop.NOT_SCROLLABLE
    }
}
