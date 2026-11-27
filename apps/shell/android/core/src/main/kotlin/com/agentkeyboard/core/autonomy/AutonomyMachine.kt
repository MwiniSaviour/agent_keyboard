package com.agentkeyboard.core.autonomy

import com.agentkeyboard.core.protocol.AgentDefinition
import com.agentkeyboard.core.protocol.AutonomyLevel
import com.agentkeyboard.core.protocol.AutonomyPhase
import com.agentkeyboard.core.protocol.ChatMessage
import com.agentkeyboard.core.protocol.FieldContext
import com.agentkeyboard.core.protocol.ImeActionKind

/**
 * Autonomy state machine (SPEC §11.6.1, ADR-0006) as a pure reducer: `(state, event) → effects`.
 * The IME interprets effects (type text, send, emit `autonomy.phase`, write audit log). Keeping it
 * pure means every guardrail is unit-tested without Android, and replaying an audit trail reproduces
 * the exact decisions.
 *
 * Invariants (tested):
 *  - Never acts in a sensitive field, a blocked/non-allowlisted app, or without consent.
 *  - The effective level never exceeds the agent's ceiling.
 *  - Any user touch pauses within the same event (FR-AU-03).
 *  - Nothing is sent without consuming reply budget (FR-AU-05) and passing the outbound check.
 *  - Every send and every escalation produces an audit effect (FR-AU-06).
 */
enum class SendStrategy { IME_ACTION_SEND, ENTER_IS_SEND, REMOTE_INPUT, A11Y_CLICK, NONE }

object SendPlanner {
    /** SPEC §11.6.3, in priority order. A11y clicks exist only in the `full` flavor. */
    fun choose(
        field: FieldContext?,
        enterIsSendConfirmed: Boolean,
        remoteInputAvailable: Boolean,
        a11yActionsAvailable: Boolean,
    ): SendStrategy = when {
        field?.imeAction == ImeActionKind.SEND -> SendStrategy.IME_ACTION_SEND
        field != null && enterIsSendConfirmed -> SendStrategy.ENTER_IS_SEND
        remoteInputAvailable -> SendStrategy.REMOTE_INPUT
        a11yActionsAvailable -> SendStrategy.A11Y_CLICK
        else -> SendStrategy.NONE
    }
}

data class AutonomyConfig(
    val confidenceThreshold: Double = 0.7,
    val cooldownMs: Long = 4_000,
    val quietHours: QuietHours? = null,
)

/** Per-conversation environment, resolved by the host from settings + `autonomy_policies` (SPEC §16). */
data class Environment(
    val field: FieldContext?,
    val conversation: String?,
    val appAllowed: Boolean,
    val contactAllowed: Boolean,
    val sendStrategy: SendStrategy,
)

data class AutonomyState(
    val phase: AutonomyPhase = AutonomyPhase.OFF,
    val requested: AutonomyLevel = AutonomyLevel.OFF,
    val agent: AgentDefinition? = null,
    val consent: Boolean = false,
    val env: Environment? = null,
    val activeRunId: String? = null,
    val draft: String? = null,
    val sendAfterTyping: Boolean = false,
    /** An inbound arrived while busy; re-plan once the current cycle ends (burst coalescing). */
    val replanPending: Boolean = false,
    val detail: String? = null,
    /** Why we are PAUSED: preconditions auto-resume on the next environment change; user/safety pauses need Resume. */
    val pause: PauseKind? = null,
) {
    val effectiveLevel: AutonomyLevel
        get() = agent?.let { requested.cappedBy(it.autonomyCeiling) } ?: AutonomyLevel.OFF
}

enum class PauseKind { PRECONDITION, USER, SAFETY }

sealed interface AutonomyEvent {
    data class Toggle(val enabled: Boolean, val level: AutonomyLevel, val agent: AgentDefinition?) : AutonomyEvent
    data class Consent(val granted: Boolean) : AutonomyEvent
    data class EnvironmentChanged(val env: Environment?) : AutonomyEvent
    data class Inbound(val message: ChatMessage, val minuteOfDay: Int) : AutonomyEvent
    data class DraftReady(val runId: String, val text: String, val confidence: Double, val model: String) : AutonomyEvent
    data class DraftFailed(val runId: String, val reason: String) : AutonomyEvent
    data object Typed : AutonomyEvent
    data class SendResult(val ok: Boolean) : AutonomyEvent
    data object CooldownElapsed : AutonomyEvent
    data object UserTouched : AutonomyEvent
    /** In AWAITING_USER: the user sent or discarded the typed draft. */
    data object DraftResolved : AutonomyEvent
    data object Resume : AutonomyEvent
    data object AckEscalation : AutonomyEvent
}

