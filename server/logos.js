// Channel logos. Providers' own logos (tvg-logo / stream_icon) are often missing, tiny or all different styles;
// this finds a clean one for each channel and serves it small, from the server's image cache.
//
//   GET /logo?key=<PROXY_KEY>&name=<channel name>&id=<tvg-id>&u=<provider logo>&w=<px>  -> image/png
//       (the matched logo, else the provider's own one, shrunk; 404 when there is neither)
//   GET /logo?...&json=1                                                                -> { src, via }
//
// Sources, in order of preference (both free; personal use):
//   1. tv-logo/tv-logos (GitHub): 512px PNGs drawn for dark backgrounds. UK folder first, then a few others.
//   2. iptv-org/api logos.json, matched by tvg-id (iptv-org ids like "BBCOne.uk") or by channel name / alt names.
//   3. The provider's own logo.
// The indexes are fetched at most once a day (a handful of GitHub API calls plus two static JSON files) and kept
// in data/logos/; logo images are fetched only when a device first asks for one, then served from disk.

import { promises as fs, createReadStream } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ensure } from './img.js';

const PROXY_KEY = process.env.PROXY_KEY || '';
const DIR = fileURLToPath(new URL('../data/logos/', import.meta.url));
const INDEX_FILE = join(DIR, 'index.json');
const DAY = 86400_000, RETRY = 6 * 3600_000;
const REPO = 'tv-logo/tv-logos';
// tv-logos country folders searched, in order (UK first), with the suffix their file names end in.
export const COUNTRIES = [['united-kingdom', 'uk'], ['ireland', 'ie'], ['international', 'int'], ['united-states', 'us']];
const RAW = (dir, path) => `https://raw.githubusercontent.com/${REPO}/main/countries/${dir}/${path.split('/').map(encodeURIComponent).join('/')}`;

// ---------- names ----------

const NUM = { one: '1', two: '2', three: '3', four: '4', five: '5', six: '6', seven: '7', eight: '8', nine: '9', ten: '10' };
// Picture-quality and stream tags that never belong to a channel's identity.
const QUALITY = new Set(['hd', 'fhd', 'uhd', 'sd', 'hq', 'lq', '4k', '8k', 'hevc', 'h265', 'h264', 'x265', '1080', '1080p', '1080i', '720', '720p',
  '576p', '50fps', '60fps', 'fps', 'hdr', 'raw', 'vip', 'backup', 'bk', 'multi', 'audio', 'uhd4k', 'ultra', 'full']);
// Country tags providers put in front of names ("UK: ", "UK| ", "[UK] ") and the country they mean.
const PREFIX = { uk: 'uk', gb: 'uk', en: 'uk', eng: 'uk', ie: 'ie', irl: 'ie', ir: 'ie', us: 'us', usa: 'us', int: 'int' };
const TRAIL = new Set(['uk', 'gb', 'ie', 'us', 'int']);
// Regional variants: "BBC One Yorkshire" falls back to BBC One when there is no Yorkshire logo.
const REGION = new Set(['london', 'scotland', 'wales', 'ni', 'cymru', 'yorkshire', 'midlands', 'east', 'west', 'north', 'south', 'northwest',
  'northeast', 'southwest', 'southeast', 'anglia', 'granada', 'meridian', 'central', 'border', 'tyne', 'tees', 'channel', 'islands', 'england',
  'ireland', 'northern', 'oxford', 'cambridge', 'yorks', 'lincs', 'westcountry', 'stv', 'utv', 'eastmids', 'westmids']);

/** A channel name as words: lower case, no country tag, no quality tags or brackets; "+1" becomes "plus". */
export function words(name) {
  let s = String(name || '').normalize('NFKC').toLowerCase();   // NFKC turns "ᴴᴰ" into "hd", full-width letters into plain
  let country = '';
  // Leading country tags: "UK: ", "UK | ", "|UK| ", "[UK] ", "UK - ", "(UK)", "UK ● ".
  s = s.replace(/^[^a-z0-9+]*([a-z]{2,3})\s*[^a-z0-9\s+&']+\s*/, (m, c) => { if (PREFIX[c] || c.length === 2) { country = PREFIX[c] || c; return ' '; } return m; });
  s = s.replace(/\[[^\]]*\]|\([^)]*\)|\{[^}]*\}/g, ' ');           // "(Backup)", "[1080p]"
  s = s.replace(/\+\s*1\b|\bplus\s*1\b/g, ' plus ');               // "+1" / "Plus 1" (tv-logos names these "-plus-")
  s = s.replace(/\+/g, ' plus ').replace(/&/g, ' and ').replace(/'/g, '');
  const toks = s.split(/[^a-z0-9]+/).filter(t => t && !QUALITY.has(t));
  // A trailing country code ("Sky News UK", "RTE One IE") is a tag, not part of the name (but "5USA" is a channel).
  while (toks.length > 1 && TRAIL.has(toks[toks.length - 1])) country ||= PREFIX[toks.pop()];
  const out = [];
  for (const t of toks) for (const w of t.replace(/([a-z])(\d)/g, '$1 $2').replace(/(\d)([a-z])/g, '$1 $2').split(' ')) out.push(NUM[w] || w);   // "ITV1" -> itv 1
  return { words: out, country };
}
export const keyOf = ws => ws.join('');

