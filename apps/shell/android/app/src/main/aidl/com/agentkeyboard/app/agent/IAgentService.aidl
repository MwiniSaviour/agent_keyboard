// IME / companion → :agent process (ADR-0003). Payloads are v1 bridge envelopes (SPEC §8), so the
// AIDL surface never changes when messages are added; only internal host signals get methods.
package com.agentkeyboard.app.agent;

import com.agentkeyboard.app.agent.IAgentCallback;

interface IAgentService {
    /** Protocol major spoken by this service; clients refuse to talk across majors. */
    int protocolVersion();

    /**
     * Deliver one shell envelope (command or request). Replies/events arrive via IAgentCallback.
     * [origin] is "ime" or "companion": replies and run events go back to that client only, and the
     * focused-field gates apply to IME work only.
     */
    oneway void post(String envelopeJson, String origin);

    /** The IME saw new clipboard text (only the default IME may read the clipboard on Android 10+). */
    oneway void recordClipboard(String text, boolean sensitiveSource);

    /** The focused field changed; scopes context snapshots and autonomy policy lookups. */
    oneway void focusChanged(String fieldContextJson);

    /** Append one autonomy audit record (FR-AU-06). The service owns the append-only table. */
    oneway void appendAudit(String auditJson);

    /**
     * Autopilot's RemoteInput send path (SPEC §11.6.3 strategy 3) for a field that can't send itself.
     * :agent re-checks consent and the conversation; the outcome arrives as onRemoteReplyResult.
     */
    oneway void remoteReply(String requestId, String conversationKey, String text);

    /**
     * Companion only: install a model or signed catalog the user picked with the system file picker
     * (offline flavor, SPEC §17). The descriptor is read-only; :agent verifies the bytes against the
     * signed catalog before anything is used. Progress arrives as models.changed.
     */
    oneway void importModel(in ParcelFileDescriptor file, String name, long sizeBytes);

    void register(IAgentCallback callback, String origin);
    void unregister(IAgentCallback callback);
}
