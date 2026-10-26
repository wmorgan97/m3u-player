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
import { gzipSync, gunzipSync } from 'node:zlib';
import { promises as fs } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { readBody } from './sync.js';
import { trending, collections, onTmdbChange } from './tmdb.js';
import { warmThumbs } from './img.js';

const PROXY_KEY = process.env.PROXY_KEY || '';
const TTL = 12 * 3600_000;
const cache = new Map();   // key -> { at, job: Promise<{ raw, gz }> }
onTmdbChange(() => cache.clear());   // a new TMDB key: rebuild overviews with trending lists

const num = v => (v === null || v === undefined || v === '' ? undefined : Number(v) || undefined);
// A 9.5-10 rating on a provider listing is almost always one or two votes: treat it as no rating.
const rate = v => { const r = num(v); return r && r > 0 && r < 9.5 ? Math.round(r * 10) / 10 : undefined; };
const str = (v, max = 400) => (v ? String(v).slice(0, max) : undefined);
const slimMovie = s => ({ stream_id: s.stream_id, name: str(s.name, 200), stream_icon: str(s.stream_icon, 300), category_id: s.category_id ?? s.category_ids?.[0],
  rating: rate(s.rating), year: str(s.year, 10), added: num(s.added), container_extension: str(s.container_extension, 8) });
const slimSeries = s => ({ series_id: s.series_id, name: str(s.name, 200), cover: str(s.cover, 300), category_id: s.category_id ?? s.category_ids?.[0],
  rating: rate(s.rating), releaseDate: str(s.releaseDate || s.release_date, 12), plot: str(s.plot, 240), genre: str(s.genre, 60), added: num(s.last_modified) });
// "4K-TOP - Dune (2021)" and "TOP - Dune (2021)" are the same title for a shelf: keep the first.
// Every leading tag goes ("4K-D+ - ", "EN | ", "TOP - "), and country tags like "(US)".
const titleKey = it => String(it.name || '').replace(/^(?:[A-Za-z0-9+&]{1,5}(?:-[A-Za-z0-9+&]{1,5})*\s*[-|:]\s+)+/, '').replace(/^\d{1,3}\.\s+(?=\D)/, '')
  .replace(/\((?:[A-Z]{2}(?:\/[A-Z]{2})?)\)/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '');
const yearIn = it => Number(String(it.year || '').match(/(19|20)\d\d/)?.[0]) || Number((String(it.name || '').match(/(?:^|\D)((?:19|20)\d\d)(?!.*(?:19|20)\d\d)/) || [])[1]) || 0;
const bareKey = it => titleKey(it).replace(/(19|20)\d\d$/, '');
const uniq = (list, n) => { const seen = new Set(), out = []; for (const it of list) { const k = titleKey(it); if (k && !seen.has(k)) { seen.add(k); out.push(it); if (out.length >= n) break; } } return out; };

// A provider blip (dropped connection, a 5xx while it's busy) is retried twice before it counts.
async function api(base, user, pass, action) {
  const u = new URL('/player_api.php', base);
  u.search = new URLSearchParams({ username: user, password: pass, action });
  for (let attempt = 1; ; attempt++) {
    try {
      const res = await fetch(u, { headers: { 'user-agent': 'Mozilla/5.0' }, signal: AbortSignal.timeout(180_000) });
      if (!res.ok) throw Object.assign(new Error(`The provider replied ${res.status}`), { retry: res.status >= 500 || res.status === 429 });
      const data = await res.json();
      return Array.isArray(data) ? data : [];
    } catch (e) {
      if (attempt >= 3 || e.retry === false) throw e;
      console.log(`[vod] ${action}: ${e.message}, retrying`);
      await new Promise(r => setTimeout(r, attempt * 3000));
    }
  }
}

// The last good catalogue of each login is kept on disk, so a provider that's down (or a restart while it's busy)
// still gives Movies & Series, just as they were at the last successful refresh.
const SAVED = fileURLToPath(new URL('../data/catalog/', import.meta.url));
const savedFile = (server, user, kind) => `${SAVED}${createHash('sha256').update(`${server}|${user}|${kind}`).digest('hex').slice(0, 24)}.json.gz`;

