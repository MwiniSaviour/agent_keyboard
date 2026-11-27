#!/usr/bin/env node
/**
 * Post-build CSP pinning (SPEC §9, §17.1). Next's static export streams its RSC payload through inline
 * `<script>` tags, so a strict `script-src 'self'` would stop the shell booting, and `'unsafe-inline'`
 * would allow any injected script. This step hashes every inline script of every exported page and
 * injects a per-page `<meta http-equiv="Content-Security-Policy">` that allows exactly those bytes.
 *
 * The native IME also sends a CSP header (ImeWebView); browsers enforce both policies, so the
 * effective script policy is this hash-pinned one everywhere: IME WebView, companion app, preview.
 *
 * Usage: node scripts/csp.mjs [outDir]   (idempotent: re-running replaces the previous meta tag)
 */
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const MARKER = 'data-ak-csp';
const INLINE_SCRIPT = /<script(\s[^>]*)?>([\s\S]*?)<\/script>/gi;

/** Directives other than script-src. `frame-ancestors` is header-only and lives in ImeWebView. */
export const BASE_DIRECTIVES = [
  "default-src 'self'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob:",
  "font-src 'self' data:",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
];

/** Returns the page with a hash-pinned CSP meta tag as the first child of <head>. */
export function applyCsp(html) {
  const cleaned = html.replace(new RegExp(`<meta http-equiv="Content-Security-Policy"[^>]*${MARKER}[^>]*>`, 'g'), '');
  const hashes = new Set();
  for (const m of cleaned.matchAll(INLINE_SCRIPT)) {
    const attrs = m[1] ?? '';
    if (/\ssrc\s*=/i.test(attrs)) continue; // external: covered by 'self'
    hashes.add(`'sha256-${createHash('sha256').update(m[2], 'utf8').digest('base64')}'`);
  }
  const policy = [...BASE_DIRECTIVES, ["script-src 'self'", ...hashes].join(' ')].join('; ');
  const meta = `<meta http-equiv="Content-Security-Policy" content="${policy}" ${MARKER}="">`;
  if (!/<head[^>]*>/i.test(cleaned)) throw new Error('page has no <head>');
  return cleaned.replace(/<head[^>]*>/i, (h) => h + meta);
}

function* htmlFiles(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) yield* htmlFiles(p);
    else if (name.endsWith('.html')) yield p;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const out = process.argv[2] ?? fileURLToPath(new URL('../out/', import.meta.url));
  let pages = 0;
  for (const file of htmlFiles(out)) {
    writeFileSync(file, applyCsp(readFileSync(file, 'utf8')));
    pages++;
  }
  console.log(`csp: pinned inline scripts in ${pages} pages`);
}
