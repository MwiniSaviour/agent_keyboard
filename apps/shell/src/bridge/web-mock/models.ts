/**
 * Simulated ModelService (ADR-0011) for the browser playground: the same `models.*` messages, states
 * and rules as `core.models.ModelService`, with a timer instead of HTTP. Packs mirror
 * `models/catalog.json` (kept in sync by models.test.ts).
 */
import type { DeviceTier, ModelPack, ModelsStatus } from '@ak/protocol';

export interface MockModelEntry {
  id: string;
  kind: ModelPack['kind'];
  name: string;
  role: string;
  approxBytes: number;
  license: string;
  licenseUrl?: string;
  tiers: DeviceTier[];
  coreFor: DeviceTier[];
}

/** Same ids, names, tiers and licences as models/catalog.json. */
export const MOCK_CATALOG: MockModelEntry[] = [
  {
    id: 'gemma-3-1b-it-int4',
    kind: 'llm',
    name: 'Gemma 3 1B',
    role: 'Replies, rewriting, explanations and definitions',
    approxBytes: 584417280,
    license: 'Gemma Terms of Use',
    licenseUrl: 'https://ai.google.dev/gemma/terms',
    tiers: ['S'],
    coreFor: ['S'],
  },
  {
    id: 'gemma-4-e2b-it',
    kind: 'llm',
    name: 'Gemma 4 E2B',
    role: 'Text, vision and audio in one model',
    approxBytes: 2588147712,
    license: 'Apache-2.0',
    licenseUrl: 'https://www.apache.org/licenses/LICENSE-2.0',
    tiers: ['M', 'L'],
    coreFor: ['M', 'L'],
  },
  {
    id: 'gemma-4-e4b-it',
    kind: 'llm',
    name: 'Gemma 4 E4B',
    role: 'Highest-quality text and vision (optional)',
    approxBytes: 3659530240,
    license: 'Apache-2.0',
    licenseUrl: 'https://www.apache.org/licenses/LICENSE-2.0',
    tiers: ['L'],
    coreFor: [],
  },
];

/** Simulated link speed: fast enough to demo, slow enough to see progress and pause. */
const BYTES_PER_TICK = 96 * 1024 * 1024;
const TICK_MS = 250;
const FREE_BYTES = 24 * 1024 ** 3;

interface PackState {
  state: ModelPack['state'];
  received: number;
  detail?: string;
}

export class MockModels {
  private tier: DeviceTier;
  private packs = new Map<string, PackState>();
  private queue: string[] = [];
  private timer: ReturnType<typeof setInterval> | null = null;
  private wifiOnly = true;
  private resident: ModelsStatus['resident'];

  constructor(tier: DeviceTier, private readonly onChange: (s: ModelsStatus) => void) {
    this.tier = tier;
  }

  private entries(): MockModelEntry[] {
    return MOCK_CATALOG.filter((e) => e.tiers.includes(this.tier) || this.packs.get(e.id)?.state === 'installed');
  }

  status(): ModelsStatus {
    const packs = this.entries().map((e): ModelPack => {
      const s = this.packs.get(e.id) ?? { state: 'available', received: 0 };
      return {
        id: e.id,
        name: e.name,
        kind: e.kind,
        role: e.role,
        sizeBytes: e.approxBytes,
        license: e.license,
        ...(e.licenseUrl ? { licenseUrl: e.licenseUrl } : {}),
        core: e.coreFor.includes(this.tier),
        state: s.state,
        receivedBytes: s.state === 'installed' ? e.approxBytes : s.received,
        ...(s.detail ? { detail: s.detail } : {}),
      };
    });
    packs.sort((a, b) => Number(b.core) - Number(a.core));
    const used = packs.reduce((n, p) => n + p.receivedBytes, 0);
    return {
      tier: this.tier,
      source: 'download',
      catalog: 'ready',
      packs,
      freeBytes: FREE_BYTES - used,
      wifiOnly: this.wifiOnly,
      ...(this.resident ? { resident: this.resident } : {}),
    };
  }

  hasTextModel(): boolean {
    return [...this.packs.values()].some((p) => p.state === 'installed');
  }

  /** Like native: the first run after install loads the model (CPU here) and keeps it resident. */
  touch(): void {
    if (this.resident) return;
    const installed = this.entries().find((e) => this.packs.get(e.id)?.state === 'installed');
    if (!installed) return;
    this.resident = { id: installed.id, backend: 'cpu' };
    this.emit();
  }

  download(ids: string[]): void {
    for (const id of ids) {
      const e = MOCK_CATALOG.find((x) => x.id === id);
      if (!e || !e.tiers.includes(this.tier)) continue; // other tiers' packs are refused, as natively
      if (this.packs.get(id)?.state === 'installed' || this.queue.includes(id)) continue;
      this.queue.push(id);
      const prev = this.packs.get(id);
      this.packs.set(id, { state: 'queued', received: prev?.received ?? 0 });
    }
    this.kick();
    this.emit();
  }

  cancel(id: string): void {
    const s = this.packs.get(id);
    if (!s || (s.state !== 'queued' && s.state !== 'downloading')) return;
    this.queue = this.queue.filter((q) => q !== id);
    this.packs.set(id, { state: 'paused', received: s.received, detail: 'Paused' });
    this.kick();
    this.emit();
  }

  delete(id: string): void {
    this.queue = this.queue.filter((q) => q !== id);
    this.packs.delete(id);
    if (this.resident?.id === id) this.resident = undefined;
    this.kick();
    this.emit();
  }

  setWifiOnly(v: boolean): void {
    this.wifiOnly = v;
    this.emit();
  }

  dispose(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  private kick(): void {
    const id = this.queue[0];
    if (!id) {
      this.dispose();
      return;
    }
    const s = this.packs.get(id)!;
    if (s.state !== 'downloading') this.packs.set(id, { state: 'downloading', received: s.received });
    if (this.timer) return;
    this.timer = setInterval(() => this.tick(), TICK_MS);
  }

  private tick(): void {
    const id = this.queue[0];
    const e = id ? MOCK_CATALOG.find((x) => x.id === id) : undefined;
    const s = id ? this.packs.get(id) : undefined;
    if (!id || !e || !s || s.state !== 'downloading') return this.kick();
    const received = Math.min(e.approxBytes, s.received + BYTES_PER_TICK);
    if (received < e.approxBytes) {
      this.packs.set(id, { state: 'downloading', received });
    } else {
      // Verified (SHA-256 against the signed catalog) and moved into place.
      this.packs.set(id, { state: 'installed', received });
      this.queue.shift();
      this.kick();
    }
    this.emit();
  }

  private emit(): void {
    this.onChange(this.status());
  }
}
