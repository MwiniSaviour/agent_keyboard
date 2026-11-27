/**
 * Simulated ModelService (ADR-0011, ADR-0012) for the browser playground: the same `models.*`
 * messages, states and rules as `core.models.ModelService` (recommendations per device, choosing a
 * model, finding models already on the phone), with timers instead of HTTP and storage. Packs mirror
 * `models/catalog.json` (kept in sync by models.test.ts).
 */
import type { DeviceTier, FoundModel, ModelFit, ModelKind, ModelPack, ModelScan, ModelsStatus } from '@ak/protocol';

export interface MockModelEntry {
  id: string;
  kind: ModelKind;
  name: string;
  role: string;
  /** Total download size (the catalog's `approxBytes`, or the sum over its files). */
  approxBytes: number;
  license: string;
  licenseUrl?: string;
  tiers: DeviceTier[];
  coreFor: DeviceTier[];
  ramMb: number;
  quality: number;
  backends: Array<'npu' | 'gpu' | 'cpu'>;
  custom?: boolean;
}

/** Same ids, names, licences, memory and quality as models/catalog.json. */
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
    ramMb: 900,
    quality: 40,
    backends: ['cpu', 'gpu'],
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
    ramMb: 1500,
    quality: 70,
    backends: ['cpu', 'gpu'],
  },
  {
    id: 'gemma-4-e4b-it',
    kind: 'llm',
    name: 'Gemma 4 E4B',
    role: 'Highest-quality text and vision',
    approxBytes: 3659530240,
    license: 'Apache-2.0',
    licenseUrl: 'https://www.apache.org/licenses/LICENSE-2.0',
    tiers: ['L'],
    coreFor: [],
    ramMb: 2400,
    quality: 80,
    backends: ['cpu', 'gpu'],
  },
  {
    id: 'zipformer-en-20m',
    kind: 'stt',
    name: 'Zipformer 20M (English)',
    role: 'Voice typing on this phone: fast and light',
    approxBytes: 45996814,
    license: 'Apache-2.0',
    licenseUrl: 'https://www.apache.org/licenses/LICENSE-2.0',
    tiers: ['S', 'M', 'L'],
    coreFor: ['S'],
    ramMb: 110,
    quality: 45,
    backends: ['cpu'],
  },
  {
    id: 'zipformer-en-2023-06-26',
    kind: 'stt',
    name: 'Zipformer (English)',
    role: 'Voice typing on this phone: more accurate',
    approxBytes: 75076366,
    license: 'Apache-2.0',
    licenseUrl: 'https://www.apache.org/licenses/LICENSE-2.0',
    tiers: ['M', 'L'],
    coreFor: ['M', 'L'],
    ramMb: 260,
    quality: 70,
    backends: ['cpu'],
  },
];

/** What the native side reads from ActivityManager / the SoC (AndroidSpecs). */
export interface MockDevice {
  totalRamMb: number;
  cpuCores: number;
  gpu: boolean;
  lowRam?: boolean;
  soc?: string;
}

/** A typical "8 GB" mid-range phone (Android reports ~7.3 GB). */
export const MOCK_DEVICE: MockDevice = { totalRamMb: 7450, cpuCores: 8, gpu: true, soc: 'SM7550' };

// ───────────── recommendations: a port of core.models.Recommender ─────────────

const MIN_TOKENS_PER_SEC = 10;
const comfortableMb = (d: MockDevice) => (d.lowRam ? Math.floor(d.totalRamMb / 4) : Math.max(600, Math.min(Math.round(d.totalRamMb * 0.4), d.totalRamMb - 2500)));
const hardLimitMb = (d: MockDevice) => (d.lowRam ? Math.floor(d.totalRamMb / 3) : Math.min(Math.round(d.totalRamMb * 0.55), d.totalRamMb - 1800));
const generative = (e: MockModelEntry) => e.kind === 'llm' || e.kind === 'vlm';