async function build(server, user, pass, kind) {
  const t0 = Date.now();
  const file = savedFile(server, user, kind);
  let cats, items;
  try {
    const [c, list] = kind === 'movies'
      ? await Promise.all([api(server, user, pass, 'get_vod_categories'), api(server, user, pass, 'get_vod_streams')])
      : await Promise.all([api(server, user, pass, 'get_series_categories'), api(server, user, pass, 'get_series')]);
    cats = c;
    items = list.map(kind === 'movies' ? slimMovie : slimSeries);
    if (items.length) fs.mkdir(SAVED, { recursive: true }).then(() => fs.writeFile(file, gzipSync(JSON.stringify({ cats, items })))).catch(e => console.log(`[vod] saving ${kind}: ${e.message}`));
  } catch (e) {
    let saved = null;
    try { saved = JSON.parse(gunzipSync(await fs.readFile(file))); } catch {}
    if (!saved?.items?.length) throw e;
    console.log(`[vod] ${kind}: provider unavailable (${e.message}), using the saved catalogue`);
    ({ cats, items } = saved);
  }
  const byCat = new Map();
  for (const it of items) { const k = String(it.category_id ?? ''); if (!byCat.has(k)) byCat.set(k, []); byCat.get(k).push(it); }
  const catList = cats.map(c => ({ category_id: c.category_id, category_name: str(c.category_name, 120), count: byCat.get(String(c.category_id))?.length || 0 })).filter(c => c.count);
  const byAdded = items.some(it => it.added) ? [...items].sort((a, b) => (b.added || 0) - (a.added || 0)) : [...items].reverse();
  const recent = uniq(byAdded, 300);
  // Popular now. Listings carry no vote counts, so a rating alone is a poor signal. Instead:
  //  - the provider's own "Trending / Popular / Top 10 / Box office" groups (strongest),
  //  - how many versions it carries (4K, HD, per-platform copies: only big titles get several),
  //  - a sensible rating (5-9.4), and how recent it is.
  const nowYear = new Date().getFullYear(), now = Date.now() / 1000;
  const yearOf = it => Number(String(it.year || it.releaseDate || '').slice(0, 4)) || Number((String(it.name || '').match(/\((19|20)\d\d\)/) || [''])[0].slice(1, 5)) || 0;
  const CURATED = /trend|popular|top ?(10|20|50|100)\b|box ?office|most ?(watched|viewed|popular)|hot ?now|blockbuster/i;
  const curatedCats = new Set(cats.filter(c => CURATED.test(c.category_name || '')).map(c => String(c.category_id)));
  const copies = new Map(), curated = new Set();
  for (const it of items) { const k = titleKey(it); copies.set(k, (copies.get(k) || 0) + 1); if (curatedCats.has(String(it.category_id))) curated.add(k); }
  const art = it => it.stream_icon || it.cover;
  const score = it => {
    const k = titleKey(it), y = yearOf(it), r = it.rating;
    return (curated.has(k) ? 4 : 0) + Math.min((copies.get(k) || 1) - 1, 4) * 0.9
      + (r ? (r < 5 ? -2 : (r - 5) * 0.6) : 0)
      + (y >= nowYear - 1 ? 1.6 : y >= nowYear - 3 ? 0.8 : y && y < nowYear - 8 ? -1 : 0)
      + ((it.added || 0) > now - 45 * 86400 ? 0.5 : 0);
  };
  const top = uniq(items.filter(it => art(it) && (curated.has(titleKey(it)) || yearOf(it) >= nowYear - 4 || (!yearOf(it) && (it.added || 0) > now - 365 * 86400)))
    .map(it => [score(it), it]).sort((a, b) => b[0] - a[0]).map(x => x[1]), 120);
  // New releases: this year's and last year's titles, newest added first (not old films the provider just added).
  const fresh = uniq(byAdded.filter(it => art(it) && yearOf(it) >= nowYear - 1), 120);
  // Trending this week (TMDB), matched to titles this provider actually has: title + year, else title alone
  // when the years agree or the listing has none. Each match carries TMDB's backdrop, plot and rated votes.
  let trend = [];
  const tmdbGroups = new Map(), tmdbCats = [];
  try {
    const tkind = kind === 'movies' ? 'movie' : 'tv';
    const [tm, cols] = await Promise.all([trending(tkind), collections(tkind).catch(() => [])]);
    if (tm.length || cols.length) {
      const bare = it => titleKey(it).replace(/(19|20)\d\d$/, '');
      // The same title often sits in several language groups: prefer the English copy (the app shows
      // English groups by default), so a match isn't hidden by the language filter.
      const catName = new Map(cats.map(c => [String(c.category_id), String(c.category_name || '')]));
      const FOREIGN = /^(?:4K[- ]?)?(?:AR|FR|DE|IT|ES|TR|PT|BR|NL|PL|RU|IN|HI|KO|KR|JP|CN|GR|SE|SW|NO|DK|FI|RO|HU|CZ|AL|EX|LAT|LATAM|IR|AF|PK|BD|SO|KU|KUR|VN|TH|PH|ID|MY|IL|UA|BG|RS|HR|BA|MK|SI|SK|LT|LV|EE|AM|AZ|GE|ARM|PERSIAN|FARSI|ARABIC|FRENCH|GERMAN|SPANISH|ITALIAN|TURKISH|HINDI|PUNJABI|TAMIL|TELUGU|URDU|MALAYALAM|BANGLA|POLISH|DUTCH|RUSSIAN|PORTUGUESE|GREEK|ALBANIAN|SOMALI|KURDISH|AFRICA|AFR|ASIA|KOREAN|JAPANESE|CHINESE|THAI|VIET)\b|[^\x00-\x7F\u00C0-\u024F\u1D00-\u1DBF\u2070-\u209F]/i;
      const foreign = it => FOREIGN.test(catName.get(String(it.category_id)) || '');
      const byKey = new Map();
      for (const it of items) {
        if (!art(it)) continue;
        for (const k of [titleKey(it), bare(it)]) if (k && (!byKey.has(k) || (foreign(byKey.get(k)) && !foreign(it)))) byKey.set(k, it);
      }
      // A TMDB list -> the provider's copies of those titles, in TMDB's order, with TMDB's extras.
      const match = list => {
        const seen = new Set(), out = [];
        for (const t of list) {
          const n = t.title.toLowerCase().replace(/&/g, 'and').replace(/[^a-z0-9]+/g, '');
          let it = byKey.get(n + t.year) || byKey.get(n);
          if (it && yearOf(it) && t.year && Math.abs(yearOf(it) - t.year) > 1) it = null;
          if (!it || seen.has(it)) continue;
          seen.add(it);
          out.push({ ...it, ...(t.vote ? { rating: t.vote } : {}), tm: { tid: t.tid, backdrop: t.backdrop, overview: t.overview, votes: t.votes, genres: t.genres, year: t.year } });
        }
        return out;
      };
      trend = match(tm);
      for (const c of cols) {
        const got = match(c.results);
        if (got.length >= 6) { tmdbGroups.set(c.id, got); tmdbCats.push({ id: c.id, name: c.name, count: got.length }); }
      }
    }
  } catch (e) { console.log(`[tmdb] lists: ${e.message}`); }
  // Every copy of a title (4K, HD, per-platform…), for the details page's "Versions" choice.
  const byTitle = new Map();
  // Keyed by title without the year (providers write "Dune (2021)", "Dune 2021" or just "Dune"); years are checked at lookup.
  for (const it of items) { const k = bareKey(it); if (!k) continue; const l = byTitle.get(k); if (l) l.push(it); else byTitle.set(k, [it]); }
  const catNames = new Map(cats.map(c => [String(c.category_id), str(c.category_name, 120)]));
  // Pre-shrink the posters of the rows people see first (Top 10, Popular, New releases, genre rows).
  const art2 = it => it.stream_icon || it.cover;
  warmThumbs([...trend.slice(0, 20), ...top.slice(0, 40), ...fresh.slice(0, 40), ...recent.slice(0, 20), ...[...tmdbGroups.values()].flatMap(g => g.slice(0, 24))].map(art2));
  const names = items.map(it => (it.name || '').toLowerCase());
  console.log(`[vod] ${kind}: ${items.length} titles in ${catList.length} categories, ${trend.length} trending matched, ${tmdbCats.length} TMDB groups (${tmdbCats.map(c => `${c.name} ${c.count}`).join(', ')})`);
  console.log(`[vod] ${kind}: built, ${((Date.now() - t0) / 1000).toFixed(1)}s`);
  return { items, names, byCat, tmdbGroups, byTitle, catNames, overview: JSON.stringify({ at: Date.now(), v: 4, cats: catList, recent, top, fresh, trending: trend, tmdbCats, total: items.length }) };
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
  } else if (q.versions) {
    const want = { name: String(q.versions).slice(0, 200), year: q.year };
    const y = yearIn(want);
    const list = (data.byTitle?.get(bareKey(want)) || []).filter(it => { const iy = yearIn(it); return !y || !iy || Math.abs(iy - y) <= 1; }).slice(0, 60).map(it => ({ ...it, category_name: data.catNames?.get(String(it.category_id)) || '' }));
    body = JSON.stringify({ items: list });
  } else if (q.tmdb) {
    body = JSON.stringify({ items: data.tmdbGroups?.get(String(q.tmdb)) || [] });
  } else if (q.group !== undefined && q.group !== null) {
    body = JSON.stringify({ items: data.byCat.get(String(q.group)) || [] });
  } else body = data.overview;
  const gz = /\bgzip\b/.test(req.headers['accept-encoding'] || '');
  send(res, 200, gz ? gzipSync(body) : body, { 'content-type': 'application/json', 'cache-control': 'no-store', ...(gz ? { 'content-encoding': 'gzip', vary: 'accept-encoding' } : {}) });
}
