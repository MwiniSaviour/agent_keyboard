/**
 * The simulated ModelService must follow the native rules (ADR-0011) and list exactly the packs the
 * real catalog lists, so the playground never demos a model the app can't install.
 */
import { readFileSync } from 'node:fs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ModelsStatus, NativeBridge } from '@ak/protocol';
import { catalogNotice, packAction } from '@/companion/ModelsView';
import { MOCK_CATALOG, MockModels } from './models';

const catalog = JSON.parse(readFileSync(new URL('../../../../../models/catalog.json', import.meta.url), 'utf8')) as {
  models: Array<Record<string, unknown>>;
};

const KEYS = ['id', 'kind', 'name', 'role', 'approxBytes', 'license', 'licenseUrl', 'tiers', 'coreFor'];

describe('mock model catalog', () => {
  it('mirrors models/catalog.json', () => {
    const pick = (m: object) => ({
      ...Object.fromEntries(Object.entries(m).filter(([k]) => KEYS.includes(k))),
    });
    expect(MOCK_CATALOG.map(pick)).toEqual(catalog.models.map(pick));
  });
});

describe('MockModels', () => {
  let last: ModelsStatus | null = null;
  beforeEach(() => {
    vi.useFakeTimers();
    last = null;
  });
  afterEach(() => vi.useRealTimers());

  it('shows only this tier’s packs, core first', () => {
    const m = new MockModels('L', () => {});
    expect(m.status().packs.map((p) => [p.id, p.core])).toEqual([
      ['gemma-4-e2b-it', true],
      ['gemma-4-e4b-it', false],
    ]);
  });

  it('downloads, verifies and installs; the first run makes it resident', () => {
    const m = new MockModels('S', (s) => (last = s));
    m.download(['gemma-3-1b-it-int4', 'gemma-4-e2b-it']); // the other tier's pack is refused
    expect(last!.packs.map((p) => p.state)).toEqual(['downloading']);
    vi.advanceTimersByTime(250);
    expect(last!.packs[0]!.receivedBytes).toBeGreaterThan(0);
    vi.advanceTimersByTime(10_000);
    expect(last!.packs[0]!.state).toBe('installed');
    expect(m.hasTextModel()).toBe(true);
    m.touch();
    expect(last!.resident).toEqual({ id: 'gemma-3-1b-it-int4', backend: 'cpu' });
    m.delete('gemma-3-1b-it-int4');
    expect(last!.resident).toBeUndefined();
    expect(last!.packs[0]!.state).toBe('available');
  });

  it('pause keeps the partial bytes and resume continues from them', () => {
    const m = new MockModels('M', (s) => (last = s));
    m.download(['gemma-4-e2b-it']);
    vi.advanceTimersByTime(500);
    m.cancel('gemma-4-e2b-it');
    const kept = last!.packs[0]!.receivedBytes;
    expect(last!.packs[0]).toMatchObject({ state: 'paused', detail: 'Paused' });
    vi.advanceTimersByTime(1_000);
    expect(last!.packs[0]!.receivedBytes).toBe(kept);
    m.download(['gemma-4-e2b-it']);
    vi.advanceTimersByTime(250);
    expect(last!.packs[0]!.receivedBytes).toBeGreaterThan(kept);
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

  it('explains an unusable catalog', () => {
    expect(catalogNotice(base)).toBeNull();
    expect(catalogNotice({ ...base, catalog: 'unconfigured' })).toMatch(/no model catalog/);
    expect(catalogNotice({ ...base, catalog: 'invalid', catalogDetail: 'Bad key.' })).toMatch(/signature.*Bad key\./);
    expect(catalogNotice({ ...base, source: 'import', catalog: 'unavailable' })).toMatch(/^Import/);
  });
});
