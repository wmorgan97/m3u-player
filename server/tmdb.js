// TMDB (themoviedb.org): real trending lists, wide backdrops, plots, cast, trailers and ratings with vote
// counts. The owner's TMDB key lives in data/tmdb.json on this server only; the app never sees it.
//
//   GET  /tmdb/status?key=<PROXY_KEY>                    -> { configured }
//   POST /tmdb/key?key=<PROXY_KEY>   body { key }         -> checks the key with TMDB, then saves it ('' removes it)
//   GET  /tmdb/info?key=..&kind=movie|tv&name=..&year=.. -> details for one title (cached for two weeks)
//   GET  /tmdb/episodes?key=..&name=..&year=..&season=N  -> { episodes: [{ n, title, plot, still, runtime, air }] }
//        (episode plots and stills for a season; cached a day while it's airing, two weeks once it's finished)

import { promises as fs } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readBody } from './sync.js';

const PROXY_KEY = process.env.PROXY_KEY || '';
const DIR = fileURLToPath(new URL('../data/', import.meta.url));
const KEY_FILE = join(DIR, 'tmdb.json'), CACHE_FILE = join(DIR, 'tmdb-cache.json');
const IMG = 'https://image.tmdb.org/t/p/';
const INFO_TTL = 14 * 86400_000, LIST_TTL = 3 * 3600_000;

let apiKey = null;          // null = not read yet
let onChange = () => {};
export const onTmdbChange = fn => { onChange = fn; };

async function loadKey() {
  if (apiKey === null) {
    try { apiKey = JSON.parse(await fs.readFile(KEY_FILE, 'utf8')).key || ''; } catch { apiKey = process.env.TMDB_KEY || ''; }
  }
  return apiKey;
}
export async function tmdbConfigured() { return !!(await loadKey()); }

// v3 "API Key" (32 hex) goes in the query; the long v4 "Read Access Token" goes in a bearer header.
async function call(path, params = {}, key = apiKey) {
  const u = new URL(`https://api.themoviedb.org/3${path}`);
  const bearer = key.length > 40;
  for (const [k, v] of Object.entries({ ...params, ...(bearer ? {} : { api_key: key }) })) if (v !== undefined && v !== '') u.searchParams.set(k, v);
  const res = await fetch(u, { headers: { accept: 'application/json', ...(bearer ? { authorization: `Bearer ${key}` } : {}) }, signal: AbortSignal.timeout(15_000) });
  if (res.status === 401) throw new Error('TMDB did not accept that key');
  if (!res.ok) throw new Error(`TMDB replied ${res.status}`);
  return res.json();
}

const genreNames = new Map();   // 'movie' | 'tv' -> Map(id -> name)
async function genres(kind) {
  if (!genreNames.has(kind)) {
    try { genreNames.set(kind, new Map(((await call(`/genre/${kind}/list`, { language: 'en-US' })).genres || []).map(g => [g.id, g.name]))); }
    catch { return new Map(); }
  }
  return genreNames.get(kind);
}

const slim = (r, kind, g) => ({
  tid: r.id,
  title: r.title || r.name || '',
  year: Number(String(r.release_date || r.first_air_date || '').slice(0, 4)) || 0,
  overview: String(r.overview || '').slice(0, 600),
  backdrop: r.backdrop_path ? `${IMG}w1280${r.backdrop_path}` : '',
  poster: r.poster_path ? `${IMG}w342${r.poster_path}` : '',
  vote: r.vote_count >= 20 ? Math.round((r.vote_average || 0) * 10) / 10 : 0,
  votes: r.vote_count || 0,
  genres: (r.genre_ids || r.genres?.map(x => x.id) || []).map(id => g?.get(id)).filter(Boolean).slice(0, 3),
  kind,
});

// Trending this week (three pages, ~60 titles), most trending first.
const lists = new Map();
export async function trending(kind) {
  if (!(await loadKey())) return [];
  const hit = lists.get(kind);
  if (hit && Date.now() - hit.at < LIST_TTL) return hit.items;
  const g = await genres(kind);
  const pages = await Promise.all([1, 2, 3].map(page => call(`/trending/${kind}/week`, { page, language: 'en-US' }).catch(() => ({ results: [] }))));
  const items = pages.flatMap(p => p.results || []).map(r => slim(r, kind, g));
  if (items.length) lists.set(kind, { at: Date.now(), items });
  return items;
}

