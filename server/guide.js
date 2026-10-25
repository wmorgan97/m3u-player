// Server-side TV guide. Provider XMLTV files are 20MB+ gzipped, too heavy to download and parse
// on a phone while it is also streaming video. The server fetches and parses the guide instead,
// keeps only the channels the app asks for, and returns a small JSON (usually well under 1MB).
//
//   POST /guide?key=<PROXY_KEY>   body: { urls, ids, names }  ->  { programmes, names, at }
//
// Results are cached for 6 hours per (guide urls, channel set), so a reload is instant.

import { createHash } from 'node:crypto';
import { XmltvParser } from '../js/epg.js';
import { readBody } from './sync.js';

const PROXY_KEY = process.env.PROXY_KEY || '';
const TTL = 6 * 3600_000, DAY = 86400_000;
const cache = new Map();   // hash -> { at, job }

async function feedUrl(parser, url) {
  const res = await fetch(url, { headers: { 'user-agent': 'Mozilla/5.0' }, signal: AbortSignal.timeout(120_000) });
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

async function build(urls, ids, names) {
  const t0 = Date.now();
  const parser = new XmltvParser({ from: Date.now() - 3 * DAY, to: Date.now() + 2 * DAY, wanted: { ids: new Set(ids), names: new Set(names) } });
  let ok = 0, lastError;
  for (const u of urls) { try { await feedUrl(parser, u); ok++; } catch (e) { lastError = e; } }
  if (!ok) throw lastError;
  const data = parser.finish();
  // Only send names for channels we kept: the full display-name map is most of a big guide.
  const keep = new Set(Object.keys(data.programmes));
  data.names = Object.fromEntries(Object.entries(data.names).filter(([, id]) => keep.has(id)));
  data.at = Date.now();
  console.log(`[guide] ${keep.size} channels in ${((Date.now() - t0) / 1000).toFixed(1)}s`);
  return JSON.stringify(data);
}

export async function handleGuide(req, res, url, send, allowed) {
  if (PROXY_KEY && url.searchParams.get('key') !== PROXY_KEY) return send(res, 403, 'Wrong or missing key');
  if (req.method !== 'POST') return send(res, 405, 'POST only');
  let q;
  try { q = JSON.parse(await readBody(req)); } catch { return send(res, 400, 'Bad body'); }
  const urls = (q.urls || []).filter(u => { try { return allowed(new URL(u)); } catch { return false; } });
  if (!urls.length) return send(res, 400, 'No usable guide link');
  const ids = [...new Set((q.ids || []).map(String))].sort();
  const names = [...new Set((q.names || []).map(String))].sort();
  const key = createHash('sha1').update(JSON.stringify([urls, ids, names])).digest('hex');
  let hit = cache.get(key);
  if (!hit || Date.now() - hit.at > TTL) {
    hit = { at: Date.now(), job: build(urls, ids, names) };
    cache.set(key, hit);
    hit.job.catch(() => cache.delete(key));
    for (const [k, v] of cache) if (Date.now() - v.at > TTL) cache.delete(k);
  }
  try {
    send(res, 200, await hit.job, { 'content-type': 'application/json', 'cache-control': 'no-store' });
  } catch (e) {
    send(res, 502, `TV guide: ${e.message}`);
  }
}
