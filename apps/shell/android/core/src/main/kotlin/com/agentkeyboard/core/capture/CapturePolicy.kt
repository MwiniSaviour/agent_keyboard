package com.agentkeyboard.core.capture

import com.agentkeyboard.core.protocol.DeviceTier

/**
 * Live screen capture policy (ADR-0010). Platform-free so every privacy and cost rule is unit-tested;
 * `ScreenCaptureService` only feeds it facts and obeys its answers.
 *
 * Lessons taken from Cluely-style desktop copilots and applied to a keyboard:
 *  - **Always ready, rarely working.** Keep the latest frame warm so context costs ~0 ms when the user
 *    asks, but only *analyse* a frame when the screen changed and someone can use it.
 *  - **Watch a region, not the world.** Only the app area above the keyboard is analysed and kept;
 *    our own keyboard, the status bar and the navigation bar are cropped out.
 *  - **Nothing leaves the device, nothing outlives its use.** Frames are JPEG bytes in RAM with a short
 *    TTL, zeroed on eviction, purged when a secure field or blocked app appears, never written to disk.
 *    (Cluely's 2025 breach exposed cloud-stored screenshots; there is nothing here to breach.)
 *  - **Visible, not invisible.** Cluely hides its overlay from capture; we do the opposite for the user:
 *    a system notification and status chip while live, and the Lens panel shows the exact frame.
 */
object CaptureGate {
    data class Facts(
        val userEnabled: Boolean,
        val projectionActive: Boolean,
        val keyboardVisible: Boolean,
        val autonomyArmed: Boolean,
        val secureField: Boolean,
        val blockedApp: Boolean,
        val scanning: Boolean = false,
    )

    sealed interface Decision {
        /** Analyse frames. */
        data object Analyse : Decision

        /** Keep the projection, drop frames. [purge] = also wipe what is buffered. */
        data class Pause(val reason: String, val purge: Boolean) : Decision

        data object Off : Decision
    }

    fun decide(f: Facts): Decision = when {
        !f.userEnabled || !f.projectionActive -> Decision.Off
        f.secureField -> Decision.Pause(PAUSED_SECURE, purge = true)
        f.blockedApp -> Decision.Pause(PAUSED_BLOCKED, purge = true)
        // Nobody is looking at the result: the keyboard is hidden and Autopilot isn't waiting on a chat.
        !f.keyboardVisible && !f.autonomyArmed && !f.scanning -> Decision.Pause(PAUSED_IDLE, purge = false)
        else -> Decision.Analyse
    }

    const val PAUSED_SECURE = "Paused in protected fields"
    const val PAUSED_BLOCKED = "Paused in this app"
    const val PAUSED_IDLE = "Paused while the keyboard is hidden"
    const val PAUSED_SECURE_WINDOW = "This app blocks screen capture"
}

/**
 * Per-frame admission: rate limit first (cheap), then change detection (needs the signature).
 * Adaptive: while the screen keeps changing frames are admitted at most every [busyIntervalMs];
 * after [settleFrames] unchanged checks the check interval backs off to [idleIntervalMs].
 */
class FrameThrottle(
    private val busyIntervalMs: Long = 250,
    private val idleIntervalMs: Long = 1_000,
    private val settleFrames: Int = 4,
) {
    enum class Verdict { STORE, UNCHANGED, THROTTLED, BLANK }

    private var lastCheckAt = Long.MIN_VALUE / 2
    private var lastStored: FrameSignature? = null
    private var unchangedRun = 0

    /** Call first with the frame's timestamp; false → close the image without reading pixels. */
    fun shouldCheck(now: Long, force: Boolean = false): Boolean {
        val interval = if (unchangedRun >= settleFrames) idleIntervalMs else busyIntervalMs
        if (!force && now - lastCheckAt < interval) return false
        lastCheckAt = now
        return true
    }

    fun admit(signature: FrameSignature, force: Boolean = false): Verdict {
        if (signature.isBlank) return Verdict.BLANK
        if (!force && !FrameDiff.changed(lastStored, signature)) {
            unchangedRun++
            return Verdict.UNCHANGED
        }
        unchangedRun = 0
        lastStored = signature
        return Verdict.STORE
    }

    /** After a purge or a resize the next real frame must be stored even if it looks the same. */
    fun reset() {
        lastStored = null
        unchangedRun = 0
        lastCheckAt = Long.MIN_VALUE / 2
    }
}

/** Geometry of the capture surface and of the analysed region, in virtual-display pixels. */
object CaptureGeometry {
    data class Surface(val width: Int, val height: Int, val densityDpi: Int, val scale: Float)

    data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /**
     * Downscale target per tier: the compositor scales into the VirtualDisplay for free, so a smaller
     * surface saves memory and every later step. ~0.4× of a 1080×2400 phone still leaves 14–18 px
     * text, enough for on-device OCR (Part 3).
     */
    fun maxLongSide(tier: DeviceTier): Int = when (tier) {
        DeviceTier.S -> 800
        DeviceTier.M -> 960
        DeviceTier.L -> 1_200
    }

    fun surface(displayWidth: Int, displayHeight: Int, densityDpi: Int, maxLongSide: Int): Surface {
        require(displayWidth > 0 && displayHeight > 0)
        val long = maxOf(displayWidth, displayHeight)
        val scale = if (long <= maxLongSide) 1f else maxLongSide.toFloat() / long
        // Even sizes keep some hardware encoders/allocators happy; never below 2 px.
        fun even(v: Float) = maxOf(2, (v.toInt() / 2) * 2)
        return Surface(even(displayWidth * scale), even(displayHeight * scale), maxOf(1, (densityDpi * scale).toInt()), scale)
    }

    /**
     * The app area worth looking at: below the status bar and above our keyboard (or the navigation
     * bar when the keyboard is hidden). All inputs are real display pixels; the result is in surface
     * pixels. Returns null when nothing meaningful is left (e.g. a full-height keyboard).
     */
    fun appRegion(surface: Surface, statusBarPx: Int, keyboardTopPx: Int?, navBarPx: Int, displayHeight: Int): Region? {
        val top = (statusBarPx * surface.scale).toInt().coerceIn(0, surface.height)
        val bottomReal = keyboardTopPx?.takeIf { it in 1..displayHeight } ?: (displayHeight - navBarPx)
        val bottom = (bottomReal * surface.scale).toInt().coerceIn(0, surface.height)
        if (bottom - top < MIN_REGION_PX) return null
        return Region(0, top, surface.width, bottom)
    }

    private const val MIN_REGION_PX = 48
}