/** Keys to try for a name, most specific first: the whole name, then without trailing region words. */
export function keysFor(name) {
  const { words: ws, country } = words(name);
  const keys = [];
  if (ws.length) keys.push(keyOf(ws));
  const cut = ws.slice();
  while (cut.length > 1 && REGION.has(cut[cut.length - 1]) && cut[cut.length - 1] !== 'plus') { cut.pop(); keys.push(keyOf(cut)); }
  if (cut.length > 1 && ['tv', 'channel', 'english', 'network'].includes(cut[cut.length - 1])) keys.push(keyOf(cut.slice(0, -1)));   // "LFC TV", "Al Jazeera English"
  return { keys: [...new Set(keys)], country, plus: ws[ws.length - 1] === 'plus' && ws.length > 1 ? keyOf(ws.slice(0, -1)) : '' };
}

// tv-logos file names: "sky-sports-main-event-uk.png", "hd/bbc-one-hd-uk.png", "comedy-central-plus-icon-uk.png".
const VARIANT = new Set(['icon', 'hz', 'alt', 'white', 'dark', 'light', 'mono', 'old', 'new', 'square', 'vertical', 'v']);
function tvLogoEntry(path, suffix) {
  if (!/\.png$/i.test(path)) return null;
  const parts = path.split('/'), file = parts.pop().replace(/\.png$/i, '');
  let ws = file.split('-').filter(Boolean);
  if (ws[ws.length - 1] === suffix) ws.pop();
  let score = parts.length * 4;                                      // sub-folders (hd/, old/) after the plain logo
  const variants = ws.filter(w => VARIANT.has(w) || /^\d$/.test(w) && ws[ws.indexOf(w) - 1] === 'icon').length;
  score += variants * 2;
  ws = ws.filter((w, i) => !VARIANT.has(w) && !(/^\d$/.test(w) && ws[i - 1] === 'icon'));
  if (ws.some(w => QUALITY.has(w))) score += 1;
  const w = words(ws.join(' ')).words;
  if (!w.length) return null;
  // Other ways providers name the same channel, tried after exact names: "BBC CBBC" = CBBC, "U & Dave" = Dave,
  // "W Network" = W, "4 More" = More4.
  const alias = [];
  const plus = w[w.length - 1] === 'plus' ? ['plus'] : [], core = plus.length ? w.slice(0, -1) : w;
  if (core.length > 1 && ['bbc', 'the'].includes(core[0])) alias.push([...core.slice(1), ...plus]);
  if (core.length > 2 && core[0] === 'u' && core[1] === 'and') alias.push([...core.slice(2), ...plus]);
  if (core.length > 1 && ['network', 'channel', 'tv'].includes(core[core.length - 1])) alias.push([...core.slice(0, -1), ...plus]);
  if (core.length === 2 && /^\d+$/.test(core[0]) && !/^\d+$/.test(core[1])) alias.push([core[1], core[0], ...plus]);
  return { key: keyOf(w), alias: alias.map(keyOf), path, score };
}

// ---------- the index ----------

