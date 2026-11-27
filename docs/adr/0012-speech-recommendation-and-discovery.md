# ADR-0012: On-device speech, per-device recommendations and finding models on the phone

- Status: Accepted · 2026-11-27
- Builds on: ADR-0004 (tiers, one resident model), ADR-0011 (signed catalog, installer, ModelService)
- Context: Part 4 adds three things.
  1. Voice typing with sherpa-onnx. The model files are pulled from Hugging Face after install and
     never bundled in the APK.
  2. Recommendations made for each phone: the best performance with the smartest model the phone
     can handle.
  3. Finding model files that are already on the phone, so the user can use them.

  SPEC §13 sets the speech rules: streaming ASR with Silero VAD, no audio on disk, and the mic on
  only while the user is dictating.

## Decisions

### 1. Catalog schema 2: models made of several files

- A speech model is 4–5 files: encoder, decoder, joiner, `tokens.txt` and `silero_vad.onnx`. An entry
  now has `files: [{name, url, sha256, sizeBytes}]` (1–8 files, 8 GB or less in total, unique names).
  It also has `quality` (0–100, used only to rank models of the same kind) and a runtime `config`.
- For `sherpa-onnx` entries, the parser requires `config.encoder/decoder/joiner/tokens` (and an
  optional `vad`) to name the entry's own files. sherpa-onnx is accepted only for `transcribe`.
- The installer keeps a partial file per model file and resumes each one separately. It verifies
  every file's SHA-256 and commits the model only when all of its files are in place:
  `noBackupFilesDir/models/<id>/`, written as `<file>.part` → fsync → rename. Part 3's flat
  single-file installs are migrated where they are found.
- `scripts/models/manifest.mjs` resolves `hf.revision` to a commit, so every URL is pinned to
  `/resolve/<40-hex>/`. It then measures each file, hashes it and signs the result.
- The VAD comes from the sherpa-onnx `asr-models` GitHub release. The release's ASR tarballs are not
  used: the installer does not extract archives, and the Hugging Face repositories serve the same
  files individually.
- Starting catalog entries:
  - `zipformer-en-20m`: about 46 MB, ~110 MB RAM, quality 45;
  - `zipformer-en-2023-06-26`: zipformer2, about 75 MB, ~260 MB RAM, quality 70.

### 2. Recommendations: smartest model that runs smoothly, judged per phone

`Recommender` is pure and deterministic. The same code drives the badges, "Download recommended" and
the engine's choice of model.

- **Memory:**
  - comfortable = `min(40 % RAM, RAM − 2.5 GB)`, at least 600 MB (a quarter of RAM on low-RAM
    devices);
  - hard limit = `min(55 % RAM, RAM − 1.8 GB)`.
  - The keyboard shares the phone with whatever app the user is typing in, so the foreground app
    and Android need about 2.5 GB left over.
- **Speed (generative models):**
  - decoding is limited by memory bandwidth, so tok/s ≈ `18 × device factor ÷ working-set GB`;
  - device factor: ×1.35 for flagships (≥ 11 GB and ≥ 8 cores), ×1.15 for ≥ 7 GB, ×0.7 below 5 GB,
    ×1.4 with a usable GPU;
  - the decode rate measured on the phone (an EMA of real runs) replaces the estimate as soon as it
    exists;
  - the floor is 10 tok/s (NFR-P-08).
- **Pick:** for each job (generative, voice), the highest-`quality` model that is both comfortable
  and fast enough. If none is, the lightest model that fits.
- **Labels:**
  - `recommended`;
  - `good`: runs well, a different trade-off;
  - `slow`: fits but slow or memory-hungry; the download is allowed after a confirmation;
  - `too_big`: over the hard limit; refused by `models.download`.
- Custom (unverified) models are judged but never recommended.
- RAM is shown as sold ("8 GB"), not as Android reports it (~7.3 GB).
- Tiers (ADR-0004) stay as the coarse label for features. The catalog's `tiers`/`coreFor` are kept
  for older clients, but recommendations no longer depend on them.
- **Which model does a job:** the user's `models.use` choice if it is installed, otherwise the
  installed model with the best advice, then the highest quality. Only one generative model is in
  memory at a time (ADR-0004).

### 3. Finding models already on the phone: copy, never load in place

- The `ModelScanner` port lists candidate files: `.litertlm`, `.onnx` and `tokens.txt`.
- `ModelMatcher`:
  - **matches a catalog entry** when every file of the entry is found by name and size in one
    folder, or when a single-file model with a unique size has been renamed;
  - **reports any other `.litertlm` of 50 MB or more as a custom model**: `custom-<size>-<stem>`,
    with `ramMb ≈ size × 1.1 + 300 MB`.
- `models.adopt` always copies into app storage:
  - catalog matches are hashed against the signed catalog while they are copied, so they are as
    trusted as a download;
  - unknown files need `trust: true`, which the Models screen sends only after an explicit
    confirmation. Their measured hash is pinned, so a change to the file is detected.
  - the copy of an unknown model is always stored as `model.litertlm` in its own folder. The
    original name comes from another app's storage (a document provider controls it), so it is shown
    but never used as a path.
  - an install that fails while opening a file (a revoked grant, a provider that went away) fails
    that one install; it never leaves the queue blocked.
