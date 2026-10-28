// Screenshots of the app with the owner's playlist for a design audit. Streams are blocked: no provider connection is opened.
import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
const base = 'http://127.0.0.1:8090';
const key = readFileSync('C:/m3u-player/proxy-key.txt', 'utf8').trim();
const pl = JSON.parse(readFileSync('C:/m3u-player/data/shotfix.json', 'utf8'));
const out = process.argv[2] || 'C:/m3u-player/data/shots';
const browser = await chromium.launch({ channel: 'chrome', headless: true });
for (const [tag, vp] of [['desk', { width: 1512, height: 945 }], ['phone', { width: 390, height: 844 }]]) {
  const ctx = await browser.newContext({ viewport: vp, deviceScaleFactor: tag === 'phone' ? 2 : 1, isMobile: tag === 'phone', hasTouch: tag === 'phone' });
  await ctx.route(/\/(live|movie|series|timeshift)\/|\/hls[?/]|\/hls$|\.ts(\?|$)|\.m3u8/, r => r.abort());
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('pageerror', e.message));
  await page.goto(`${base}/index.html`);
  await page.waitForTimeout(1500);
  await page.evaluate(async ({ pl, key }) => {
    const { db } = await import('/js/db.js');
    await db.putPlaylist(pl);
    const s = (await db.get('settings')) || {};
    await db.set('settings', { ...s, serverKey: key });
  }, { pl, key });
  for (const [name, hash, wait] of [['home', '#/', 14000], ['movies', '#/movies', 9000], ['series', '#/series', 7000], ['live', '#/live', 4000]]) {
    if (name === 'home') { await page.reload(); } else { await page.evaluate(h => { location.hash = h; }, hash); }
    await page.waitForTimeout(wait);
    if (name === 'home') { for (let y = 0; y < 6000; y += 600) { await page.evaluate(v => scrollTo(0, v), y); await page.waitForTimeout(250); } await page.evaluate(() => scrollTo(0, 0)); await page.waitForTimeout(1500); }
    await page.screenshot({ path: `${out}/${tag}-${name}.png`, fullPage: name === 'home' });
    if (name === 'movies') { for (let y = 0; y < 3000; y += 600) { await page.evaluate(v => scrollTo(0, v), y); await page.waitForTimeout(300); } await page.evaluate(() => scrollTo(0, 0)); await page.waitForTimeout(1200); await page.screenshot({ path: `${out}/${tag}-movies-full.png`, fullPage: true }); }
    await page.screenshot({ path: `${out}/${tag}-${name}-fold.png` });
  }
  await ctx.close();
}
await browser.close();
console.log('done');
