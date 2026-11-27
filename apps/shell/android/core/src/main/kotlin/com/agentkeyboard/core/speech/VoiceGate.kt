package com.agentkeyboard.core.speech

/**
 * When dictation may run (SPEC §13.1, FR-KB-14, P5). Enforced natively, not only in the shell: the
 * native side holds the microphone and commits the text, so it is the gate that fails closed.
 */
object VoiceGate {
    const val SECURE_FIELD = "Voice typing is off in protected fields."

    /** Why dictation can't start in this field, or null when it can. */
    fun refuseStart(sensitive: Boolean): String? = if (sensitive) SECURE_FIELD else null

    /**
     * A field (re)started while dictating. Dictation belongs to the field it began in: it survives
     * only an app restarting input on that same field (some do after every commit, and Android
     * reports it as `restarting`), and never a field that is now protected.
     */
    fun keepOnFieldStart(restarting: Boolean, sensitive: Boolean): Boolean = restarting && !sensitive
}
