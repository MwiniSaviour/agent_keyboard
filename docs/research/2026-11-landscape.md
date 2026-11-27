# Research Notes — On-device Agent Keyboard (Nov 2026)

> Status: input to `docs/SPEC.md`. Every claim that drives an architectural decision is cited.
> Numbers are vendor/community reported; they are **hypotheses to be re-measured** on our
> reference devices (SPEC §19.4) before any decision becomes irreversible.

## R1. Small LLMs that fit a 4 GB phone

| Finding | Source |
|---|---|
| On a 4–6 GB phone, practical models are 0.5–1B params at 4-bit (Qwen3 0.6B, Gemma 3 1B, LFM2 350M–1.2B). Rule of thumb ≈0.5–0.6 GB per 1B params (4-bit) plus a KV cache that grows with context. | [promptquorum — Local AI on Android 2026](https://www.promptquorum.com/power-local-llm/local-ai-on-android-guide), [localaimaster](https://localaimaster.com/blog/run-llm-on-phone) |
| Gemma 3 1B Q4 ≈ 720 MB file, "Google states it runs on devices with as little as 4GB RAM", ~10–15 tok/s on older 4 GB Android. | [promptquorum — mobile models](https://www.promptquorum.com/power-local-llm/mobile-llm-models-phi4-gemma-smollm) |
| Decode speed ≈ memory bandwidth ÷ model size, so smaller weights are faster, and a hot phone throttles. | [promptquorum — Local AI on Android 2026](https://www.promptquorum.com/power-local-llm/local-ai-on-android-guide) |

## R2. Gemma 4 / Gemma 3n and LiteRT-LM

| Finding | Source |
|---|---|
| Gemma 4 released 2 Apr 2026, **Apache-2.0**, E2B/E4B variants optimised for LiteRT-LM, function calling. | [dev.to Gemma 4 guide](https://dev.to/rahul_patwa_f99f19cd1519b/gemma-4-on-android-complete-developer-guide-to-on-device-ai-f5b), [andrew.ooo](https://andrew.ooo/posts/litert-lm-google-edge-llm-inference/) |
| Gemma 4 E2B on Galaxy S26 Ultra: file 2.58 GB; CPU 557 prefill / 46.9 decode tok/s, TTFT 1.8 s, **peak 1,733 MB**; GPU 52 decode tok/s, TTFT 0.3 s, **peak CPU memory 676 MB**. E4B: 3.65 GB, CPU peak 3,283 MB. | [itechguides E2B vs E4B](https://www.itechguides.com/gemma-4-on-mobile-which-model-to-load-e2b-vs-e4b-and-how-to-integrate-it-on-android/), [openally](https://openally.ai/blog/gemma-4-android-litert-lm) |
| Mid-range (Snapdragon 7s Gen 3) CPU: ~8–12 decode tok/s for E2B. SD 8 Gen 3 CPU measured 11–19 tok/s. | [dev.to](https://dev.to/rahul_patwa_f99f19cd1519b/gemma-4-on-android-complete-developer-guide-to-on-device-ai-f5b), [HF NPU build](https://huggingface.co/Fertroll10/gemma-4-E2B-it-litert-lm-npu) |
| Multi-Token Prediction: up to 2.2× decode speedup on mobile GPU, 1.5× on CPU. | [itechguides](https://www.itechguides.com/gemma-4-on-mobile-which-model-to-load-e2b-vs-e4b-and-how-to-integrate-it-on-android/) |
| LiteRT-LM: Google's edge runtime (.litertlm), GPU/NPU backends, vision + audio input, function calling; memory-maps embeddings; supports Gemma 3/3n/4, EmbeddingGemma, Qwen, Phi, FastVLM. | [groundy LiteRT](https://groundy.com/articles/google-litert-running-llms-your-phone-without/), [andrew.ooo](https://andrew.ooo/posts/litert-lm-google-edge-llm-inference/) |
| Gemma 3n uses Per-Layer Embeddings and conditional parameter loading (vision and audio weights skipped unless needed), so its effective footprint is smaller than the total parameter count. | [hivebook Gemma 3n](https://hivebook.wiki/wiki/gemma-3n-google-s-on-device-multimodal-model) |
| Practical gotcha: real chat history plus system prompts quickly overflow a 4K window. You have to budget context. | [openally](https://openally.ai/blog/gemma-4-android-litert-lm) |

## R3. Gemini Nano / AICore (system model)

| Finding | Source |
|---|---|
| ML Kit GenAI Prompt API: text + up to 10 images, input ≲4K tokens, **only while calling app is in the foreground**, not on unlocked bootloaders; Pixel 9+, Galaxy S25+ and other recent flagships. | [promptquorum](https://www.promptquorum.com/power-local-llm/local-ai-on-android-guide), [capgo](https://capgo.app/blog/gemini-nano-capacitor-app/) |
| On-device ADK backend has no tool-execution path. | [luciqai AICore article](https://github.com/luciqai/AICore-Experiment/blob/main/gemini-nano-article.md) |

**Implication:** an IME is never "the foreground app", so Nano is *opportunistic only*. It's usable from the companion app, but it can't sit on the keyboard's critical path. This needs to be verified on device (SPEC Open Question Q4).

## R4. Small vision-language models

| Finding | Source |
|---|---|
| SmolVLM2-256M ≈0.8 GB for one image, 500M ≈1.2 GB. They're the smallest widely used instruction VLMs as of Nov 2026. | [forasoft](https://www.forasoft.com/learn/ai-for-video-engineering/articles-ai/depth-anything-smolvlm-small-on-device-cv) |
| LFM2-VL 450M / 1.6B: native 512×512, tunable image-token budget (note: LFM Open License, not Apache). | [LFM2-VL coverage](https://americanjuggalo.com/liquid-ais-lfm2-vl-gives-smartphones-small-ai-vision-models/) |

**Implication:** pixels are expensive on 4 GB devices. Screen understanding should come from **structure first** (accessibility tree, EditorInfo, OCR), with the VLM used only for genuine images (ADR-0005).

## R5. Speech

| Finding | Source |
|---|---|
| Android benchmark (sherpa-onnx): Moonshine Tiny 27M RTF 0.05; Zipformer streaming 20M (~73 MB) RTF 0.12; Whisper Tiny RTF 0.07; Android offline SpeechRecognizer RTF 0.12. whisper.cpp tiny was 51× slower in that configuration. | [voiceping benchmark](https://voiceping.net/en/blog/research-offline-speech-transcription-benchmark/), [repo](https://github.com/voiceping-ai/android-offline-transcribe) |
| Whisper is not streaming (≥3.3 s latency for streaming hacks). For partial results use Moonshine v2 or sherpa-onnx Zipformer. | [ruoqijin ASR deep dive](https://ruoqijin.com/blog/asr-deep-dive-2025-2026), [mickeyzzc](https://blog.mickeyzzc.tech/en/posts/aihelper/on-device-voice-stack/) |
| sherpa-onnx: Apache-2.0, ASR + TTS + VAD, Android/iOS, Kotlin/Swift bindings. | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) |
| Kokoro-82M is high quality but slow on budget CPUs (2–3 min per min of audio). Piper/VITS is fast (~20 s per min on budget hardware). | [VoxSherpa](https://github.com/CodeBySonu95/VoxSherpa-TTS) |

## R6. Embeddings and retrieval

| Finding | Source |
|---|---|
| EmbeddingGemma 308M: <200 MB RAM quantised, 100+ languages, 2K context, Matryoshka 768→512→256→128, supported by LiteRT. | [InfoQ](https://www.infoq.com/news/2025/09/embedding-gemma/) |
| Existing on-device pattern: EmbeddingGemma @256-d + sqlite-vec next to the data. | [sanenotes #41](https://github.com/swiftsaneai/sanenotes/issues/41) |

## R7. Platform and policy constraints (decisive)

| Finding | Source |
|---|---|
| **Google Play Accessibility policy:** "Any use of the Accessibility API that enables an app to autonomously initiate, plan and execute actions or decisions is strictly prohibited". Deterministic, human-scripted automation is allowed. Effective/enforced 28 Jan 2026. Non-accessibility-tool apps need a declaration, store-listing disclosure and in-app consent. | [Play Console Help 10964491](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en-IN), [Play Console Help 16558241](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en), [zerotap](https://zerotap.app/agent) |
| Industry response: split into a Play app plus a separately distributed "device-control" companion. | [zerotap](https://zerotap.app/agent), [openally](https://openally.ai/blog/how-ai-agents-do-things-on-your-phone) |
| Background replies through `NotificationListenerService` + notification `RemoteInput` (the same path Wear OS and Android Auto use) work for WhatsApp, Messenger and others when the notification exposes a reply action. | [dev.to auto-responder](https://dev.to/prince_nebhwani_4ec88c602/how-i-built-an-ai-whatsapp-auto-responder-with-0-ban-risk-using-google-gemini-51ae), [smartreply-ai](https://github.com/Croxxxy-cpu/smartreply-ai-2) |
| 16 KB page-size support is mandatory for native libs (Play, Android 15+ targets; deadline extended to 31 May 2026). | [onnxruntime #26228](https://github.com/microsoft/onnxruntime/issues/26228), [medium](https://medium.com/@ahmetatalay95/google-play-16kb-page-size-requirement-what-you-need-to-do-before-november-2025-9c85831ca11f) |

## R8. Capacitor / WebView in an IME

| Finding | Source |
|---|---|
| Capacitor's `Bridge` is built around `AppCompatActivity`/`Fragment` (BridgeActivity). It can't be hosted by a `Service`. | [Bridge.java](https://github.com/ionic-team/capacitor/blob/main/android/capacitor/src/main/java/com/getcapacitor/Bridge.java) |
| `InputMethodService.onCreateInputView()` can return a WebView and call `getCurrentInputConnection()` from a JS interface. Historically people reported it as heavy and slow. | [StackOverflow 9176118](https://stackoverflow.com/questions/9176118/android-keyboard-replacement-web-view) |
| Next.js + Capacitor 8: `output: 'export'`, `images.unoptimized`, `trailingSlash: true`, `webDir: 'out'`. | [capgo guide](https://capgo.app/blog/nextjs-mobile-app-capacitor-from-scratch/) |

**Implication:** the IME needs a **custom lightweight bridge** (androidx.webkit `WebMessageListener`), pre-warmed and never torn down, plus a measured go/no-go gate with a native fallback (ADR-0002).

## R9. iOS

| Finding | Source |
|---|---|
| Keyboard extensions are memory-capped at ~60–70 MB (no official number), and network/App Group sharing needs Full Access. Microphone use from the extension is unreliable. | [Daniel Saidi, 24 Nov 2026](https://danielsaidi.com/blog/2026/11/24/some-thoughts-following-the-last-crazy-weekend-of-custom-keyboards), [dictus #555](https://github.com/getdictus/dictus-ios/issues/555), [KeyboardKit](https://keyboardkit.com/about) |

**Implication:** iOS cannot host the model, the screen reader or autonomous mode. It's a reduced, later-phase target (ADR-0001).

## R10. Keyboard core prior art

| Finding | Source |
|---|---|
| HeliBoard (AOSP/OpenBoard fork) is fully offline, but it's GPL-3.0 and glide typing needs a closed library. | [HeliBoard](https://github.com/heliborg/heliboard) |

**Implication:** don't copy GPL code into a proprietary product. AOSP LatinIME (Apache-2.0) dictionaries/algorithms are the safe reference.
