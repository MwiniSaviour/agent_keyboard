/**
 * The web mock's guardrails must behave exactly like the native policy. Both replay the shared
 * fixture packages/protocol/fixtures/policy.json (SPEC §19.1); add cases there, not here.
 */
import { describe, expect, it } from 'vitest';
import fixtures from '@ak/protocol/fixtures/policy.json';
import { checkInbound, checkOutbound, RateLimiter } from './policy';

describe('autonomy policy parity with native (FR-AU-04/05)', () => {
  it.each(fixtures.inbound)('inbound: $text', ({ text, ok, reason }) => {
    expect(checkInbound(text)).toEqual(ok ? { ok: true } : { ok: false, reason });
  });

  it.each(fixtures.outbound)('outbound: $text', ({ text, ok, reason }) => {
    expect(checkOutbound(text)).toEqual(ok ? { ok: true } : { ok: false, reason });
  });

  it('rate-limits within a sliding window', () => {
    const { max, windowMs, steps } = fixtures.rateLimit;
    const rl = new RateLimiter(max, windowMs);
    for (const { at, ok } of steps) expect(rl.tryAcquire(at), `at ${at}`).toBe(ok);
  });
});
