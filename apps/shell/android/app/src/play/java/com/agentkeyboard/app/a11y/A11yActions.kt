package com.agentkeyboard.app.a11y

/**
 * `play` flavor: accessibility is read-only (ADR-0006; Play accessibility policy, enforced from
 * 2026-01-28, forbids using the API for autonomous AI-driven actions). Autonomy degrades to Draft or
 * uses IME/RemoteInput send paths instead (SPEC §11.6.3).
 */
object A11yActions {
    const val AVAILABLE = false

    fun clickSend(): Boolean = false
}