// Curated lists for the library sidebar: TMDB's own lists plus one per genre (most popular, well voted).
const COLLECTIONS = {
  movie: [['list:popular', 'Popular', '/movie/popular', {}], ['list:top', 'Top rated', '/movie/top_rated', {}], ['list:now', 'In cinemas', '/movie/now_playing', {}],
    ...[[28, 'Action'], [12, 'Adventure'], [16, 'Animation'], [35, 'Comedy'], [80, 'Crime'], [99, 'Documentary'], [18, 'Drama'], [10751, 'Family'], [14, 'Fantasy'],
      [36, 'History'], [27, 'Horror'], [10402, 'Music'], [9648, 'Mystery'], [10749, 'Romance'], [878, 'Sci-Fi'], [53, 'Thriller'], [10752, 'War'], [37, 'Western']]
      .map(([id, name]) => [`genre:${id}`, name, '/discover/movie', { with_genres: id, sort_by: 'popularity.desc', 'vote_count.gte': 150 }])],
  tv: [['list:popular', 'Popular', '/tv/popular', {}], ['list:top', 'Top rated', '/tv/top_rated', {}], ['list:now', 'On the air', '/tv/on_the_air', {}],
    ...[[10759, 'Action & Adventure'], [16, 'Animation'], [35, 'Comedy'], [80, 'Crime'], [99, 'Documentary'], [18, 'Drama'], [10751, 'Family'], [10762, 'Kids'],
      [9648, 'Mystery'], [10764, 'Reality'], [10765, 'Sci-Fi & Fantasy'], [10768, 'War & Politics'], [37, 'Western']]
      .map(([id, name]) => [`genre:${id}`, name, '/discover/tv', { with_genres: id, sort_by: 'popularity.desc', 'vote_count.gte': 80 }])],
};
const PAGES = 8;   // 160 titles per list before matching to the catalogue
export async function collections(kind) {
  if (!(await loadKey())) return [];
  const ck = `col:${kind}`, hit = lists.get(ck);
  if (hit && Date.now() - hit.at < 4 * LIST_TTL) return hit.items;
  const g = await genres(kind);
  const jobs = COLLECTIONS[kind].flatMap(([id, , path, params]) => Array.from({ length: PAGES }, (_, i) => ({ id, path, params: { ...params, page: i + 1, language: 'en-US' } })));
  const pages = new Map();
  for (let i = 0; i < jobs.length; i += 10) {   // ten at a time: well inside TMDB's rate limit
    await Promise.all(jobs.slice(i, i + 10).map(async j => {
      const r = await call(j.path, j.params).catch(() => ({ results: [] }));
      if (!pages.has(j.id)) pages.set(j.id, []);
      pages.get(j.id)[j.params.page - 1] = r.results || [];
    }));
  }
  const items = COLLECTIONS[kind].map(([id, name]) => ({ id, name, results: (pages.get(id) || []).flat().filter(Boolean).map(r => slim(r, kind, g)) }));
  if (items.some(c => c.results.length)) lists.set(ck, { at: Date.now(), items });
  return items;
}

// Details for one title, by search (title + year). Cached on disk so repeat views cost nothing.
let cache = null, saveTimer = 0;
async function loadCache() {
  if (!cache) { try { cache = new Map(Object.entries(JSON.parse(await fs.readFile(CACHE_FILE, 'utf8')))); } catch { cache = new Map(); } }
  return cache;
}
function saveSoon() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    try {
      const now = Date.now();
      const keep = [...cache].filter(([, v]) => now - v.at < INFO_TTL).slice(-5000);
      await fs.mkdir(DIR, { recursive: true });
      await fs.writeFile(CACHE_FILE, JSON.stringify(Object.fromEntries(keep)));
    } catch {}
  }, 5000);
  saveTimer.unref?.();
}
const norm = s => String(s || '').toLowerCase().replace(/&/g, 'and').replace(/[^a-z0-9]+/g, '');
export async function info(kind, name, year) {
  if (!(await loadKey())) return null;
  const c = await loadCache();
  const ck = `${kind}|${norm(name)}|${year || ''}`;
  const hit = c.get(ck);
  if (hit && Date.now() - hit.at < INFO_TTL) return hit.data;
  const search = await call(`/search/${kind}`, { query: name, language: 'en-US', include_adult: 'false', ...(year ? { [kind === 'movie' ? 'year' : 'first_air_date_year']: year } : {}) });
  let results = search.results || [];
  if (!results.length && year) results = (await call(`/search/${kind}`, { query: name, language: 'en-US', include_adult: 'false' })).results || [];
  // Prefer an exact title match, then the most voted.
  const want = norm(name);
  const best = results.find(r => norm(r.title || r.name) === want) || [...results].sort((a, b) => (b.vote_count || 0) - (a.vote_count || 0))[0];
  let data = null;
  if (best) {
    const d = await call(`/${kind}/${best.id}`, { language: 'en-US', append_to_response: 'videos,credits' });
    const g = await genres(kind);
    const trailer = (d.videos?.results || []).find(v => v.site === 'YouTube' && v.type === 'Trailer') || (d.videos?.results || []).find(v => v.site === 'YouTube');
    data = { ...slim(d, kind, g),
      genres: (d.genres || []).map(x => x.name).slice(0, 4),
      runtime: d.runtime || d.episode_run_time?.[0] || 0,
      tagline: d.tagline || '',
      cast: (d.credits?.cast || []).slice(0, 8).map(p => p.name),
      director: (d.credits?.crew || []).filter(p => p.job === 'Director').map(p => p.name).slice(0, 2).join(', '),
      trailer: trailer?.key || '',
      seasons: d.number_of_seasons || 0,
    };
  }
  c.set(ck, { at: Date.now(), data });
  saveSoon();
  return data;
}

