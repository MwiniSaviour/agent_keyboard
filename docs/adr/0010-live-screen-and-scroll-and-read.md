# ADR-0010: Live screen pixels and scroll-and-read

- Status: Accepted · 2026-11-27
- Amends: ADR-0005 (rung 3 becomes live, not one-shot), ADR-0006 (one user-started scroll action in `play`)
- Context: The agent only answers well when it knows what the user is looking at. Rungs 1–2
  (notifications, accessibility tree) miss three things:
  - apps that draw their own UI (Flutter, games, canvas, many banking or super-apps), where the
    accessibility tree is empty;
  - images, layout and anything that isn't text;
  - everything above or below the visible screen, such as the earlier part of a chat or the rest of
    an article.

  The user asked for real-time screen pixels and the ability to scroll, using Cluely as a case study.

## Case study: what Cluely-style copilots get right and wrong

Sources are listed in `docs/research/2026-11-landscape.md`. In short:

| Cluely / clones | Lesson | What we do |
|---|---|---|
| Screen OCR plus audio, then an LLM. Suggestions arrive in about 1–2 s because context is already captured when the user asks. | **Always ready, rarely working.** Keep the newest frame warm, so asking costs about 0 ms of capture. | A MediaProjection VirtualDisplay keeps the newest frame of the app region in RAM. A frame is only analysed when the screen changed and someone can use it. |
| Some clones watch a user-chosen region and keep a capped screenshot queue (for example 50, oldest dropped). | **Watch a region, not the world**, and bound the buffer. | We only fingerprint and keep the app area above the keyboard. Our keyboard, the status bar and the navigation bar are cropped out. The ring is capped at 6 frames, 1.5 MB and a 30 s TTL. |
| The overlay hides itself from screen capture (`WDA_EXCLUDEFROMCAPTURE`, `setContentProtection`) so other people can't see it. | Invisibility to others is the product. For a keyboard that is the wrong value. | **Visible to the user, always.** Android's capture indicator and our notification (with Stop) are shown, there is a LIVE badge in the keyboard bar, and the Lens panel shows the exact frame the agent can use. |
| Screenshots and transcripts are processed and stored in the cloud. The 2025 breach exposed about 83k users' data. | **Nothing stored means nothing to breach.** | Frames are JPEG bytes in RAM only, zeroed on eviction, and never written to disk or sent anywhere. They are purged on secure fields, blocked apps and all-black (`FLAG_SECURE`) frames. |

## Decisions

1. **Pixels come from MediaProjection, not `AccessibilityService.takeScreenshot`.**
   - `takeScreenshot` (API 30+) is stills only, rate-limited (about one every 333 ms) and needs the
     accessibility service.
   - MediaProjection gives a continuous stream on API 29+. Android shows the user a system consent
     dialog every session, and the token is single-use and never stored.
   - The service is a `mediaProjection` foreground service in the **main process**, next to the IME,
     so the keyboard reads frames without IPC. `FOREGROUND_SERVICE_MEDIA_PROJECTION` is declared, and
     the FGS is promoted before `getMediaProjection` (required on API 34+).

2. **Cost model: drop early, analyse rarely.**
   - The compositor downscales into a VirtualDisplay of at most 800/960/1200 px on the long side,
     depending on tier (`CaptureGeometry`).
   - A VirtualDisplay produces frames only when the screen changes.
   - `CaptureGate` drops frames before any pixel is read unless the keyboard is visible or Autopilot
     is armed, and no secure field or blocked app is focused.
   - `FrameThrottle` checks at most 4 frames/s while the screen is busy, backing off to 1/s once it
     settles.
   - `FrameSignature` (a 16×16 luma block grid, about 25k strided samples) decides whether the region
     really changed. It ignores sub-block noise such as a blinking caret. Only changed frames are
     JPEG-encoded into the `FrameRing`.
   - All of this policy is platform-free and unit-tested.

3. **Privacy gates are enforced natively and fail closed.**
   - `screen.capture{enabled:true}` is refused in secure fields by the router.
   - While capturing, a secure field, a blocked app or an all-black frame **pauses and purges** the
     buffer immediately. It does not wait for the next frame, because a static screen produces none.
   - `context.snapshot{scope:"visual"}` never starts capture. It answers rung 3 only while capture is
     live, and fails with `unavailable` otherwise.
   - Capture stops with the keyboard process, and `START_NOT_STICKY` means it never comes back without
     fresh consent.

4. **Scroll-and-read is a user-started, deterministic routine.**
   - `context.scan{direction, pages ≤ 8}` scrolls the app's largest visible scrollable node with
     accessibility scroll actions and reads each page. `ScanMerger` removes the overlap between pages
     and prepends older content when scrolling up. Afterwards it scrolls back the same number of steps
     and reports `scan{pages, complete, restored}`.
   - `ScanPlanner` decides when to stop: end of list, two pages with nothing new, page cap, or a
     12k-character budget.
   - Google Play's Accessibility API policy forbids autonomous, AI-initiated actions. So:
     - **`play`:** a scan must follow a finger-down on our own keyboard within 3 s. The IME measures
       this natively and the shell cannot forge it.
     - **`full`:** a scan may also run on the agent's behalf, but only after Autopilot consent
       (`ScrollPolicy`).
   - Scrolling is the only action in the main source set. Clicks stay in the `full` flavor
     (ADR-0006). The accessibility description and disclosure strings say so.

5. **Run context is attached by the IME, never trusted from JS.**
   - On `agent.run` the IME strips `params.conversationTitle` and `params.screen` from the shell
     payload.
   - It then attaches the chat title it read on screen, so rung 1 resolves the right conversation and
     reports `chat.match` (exact, likely or uncertain) instead of guessing.
   - It also attaches the screen transcript, preferring a scan of the same app from the last 60 s.
   - `context.snapshot.titleHint` is native-internal in the same way.

6. **Part 3 seam.**
   - Rung 3 frames already carry a handle, size and Lens preview.
   - OCR (rung 3 text) and VLM captions (rung 4) will read `FrameRing` in-process and attach their
     text as `params.screen{source:"ocr"}`, so a pixel never crosses into `:agent`.

## Consequences

- **Good:**
  - Context works in apps with no accessibility tree, and covers the whole chat, not just the
    visible screen.
  - It costs nothing when idle, holds frames for at most 30 s and stores nothing.
  - Every gate is tested on the JVM: 9 capture tests and 10 scan tests.
- **Cost:**
  - The keyboard closes while Android shows the consent dialog, and the user must allow capture again
    after each stop or reboot. This is platform behaviour, and an honest one.
  - If the user picks "single app" capture on Android 14+, frames only show that app.
  - A rotation keeps the first surface size, with letterboxed frames, until capture is restarted.
    This is acceptable for Part 2 and gets revisited with `onCapturedContentResize` in Part 3.
- **Unverified on device:** frame timing, OEM VirtualDisplay quirks, and scroll restoration on
  reversed chat lists. These are part of the open item G device pass.
