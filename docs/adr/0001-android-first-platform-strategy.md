# ADR-0001: Android-first; reduced iOS

- Status: Accepted · 2026-11-27
- Context: The product's core value (whole-screen understanding, on-device LLM in the keyboard,
  autonomous replies) depends on OS capabilities. Android provides an IME with a full
  `InputConnection`, AccessibilityService (read), NotificationListenerService + RemoteInput, and enough
  memory for a separate model process. iOS keyboard extensions are capped at ~60–70 MB, need Full
  Access for networking and App Group sharing, can't use the microphone reliably and can't read other
  apps' screens [R9].
- Decision: Android 10+ arm64 is the only v1 target. iOS ships in Phase 7 as a reduced keyboard
  (typing + refinement via system Foundation Models where available). The web build exists solely as a
  dev/QA playground with a mock bridge.
- Consequences: (+) Engineering focuses on one platform, so we can go deeper. (−) iOS users wait, and
  marketing must not promise iOS parity. The shell keeps platform-specific code behind `NativeBridge`, so
  the UI is reusable on iOS.
