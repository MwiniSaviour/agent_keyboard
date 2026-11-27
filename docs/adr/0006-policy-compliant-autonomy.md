# ADR-0006: Autonomy via IME + RemoteInput; `play` and `full` flavors

- Status: Accepted (pending legal review, SPEC Q2) · 2026-11-27
- Context: Google Play prohibits apps from using the Accessibility API to "autonomously initiate, plan, and
  execute actions or decisions", enforced since 28 Jan 2026. Only deterministic, human-scripted automation
  is exempt [R7]. Competitors split device control into a separately distributed companion [R7].
- Decision:
  - **Execution channels in the `play` flavor:** the IME's own `InputConnection` (we *are* the keyboard
    in the focused field), `performEditorAction`, newline-as-send when the app is configured that way,
    and notification `RemoteInput` for background chats [R7]. The Accessibility service is
    **read-only** (context), declared and disclosed, and is never used to click or act.
  - Where sending requires a UI click, the `play` flavor degrades to **Draft** (user taps send).
  - **`full` flavor** (direct/alternative distribution) adds the `:feature-a11y-actions` module
    (focus moves, send-button clicks) behind the same guardrails.
  - Autonomy defaults to *Draft*. *Auto* needs per-app opt-in plus the informed consent screen.
- Consequences: (+) Play-distributable core product. (−) Some fully hands-free flows exist only in the
  `full` flavor. (−) We must keep the read-only claim true and auditable: the module boundary and Gradle
  flavor guarantee the `play` APK contains no action code.
