package com.agentkeyboard.core.inference

import com.agentkeyboard.core.inference.InferenceScheduler.Effect
import com.agentkeyboard.core.inference.InferenceScheduler.Item
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InferenceSchedulerTest {
    private val s = InferenceScheduler<String>(maxQueued = 4)
    private fun item(key: String, p: Priority) = Item(key, p, key)
    private fun start(key: String, p: Priority) = Effect.Start(item(key, p))
    private fun cancel(key: String, p: Priority, reason: String, requeued: Boolean = false) = Effect.Cancel(item(key, p), reason, requeued)

    @Test fun firstRequestStartsImmediately() {
        assertEquals(listOf(start("a", Priority.P1)), s.submit(item("a", Priority.P1)))
        assertEquals("a", s.active?.key)
    }

    @Test fun latestWinsWithinInteractivePriorities() {
        s.submit(item("a", Priority.P1))
        assertEquals(listOf(cancel("a", Priority.P1, InferenceScheduler.REPLACED), start("b", Priority.P1)), s.submit(item("b", Priority.P1)))
        // A queued P2 draft is replaced by a newer one, without touching the running P1.
        s.submit(item("d1", Priority.P2))
        assertEquals(listOf(cancel("d1", Priority.P2, InferenceScheduler.REPLACED)), s.submit(item("d2", Priority.P2)))
        assertEquals(listOf("d2"), s.queued.map { it.key })
    }

    @Test fun higherPriorityPreemptsAndRequeuesBackgroundWork() {
        s.submit(item("bg", Priority.P3))
        val fx = s.submit(item("card", Priority.P1))
        assertEquals(listOf(cancel("bg", Priority.P3, InferenceScheduler.PREEMPTED, requeued = true), start("card", Priority.P1)), fx)
        assertEquals(listOf("bg"), s.queued.map { it.key })
        // The engine acknowledges the cancel of "bg" late: it is not active, so nothing happens.
        assertEquals(emptyList(), s.finished("bg"))
        assertEquals(listOf(start("bg", Priority.P3)), s.finished("card"))
    }

    @Test fun dictationInterruptsAVisibleCardWithoutRequeue() {
        s.submit(item("card", Priority.P1))
        val fx = s.submit(item("voice", Priority.P0))
        assertEquals(listOf(cancel("card", Priority.P1, InferenceScheduler.INTERRUPTED), start("voice", Priority.P0)), fx)
        assertTrue(s.queued.isEmpty())
    }

    @Test fun lowerPriorityWaitsInPriorityOrderAndBackgroundIsDeduplicated() {
        s.submit(item("card", Priority.P1))
        s.submit(item("idx1", Priority.P4))
        s.submit(item("pre", Priority.P3))
        s.submit(item("draft", Priority.P2))
        assertEquals(emptyList(), s.submit(item("idx1", Priority.P4)))
        assertEquals(listOf("draft", "pre", "idx1"), s.queued.map { it.key })
        assertEquals(listOf(start("draft", Priority.P2)), s.finished("card"))
    }

    @Test fun overflowDropsTheLeastUrgentOldestItem() {
        s.submit(item("card", Priority.P1))
        s.submit(item("p3a", Priority.P3))
        s.submit(item("p4a", Priority.P4))
        s.submit(item("p4b", Priority.P4))
        s.submit(item("p3b", Priority.P3))
        val fx = s.submit(item("draft", Priority.P2))
        assertEquals(listOf(cancel("p4a", Priority.P4, InferenceScheduler.OVERFLOW)), fx)
        assertEquals(listOf("draft", "p3a", "p3b", "p4b"), s.queued.map { it.key })
    }

    @Test fun trimDropsOnlyBackgroundWork() {
        s.submit(item("pre", Priority.P3))
        s.submit(item("idx", Priority.P4))
        val fx = s.trim()
        assertEquals(listOf(cancel("idx", Priority.P4, InferenceScheduler.TRIMMED), cancel("pre", Priority.P3, InferenceScheduler.TRIMMED)), fx)
        assertTrue(s.idle)
        s.submit(item("card", Priority.P1))
        assertEquals(emptyList(), s.trim())
    }

    @Test fun userCancelStartsTheNextAndClearCancelsEverything() {
        s.submit(item("card", Priority.P1))
        s.submit(item("pre", Priority.P3))
        assertEquals(listOf(cancel("card", Priority.P1, "Stopped"), start("pre", Priority.P3)), s.cancel("card", "Stopped"))
        s.submit(item("idx", Priority.P4))
        assertEquals(2, s.clear("Shutting down").size)
        assertNull(s.active)
        assertTrue(s.idle)
    }
}
