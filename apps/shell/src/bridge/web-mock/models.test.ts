/**
 * The simulated ModelService must follow the native rules (ADR-0011) and list exactly the packs the
 * real catalog lists, so the playground never demos a model the app can't install.
 */
import { readFileSync } from 'node:fs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ModelsStatus, NativeBridge } from '@ak/protocol';
import { catalogNotice, foundAction, packAction, scanScope, switchAction } from '@/companion/ModelsView';
import { MOCK_CATALOG, MOCK_DEVICE, MockModels, advise, estimateTokensPerSec, marketedGb, type MockDevice } from './models';

const catalog = JSON.parse(readFileSync(new URL('../../../../../models/catalog.json', import.meta.url), 'utf8')) as {
  models: Array<Record<string, unknown>>;
};

const KEYS = ['id', 'kind', 'name', 'role', 'license', 'licenseUrl', 'tiers', 'coreFor', 'ramMb', 'quality', 'backends'];

describe('mock model catalog', () => {
  it('mirrors models/catalog.json', () => {
    const pick = (m: object) => ({
      ...Object.fromEntries(Object.entries(m).filter(([k]) => KEYS.includes(k))),
      quality: (m as { quality?: number }).quality ?? 50,
    });
    expect(MOCK_CATALOG.map(pick)).toEqual(catalog.models.map(pick));
    // Download size: the single file's hint, or the sum over a multi-file model's files.
    const size = (m: Record<string, unknown>) =>
      (m.approxBytes as number | undefined) ?? (m.files as Array<{ approxBytes: number }>).reduce((n, f) => n + f.approxBytes, 0);
    expect(MOCK_CATALOG.map((m) => m.approxBytes)).toEqual(catalog.models.map(size));
  });
});

/** Same cases as core RecommenderTest: the playground must recommend what the phone would. */
describe('mock recommendations', () => {
  const fits = (d: MockDevice) => Object.fromEntries([...advise(MOCK_CATALOG, d)].map(([id, a]) => [id, a.fit]));

  it('a 4 GB phone gets the 1B model and the light voice model', () => {
    expect(fits({ totalRamMb: 3700, cpuCores: 8, gpu: false })).toEqual({
      'gemma-3-1b-it-int4': 'recommended',
      'gemma-4-e2b-it': 'slow', // fits under the hard limit, not comfortably
      'gemma-4-e4b-it': 'too_big',
      'zipformer-en-20m': 'good',
      'zipformer-en-2023-06-26': 'recommended',
    });
  });

  it('an 8 GB phone with a GPU gets the smartest model that stays above 10 tokens/s', () => {
    const f = fits(MOCK_DEVICE);
    expect(f['gemma-4-e4b-it']).toBe('recommended');
    expect(f['gemma-4-e2b-it']).toBe('good');
    expect(estimateTokensPerSec(MOCK_CATALOG[2]!, MOCK_DEVICE)).toBeGreaterThan(10);
  });

  it('without a GPU the same phone is too slow for E4B', () => {
    const f = fits({ ...MOCK_DEVICE, gpu: false });
    expect(f['gemma-4-e4b-it']).toBe('slow');
    expect(f['gemma-4-e2b-it']).toBe('recommended');
  });

  it('labels RAM as sold', () => {
    expect(marketedGb(7450)).toBe('8 GB');
    expect(marketedGb(11200)).toBe('12 GB');
    expect(marketedGb(5600)).toBe('6 GB');
  });
});

