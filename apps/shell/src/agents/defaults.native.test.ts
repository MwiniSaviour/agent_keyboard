/**
 * The native `:agent` process seeds its SQLCipher database from
 * `android/core/src/main/resources/agents/defaults.json`. This keeps that file byte-identical to the
 * typed defaults the web build uses, so both runtimes start with the same agents.
 * Run with UPDATE_FIXTURES=1 to regenerate after editing defaults.ts.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { DEFAULT_AGENTS, GENERAL_AGENT_ID } from './defaults';

const JSON_PATH = fileURLToPath(new URL('../../android/core/src/main/resources/agents/defaults.json', import.meta.url));

describe('native agent seeds', () => {
  it('defaults.json matches defaults.ts', () => {
    const generated = `${JSON.stringify(DEFAULT_AGENTS, null, 2)}\n`;
    if (process.env.UPDATE_FIXTURES === '1') writeFileSync(JSON_PATH, generated);
    expect(readFileSync(JSON_PATH, 'utf8')).toBe(generated);
  });

  it('General comes first and is the only general-kind agent (FR-AG-01)', () => {
    expect(DEFAULT_AGENTS[0]?.id).toBe(GENERAL_AGENT_ID);
    expect(DEFAULT_AGENTS.filter((a) => a.kind === 'general')).toHaveLength(1);
  });
});
