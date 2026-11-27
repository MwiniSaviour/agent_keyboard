/**
 * Browser smoke test for the playground (17 checks: typing, autocap, caps lock, layers, send, physical keys,
 * lens → reply/define/fill, autonomy consent + auto-reply + PIN escalation, protected fields, long-press,
 * agent creation, mobile layout). Not part of `npm test` — it needs a Chromium:
 *
 *   npm i --no-save @sparticuz/chromium puppeteer-core   # or point executablePath at a local Chrome
 *   npm run build -w @ak/shell && npm run preview -w @ak/shell &
 *   SHOTS=/tmp/shots node apps/shell/e2e/smoke.mjs
 */
import chromium from '@sparticuz/chromium';
import puppeteer from 'puppeteer-core';

const BASE = process.env.BASE_URL ?? 'http://127.0.0.1:3000';
const shots = process.env.SHOTS ?? '/tmp/e2e/shots';
const browser = await puppeteer.launch({
  executablePath: await chromium.executablePath(),
  args: [...chromium.args, '--no-sandbox'],
  headless: true,
  defaultViewport: { width: 1280, height: 900, deviceScaleFactor: 1 },
});
const page = await browser.newPage();
const errors = [];
page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
page.on('pageerror', (e) => errors.push('PAGEERROR ' + e.message));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const check = (name, ok, extra = '') => {
  results.push(`${ok ? 'PASS' : 'FAIL'} ${name} ${extra}`);
};
async function tap(keyId) {
  const el = await page.$(`[data-key="${keyId}"]`);
  if (!el) throw new Error('no key ' + keyId);
  const b = await el.boundingBox();
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2);
  await page.mouse.down();
  await sleep(15);
  await page.mouse.up();
  await sleep(15);
}
async function typeWord(w) {
  for (const ch of w) await tap(ch === ' ' ? 'space' : `k:${ch.toLowerCase()}`);
}
const composerText = () => page.$eval('[class*="composerField"]', (e) => e.textContent);
const clickText = async (sel, text) => {
  const ok = await page.evaluate(
    (sel, text) => {
      const el = [...document.querySelectorAll(sel)].find((e) => e.textContent.trim().includes(text));
      if (!el) return false;
      el.click();
      return true;
    },
    sel,
    text,
  );
  if (!ok) throw new Error(`not found: ${sel} "${text}"`);
};

await page.goto(BASE + '/', { waitUntil: 'networkidle0' });
await page.waitForSelector('[data-key="k:q"]');
await page.screenshot({ path: `${shots}/01-light.png` });

// typing + autocap
await typeWord('hello world');
let t = await composerText();
check('typing with autocap', t.includes('Hello world'), JSON.stringify(t));
const sugg = await page.$$eval('[role="option"]', (els) => els.map((e) => e.textContent));
check('suggestions shown', sugg.length > 0, JSON.stringify(sugg));

// backspace
await tap('backspace');
t = await composerText();
check('backspace', t.includes('Hello worl') && !t.includes('world'), JSON.stringify(t));

// shift double tap = caps lock
await tap('space');
await tap('shift');
await tap('shift');
await typeWord('ok');
t = await composerText();
check('caps lock', t.includes('OK'), JSON.stringify(t));
await tap('shift'); // unlock

// layer switch
await page.click('[data-key^="layer:symbols"]'); await sleep(50);
const hasDigits = !!(await page.$('[data-key="k:1"]'));
check('symbols layer', hasDigits);
await page.click('[data-key^="layer:letters"]'); await sleep(50);

// send message via enter
await tap('enter');
await sleep(100);
const lastMe = await page.$$eval('[class*="bubbleMe"]', (els) => els.at(-1)?.textContent ?? '');
check('send via enter', lastMe.includes('OK'), JSON.stringify(lastMe));

// physical keyboard
await page.keyboard.type('Hi there');
t = await composerText();
check('physical keyboard', t.includes('Hi there'), JSON.stringify(t));

// insight chip → reply draft
await page.screenshot({ path: `${shots}/02-typed.png` });

// Refine via lens
await page.click('[aria-label="Screen context and actions"]');
await sleep(300);
await page.screenshot({ path: `${shots}/03-lens-chat.png` });
await clickText('button', 'Draft reply');
await sleep(1600);
const result = await page.$eval('[class*="resultText"]', (e) => e.textContent).catch(() => '');
check('reply draft streamed', result.length > 10, JSON.stringify(result.slice(0, 120)));
await page.screenshot({ path: `${shots}/04-result-reply.png` });
await clickText('button', 'Apply').catch(() => clickText('button', 'Insert'));
await sleep(150);
t = await composerText();
check('apply reply replaces field', t.length > 10 && !t.includes('Hi there'), JSON.stringify(t));

// Live screen + scroll-and-read (ADR-0010): the web mock stands in for MediaProjection and a11y scrolling
await page.click('[aria-label="Screen context and actions"]');
await sleep(300);
await clickText('button', 'Read earlier messages');
await sleep(400);
const scanned = await page.$eval('[class*="ctxSummary"]', (e) => e.textContent).catch(() => '');
const earlier = await page.$eval('[class*="ctxQuote"]', (e) => e.textContent).catch(() => '');
check('scroll-and-read', /screens? read/.test(scanned) && /messages/.test(earlier), JSON.stringify(scanned + ' | ' + earlier));
await clickText('button', 'Turn on live screen');
await sleep(900);
const frameShown = await page.$('[class*="ctxFrame"] img').then((e) => !!e);
const liveBadge = await page.$('[aria-label$="Live screen is on"]').then((e) => !!e);
check('live screen frame + LIVE badge', frameShown && liveBadge, `frame=${frameShown} badge=${liveBadge}`);
await page.screenshot({ path: `${shots}/04a-lens-live.png` });
await clickText('button', 'Stop live screen');
await sleep(200);
await page.click('[aria-label="Close"]');
await sleep(200);

