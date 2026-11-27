package com.agentkeyboard.app.capture

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.agentkeyboard.app.AkLog
import com.agentkeyboard.core.capture.CaptureGate
import com.agentkeyboard.core.capture.FrameRing
import java.io.ByteArrayOutputStream

/**
 * Live screen capture, process-wide state (ADR-0010). Lives in the main process next to the IME, so
 * the keyboard reads frames with no IPC: [ScreenCaptureService] writes [ring] on its capture thread,
 * the IME reads it on main.
 *
 * Every decision (analyse / pause / purge / off) comes from [CaptureGate]; this object only gathers
 * the facts, applies the answer, and reports the result as `screen.state`.
 */
object ScreenCapture {
    enum class Status(val wire: String) { OFF("off"), STARTING("starting"), LIVE("live"), PAUSED("paused") }

    data class State(val status: Status, val detail: String? = null)

    /**
     * RAM only, short-lived, zeroed on eviction: the newest ~6 frames of the app region, ≤ 1.5 MB,
     * 30 s TTL (refreshed while the screen is unchanged). Nothing is ever written to disk.
     */
    val ring = FrameRing(capacity = 6, maxBytes = 1_536 * 1024, ttlMs = 30_000)

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile var state: State = State(Status.OFF)
        private set

    /** The IME's listener (main thread). One keyboard per process, so one listener. */
    var listener: ((State) -> Unit)? = null

    // Facts (CaptureGate.Facts minus the scan flag, which ScreenReaderService owns).
    @Volatile private var userEnabled = false
    @Volatile private var projectionActive = false
    @Volatile private var keyboardVisible = false
    @Volatile private var autonomyArmed = false
    @Volatile private var secureField = false
    @Volatile private var blockedApp = false
    @Volatile private var blankScreen = false

    /** Top of our keyboard in real display pixels (null while hidden): frames are cropped above it. */
    @Volatile var keyboardTopPx: Int? = null

    val wantsFrames: Boolean get() = decision() is CaptureGate.Decision.Analyse

    /** The user turned live screen on: ask Android (system dialog, every session) and start. */
    fun requestStart(context: Context) {
        if (state.status != Status.OFF) return publish()
        userEnabled = true
        set(State(Status.STARTING, "Waiting for your OK"))
        try {
            context.startActivity(
                Intent(context, ScreenCaptureConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            )
        } catch (e: RuntimeException) { // ActivityNotFound / background-start refusal
            AkLog.w("screen capture consent could not be shown: ${e::class.java.simpleName}")
            userEnabled = false
            set(State(Status.OFF, "Couldn’t ask for screen access"))
        }
    }

    fun stop(context: Context) {
        userEnabled = false
        if (projectionActive) context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
        ring.clear()
        set(State(Status.OFF))
    }

    // ───────────── from the consent activity / service ─────────────

    internal fun onConsentDenied() {
        userEnabled = false
        set(State(Status.OFF, "Screen access not allowed"))
    }

    internal fun onProjectionStarted() {
        projectionActive = true
        blankScreen = false
        reevaluate()
    }

    /** Projection ended: our stop, the system "stop sharing" chip, or another app took the screen. */
    internal fun onProjectionStopped() {
        projectionActive = false
        userEnabled = false
        ring.clear()
        set(State(Status.OFF))
    }

    /** All-black frames: a `FLAG_SECURE` window (banking, DRM). Treated like a secure field. */
    internal fun onBlankFrame(blank: Boolean) {
        if (blank == blankScreen) return
        blankScreen = blank
        if (blank) ring.clear()
        reevaluate()
    }

    // ───────────── from the IME (main thread) ─────────────

    fun updateFacts(keyboardVisible: Boolean, autonomyArmed: Boolean, secureField: Boolean, blockedApp: Boolean) {
        this.keyboardVisible = keyboardVisible
        this.autonomyArmed = autonomyArmed
        this.secureField = secureField
        this.blockedApp = blockedApp
        reevaluate() // purge now: with a static screen no new frame would arrive to trigger it
    }

    /** Small data: URL of a frame for the Lens panel (decoded with subsampling; main thread is fine). */
    fun preview(frame: FrameRing.Frame, maxSide: Int = 240): String? = try {
        val opts = BitmapFactory.Options().apply {
            var s = 1
            while (maxOf(frame.width, frame.height) / (s * 2) >= maxSide) s *= 2
            inSampleSize = s
        }
        val bmp = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, opts)
        bmp?.let {
            val out = ByteArrayOutputStream(16 * 1024)
            it.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, out)
            it.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (e: RuntimeException) {
        null
    }

    private fun decision(): CaptureGate.Decision = when {
        blankScreen && userEnabled && projectionActive -> CaptureGate.Decision.Pause(CaptureGate.PAUSED_SECURE_WINDOW, purge = true)
        else -> CaptureGate.decide(
            CaptureGate.Facts(
                userEnabled = userEnabled, projectionActive = projectionActive, keyboardVisible = keyboardVisible,
                autonomyArmed = autonomyArmed, secureField = secureField, blockedApp = blockedApp,
                scanning = com.agentkeyboard.app.a11y.ScreenReaderService.scanning,
            ),
        )
    }

    private fun reevaluate() {
        val next = when (val d = decision()) {
            CaptureGate.Decision.Analyse -> State(Status.LIVE)
            is CaptureGate.Decision.Pause -> {
                if (d.purge) ring.clear()
                State(Status.PAUSED, d.reason)
            }
            CaptureGate.Decision.Off -> if (state.status == Status.STARTING && userEnabled) state else State(Status.OFF)
        }
        set(next)
    }

    private fun set(next: State) {
        synchronized(lock) {
            if (next == state) return
            state = next
        }
        publish()
    }

    private fun publish() {
        val s = state
        main.post { listener?.invoke(s) }
    }
}
