// Real fixtures for the Sports page. The TV guide alone is a noisy source of "what's on" (replays, re-airs, placeholders,
// foreign copies); the fixture list says what is actually being played, and the guide is only used to find which of the
// user's channels shows each game.
//
//   GET  /fixtures?key=<PROXY_KEY>&days=3            -> { at, sources: { [key]: { at, ok } }, fixtures: [fixture…] }
//        live now + the next `days` days (max 7) + games that ended in the last 3 hours; fixture shape in js/fixtures.js
//   POST /fixtures/channels?key=…  body { urls, ids, names, days? }
//        -> { at, guideAt, channels: { [fixtureId]: [{ id, name, start, end, title, desc, live }] } }
//        (urls = the playlist's guide links, ids/names = its channels, exactly as for POST /guide)
//
// Source: ESPN's public site API (no key). Personal use only — see the note in docs or the PR. Each competition is
// fetched on its own, so one failing keeps its last good copy; the whole set is kept on disk (data/fixtures-cache.json)
// so a restart doesn't start empty. Refreshed every 15 minutes while someone has asked in the last 6 hours, and every
// 2 minutes for competitions with a game on.

import { promises as fs } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { normaliseEspn, programmeIndex, matchChannels, sortFixtures, isOn } from '../js/fixtures.js';
import { readBody } from './sync.js';
import { guideIndex } from './guide.js';

const PROXY_KEY = process.env.PROXY_KEY || '';
const BASE = process.env.FIXTURES_BASE || 'https://site.api.espn.com/apis/site/v2/sports';
const DIR = fileURLToPath(new URL('../data/', import.meta.url));
const CACHE_FILE = process.env.FIXTURES_CACHE || join(DIR, 'fixtures-cache.json');
const MIN = 60_000, HOUR = 60 * MIN, DAY = 24 * HOUR;
const FULL_EVERY = 15 * MIN, LIVE_EVERY = 2 * MIN, IDLE_AFTER = 6 * HOUR, WINDOW = 7 * DAY, KEEP_PAST = 12 * HOUR;

// rank: overrides the IMPORTANCE score of comp (sports.js); rankAs: use another competition's score.
// by: 'month' (one request per calendar month the window touches) or 'day' (busy US leagues: one per day).
export const SOURCES = [
  { key: 'eng.1', path: 'soccer/eng.1', sport: 'football', comp: 'Premier League' },
  { key: 'uefa.champions', path: 'soccer/uefa.champions', sport: 'football', comp: 'Champions League' },
  { key: 'eng.fa', path: 'soccer/eng.fa', sport: 'football', comp: 'FA Cup' },
  { key: 'uefa.europa', path: 'soccer/uefa.europa', sport: 'football', comp: 'Europa League' },
  { key: 'uefa.europa.conf', path: 'soccer/uefa.europa.conf', sport: 'football', comp: 'Conference League', rank: 80 },
  { key: 'eng.league_cup', path: 'soccer/eng.league_cup', sport: 'football', comp: 'Carabao Cup' },
  { key: 'sco.1', path: 'soccer/sco.1', sport: 'football', comp: 'Scottish Premiership' },
  { key: 'eng.2', path: 'soccer/eng.2', sport: 'football', comp: 'Championship', rankAs: 'EFL' },
  { key: 'esp.1', path: 'soccer/esp.1', sport: 'football', comp: 'LaLiga' },
  { key: 'ita.1', path: 'soccer/ita.1', sport: 'football', comp: 'Serie A' },
  { key: 'ger.1', path: 'soccer/ger.1', sport: 'football', comp: 'Bundesliga' },
  { key: 'fra.1', path: 'soccer/fra.1', sport: 'football', comp: 'Ligue 1', rank: 64 },
  { key: 'f1', path: 'racing/f1', sport: 'f1', comp: 'F1' },
  { key: 'nfl', path: 'football/nfl', sport: 'nfl', comp: 'NFL', by: 'day' },
  { key: 'nba', path: 'basketball/nba', sport: 'nba', comp: 'NBA', by: 'day' },
  { key: 'ufc', path: 'mma/ufc', sport: 'ufc', comp: 'UFC' },
  { key: 'rugby.6n', path: 'rugby/180659', sport: 'rugby', comp: 'Six Nations' },
  { key: 'rugby.tests', path: 'rugby/289234', sport: 'rugby', comp: 'Rugby internationals', rankAs: 'Rugby' },
  { key: 'rugby.prem', path: 'rugby/267979', sport: 'rugby', comp: 'Premiership Rugby', rankAs: 'Rugby' },
  { key: 'rugby.urc', path: 'rugby/270557', sport: 'rugby', comp: 'URC', rank: 56 },
  { key: 'rugby.ecc', path: 'rugby/271937', sport: 'rugby', comp: 'Champions Cup', rankAs: 'Rugby' },
];

// The feed's days are US Eastern days (an NBA game at 00:30 UTC is "yesterday").
const etDay = t => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/New_York', year: 'numeric', month: '2-digit', day: '2-digit' }).format(t).replace(/-/g, '');

