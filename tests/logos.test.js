import { test } from 'node:test';
import assert from 'node:assert/strict';

process.env.PROXY_KEY = 'k3y'; process.env.ALLOW_PRIVATE = '1'; process.env.FFMPEG ||= 'ffmpeg';   // read when the server modules load
const { words, keysFor, buildIndex, resolveLogo, useIndex } = await import('../server/logos.js');

const tvlogos = {
  'united-kingdom': ['bbc-one-uk.png', 'hd/bbc-one-hd-uk.png', 'bbc-one-scotland-uk.png', 'itv-1-uk.png', 'channel-4-uk.png', 'channel-4-plus-uk.png',
    'sky-sports-main-event-uk.png', 'hd/sky-sports-main-event-hd-uk.png', 'sky-sports-main-event-icon-uk.png', 'e-4-uk.png', 'u-and-dave-uk.png',
    'bbc-cbbc-uk.png', '4-more-uk.png', 'w-network-uk.png', 'film-4-uk.png', '0_all_logos_mosaic.md'],
  'united-states': ['cnn-us.png', 'bbc-one-us.png'],
};
const channels = [
  { id: 'BBCOne.uk', name: 'BBC One', alt_names: ['BBC1'], country: 'UK' },
  { id: 'Gold.uk', name: 'Gold', country: 'UK' },
  { id: 'GoldTV.us', name: 'Gold', country: 'US' },
  { id: 'Quest.uk', name: 'Quest', country: 'UK' },
];
const logos = [
  { channel: 'Gold.uk', width: 100, format: 'PNG', url: 'https://x/gold-small.png' },
  { channel: 'Gold.uk', width: 400, format: 'PNG', url: 'https://x/gold.png' },
  { channel: 'Gold.uk', width: 900, format: 'SVG', url: 'https://x/gold.svg' },
  { channel: 'GoldTV.us', width: 900, format: 'PNG', url: 'https://x/gold-us.png' },
  { channel: 'Quest.uk', feed: 'HD', width: 900, format: 'PNG', url: 'https://x/quest-hd.png' },
  { channel: 'Quest.uk', width: 300, format: 'PNG', url: 'https://x/quest.png' },
];
const idx = buildIndex({ tvlogos, channels, logos });
const R = 'https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/';
const find = (name, id = '', provider = '') => resolveLogo(idx, { name, id, provider });

test('names lose country tags, quality tags and brackets; +1 stays distinct', () => {
  for (const n of ['UK: BBC One HD', '|UK| BBC ONE FHD', 'UK ● BBC One ᴴᴰ', '[UK] BBC 1 4K', 'UK - BBC One (Backup)', 'BBC One UHD', 'BBC1 1080p'])
    assert.equal(keysFor(n).keys[0], 'bbc1', n);
  assert.equal(words('UK: BBC One').country, 'uk');
  assert.equal(words('US: CNN HD').country, 'us');
  assert.equal(keysFor('Channel 4 +1').keys[0], 'channel4plus');
  assert.equal(keysFor('Channel 4+1 HD').keys[0], 'channel4plus');
  assert.notEqual(keysFor('Channel 4 +1').keys[0], keysFor('Channel 4').keys[0]);
  assert.equal(keysFor('5USA').keys[0], '5usa');
  assert.equal(keysFor('Sky News UK').keys[0], 'skynews');
  assert.deepEqual(keysFor('BBC One Yorkshire HD').keys, ['bbc1yorkshire', 'bbc1']);
});

test('tv-logos UK first, the plain file over hd/ and icon variants', () => {
  assert.equal(find('UK: BBC One HD').src, `${R}united-kingdom/bbc-one-uk.png`);
  assert.equal(find('UK: BBC One HD').via, 'tv-logos');
  assert.equal(find('|UK| SKY SPORTS MAIN EVENT FHD').src, `${R}united-kingdom/sky-sports-main-event-uk.png`);
  assert.equal(find('ITV1 HD').src, `${R}united-kingdom/itv-1-uk.png`);
  assert.equal(find('US: BBC One').src, `${R}united-states/bbc-one-us.png`);   // a US-tagged name prefers the US folder
  assert.equal(find('CNN HD').src, `${R}united-states/cnn-us.png`);
});

