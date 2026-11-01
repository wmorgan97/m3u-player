// GET /fixtures and POST /fixtures/channels against a stand-in fixtures feed and TV guide (no internet).
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readFile, rm } from 'node:fs/promises';

const H = 3600_000, now = Date.now(), ko = Math.floor(now / 60_000) * 60_000 - 20 * 60_000;   // a game 20 minutes in
const iso = t => new Date(t).toISOString().replace(/:\d\d\.\d+Z$/, 'Z');
const xt = t => new Date(t).toISOString().replace(/[-:T]/g, '').slice(0, 14) + ' +0000';
const team = (id, n, s) => ({ id, displayName: n, shortDisplayName: s, name: n, logo: `https://a.espncdn.com/i/teamlogos/soccer/500/${id}.png` });
const game = (id, t, home, away, state) => ({ id, date: iso(t), competitions: [{ id, date: iso(t), status: { type: { state, name: state === 'in' ? 'STATUS_FIRST_HALF' : 'STATUS_SCHEDULED', shortDetail: state === 'in' ? "20'" : '' } },
  competitors: [{ homeAway: 'home', score: '1', team: home }, { homeAway: 'away', score: '0', team: away }] }] });
let failPl = false, hits = 0;
const CACHE = join(tmpdir(), `fixtures-test-${process.pid}.json`);
let upstream, app, base;

before(async () => {
  upstream = createServer((req, res) => {
    const u = new URL(req.url, 'http://x');
    if (u.pathname === '/espn/soccer/eng.1/scoreboard') {
      hits++;
      if (failPl) { res.writeHead(500); return res.end(); }
      res.writeHead(200, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ events: [game('1', ko, team('364', 'Liverpool', 'Liverpool'), team('359', 'Arsenal', 'Arsenal'), 'in'),
        game('2', ko + 26 * H, team('360', 'Manchester United', 'Man Utd'), team('367', 'Tottenham Hotspur', 'Spurs'), 'pre'),
        game('3', ko + 20 * 24 * H, team('1', 'Everton', 'Everton'), team('2', 'Fulham', 'Fulham'), 'pre')] }));   // outside the window
    }
    if (u.pathname.startsWith('/espn/')) { res.writeHead(200, { 'content-type': 'application/json' }); return res.end('{"events":[]}'); }
    if (u.pathname === '/xmltv.php') {
      const p = (ch, s, e, t) => `<programme start="${xt(s)}" stop="${xt(e)}" channel="${ch}"><title>${t}</title></programme>`;
      res.writeHead(200, { 'content-type': 'application/xml' });
      return res.end(`<?xml version="1.0"?><tv><channel id="ssme"><display-name>Sky Sports Main Event</display-name></channel><channel id="tnt1"><display-name>TNT Sports 1</display-name></channel>
        ${p('ssme', ko - 30 * 60_000, ko + 2 * H, 'Live PL: Liverpool v Arsenal')}${p('ssme', ko + 5 * H, ko + 7 * H, 'PL: Liverpool v Arsenal')}
        ${p('tnt1', ko + 26 * H - 30 * 60_000, ko + 28 * H, 'Live Premier League: Man Utd v Spurs')}${p('tnt1', ko - H, ko + 3 * H, 'Live: Celtic v Rangers')}</tv>`);
    }
    res.writeHead(404).end();
  }).listen(0, '127.0.0.1');
  await new Promise(r => upstream.once('listening', r));
  const up = `http://127.0.0.1:${upstream.address().port}`;
  Object.assign(process.env, { PROXY_KEY: 'k3y', ALLOW_PRIVATE: '1', FIXTURES_BASE: `${up}/espn`, FIXTURES_CACHE: CACHE });
  const { createAppServer } = await import('../server/server.js');
  app = createAppServer().listen(0, '127.0.0.1');
  await new Promise(r => app.once('listening', r));
  base = `http://127.0.0.1:${app.address().port}`;
  globalThis.GUIDE = `${up}/xmltv.php`;
});
after(async () => { upstream.close(); app.close(); await rm(CACHE, { force: true }); await rm(`${CACHE}.tmp`, { force: true }); });

test('GET /fixtures: key, normalised fixtures in the window, live first by time', async () => {
  assert.equal((await fetch(`${base}/fixtures`)).status, 403);
  const r = await fetch(`${base}/fixtures?key=k3y&days=3`);
  assert.equal(r.status, 200);
  const d = await r.json();
  assert.ok(d.at > 0);
  assert.equal(d.sources['eng.1'].ok, true);
  assert.deepEqual(d.fixtures.map(f => f.id), ['eng.1:1', 'eng.1:2']);
  const [live, next] = d.fixtures;
  assert.equal(live.status, 'live');
  assert.equal(live.minute, "20'");
  assert.equal(live.home.name, 'Liverpool');
  assert.equal(live.compRank, 100);
  assert.equal(next.home.short, 'Man Utd');
  assert.equal(next.home.score, undefined);
});

test('POST /fixtures/channels: only the user\'s channels, the live airing, both teams', async () => {
  const body = { urls: [globalThis.GUIDE, globalThis.GUIDE], ids: ['ssme', 'tnt1'], names: [] };
  const d = await (await fetch(`${base}/fixtures/channels?key=k3y`, { method: 'POST', body: JSON.stringify(body) })).json();
  assert.deepEqual(d.channels['eng.1:1'].map(c => [c.id, c.live, c.title]), [['ssme', true, 'Live PL: Liverpool v Arsenal']]);
  assert.deepEqual(d.channels['eng.1:2'].map(c => c.id), ['tnt1']);
  const none = await (await fetch(`${base}/fixtures/channels?key=k3y`, { method: 'POST', body: JSON.stringify({ ...body, ids: ['tnt1'] }) })).json();
  assert.equal(none.channels['eng.1:1'], undefined);
  assert.equal((await fetch(`${base}/fixtures/channels?key=k3y`, { method: 'POST', body: JSON.stringify({ urls: ['http://'] }) })).status, 400);
});

test('a failing source keeps its last good fixtures, and the cache reaches disk', async () => {
  const { _state } = await import('../server/fixtures.js');
  failPl = true; _state.at = 0; const before = hits;
  const d = await (await fetch(`${base}/fixtures?key=k3y`)).json();   // starts a refresh, answers from memory
  await new Promise(r => setTimeout(r, 300));
  assert.ok(hits > before);
  assert.equal(d.fixtures.length, 2);
  const after = await (await fetch(`${base}/fixtures?key=k3y`)).json();
  assert.equal(after.sources['eng.1'].ok, false);
  assert.deepEqual(after.fixtures.map(f => f.id), ['eng.1:1', 'eng.1:2']);
  await new Promise(r => setTimeout(r, 2300));
  const disk = JSON.parse(await readFile(CACHE, 'utf8'));
  assert.equal(disk.sources['eng.1'].fixtures.length, 2);
});
