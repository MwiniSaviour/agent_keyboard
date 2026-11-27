package com.agentkeyboard.core.inference

/**
 * Inference priorities (SPEC §11.3). Lower ordinal = more urgent.
 * - P0 voice-final refine: the user is waiting with the mic panel open.
 * - P1 interactive: a result card the user asked for.
 * - P2 autonomy: a draft for an armed conversation.
 * - P3 insight precompute, P4 background (indexing, memory extraction).
 */
enum class Priority { P0, P1, P2, P3, P4;

    /** Work nobody is watching: re-run later if preempted, and the first to go under memory pressure. */
    val background: Boolean get() = this >= P3
}

/**
 * One active generation at a time (one resident model, ADR-0004); everything else waits here. Pure
 * state machine — it decides, [com.agentkeyboard.core.agent.AgentRuntime] executes the effects — so
 * the rules are unit-tested without threads:
 *
 * 1. **Latest wins within P0–P2.** A newer request of the same priority replaces the older one (queued
 *    or running): the user moved on, or a newer inbound message superseded the draft.
 * 2. **Higher preempts lower** via cancel + requeue. Preempted P2–P4 work is re-queued at the head of
 *    its class and restarted later (its streamed tokens are never shown, so restarting is invisible).
 *    A preempted P1 card is cancelled with a reason instead: its tokens are already on screen.
 * 3. **Lower waits.** P3/P4 are FIFO and de-duplicated by key; the queue is bounded, and overflow drops
 *    the least urgent, oldest item.
 */
class InferenceScheduler<T : Any>(private val maxQueued: Int = 8) {

    data class Item<T>(val key: String, val priority: Priority, val payload: T)

    sealed interface Effect<T> {
        data class Start<T>(val item: Item<T>) : Effect<T>
        /** [requeued] = it will start again later; the client must not be told it ended. */
        data class Cancel<T>(val item: Item<T>, val reason: String, val requeued: Boolean) : Effect<T>
    }

    var active: Item<T>? = null
        private set
    private val queue = ArrayList<Item<T>>()

    val queued: List<Item<T>> get() = queue.toList()
    val idle: Boolean get() = active == null && queue.isEmpty()

    fun submit(item: Item<T>): List<Effect<T>> {
        val out = ArrayList<Effect<T>>()
        val supersedes = item.priority <= Priority.P2
        if (supersedes) {
            queue.removeAll { q -> (q.priority == item.priority).also { if (it) out += Effect.Cancel(q, REPLACED, requeued = false) } }
        } else if (queue.any { it.key == item.key } || active?.key == item.key) {
            return out // background duplicate
        }
        val running = active
        when {
            running == null -> start(item, out)
            supersedes && running.priority == item.priority -> {
                out += Effect.Cancel(running, REPLACED, requeued = false)
                start(item, out)
            }
            item.priority < running.priority -> {
                if (running.priority > Priority.P1) {
                    out += Effect.Cancel(running, PREEMPTED, requeued = true)
                    enqueue(running, head = true, out)
                } else {
                    out += Effect.Cancel(running, INTERRUPTED, requeued = false)
                }
                start(item, out)
            }
            else -> enqueue(item, head = false, out)
        }
        return out
    }

    /** The active item ended (done, failed or acknowledged cancel). Starts the next one. */
    fun finished(key: String): List<Effect<T>> {
        if (active?.key != key) return emptyList()
        active = null
        return next()
    }

    /** User cancel of a queued or running item. */
    fun cancel(key: String, reason: String): List<Effect<T>> {
        val out = ArrayList<Effect<T>>()
        queue.removeAll { q -> (q.key == key).also { if (it) out += Effect.Cancel(q, reason, requeued = false) } }
        val running = active
        if (running?.key == key) {
            out += Effect.Cancel(running, reason, requeued = false)
            active = null
            out += next()
        }
        return out
    }

    /** Memory pressure (SPEC §12.2): drop all background work, running or queued. */
    fun trim(): List<Effect<T>> {
        val out = ArrayList<Effect<T>>()
        queue.removeAll { q -> q.priority.background.also { if (it) out += Effect.Cancel(q, TRIMMED, requeued = false) } }
        val running = active
        if (running != null && running.priority.background) {
            out += Effect.Cancel(running, TRIMMED, requeued = false)
            active = null
            out += next()
        }
        return out
    }

    /** Cancels everything (service shutdown). */
    fun clear(reason: String): List<Effect<T>> {
        val out = ArrayList<Effect<T>>()
        active?.let { out += Effect.Cancel(it, reason, requeued = false) }
        queue.forEach { out += Effect.Cancel(it, reason, requeued = false) }
        active = null
        queue.clear()
        return out
    }

    private fun next(): List<Effect<T>> {
        if (queue.isEmpty()) return emptyList()
        val item = queue.removeAt(0)
        active = item
        return listOf(Effect.Start(item))
    }

    private fun start(item: Item<T>, out: MutableList<Effect<T>>) {
        active = item
        out += Effect.Start(item)
    }

    private fun enqueue(item: Item<T>, head: Boolean, out: MutableList<Effect<T>>) {
        // Stable insertion by priority: at the head of its class, or behind its equals.
        val idx = if (head) queue.indexOfFirst { it.priority >= item.priority } else queue.indexOfFirst { it.priority > item.priority }
        queue.add(if (idx < 0) queue.size else idx, item)
        while (queue.size > maxQueued) {
            val worst = queue.withIndex().maxWith(compareBy<IndexedValue<Item<T>>> { it.value.priority }.thenByDescending { it.index })
            queue.removeAt(worst.index)
            out += Effect.Cancel(worst.value, OVERFLOW, requeued = false)
        }
    }

    companion object {
        const val REPLACED = "Replaced by a newer request"
        const val PREEMPTED = "Paused for a more urgent request"
        const val INTERRUPTED = "Interrupted by dictation. Tap Retry."
        const val TRIMMED = "Stopped to free memory"
        const val OVERFLOW = "Too many requests at once"
    }
}