sealed interface Effect {
    data class Phase(val phase: AutonomyPhase, val detail: String?) : Effect
    data class RequestDraft(val runId: String, val agentId: String, val conversation: String) : Effect
    data class CancelRun(val runId: String) : Effect
    /** Replace the composing field content with [text]; the user can still edit it. */
    data class TypeDraft(val text: String) : Effect
    data class Send(val strategy: SendStrategy) : Effect
    data class Suggest(val text: String) : Effect
    data class ScheduleCooldown(val ms: Long) : Effect
    data class NeedsYou(val reason: String) : Effect
    data class Audit(val action: String, val agentId: String?, val app: String?, val conversation: String?, val sent: String?, val reason: String?, val model: String?) : Effect
}

class AutonomyMachine(
    private val config: AutonomyConfig = AutonomyConfig(),
    private val budget: ReplyBudget = ReplyBudget(),
    private val pingPong: PingPongDetector = PingPongDetector(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val newRunId: () -> String,
) {
    var state = AutonomyState()
        private set

    fun dispatch(event: AutonomyEvent): List<Effect> {
        val fx = ArrayList<Effect>(4)
        val before = state.phase to state.detail
        reduce(event, fx)
        if ((state.phase to state.detail) != before) fx.add(0, Effect.Phase(state.phase, state.detail))
        return fx
    }

    private fun go(phase: AutonomyPhase, detail: String? = null, pause: PauseKind? = null) {
        state = state.copy(phase = phase, detail = detail, pause = if (phase == AutonomyPhase.PAUSED) pause else null)
    }

    private fun reduce(e: AutonomyEvent, fx: MutableList<Effect>) {
        when (e) {
            is AutonomyEvent.Toggle -> {
                cancelActive(fx)
                state = state.copy(requested = if (e.enabled) e.level else AutonomyLevel.OFF, agent = e.agent)
                if (!e.enabled || state.effectiveLevel == AutonomyLevel.OFF) {
                    pingPong.reset(); go(AutonomyPhase.OFF); return
                }
                go(AutonomyPhase.ARMING)
                arm()
            }
            is AutonomyEvent.Consent -> {
                state = state.copy(consent = e.granted)
                if (!e.granted && state.phase != AutonomyPhase.OFF) { cancelActive(fx); go(AutonomyPhase.OFF, "Consent withdrawn") }
            }
            is AutonomyEvent.EnvironmentChanged -> {
                val convChanged = e.env?.conversation != state.env?.conversation
                state = state.copy(env = e.env)
                if (state.phase == AutonomyPhase.OFF) return
                val blocked = blockedReason()
                when {
                    // A blur, a secure field or a non-allowlisted app pauses at once (ADR-0006: keyboard must be open).
                    blocked != null -> { cancelActive(fx); go(AutonomyPhase.PAUSED, blocked, PauseKind.PRECONDITION) }
                    convChanged -> { cancelActive(fx); pingPong.reset(); arm() }
                    state.phase == AutonomyPhase.ARMING ||
                        (state.phase == AutonomyPhase.PAUSED && state.pause == PauseKind.PRECONDITION) -> arm()
                }
            }
            is AutonomyEvent.Inbound -> onInbound(e, fx)
            is AutonomyEvent.DraftReady -> onDraft(e, fx)
            is AutonomyEvent.DraftFailed -> {
                if (e.runId != state.activeRunId) return
                state = state.copy(activeRunId = null)
                escalate("Couldn't draft a reply: ${e.reason}", fx)
            }
            AutonomyEvent.Typed -> {
                if (state.phase != AutonomyPhase.TYPING) return
                if (state.sendAfterTyping) {
                    go(AutonomyPhase.SENDING); fx += Effect.Send(state.env!!.sendStrategy)
                } else {
                    go(AutonomyPhase.AWAITING_USER, "Review and send")
                }
            }
            is AutonomyEvent.SendResult -> {
                if (state.phase != AutonomyPhase.SENDING) return
                val env = state.env
                if (e.ok) {
                    pingPong.onSent(clock())
                    fx += audit("sent", sent = state.draft)
                    state = state.copy(draft = null)
                    go(AutonomyPhase.COOLDOWN); fx += Effect.ScheduleCooldown(config.cooldownMs)
                } else {
                    fx += audit("send_failed", reason = env?.sendStrategy?.name)
                    go(AutonomyPhase.AWAITING_USER, "Couldn't send. Tap send to finish.")
                }
            }
            AutonomyEvent.CooldownElapsed -> if (state.phase == AutonomyPhase.COOLDOWN) observeOrReplan(fx)
            AutonomyEvent.UserTouched -> {
                if (state.phase == AutonomyPhase.OFF || state.phase == AutonomyPhase.PAUSED) return
                cancelActive(fx)
                go(AutonomyPhase.PAUSED, "Paused. You're typing.", PauseKind.USER)
            }
            AutonomyEvent.DraftResolved -> if (state.phase == AutonomyPhase.AWAITING_USER) {
                state = state.copy(draft = null); observeOrReplan(fx)
            }
            AutonomyEvent.Resume -> if (state.phase == AutonomyPhase.PAUSED) arm()
            AutonomyEvent.AckEscalation -> if (state.phase == AutonomyPhase.ESCALATED) arm()
        }
    }

    /** ARMING → OBSERVING when every precondition of FR-AU-02 holds; otherwise PAUSED with the reason. */
    private fun arm() {
        val reason = blockedReason()
        if (reason == null) go(AutonomyPhase.OBSERVING) else go(AutonomyPhase.PAUSED, reason, PauseKind.PRECONDITION)
    }

    private fun blockedReason(): String? {
        val env = state.env
        val agent = state.agent
        return when {
            !state.consent -> "Turn on Autopilot from the keyboard to give consent"
            agent == null -> "No agent selected"
            env?.field == null -> "Waiting for a text field"
            env.field.sensitive -> "Off in secure fields"
            !env.appAllowed || !agent.appliesTo(env.field.packageName) -> "Not enabled for this app"
            else -> null
        }
    }

    private fun onInbound(e: AutonomyEvent.Inbound, fx: MutableList<Effect>) {
        if (e.message.fromMe) return
        val env = state.env ?: return
        val conversation = env.conversation ?: return
        val phase = state.phase
        if (phase == AutonomyPhase.OFF || phase == AutonomyPhase.PAUSED || phase == AutonomyPhase.ESCALATED) return
        budget.onInbound(conversation)
        if (pingPong.onInbound(clock())) {
            cancelActive(fx)
            fx += audit("paused_ping_pong")
            go(AutonomyPhase.PAUSED, "Paused: fast back-and-forth. Possibly another bot.", PauseKind.SAFETY); return
        }
        if (phase != AutonomyPhase.OBSERVING) {
            state = state.copy(replanPending = true); return
        }
        plan(e.message.text, e.minuteOfDay, fx)
    }

    private fun plan(latestInbound: String?, minuteOfDay: Int?, fx: MutableList<Effect>) {
        val env = state.env ?: return
        val conversation = env.conversation ?: return
        val agent = state.agent ?: return
        state = state.copy(replanPending = false)
        if (!env.contactAllowed) return // stays OBSERVING: a denied contact is not an error
        if (minuteOfDay != null && config.quietHours?.contains(minuteOfDay) == true) {
            go(AutonomyPhase.OBSERVING, "Quiet hours"); return
        }
        if (latestInbound != null) {
            val v = Policy.checkInbound(latestInbound)
            if (v is Verdict.Blocked) { escalate(v.reason, fx); return }
        }
        if (!budget.canReply(conversation, clock())) {
            fx += audit("rate_limited")
            go(AutonomyPhase.PAUSED, "Paused: reply limit reached", PauseKind.SAFETY); return
        }
        val runId = newRunId()
        state = state.copy(activeRunId = runId)
        go(AutonomyPhase.PLANNING)
        fx += Effect.RequestDraft(runId, agent.id, conversation)
    }

    private fun onDraft(e: AutonomyEvent.DraftReady, fx: MutableList<Effect>) {
        if (e.runId != state.activeRunId || state.phase != AutonomyPhase.PLANNING) return
        state = state.copy(activeRunId = null)
        go(AutonomyPhase.REVIEW)
        val out = Policy.checkOutbound(e.text)
        if (out is Verdict.Blocked) { escalate(out.reason, fx); return }
        if (e.confidence < config.confidenceThreshold) { escalate("Low confidence", fx); return }
        val env = state.env ?: return
        when (state.effectiveLevel) {
            AutonomyLevel.OFF -> go(AutonomyPhase.OFF)
            AutonomyLevel.SUGGEST -> { fx += Effect.Suggest(e.text); observeOrReplan(fx) }
            AutonomyLevel.DRAFT -> typeDraft(e.text, send = false, fx)
            AutonomyLevel.AUTO -> {
                val canSend = env.sendStrategy != SendStrategy.NONE && budget.consume(env.conversation!!, clock())
                if (canSend) fx += audit("auto_reply", sent = e.text, model = e.model)
                typeDraft(e.text, send = canSend, fx)
            }
        }
    }

    private fun typeDraft(text: String, send: Boolean, fx: MutableList<Effect>) {
        state = state.copy(draft = text, sendAfterTyping = send)
        go(AutonomyPhase.TYPING)
        fx += Effect.TypeDraft(text)
    }

    private fun observeOrReplan(fx: MutableList<Effect>) {
        go(AutonomyPhase.OBSERVING)
        if (state.replanPending) plan(null, null, fx)
    }

    private fun escalate(reason: String, fx: MutableList<Effect>) {
        cancelActive(fx)
        fx += audit("escalated", reason = reason)
        fx += Effect.NeedsYou(reason)
        go(AutonomyPhase.ESCALATED, reason)
    }

    private fun cancelActive(fx: MutableList<Effect>) {
        state.activeRunId?.let { fx += Effect.CancelRun(it) }
        state = state.copy(activeRunId = null, replanPending = false, sendAfterTyping = false)
    }

    private fun audit(action: String, sent: String? = null, reason: String? = null, model: String? = null) = Effect.Audit(
        action = action, agentId = state.agent?.id, app = state.env?.field?.packageName,
        conversation = state.env?.conversation, sent = sent, reason = reason, model = model,
    )
}
