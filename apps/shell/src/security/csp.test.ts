import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { applyCsp } from '../../scripts/csp.mjs';

const sha = (s: string) => `'sha256-${createHash('sha256').update(s, 'utf8').digest('base64')}'`;
const policyOf = (html: string) => /http-equiv="Content-Security-Policy" content="([^"]+)"/.exec(html)?.[1] ?? '';

describe('post-build CSP pinning', () => {
  const page =
    '<!DOCTYPE html><html><head><meta charset="utf-8"><script src="/_next/a.js" async=""></script></head>' +
    '<body><script>(self.__next_f=self.__next_f||[]).push([0])</script><script id="_R_">x()</script></body></html>';

  it('pins exactly the inline scripts and never allows unsafe-inline scripts', () => {
    const policy = policyOf(applyCsp(page));
    const scriptSrc = policy.split('; ').find((d) => d.startsWith('script-src'))!;
    expect(scriptSrc.split(' ').sort()).toEqual(
      ["script-src", "'self'", sha('(self.__next_f=self.__next_f||[]).push([0])'), sha('x()')].sort(),
    );
    expect(scriptSrc).not.toContain('unsafe');
    expect(policy).toContain("connect-src 'self'");
    expect(policy).toContain("object-src 'none'");
  });

  it('is the first element of <head> and idempotent', () => {
    const once = applyCsp(page);
    expect(once.indexOf('Content-Security-Policy')).toBeLessThan(once.indexOf('charset'));
    expect(applyCsp(once)).toBe(once);
  });
});
