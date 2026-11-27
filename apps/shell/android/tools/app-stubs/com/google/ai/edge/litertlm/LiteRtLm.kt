// Compile-only stand-ins for com.google.ai.edge.litertlm:litertlm-android:0.20.0, used by
// tools/verify-app.sh (no Gradle/Maven in the sandbox). Only the subset the app calls, with the
// release's parameter names, so named arguments type-check exactly as against the real AAR.
// Source of truth: github.com/google-ai-edge/LiteRT-LM tag v0.20.0, kotlin/java/com/google/ai/edge/litertlm.
// Never packaged: Gradle resolves the real library.
package com.google.ai.edge.litertlm

sealed class Backend(val name: String) {
    data class CPU(val threadCount: Int? = null) : Backend("CPU")
    class GPU : Backend("GPU")
    data class NPU(val nativeLibraryDir: String = "") : Backend("NPU")
}

data class EngineConfig(
    val modelPath: String,
    val backend: Backend = Backend.CPU(),
    val visionBackend: Backend? = null,
    val audioBackend: Backend? = null,
    val maxNumTokens: Int? = null,
    val maxNumImages: Int? = null,
    val cacheDir: String? = null,
)

class Engine(val engineConfig: EngineConfig) : AutoCloseable {
    fun isInitialized(): Boolean = false
    fun initialize() {}
    fun createConversation(conversationConfig: ConversationConfig = ConversationConfig()): Conversation = throw UnsupportedOperationException("stub")
    override fun close() {}
}

data class SamplerConfig(val topK: Int, val topP: Double, val temperature: Double, val seed: Int = 0)

data class ThinkingConfig(val enableThinking: Boolean = true, val thinkingTokenBudget: Int = -1)

data class ConversationConfig(
    val systemInstruction: Contents? = null,
    val samplerConfig: SamplerConfig? = null,
    val prefillPrefaceOnInit: Boolean = false,
    val maxOutputToken: Int? = null,
    val thinkingConfig: ThinkingConfig? = null,
    val enableResponseFormat: Boolean = false,
)

class Contents private constructor(val text: String) {
    override fun toString() = text
    companion object {
        fun of(text: String) = Contents(text)
    }
}

class Message private constructor(private val text: String) {
    override fun toString() = text
}

interface MessageCallback {
    fun onMessage(message: Message)
    fun onDone()
    fun onError(throwable: Throwable)
}

data class ResponseFormat(val schemaOrPattern: String) {
    companion object {
        fun json(schema: String) = ResponseFormat(schema)
    }
}

class Conversation private constructor() : AutoCloseable {
    val isAlive: Boolean get() = false
    fun sendMessageAsync(
        text: String,
        callback: MessageCallback,
        extraContext: Map<String, Any> = emptyMap(),
        maxOutputToken: Int? = null,
        thinkingConfig: ThinkingConfig? = null,
        responseFormat: ResponseFormat? = null,
    ) {}
    fun cancelProcess() {}
    fun clone(): Conversation = this
    override fun close() {}
}

class LiteRtLmJniException(message: String) : RuntimeException(message)