- Copying costs storage, but loading in place would let another app swap the bytes after the check,
  and the file could disappear if the user cleans up Downloads.
- **Where the scan looks (Android):**
  - this app's external `files/models`;
  - files the user picked (`models.scan {pick: 'file'}`, multi-select, persisted document grants).
    Files picked together count as one folder, so all the files of a speech model match as a set.
    Grants are released once the files are copied in, and at once for files that can't be a model;
  - folders the user granted with the folder picker (persisted SAF tree grants);
  - all of shared storage, only on the `full` (sideload) flavor and only after the user grants
    all-files access in system settings (`MANAGE_EXTERNAL_STORAGE`, declared only in
    `src/full/AndroidManifest.xml`). The scan starts with Download/ and Documents/.
- Play builds never declare all-files access; Play policy reserves it for file managers and similar
  apps.
- **Download/ on Android 11+:** without all-files access, apps can't list other apps' files in
  Download/, and the folder picker refuses to grant Download/ itself. That is where most people
  save a model, so Play builds reach it by picking the files, or a folder inside Download/. The
  Models screen says exactly this instead of promising a Downloads search.
- The Models screen refreshes and scans again whenever it becomes visible, for example when the
  user comes back from system settings or from a download.
- **Scan limits:** depth 5, 20 000 entries, 500 results, 4 s. Scans run when the Models screen
  opens (at most every 2 minutes) and on demand.

### 4. Voice typing runs in the keyboard process

- Recognition runs where the user is looking. A visible IME counts as in use for microphone access,
  and the audio never crosses a process boundary.
- `:agent` owns installation. Whenever the transcribe model changes, it publishes the active model's
  absolute file paths to `models/active-stt.json` (`SttPointer`, written atomically). The keyboard
  reads that file, so starting the keyboard still never binds `:agent` (ADR-0009).
- **`VoiceTyping`:**
  - capture: `AudioRecord` 16 kHz mono from the `VOICE_RECOGNITION` source, in 100 ms reads on an
    urgent-audio thread;
  - recognition: sherpa-onnx `OnlineRecognizer` (greedy search) with endpointing, which splits
    speech into segments at 1 s pauses; Silero `Vad` tracks speech activity;
  - events: `voice.partial` while speaking, `voice.final` per segment, `voice.level`, `voice.end`;
  - auto-stop: after 6 s with no speech and after 2 minutes.
- The recognizer loads on first use and is released after 60 s idle or when the keyboard is
  destroyed.
- **When dictation may run (`core.speech.VoiceGate`, enforced natively; the native side holds the
  mic and commits the text):**
  - never in a protected field (FR-KB-14): `voice.start` there ends at once with a reason;
  - a session belongs to the field it began in. It ends when focus moves to another field (Android
    sends no finish callback while the keyboard stays up) or the field turns protected. It survives
    only an app restarting input on the same field, which some apps do after every commit;
  - it ends when the keyboard hides, and when the shell's voice panel closes or is replaced by
    another panel or an agent run. The shell sends `voice.cancel` whenever the panel stops being the
    voice panel during a session (SPEC §13.1: the mic runs only while the voice panel is open);
  - a session that ends without the user stopping it (no voice model, no mic permission, mic busy)
    shows its reason in the keyboard.
- **Privacy:** audio exists only in two reused 100 ms buffers, zeroed after every read and when the
  session ends (§13.1). Nothing is written to disk or logged. The VAD's speech segments are popped
  as soon as they form, and the VAD is reset at the end of each session.
- **Transcript tidy-up:** the English zipformers output upper-case text without punctuation.
  `core.speech.Transcript` lower-cases all-caps output, restores "I", and capitalises the start of a
  sentence based on the text before the cursor. `refine` (LLM punctuation) is still to come.
- **Microphone permission:**
  - an IME can't show a permission dialog, so the companion asks for it
    (`system.open {target: 'microphone'}` shows the runtime dialog);
  - after a permanent denial it opens the app's settings page;
  - the keyboard explains what to do if it starts without the permission.
- `Capabilities.voice` is true when a speech model is installed.

### 5. Packaging: the engine ships, models never do

- The pinned sherpa-onnx `2.0.3` static-link AAR (onnxruntime linked in, 40 MB AAR before ABI
  filtering) is downloaded by a Gradle task from the GitHub release. The task checks the SHA-256
  recorded in `variables.gradle` and refuses any mismatch.
- The JitPack coordinate was rejected because how its versions map to release tags is unclear.
- `abiFilters 'arm64-v8a', 'x86_64'`: every phone that meets Tier S is 64-bit, and x86_64 covers the
  emulator. This drops the 32-bit copies of every native library.

## Consequences

- One catalog format now covers text and speech models, and later embeddings (§14) the same way.
- The Models screen lists every model with a reason for its fit, rather than only one tier's pack.
- Unknown models can give poor or harmful output. They are always labelled unverified, never
  recommended, and need the user's confirmation.
- Recommendations are estimates until there are real measurements. They should be calibrated on
  real phones (NFR-P), and speech `ramMb` should be measured.

## Not done yet

- Hosting a real signed `models.json`, and real hashes for the speech files: the sandbox could not
  reach Hugging Face.
- LLM `refine` of transcripts, and voice commands ("new line", "send").
- Speech models for other languages, and the system `SpeechRecognizer` path for languages that have
  offline packs.
