// Cross-device state: favourites, recently watched and watch progress, kept in one JSON
// file so every device that has the server key sees the same lists. The server merges:
//   favourites  whole list, newest edit wins (favAt)
//   recents     union by url, newest play first; an explicit edit (recReset) replaces the list
//   progress    per url, newest wins
//
//   POST /sync?key=<PROXY_KEY>   body: the device's state -> the merged state

import { promises as fs } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const PROXY_KEY = process.env.PROXY_KEY || '';
const DIR = fileURLToPath(new URL('../data/', import.meta.url));
const FILE = join(DIR, 'sync.json');
const MAX_RECENTS = 50, MAX_PROGRESS = 500, MAX_BODY = 4 << 20;

let stored = null;
let writing = Promise.resolve();

async function load() {
  if (!stored) {
    try { stored = JSON.parse(await fs.readFile(FILE, 'utf8')); } catch { stored = {}; }
  }
  return stored;
}

export function readBody(req, limit = MAX_BODY) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', c => { size += c.length; if (size > limit) { reject(new Error('Too large')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

export function merge(a, b) {
  const out = { ...a };
  if (Array.isArray(b.favorites) && (b.favAt || 0) >= (a.favAt || 0)) { out.favorites = b.favorites; out.favAt = b.favAt || 0; }
  if (Array.isArray(b.recents)) {
    const replace = (b.recReset || 0) > (a.recReset || 0);
    const base = replace ? [] : a.recents || [];
    if (replace) out.recReset = b.recReset;
    const byUrl = new Map();
    for (const r of [...base, ...b.recents]) {
      if (!r?.url) continue;
      const o = byUrl.get(r.url);
      if (!o || (r.at || 0) > (o.at || 0)) byUrl.set(r.url, r);
    }
    out.recents = [...byUrl.values()].sort((x, y) => (y.at || 0) - (x.at || 0)).slice(0, MAX_RECENTS);
  }
  if (b.progress && typeof b.progress === 'object') {
    const p = { ...(a.progress || {}) };
    for (const [u, v] of Object.entries(b.progress)) if (!p[u] || (v?.at || 0) > (p[u].at || 0)) p[u] = v;
    const entries = Object.entries(p);
    out.progress = entries.length > MAX_PROGRESS ? Object.fromEntries(entries.sort((x, y) => y[1].at - x[1].at).slice(0, MAX_PROGRESS)) : p;
  }
  // Playlist sources (Xtream logins / playlist links, not their channels): newest record per source wins;
  // a deleted one stays as a marker so other devices don't add it back.
  if (b.sources && typeof b.sources === 'object') {
    const s = { ...(a.sources || {}) };
    for (const [k, v] of Object.entries(b.sources)) if (v && typeof v === 'object' && (!s[k] || (v.at || 0) > (s[k].at || 0))) s[k] = v;
    out.sources = s;
  }
  return out;
}

export async function handleSync(req, res, url, send) {
  if (PROXY_KEY && url.searchParams.get('key') !== PROXY_KEY) return send(res, 403, 'Wrong or missing key');
  if (req.method !== 'POST') return send(res, 200, JSON.stringify(await load()), { 'content-type': 'application/json' });
  let incoming;
  try { incoming = JSON.parse(await readBody(req)); } catch { return send(res, 400, 'Bad body'); }
  stored = merge(await load(), incoming || {});
  const snapshot = JSON.stringify(stored);
  writing = writing.then(async () => {
    await fs.mkdir(DIR, { recursive: true });
    await fs.writeFile(FILE + '.tmp', snapshot);
    await fs.rename(FILE + '.tmp', FILE);            // atomic: a crash mid-write never corrupts the file
  }).catch(e => console.error('[sync] write failed', e.message));
  send(res, 200, snapshot, { 'content-type': 'application/json', 'cache-control': 'no-store' });
}