// One season's episodes. Providers often give no plots and only tiny stills; TMDB has both.
export async function episodes(name, year, season) {
  const show = await info('tv', name, year);
  if (!show?.tid) return null;
  const c = await loadCache();
  const ck = `eps|${show.tid}|${season}`;
  const hit = c.get(ck);
  if (hit && Date.now() - hit.at < (hit.done ? INFO_TTL : 86400_000)) return hit.data;
  let d;
  try { d = await call(`/tv/${show.tid}/season/${season}`, { language: 'en-US' }); }
  catch (e) { if (/404/.test(e.message)) d = { episodes: [] }; else throw e; }
  const today = new Date().toISOString().slice(0, 10);
  const list = (d.episodes || []).map(e => ({
    n: e.episode_number, title: e.name || '', plot: String(e.overview || '').slice(0, 600),
    still: e.still_path ? `${IMG}w780${e.still_path}` : '', runtime: e.runtime || 0, air: e.air_date || '',
  }));
  const data = { tid: show.tid, season, episodes: list };
  c.set(ck, { at: Date.now(), done: list.length > 0 && list.every(e => e.air && e.air < today && e.plot), data });
  saveSoon();
  return data;
}

export async function handleTmdb(req, res, url, send) {
  if (PROXY_KEY && url.searchParams.get('key') !== PROXY_KEY) return send(res, 403, 'Wrong or missing key');
  const json = (code, obj) => send(res, code, JSON.stringify(obj), { 'content-type': 'application/json', 'cache-control': 'no-store' });
  try {
    if (url.pathname === '/tmdb/status') return json(200, { configured: await tmdbConfigured() });
    if (url.pathname === '/tmdb/key' && req.method === 'POST') {
      const body = JSON.parse(await readBody(req, 4096) || '{}');
      const k = String(body.key || '').trim();
      if (k && !/^[A-Za-z0-9._-]{20,400}$/.test(k)) return json(400, { error: 'That does not look like a TMDB key' });
      if (k) await call('/configuration', {}, k);          // throws if TMDB rejects it
      await fs.mkdir(DIR, { recursive: true });
      await fs.writeFile(KEY_FILE, JSON.stringify({ key: k }));
      apiKey = k; lists.clear(); genreNames.clear();
      onChange();
      console.log(`[tmdb] key ${k ? 'saved' : 'removed'}`);
      return json(200, { configured: !!k });
    }
    if (url.pathname === '/tmdb/info') {
      const kind = url.searchParams.get('kind') === 'tv' ? 'tv' : 'movie';
      const name = String(url.searchParams.get('name') || '').slice(0, 200);
      const year = Number(url.searchParams.get('year')) || 0;
      if (!name) return json(400, { error: 'Missing name' });
      return json(200, (await info(kind, name, year)) || {});
    }
    if (url.pathname === '/tmdb/episodes') {
      const name = String(url.searchParams.get('name') || '').slice(0, 200);
      const year = Number(url.searchParams.get('year')) || 0, season = Number(url.searchParams.get('season'));
      if (!name || !(season >= 0 && season < 200)) return json(400, { error: 'Missing name or season' });
      return json(200, (await episodes(name, year, season)) || { episodes: [] });
    }
    return send(res, 404, 'Not found');
  } catch (e) {
    return json(502, { error: e.message });
  }
}
