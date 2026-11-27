# ADR-0002: WebView-rendered keyboard in the IME, custom bridge, native fallback gate

- Status: Accepted (gated by SPEC §19.3) · 2026-11-27
- Context: The product mandate is Capacitor + Next.js. Capacitor's `Bridge` is built around an
  Activity/Fragment [R8], but an Android keyboard is an `InputMethodService`, so the Capacitor bridge
  can't run there. WebView-based keyboards have historically been seen as heavy and slow [R8], and
  P1 says typing is sacred.
- Options:
  1. Fully native keyboard (Kotlin/Compose); Next.js only for the companion app.
  2. WebView keyboard in the IME with a custom message bridge.
  3. Hybrid: native key grid + WebView for agent panels.
- Decision: **Option 2 now, with Option 3 as a pre-built fallback.**
  - The IME hosts *one* pre-warmed, never-destroyed WebView that loads the `/ime` route of the same
    static bundle, served by `WebViewAssetLoader` at `https://ak.local`.
  - Transport is androidx.webkit `addWebMessageListener` + `WebMessagePort` with the versioned JSON
    envelope (SPEC §8). It is origin-scoped and avoids `addJavascriptInterface` reflection.
  - The hot path follows ADR-0008 (pure reducer, imperative pressed state, one delegated listener).
  - A native `FallbackKeyboardView` ships from Phase 2. It covers cold start (before JS `ready`),
    renderer crashes, and secure-input fields if needed.
  - **Gate:** if p95 touch→glyph exceeds 35 ms on the reference 4 GB device after optimisation, the
    key grid moves to native and the WebView keeps agent UI only (Option 3).
- Consequences: (+) One UI codebase across IME and companion; rapid iteration; same tests. (−) We
  must implement and maintain a small custom bridge and a native fallback keyboard. (−) The WebView
  renderer costs ~40–60 MB of the IME's 90 MB budget, so we measure it in CI.