/** The scoreboard requests for one source covering [from, to]. */
export function requestsFor(src, from, to) {
  const out = new Set();
  if (src.by === 'day') for (let t = from; t <= Math.min(to, from + 5 * DAY); t += DAY) out.add(`dates=${etDay(t)}`);   // busy leagues: 5 days is plenty
  else for (let t = from; t <= to + DAY; t += 20 * DAY) out.add(`dates=${etDay(t).slice(0, 6)}&limit=200`);
  if (src.by !== 'day') out.add(`dates=${etDay(to).slice(0, 6)}&limit=200`);
  return [...out].map(q => `${BASE}/${src.path}/scoreboard?${q}`);
}

const state = { loaded: false, at: 0, liveAt: 0, asked: 0, job: null, liveJob: null, sources: {} };   // key -> { at, ok, error, fixtures }

async function getJson(url) {
  const res = await fetch(url, { headers: { accept: 'application/json', 'user-agent': 'Mozilla/5.0 (m3u-player fixtures)' }, signal: AbortSignal.timeout(12_000) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const text = await res.text();
  if (text.length > 6_000_000) throw new Error('reply too big');
  return JSON.parse(text);
}

async function fetchSource(src, urls) {
  const byId = new Map();
  for (const u of urls) {
    const d = await getJson(u);
    for (const ev of d.events || []) for (const f of normaliseEspn(ev, src)) byId.set(f.id, f);
  }
  return [...byId.values()];
}
const inWindow = (f, now) => f.start < now + WINDOW && (f.end > now - KEEP_PAST || f.status === 'live');

let saveTimer = 0;
function saveSoon() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    try {
      await fs.mkdir(DIR, { recursive: true });
      const tmp = `${CACHE_FILE}.tmp`;
      await fs.writeFile(tmp, JSON.stringify({ at: state.at, sources: state.sources }));
      await fs.rename(tmp, CACHE_FILE);
    } catch (e) { console.error('[fixtures] save failed:', e.message); }
  }, 2000);
  saveTimer.unref?.();
}
async function load() {
  if (state.loaded) return;
  state.loaded = true;
  try {
    const d = JSON.parse(await fs.readFile(CACHE_FILE, 'utf8'));
    const now = Date.now();
    for (const src of SOURCES) {
      const s = d.sources?.[src.key];
      if (s && Array.isArray(s.fixtures)) state.sources[src.key] = { at: s.at || 0, ok: s.ok !== false, error: s.error || '', fixtures: s.fixtures.filter(f => inWindow(f, now)) };
    }
    state.at = d.at || 0;
  } catch {}
}

// A few requests at a time; a source that fails keeps the fixtures it had (marked not ok).
async function runAll(jobs) {
  let i = 0;
  await Promise.all([0, 1, 2, 3].map(async () => { while (i < jobs.length) await jobs[i++](); }));
}
async function refreshAll() {
  const now = Date.now(), t0 = now;
  let ok = 0;
  await runAll(SOURCES.map(src => async () => {
    const prev = state.sources[src.key];
    try {
      const list = (await fetchSource(src, requestsFor(src, now - (src.by === 'day' ? DAY : 6 * HOUR), now + WINDOW))).filter(f => inWindow(f, now));
      state.sources[src.key] = { at: Date.now(), ok: true, error: '', fixtures: list };
      ok++;
    } catch (e) {
      state.sources[src.key] = { at: prev?.at || 0, ok: false, error: String(e.message || e).slice(0, 120), fixtures: (prev?.fixtures || []).filter(f => inWindow(f, now)) };
    }
  }));
  state.at = Date.now(); state.liveAt = state.at;
  console.log(`[fixtures] ${ok}/${SOURCES.length} competitions, ${allFixtures().length} fixtures in ${((Date.now() - t0) / 1000).toFixed(1)}s`);
  saveSoon();
}
// Games on (or due on) now: only their competitions' days are fetched again.
async function refreshLive() {
  const now = Date.now(), want = new Map();
  for (const src of SOURCES) {
    const days = new Set();
    for (const f of state.sources[src.key]?.fixtures || []) if (isOn(f, now) || (f.status === 'scheduled' && f.start <= now + LIVE_EVERY && f.start > now - 6 * HOUR)) days.add(etDay(f.start));
    if (days.size) want.set(src, [...days].map(d => `${BASE}/${src.path}/scoreboard?dates=${d}`));
  }
  state.liveAt = now;
  if (!want.size) return;
  await runAll([...want].map(([src, urls]) => async () => {
    try {
      const fresh = new Map((await fetchSource(src, urls)).map(f => [f.id, f]));
      const s = state.sources[src.key];
      s.fixtures = s.fixtures.map(f => fresh.get(f.id) || f);
    } catch {}
  }));
  saveSoon();
}
const anyOn = now => SOURCES.some(src => (state.sources[src.key]?.fixtures || []).some(f => isOn(f, now) || (f.status === 'scheduled' && f.start <= now + LIVE_EVERY && f.start > now - 6 * HOUR)));