export function estimateTokensPerSec(e: MockModelEntry, d: MockDevice): number {
  let factor = 18;
  if (d.totalRamMb >= 11000 && d.cpuCores >= 8) factor *= 1.35;
  else if (d.totalRamMb >= 7000) factor *= 1.15;
  else if (d.totalRamMb < 5000) factor *= 0.7;
  if (d.gpu && e.backends.includes('gpu')) factor *= 1.4;
  return factor / Math.max(0.2, e.ramMb / 1000);
}

export function marketedGb(mb: number): string {
  const g = mb / 1024;
  return `${[2, 3, 4, 6, 8, 12, 16, 24].find((s) => s >= g - 0.05) ?? Math.round(g)} GB`;
}

const ramNeededMb = (e: MockModelEntry) => Math.max(Math.round(e.ramMb / 0.55), e.ramMb + 1800);

export function advise(entries: MockModelEntry[], d: MockDevice): Map<string, { fit: ModelFit; detail: string }> {
  const comfortable = comfortableMb(d);
  const hard = hardLimitMb(d);
  const out = new Map<string, { fit: ModelFit; detail: string }>();
  const groups = new Map<string, MockModelEntry[]>();
  for (const e of entries) {
    const k = generative(e) ? 'gen' : e.kind;
    groups.set(k, [...(groups.get(k) ?? []), e]);
  }
  for (const group of groups.values()) {
    const speed = new Map(group.map((e) => [e.id, generative(e) ? estimateTokensPerSec(e, d) : null]));
    const fast = (e: MockModelEntry) => (speed.get(e.id) ?? MIN_TOKENS_PER_SEC) >= MIN_TOKENS_PER_SEC;
    const candidates = group.filter((e) => !e.custom && e.ramMb <= hard);
    const smooth = candidates.filter((e) => e.ramMb <= comfortable && fast(e));
    const pick =
      [...smooth].sort((a, b) => b.quality - a.quality || a.ramMb - b.ramMb)[0] ?? [...candidates].sort((a, b) => a.ramMb - b.ramMb)[0];
    for (const e of group) {
      const tps = speed.get(e.id);
      const words = tps != null ? ` (about ${Math.max(1, Math.round(tps * 0.75))} words a second, estimated)` : '';
      const noun = e.kind === 'stt' ? 'voice model' : 'model';
      out.set(
        e.id,
        e.ramMb > hard
          ? { fit: 'too_big', detail: `Needs more memory than this phone can spare (for phones with ${marketedGb(ramNeededMb(e))}+ RAM)` }
          : e.id === pick?.id && e.ramMb <= comfortable && fast(e)
            ? { fit: 'recommended', detail: `Best for this phone: the smartest ${noun} that runs smoothly with ${marketedGb(d.totalRamMb)} of RAM${words}` }
            : e.id === pick?.id
              ? { fit: 'recommended', detail: `The lightest ${noun} that fits this phone; expect slower replies${words}` }
              : e.ramMb > comfortable
                ? { fit: 'slow', detail: `Fits, but may close other apps while it works${words}` }
                : !fast(e)
                  ? { fit: 'slow', detail: `Smarter, but slow on this phone${words}` }
                  : e.custom
                    ? { fit: 'good', detail: `Unverified model from this phone${words}` }
                    : pick && e.quality < pick.quality
                      ? { fit: 'good', detail: `Faster and lighter, less capable than ${pick.name}${words}` }
                      : { fit: 'good', detail: `Runs well on this phone${words}` },
      );
    }
  }
  return out;
}

const FIT_ORDER: Array<ModelFit | undefined> = ['recommended', 'good', 'slow', undefined, 'too_big'];
const KIND_ORDER: ModelKind[] = ['llm', 'vlm', 'stt', 'embedding', 'tts'];
const jobOf = (e: MockModelEntry) => (e.kind === 'stt' ? 'transcribe' : 'text');

