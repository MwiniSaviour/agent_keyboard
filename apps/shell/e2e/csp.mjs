/**
 * CSP check (SPEC §9/§17.1): every exported page boots with zero violations under the hash-pinned
 * meta policy from scripts/csp.mjs, and an injected inline script is refused (negative control).
 * Usage: BASE_URL=http://127.0.0.1:3000 node csp.mjs  (same Chromium setup as smoke.mjs)
 */
import chromium from '@sparticuz/chromium';
import puppeteer from 'puppeteer-core';
const browser = await puppeteer.launch({ executablePath: process.env.IMPECCABLE_BROWSER || '/tmp/chromium', args: chromium.args, headless: true });
let failed = 0;
for (const path of ['/ime/', '/', '/agents/']) {
  const page = await browser.newPage();
  const errors = [];
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  page.on('pageerror', (e) => errors.push('PAGEERROR ' + e.message));
  await page.evaluateOnNewDocument(() => {
    window.__csp = [];
    document.addEventListener('securitypolicyviolation', (e) => window.__csp.push(`${e.violatedDirective} ${e.blockedURI}`));
  });
  await page.goto(process.env.BASE_URL + path, { waitUntil: 'networkidle0' });
  await new Promise((r) => setTimeout(r, 500));
  const csp = await page.evaluate(() => window.__csp);
  const baseline = errors.length; // before the negative control
  const hasMeta = await page.evaluate(() => !!document.querySelector('meta[http-equiv="Content-Security-Policy"]'));
  const hydrated = await page.evaluate(() => document.querySelectorAll('button, a, input, textarea').length);
  // Negative control: an injected inline script must be refused.
  const injected = await page.evaluate(() => { const s = document.createElement('script'); s.textContent = 'window.__pwned = 1'; document.body.appendChild(s); return window.__pwned === 1; });
  await new Promise((r) => setTimeout(r, 200));
  const refused = errors.slice(baseline).some((e) => e.includes('Refused to execute inline script'));
  const ok = hasMeta && csp.length === 0 && baseline === 0 && hydrated > 0 && !injected && refused;
  if (!ok) failed++;
  console.log(`${ok ? '✓' : '✗'} ${path} meta=${hasMeta} violations=${csp.length} bootErrors=${baseline} buttons=${hydrated} injectedRan=${injected} injectionRefused=${refused}`, errors.slice(0, baseline));
  await page.close();
}
await browser.close();
process.exit(failed ? 1 : 0);
