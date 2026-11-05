// Extras for the film and series pages, each behind its own optional key (kept on this server only, like TMDB's):
//
//   OMDb (omdbapi.com): IMDb rating, Rotten Tomatoes % and Metacritic score, looked up by IMDb id. The free key allows
//   1,000 requests a day, so every answer is kept for three weeks, at most 900 lookups go out in a day, and lookups only
//   happen when a details page opens or a spotlight slide shows (never in bulk). Without a key nothing is fetched.
//
//   Fanart.tv: title logos ("clear logos") for the few titles TMDB has none for. Optional; no key by default.
//
//   GET  /tmdb/ratings?key=..&imdb=tt1234567          -> { imdb: 8.1, rt: 93, mc: 75 } (only the ones known; {} without a key)
//   POST /tmdb/omdb-key?key=..    body { key }       -> checks the key with OMDb, then saves it ('' removes it)
//   POST /tmdb/fanart-key?key=..  body { key }       -> checks the key with Fanart.tv, then saves it ('' removes it)

import { promises as fs } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = process.env.M3U_DATA_DIR || fileURLToPath(new URL('../data/', import.meta.url));
const RATINGS_TTL = 21 * 86400_000, MISS_TTL = 7 * 86400_000;
const DAILY_CAP = 900;   // OMDb's free tier is 1,000 a day: leave room for anything else using the key

// ---------- keys ----------
const keys = { omdb: null, fanart: null };   // null = not read yet
const ENV = { omdb: 'OMDB_KEY', fanart: 'FANART_KEY' };
export async function extraKey(name) {
  if (keys[name] === null) {
    try { keys[name] = JSON.parse(await fs.readFile(join(DIR, `${name}.json`), 'utf8')).key || ''; } catch { keys[name] = process.env[ENV[name]] || ''; }
  }
  return keys[name];
}
async function saveKey(name, k) {
  await fs.mkdir(DIR, { recursive: true });
  await fs.writeFile(join(DIR, `${name}.json`), JSON.stringify({ key: k }));
  keys[name] = k;
}

// ---------- OMDb ratings ----------
let cache = null, saveTimer = 0;
const CACHE_FILE = () => join(DIR, 'omdb-cache.json');
async function loadCache() {
  if (!cache) { try { cache = new Map(Object.entries(JSON.parse(await fs.readFile(CACHE_FILE(), 'utf8')))); } catch { cache = new Map(); } }
  return cache;
}
function saveSoon() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    try {
      const now = Date.now();
      const keep = [...cache].filter(([, v]) => now - v.at < (v.miss ? MISS_TTL : RATINGS_TTL)).slice(-8000);
      await fs.mkdir(DIR, { recursive: true });
      await fs.writeFile(CACHE_FILE(), JSON.stringify(Object.fromEntries(keep)));
    } catch {}
  }, 5000);
  saveTimer.unref?.();
}
const budget = { day: '', used: 0, out: false };
function spend() {
  const day = new Date().toISOString().slice(0, 10);
  if (budget.day !== day) Object.assign(budget, { day, used: 0, out: false });
  if (budget.out || budget.used >= DAILY_CAP) return false;
  budget.used++;
  return true;
}

/** OMDb's answer -> { imdb, rt, mc } with only the numbers it actually has. */
export function parseOmdb(j) {
  const out = {};
  const imdb = Number(j?.imdbRating);
  if (imdb > 0) out.imdb = Math.round(imdb * 10) / 10;
  const rt = (j?.Ratings || []).find(r => /rotten/i.test(r.Source))?.Value;
  if (/^\d{1,3}%$/.test(rt || '')) out.rt = Number(rt.slice(0, -1));
  const mc = Number(j?.Metascore);
  if (mc > 0) out.mc = mc;
  return out;
}

