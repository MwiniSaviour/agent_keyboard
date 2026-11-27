# ADR-0009: Native host implementation (Part 2)

- Status: Accepted · 2026-11-27
- Context: ADR-0002/0003/0005/0006 fixed the shape of the Android host: a pre-warmed WebView IME, a
  separate `:agent` process over AIDL, a context ladder, and policy-compliant autonomy. Part 2 turns
  that into code that meets three constraints at once:
  - it must be verifiable without a device, because CI and sandbox runs have no SDK;
  - it must be safe by construction, not by convention;
  - it must be cheap on 4 GB phones (NFR-M, NFR-P).

## Decisions

1. **Pure-Kotlin `:core`, thin Android adapters.**
   - Everything that decides something lives in `core/` with no Android imports: the envelope codec,
     bridge router, message decoders, editor ops, field classifier, autonomy reducer, privacy filter,
     conversation cache, and the whole `:agent` runtime.
   - Android classes translate platform callbacks into core calls and core effects into platform calls.
   - Result: 107 JVM tests cover the decisions. They run under Gradle (`:core:test`) and in the
     no-SDK harness (`tools/verify-core.sh`).

2. **Hand-rolled JSON with hard caps.**
   - A 300-line codec: 512 KB input cap, depth 32, safe-integer numbers.
   - It replaces kotlinx-serialization, avoiding its runtime and plugin on the keystroke path and in
     the harness. Decoders are strict: an unknown enum wire rejects the whole message.

3. **One protocol, two transports, shared fixtures.**
   - The IME (WebMessageListener) and the companion (Capacitor `AgentKit`) carry the same v1
     envelopes.
   - `packages/protocol/fixtures/contract.json` and `policy.json` are replayed by the TS and Kotlin
     suites.
   - The native agent seeds are generated from the shell's `defaults.ts` and drift-checked by vitest.

4. **Ownership split implemented by `ImeController`.**
   - **In-process:** latency- or safety-critical work stays in the IME — editor ops, haptics,
     `ui.resize`, the autonomy state machine, the native consent record, and rung-0/2 context.
   - **Forwarded:** data and AI go to `:agent` — agents, clipboard store, settings, audit, rung 1,
     runs and TTS.
   - Forwarded requests time out after 4 s and fail fast when the binder dies, so the shell never
     waits for its 5 s timeout.

5. **Safety gates are native and fail closed.**
   - Secure fields block `agent.*` and `context.*` in the IME router *and* in `:agent`.
   - A focus record without `sensitive` is treated as sensitive.
   - Autonomy arms only with the native consent record and an agent ceiling fetched from `:agent`;
     if the fetch fails, it stays disarmed.
   - Clipboard secrets and password-field copies are never stored, and audit text is redacted.
   - Accessibility is read-only in `play`. The single action (`clickSend`) exists only in the `full`
     source set, with its own disclosure string.

6. **`:agent` runtime: single-threaded state, single-flight inference.**
   - `AgentRuntime` is confined to one HandlerThread; inference runs on one background-priority
     thread. No locks.
   - The newest run supersedes the previous one (one resident model, ADR-0004).
   - Cancellation is immediate: the error is emitted at once and late results are dropped.
   - The specialUse FGS is held only while a run that `needsForeground` is in flight, and is not
     dropped during supersession. Restarting an FGS from the background can be refused on
     Android 12+; a test caught exactly that flicker.
   - Part 2's `NoModelEngine` fails fast with `unavailable`. Part 3 plugs in behind the
     `InferenceEngine` port.

7. **Persistence.**
   - One SQLCipher database, opened only in `:agent`, with schema from forward-only SQL migrations
     shared with CI (`verify-schema.py` applies them to real SQLite+FTS5).
   - The key is a random 256-bit raw key (`x'…'`, which skips PBKDF2, saving hundreds of ms per open
     on low-end CPUs). It is wrapped by a non-exportable AndroidKeyStore AES-GCM key and stored in
     `noBackupFilesDir`.
   - If the wrapped key is lost, the store resets rather than crash-looping.

8. **Frame-paced input.**
   - The first text op in a frame is applied immediately, so a single keystroke has no added latency.
   - Further ops in that frame are coalesced into one batch edit on the next Choreographer frame.
   - `editor.state` is throttled to one per frame and 2,000 chars per side.

9. **Resilience.**
   - The renderer is recreated after `onRenderProcessGone`, at most 3 times a minute; after that the
     native fallback keyboard stays.
   - The fallback renders at the shell's height, so swapping never moves the host app's layout.
   - `AgentClient` binds lazily, checks the protocol version, queues up to 64 calls, and backs off
     from 250 ms to 8 s.

10. **Content Security Policy.**
    - Next's static export needs inline scripts. A post-build step (`scripts/csp.mjs`) injects a
      per-page meta CSP that hash-pins exactly those scripts.
    - The native header adds `frame-ancestors` and blocks every request that isn't to `ak.local`.
    - Verified in Chromium: zero violations at boot, and injected inline scripts are refused.

11. **Verification without the SDK.**
    - `tools/verify-app.sh` type-checks both flavors under `-Werror`. It compiles against the real
      `android.jar` and resolves symbols from the real Capacitor, androidx.webkit and SQLCipher
      sources.
    - Generated stand-ins exist only for build outputs (`R` from `res/`, `BuildConfig`, AIDL
      interfaces from the `.aidl` files).
    - It also checks that every manifest component resolves to a compiled class. Mutation runs
      (a wrong API name, a missing string, a manifest typo) all fail it.

12. **Platform floor:** minSdk 29, as in SPEC §6/§7. The Capacitor scaffold's 24 was drift, and raising
    it removed obsolete compatibility branches. Unused `kotlinx-coroutines` was dropped.

## Consequences

- (+) Every decision is testable on a laptop. The app module is type-checked against the real APIs.
  Safety rules are enforced in two places and fail closed.
- (+) Part 3 changes one port (`InferenceEngine`) plus model management; the plumbing is done.
- (−) The JSON codec and the harness are code we own.
- (−) The no-SDK checks do not replace a Gradle build, instrumented IME tests or Macrobenchmark on
  reference devices (SPEC §19.4). Those remain release gates.
