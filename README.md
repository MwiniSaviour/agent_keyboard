# Agent Keyboard

An **offline-first AI keyboard** for Android, built with Capacitor and Next.js. It understands what's on your screen (chats, forms, essays, images), types with your voice, refines and explains text, reads aloud, and in **autonomous mode** replies to chats or fills forms for you. It runs on 4 GB phones, and all inference happens on the device.

> Status: **Part 1 (agent shell)** is done: keyboard UI, protocol, engine and a full in-browser simulation.
> **Part 2 (native Android host)** is written and verified without a device: the IME service, shell transport, native fallback keyboard, `:agent` process (SQLCipher store, notification context, TTS, companion plugin), live screen capture and scroll-and-read (ADR-0010), plus 134 JVM tests and a `-Werror` type-check of both flavors against the real Android APIs (ADR-0009). It has **not yet been built with Gradle or run on a device**. Pre-Part-3 review and fixes: [`docs/reviews/2026-11-27-part2-review.md`](docs/reviews/2026-11-27-part2-review.md).
> **Part 3 (on-device models)** is next: `agent.run` currently answers `unavailable`. See [`docs/SPEC.md`](docs/SPEC.md).

## Try it

```bash
nvm use            # Node 22
npm install
npm run dev        # http://localhost:3000, the playground
```

The playground shows a simulated phone with three host apps above the real keyboard:

| Scenario | Try |
|---|---|
| **Chat** (WhatsApp) | Switch the pill to **Sales Bot** and turn **AUTO** on. Press "Jane sends a message" and watch it reply on its own. When Jane asks for a bank PIN, it **escalates** to you instead of answering. |
| **Form** (Chrome) | Lens → **Fill form**. It fills from your on-device profile and never touches the password field. Forms are never auto-submitted. |
| **Essay** (Keep) | Tap *exigent*, then Lens → **Define word** (the "Vocabulary" card). Or use **Summarize**. |

Other things to try: typing with autocorrect-style suggestions, shift double-tap for caps lock, long-press for the symbol hints, space-bar drag to move the cursor, the mic for simulated streaming dictation, emoji, clipboard and quick replies, settings, light/dark. Your physical keyboard types into the phone too.

Companion pages: `/agents` (create and edit agents with a ≤15,000-character prompt and learning resources), `/models` (model packs per device tier), `/settings`, and `/ime` (the bare surface the Android IME loads).

## Layout

```
docs/                 SPEC-001, research notes, ADRs 0001–0009, reviews/
packages/protocol     @ak/protocol: typed shell⇄native message contract (zero deps)
packages/keyboard-engine  @ak/keyboard-engine: pure layouts, reducer, editor model, predictor (zero deps)
apps/shell            @ak/shell: Next.js static export (keyboard, playground, companion app)
  src/bridge          ImeBridge (WebMessagePort) · CapacitorBridge · WebMockBridge (lazy, dev only)
  src/keyboard        Controller + KeyGrid (delegated pointer handling) + panels
  src/agents          General + custom agents (repository)
  e2e/smoke.mjs       headless-Chromium smoke test (17 checks)
  e2e/csp.mjs         every page boots under the hash-pinned CSP; injected scripts are refused
  scripts/csp.mjs     post-build: per-page CSP pinning Next's inline scripts
apps/shell/android    native host (Gradle; Capacitor 8)
  core/               pure-Kotlin logic: codec, router, editor ops, autonomy, privacy, :agent runtime, SQL migrations
  app/                IME service, WebView transport, fallback keyboard, :agent service, SQLCipher, listeners
  tools/              verify-core.sh · verify-app.sh · schema check · AndroidConst parity (no-SDK verification)
```

## Scripts

| Command | What it does |
|---|---|
| `npm run check` | Typechecks all packages, runs the tests (engine 41, protocol 3, shell 43) and builds |
| `npm run build` | Static export to `apps/shell/out` (includes the web mock for the playground) |
| `npm run build:native -w @ak/shell` | Production build with `NEXT_PUBLIC_AK_MOCK=0`; the mock is removed from the bundle |
| `npm run size -w @ak/shell` | NFR-P-14 budget for `/ime` (app code ≤ 40 KB gzip on top of the framework) |
| `npm run preview` | Serves `out/` on 0.0.0.0:3000 |
| `./gradlew :core:test :app:assembleDebug` (in `apps/shell/android`) | Native build and core tests (requires the Android SDK) |
| `tools/verify-core.sh` · `tools/verify-app.sh` | The same checks without Gradle or the SDK; the scripts' headers list the env vars they need |

## Key decisions

These are summarised from the ADRs.

- The keyboard renders in **one pre-warmed WebView** inside the IME and talks to native over `WebMessagePort` JSON envelopes. A native fallback view covers cold start. If p95 touch→glyph latency exceeds 35 ms on the 4 GB reference device, the key grid moves to native (ADR-0002).
- The IME process (≤ 90 MB) is separate from the `:agent` foreground service that hosts the models (ADR-0003).
- **LiteRT-LM** for LLM/VLM and **sherpa-onnx** for speech. Model tiers:
  - S (4 GB): Gemma 3 1B / Qwen3-0.6B
  - M: Gemma 4 E2B
  - L: E4B

  Only one generative model is resident at a time (ADR-0004).
- Screen context uses a **context ladder**: EditorInfo → notifications → accessibility outline → OCR → VLM, escalating only as needed (ADR-0005).
- Autonomy acts **only** through InputConnection, editor actions and notification RemoteInput. The accessibility service is read-only in the Play build, following the Play policy of January 2026 (ADR-0006).
- Storage is SQLite + FTS5 + sqlite-vec under SQLCipher (ADR-0007).
- The shell has no state library and no CSS framework: CSS Modules, CSS variables and inline SVG (ADR-0008).
