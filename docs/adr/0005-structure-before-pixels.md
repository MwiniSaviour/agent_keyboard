# ADR-0005: Context from structure before pixels

- Status: Accepted · 2026-11-27
- Context: "See and understand the full screen" could be implemented naïvely as *screenshot → VLM*
  on every request. On Tier S that costs a model swap, 1–6 s and ~0.5–1 GB [R4], and it's also worse for
  text-heavy screens than reading the text directly.
- Decision: The Context Engine climbs a **cost ladder** (SPEC §10.1): EditorInfo/surrounding text →
  notifications → accessibility outline → OCR → VLM. It stops at the first rung that satisfies the
  skill's declared `contextNeeds`. VLM is used for genuine images (photos, charts, memes), never to
  read text that the a11y tree or OCR already gives us.
- Consequences: (+) Most context requests cost < 60 ms and no extra RAM. (+) Better grounding (exact
  strings, roles, field types). (−) Per-app heuristics are needed for chat/form extraction, and app UI changes
  can break them. Mitigated by notification-first chat history and an adapter registry with telemetry.
