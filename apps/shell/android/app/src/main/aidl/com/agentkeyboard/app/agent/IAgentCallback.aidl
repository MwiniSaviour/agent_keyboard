// :agent process → IME / companion. One raw v1 envelope per call (results, errors, events), plus a
// typed signal for inbound chat messages that drive the autonomy state machine in the IME.
package com.agentkeyboard.app.agent;

interface IAgentCallback {
    oneway void onEnvelope(String envelopeJson);

    /** A new message arrived in a conversation (notification listener, already privacy-filtered). */
    oneway void onInbound(String packageName, String conversationKey, String messageJson, boolean remoteInputAvailable);

    /** Outcome of IAgentService.remoteReply. */
    oneway void onRemoteReplyResult(String requestId, boolean ok);
}
