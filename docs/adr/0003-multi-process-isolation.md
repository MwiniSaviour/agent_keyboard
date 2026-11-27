# ADR-0003: Separate IME and `:agent` processes

- Status: Accepted · 2026-11-27
- Context: On a 4 GB device, a loaded LLM (0.5–1.7 GB) makes our process a prime target for the
  low-memory killer. If the model lives in the IME process, an OOM kill takes the keyboard down while the
  user is typing.
- Decision: Two processes. The **IME process** holds the InputMethodService, WebView, predictions and
  fallback keyboard (≤ 90 MB). The **`:agent` process** is a foreground service (type `specialUse`)
  with the orchestrator, inference, context observers and DB. They communicate over AIDL with `oneway` calls
  and a callback interface. The companion app talks to `:agent` through the same AIDL via a Capacitor
  plugin.
- Consequences: (+) Typing survives model OOM and native crashes. (+) Models can be unloaded or killed
  freely. (−) IPC serialization cost (negligible versus inference). (−) More complex lifecycle: the IME
  binds lazily and reconnects with backoff. The AccessibilityService and NotificationListener are
  declared with `android:process=":agent"` so context stays near the orchestrator.