// autonomy — switch to Sales Bot (ceiling: auto)
await page.click('[aria-label^="Active agent"]');
await sleep(200);
await page.screenshot({ path: `${shots}/04b-agents.png` });
await clickText('[role="option"]', 'Sales Bot');
await sleep(200);
await page.click('[aria-label="Autopilot"]');
await sleep(200);
await page.screenshot({ path: `${shots}/05-consent.png` });
await clickText('button', 'Auto-send');
await clickText('button', 'Turn on');
await sleep(400);
const autoOn = await page.$eval('[aria-label="Autopilot"]', (e) => e.getAttribute('aria-checked'));
check('autonomy on', autoOn === 'true');
const before = await page.$$eval('[class*="bubbleMe"]', (els) => els.length);
await clickText('button', 'Jane sends');
await sleep(400);
await page.screenshot({ path: `${shots}/06-auto-thinking.png` });
await sleep(7000);
const after = await page.$$eval('[class*="bubbleMe"]', (els) => els.map((e) => e.textContent));
check('autopilot replied', after.length > before, JSON.stringify(after.at(-1)));
await page.screenshot({ path: `${shots}/07-auto-replied.png` });
// PIN escalation — in Auto mode Jane keeps messaging by herself; poll until escalated
let strip = '';
for (let i = 0; i < 30; i++) {
  await sleep(1000);
  strip = await page.$eval('[role="status"]', (e) => e.textContent).catch(() => '');
  if (/needs you/i.test(strip)) break;
  const btn = await page.evaluate(() => [...document.querySelectorAll('button')].find((b) => /Jane sends/.test(b.textContent) && !b.disabled));
  if (btn) await clickText('button', 'Jane sends').catch(() => {});
}
const mine = await page.$$eval('[class*="bubbleMe"]', (els) => els.map((e) => e.textContent));
check('PIN escalation', /needs you/i.test(strip), JSON.stringify(strip) + ' sent=' + mine.length);
await page.screenshot({ path: `${shots}/08-escalated.png` });
await page.click('[aria-label="Autopilot"]').catch(() => {});

// Article: define exigent
await clickText('[role="tab"]', 'Essay');
await sleep(300);
await clickText('button', 'exigent');
await page.click('[aria-label="Screen context and actions"]');
await sleep(300);
await clickText('button', 'Define word');
await sleep(1800);
const def = await page.$eval('[class*="resultText"]', (e) => e.textContent).catch(() => '');
check('define exigent', /urgent|immediate|pressing/i.test(def), JSON.stringify(def.slice(0, 140)));
await page.screenshot({ path: `${shots}/09-define.png` });
await page.click('[aria-label="Close"]');

// Form fill
await clickText('[role="tab"]', 'Form');
await sleep(300);
await page.click('[aria-label="Screen context and actions"]');
await sleep(300);
await clickText('button', 'Fill form');
await sleep(1800);
await page.screenshot({ path: `${shots}/10-form-result.png` });
await clickText('button', 'Fill fields').catch(() => clickText('button', 'Apply'));
await sleep(2500);
const formText = await page.evaluate(() => document.querySelector('h2')?.parentElement?.textContent ?? '');
check('form filled', formText.includes('Ama Mensah') && formText.includes('Kumasi'), '');
await page.screenshot({ path: `${shots}/11-form-filled.png` });

// password field is protected
await clickText('label', 'Password');
await page.evaluate(() => [...document.querySelectorAll('label')].find((l) => l.textContent.includes('Password')).querySelector('button').click());
await sleep(200);
const secure = await page.$eval('[role="status"]', (e) => e.textContent).catch(() => '');
check('protected field notice', /Protected/i.test(secure), JSON.stringify(secure));

// dark
await clickText('button', 'Dark');
await clickText('[role="tab"]', 'Chat');
await sleep(300);
await page.screenshot({ path: `${shots}/12-dark.png` });

// long-press hint
const q = await page.$('[data-key="k:q"]');
const qb = await q.boundingBox();
await page.mouse.move(qb.x + 10, qb.y + 10);
await page.mouse.down();
await sleep(450);
await page.mouse.up();
await sleep(50);
t = await composerText();
check('long-press hint inserts', /[1%]/.test(t), JSON.stringify(t));

// companion pages
for (const r of ['/agents/', '/agents/new/', '/models/', '/settings/', '/ime/']) {
  await page.goto(BASE + r, { waitUntil: 'networkidle0' });
  await sleep(200);
  await page.screenshot({ path: `${shots}/page-${r.replace(/\//g, '_')}.png` });
}
// create an agent
await page.goto(BASE + '/agents/new/', { waitUntil: 'networkidle0' });
await page.type('#name', 'Tutor');
await page.type('#role', 'You are a patient math tutor.');
await clickText('button', 'Create');
await sleep(500);
const listText = await page.evaluate(() => document.body.textContent);
check('agent created', listText.includes('Tutor') && page.url().endsWith('/agents/'));

// mobile viewport
await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
await page.goto(BASE + '/', { waitUntil: 'networkidle0' });
await sleep(300);
await page.screenshot({ path: `${shots}/13-mobile.png` });

console.log(results.join('\n'));
console.log('console errors:', errors.length ? errors : 'none');
await browser.close();
