'use client';
/**
 * Models (SPEC §12.1, §12.4). On a device, ModelManager reports real state via the bridge; in the
 * browser we show the tier plan for the detected (or chosen) RAM so the catalog is reviewable.
 */
import { useEffect, useState } from 'react';
import type { DeviceTier } from '@ak/protocol';
import { CompanionShell } from './CompanionShell';
import css from './companion.module.css';

interface Pack {
  id: string;
  name: string;
  role: string;
  mb: number;
  required: boolean;
  note?: string;
}

const PACKS: Record<DeviceTier, Pack[]> = {
  S: [
    { id: 'llm-s', name: 'Gemma 3 1B · int4', role: 'Text: replies, refine, explain', mb: 680, required: true, note: 'Bake-off vs Qwen3-0.6B (§19.5)' },
    { id: 'emb', name: 'EmbeddingGemma 300M · int8 @256d', role: 'Memory & learning resources', mb: 180, required: true },
    { id: 'stt-s', name: 'Zipformer streaming 20M (EN)', role: 'Voice typing', mb: 73, required: true },
    { id: 'dict', name: 'English dictionary + n-gram', role: 'Suggestions, autocorrect', mb: 12, required: true },
    { id: 'vlm-s', name: 'FastVLM 0.5B', role: 'Image understanding (on demand)', mb: 560, required: false, note: 'Loaded exclusively; OCR covers most screens' },
  ],
  M: [
    { id: 'llm-m', name: 'Gemma 4 E2B · int4', role: 'Text, vision and audio — one model', mb: 2580, required: true },
    { id: 'emb', name: 'EmbeddingGemma 300M · int8', role: 'Memory & learning resources', mb: 180, required: true },
    { id: 'stt-m', name: 'Zipformer streaming 20M', role: 'Voice typing (streaming)', mb: 73, required: true },
    { id: 'dict', name: 'English dictionary + n-gram', role: 'Suggestions, autocorrect', mb: 12, required: true },
    { id: 'tts-piper', name: 'Piper voice (EN)', role: 'Natural read-aloud', mb: 63, required: false },
  ],
  L: [
    { id: 'llm-l', name: 'Gemma 4 E4B · int4', role: 'Highest quality text & vision', mb: 3650, required: false, note: 'Optional; E2B stays default' },
    { id: 'llm-m', name: 'Gemma 4 E2B · int4', role: 'Text, vision and audio', mb: 2580, required: true },
    { id: 'emb', name: 'EmbeddingGemma 300M · int8', role: 'Memory & learning resources', mb: 180, required: true },
    { id: 'stt-l', name: 'Moonshine v2 base', role: 'Voice typing (higher accuracy)', mb: 240, required: true },
    { id: 'tts-kokoro', name: 'Kokoro 82M', role: 'Studio-quality read-aloud', mb: 330, required: false },
  ],
};

const TIER_LABEL: Record<DeviceTier, string> = { S: '4–5 GB RAM', M: '6–8 GB RAM', L: '9 GB+ RAM' };

function detectTier(): DeviceTier {
  const gb = (navigator as Navigator & { deviceMemory?: number }).deviceMemory ?? 4;
  return gb < 5.5 ? 'S' : gb < 9 ? 'M' : 'L';
}

const fmt = (mb: number) => (mb >= 1000 ? `${(mb / 1000).toFixed(2)} GB` : `${mb} MB`);

export function ModelsView() {
  const [tier, setTier] = useState<DeviceTier>('S');
  useEffect(() => setTier(detectTier()), []);
  const packs = PACKS[tier];
  const core = packs.filter((p) => p.required).reduce((n, p) => n + p.mb, 0);

  return (
    <CompanionShell title="Models" active="/models/">
      <section className={css.card}>
        <div className={css.row} style={{ justifyContent: 'space-between' }}>
          <div>
            <div className={css.name}>Device tier {tier}</div>
            <div className={css.muted}>{TIER_LABEL[tier]} · everything runs on this phone, offline</div>
          </div>
          <div className={css.seg}>
            {(['S', 'M', 'L'] as DeviceTier[]).map((t) => (
              <button type="button" key={t} aria-pressed={tier === t} onClick={() => setTier(t)}>
                {t}
              </button>
            ))}
          </div>
        </div>
        <div className={css.muted} style={{ marginTop: 12 }}>
          Core download {fmt(core)} · one generative model in memory at a time
        </div>
        <div className={css.meter}>
          <i style={{ width: `${Math.min(100, (core / 3200) * 100)}%` }} />
        </div>
      </section>

      <div className={css.list}>
        {packs.map((p) => (
          <div key={p.id} className={css.item}>
            <span className={css.grow}>
              <div className={css.name}>{p.name}</div>
              <div className={css.muted}>
                {p.role} · {fmt(p.mb)}
              </div>
              {p.note ? <div className={css.muted}>{p.note}</div> : null}
            </span>
            <span className={css.pill}>{p.required ? 'Core' : 'Optional'}</span>
          </div>
        ))}
      </div>
      <p className={css.help} style={{ marginTop: 14 }}>
        Packs download over Wi-Fi, are verified (SHA-256 + signed manifest) and memory-mapped from internal storage. In this
        browser preview, AI responses are simulated.
      </p>
    </CompanionShell>
  );
}
