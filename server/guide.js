// Server-side TV guide. Provider XMLTV files are 20MB+ gzipped (hundreds of MB of XML), far too
// heavy for a phone that is also streaming video. The server downloads and indexes the whole guide
// (every few hours, kept in memory), and the app asks only for the channels it is showing:
//
//   POST /guide?key=<PROXY_KEY>   body: { urls, ids, names }  ->  { at, names, programmes }
//
// ids are lower-case tvg-ids, names are normalised channel names (see normName in js/epg.js).

import { promises as fs } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { XmltvParser, normName } from '../js/epg.js';
import { readBody } from './sync.js';

const PROXY_KEY = process.env.PROXY_KEY || '';
const HOUR = 3600_000;
const REFRESH = 6 * HOUR;
const DIR = fileURLToPath(new URL('../data/', import.meta.url));
const URLS_FILE = join(DIR, 'guide-urls.json');
const indexes = new Map();   // urls key -> { at, chans: Map(id -> JSON string), names, job }

let allowed = () => true;
// Lower-case, accents off, punctuation to spaces: "Arsenal v Spurs!" -> "arsenal v spurs".
const fold = t => String(t || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
async function feedUrl(parser, url) {
  let res, next = new URL(url);
  for (let hop = 0; ; hop++) {   // redirects re-checked against the private-address block, like the proxy
    res = await fetch(next, { headers: { 'user-agent': 'Mozilla/5.0' }, redirect: 'manual', signal: AbortSignal.timeout(180_000) });
    const loc = res.headers.get('location');
    if (!(res.status >= 300 && res.status < 400 && loc) || hop >= 5) break;
    next = new URL(loc, next);
    if (!/^https?:$/.test(next.protocol) || !allowed(next)) throw new Error('Guide link redirected to a blocked address');
  }
  if (!res.ok) throw new Error(`Guide server replied ${res.status}`);
  const reader = res.body.getReader();
  const first = await reader.read();
  const head = first.value || new Uint8Array();
  let body = new ReadableStream({
    start(c) { if (head.length) c.enqueue(head); if (first.done) c.close(); },
    async pull(c) { const { done, value } = await reader.read(); if (done) c.close(); else c.enqueue(value); },
  });
  if (head[0] === 0x1f && head[1] === 0x8b) body = body.pipeThrough(new DecompressionStream('gzip'));
  for await (const text of body.pipeThrough(new TextDecoderStream())) parser.feed(text);
}

async function build(urls) {
  const t0 = Date.now();
  const parser = new XmltvParser({ from: Date.now() - 26 * HOUR, to: Date.now() + 74 * HOUR });
  let ok = 0, lastError;
  for (const u of urls) { try { await feedUrl(parser, u); ok++; } catch (e) { lastError = e; } }
  if (!ok) throw lastError;
  const data = parser.finish();
  // Store each channel's programmes as ready-made JSON (short descriptions): cheap to hold and to send.
  const chans = new Map();
  for (const [id, list] of Object.entries(data.programmes)) chans.set(id, JSON.stringify(list.map(p => p[4] ? [p[0], p[1], p[2], p[3].slice(0, 600), p[4]] : [p[0], p[1], p[2], p[3].slice(0, 600)])));
  console.log(`[guide] indexed ${chans.size} channels in ${((Date.now() - t0) / 1000).toFixed(1)}s`);
  return { at: Date.now(), chans, names: data.names, display: data.display || {} };
}

const MAX_INDEXES = 3;
function indexFor(urls) {
  const key = urls.join('\n');
  let ix = indexes.get(key);
  if (!ix) {
    // Each index holds a whole guide in memory: keep at most a few (oldest dropped), so odd link combinations can't
    // pile up and run the server out of memory (it did once, 2026-10-28).
    while (indexes.size >= MAX_INDEXES) indexes.delete(indexes.keys().next().value);
    ix = { at: 0, chans: null, names: {}, job: null }; indexes.set(key, ix);
  }
  if (!ix.job && Date.now() - ix.at > REFRESH) {
    ix.job = build(urls)
      .then(r => Object.assign(ix, r))
      .catch(e => { console.error('[guide] build failed:', e.message); if (!ix.chans) indexes.delete(key); throw e; })
      .finally(() => { ix.job = null; });
    ix.job.catch(() => {});
  }
  return ix;
}

async function remember(urls) {
  try {
    const known = JSON.parse(await fs.readFile(URLS_FILE, 'utf8').catch(() => '[]'));
    if (known.some(k => k.join('\n') === urls.join('\n'))) return;
    await fs.mkdir(DIR, { recursive: true });
    await fs.writeFile(URLS_FILE, JSON.stringify([urls, ...known].slice(0, 5)));
  } catch {}
}

// Build the guides the app has used before as soon as the server starts, and keep them fresh,
// so the first request of the day is instant.
fs.readFile(URLS_FILE, 'utf8').then(t => JSON.parse(t).forEach(indexFor)).catch(() => {});
setInterval(() => { for (const key of indexes.keys()) indexFor(key.split('\n')); }, HOUR).unref();

export async function handleGuide(req, res, url, send, allow) {
  allowed = allow;
  if (PROXY_KEY && url.searchParams.get('key') !== PROXY_KEY) return send(res, 403, 'Wrong or missing key');
  if (req.method !== 'POST') return send(res, 405, 'POST only');
  let q;
  try { q = JSON.parse(await readBody(req)); } catch { return send(res, 400, 'Bad body'); }
  // Each link once, at most 4: the same pair sent twice is the same guide, not a new one to build.
  const urls = [...new Set((Array.isArray(q.urls) ? q.urls : []).map(String))].filter(u => { try { return allowed(new URL(u)); } catch { return false; } }).slice(0, 4);
  if (!urls.length) return send(res, 400, 'No usable guide link');
  remember(urls);
  const ix = indexFor(urls);
  if (!ix.chans) {
    try { await ix.job; } catch (e) { return send(res, 502, `TV guide: ${e.message}`); }
  }
  // "Fix guide match": search the guide's channels by name or id; only those with programmes.
  if (typeof q.search === 'string') {
    const t = normName(q.search), raw = q.search.trim().toLowerCase(), out = [];
    for (const [id, name] of Object.entries(ix.display || {})) {
      if (!ix.chans.has(id)) continue;
      if ((t && normName(name).includes(t)) || (raw && id.includes(raw))) out.push({ id, name });
      if (out.length >= 60) break;
    }
    out.sort((a, b) => a.name.length - b.name.length);
    return send(res, 200, JSON.stringify({ matches: out }), { 'content-type': 'application/json', 'cache-control': 'no-store' });
  }
  // "Find a team / show": every programme in the next 3 days whose title mentions the words, across ALL channels in the
  // guide (the apps only hold listings for channels they have shown). Whole words, any case/accents; "Arsenal" also
  // matches "Arsenal v Spurs" but not "Arsenalfan". Same title + start on several channels (HD/SD copies) comes back once
  // per channel, the app picks which copy to use.
  if (typeof q.find === 'string') {
    const words = fold(q.find).split(' ').filter(w => w.length > 1);
    if (!words.length) return send(res, 400, 'Search for at least two letters');
    const now = Date.now(), until = now + 72 * HOUR, out = [];
    const quick = words[0];
    for (const [id, json] of ix.chans) {
      if (!fold(json).includes(quick)) continue;   // cheap pre-check before parsing this channel's listings
      for (const p of JSON.parse(json)) {
        if (p[1] <= now || p[0] >= until) continue;
        const t = ` ${fold(p[2])} `;
        if (words.every(w => t.includes(` ${w} `))) out.push({ id, name: ix.display?.[id] || id, start: p[0], end: p[1], title: p[2], desc: (p[3] || '').slice(0, 300) });
      }
      if (out.length > 400) break;
    }
    out.sort((a, b) => a.start - b.start);
    return send(res, 200, JSON.stringify({ at: ix.at, matches: out.slice(0, 200) }), { 'content-type': 'application/json', 'cache-control': 'no-store' });
  }
  // Sports hub: several searches in one request, only on the channels the app has (ids / normalised names, as for a
  // listings request), so a popular word ("Premier League") isn't cut off by the same programme on hundreds of foreign
  // channels. -> { at, found: { [query]: [{ id, name, start, end, title, desc }] } }, each soonest first, at most 300.
  if (Array.isArray(q.finds)) {
    const qs = q.finds.filter(f => typeof f === 'string').slice(0, 40).map(f => ({ f, words: fold(f).split(' ').filter(w => w.length > 1) })).filter(x => x.words.length);
    const only = new Set();
    for (const id of q.ids || []) if (ix.chans.has(id)) only.add(id);
    for (const n of q.names || []) { const id = ix.names[n]; if (id && ix.chans.has(id)) only.add(id); }
    const now = Date.now(), until = now + 72 * HOUR, found = Object.fromEntries(qs.map(x => [x.f, []]));
    for (const id of only.size ? only : ix.chans.keys()) {
      const json = ix.chans.get(id), folded = fold(json);
      const hit = qs.filter(x => folded.includes(x.words[0]));
      if (!hit.length) continue;
      for (const p of JSON.parse(json)) {
        if (p[1] <= now || p[0] >= until) continue;
        const t = ` ${fold(p[2])} `;
        for (const x of hit) if (x.words.every(w => t.includes(` ${w} `))) found[x.f].push({ id, name: ix.display?.[id] || id, start: p[0], end: p[1], title: p[2], desc: (p[3] || '').slice(0, 300) });
      }
    }
    for (const k of Object.keys(found)) found[k] = found[k].sort((a, b) => a.start - b.start).slice(0, 300);
    return send(res, 200, JSON.stringify({ at: ix.at, found }), { 'content-type': 'application/json', 'cache-control': 'no-store' });
  }
  const names = {}, ids = new Set();
  for (const id of q.ids || []) if (ix.chans.has(id)) ids.add(id);
  for (const n of q.names || []) { const id = ix.names[n]; if (id && ix.chans.has(id)) { names[n] = id; ids.add(id); } }
  let body = `{"at":${ix.at},"names":${JSON.stringify(names)},"programmes":{`;
  let first = true;
  for (const id of ids) { body += `${first ? '' : ','}${JSON.stringify(id)}:${ix.chans.get(id)}`; first = false; }
  body += '}}';
  console.log(`[guide] request: ${(q.ids || []).length} ids + ${(q.names || []).length} names -> ${ids.size} channels`);
  const gz = /\bgzip\b/.test(req.headers['accept-encoding'] || '');
  send(res, 200, gz ? gzipSync(body) : body, { 'content-type': 'application/json', 'cache-control': 'no-store', ...(gz ? { 'content-encoding': 'gzip', vary: 'accept-encoding' } : {}) });
}
