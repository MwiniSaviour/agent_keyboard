# Agent Keyboard

An **offline-first AI keyboard** for Android, built with Capacitor and Next.js. It understands what's on your screen (chats, forms, essays, images), types with your voice, refines and explains text, reads aloud, and in **autonomous mode** replies to chats or fills forms for you. It runs on 4 GB phones, and all inference happens on the device.

> Status: **Phase 1, Agent Shell.** The keyboard UI, the protocol, the engine and a full in-browser simulation are done. Native Android (Phase 2) and the on-device model runtime (Phase 3) are specified but not built yet. See [`docs/SPEC.md`](docs/SPEC.md).

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
docs/                 SPEC-001, research notes, ADRs 0001–0008
packages/protocol     @ak/protocol: typed shell⇄native message contract (zero deps)
packages/keyboard-engine  @ak/keyboard-engine: pure layouts, reducer, editor model, predictor (zero deps)
apps/shell            @ak/shell: Next.js static export (keyboard, playground, companion app)
  src/bridge          ImeBridge (WebMessagePort) · CapacitorBridge · WebMockBridge (lazy, dev only)
  src/keyboard        Controller + KeyGrid (delegated pointer handling) + panels
  src/agents          General + custom agents (repository)
  e2e/smoke.mjs       headless-Chromium smoke test (17 checks)
```

## Scripts

| Command | What it does |
|---|---|
| `npm run check` | Typechecks all packages, runs the tests (engine 38, shell 9) and builds |
| `npm run build` | Static export to `apps/shell/out` (includes the web mock for the playground) |
| `npm run build:native -w @ak/shell` | Production build with `NEXT_PUBLIC_AK_MOCK=0`; the mock is removed from the bundle |
| `npm run size -w @ak/shell` | NFR-P-14 budget for `/ime` (app code ≤ 40 KB gzip on top of the framework) |
| `npm run preview` | Serves `out/` on 0.0.0.0:3000 |

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
