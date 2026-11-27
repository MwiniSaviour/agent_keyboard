package com.agentkeyboard.app.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.agentkeyboard.app.AkLog
import java.io.IOException
import java.io.InputStream

/**
 * The single pre-warmed WebView that renders `/ime` (ADR-0002, SPEC §9).
 *
 *  - Content is served from APK assets by [WebViewAssetLoader] at `https://ak.local` (same origin as the
 *    Capacitor companion), so there is no file:// access and no network.
 *  - Transport: `WebViewCompat.addWebMessageListener(webView, "akNative", {"https://ak.local"})` injects
 *    `window.akNative`; native replies through the [JavaScriptReplyProxy] captured from the first
 *    message (the shell always sends `hello` first). Messages from any other origin or a sub-frame are
 *    ignored.
 *  - Created once, never destroyed on hide; recreated only after a renderer crash.
 */
class ImeWebView(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onShellMessage(raw: String)
        fun onRendererGone(crashed: Boolean)
    }

    var view: WebView? = null
        private set
    private var reply: JavaScriptReplyProxy? = null

    /** False when the system WebView is too old for the listener API: the IME stays on the fallback keyboard. */
    val supported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    @SuppressLint("SetJavaScriptEnabled")
    fun create(): WebView? {
        if (!supported) {
            AkLog.w("WebView lacks WEB_MESSAGE_LISTENER; using fallback keyboard")
            return null
        }
        val loader = WebViewAssetLoader.Builder()
            .setDomain(ORIGIN_HOST)
            .addPathHandler("/", StaticExportHandler(context))
            .build()
        val wv = WebView(context)
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.isHapticFeedbackEnabled = false // haptics come from feedback.key → native, honouring settings
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true // shell settings + consent mirror live in localStorage
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE // assets are local; HTTP cache would only cost disk
            offscreenPreRaster = true // keep tiles ready while hidden → first show is instant
            textZoom = 100 // key labels are sized in CSS; system font scale is applied by the shell
            safeBrowsingEnabled = false // local-only content, avoid network
        }
        // Keep the renderer important while visible; let the OS reclaim it first when hidden.
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.host == ORIGIN_HOST) loader.shouldInterceptRequest(request.url) else BLOCKED

            /** No navigation away from the shell, ever (SPEC §9 / §17.1). */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != ORIGIN_HOST || !request.isForMainFrame

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val crashed = detail.didCrash()
                AkLog.w("IME renderer gone (crashed=$crashed)")
                destroy()
                listener.onRendererGone(crashed)
                return true // we handled it: the IME process survives
            }
        }
        WebViewCompat.addWebMessageListener(wv, JS_OBJECT, setOf(ORIGIN)) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            if (!isMainFrame || sourceOrigin.host != ORIGIN_HOST || sourceOrigin.scheme != "https") return@addWebMessageListener
            reply = replyProxy
            message.data?.let(listener::onShellMessage)
        }
        wv.loadUrl(ENTRY_URL)
        view = wv
        return wv
    }

    /** Main thread only. Drops messages until the shell has spoken (there is nobody to receive them). */
    fun post(raw: String): Boolean {
        val r = reply ?: return false
        r.postMessage(raw)
        return true
    }

    fun onShown() {
        view?.onResume()
    }

    fun onHidden() {
        // Keep JS timers alive for in-flight streams but stop rendering work.
        view?.onPause()
    }

    fun destroy() {
        reply = null
        view?.let { wv ->
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.destroy()
        }
        view = null
    }

    /**
     * Serves the Next.js static export (`output: 'export'`, `trailingSlash: true`) from
     * `assets/public`: `/ime/` → `ime/index.html`. Only GET of known asset paths; no directory listing.
     */
    private class StaticExportHandler(private val context: Context) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            if (path.contains("..")) return null
            val clean = path.trimStart('/')
            val candidates = when {
                clean.isEmpty() -> listOf("index.html")
                clean.endsWith("/") -> listOf("${clean}index.html")
                clean.substringAfterLast('/').contains('.') -> listOf(clean)
                else -> listOf("$clean/index.html", "$clean.html")
            }
            for (c in candidates) {
                val stream = open("public/$c") ?: continue
                val res = WebResourceResponse(mimeOf(c), if (isText(c)) "utf-8" else null, stream)
                res.responseHeaders = SECURITY_HEADERS
                return res
            }
            return null
        }

        private fun open(asset: String): InputStream? = try {
            context.assets.open(asset)
        } catch (_: IOException) {
            null
        }

        private fun isText(p: String) = p.endsWith(".html") || p.endsWith(".js") || p.endsWith(".css") || p.endsWith(".txt") || p.endsWith(".json") || p.endsWith(".svg")

        private fun mimeOf(p: String): String = when (p.substringAfterLast('.')) {
            "html" -> "text/html"
            "js" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "txt" -> "text/plain"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "woff2" -> "font/woff2"
            "ico" -> "image/x-icon"
            else -> "application/octet-stream"
        }
    }

    companion object {
        const val ORIGIN_HOST = "ak.local"
        const val ORIGIN = "https://ak.local"
        const val ENTRY_URL = "https://ak.local/ime/"
        const val JS_OBJECT = "akNative"

        /**
         * Header CSP, enforced together with the per-page meta CSP that `scripts/csp.mjs` injects at
         * build time. That meta policy hash-pins every inline script Next emits, so the effective
         * script policy is "self + exactly those hashes". This header can't know per-build hashes, so it
         * allows inline scripts and contributes what meta tags cannot express (`frame-ancestors`).
         * Verified in Chromium by `apps/shell/e2e/csp.mjs`.
         */
        private val SECURITY_HEADERS = mapOf(
            "Content-Security-Policy" to "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
            "X-Content-Type-Options" to "nosniff",
            "Cache-Control" to "no-store",
        )

        private val BLOCKED = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), "".byteInputStream())
    }
}
