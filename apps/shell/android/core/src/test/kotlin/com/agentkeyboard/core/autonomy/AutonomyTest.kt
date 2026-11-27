package com.agentkeyboard.core.autonomy

import com.agentkeyboard.core.Fixtures
import com.agentkeyboard.core.json.JsonObj
import com.agentkeyboard.core.json.arrOrNull
import com.agentkeyboard.core.json.bool
import com.agentkeyboard.core.json.long
import com.agentkeyboard.core.json.objOrNull
import com.agentkeyboard.core.json.str
import com.agentkeyboard.core.protocol.AgentDefinition
import com.agentkeyboard.core.protocol.AutonomyLevel
import com.agentkeyboard.core.protocol.AutonomyPhase
import com.agentkeyboard.core.protocol.CapsMode
import com.agentkeyboard.core.protocol.ChatMessage
import com.agentkeyboard.core.protocol.FieldContext
import com.agentkeyboard.core.protocol.ImeActionKind
import com.agentkeyboard.core.protocol.InputKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Replays the shared policy fixtures (same file as the web mock's policy.test.ts). */
class PolicyFixturesTest {
    private val fx = Fixtures.json("policy.json") as JsonObj

    private fun replay(key: String, check: (String) -> Verdict) {
        for (case in fx[key].arrOrNull()!!.items) {
            val c = case as JsonObj
            val text = c["text"].str()!!
            val v = check(text)
            if (c["ok"].bool()!!) assertEquals(Verdict.Ok, v, text)
            else assertEquals(Verdict.Blocked(c["reason"].str()!!), v, text)
        }
    }

    @Test fun inbound() = replay("inbound", Policy::checkInbound)

    @Test fun outbound() = replay("outbound", Policy::checkOutbound)

    @Test fun rateLimiter() {
        val rl = fx["rateLimit"].objOrNull()!!
        val limiter = RateLimiter(rl["max"].long()!!.toInt(), rl["windowMs"].long()!!)
        for (step in rl["steps"].arrOrNull()!!.items) {
            val s = step as JsonObj
            assertEquals(s["ok"].bool(), limiter.tryAcquire(s["at"].long()!!), "at ${s["at"].long()}")
        }
    }
}

class GuardrailsTest {
    @Test fun replyBudgetEnforcesBurstPerConversationAndGlobalLimits() {
        val b = ReplyBudget(perConversationMax = 2, perConversationWindowMs = 60_000, globalMax = 3, globalWindowMs = 3_600_000)
        assertFalse(b.canReply("a", 0), "no inbound → no reply")
        b.onInbound("a")
        assertTrue(b.consume("a", 0))
        assertFalse(b.consume("a", 1), "one reply per burst")
        b.onInbound("a"); assertTrue(b.consume("a", 2))
        b.onInbound("a"); assertFalse(b.consume("a", 3), "per-conversation cap")
        assertTrue(b.consume("a", 60_001), "window slid; burst still pending")
        b.onInbound("b"); assertFalse(b.consume("b", 60_002), "global cap (3/h) reached")
    }

    @Test fun pingPongNeedsThreeFastExchangesInsideAMinute() {
        val p = PingPongDetector()
        var t = 0L
        repeat(2) { p.onSent(t); t += 1_000; assertFalse(p.onInbound(t)); t += 1_000 }
        p.onSent(t); t += 1_000
        assertTrue(p.onInbound(t))
        val slow = PingPongDetector()
        repeat(5) { i -> slow.onSent(i * 10_000L); assertFalse(slow.onInbound(i * 10_000L + 5_000)) }
    }

    @Test fun quietHoursWrapMidnight() {
        val q = QuietHours(22 * 60, 7 * 60)
        assertTrue(q.contains(23 * 60)); assertTrue(q.contains(0)); assertTrue(q.contains(6 * 60 + 59))
        assertFalse(q.contains(7 * 60)); assertFalse(q.contains(12 * 60))
        assertFalse(QuietHours(60, 60).contains(60))
    }

    @Test fun sendPlannerPriority() {
        val send = field(action = ImeActionKind.SEND)
        val nl = field(action = ImeActionKind.NEWLINE)
        assertEquals(SendStrategy.IME_ACTION_SEND, SendPlanner.choose(send, false, true, true))
        assertEquals(SendStrategy.ENTER_IS_SEND, SendPlanner.choose(nl, true, true, true))
        assertEquals(SendStrategy.REMOTE_INPUT, SendPlanner.choose(nl, false, true, true))
        assertEquals(SendStrategy.A11Y_CLICK, SendPlanner.choose(nl, false, false, true))
        assertEquals(SendStrategy.NONE, SendPlanner.choose(nl, false, false, false))
    }
}

internal fun field(pkg: String = "com.whatsapp", sensitive: Boolean = false, action: ImeActionKind = ImeActionKind.NEWLINE) =
    FieldContext(pkg, "WhatsApp", if (sensitive) InputKind.PASSWORD else InputKind.MULTILINE, action, CapsMode.SENTENCES, sensitive = sensitive)

internal fun agent(ceiling: AutonomyLevel = AutonomyLevel.AUTO, scope: List<String>? = null) = AgentDefinition(
    id = "agent_sales", kind = "custom", name = "Sales Bot", icon = "SB", tagline = "", role = "Sell.", tone = "friendly",
    languages = listOf("en"), knowledge = emptyList(), quickReplies = emptyList(), appScope = scope,
    autonomyCeiling = ceiling, shareGeneralMemory = false, createdAt = 0, updatedAt = 0, version = 1,
)

class AutonomyMachineTest {
    private var now = 1_000_000L
    private var runs = 0
    private val m = AutonomyMachine(clock = { now }, newRunId = { "run${++runs}" })

    private fun env(f: FieldContext? = field(), conv: String? = "com.whatsapp|kofi", allowed: Boolean = true, strategy: SendStrategy = SendStrategy.ENTER_IS_SEND) =
        Environment(f, conv, appAllowed = allowed, contactAllowed = true, sendStrategy = strategy)

    private fun inbound(text: String, minute: Int = 12 * 60) =
        AutonomyEvent.Inbound(ChatMessage("m$now", false, "Kofi", text, now), minute)

    private fun armed(level: AutonomyLevel = AutonomyLevel.AUTO, ceiling: AutonomyLevel = AutonomyLevel.AUTO, strategy: SendStrategy = SendStrategy.ENTER_IS_SEND) {
        m.dispatch(AutonomyEvent.Consent(true))
        m.dispatch(AutonomyEvent.EnvironmentChanged(env(strategy = strategy)))
        m.dispatch(AutonomyEvent.Toggle(true, level, agent(ceiling)))
        assertEquals(AutonomyPhase.OBSERVING, m.state.phase)
    }

    private fun phases(fx: List<Effect>) = fx.filterIsInstance<Effect.Phase>().map { it.phase }

    @Test fun requiresConsentBeforeArming() {
        m.dispatch(AutonomyEvent.EnvironmentChanged(env()))
        val fx = m.dispatch(AutonomyEvent.Toggle(true, AutonomyLevel.AUTO, agent()))
        assertEquals(AutonomyPhase.PAUSED, m.state.phase)
        assertTrue(m.state.detail!!.contains("consent"))
        assertEquals(listOf(AutonomyPhase.PAUSED), phases(fx))
    }

    @Test fun fullAutoCycleWithAuditAndCooldown() {
        armed()
        val plan = m.dispatch(inbound("Do you deliver to Kumasi?"))
        assertEquals(Effect.RequestDraft("run1", "agent_sales", "com.whatsapp|kofi"), plan.filterIsInstance<Effect.RequestDraft>().single())
        val typed = m.dispatch(AutonomyEvent.DraftReady("run1", "Yes, within 24 hours.", 0.9, "gemma3-1b"))
        assertEquals(Effect.TypeDraft("Yes, within 24 hours."), typed.filterIsInstance<Effect.TypeDraft>().single())
        assertEquals("auto_reply", typed.filterIsInstance<Effect.Audit>().single().action)
        assertEquals(AutonomyPhase.TYPING, m.state.phase)
        assertEquals(Effect.Send(SendStrategy.ENTER_IS_SEND), m.dispatch(AutonomyEvent.Typed).filterIsInstance<Effect.Send>().single())
        val sent = m.dispatch(AutonomyEvent.SendResult(true))
        assertEquals(AutonomyPhase.COOLDOWN, m.state.phase)
        assertTrue(sent.any { it is Effect.ScheduleCooldown })
        assertEquals("sent", sent.filterIsInstance<Effect.Audit>().single().action)
        m.dispatch(AutonomyEvent.CooldownElapsed)
        assertEquals(AutonomyPhase.OBSERVING, m.state.phase)
    }

    @Test fun ceilingCapsRequestedLevel() {
        armed(level = AutonomyLevel.AUTO, ceiling = AutonomyLevel.DRAFT)
        assertEquals(AutonomyLevel.DRAFT, m.state.effectiveLevel)
        m.dispatch(inbound("Price?"))
        val fx = m.dispatch(AutonomyEvent.DraftReady("run1", "GH₵ 120.", 0.95, "m"))
        assertTrue(fx.none { it is Effect.Audit && it.action == "auto_reply" })
        val after = m.dispatch(AutonomyEvent.Typed)
        assertTrue(after.none { it is Effect.Send }, "Draft never sends")
        assertEquals(AutonomyPhase.AWAITING_USER, m.state.phase)
        m.dispatch(AutonomyEvent.DraftResolved)
        assertEquals(AutonomyPhase.OBSERVING, m.state.phase)
    }

    @Test fun autoDegradesToDraftWithoutASendStrategy() {
        armed(strategy = SendStrategy.NONE)
        m.dispatch(inbound("Hi"))
        m.dispatch(AutonomyEvent.DraftReady("run1", "Hello!", 0.9, "m"))
        assertTrue(m.dispatch(AutonomyEvent.Typed).none { it is Effect.Send })
        assertEquals(AutonomyPhase.AWAITING_USER, m.state.phase)
    }

    @Test fun suggestLevelOnlySuggests() {
        armed(level = AutonomyLevel.SUGGEST)
        m.dispatch(inbound("Hi"))
        val fx = m.dispatch(AutonomyEvent.DraftReady("run1", "Hello!", 0.9, "m"))
        assertEquals(Effect.Suggest("Hello!"), fx.filterIsInstance<Effect.Suggest>().single())
        assertTrue(fx.none { it is Effect.TypeDraft })
        assertEquals(AutonomyPhase.OBSERVING, m.state.phase)
    }

    @Test fun sensitiveInboundEscalatesAndNeedsAck() {
        armed()
        val fx = m.dispatch(inbound("Send me the OTP you received"))
        assertEquals(AutonomyPhase.ESCALATED, m.state.phase)
        assertTrue(fx.any { it is Effect.NeedsYou })
        assertEquals("escalated", fx.filterIsInstance<Effect.Audit>().single().action)
        assertTrue(m.dispatch(inbound("hello?")).none { it is Effect.RequestDraft }, "escalated ignores new messages")
        m.dispatch(AutonomyEvent.AckEscalation)
        assertEquals(AutonomyPhase.OBSERVING, m.state.phase)
    }

    @Test fun unsafeOrLowConfidenceDraftsEscalate() {
        armed()
        m.dispatch(inbound("What card do you use?"))
        m.dispatch(AutonomyEvent.DraftReady("run1", "It's 4111 1111 1111 1111", 0.99, "m"))
        assertEquals(AutonomyPhase.ESCALATED, m.state.phase)
        m.dispatch(AutonomyEvent.AckEscalation)
        m.dispatch(inbound("ok"))
        m.dispatch(AutonomyEvent.DraftReady("run2", "Maybe?", 0.2, "m"))
        assertEquals(AutonomyPhase.ESCALATED, m.state.phase)
        assertEquals("Low confidence", m.state.detail)
    }

    @Test fun userTouchPausesImmediatelyAndCancelsTheRun() {
        armed()
        m.dispatch(inbound("Hi"))
        val fx = m.dispatch(AutonomyEvent.UserTouched)
        assertEquals(AutonomyPhase.PAUSED, m.state.phase)
        assertEquals(Effect.CancelRun("run1"), fx.filterIsInstance<Effect.CancelRun>().single())
        assertTrue(m.dispatch(AutonomyEvent.DraftReady("run1", "late", 0.9, "m")).isEmpty(), "stale drafts are ignored")
        // A user pause survives focus churn in the same conversation; only Resume re-arms.
        m.dispatch(AutonomyEvent.EnvironmentChanged(env()))
        assertEquals(AutonomyPhase.PAUSED, m.state.phase)
        m.dispatch(AutonomyEvent.Resume)
        assertEquals(AutonomyPhase.OBSERVING, m.state.phase)
    }

    @Test fun sensitiveFieldBlurAndDisallowedAppPauseThenAutoResume() {
        armed()
        m.dispatch(AutonomyEvent.EnvironmentChanged(env(f = field(sensitive = true))))
        assertEquals(AutonomyPhase.PAUSED, m.state.phase)
        assertEquals("Off in secure fields", m.state.detail)
        m.dispatch(AutonomyEvent.EnvironmentChanged(env(f = null)))
        assertEquals("Waiting for a text field", m.state.detail)
        m.dispatch(AutonomyEvent.EnvironmentChanged(env(allowed = false)))
        assertEquals("Not enabled for this app", m.state.detail)
        m.dispatch(AutonomyEvent.EnvironmentChanged(env()))
        assertEquals(AutonomyPhase.OBSERVING, m.state.phase)
    }

    @Test fun agentAppScopeIsEnforced() {
        m.dispatch(AutonomyEvent.Consent(true))
        m.dispatch(AutonomyEvent.EnvironmentChanged(env()))
        m.dispatch(AutonomyEvent.Toggle(true, AutonomyLevel.AUTO, agent(scope = listOf("com.facebook.orca"))))
        assertEquals(AutonomyPhase.PAUSED, m.state.phase)
    }

    @Test fun burstWhilePlanningIsCoalescedIntoOneReplan() {
        armed(level = AutonomyLevel.DRAFT)
        m.dispatch(inbound("Hi"))
        assertTrue(m.dispatch(inbound("Are you there?")).none { it is Effect.RequestDraft })
        m.dispatch(AutonomyEvent.DraftReady("run1", "Hello!", 0.9, "m"))
        m.dispatch(AutonomyEvent.Typed)
        val fx = m.dispatch(AutonomyEvent.DraftResolved)
        assertEquals("run2", fx.filterIsInstance<Effect.RequestDraft>().single().runId)
    }

    @Test fun rateLimitPausesForSafety() {
        armed()
        repeat(6) { i ->
            m.dispatch(inbound("q$i"))
            m.dispatch(AutonomyEvent.DraftReady("run${i + 1}", "a$i", 0.9, "m"))
            m.dispatch(AutonomyEvent.Typed)
            m.dispatch(AutonomyEvent.SendResult(true))
            now += 4_000 // slower than ping-pong's 3 s threshold
            m.dispatch(AutonomyEvent.CooldownElapsed)
        }
        val fx = m.dispatch(inbound("one more"))
        assertEquals(AutonomyPhase.PAUSED, m.state.phase)
        assertEquals("Paused: reply limit reached", m.state.detail)
        assertEquals("rate_limited", fx.filterIsInstance<Effect.Audit>().single().action)
    }

    @Test fun pingPongPauses() {
        armed()
        repeat(3) { i ->
            m.dispatch(inbound("q$i"))
            m.dispatch(AutonomyEvent.DraftReady("run${i + 1}", "a$i", 0.9, "m"))
            m.dispatch(AutonomyEvent.Typed)
            m.dispatch(AutonomyEvent.SendResult(true))
            m.dispatch(AutonomyEvent.CooldownElapsed)
            now += 1_000 // the other side answers in 1 s
        }
        m.dispatch(inbound("q3"))
        assertEquals(AutonomyPhase.PAUSED, m.state.phase)
        assertTrue(m.state.detail!!.contains("back-and-forth"))
    }

    @Test fun quietHoursSuppressReplies() {
        val quiet = AutonomyMachine(AutonomyConfig(quietHours = QuietHours(22 * 60, 7 * 60)), clock = { now }, newRunId = { "r" })
        quiet.dispatch(AutonomyEvent.Consent(true))
        quiet.dispatch(AutonomyEvent.EnvironmentChanged(env()))
        quiet.dispatch(AutonomyEvent.Toggle(true, AutonomyLevel.AUTO, agent()))
        assertTrue(quiet.dispatch(inbound("hi", minute = 23 * 60)).none { it is Effect.RequestDraft })
        assertEquals("Quiet hours", quiet.state.detail)
    }

    @Test fun ownMessagesNeverTriggerReplies() {
        armed()
        assertTrue(m.dispatch(AutonomyEvent.Inbound(ChatMessage("x", true, "Me", "hi", now), 600)).isEmpty())
    }

    @Test fun toggleOffCancelsEverything() {
        armed()
        m.dispatch(inbound("Hi"))
        val fx = m.dispatch(AutonomyEvent.Toggle(false, AutonomyLevel.OFF, agent()))
        assertEquals(AutonomyPhase.OFF, m.state.phase)
        assertTrue(fx.any { it is Effect.CancelRun })
    }

    @Test fun phaseEffectIsEmittedFirstAndOnlyOnChange() {
        armed()
        val fx = m.dispatch(inbound("Hi"))
        assertEquals(Effect.Phase(AutonomyPhase.PLANNING, null), fx.first())
        assertTrue(m.dispatch(AutonomyEvent.Resume).isEmpty(), "no-op events emit nothing")
    }
}
