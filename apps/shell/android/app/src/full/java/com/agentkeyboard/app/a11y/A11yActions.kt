package com.agentkeyboard.app.a11y

import android.view.accessibility.AccessibilityNodeInfo

/**
 * `full` (sideload) flavor only: the single accessibility action we perform, tapping the host app's
 * Send button after the user enabled Auto for that agent/app (SPEC §11.6.3 strategy 4). Disclosed in
 * the consent flow; never compiled into the Play build.
 */
object A11yActions {
    const val AVAILABLE = true

    private val SEND = Regex("""^(send|send message|envoyer|enviar|senden)$""", RegexOption.IGNORE_CASE)

    fun clickSend(): Boolean {
        val root = ScreenReaderService.instance?.appRoot() ?: return false
        val target = find(root, 0) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun find(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 40) return null
        val label = (n.contentDescription ?: n.text)?.toString()?.trim().orEmpty()
        if (n.isClickable && n.isEnabled && n.isVisibleToUser && SEND.matches(label)) return n
        for (i in 0 until n.childCount) {
            val hit = n.getChild(i)?.let { find(it, depth + 1) }
            if (hit != null) return hit
        }
        return null
    }
}
