package com.agentkeyboard.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.agentkeyboard.app.companion.AgentKitPlugin
import com.getcapacitor.BridgeActivity

/** Companion app (settings, agents, models, audit). The keyboard itself never starts this Activity. */
class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(AgentKitPlugin::class.java) // must precede super.onCreate (Capacitor contract)
        super.onCreate(savedInstanceState)
        route(intent?.data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent.data)
    }

    /** Deep links from the keyboard (`app.open {route}`) are same-origin only (SPEC §17.1). */
    private fun route(uri: Uri?) {
        if (uri == null || uri.scheme != "https" || uri.host != ORIGIN_HOST) return
        bridge?.webView?.loadUrl(uri.toString())
    }

    private companion object {
        const val ORIGIN_HOST = "ak.local"
    }
}