describe('MockModels', () => {
  let last: ModelsStatus | null = null;
  beforeEach(() => {
    vi.useFakeTimers();
    last = null;
  });
  afterEach(() => vi.useRealTimers());

  it('lists every model, recommended first, with the device it judged', () => {
    const m = new MockModels('M', () => {});
    const s = m.status();
    expect(s.packs.map((p) => [p.id, p.fit])).toEqual([
      ['gemma-4-e4b-it', 'recommended'],
      ['zipformer-en-2023-06-26', 'recommended'],
      ['gemma-4-e2b-it', 'good'],
      ['gemma-3-1b-it-int4', 'good'],
      ['zipformer-en-20m', 'good'],
    ]);
    expect(s.packs.filter((p) => p.core).map((p) => p.id)).toEqual(['gemma-4-e4b-it', 'zipformer-en-2023-06-26']);
    expect(s.device).toEqual({ ramMb: 7450, ramLabel: '8 GB', gpu: true, soc: 'SM7550' });
    expect(s.found).toBeUndefined();
  });

  it('downloads, verifies and installs; the first run makes it resident', () => {
    const m = new MockModels('S', (s) => (last = s), { totalRamMb: 3700, cpuCores: 8, gpu: false });
    m.download(['gemma-3-1b-it-int4', 'gemma-4-e4b-it']); // E4B is too big for this phone: refused
    expect(last!.packs.filter((p) => p.state !== 'available').map((p) => [p.id, p.state])).toEqual([['gemma-3-1b-it-int4', 'downloading']]);
    const p = () => last!.packs.find((x) => x.id === 'gemma-3-1b-it-int4')!;
    vi.advanceTimersByTime(250);
    expect(p().receivedBytes).toBeGreaterThan(0);
    vi.advanceTimersByTime(10_000);
    expect(p()).toMatchObject({ state: 'installed', active: true });
    expect(m.hasTextModel()).toBe(true);
    m.touch();
    expect(last!.resident).toEqual({ id: 'gemma-3-1b-it-int4', backend: 'cpu' });
    m.delete('gemma-3-1b-it-int4');
    expect(last!.resident).toBeUndefined();
    expect(p().state).toBe('available');
  });

  it('models.use picks which installed model does the job', () => {
    const m = new MockModels('M', (s) => (last = s));
    m.download(['gemma-4-e2b-it', 'gemma-3-1b-it-int4']);
    vi.advanceTimersByTime(60_000);
    const active = () => last!.packs.filter((x) => x.active).map((x) => x.id);
    expect(active()).toEqual(['gemma-4-e2b-it']); // the better-advised of the two
    m.use('gemma-3-1b-it-int4');
    expect(active()).toEqual(['gemma-3-1b-it-int4']);
    m.use('gemma-4-e4b-it'); // not installed: ignored
    expect(active()).toEqual(['gemma-3-1b-it-int4']);
  });

  it('finds models on the phone; verified ones install, unknown ones need trust', () => {
    const m = new MockModels('M', (s) => (last = s));
    m.scanNow();
    expect(last!.scan!.state).toBe('scanning');
    vi.advanceTimersByTime(1_000);
    expect(last!.found!.map((f) => [f.name, f.verified])).toEqual([
      ['gemma3-1b-it-int4.litertlm', true],
      ['Qwen3-0.6B-q8.litertlm', false],
    ]);
    m.adopt('found-gemma-3-1b-it-int4', false);
    expect(last!.packs.find((p) => p.id === 'gemma-3-1b-it-int4')!.state).toBe('installed');
    m.adopt('custom-1d6xk-qwen3-0.6b-q8', false);
    expect(last!.catalogDetail).toMatch(/trust/);
    m.adopt('custom-1d6xk-qwen3-0.6b-q8', true);
    const custom = last!.packs.find((p) => p.custom)!;
    expect(custom).toMatchObject({ name: 'Qwen3-0.6B-q8', state: 'installed', fit: 'good', core: false });
    expect(last!.found).toBeUndefined();
    m.scanNow('folder');
    vi.advanceTimersByTime(1_000);
    expect(last!.found).toBeUndefined(); // both are installed now
    expect(last!.scan).toMatchObject({ access: 'folders', folders: 1 });
  });

  it('pause keeps the partial bytes and resume continues from them', () => {
    const m = new MockModels('M', (s) => (last = s));
    const p = () => last!.packs.find((x) => x.id === 'gemma-4-e2b-it')!;
    m.download(['gemma-4-e2b-it']);
    vi.advanceTimersByTime(500);
    m.cancel('gemma-4-e2b-it');
    const kept = p().receivedBytes;
    expect(p()).toMatchObject({ state: 'paused', detail: 'Paused' });
    vi.advanceTimersByTime(1_000);
    expect(p().receivedBytes).toBe(kept);
    m.download(['gemma-4-e2b-it']);
    vi.advanceTimersByTime(250);
    expect(p().receivedBytes).toBeGreaterThan(kept);
    m.dispose();
  });
});