/** Builds the lookup tables from the raw downloads (also used by the tests and the report with fixture data). */
export function buildIndex({ tvlogos = {}, channels = [], logos = [] }) {
  const tv = new Map();                                              // country -> Map(key -> { url, score })
  for (const [dir, suffix] of COUNTRIES) {
    const m = new Map();
    for (const p of tvlogos[dir] || []) {
      const e = tvLogoEntry(p, suffix);
      if (!e) continue;
      for (const [k, sc] of [[e.key, e.score], ...e.alias.map(a => [a, e.score + 100])]) if (!m.has(k) || m.get(k).score > sc) m.set(k, { url: RAW(dir, p), score: sc });
    }
    tv.set(suffix, m);
  }
  // iptv-org: the best picture per channel id (no feed, not SVG, widest), and channel ids by name / alt name.
  const best = new Map();
  for (const l of logos) {
    if (!l?.channel || !l.url || /svg/i.test(l.format || '') || /\.svg(\?|$)/i.test(l.url) || l.in_use === false) continue;
    const id = l.channel.toLowerCase();
    const score = (l.feed ? 1e6 : 0) - Math.min(l.width || 0, 1000) - (/png/i.test(l.format || '') ? 50 : 0);
    if (!best.has(id) || best.get(id).score > score) best.set(id, { url: l.url, score });
  }
  const byId = new Map(), byKey = new Map();
  for (const c of channels) {
    if (!c?.id) continue;
    const id = c.id.toLowerCase();
    const ch = { id, name: c.name || '', alt: c.alt_names || [], country: String(c.country || '').toLowerCase(), logo: best.get(id)?.url || '' };
    byId.set(id, ch);
    for (const n of [ch.name, ...ch.alt]) {
      const k = keyOf(words(n).words);
      if (!k) continue;
      const list = byKey.get(k) || [];
      list.push(ch); byKey.set(k, list);
    }
  }
  for (const [id, b] of best) if (!byId.has(id)) byId.set(id, { id, name: '', alt: [], country: '', logo: b.url });
  return { tv, byId, byKey };
}

const ORDER = { uk: ['uk', 'ie', 'int', 'us'], ie: ['ie', 'uk', 'int', 'us'], us: ['us', 'int', 'uk', 'ie'] };
const ISO = { uk: 'uk', ie: 'ie', us: 'us', int: '' };

