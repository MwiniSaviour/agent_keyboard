# Part 2 review: native Android host

**Date:** 2026-11-27 · **Scope:** `apps/shell/android/**` and the shell/protocol code it talks to, checked
against SPEC FR/NFR tables, §8 (protocol), §9–§11 and ADR-0002/3/6/7/9 · **Base:** `9eddaa8`

## Method

I read every native source file (IME service, controller, executor, WebView host, fallback keyboard,
AIDL client, `:agent` service and hub, storage, notification listener, accessibility reader, companion
plugin, manifests) next to the matching shell code. I traced each wire message from both ends. Every
finding below was confirmed in the code before it was changed. Each fix has a regression test where
the logic lives in pure code (TS or `:core`). Fixes in Android-only classes are type-checked with
`-Werror` against `android.jar` for both flavors.

## Findings

Severity: **High** means user-visible breakage or data loss, **Medium** means wrong behaviour in a
common path, **Low** means an edge case, polish or a documentation gap.

| # | Sev | Area | Finding | Status |
|---|---|---|---|---|
| 1 | High | Protocol / editing | `text.delete` unit mismatch. The shell sent UTF-16 units, but SPEC §8.2 and Android (`deleteSurroundingTextInCodePoints`) use code points. Backspace on 👍 also deleted the character before it, and a ZWJ family emoji took 3 extra characters with it. The same happened when a suggestion replaced a word. | **Fixed.** The controller converts at the boundary (`toWireDelete`). The mock host applies code points. Units are documented on the message. 3 tests, including a controller test that fails without the fix. |
| 2 | High | Form fill (FR-AU-08, §11.7) | Native `fillForm` wrote the **first value into whatever field was focused**. `EditorInfo` has no autofill hints, so the hint match never succeeded. It also filled only one field, and it pressed `next` even when the field's action was not `next`. | **Fixed.** New `core/editor/FormFill` does a deterministic match (exact id/label/hint, then semantic kind, autofill hint or exact label phrase). It never guesses and never fills sensitive fields. Values are written one by one as focus reaches each field (60 s, same app). `next` is used only where the field declares it, and nothing is ever submitted. 5 tests. |
| 3 | High | Storage (ADR-0007) | `KeyVault` treated **any** unwrap failure as "key lost" and wiped the encrypted DB. A transient Keystore error (daemon busy, `ProviderException`) therefore silently erased agents, clipboard and the audit log. | **Fixed.** The store is reset only when the key is provably gone: the alias is missing, `AEADBadTag`, or the blob is corrupt. Other errors propagate. `AgentHub` catches a failed runtime start and retries on the next request instead of crash-looping `:agent`. |
| 4 | Medium | Settings (FR-KB-09) | Haptics and key sounds were coupled. The default (haptics on, sounds off) still played a sound on every key, and turning haptics off with sounds on still vibrated. The native side never saw keyboard settings: the comment claimed native persistence that did not exist. | **Fixed.** The shell mirrors `keyboard.theme/haptics/keySounds` to the IME (`settings.set`, IME-local, not forwarded to `:agent`). Native gates each kind of feedback separately. The fallback keyboard uses the same gate. |
| 5 | Medium | Theme (FR-UI-08) | Native sent `theme.update` but the shell **ignored it**. Inside an IME, `prefers-color-scheme` does not reliably follow night mode, so the "system" theme could be wrong. The fallback followed system night mode even when the user had chosen light or dark, so the board flashed when the shell took over. | **Fixed.** The shell handles `theme.update`, and native emits it on `hello` and on configuration changes. The fallback follows the user's theme setting. |
| 6 | Medium | Settings sync | Settings changed in the companion did not reach the long-lived IME page until the WebView was recreated, which is rare by design. | **Fixed.** A `storage` event listener re-hydrates settings. Both pages share the origin and profile. |
| 7 | Medium | Lifecycle | `onStartInputView(restarting=true)` → `executor.discard()` dropped keystrokes still queued for the frame. A restart has no finish callback first, so nothing had flushed them. | **Fixed.** Pending ops are flushed (applied) instead of discarded. |
| 8 | Medium | Resilience | After 3 renderer crashes in a minute, the log said "fallback until next IME start", but nothing ever recovered. The shell was gone until the process died. | **Fixed.** On the next show after a 60 s cool-down, the WebView is recreated. |
| 9 | Medium | Performance (NFR-P-01/02) | The rung-2 accessibility tree walk (up to 600 nodes, each a binder call into the host app) ran on the **IME main thread**. | **Fixed.** It now runs on a dedicated reader thread with the reply posted to main. The secure-field check is repeated after the read in case focus moved. |
| 10 | Medium | Process model (ADR-0003, §9 "lazy bind") | `focusChanged` on every field start bound `:agent`, so the second process was started (and kept at BIND_IMPORTANT) the first time any field was focused. | **Fixed.** Focus is cached and delivered first on connect, so the secure-field gate still sees it before any request. The link stays up ("sticky") while autonomy is armed, so inbound messages keep arriving. |
| 11 | Medium | Autonomy safety (FR-AU-04/05) | MessagingStyle "from me" detection relied only on `sender == null`. Many apps set the sender to the user's own `Person`, so the user's replies looked like incoming messages, which is a reply-loop risk. | **Fixed.** Also matches `EXTRA_MESSAGING_PERSON` (by key, else name) and `EXTRA_SELF_DISPLAY_NAME`. |
| 12 | Medium | Fallback UX | No digits or symbols: an OTP or phone field could not be typed into while the WebView booted or after a crash. Rollover typing lost both keys, because `POINTER_DOWN/UP` was ignored. Backspace deleted one code point, which is half an emoji. Repeat timing was 400/50 ms instead of FR-KB-05's 350/60 ms. | **Fixed.** Numbers/symbols layer, opened automatically for number/phone/OTP fields. Rollover commits the held key. Slide-to-correct. Grapheme backspace (`TextWindow.lastGraphemeCodePoints`, tested with skin-tone, ZWJ and combining marks). Repeat 350/60 ms. |
| 13 | Low | Editing parity | Native `ReplaceWord` word boundary lacked combining marks: Devanagari vowel signs and decomposed accents split words. The shell uses `[\p{L}\p{M}\p{N}'’-]`, so the two sides replaced different spans. | **Fixed.** The native side has the same character classes. Tests cover Hindi, a decomposed é, hyphenation and emoji. |
| 14 | Low | Editing | A stale "has selection" flag (`onUpdateSelection` is async) could make the next backspace remove a selection that was already gone instead of a character. | **Fixed.** Any applied op marks the selection collapsed locally. |
| 15 | Low | Editing | `ReplaceField` selected `0..text.length` of a possibly partial extracted window (`startOffset > 0`). | **Fixed.** Uses `startOffset + length`. |
| 16 | Low | Autonomy | The ENTER_IS_SEND path sent a bare Enter without the soft-keyboard flags and bypassed the executor queue. | **Fixed.** Uses `InputExecutor.sendEnterKey()` after a flush, with the same flags as every other key. |
| 17 | Low | UX | Every cold start opened at 290 dp, then jumped to the shell's real height once `ui.resize` arrived. | **Fixed.** The last height is remembered. |
| 18 | Low | Repo hygiene | `apps/shell/src/build/csp.test.ts` was never committed: the `build/` ignore rule matched it. | **Fixed.** Moved to `src/security/`. |
| 19 | Low | Docs | Stale "API 24 = our minSdk" comment. `editor.cursor` units were undocumented. The autonomy `data.confidence` convention was undocumented. | **Fixed.** |