function tick() {
  const now = Date.now();
  if (state.job || state.liveJob) return state.job;
  if (now - state.at > FULL_EVERY) {
    state.job = refreshAll().catch(e => console.error('[fixtures]', e.message)).finally(() => { state.job = null; });
    return state.job;
  }
  if (now - state.liveAt > LIVE_EVERY && anyOn(now)) state.liveJob = refreshLive().catch(() => {}).finally(() => { state.liveJob = null; });
  return null;
}
setInterval(() => { if (state.asked && Date.now() - state.asked < IDLE_AFTER) tick(); }, MIN).unref();

function allFixtures() {
  return SOURCES.flatMap(src => state.sources[src.key]?.fixtures || []);
}
/** The fixtures from now (and anything on) to `days` ahead, plus results from the last 3 hours. */
export async function currentFixtures(days = 3) {
  await load();
  state.asked = Date.now();
  const job = tick();
  if (job && !allFixtures().length) {   // first ever request: wait for the feed (not forever)
    let timer;
    await Promise.race([job, new Promise(r => { timer = setTimeout(r, 25_000); })]);
    clearTimeout(timer);
  }
  const now = Date.now(), until = now + Math.min(7, Math.max(1, days)) * DAY;
  return sortFixtures(allFixtures().filter(f => f.start < until && (f.end > now - 3 * HOUR || isOn(f, now))));
}

const sendJson = (req, res, send, obj) => {
  const body = JSON.stringify(obj);
  const gz = body.length > 2000 && /\bgzip\b/.test(req.headers['accept-encoding'] || '');
  send(res, 200, gz ? gzipSync(body) : body, { 'content-type': 'application/json', 'cache-control': 'no-store', ...(gz ? { 'content-encoding': 'gzip', vary: 'accept-encoding' } : {}) });
};

// The guide's sport programmes on one set of channels, by hour: built once per guide build / channel set (and at most
// every 20 minutes as the window moves), so a request is a few small lookups per fixture.
const progIndexes = new Map();   // key -> { at, guideAt, byHour }
function progIndexFor(ix, only, now) {
  let h = 0;
  for (const id of [...only].sort()) for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) | 0;
  const key = `${ix.at}|${only.size}|${h}`;
  let hit = progIndexes.get(key);
  if (hit && now - hit.at < 20 * MIN) return hit.byHour;
  const progs = [], until = now + 8 * DAY;
  for (const id of only.size ? only : []) {
    const json = ix.chans.get(id);
    if (!json || !/ v | vs|@| at |f1|formula|grand prix|ufc/i.test(json)) continue;
    const name = ix.display?.[id] || id;
    for (const p of JSON.parse(json)) if (p[1] > now - 4 * HOUR && p[0] < until) progs.push({ id, name, start: p[0], end: p[1], title: p[2], desc: p[3] || '' });
  }
  const byHour = programmeIndex(progs, now);
  while (progIndexes.size >= 4) progIndexes.delete(progIndexes.keys().next().value);
  progIndexes.set(key, { at: now, byHour });
  return byHour;
}

export async function handleFixtures(req, res, url, send, allow) {
  if (PROXY_KEY && url.searchParams.get('key') !== PROXY_KEY) return send(res, 403, 'Wrong or missing key');
  if (url.pathname === '/fixtures') {
    if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, 'GET only');
    const days = Number(url.searchParams.get('days')) || 3;
    const fixtures = await currentFixtures(days);
    const sources = Object.fromEntries(SOURCES.map(s => [s.key, { at: state.sources[s.key]?.at || 0, ok: state.sources[s.key]?.ok !== false && !!state.sources[s.key] }]));
    return sendJson(req, res, send, { at: state.at, sources, fixtures });
  }
  if (url.pathname === '/fixtures/channels') {
    if (req.method !== 'POST') return send(res, 405, 'POST only');
    let q;
    try { q = JSON.parse(await readBody(req)); } catch { return send(res, 400, 'Bad body'); }
    const urls = [...new Set((Array.isArray(q.urls) ? q.urls : []).map(String))].filter(u => { try { return allow(new URL(u)); } catch { return false; } }).slice(0, 4);
    if (!urls.length) return send(res, 400, 'No usable guide link');
    const [fixtures, ix] = await Promise.all([currentFixtures(Number(q.days) || 3), guideIndex(urls, allow)]);
    if (!ix?.chans) return send(res, 502, 'TV guide not ready');
    const only = new Set();
    for (const id of q.ids || []) if (ix.chans.has(id)) only.add(id);
    for (const n of q.names || []) { const id = ix.names[n]; if (id && ix.chans.has(id)) only.add(id); }
    const now = Date.now();
    const channels = matchChannels(fixtures, progIndexFor(ix, only, now));
    return sendJson(req, res, send, { at: state.at, guideAt: ix.at, channels });
  }
  send(res, 404, 'Not found');
}

export const _state = state;   // tests
