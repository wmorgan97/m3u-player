// Film and series page extras: title logos, "Also on" providers (TMDB / JustWatch), OMDb ratings and the Fanart.tv
// gap-filler. Every outside service is mocked with recorded sample answers (tests/filmpage-fixtures); nothing goes to the network.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const dataDir = mkdtempSync(join(tmpdir(), 'm3u-filmpage-'));
process.env.M3U_DATA_DIR = dataDir;
process.env.PROXY_KEY = '';
writeFileSync(join(dataDir, 'tmdb.json'), JSON.stringify({ key: 'a'.repeat(32) }));

const fixture = name => JSON.parse(readFileSync(new URL(`./filmpage-fixtures/${name}`, import.meta.url), 'utf8'));
const calls = [];
const realFetch = globalThis.fetch;
let omdbReply = () => fixture('omdb-tt1375666.json');
globalThis.fetch = async (u, opts) => {
  const url = new URL(String(u));
  if (url.hostname === '127.0.0.1') return realFetch(u, opts);
  calls.push(url);
  const ok = body => new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } });
  if (url.hostname === 'api.themoviedb.org') {
    if (url.pathname === '/3/search/movie') return ok({ results: [{ id: 27205, title: 'Inception', vote_count: 37000 }] });
    if (url.pathname === '/3/movie/27205') return ok(fixture('tmdb-movie-27205.json'));
    if (url.pathname.startsWith('/3/genre/')) return ok({ genres: [] });
  }
  if (url.hostname === 'www.omdbapi.com') {
    if (url.searchParams.get('apikey') === 'badkey1') return new Response(JSON.stringify({ Response: 'False', Error: 'Invalid API key!' }), { status: 401 });
    return ok(omdbReply(url));
  }
  if (url.hostname === 'webservice.fanart.tv') return ok(fixture('fanart-tv-81189.json'));
  return new Response('not mocked', { status: 404 });
};

const { createAppServer } = await import('../server/server.js');
const { pickLogo, providersOf } = await import('../server/tmdb.js');
const { parseOmdb, pickFanartLogo } = await import('../server/extras.js');

let app, base;
before(async () => { app = createAppServer().listen(0); await new Promise(r => app.once('listening', r)); base = `http://127.0.0.1:${app.address().port}`; });
after(() => { app.close(); globalThis.fetch = realFetch; rmSync(dataDir, { recursive: true, force: true }); });
const get = async p => (await realFetch(`${base}${p}`)).json();
const post = async (p, body) => { const r = await realFetch(`${base}${p}`, { method: 'POST', body: JSON.stringify(body) }); return { status: r.status, body: await r.json() }; };

test('picks a wide English PNG logo over square, SVG and other languages', () => {
  const l = pickLogo(fixture('tmdb-movie-27205.json').images.logos);
  assert.equal(l.logo, 'https://image.tmdb.org/t/p/original/wide-en.png');
  assert.equal(l.logoAspect, 4.36);
  assert.deepEqual(pickLogo([{ iso_639_1: 'fr', file_path: '/x.png', aspect_ratio: 3 }]), { logo: '', logoAspect: 0 });
});

test('UK providers: subscriptions first, then free / with ads; one per service; no add-on channels or rentals', () => {
  const p = providersOf(fixture('tmdb-movie-27205.json')['watch/providers']);
  assert.deepEqual(p.map(x => x.name), ['Netflix', 'Prime Video', 'ITVX']);
  assert.equal(p[0].logo, 'https://image.tmdb.org/t/p/w92/netflix.jpg');
  assert.deepEqual(providersOf({ results: {} }), []);
});

test('OMDb and Fanart answers parse to just what they have', () => {
  assert.deepEqual(parseOmdb(fixture('omdb-tt1375666.json')), { imdb: 8.8, rt: 87, mc: 74 });
  assert.deepEqual(parseOmdb({ imdbRating: 'N/A', Metascore: 'N/A', Ratings: [] }), {});
  assert.equal(pickFanartLogo(fixture('fanart-tv-81189.json'), 'tv'), 'https://assets.fanart.tv/fanart/tv/81189/hdtvlogo/en-b.png');
});

test('/tmdb/info carries the logo, UK providers and IMDb id; cached after the first open', async () => {
  const d = await get('/tmdb/info?kind=movie&name=Inception&year=2010');
  assert.equal(d.logo, 'https://image.tmdb.org/t/p/original/wide-en.png');
  assert.equal(d.imdb, 'tt1375666');
  assert.equal(d.providers.length, 3);
  const detail = calls.find(u => u.pathname === '/3/movie/27205');
  assert.match(detail.searchParams.get('append_to_response'), /images/);
  assert.match(detail.searchParams.get('append_to_response'), /watch\/providers/);
  const n = calls.length;
  await get('/tmdb/info?kind=movie&name=Inception&year=2010');
  assert.equal(calls.length, n, 'second open is served from the cache');
});

test('ratings: nothing without an OMDb key; with one, fetched once and cached', async () => {
  assert.deepEqual(await get('/tmdb/status'), { configured: true, omdb: false, fanart: false });
  assert.deepEqual(await get('/tmdb/ratings?imdb=tt1375666'), {});
  assert.equal(calls.filter(u => u.hostname === 'www.omdbapi.com').length, 0);

  const bad = await post('/tmdb/omdb-key', { key: 'badkey1' });
  assert.equal(bad.status, 400);
  assert.equal((await post('/tmdb/omdb-key', { key: 'goodkey1' })).status, 200);
  assert.equal((await get('/tmdb/status')).omdb, true);

  const before = calls.filter(u => u.hostname === 'www.omdbapi.com').length;
  assert.deepEqual(await get('/tmdb/ratings?imdb=tt1375666'), { imdb: 8.8, rt: 87, mc: 74 });
  await get('/tmdb/ratings?imdb=tt1375666');
  assert.equal(calls.filter(u => u.hostname === 'www.omdbapi.com').length, before + 1, 'one lookup per title');
  assert.deepEqual(await get('/tmdb/ratings?imdb=not-an-id'), {});

  // Over the daily allowance: OMDb says so, the server stops asking and serves what it has.
  omdbReply = () => ({ Response: 'False', Error: 'Request limit reached!' });
  assert.deepEqual(await get('/tmdb/ratings?imdb=tt0000002'), {});
  const after1 = calls.filter(u => u.hostname === 'www.omdbapi.com').length;
  await get('/tmdb/ratings?imdb=tt0000003');
  assert.equal(calls.filter(u => u.hostname === 'www.omdbapi.com').length, after1);

  assert.equal((await post('/tmdb/omdb-key', { key: '' })).body.configured, false);
});

test('Fanart.tv key is optional and checked before it is saved', async () => {
  assert.equal((await post('/tmdb/fanart-key', { key: 'fanartkey1' })).status, 200);
  assert.equal((await get('/tmdb/status')).fanart, true);
  assert.equal((await post('/tmdb/fanart-key', { key: '' })).body.configured, false);
});
