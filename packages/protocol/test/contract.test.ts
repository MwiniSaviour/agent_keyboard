/**
 * Keeps `fixtures/contract.json` (consumed by the Kotlin suite) in lock-step with the typed source.
 * Run with UPDATE_FIXTURES=1 to regenerate after an intentional protocol change.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { commands, events, rejected, requests } from '../fixtures/contract';
import { PROTOCOL_VERSION } from '../src';

const JSON_PATH = fileURLToPath(new URL('../fixtures/contract.json', import.meta.url));

function build() {
  return {
    $comment: 'GENERATED from fixtures/contract.ts by test/contract.test.ts. Do not edit by hand.',
    protocol: PROTOCOL_VERSION,
    catalog: {
      commands: Object.keys(commands),
      requests: Object.keys(requests),
      events: Object.keys(events),
      errorCodes: ['unknown_type', 'bad_payload', 'secure_field', 'unavailable', 'internal', 'version'],
    },
    commands,
    requests,
    events,
    rejected,
  };
}

describe('bridge contract fixtures (SPEC §19.1)', () => {
  it('contract.json matches the typed source', () => {
    const generated = `${JSON.stringify(build(), null, 2)}\n`;
    if (process.env.UPDATE_FIXTURES === '1') writeFileSync(JSON_PATH, generated);
    expect(readFileSync(JSON_PATH, 'utf8')).toBe(generated);
  });

  it('message type names follow the dotted lower-case convention', () => {
    for (const t of [...Object.keys(commands), ...Object.keys(requests), ...Object.keys(events)]) {
      expect(t).toMatch(/^[a-z]+(\.[a-z]+)*$/);
    }
  });

  it('rejected case names are unique', () => {
    expect(new Set(rejected.map((r) => r.name)).size).toBe(rejected.length);
  });
});
