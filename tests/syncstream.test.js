// Live "changed" signal over SSE (server/sync.js): fan-out, own-change skip, profile scoping, caps, cleanup.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
process.env.PROXY_KEY = 'k3y';
process.env.SYNC_DATA_DIR = mkdtempSync(join(tmpdir(), 'sse-'));
process.env.SYNC_HEARTBEAT_MS = '150';
const { createAppServer } = await import('../server/server.js');
const { streamCount, MAX_STREAMS } = await import('../server/sync.js');

let app, port;
before(async () => { app = createAppServer().listen(0); await new Promise(r => app.once('listening', r)); port = app.address().port; });
after(() => { app.closeAllConnections?.(); app.close(); });

function open(q = '') {
  return new Promise((resolve, reject) => {
    const r = http.get({ port, path: `/sync/stream?key=k3y${q}` }, res => {
      const c = { res, text: '', req: r, events: () => [...c.text.matchAll(/event: changed\ndata: (.*)\n\n/g)].map(m => JSON.parse(m[1])) };
      res.setEncoding('utf8'); res.on('data', d => { c.text += d; });
      resolve(c);
    });
    r.on('error', reject);
  });
}
const post = (body, q = '') => new Promise((resolve, reject) => {
  const r = http.request({ port, method: 'POST', path: `/sync?key=k3y${q}` }, res => { res.resume(); res.on('end', () => resolve(res.statusCode)); });
  r.on('error', reject); r.end(JSON.stringify(body));
});
const wait = ms => new Promise(r => setTimeout(r, ms));
const gone = async n => { for (let i = 0; i < 40 && streamCount() !== n; i++) await wait(25); return streamCount(); };

test('needs the key; headers pass through a proxy', async () => {
  const code = await new Promise(r => http.get({ port, path: '/sync/stream' }, res => { res.resume(); r(res.statusCode); }));
  assert.equal(code, 403);
  const a = await open();
  assert.equal(a.res.statusCode, 200);
  assert.match(a.res.headers['content-type'], /text\/event-stream/);
  assert.match(a.res.headers['cache-control'], /no-cache/);
  assert.equal(a.res.headers['x-accel-buffering'], 'no');
  await wait(30);
  assert.match(a.text, /^retry: 3000\n/);
  a.req.destroy(); assert.equal(await gone(0), 0);
});

test('two streams both get one changed event, without user data; the writer is named', async () => {
  const a = await open('&cid=aaa'), b = await open('&cid=bbb');
  assert.equal(await post({ favorites: ['http://secret/1'], favAt: 5 }, '&cid=aaa'), 200);
  await wait(60);
  for (const s of [a, b]) {
    const ev = s.events();
    assert.equal(ev.length, 1);
    assert.equal(ev[0].from, 'aaa'); assert.equal(ev[0].p, 'main');
    assert.ok(ev[0].v > 0);
    assert.ok(!s.text.includes('secret'));
  }
  await post({ favorites: ['http://secret/1'], favAt: 5 }, '&cid=bbb');   // identical state: nothing changed, no event
  await wait(60);
  assert.equal(a.events().length, 1);
  a.req.destroy(); b.req.destroy(); await gone(0);
});

test('personal changes stay in their profile; shared state reaches everyone', async () => {
  await post({ profiles: { mz: { name: 'Mazie', color: 1, at: 1 } } });
  const main = await open(), mz = await open('&profile=mz');
  await wait(30);
  main.text = mz.text = '';
  await post({ favorites: ['http://x/1'], favAt: 9 }, '&profile=mz');
  await wait(60);
  assert.equal(mz.events().length, 1); assert.equal(mz.events()[0].p, 'mz');
  assert.equal(main.events().length, 0);
  await post({ sources: { s1: { url: 'http://pl/1', at: 3 } } }, '&profile=mz');
  await wait(60);
  assert.equal(main.events().length, 1); assert.equal(main.events()[0].p, '*');
  assert.equal(mz.events().length, 2);
  main.req.destroy(); mz.req.destroy(); await gone(0);
});

test('heartbeat comments arrive', async () => {
  const a = await open();
  await wait(400);
  assert.ok((a.text.match(/: hb\n\n/g) || []).length >= 1);
  a.req.destroy(); await gone(0);
});

test('closing cleans up and the number of streams is capped', async () => {
  const all = [];
  for (let i = 0; i < MAX_STREAMS; i++) all.push(await open());
  assert.equal(streamCount(), MAX_STREAMS);
  const over = await new Promise(r => http.get({ port, path: '/sync/stream?key=k3y' }, res => { res.resume(); r(res.statusCode); }));
  assert.equal(over, 503);
  all.forEach(s => s.req.destroy());
  assert.equal(await gone(0), 0);
  assert.equal(await post({ favorites: [], favAt: 99 }), 200);
});
