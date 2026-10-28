import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
const base = 'http://127.0.0.1:8090';
const key = readFileSync('C:/m3u-player/proxy-key.txt', 'utf8').trim();
const pl = JSON.parse(readFileSync('C:/m3u-player/data/shotfix.json', 'utf8'));
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const ctx = await browser.newContext({ viewport: { width: 1512, height: 945 } });
await ctx.route(/\/(live|movie|series|timeshift)\/|\/hls[?/]|\/hls$|\.ts(\?|$)|\.m3u8|\/sync/, r => r.abort());
await ctx.addInitScript(() => { addEventListener('unhandledrejection', e => console.error('UNHANDLED ' + (e.reason && e.reason.stack || e.reason))); addEventListener('error', e => console.error('ERROR ' + (e.error && e.error.stack || e.message))); });
const page = await ctx.newPage();
page.on('pageerror', e => console.log('pageerror', e.message));
page.on('console', m => { if (m.type() === 'error') console.log('console', m.text().slice(0, 200)); });
await page.goto(`${base}/index.html`); await page.waitForTimeout(1500);
await page.evaluate(async ({ pl, key }) => { const { db } = await import('/js/db.js'); await db.putPlaylist(pl); const s = (await db.get('settings')) || {}; await db.set('settings', { ...s, serverKey: key }); }, { pl, key });
await page.reload(); await page.waitForTimeout(10000);
for (const pass of [1, 2]) {
  await page.goto('about:blank'); await page.goto(`${base}/index.html#/movies`); await page.waitForTimeout(6000);
  console.log(`pass ${pass}:`, await page.evaluate(() => `${location.hash} list=${document.querySelector('#list')?.className} items=${document.querySelectorAll('#list > li').length} first=${document.querySelector('#list > li')?.className}`));
  await page.screenshot({ path: `C:/m3u-player/data/shots/reg${pass}.png` });
}
await browser.close();
