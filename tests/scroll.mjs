// Movies page at phone speed (CPU x6): scroll down through the rows for 30s, report what the in-app recorder logged.
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
const base = 'http://127.0.0.1:8090';
const key = readFileSync('C:/m3u-player/proxy-key.txt', 'utf8').trim();
const pl = JSON.parse(readFileSync('C:/m3u-player/data/shotfix.json', 'utf8'));
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) ScrollTest' });
await ctx.route(/\/(live|movie|series|timeshift)\/|\/hls[?/]|\/hls$|\.ts(\?|$)|\.m3u8|\/sync/, r => r.abort());
const page = await ctx.newPage();
await page.goto(`${base}/index.html`); await page.waitForTimeout(1500);
await page.evaluate(async ({ pl, key }) => { const { db } = await import('/js/db.js'); await db.putPlaylist(pl); const s = (await db.get('settings')) || {}; await db.set('settings', { ...s, serverKey: key }); }, { pl, key });
await page.reload(); await page.waitForTimeout(15000);
const cdp = await ctx.newCDPSession(page);
await cdp.send('Emulation.setCPUThrottlingRate', { rate: 6 });
await page.goto('about:blank'); await page.goto(`${base}/index.html#/movies`); await page.waitForTimeout(3000);
for (let i = 0; i < 26; i++) { await page.mouse.wheel(0, 700); await page.waitForTimeout(1000); }
await page.waitForTimeout(6000);
await browser.close();
