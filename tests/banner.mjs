import { chromium } from 'playwright-core';
const b = await chromium.launch({ channel: 'chrome', headless: true });
const p = await b.newPage({ viewport: { width: 640, height: 360 } });
await p.goto('file:///C:/temp/banner.html');
await p.locator('#b').screenshot({ path: 'C:/temp/banner.png' });
await b.close();
