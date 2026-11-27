# ADR-0011: On-device model runtime

- Status: Accepted · 2026-11-27
- Builds on: ADR-0003 (`:agent` hosts models), ADR-0004 (LiteRT-LM only, tiers S/M/L, one resident model)
- Context: Part 3 turns the stubbed engine (`NoModelEngine`) into real on-device generation. SPEC §12
  sets the rules: tiered model packs, signed `models.json`, resumable downloads with SHA-256 checks
  and atomic moves, no models in git or the APK, licences shown in the app, and the §11.4 scheduler.
  This ADR records how those rules are put into practice and the choices the SPEC left open.

## Decisions

### 1. Model catalog: a signed envelope, with keys looked up by id

- The app trusts one file: `models.json`, shaped
  `{keyId, payload: base64(json), signature: base64(DER ECDSA P-256 over the payload bytes)}`.
- The payload is `{schema: 1, issuedAt, models[]}`. Each entry has:
  - `id`, `file`, an https `url` pinned to a Hugging Face **commit** (`resolve/<sha>/<file>`), so the
    bytes behind a hash never change;
  - `sha256`, `sizeBytes` (8 GB or less), `tiers`, `coreFor`, `capabilities`, `backends`;
  - `runtime` (`litertlm` | `system`), `contextTokens`, `ramMb`, `minAppVersion`;
  - `license` and `licenseUrl`, which are shown on the Models screen.
- Verification is strict:
  - an unknown `keyId`, a bad signature or a schema violation means the catalog is ignored (`invalid`);
  - an `issuedAt` older than the cached catalog is refused, so an old catalog can't be replayed.

  Allowed public keys come from `ak.modelManifestKeys=keyId:base64Spki[,…]`. Keeping several keys
  lets a key be rotated without an app update locking anyone out. With no URL or key the build is
  `unconfigured`: it says so and downloads nothing.
- The catalog is cached in app-private storage, rechecked after 6 hours at most when the Models
  screen opens, and capped at 256 KB.
- Tooling: `scripts/models/manifest.mjs keygen | build | sign | verify | fixture`.
  - `build` streams every pinned URL to measure the exact size and SHA-256. The source list is
    `models/catalog.json`.
  - The test fixtures (`packages/protocol/fixtures/models.*.json`, key `ak-test`) are checked by
    both the TS and Kotlin suites.

### 2. Downloads: our own resumable installer, not WorkManager

`core.models.ModelInstaller` (platform-free and unit-tested) handles transfers:
- it writes `<file>.part` with `Range` resume (206 appends, 200 restarts, 416 discards and retries);
- it retries transient errors 5 times, backing off from 2 s up to a 30 s cap;
- it keeps 512 MB of free-space headroom;
- it hashes while streaming, then fsyncs and renames. A file that fails the hash is deleted and is
  never used.

Files live in `noBackupFilesDir/models`, which is never backed up and is memory-mapped by LiteRT-LM.

WorkManager was rejected for three reasons. It would add a dependency and a second process
lifecycle to `:agent`. It can't satisfy "only on Wi-Fi, but start the moment the user taps"
without its own foreground plumbing. And our queue already persists through the SQLCipher settings
store.

**Transfers start only from the Models screen**, either when it opens (`models.status` from the
companion) or from `models.download`. Never from a background wakeup:
- While a transfer runs, `AgentService` is a `dataSync` foreground service. Its notification says
  "Installing AI model".
- If Android 15+ ends the dataSync time budget (`onTimeout`), the queue is **suspended**: partial
  files are kept, and the queue resumes the next time the user opens Models.
- `models.wifiOnly` (default true) waits for an unmetered network. It is the only `models.*`
  setting that can be changed.
- Deleting a model during its download discards the partial output.

The **offline flavor** has no INTERNET permission, so it installs by **import**:
- the companion opens the system file picker and passes the descriptor over AIDL
  (`importModel(pfd, name, size)`);
- `:agent` matches the file name and size against the cached signed catalog, then applies the
  same SHA-256 check and atomic move;