/** The best logo for a channel: { src, via } (via: 'tv-logos', 'iptv-org', 'provider'), or null. */
export function resolveLogo(index, { name = '', id = '', provider = '' } = {}) {
  const { keys, country, plus } = keysFor(name);
  const order = ORDER[country] || ORDER.uk;
  const tvFind = ks => { for (const c of order) for (const k of ks) { const hit = index.tv.get(c)?.get(k); if (hit) return hit.url; } return ''; };
  const tid = String(id || '').toLowerCase().replace(/@.*$/, '').trim();
  const byId = tid ? index.byId.get(tid) : null;
  const idKeys = byId ? [byId.name, ...byId.alt].map(n => keyOf(words(n).words)).filter(Boolean) : [];
  const full = keys.slice(0, 1), regional = keys.slice(1);

  let src = tvFind(full) || tvFind(idKeys);
  if (src) return { src, via: 'tv-logos' };
  if (byId?.logo) return { src: byId.logo, via: 'iptv-org' };
  const iptvByName = ks => {
    for (const k of ks) {
      const list = (index.byKey.get(k) || []).filter(c => c.logo);
      if (!list.length) continue;
      const want = ISO[country] ?? 'uk';
      return (list.find(c => c.country === (want || 'uk')) || list.find(c => c.country === 'uk') || list[0]).logo;
    }
    return '';
  };
  src = iptvByName(full);
  if (src) return { src, via: 'iptv-org' };
  src = tvFind(regional);
  if (src) return { src, via: 'tv-logos' };
  src = iptvByName(regional);
  if (src) return { src, via: 'iptv-org' };
  if (/^https?:\/\//i.test(provider)) return { src: provider, via: 'provider' };
  // A "+1" channel with no logo of its own (and none from the provider) shows the main channel's: never instead of one.
  if (plus) { src = tvFind([plus]); if (src) return { src, via: 'tv-logos' }; src = iptvByName([plus]); if (src) return { src, via: 'iptv-org' }; }
  return null;
}

// ---------- downloads (at most daily) ----------

async function getJson(url) {
  const res = await fetch(url, { headers: { 'user-agent': 'm3u-player-logos (personal use)', accept: 'application/json' }, signal: AbortSignal.timeout(60_000) });
  if (!res.ok) throw new Error(`${url} replied ${res.status}`);
  return res.json();
}

// tv-logos file lists: one tree call for the root, one for countries/, one per country folder (5-6 API calls a day).
async function fetchTvLogos() {
  const api = sha => `https://api.github.com/repos/${REPO}/git/trees/${sha}`;
  const root = await getJson(api('main'));
  const countries = await getJson(api(root.tree.find(t => t.path === 'countries').sha));
  const out = {};
  for (const [dir] of COUNTRIES) {
    const t = countries.tree.find(x => x.path === dir);
    if (!t) continue;
    const tree = await getJson(`${api(t.sha)}?recursive=1`);
    out[dir] = tree.tree.filter(x => x.type === 'blob' && /\.png$/i.test(x.path)).map(x => x.path);
  }
  return out;
}

export async function downloadIndex() {
  const [tvlogos, channels, logos] = await Promise.all([
    fetchTvLogos(),
    getJson('https://iptv-org.github.io/api/channels.json'),
    getJson('https://iptv-org.github.io/api/logos.json'),
  ]);
  // Only what the matcher reads is kept (the full files are ~14MB).
  return {
    at: Date.now(), tvlogos,
    channels: channels.map(c => ({ id: c.id, name: c.name, alt_names: c.alt_names?.length ? c.alt_names : undefined, country: c.country })),
    logos: logos.filter(l => !/svg/i.test(l.format || '') && l.in_use !== false).map(l => ({ channel: l.channel, feed: l.feed || undefined, width: l.width, format: l.format, url: l.url })),
  };
}

let index = null, indexAt = 0, lastTry = 0, loading = null;
const resolved = new Map();                                          // "id|name|provider" -> result (cleared on a new index)

async function refresh() {
  lastTry = Date.now();
  try {
    const raw = await downloadIndex();
    await fs.mkdir(DIR, { recursive: true });
    const tmp = `${INDEX_FILE}.${process.pid}.tmp`;
    await fs.writeFile(tmp, JSON.stringify(raw));
    await fs.rename(tmp, INDEX_FILE);
    index = buildIndex(raw); indexAt = raw.at; resolved.clear();
    console.log(`[logos] index refreshed: ${Object.values(raw.tvlogos).reduce((n, l) => n + l.length, 0)} tv-logos files, ${raw.logos.length} iptv-org logos`);
  } catch (e) { console.error('[logos] index refresh failed:', e.message); }
}

/** Uses this raw index instead of downloading one (tests). */
export function useIndex(raw) { index = buildIndex(raw); indexAt = Date.now(); resolved.clear(); }

/** The index, read from disk on first use and refreshed in the background when it is a day old. */
export async function getIndex() {
  if (!index && !loading) {
    loading = (async () => {
      try { const raw = JSON.parse(await fs.readFile(INDEX_FILE, 'utf8')); index = buildIndex(raw); indexAt = raw.at || 0; } catch {}
      if (!index) await refresh();
    })().finally(() => { loading = null; });
  }
  if (loading) await loading;
  if (index && Date.now() - indexAt > DAY && Date.now() - lastTry > RETRY && !loading) {
    lastTry = Date.now();
    loading = refresh().finally(() => { loading = null; });          // keeps answering from the old index meanwhile
  }
  return index;
}

export async function handleLogo(req, res, url, send, allowed) {
  if (PROXY_KEY && url.searchParams.get('key') !== PROXY_KEY) return send(res, 403, 'Wrong or missing key');
  const name = (url.searchParams.get('name') || '').slice(0, 200), id = (url.searchParams.get('id') || '').slice(0, 120);
  let provider = url.searchParams.get('u') || '';
  try { const p = new URL(provider); if (!/^https?:$/.test(p.protocol) || !allowed(p)) provider = ''; } catch { provider = ''; }
  const ck = `${id}|${name}|${provider}`;
  let r = resolved.get(ck);
  if (r === undefined) {
    const idx = await getIndex().catch(() => null);
    r = idx ? resolveLogo(idx, { name, id, provider }) : (provider ? { src: provider, via: 'provider' } : null);
    if (idx) { if (resolved.size > 50_000) resolved.clear(); resolved.set(ck, r); }
  }
  if (url.searchParams.has('json')) return send(res, 200, JSON.stringify(r || { src: '', via: '' }), { 'content-type': 'application/json', 'cache-control': 'no-store' });
  const w = Math.min(Math.max(Number(url.searchParams.get('w')) || 200, 120), 500);
  let file = r ? await ensure(r.src, w, true) : null;
  if (!file && r && r.via !== 'provider' && provider) file = await ensure(provider, w, true);   // the matched picture is gone: the provider's own
  if (!file) {
    if (provider) { res.writeHead(302, { location: provider, 'cache-control': 'public, max-age=3600', 'access-control-allow-origin': '*' }); return res.end(); }
    return send(res, 404, 'No logo', { 'cache-control': 'public, max-age=86400' });
  }
  const st = await fs.stat(file);
  res.writeHead(200, { 'content-type': 'image/png', 'content-length': st.size, 'cache-control': 'public, max-age=604800', 'access-control-allow-origin': '*' });
  if (req.method === 'HEAD') return res.end();
  createReadStream(file).on('error', () => res.destroy()).pipe(res);
}
