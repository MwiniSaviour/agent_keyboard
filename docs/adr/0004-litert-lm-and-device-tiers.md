# ADR-0004: LiteRT-LM as the single generative runtime; S/M/L device tiers

- Status: Accepted (Tier S model choice pending bake-off, SPEC §19.5) · 2026-11-27
- Context: The requirement is "minimum 4 GB RAM, low disk, lightning fast". Gemma 4 E2B (Apache-2.0)
  gives text + vision + audio in one model, but it peaks at ~1.7 GB on CPU (≈0.7 GB CPU-side on GPU) and
  is a 2.58 GB file [R2]. That's too much for a 4 GB phone where we own ~1.2 GB peak. Each extra runtime
  (llama.cpp, MLC, ONNX for LLMs) adds MBs to the APK and multiplies integration and testing cost.
- Decision:
  - **One generative runtime: LiteRT-LM** (CPU/GPU/NPU, multimodal, function calling, mmap embeddings;
    supports Gemma, Qwen, FastVLM) [R2]. sherpa-onnx is accepted separately for speech (small, purpose-built) [R5].
  - **Tiers by RAM** (`ActivityManager.MemoryInfo.totalMem`): S < 5.5 GB, M 5.5–9 GB, L ≥ 9 GB. Same
    features on every tier; models differ (SPEC §12.1). The user can override downwards.
  - **One resident generative model at a time.** On Tier S, vision is an on-demand exclusive swap.
  - AICore/Gemini Nano is opportunistic and companion-only (foreground restriction [R3]).
- Consequences: (+) Small APK, one integration to optimise, clear memory accounting. (−) Models
  outside LiteRT-LM need conversion; if a better Tier S model appears only as GGUF, we revisit with a new ADR.