- files that aren't in the catalog are refused, with a reason shown on the screen.

The keyboard can only read `models.status`. It ignores download, cancel, delete and import.

### 3. Model choice per tier

The sources are `litert-community` builds, whose names, sizes and commits match Google AI Edge
Gallery's allowlist.

| Tier | Core text model | Optional |
|---|---|---|
| S (4–5 GB) | Gemma 3 1B int4 (584 MB, Gemma Terms) | — |
| M (6–8 GB) | Gemma 4 E2B (2.59 GB, Apache-2.0: text, vision, audio) | — |
| L (9 GB+) | Gemma 4 E2B | Gemma 4 E4B (3.66 GB) |

Embeddings, STT and the Tier S VLM are separate packs. They will be added to the catalog as their
features land (SPEC §13–§14). Until then the skills that need them report `unavailable` rather than
guessing.

### 4. Inference: one engine, scheduled, with a cached prefix

- **`ModelManager`** keeps exactly one model resident.
  - It picks the backend from the entry's list (NPU → GPU → CPU).
  - A GPU or NPU is proven by a **crash-safe probe**. A probe record (key
    `v{app}:{id}:{sha12}:{backend}`) is committed *before* loading. If the process dies mid-load,
    that backend is skipped next time.
  - It uses 4 threads, or 2 when the device is thermally throttled.
  - It unloads after 5 minutes idle, and on `onTrimMemory`. An out-of-memory error becomes
    `EngineFailure("unavailable")`, which is shown as a clear message.
- **`InferenceScheduler`** (SPEC §11.4) runs everything through priorities P0–P4:
  - for P0–P2, the latest request wins;
  - preempted P2–P4 work is requeued, while P1 is cancelled and shown with Retry;
  - P3/P4 duplicates are dropped;
  - when full, the least urgent, oldest item is dropped;
  - **only P0/P1 runs stream tokens**, because a requeued streaming run would repeat text.
- **Skills and PromptBuilder** turn each skill into a prompt that fits the context window:
  - tokens are estimated at chars/3 with a 48-token margin;
  - the persona gets at most a quarter of the context and the output at most half.
- **Output validators** check structured skills (JSON schema) and cap autonomy confidence with
  heuristics:
  - blocked requests get 0;
  - ungrounded numbers 0.4 or less;
  - truncated output 0.3 or less;
  - very long output is multiplied by 0.8.

  The model's own confidence is never trusted on its own.
- **LiteRT-LM adapter** (`LiteRtLmRuntime`, pinned to v0.20.0 APIs):
  - The agent persona goes in the **system instruction**, prefilled once into a base
    `Conversation` (`prefillPrefaceOnInit`). Each run `clone()`s it, so the persona's prefill cost is
    paid once per agent and sampler.
  - If `clone()` or `responseFormat` turns out unsupported for a model, the adapter falls back to a
    fresh conversation or plain text, and remembers that per model.
  - Thinking is disabled, and any `<think>…</think>` is filtered from the stream.
  - Cancel uses `cancelProcess()`, polled every 40 ms with a 3 s grace period. A conversation that
    native code may still be writing into is abandoned rather than closed, because closing it could
    crash.
  - GPU needs `<uses-native-library>` for `libOpenCL.so` and `libvndksupport.so` (both optional).
    NPU uses `nativeLibraryDir`, but stays off in this build.

## Consequences

- Nothing model-related is in git or the APK. A fresh install has no text model until the user
  downloads (or imports) the core pack. Until then the skills report `unavailable`, and
  `system.status.modelsInstalled` is false.
- The release pipeline has to host a signed `models.json` and set `ak.modelManifestUrl` and
  `ak.modelManifestKeys`. Real hashes are measured by `manifest.mjs build` on a networked machine.
  The fixtures only exercise the format.
- The web playground simulates the same lifecycle (`web-mock/models.ts`), and a test keeps it in
  sync with `models/catalog.json`.
- Open: the STT engine choice (§13), embeddings and retrieval (§14), the Tier S VLM swap, and
  measuring NFR-P-07/08 on real Tier S and M phones.
