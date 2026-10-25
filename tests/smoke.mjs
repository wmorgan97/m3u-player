// Headless Chrome smoke test for the live server: the app boots without script errors and the real
// player decodes video on the VOD, converted and live paths. Exit 1 on any failure (deploy rolls back).
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';

const base = process.env.BASE || 'http://127.0.0.1:8090';
const key = readFileSync(new URL('../proxy-key.txt', import.meta.url), 'utf8').trim();
const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--autoplay-policy=no-user-gesture-required', '--mute-audio'] });
let failed = 0;
try {
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(`${base}/index.html?smoke=${Date.now()}`);
  await page.waitForTimeout(3000);
  const html = await page.evaluate(() => document.querySelector('#view')?.innerHTML.length || 0);
  if (html < 100 || errors.length) { failed++; console.log(`FAIL app boots: view=${html} chars, errors=${errors.join(' | ').slice(0, 300)}`); }
  else console.log('PASS app boots without script errors');

  const test = await browser.newPage();
  test.on('pageerror', e => console.log(`page error: ${e.message}`));
  await test.goto(`${base}/vendor/smoketest.html?key=${encodeURIComponent(key)}`);
  await test.waitForFunction(() => window.__done === true, null, { timeout: 240000 });
  const results = await test.evaluate(() => window.__results);
  for (const r of results) console.log(`${r.ok ? 'PASS' : 'FAIL'} ${r.name}${r.ok ? ` (${(r.ms / 1000).toFixed(1)}s)` : `: ${r.error}`}`);
  failed += results.filter(r => !r.ok).length;
} catch (e) {
  failed++;
  console.log(`FAIL smoke runner: ${e.message}`);
} finally {
  await browser.close();
}
console.log(failed ? `SMOKE FAILED (${failed})` : 'SMOKE OK');
process.exit(failed ? 1 : 0);
