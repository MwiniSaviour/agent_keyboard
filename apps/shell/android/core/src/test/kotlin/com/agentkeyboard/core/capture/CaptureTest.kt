package com.agentkeyboard.core.capture

import com.agentkeyboard.core.protocol.DeviceTier
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureTest {
    /** A synthetic 400×800 "chat screen": light background, dark text rows, optional extras. */
    private class Screen(val w: Int = 400, val h: Int = 800) {
        val px = IntArray(w * h) { 240 }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, v: Int) { for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = v }
        fun sig() = FrameSignature.of(0, 0, w, h) { x, y -> px[y * w + x] }
        fun copy() = Screen(w, h).also { px.copyInto(it.px) }
    }

    private fun chat() = Screen().apply { for (i in 0 until 10) rect(20, 40 + i * 60, 300, 60 + i * 60, 30) }

    // ───────── signature & diff ─────────

    @Test fun identicalFramesAreUnchanged() {
        val a = chat()
        assertEquals(0, FrameDiff.changedBlocks(a.sig(), a.copy().sig()))
        assertFalse(FrameDiff.changed(a.sig(), a.copy().sig()))
    }

    @Test fun aBlinkingCaretIsIgnoredButANewMessageIsNot() {
        val a = chat()
        val caret = a.copy().apply { rect(350, 700, 352, 740, 0) } // 2 px caret inside one block
        assertFalse(FrameDiff.changed(a.sig(), caret.sig()), "caret blink must not wake the pipeline")
        val bubble = a.copy().apply { rect(120, 650, 380, 700, 60) } // a new bubble
        assertTrue(FrameDiff.changed(a.sig(), bubble.sig()))
    }

    @Test fun secureWindowsCaptureAsBlankAndAreDetected() {
        val black = Screen().apply { rect(0, 0, w, h, 0) }
        assertTrue(black.sig().isBlank)
        val dimVideo = Screen().apply { rect(0, 0, w, h, 8); rect(0, 0, 50, 50, 12) }
        assertTrue(dimVideo.sig().isBlank)
        assertFalse(chat().sig().isBlank)
        val darkModeChat = Screen().apply { rect(0, 0, w, h, 0); for (i in 0 until 10) rect(20, 40 + i * 60, 300, 60 + i * 60, 220) }
        assertFalse(darkModeChat.sig().isBlank, "a dark theme is not a secure window")
    }

    @Test fun signatureCoversOnlyTheRequestedRegion() {
        val a = chat()
        val keyboardChanged = a.copy().apply { rect(0, 700, 400, 800, 0) } // our own keyboard area
        val region = { s: Screen -> FrameSignature.of(0, 0, 400, 680) { x, y -> s.px[y * s.w + x] } }
        assertFalse(FrameDiff.changed(region(a), region(keyboardChanged)), "our keyboard never counts as screen content")
    }

    // ───────── throttle ─────────

    @Test fun throttleRateLimitsThenBacksOffWhileStatic() {
        val t = FrameThrottle(busyIntervalMs = 250, idleIntervalMs = 1_000, settleFrames = 2)
        assertTrue(t.shouldCheck(0))
        assertFalse(t.shouldCheck(100))
        assertTrue(t.shouldCheck(260))
        val s = chat().sig()
        assertEquals(FrameThrottle.Verdict.STORE, t.admit(s))
        assertEquals(FrameThrottle.Verdict.UNCHANGED, t.admit(s))
        assertEquals(FrameThrottle.Verdict.UNCHANGED, t.admit(s))
        assertFalse(t.shouldCheck(600), "settled: back off to the idle interval")
        assertTrue(t.shouldCheck(1_300))
        assertTrue(t.shouldCheck(1_301, force = true), "a scan can force a fresh frame")
        assertEquals(FrameThrottle.Verdict.STORE, t.admit(s, force = true))
        assertEquals(FrameThrottle.Verdict.BLANK, t.admit(Screen().apply { rect(0, 0, w, h, 0) }.sig()))
        t.reset()
        assertEquals(FrameThrottle.Verdict.STORE, t.admit(s), "after a purge the same content is stored again")
    }

    // ───────── gate ─────────

    @Test fun gatePausesAndPurgesInSecureFieldsAndBlockedApps() {
        val base = CaptureGate.Facts(userEnabled = true, projectionActive = true, keyboardVisible = true, autonomyArmed = false, secureField = false, blockedApp = false)
        assertEquals(CaptureGate.Decision.Analyse, CaptureGate.decide(base))
        assertEquals(CaptureGate.Decision.Pause(CaptureGate.PAUSED_SECURE, purge = true), CaptureGate.decide(base.copy(secureField = true)))
        assertEquals(CaptureGate.Decision.Pause(CaptureGate.PAUSED_BLOCKED, purge = true), CaptureGate.decide(base.copy(blockedApp = true)))
        assertEquals(CaptureGate.Decision.Pause(CaptureGate.PAUSED_IDLE, purge = false), CaptureGate.decide(base.copy(keyboardVisible = false)))
        assertEquals(CaptureGate.Decision.Analyse, CaptureGate.decide(base.copy(keyboardVisible = false, autonomyArmed = true)))
        assertEquals(CaptureGate.Decision.Off, CaptureGate.decide(base.copy(projectionActive = false)))
        assertEquals(CaptureGate.Decision.Off, CaptureGate.decide(base.copy(userEnabled = false, secureField = true)))
        // Secure beats everything, including an armed Autopilot or a running scan.
        assertEquals(CaptureGate.PAUSED_SECURE, (CaptureGate.decide(base.copy(secureField = true, autonomyArmed = true, scanning = true)) as CaptureGate.Decision.Pause).reason)
    }

    // ───────── geometry ─────────

    @Test fun surfaceIsDownscaledPerTierAndRegionExcludesBarsAndKeyboard() {
        val s = CaptureGeometry.surface(1080, 2400, 420, CaptureGeometry.maxLongSide(DeviceTier.M))
        assertEquals(960, s.height)
        assertEquals(432, s.width)
        assertEquals(168, s.densityDpi)
        val r = assertNotNull(CaptureGeometry.appRegion(s, statusBarPx = 100, keyboardTopPx = 1500, navBarPx = 60, displayHeight = 2400))
        assertEquals(40, r.top)
        assertEquals(600, r.bottom)
        val noKb = assertNotNull(CaptureGeometry.appRegion(s, 100, null, 60, 2400))
        assertEquals(936, noKb.bottom)
        assertNull(CaptureGeometry.appRegion(s, 100, 140, 60, 2400), "a full-screen keyboard leaves nothing to read")
        val small = CaptureGeometry.surface(720, 1280, 320, 1_600)
        assertEquals(720, small.width, "never upscaled")
        assertTrue(CaptureGeometry.maxLongSide(DeviceTier.S) < CaptureGeometry.maxLongSide(DeviceTier.L))
    }

    // ───────── ring ─────────

    @Test fun ringIsBoundedByCountBytesAndAgeAndZeroesEvictedFrames() {
        var now = 0L
        val released = mutableListOf<ByteArray>()
        val ring = FrameRing(capacity = 3, maxBytes = 1_000, ttlMs = 30_000, clock = { now }, release = { f -> f.jpeg.fill(0); released += f.jpeg })
        val first = ring.add(ByteArray(100) { 7 }, 10, 10)
        repeat(3) { now += 10; ring.add(ByteArray(100) { 7 }, 10, 10) }
        assertEquals(3, ring.size())
        assertNull(ring.get(first.handle))
        assertTrue(released.single().all { it == 0.toByte() }, "evicted pixels are zeroed")
        ring.add(ByteArray(900), 10, 10)
        assertTrue(ring.bytes() <= 1_000 || ring.size() == 1)
        now += 31_000
        assertNull(ring.latest())
        assertEquals(0, ring.size())
    }

    @Test fun touchingKeepsTheLatestFrameFreshWithoutCopying() {
        var now = 0L
        val ring = FrameRing(ttlMs = 30_000, clock = { now })
        val f = ring.add(ByteArray(10), 1, 1)
        now = 25_000
        ring.touchLatest()
        now = 50_000
        assertEquals(f.handle, ring.latest()?.handle, "unchanged screen: still valid")
        assertNull(ring.latest(maxAgeMs = 1_000))
        ring.clear()
        assertNull(ring.latest())
    }
}
