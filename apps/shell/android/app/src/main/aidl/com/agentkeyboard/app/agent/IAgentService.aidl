// IME / companion → :agent process (ADR-0003). Payloads are v1 bridge envelopes (SPEC §8), so the
// AIDL surface never changes when messages are added; only internal host signals get methods.
package com.agentkeyboard.app.agent;

import com.agentkeyboard.app.agent.IAgentCallback;

interface IAgentService {
    /** Protocol major spoken by this service; clients refuse to talk across majors. */
    int protocolVersion();

    /** Deliver one shell envelope (command or request). Replies/events arrive via IAgentCallback. */
    oneway void post(String envelopeJson);

    /** The IME saw new clipboard text (only the default IME may read the clipboard on Android 10+). */
    oneway void recordClipboard(String text, boolean sensitiveSource);

    /** The focused field changed; scopes context snapshots and autonomy policy lookups. */
    oneway void focusChanged(String fieldContextJson);

    /** Append one autonomy audit record (FR-AU-06). The service owns the append-only table. */
    oneway void appendAudit(String auditJson);

    void register(IAgentCallback callback);
    void unregister(IAgentCallback callback);
}