## Open items (not changed here)

| # | Area | Item | Proposed handling |
|---|---|---|---|
| A | Context (§10.1) | Rung 1 answers with the **most recently updated** conversation of the focused app (`ConversationCache.latestFor`). That may not be the chat on screen. | Part 3: match on the a11y/visual title when available. Otherwise show the source ("From notification · Ama") in the context chip and require the user to confirm before replying. |
| B | Accessibility | The native fallback keyboard exposes no virtual view tree, so TalkBack cannot explore its keys. | Add `ExploreByTouchHelper` (androidx.customview) before release. |
| C | Consent (FR-AU-02) | There is no revoke path: the shell only ever sends `autonomy.consent = true`, and it re-sends on every enable, which adds a `consent_granted` audit row each time. | A companion "Revoke consent" control sends `false`. Send `true` only from the consent flow, and audit only changes. |
| D | Policy | `:agent` keeps one focused field for all clients. A companion-initiated run after the IME's last field was a password field is refused. | Scope the focus gate to IME-originated runs. |
| E | Sound | Platform key clicks play only when system "Touch sounds" is on. | Accept, or use SoundPool with bundled clicks if the in-app toggle should be authoritative. |
| F | Notifications | Reply actions are not dropped on `onNotificationRemoved`. They expire naturally (32-entry LRU, `CanceledException`). | Drop them on removal in Part 3, together with background replies. |
| G | Verification | Nothing here has run on a device or under Gradle. Lifecycle ordering, WebView theming, Keystore behaviour and OEM quirks are reasoned from the API docs only. | First device pass: the J1–J6 journeys plus a cold-start trace for NFR-P-03/06. |

## Verification after fixes

| Check | Result |
|---|---|
| `tools/verify-core.sh` | 107/107 JVM tests (was 101), AndroidConst parity 34/34, schema v1 OK |
| `tools/verify-app.sh` | play and full: 0 errors, 0 warnings (`-Werror`), manifests 6/6 |
| `npm run check` | engine 40, protocol 3, shell 40 tests; typecheck and builds green |
| E2E smoke / CSP probe | 17/17 · 3/3 pages, 0 violations |
