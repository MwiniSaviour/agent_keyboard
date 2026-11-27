// Compile-time stand-in for the subset of sherpa-onnx 2.0.3's Kotlin API (sherpa-onnx/kotlin-api,
// package com.k2fsa.sherpa.onnx) that ime/VoiceTyping.kt calls. Same names, parameter names and
// defaults as the tag so named arguments type-check. Never packaged: Gradle uses the release AAR.
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class EndpointRule(var mustContainNonSilence: Boolean, var minTrailingSilence: Float, var minUtteranceLength: Float)

data class EndpointConfig(
    var rule1: EndpointRule = EndpointRule(false, 2.4f, 0.0f),
    var rule2: EndpointRule = EndpointRule(true, 1.4f, 0.0f),
    var rule3: EndpointRule = EndpointRule(false, 0.0f, 20.0f),
)

data class FeatureConfig(var sampleRate: Int = 16000, var featureDim: Int = 80, var dither: Float = 0.0f)

data class OnlineTransducerModelConfig(var encoder: String = "", var decoder: String = "", var joiner: String = "")

data class OnlineModelConfig(
    var transducer: OnlineTransducerModelConfig = OnlineTransducerModelConfig(),
    var tokens: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
    var modelingUnit: String = "",
    var bpeVocab: String = "",
)

data class OnlineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OnlineModelConfig = OnlineModelConfig(),
    var endpointConfig: EndpointConfig = EndpointConfig(),
    var enableEndpoint: Boolean = true,
    var decodingMethod: String = "greedy_search",
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var blankPenalty: Float = 0.0f,
)

class OnlineRecognizerResult(val text: String, val tokens: Array<String>, val timestamps: FloatArray, val ysProbs: FloatArray)

class OnlineStream(var ptr: Long = 0) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int): Unit = TODO()
    fun inputFinished(): Unit = TODO()
    fun release(): Unit = TODO()
}

class OnlineRecognizer(assetManager: AssetManager? = null, val config: OnlineRecognizerConfig) {
    fun release(): Unit = TODO()
    fun createStream(hotwords: String = ""): OnlineStream = TODO()
    fun reset(stream: OnlineStream): Unit = TODO()
    fun decode(stream: OnlineStream): Unit = TODO()
    fun isEndpoint(stream: OnlineStream): Boolean = TODO()
    fun isReady(stream: OnlineStream): Boolean = TODO()
    fun getResult(stream: OnlineStream): OnlineRecognizerResult = TODO()
}

data class SileroVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F,
    var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 512,
    var maxSpeechDuration: Float = 5.0F,
)

data class VadModelConfig(
    var sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(),
    var sampleRate: Int = 16000,
    var numThreads: Int = 1,
    var provider: String = "cpu",
    var debug: Boolean = false,
)

class Vad(assetManager: AssetManager? = null, var config: VadModelConfig) {
    fun release(): Unit = TODO()
    fun acceptWaveform(samples: FloatArray): Unit = TODO()
    fun empty(): Boolean = TODO()
    fun pop(): Unit = TODO()
    fun isSpeechDetected(): Boolean = TODO()
    fun reset(): Unit = TODO()
}