/** What the simulated scan "finds" in Download: a catalog file (verified by hash) and an unknown model. */
const MOCK_FOUND: FoundModel[] = [
  { id: 'found-gemma-3-1b-it-int4', name: 'gemma3-1b-it-int4.litertlm', kind: 'llm', sizeBytes: 584417280, where: 'Download', verified: true, entryId: 'gemma-3-1b-it-int4' },
  { id: 'custom-1d6xk-qwen3-0.6b-q8', name: 'Qwen3-0.6B-q8.litertlm', kind: 'llm', sizeBytes: 614236160, where: 'Download', verified: false },
];

/** Simulated link speed: fast enough to demo, slow enough to see progress and pause. */
const BYTES_PER_TICK = 96 * 1024 * 1024;
const TICK_MS = 250;
const SCAN_MS = 700;
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
  private custom: MockModelEntry[] = [];
  private active = new Map<string, string>();
  private found: FoundModel[] = [];
  private scan: ModelScan = { state: 'idle', access: 'app', folders: 0, allFilesOption: false };
  private note: string | undefined;
  micGranted = true;

  constructor(
    tier: DeviceTier,
    private readonly onChange: (s: ModelsStatus) => void,
    private readonly device: MockDevice = MOCK_DEVICE,
  ) {
    this.tier = tier;
  }

  private entries(): MockModelEntry[] {
    return [...MOCK_CATALOG, ...this.custom];
  }

  private entry(id: string): MockModelEntry | undefined {
    return this.entries().find((e) => e.id === id);
  }

  private installed(e: MockModelEntry): boolean {
    return this.packs.get(e.id)?.state === 'installed';
  }

  /** The installed model used for [job]: the user's choice, else the best advised, then the smartest. */
  private chosen(job: string): MockModelEntry | undefined {
    const adv = advise(this.entries(), this.device);
    return this.entries()
      .filter((e) => jobOf(e) === job && this.installed(e))
      .sort(
        (a, b) =>
          Number(this.active.get(job) === b.id) - Number(this.active.get(job) === a.id) ||
          FIT_ORDER.indexOf(adv.get(a.id)?.fit) - FIT_ORDER.indexOf(adv.get(b.id)?.fit) ||
          b.quality - a.quality,
      )[0];
  }

  status(): ModelsStatus {
    const adv = advise(this.entries(), this.device);
    const chosen = new Set([this.chosen('text')?.id, this.chosen('transcribe')?.id]);
    const packs = this.entries().map((e): ModelPack => {
      const s = this.packs.get(e.id) ?? { state: 'available', received: 0 };
      const a = adv.get(e.id);
      return {
        id: e.id,
        name: e.name,
        kind: e.kind,
        role: e.role,
        sizeBytes: e.approxBytes,
        license: e.license,
        ...(e.licenseUrl ? { licenseUrl: e.licenseUrl } : {}),
        core: a?.fit === 'recommended',
        state: s.state,
        receivedBytes: s.state === 'installed' ? e.approxBytes : s.received,
        ...(s.detail ? { detail: s.detail } : {}),
        ...(a ? { fit: a.fit, fitDetail: a.detail } : {}),
        ...(chosen.has(e.id) ? { active: true } : {}),
        ...(e.custom ? { custom: true } : {}),
      };
    });
    packs.sort(
      (a, b) =>
        FIT_ORDER.indexOf(a.fit) - FIT_ORDER.indexOf(b.fit) ||
        KIND_ORDER.indexOf(a.kind) - KIND_ORDER.indexOf(b.kind) ||
        (this.entry(b.id)?.quality ?? 0) - (this.entry(a.id)?.quality ?? 0),
    );
    const used = packs.reduce((n, p) => n + p.receivedBytes, 0);
    return {
      tier: this.tier,
      source: 'download',
      catalog: 'ready',
      ...(this.note ? { catalogDetail: this.note } : {}),
      packs,
      freeBytes: FREE_BYTES - used,
      wifiOnly: this.wifiOnly,
      ...(this.resident ? { resident: this.resident } : {}),
      device: { ramMb: this.device.totalRamMb, ramLabel: marketedGb(this.device.totalRamMb), gpu: this.device.gpu, ...(this.device.soc ? { soc: this.device.soc } : {}) },
      ...(this.found.length > 0 ? { found: this.found } : {}),
      scan: this.scan,
      micGranted: this.micGranted,
    };
  }

  hasTextModel(): boolean {
    return this.chosen('text') != null;
  }

  hasVoiceModel(): boolean {
    return this.chosen('transcribe') != null;
  }

  /** Like native: the first run after install loads the chosen model (CPU here) and keeps it resident. */
  touch(): void {
    const text = this.chosen('text');
    if (!text || this.resident?.id === text.id) return;
    this.resident = { id: text.id, backend: 'cpu' };
    this.emit();
  }

  download(ids: string[]): void {
    const adv = advise(this.entries(), this.device);
    for (const id of ids) {
      const e = MOCK_CATALOG.find((x) => x.id === id);
      if (!e || adv.get(id)?.fit === 'too_big') continue; // refused, as natively
      if (this.packs.get(id)?.state === 'installed' || this.queue.includes(id)) continue;
      this.queue.push(id);
      const prev = this.packs.get(id);
      this.packs.set(id, { state: 'queued', received: prev?.received ?? 0 });
    }
    this.kick();
    this.emit();
  }

  use(id: string): void {
    const e = this.entry(id);
    if (!e || !this.installed(e)) return;
    this.active.set(jobOf(e), id);
    if (jobOf(e) === 'text' && this.resident && this.resident.id !== id) this.resident = undefined; // swapped on next run
    this.emit();
  }

  /** Simulates a scan of Download (and a picked folder: same result, one more granted folder). */
  scanNow(pickFolder = false): void {
    if (this.scan.state === 'scanning') return;
    this.scan = { ...this.scan, state: 'scanning', ...(pickFolder ? { access: 'folders', folders: this.scan.folders + 1 } : {}) };
    this.emit();
    setTimeout(() => {
      const have = new Set(this.entries().filter((e) => this.installed(e)).map((e) => e.id));
      this.found = MOCK_FOUND.filter((f) => !have.has(f.entryId ?? f.id));
      this.scan = { ...this.scan, state: 'idle' };
      this.emit();
    }, SCAN_MS);
  }

  adopt(id: string, trust: boolean): void {
    this.note = undefined;
    const f = this.found.find((x) => x.id === id);
    if (!f) return this.emit();
    if (!f.verified && !trust) {
      this.note = `Confirm that you trust ${f.name} to use it`;
      return this.emit();
    }
    this.found = this.found.filter((x) => x.id !== id);
    if (f.entryId) {
      // Copied from the phone and hashed against the signed catalog: no download needed.
      this.packs.set(f.entryId, { state: 'installed', received: f.sizeBytes });
    } else {
      this.custom.push({
        id: f.id,
        kind: f.kind,
        name: f.name.replace(/\.litertlm$/, ''),
        role: 'Your model, found on this phone (unverified)',
        approxBytes: f.sizeBytes,
        license: 'Unknown: check where it came from',
        tiers: [],
        coreFor: [],
        ramMb: Math.round((f.sizeBytes / 1e6) * 1.1 + 300),
        quality: 50,
        backends: ['cpu', 'gpu'],
        custom: true,
      });
      this.packs.set(f.id, { state: 'installed', received: f.sizeBytes });
    }
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
    this.custom = this.custom.filter((c) => c.id !== id);
    for (const [job, a] of this.active) if (a === id) this.active.delete(job);
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
      this.found = this.found.filter((f) => f.entryId !== id);
      this.queue.shift();
      this.kick();
    }
    this.emit();
  }

  private emit(): void {
    this.onChange(this.status());
  }
}
