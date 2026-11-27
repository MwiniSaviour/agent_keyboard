package com.agentkeyboard.core.context

import com.agentkeyboard.core.context.ScrollPolicy.Initiator
import com.agentkeyboard.core.protocol.ChatMessage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenScanTest {

    // ───────── policy ─────────

    @Test fun onlyTheUserMayTriggerScrollingInThePlayBuild() {
        assertTrue(ScrollPolicy.allowed(Initiator.USER, agentActionsBuild = false, consent = false))
        assertFalse(ScrollPolicy.allowed(Initiator.AGENT, agentActionsBuild = false, consent = true))
        assertFalse(ScrollPolicy.allowed(Initiator.AGENT, agentActionsBuild = true, consent = false))
        assertTrue(ScrollPolicy.allowed(Initiator.AGENT, agentActionsBuild = true, consent = true))
    }

    @Test fun directionFollowsWhereTheMissingContentIs() {
        assertEquals("up", ScrollPolicy.directionFor("reply", "chat"))
        assertEquals("up", ScrollPolicy.directionFor("summarize", "chat"))
        assertEquals("down", ScrollPolicy.directionFor("summarize", "article"))
        assertNull(ScrollPolicy.directionFor("refine", "chat"), "rewriting the field needs no scrolling")
        assertNull(ScrollPolicy.directionFor("reply", "article"))
    }

    // ───────── merger ─────────

    @Test fun scrollingDownAppendsWithoutTheOverlap() {
        val m = ScanMerger()
        assertEquals(4, m.add(listOf("Title", "a", "b", "c"), "down"))
        assertEquals(2, m.add(listOf("b", "c", "d", "e"), "down"))
        assertEquals("Title\na\nb\nc\nd\ne", m.text)
    }

    @Test fun scrollingUpPrependsOlderContentInOrder() {
        val m = ScanMerger()
        m.add(listOf("m5", "m6", "m7"), "up")
        assertEquals(2, m.add(listOf("m3", "m4", "m5", "m6"), "up"))
        assertEquals(2, m.add(listOf("m1", "m2", "m3"), "up"))
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5", "m6", "m7"), m.text.lines())
    }

    @Test fun stickyHeadersAndWhitespaceNoiseAreNotRepeated() {
        val m = ScanMerger()
        m.add(listOf("Ama", "  hello   there ", "how much?"), "up")
        assertEquals(1, m.add(listOf("Ama", "older line", "hello there"), "up"), "header 'Ama' is already known")
        assertEquals(listOf("older line", "Ama", "hello there", "how much?"), m.text.lines())
    }

    @Test fun mergerStopsAtItsBudget() {
        val m = ScanMerger(maxChars = 20)
        m.add(listOf("0123456789", "abcdefghij", "klmnopqrst"), "down")
        assertTrue(m.full)
        assertTrue(m.text.length <= 20)
    }

    // ───────── planner ─────────

    @Test fun plannerScrollsUntilTheEndAndRestoresEveryStep() {
        val p = ScanPlanner(maxPages = 8)
        assertEquals(ScanPlanner.Next.Scroll, p.start(scrollable = true))
        assertEquals(ScanPlanner.Next.Scroll, p.afterScroll(true, newLines = 5, budgetFull = false, cancelled = false))
        val done = p.afterScroll(scrollSucceeded = false, newLines = 0, budgetFull = false, cancelled = false)
        assertEquals(ScanPlanner.Next.Done(ScanPlanner.Stop.END, restoreSteps = 1), done)
        assertTrue(ScanPlanner.complete(ScanPlanner.Stop.END))
    }

    @Test fun listsThatClaimToScrollButDoNotMoveEndTheScan() {
        val p = ScanPlanner(maxPages = 8)
        p.start(true)
        assertEquals(ScanPlanner.Next.Scroll, p.afterScroll(true, 0, false, false))
        assertEquals(ScanPlanner.Next.Done(ScanPlanner.Stop.END, 2), p.afterScroll(true, 0, false, false))
    }

    @Test fun plannerHonoursPagesBudgetCancelAndNonScrollableScreens() {
        assertEquals(ScanPlanner.Next.Done(ScanPlanner.Stop.NOT_SCROLLABLE, 0), ScanPlanner(3).start(false))
        assertEquals(ScanPlanner.Next.Done(ScanPlanner.Stop.MAX_PAGES, 0), ScanPlanner(1).start(true))
        val p = ScanPlanner(3).also { it.start(true) }
        p.afterScroll(true, 4, false, false)
        assertEquals(ScanPlanner.Next.Done(ScanPlanner.Stop.MAX_PAGES, 2), p.afterScroll(true, 4, false, false))
        assertEquals(3, p.pages)
        val b = ScanPlanner(8).also { it.start(true) }
        assertEquals(ScanPlanner.Next.Done(ScanPlanner.Stop.BUDGET, 1), b.afterScroll(true, 4, budgetFull = true, cancelled = false))
        val c = ScanPlanner(8).also { it.start(true) }
        assertEquals(ScanPlanner.Next.Done(ScanPlanner.Stop.CANCELLED, 1), c.afterScroll(true, 4, false, cancelled = true))
        assertFalse(ScanPlanner.complete(ScanPlanner.Stop.CANCELLED))
    }

    // ───────── conversation resolution (review item A) ─────────

    @Test fun titlesMatchAcrossDecorationsAndUnknownHintsAreUncertain() {
        var now = 0L
        val cache = ConversationCache(clock = { now })
        cache.record("com.whatsapp", "Ama Mensah", listOf(ChatMessage("1", false, "Ama", "hi", 1)))
        assertEquals("likely", cache.resolve("com.whatsapp", null)?.match)
        assertEquals("exact", cache.resolve("com.whatsapp", "  ama   MENSAH ✓")?.match)
        val other = cache.resolve("com.whatsapp", "Kwame")!!
        assertEquals("uncertain", other.match, "the screen shows a chat we have no notifications for")
        assertFalse(other.usableForDrafting)
        assertNull(cache.resolve("org.telegram.messenger", null))
        now += 7 * 60 * 60 * 1000L
        assertNull(cache.resolve("com.whatsapp", "Ama Mensah"), "expired")
    }
}
