/**
 * Autonomy guardrails (SPEC §11.6.2). Reference implementation for the mock; the production policy
 * runs natively in the `:agent` process. Inbound text is untrusted (SPEC §17.1).
 */

export type PolicyVerdict = { ok: true } | { ok: false; reason: string };

const SENSITIVE_INBOUND: Array<[RegExp, string]> = [
  [/\b(pin|password|passcode|otp|one[- ]time code|cvv|security code)\b/i, 'Sensitive request (credentials)'],
  [/\b(bank (details|account)|account number|transfer (me|money)|send (me )?money|mobile money pin)\b/i, 'Money request'],
  [/\bignore (all |your )?(previous|prior) instructions\b/i, 'Instruction-like message (possible prompt injection)'],
  [/\b(lawyer|contract|sign(ed)? (the )?agreement|legal action)\b/i, 'Legal commitment'],
];

export function checkInbound(text: string): PolicyVerdict {
  for (const [re, reason] of SENSITIVE_INBOUND) if (re.test(text)) return { ok: false, reason };
  return { ok: true };
}

const PII_OUTBOUND: Array<[RegExp, string]> = [
  [/\b\d{4}[ -]?\d{4}[ -]?\d{4}[ -]?\d{4}\b/, 'Card number in reply'],
  [/\b\d{6}\b(?=.*\b(code|otp)\b)/i, 'One-time code in reply'],
];

export function checkOutbound(text: string): PolicyVerdict {
  for (const [re, reason] of PII_OUTBOUND) if (re.test(text)) return { ok: false, reason };
  return { ok: true };
}

/** Sliding-window rate limiter (FR-AU-05). */
export class RateLimiter {
  private stamps: number[] = [];
  constructor(
    private readonly max: number,
    private readonly windowMs: number,
  ) {}
  tryAcquire(now = Date.now()): boolean {
    this.stamps = this.stamps.filter((t) => now - t < this.windowMs);
    if (this.stamps.length >= this.max) return false;
    this.stamps.push(now);
    return true;
  }
}