const inflight = new Map();
export async function ratings(imdb) {
  const key = await extraKey('omdb');
  if (!key || !/^tt\d{5,10}$/.test(imdb || '')) return {};
  const c = await loadCache();
  const hit = c.get(imdb);
  if (hit && Date.now() - hit.at < (hit.miss ? MISS_TTL : RATINGS_TTL)) return hit.data;
  if (inflight.has(imdb)) return inflight.get(imdb);
  const job = (async () => {
    if (!spend()) return hit?.data || {};   // over today's allowance: an older answer, or nothing
    try {
      const res = await fetch(`https://www.omdbapi.com/?apikey=${encodeURIComponent(key)}&i=${imdb}`, { signal: AbortSignal.timeout(10_000) });
      const j = await res.json().catch(() => ({}));
      if (/limit reached/i.test(j.Error || '')) { budget.out = true; return hit?.data || {}; }
      if (!res.ok && j.Response !== 'False') return hit?.data || {};
      const data = j.Response === 'False' ? {} : parseOmdb(j);
      c.set(imdb, { at: Date.now(), data, ...(Object.keys(data).length ? {} : { miss: 1 }) });
      saveSoon();
      return data;
    } catch { return hit?.data || {}; }
  })().finally(() => inflight.delete(imdb));
  inflight.set(imdb, job);
  return job;
}

// ---------- Fanart.tv logos (a gap-filler for TMDB) ----------
/** The best English logo from a Fanart.tv answer (HD first, then most liked). */
export function pickFanartLogo(j, kind) {
  const sets = kind === 'tv' ? [j?.hdtvlogo, j?.clearlogo] : [j?.hdmovielogo, j?.movielogo];
  for (const set of sets) {
    const en = (set || []).filter(l => l.lang === 'en' || l.lang === '00' || !l.lang).sort((a, b) => (b.likes || 0) - (a.likes || 0));
    if (en[0]?.url) return en[0].url;
  }
  return '';
}
export async function fanartLogo(kind, tmdbId, tvdbId) {
  const key = await extraKey('fanart');
  const id = kind === 'tv' ? tvdbId : tmdbId;
  if (!key || !id) return '';
  try {
    const res = await fetch(`https://webservice.fanart.tv/v3/${kind === 'tv' ? 'tv' : 'movies'}/${id}?api_key=${encodeURIComponent(key)}`, { signal: AbortSignal.timeout(10_000) });
    if (!res.ok) return '';
    return pickFanartLogo(await res.json(), kind);
  } catch { return ''; }
}

export async function extrasStatus() { return { omdb: !!(await extraKey('omdb')), fanart: !!(await extraKey('fanart')) }; }

// ---------- routes (called from tmdb.js, which has already checked the proxy key) ----------
export async function handleExtras(req, url, json, readBody) {
  if (url.pathname === '/tmdb/ratings') return json(200, await ratings(String(url.searchParams.get('imdb') || '')));
  const which = url.pathname === '/tmdb/omdb-key' ? 'omdb' : url.pathname === '/tmdb/fanart-key' ? 'fanart' : null;
  if (!which || req.method !== 'POST') return null;
  const body = JSON.parse(await readBody(req, 4096) || '{}');
  const k = String(body.key || '').trim();
  if (k && !/^[A-Za-z0-9_-]{6,80}$/.test(k)) return json(400, { error: `That does not look like ${which === 'omdb' ? 'an OMDb' : 'a Fanart.tv'} key` });
  if (k) {
    const check = which === 'omdb' ? `https://www.omdbapi.com/?apikey=${encodeURIComponent(k)}&i=tt0111161` : `https://webservice.fanart.tv/v3/movies/550?api_key=${encodeURIComponent(k)}`;
    const res = await fetch(check, { signal: AbortSignal.timeout(10_000) });
    const j = await res.json().catch(() => ({}));
    if (res.status === 401 || res.status === 403 || j.Response === 'False' || /invalid/i.test(j['error message'] || j.Error || '')) return json(400, { error: `${which === 'omdb' ? 'OMDb' : 'Fanart.tv'} did not accept that key` });
  }
  await saveKey(which, k);
  if (which === 'omdb') budget.out = false;
  console.log(`[${which}] key ${k ? 'saved' : 'removed'}`);
  return json(200, { configured: !!k });
}
