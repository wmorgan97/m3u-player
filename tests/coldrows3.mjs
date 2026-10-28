// Cold start on Movies: rows seen on the last visit must show their posters at once, not "Loading…".
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
const base = 'http://127.0.0.1:8090';
const key = readFileSync('C:/m3u-player/proxy-key.txt', 'utf8').trim();
const pl = JSON.parse(readFileSync('C:/m3u-player/data/shotfix.json', 'utf8'));
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const ctx = await browser.newContext({ viewport: { width: 393, height: 852 }, isMobile: true, hasTouch: true });
await ctx.route(/\/(live|movie|series|timeshift)\/|\/hls[?/]|\/hls$|\.ts(\?|$)|\.m3u8|\/sync/, r => r.abort());
let slowRows = false;
// (no /vod interception)
const page = await ctx.newPage();
page.on('console', m => { if (/row load failed/.test(m.text())) console.log('console', m.text()); }); page.on('pageerror', e => console.log('pageerror', e.message));
await page.goto(`${base}/index.html`); await page.waitForTimeout(1500);
await page.evaluate(async ({ pl, key }) => { const { db } = await import('/js/db.js'); await db.putPlaylist(pl); const s = (await db.get('settings')) || {}; await db.set('settings', { ...s, serverKey: key }); }, { pl, key });
await page.reload(); await page.waitForTimeout(8000);
const count = () => page.evaluate(() => { const sh = [...document.querySelectorAll('#list > .shelf[data-group]')].slice(0, 12); return `rows=${sh.length} loading=${sh.filter(s => s.querySelector('.shelf-empty')).length} noPosters=${sh.filter(s => !s.querySelector(".scard:not(.more)")).map(s => s.dataset.group + ":" + s.querySelector(".shelf-row").innerHTML.slice(0, 160)).join(" | ")}`; });
// Visit 1: open Movies and scroll so the first rows load (and get saved).
await page.goto('about:blank'); await page.goto(`${base}/index.html#/movies`); await page.waitForTimeout(5000);
for (let i = 0; i < 12; i++) { await page.mouse.wheel(0, 700); await page.waitForTimeout(500); }
await page.waitForTimeout(5000);
console.log('visit 1 after loading:', await count());
// Visit 2: cold start with row requests made slow (8s), as on a phone network.
slowRows = true;
await page.goto('about:blank'); await page.goto(`${base}/index.html#/movies`); await page.waitForTimeout(2500);
for (let i = 0; i < 12; i++) await page.mouse.wheel(0, 700);
await page.waitForTimeout(500);
console.log('cold start, 3s in:', await count());
await browser.close();