describe('Models screen helpers', () => {
  const sent: Array<[string, unknown]> = [];
  const bridge = { send: (t: string, p: unknown) => sent.push([t, p]) } as unknown as NativeBridge;
  const base: ModelsStatus = { tier: 'M', source: 'download', catalog: 'ready', packs: [], freeBytes: 0, wifiOnly: true };
  const pack = (state: ModelsStatus['packs'][number]['state']) => ({
    id: 'x', name: 'X', kind: 'llm' as const, role: '', sizeBytes: 10, license: 'Apache-2.0', core: true, state, receivedBytes: 0,
  });

  it('offers one action per state', () => {
    expect(packAction(pack('available'), 'download', bridge)?.label).toBe('Download');
    expect(packAction(pack('available'), 'import', bridge)).toBeNull(); // offline builds import instead
    expect(packAction(pack('downloading'), 'download', bridge)?.label).toBe('Pause');
    expect(packAction(pack('paused'), 'download', bridge)?.label).toBe('Resume');
    expect(packAction(pack('failed'), 'download', bridge)?.label).toBe('Retry');
    expect(packAction(pack('installed'), 'download', bridge)?.label).toBe('Delete');
    expect(packAction({ ...pack('installed'), updateAvailable: true }, 'download', bridge)?.label).toBe('Update');
    packAction(pack('downloading'), 'download', bridge)!.run();
    expect(sent.at(-1)).toEqual(['models.cancel', { id: 'x' }]);
  });

  it('never offers a model too big for the phone; installed ones can be switched to', () => {
    expect(packAction({ ...pack('available'), fit: 'too_big' }, 'download', bridge)).toBeNull();
    expect(switchAction(pack('installed'), bridge)?.label).toBe('Use');
    expect(switchAction({ ...pack('installed'), active: true }, bridge)).toBeNull();
    expect(switchAction(pack('available'), bridge)).toBeNull();
    switchAction(pack('installed'), bridge)!.run();
    expect(sent.at(-1)).toEqual(['models.use', { id: 'x' }]);
  });

  it('unknown found models are only adopted after the user confirms', () => {
    const f = { id: 'f', name: 'm.litertlm', kind: 'llm' as const, sizeBytes: 1e8, where: 'Download', verified: false };
    const ask = vi.fn().mockReturnValueOnce(false).mockReturnValueOnce(true);
    vi.stubGlobal('confirm', ask);
    foundAction(f, bridge).run();
    expect(sent.at(-1)).not.toEqual(['models.adopt', { id: 'f', trust: true }]);
    foundAction(f, bridge).run();
    expect(sent.at(-1)).toEqual(['models.adopt', { id: 'f', trust: true }]);
    expect(ask).toHaveBeenCalledTimes(2);
    foundAction({ ...f, verified: true }, bridge).run();
    expect(sent.at(-1)).toEqual(['models.adopt', { id: 'f' }]);
    vi.unstubAllGlobals();
    expect(scanScope({ state: 'idle', access: 'folders', folders: 1, allFilesOption: false })).toMatch(/1 folder you chose/);
    // Never promises Download/ without all-files access (Android 11+ hides it from apps).
    expect(scanScope({ state: 'idle', access: 'app', folders: 0, allFilesOption: false })).toMatch(/doesn’t let apps look through Download: pick the model files/);
    expect(scanScope({ state: 'idle', access: 'all', folders: 0, allFilesOption: true })).toMatch(/everywhere/);
  });

  it('explains an unusable catalog', () => {
    expect(catalogNotice(base)).toBeNull();
    expect(catalogNotice({ ...base, catalog: 'unconfigured' })).toMatch(/no model catalog/);
    expect(catalogNotice({ ...base, catalog: 'invalid', catalogDetail: 'Bad key.' })).toMatch(/signature.*Bad key\./);
    expect(catalogNotice({ ...base, source: 'import', catalog: 'unavailable' })).toMatch(/^Import/);
  });
});