test('+1 channels get their own logo, regions fall back to the main one', () => {
  assert.equal(find('Channel 4 +1').src, `${R}united-kingdom/channel-4-plus-uk.png`);
  assert.equal(find('BBC One Scotland HD').src, `${R}united-kingdom/bbc-one-scotland-uk.png`);
  assert.equal(find('BBC One Yorkshire HD').src, `${R}united-kingdom/bbc-one-uk.png`);
  // No +1 logo anywhere: the provider's own first, the main channel's only when there is none.
  assert.equal(find('E4 +1', '', 'https://p/e4plus.png').via, 'provider');
  assert.equal(find('E4 +1').src, `${R}united-kingdom/e-4-uk.png`);
});

test('aliases: BBC CBBC, U & Dave, W Network, 4 More', () => {
  assert.equal(find('CBBC HD').src, `${R}united-kingdom/bbc-cbbc-uk.png`);
  assert.equal(find('Dave').src, `${R}united-kingdom/u-and-dave-uk.png`);
  assert.equal(find('W').src, `${R}united-kingdom/w-network-uk.png`);
  assert.equal(find('More4').src, `${R}united-kingdom/4-more-uk.png`);
});

test('iptv-org by tvg-id, then by name (UK first), best picture: no feed, no SVG, widest', () => {
  assert.equal(find('Some Gold Channel', 'Gold.uk').src, 'https://x/gold.png');
  assert.equal(find('Some Gold Channel', 'gold.UK@HD').src, 'https://x/gold.png');
  assert.equal(find('UK: Gold HD').src, 'https://x/gold.png');
  assert.equal(find('Quest').src, 'https://x/quest.png');
  // A tvg-id whose iptv-org name has a tv-logos file uses tv-logos.
  assert.equal(find('Totally different', 'BBCOne.uk').src, `${R}united-kingdom/bbc-one-uk.png`);
});

test('the provider logo when nothing matches; null when there is none', () => {
  assert.deepEqual(find('My Local Channel', '', 'https://p/logo.png'), { src: 'https://p/logo.png', via: 'provider' });
  assert.equal(find('My Local Channel'), null);
  assert.equal(find(''), null);
});

test('GET /logo: key check, JSON answer, PNG from the image cache, 404 with nothing', async () => {
  const { createServer } = await import('node:http');
  const { spawnSync } = await import('node:child_process');
  const { createAppServer } = await import('../server/server.js');
  // A tiny transparent PNG made by ffmpeg (skipped where ffmpeg isn't installed).
  const png = spawnSync(process.env.FFMPEG, ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'color=c=red@0.5:s=64x32,format=rgba', '-frames:v', '1', '-f', 'image2', '-c:v', 'png', '-'], { maxBuffer: 1e6 });
  const up = createServer((req, res) => req.url === '/p.png' && png.stdout?.length ? res.writeHead(200, { 'content-type': 'image/png' }).end(png.stdout) : res.writeHead(404).end()).listen(0);
  const app = createAppServer().listen(0);
  await Promise.all([up, app].map(s => new Promise(r => s.listening ? r() : s.once('listening', r))));
  const base = `http://127.0.0.1:${app.address().port}`, prov = `http://127.0.0.1:${up.address().port}/p.png`;
  try {
    useIndex({ tvlogos, channels, logos });
    assert.equal((await fetch(`${base}/logo?name=x`)).status, 403);
    const j = await (await fetch(`${base}/logo?key=k3y&json=1&name=${encodeURIComponent('UK: BBC One HD')}`)).json();
    assert.deepEqual(j, { src: `${R}united-kingdom/bbc-one-uk.png`, via: 'tv-logos' });
    assert.equal((await fetch(`${base}/logo?key=k3y&name=Nothing%20Here`)).status, 404);
    if (png.stdout?.length) {
      const r = await fetch(`${base}/logo?key=k3y&w=120&name=Local&u=${encodeURIComponent(prov)}`);
      assert.equal(r.status, 200);
      assert.equal(r.headers.get('content-type'), 'image/png');
      const b = Buffer.from(await r.arrayBuffer());
      assert.equal(b.subarray(1, 4).toString(), 'PNG');
    }
  } finally { up.close(); app.close(); }
});
