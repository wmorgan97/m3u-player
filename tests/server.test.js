import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';

process.env.PROXY_KEY = 'k3y';
process.env.ALLOW_PRIVATE = '1';
const { createAppServer, isPrivateHost, rewriteHls } = await import('../server/server.js');

const BYTES = Buffer.from('0123456789');
let upstream, app, up, base;

before(async () => {
  upstream = createServer((req, res) => {
    if (req.url === '/live/index.m3u8') {
      res.writeHead(200, { 'content-type': 'application/vnd.apple.mpegurl' });
      return res.end('#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n#EXTINF:4,\nseg0.ts\n#EXTINF:4,\nhttp://cdn.example/seg1.ts\n');
    }
    if (req.url === '/list.m3u') { res.writeHead(200, { 'content-type': 'audio/x-mpegurl' }); return res.end('#EXTM3U\n#EXTINF:-1,A\nhttp://x/a.m3u8\n'); }
    if (req.url === '/file.bin') {
      const m = /bytes=(\d+)-(\d+)/.exec(req.headers.range || '');
      if (m) { res.writeHead(206, { 'content-range': `bytes ${m[1]}-${m[2]}/10`, 'content-length': m[2] - m[1] + 1 }); return res.end(BYTES.subarray(+m[1], +m[2] + 1)); }
      res.writeHead(200, { 'content-length': 10 }); return res.end(BYTES);
    }
    res.writeHead(404).end();
  }).listen(0);
  app = createAppServer().listen(0);
  await Promise.all([upstream, app].map(s => new Promise(r => s.listening ? r() : s.once('listening', r))));
  up = `http://127.0.0.1:${upstream.address().port}`;
  base = `http://127.0.0.1:${app.address().port}`;
});
after(() => { upstream.close(); app.close(); });

const proxied = (url, key = 'k3y') => `${base}/proxy?key=${key}&url=${encodeURIComponent(url)}`;

test('serves only the app files', async () => {
  const home = await fetch(`${base}/`);
  assert.equal(home.status, 200);
  assert.match(await home.text(), /<title>M3U Player<\/title>/);
  assert.equal((await fetch(`${base}/js/app.js`)).status, 200);
  for (const p of ['/server/server.js', '/package.json', '/.git/config', '/js/../package.json', '/deploy/install.sh', '/tests/server.test.js']) {
    assert.equal((await fetch(base + p)).status, 404, p);
  }
});

test('reports proxy status and enforces the key', async () => {
  assert.deepEqual(await (await fetch(`${base}/proxy`)).json(), { m3uProxy: true, keyRequired: true, keyValid: false });
  assert.equal((await (await fetch(`${base}/proxy?key=k3y`)).json()).keyValid, true);
  assert.equal((await fetch(proxied(`${up}/list.m3u`, 'wrong'))).status, 403);
  const ok = await fetch(proxied(`${up}/list.m3u`));
  assert.equal(ok.status, 200);
  assert.equal(ok.headers.get('access-control-allow-origin'), '*');
  assert.match(await ok.text(), /#EXTINF:-1,A/);
});

test('rewrites HLS playlists through the proxy', async () => {
  const text = await (await fetch(proxied(`${up}/live/index.m3u8`))).text();
  const lines = text.trim().split('\n');
  assert.equal(lines[4], `/proxy?key=k3y&url=${encodeURIComponent(`${up}/live/seg0.ts`)}`);
  assert.equal(lines[6], `/proxy?key=k3y&url=${encodeURIComponent('http://cdn.example/seg1.ts')}`);
  assert.match(lines[2], new RegExp(`URI="/proxy\\?key=k3y&url=${encodeURIComponent(`${up}/live/key.bin`).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"`));
});

test('passes byte ranges through', async () => {
  const res = await fetch(proxied(`${up}/file.bin`), { headers: { range: 'bytes=2-5' } });
  assert.equal(res.status, 206);
  assert.equal(res.headers.get('content-range'), 'bytes 2-5/10');
  assert.equal(await res.text(), '2345');
});

test('blocks private addresses by default', () => {
  for (const h of ['localhost', '127.0.0.1', '10.1.2.3', '192.168.0.1', '172.20.0.1', '169.254.169.254', '[::1]', 'fd00::1', 'router.local']) assert.equal(isPrivateHost(h), true, h);
  for (const h of ['8.8.8.8', 'provider.tv', '172.32.0.1', '2606:4700::1111']) assert.equal(isPrivateHost(h), false, h);
});

test('rewriteHls resolves relative URIs', () => {
  assert.equal(rewriteHls('#EXTM3U\nlow/a.m3u8', 'http://h/x/master.m3u8', u => `P(${u})`), '#EXTM3U\nP(http://h/x/low/a.m3u8)');
});
