#!/usr/bin/env node
/**
 * NFR-P-14 budget check for the /ime route. Run after a production build with NEXT_PUBLIC_AK_MOCK=0.
 * Reports framework baseline (from the 404 page) and app code on top of it; exits 1 over budget.
 */
import { readFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { join } from 'node:path';

const OUT = new URL('../out/', import.meta.url).pathname;
const APP_BUDGET_KB = 40;
const TOTAL_BUDGET_KB = 210;

const scripts = (page) =>
  new Set([...readFileSync(join(OUT, page), 'utf8').matchAll(/src="(\/_next\/static\/[^"]+\.js)"/g)].map((m) => m[1]));
const kb = (files) => [...files].reduce((n, f) => n + gzipSync(readFileSync(join(OUT, f)), { level: 9 }).length, 0) / 1024;

const base = scripts('404.html');
const ime = scripts('ime/index.html');
const app = new Set([...ime].filter((f) => !base.has(f)));
const mock = [...ime].some((f) => readFileSync(join(OUT, f), 'utf8').includes('Jane Doe'));

const total = kb(ime);
const appKb = kb(app);
console.log(`/ime total ${total.toFixed(1)} KB gzip · framework ${kb(base).toFixed(1)} KB · app ${appKb.toFixed(1)} KB · mock bundled: ${mock}`);
if (mock || appKb > APP_BUDGET_KB || total > TOTAL_BUDGET_KB) {
  console.error(`Over budget (app ≤ ${APP_BUDGET_KB} KB, total ≤ ${TOTAL_BUDGET_KB} KB, no mock).`);
  process.exit(1);
}
