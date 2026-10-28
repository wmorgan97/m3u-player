// Cold start at phone speed (CPU x6): which functions keep the main thread busy in the first 40s.
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
const base = 'http://127.0.0.1:8090';
const key = readFileSync('C:/m3u-player/proxy-key.txt', 'utf8').trim();
const pl = JSON.parse(readFileSync('C:/m3u-player/data/shotfix.json', 'utf8'));
const sync = JSON.parse(readFileSync('C:/m3u-player/data/sync.json', 'utf8'));
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
await ctx.route(/\/(live|movie|series|timeshift)\/|\/hls[?/]|\/hls$|\.ts(\?|$)|\.m3u8|\/sync/, r => r.abort());
const page = await ctx.newPage();
await page.goto(`${base}/index.html`); await page.waitForTimeout(1500);
await page.evaluate(async ({ pl, key, sync }) => { const { db } = await import('/js/db.js'); await db.putPlaylist(pl); await db.set('recents', sync.recents || []); await db.set('progress', sync.progress || {}); const s = (await db.get('settings')) || {}; await db.set('settings', { ...s, serverKey: key }); }, { pl, key, sync });
await page.reload(); await page.waitForTimeout(25000);          // first run: everything gets saved on the device
const cdp = await ctx.newCDPSession(page);
await cdp.send('Emulation.setCPUThrottlingRate', { rate: 6 });
await cdp.send('Profiler.enable'); await cdp.send('Profiler.setSamplingInterval', { interval: 1000 });
await page.evaluate(() => { window.__gaps = []; });
await cdp.send('Profiler.start');
await page.reload();                                               // the cold restart
await page.evaluate(() => { let last = performance.now(); const f = t => { const g = t - last; if (g > 120) (window.__gaps ||= []).push([Math.round(t), Math.round(g)]); last = t; requestAnimationFrame(f); }; requestAnimationFrame(f); });
await page.waitForTimeout(40000);
const { profile } = await cdp.send('Profiler.stop');
const gaps = await page.evaluate(() => window.__gaps);
console.log('freezes (ms after reload: length):', gaps.map(g => `${g[0]}:${g[1]}`).join(' ')); console.log(`frozen frames >120ms: ${gaps.length}, worst ${Math.max(0, ...gaps.map(g => g[1]))}ms, total ${gaps.reduce((n, g) => n + g[1], 0)}ms`);
const self = new Map(), byId = new Map(profile.nodes.map(n => [n.id, n]));
const dt = profile.timeDeltas; let i = 0;
for (const id of profile.samples) { const n = byId.get(id), f = n.callFrame; const k = `${f.functionName || '(anon)'} ${f.url.split('/').pop()}:${f.lineNumber + 1}`; self.set(k, (self.get(k) || 0) + (dt[i++] || 0)); }
[...self].filter(([k]) => !/^\(idle|\(program|\(garbage/.test(k)).sort((a, b) => b[1] - a[1]).slice(0, 18).forEach(([k, us]) => console.log(`${(us / 1000).toFixed(0).padStart(6)}ms  ${k}`));
await browser.close();
