// Xtream movie/series catalogues via the server. Providers answer get_vod_streams with 50-70MB
// of JSON (180k+ titles); a phone can't download and parse that comfortably. The server fetches
// it, keeps only the fields the app shows, caches it per account for 12 hours and serves it gzipped
// (typically a few MB -> under 1MB on the wire). Credentials are used for the upstream call only
// and never logged or stored.
//
//   POST /vod?key=<PROXY_KEY>   body: { server, user, pass, kind: 'movies' | 'series', group?, q?, force? }
//   overview (no group/q) -> { cats: [{ category_id, category_name, count }], recent: [...300 newest], total }
//   group: <category_id>    -> { items: [that category's titles] }
//   q: 'text'               -> { items: [up to 400 matching titles] }
// The app never downloads the whole catalogue: a group is a few hundred titles, search is server-side.

import { createHash } from 'node:crypto';
import { gzipSync } from 'node:zlib';
import { readBody } from './sync.js';

const PROXY_KEY = process.env.PROXY_KEY || '';
const TTL = 12 * 3600_000;
const cache = new Map();   // key -> { at, job: Promise<{ raw, gz }> }

const num = v => (v === null || v === undefined || v === '' ? undefined : Number(v) || undefined);
const str = (v, max = 400) => (v ? String(v).slice(0, max) : undefined);
const slimMovie = s => ({ stream_id: s.stream_id, name: str(s.name, 200), stream_icon: str(s.stream_icon, 300), category_id: s.category_id ?? s.category_ids?.[0],
  rating: num(s.rating), year: str(s.year, 10), added: num(s.added), container_extension: str(s.container_extension, 8) });
const slimSeries = s => ({ series_id: s.series_id, name: str(s.name, 200), cover: str(s.cover, 300), category_id: s.category_id ?? s.category_ids?.[0],
  rating: num(s.rating), releaseDate: str(s.releaseDate || s.release_date, 12), plot: str(s.plot, 240), genre: str(s.genre, 60), added: num(s.last_modified) });
// "4K-TOP - Dune (2021)" and "TOP - Dune (2021)" are the same title for a shelf: keep the first.
const titleKey = it => String(it.name || '').toLowerCase().replace(/^[^-|]{1,14}[-|]\s*/, '').replace(/[^a-z0-9]+/g, '');
const uniq = (list, n) => { const seen = new Set(), out = []; for (const it of list) { const k = titleKey(it); if (k && !seen.has(k)) { seen.add(k); out.push(it); if (out.length >= n) break; } } return out; };

async function api(base, user, pass, action) {
  const u = new URL('/player_api.php', base);
  u.search = new URLSearchParams({ username: user, password: pass, action });
  const res = await fetch(u, { headers: { 'user-agent': 'Mozilla/5.0' }, signal: AbortSignal.timeout(180_000) });
  if (!res.ok) throw new Error(`The provider replied ${res.status}`);
  const data = await res.json();
  return Array.isArray(data) ? data : [];
}

async function build(server, user, pass, kind) {
  const t0 = Date.now();
  const [cats, list] = kind === 'movies'
    ? await Promise.all([api(server, user, pass, 'get_vod_categories'), api(server, user, pass, 'get_vod_streams')])
    : await Promise.all([api(server, user, pass, 'get_series_categories'), api(server, user, pass, 'get_series')]);
  const items = list.map(kind === 'movies' ? slimMovie : slimSeries);
  const byCat = new Map();
  for (const it of items) { const k = String(it.category_id ?? ''); if (!byCat.has(k)) byCat.set(k, []); byCat.get(k).push(it); }
  const catList = cats.map(c => ({ category_id: c.category_id, category_name: str(c.category_name, 120), count: byCat.get(String(c.category_id))?.length || 0 })).filter(c => c.count);
  const byAdded = items.some(it => it.added) ? [...items].sort((a, b) => (b.added || 0) - (a.added || 0)) : [...items].reverse();
  const recent = uniq(byAdded, 300);
  // Top rated, recent-ish: rated 7+, from the last three years (by year/release date, else when added).
  const nowYear = new Date().getFullYear(), yearAgo = Date.now() / 1000 - 3 * 365 * 86400;
  const yearOf = it => Number(String(it.year || it.releaseDate || '').slice(0, 4)) || 0;
  const top = uniq(items.filter(it => (it.rating || 0) >= 7 && (yearOf(it) >= nowYear - 3 || (!yearOf(it) && (it.added || 0) > yearAgo)) && (it.stream_icon || it.cover))
    .sort((a, b) => (b.rating || 0) - (a.rating || 0) || (b.added || 0) - (a.added || 0)), 120);
  const names = items.map(it => (it.name || '').toLowerCase());
  console.log(`[vod] ${kind}: ${items.length} titles in ${catList.length} categories, ${((Date.now() - t0) / 1000).toFixed(1)}s`);
  return { items, names, byCat, overview: JSON.stringify({ at: Date.now(), cats: catList, recent, top, total: items.length }) };
}

export async function handleCatalog(req, res, url, send, allowed) {
  if (PROXY_KEY && url.searchParams.get('key') !== PROXY_KEY) return send(res, 403, 'Wrong or missing key');
  if (req.method !== 'POST') return send(res, 405, 'POST only');
  let q;
  try { q = JSON.parse(await readBody(req)); } catch { return send(res, 400, 'Bad body'); }
  const kind = q.kind === 'series' ? 'series' : 'movies';
  let base;
  try { base = new URL(q.server); } catch { return send(res, 400, 'Bad server address'); }
  if (!allowed(base) || !q.user) return send(res, 400, 'Server address not allowed');
  const key = createHash('sha256').update(`${base.origin}|${q.user}|${q.pass || ''}|${kind}`).digest('hex');
  let hit = cache.get(key);
  if (!hit || Date.now() - hit.at > TTL || q.force) {
    hit = { at: Date.now(), job: build(base.origin, q.user, q.pass || '', kind) };
    cache.set(key, hit);
    hit.job.catch(() => cache.delete(key));
    for (const [k, v] of cache) if (Date.now() - v.at > TTL) cache.delete(k);
  }
  let data;
  try { data = await hit.job; } catch (e) { return send(res, 502, `Movies & series: ${e.message}`); }
  let body;
  if (q.q) {
    const t = String(q.q).toLowerCase().slice(0, 80), out = [];
    for (let i = 0; i < data.names.length && out.length < 400; i++) if (data.names[i].includes(t)) out.push(data.items[i]);
    body = JSON.stringify({ items: out });
  } else if (q.group !== undefined && q.group !== null) {
    body = JSON.stringify({ items: data.byCat.get(String(q.group)) || [] });
  } else body = data.overview;
  const gz = /\bgzip\b/.test(req.headers['accept-encoding'] || '');
  send(res, 200, gz ? gzipSync(body) : body, { 'content-type': 'application/json', 'cache-control': 'no-store', ...(gz ? { 'content-encoding': 'gzip', vary: 'accept-encoding' } : {}) });
}
