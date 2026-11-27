# Architecture Decision Records

Format: [MADR-lite](https://adr.github.io/madr/). One decision per file, immutable once *Accepted*;
supersede with a new ADR instead of editing. Reference the ADR in PRs that implement it.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-android-first-platform-strategy.md) | Android-first; reduced iOS | Accepted |
| [0002](0002-webview-keyboard-with-custom-ime-bridge.md) | WebView-rendered keyboard in the IME with a custom bridge and native fallback gate | Accepted (gated) |
| [0003](0003-multi-process-isolation.md) | Separate IME and `:agent` processes | Accepted |
| [0004](0004-litert-lm-and-device-tiers.md) | LiteRT-LM as the single generative runtime; S/M/L device tiers | Accepted (model picks pending bake-off) |
| [0005](0005-structure-before-pixels.md) | Context from structure before pixels | Accepted |
| [0006](0006-policy-compliant-autonomy.md) | Autonomy via IME + RemoteInput; `play` / `full` flavors | Accepted (pending legal Q2) |
| [0007](0007-sqlite-fts5-sqlite-vec.md) | SQLite + FTS5 + sqlite-vec (SQLCipher) as the single store | Accepted |
| [0008](0008-zero-dependency-hot-path.md) | Zero-dependency, pure-reducer hot path in the shell | Accepted |
