/**
 * Model manifest (SPEC §12.6, ADR-0011): the signed test vector and the source catalog.
 *
 * `fixtures/models.signed.json` is produced by `scripts/models/manifest.mjs fixture`. This test checks
 * it with Node's crypto; `ModelManifestTest` checks the same bytes with java.security, which is what
 * proves the publisher's signatures verify on the phone.
 */
import { createPublicKey, verify } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const read = (rel: string) => JSON.parse(readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8'));

interface Envelope { keyId: string; payload: string; signature: string }
interface Entry {
  id: string; kind: string; file: string; url: string; tiers: string[]; coreFor: string[];
  capabilities: string[]; runtime: string; contextTokens: number; license: string;
}

describe('signed model manifest', () => {
  const env = read('../fixtures/models.signed.json') as Envelope;
  const pub = read('../fixtures/models.public.json') as { keyId: string; publicKey: string };
  const key = createPublicKey({ key: Buffer.from(pub.publicKey, 'base64'), format: 'der', type: 'spki' });

  it('verifies with the pinned test key', () => {
    expect(env.keyId).toBe(pub.keyId);
    expect(verify('sha256', Buffer.from(env.payload, 'base64'), key, Buffer.from(env.signature, 'base64'))).toBe(true);
    const manifest = JSON.parse(Buffer.from(env.payload, 'base64').toString('utf8'));
    expect(manifest.schema).toBe(1);
    expect(manifest.models.map((m: Entry) => m.id)).toEqual(['gemma-3-1b-it-int4', 'gemma-4-e2b-it']);
  });

  it('rejects a single flipped payload byte', () => {
    const bytes = Buffer.from(env.payload, 'base64');
    bytes[10] = (bytes[10] ?? 0) ^ 1;
    expect(verify('sha256', bytes, key, Buffer.from(env.signature, 'base64'))).toBe(false);
  });
});

describe('models/catalog.json', () => {
  const catalog = read('../../../models/catalog.json') as { schema: number; models: Entry[] };

  it('is well formed for the app parser', () => {
    expect(catalog.schema).toBe(1);
    const ids = new Set<string>();
    for (const m of catalog.models) {
      expect(m.id).toMatch(/^[a-z0-9][a-z0-9._-]{1,63}$/);
      expect(ids.has(m.id)).toBe(false);
      ids.add(m.id);
      expect(m.url.startsWith('https://')).toBe(true);
      // Pinned to a commit, never a moving branch: the signed hash must stay valid.
      expect(m.url).toMatch(/\/resolve\/[0-9a-f]{40}\//);
      expect(m.url.endsWith(`/${m.file}`)).toBe(true);
      for (const t of m.coreFor) expect(m.tiers).toContain(t);
      expect(m.runtime).toBe('litertlm');
      expect(m.license.length).toBeGreaterThan(0);
    }
  });

  it('gives every tier exactly one core text model', () => {
    for (const tier of ['S', 'M', 'L']) {
      const core = catalog.models.filter((m) => m.coreFor.includes(tier) && m.capabilities.includes('text'));
      expect(core.map((m) => m.id), `tier ${tier}`).toHaveLength(1);
    }
  });
});
