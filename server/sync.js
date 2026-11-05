// Cross-device state: favourites, recently watched and watch progress, kept in one JSON
// file so every device that has the server key sees the same lists. The server merges:
//   favourites  whole list, newest edit wins (favAt)
//   recents     union by url, newest play first; an explicit edit (recReset) replaces the list
//   progress    per url, newest wins
//   reminders   per "<channel url>|<start ms>", newest edit (at) wins; a removed one stays as a
//               { deleted } marker until the programme has passed, so other devices drop it too
//   follows     teams and shows followed, per lower-cased name ({ q, at }), newest edit wins; an unfollow stays as a
//               { deleted } marker (for 90 days) so other devices drop it too
//   watchlater  films and series saved for later, per "<movie|series>:<id>" ({ kind, id, name, logo, … at }), newest
//               edit wins; a removed one stays as a { deleted } marker (for 90 days) so other devices drop it too
//
//   handoff     "Play on TV": the phone's one latest request { id, kind, url, name, t, at, sent, from }; newest at wins;
//               a TV that plays it adds { taken, takenAt } (same id). Dropped after 10 minutes; TVs act only on ones < 60 s old.
//   tvs         TV apps that have synced, per name ({ name, at }): the phone shows "Play on TV" only once a TV exists
//
// Viewer profiles ("Who's watching?"): playlists, settings, TVs and Play on TV stay shared; the personal lists
// (PERSONAL below) are per profile. The default profile ("main") is the top level of the file, exactly as before
// profiles existed, so an app that doesn't know about profiles keeps reading and writing it. Other profiles live in
//   p           { <profile id>: { favorites, favAt, recents, … } }, merged with the same rules as the top level
//   profiles    { <id>: { name, color, at } }, newest edit per id wins; a removed one stays { deleted } for good
//               (its data is kept 30 days, then dropped). "main" can be renamed, never removed.
// A request picks its profile with &profile=<id> (none = main); the reply shows that profile's lists at the top level
// and never includes `p`. The first load backs the file up once to data/sync.pre-profiles.json.
//
//   POST /sync?key=<PROXY_KEY>   body: the device's state -> the merged state
//   GET  /sync?key=…&handoff=1[&tv=<name>]   just { handoff, now, live } (cheap: the TV app polls it every few seconds
//               while it's open; tv=<name> marks that TV as open now, which the phone reads from `live`)

import { promises as fs, constants as fsc } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const PROXY_KEY = process.env.PROXY_KEY || '';
const DIR = process.env.SYNC_DATA_DIR || fileURLToPath(new URL('../data/', import.meta.url));
const FILE = join(DIR, 'sync.json'), BACKUP = join(DIR, 'sync.pre-profiles.json');
const MAX_RECENTS = 50, MAX_PROGRESS = 500, MAX_BODY = 4 << 20;

let stored = null;
let writing = Promise.resolve();

async function load() {
  if (!stored) {
    try { stored = JSON.parse(await fs.readFile(FILE, 'utf8')); } catch { stored = {}; }
    // Before profiles touch the file for the first time: a copy of it as it was (never overwritten).
    if (!stored.profiles && !stored.p) await fs.copyFile(FILE, BACKUP, fsc.COPYFILE_EXCL).catch(() => {});
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
  if (b.reminders && typeof b.reminders === 'object') {
    const r = { ...(a.reminders || {}) }, old = Date.now() - 6 * 3600_000;
    for (const [k, v] of Object.entries(b.reminders)) if (v && typeof v === 'object' && (!r[k] || (v.at || 0) > (r[k].at || 0))) r[k] = v;
    for (const [k, v] of Object.entries(r)) if (!(v?.start > old)) delete r[k];
    out.reminders = r;
  }
  if (b.follows && typeof b.follows === 'object' && !Array.isArray(b.follows)) {
    const f = { ...(a.follows || {}) }, old = Date.now() - 90 * 86400_000;
    for (const [k0, v] of Object.entries(b.follows)) {
      const k = k0.trim().toLowerCase();
      if (!k || k.length > 80 || !v || typeof v !== 'object') continue;
      const e = v.deleted ? { deleted: true, at: +v.at || 0 } : { q: String(v.q || k0).trim().slice(0, 80), at: +v.at || 0 };
      if (!f[k] || e.at > (f[k].at || 0)) f[k] = e;
    }
    for (const [k, v] of Object.entries(f)) if (v.deleted && !(v.at > old)) delete f[k];
    const keys = Object.keys(f);
    out.follows = keys.length > 200 ? Object.fromEntries(keys.sort((x, y) => f[y].at - f[x].at).slice(0, 200).map(k => [k, f[k]])) : f;
  }
  if (b.watchlater && typeof b.watchlater === 'object' && !Array.isArray(b.watchlater)) out.watchlater = mergeWatchLater(a.watchlater, b.watchlater);
  if (b.handoff && typeof b.handoff === 'object') out.handoff = mergeHandoff(a.handoff, b.handoff);
  if (out.handoff && !(out.handoff.sent > Date.now() - HANDOFF_KEEP)) delete out.handoff;
  if (b.tvs && typeof b.tvs === 'object' && !Array.isArray(b.tvs)) {
    const t = { ...(a.tvs || {}) };
    for (const [k0, v] of Object.entries(b.tvs)) {
      const k = String(k0).trim().slice(0, 60);
      if (k && v && typeof v === 'object' && (+v.at || 0) > (t[k]?.at || 0)) t[k] = { name: String(v.name || k).slice(0, 60), at: +v.at };
    }
    const keys = Object.keys(t);
    out.tvs = keys.length > 10 ? Object.fromEntries(keys.sort((x, y) => t[y].at - t[x].at).slice(0, 10).map(k => [k, t[k]])) : t;
  }
  return out;
}

// ---------- viewer profiles ----------
export const MAIN = 'main', MAX_PROFILES = 5;
export const PERSONAL = ['favorites', 'favAt', 'recents', 'recReset', 'progress', 'reminders', 'follows', 'watchlater'];
const EMPTY = () => ({ favorites: [], favAt: 0, recents: [], recReset: 0, progress: {}, reminders: {}, follows: {}, watchlater: {} });
const PROFILE_KEEP = 30 * 86400_000;
export const profileId = v => { const s = String(v || '').trim(); return /^[A-Za-z0-9_-]{1,24}$/.test(s) ? s : MAIN; };
const liveProfiles = l => Object.entries(l || {}).filter(([k, v]) => k !== MAIN && v && !v.deleted).length;
export function mergeProfiles(a0, b) {
  const out = { ...(a0 && typeof a0 === 'object' ? a0 : {}) };
  for (const [k0, v] of Object.entries(b && typeof b === 'object' && !Array.isArray(b) ? b : {})) {
    const k = String(k0).trim();
    if (profileId(k) !== k || !v || typeof v !== 'object' || out[k]?.deleted) continue;   // removed ids never come back
    const at = +v.at || 0;
    const e = v.deleted && k !== MAIN ? { deleted: true, at } : { name: String(v.name || '').trim().slice(0, 24), color: Math.max(0, Math.min(9, Math.trunc(+v.color) || 0)), at };
    if (!e.deleted && !e.name) continue;
    if (out[k] && at <= (out[k].at || 0)) continue;
    if (!out[k] && !e.deleted && k !== MAIN && liveProfiles(out) >= MAX_PROFILES - 1) continue;   // main + 4 more
    out[k] = e;
  }
  return out;
}
/** Merges one device's POST into the stored state, for [profile] (default: main, the top level). */
export function mergeState(a0, b0, profile, now = Date.now()) {
  const a = a0 || {}, b = { ...(b0 && typeof b0 === 'object' ? b0 : {}) };
  const pid = profileId(profile);
  const profiles = b.profiles !== undefined ? mergeProfiles(a.profiles, b.profiles) : a.profiles;
  delete b.profiles; delete b.p; delete b.profile;
  let out;
  if (pid === MAIN) out = merge(a, b);
  else {
    const mine = {};
    for (const k of PERSONAL) if (k in b) { mine[k] = b[k]; delete b[k]; }
    out = merge(a, b);
    const prof = profiles?.[pid];
    // Only a profile that exists (and wasn't removed) takes writes: a removed one's device gets told, below.
    if (prof && !prof.deleted && Object.keys(mine).length) out.p = { ...(a.p || {}), [pid]: merge(a.p?.[pid] || {}, mine) };
  }
  if (profiles) out.profiles = profiles;
  if (out.p) for (const id of Object.keys(out.p)) {
    const pr = out.profiles?.[id];
    if (pr?.deleted && !(pr.at > now - PROFILE_KEEP)) { out.p = { ...out.p }; delete out.p[id]; }
  }
  if (out.p && !Object.keys(out.p).length) delete out.p;
  return out;
}
/** What a device on [profile] sees: shared data plus that profile's lists at the top level (never `p`). */
export function viewFor(state, profile) {
  const { p, ...rest } = state || {};
  const pid = profileId(profile);
  if (pid === MAIN) return rest;
  for (const k of PERSONAL) delete rest[k];
  const v = { ...rest, ...EMPTY(), ...(p?.[pid] || {}), profile: pid };
  if (!state?.profiles?.[pid] || state.profiles[pid].deleted) v.profileGone = true;
  return v;
}
/** Rolls the file back to what it was before profiles: the default profile's data stays, the rest goes. */
export function stripProfiles(state) { const { p, profiles, ...rest } = state || {}; return rest; }

// ---------- Play on TV ----------
const HANDOFF_KEEP = 10 * 60_000, HANDOFF_FIELDS = ['id', 'kind', 'url', 'name', 'logo', 'group', 'src', 'sid', 'series', 'season', 'num', 'year', 'from', 'tvgId', 'profile'];
// One request at a time: a newer one (by at) replaces it; the TV's "taken" for the same id is added to it.
export function mergeHandoff(a, b) {
  if (!b?.id) return a;
  if (a?.id === b.id) return b.taken && !a.taken ? { ...a, taken: String(b.taken).slice(0, 60), takenAt: +b.takenAt || Date.now() } : a;
  if (a && (+b.at || 0) <= (+a.at || 0)) return a;
  if (!/^https?:\/\//i.test(String(b.url || ''))) return a;
  const e = { at: +b.at || 0, sent: +b.sent || Date.now() };
  for (const f of HANDOFF_FIELDS) if (b[f] !== undefined && b[f] !== null && b[f] !== '') e[f] = typeof b[f] === 'string' ? b[f].slice(0, 1000) : b[f];
  if (Number.isFinite(+b.t) && +b.t > 0) e.t = +b.t;
  if (Number.isFinite(+b.d) && +b.d > 0) e.d = +b.d;
  return e;
}
// TV apps polling for handoffs right now: name -> last poll (memory only; a restart forgets, which is fine).
const tvPolls = new Map();
export function handoffView(state, now = Date.now()) {
  const live = {};
  for (const [k, at] of tvPolls) if (now - at < 20_000) live[k] = at; else tvPolls.delete(k);
  return { handoff: state?.handoff || null, now, live };
}

const MAX_WATCHLATER = 300, WL_FIELDS = ['kind', 'id', 'name', 'logo', 'year', 'rating', 'group', 'ext', 'url'];
// Watch later: newest edit (at) per key wins; removals are kept 90 days so a device that was offline drops them too.
export function mergeWatchLater(a0, b) {
  const w = { ...(a0 && typeof a0 === 'object' ? a0 : {}) }, old = Date.now() - 90 * 86400_000;
  for (const [k0, v] of Object.entries(b)) {
    const k = String(k0).trim();
    if (!/^(movie|series):\S{1,120}$/.test(k) || !v || typeof v !== 'object') continue;
    let e;
    if (v.deleted) e = { deleted: true, at: +v.at || 0 };
    else {
      e = { at: +v.at || 0 };
      for (const f of WL_FIELDS) if (v[f] !== undefined && v[f] !== null && v[f] !== '') e[f] = typeof v[f] === 'string' ? v[f].slice(0, 600) : v[f];
      if (!e.name) continue;
    }
    if (!w[k] || e.at > (w[k].at || 0)) w[k] = e;
  }
  for (const [k, v] of Object.entries(w)) if (v?.deleted && !(v.at > old)) delete w[k];
  const keys = Object.keys(w);
  return keys.length > MAX_WATCHLATER ? Object.fromEntries(keys.sort((x, y) => (w[y].at || 0) - (w[x].at || 0)).slice(0, MAX_WATCHLATER).map(k => [k, w[k]])) : w;
}

export async function handleSync(req, res, url, send) {
  if (PROXY_KEY && url.searchParams.get('key') !== PROXY_KEY) return send(res, 403, 'Wrong or missing key');
  if (req.method !== 'POST' && url.searchParams.has('handoff')) {
    const tv = (url.searchParams.get('tv') || '').trim().slice(0, 60);
    if (tv) tvPolls.set(tv, Date.now());
    return send(res, 200, JSON.stringify(handoffView(await load())), { 'content-type': 'application/json', 'cache-control': 'no-store' });
  }
  const profile = url.searchParams.get('profile');
  if (req.method !== 'POST') return send(res, 200, JSON.stringify(viewFor(await load(), profile)), { 'content-type': 'application/json' });
  let incoming;
  try { incoming = JSON.parse(await readBody(req)); } catch { return send(res, 400, 'Bad body'); }
  // The server's own clock dates a new handoff, so phone/TV clocks that disagree can't make it look stale or fresh.
  if (incoming?.handoff && typeof incoming.handoff === 'object' && !incoming.handoff.taken) incoming.handoff.sent = Date.now();
  stored = mergeState(await load(), incoming || {}, profile);
  const snapshot = JSON.stringify(stored);
  writing = writing.then(async () => {
    await fs.mkdir(DIR, { recursive: true });
    await fs.writeFile(FILE + '.tmp', snapshot);
    await fs.rename(FILE + '.tmp', FILE);            // atomic: a crash mid-write never corrupts the file
  }).catch(e => console.error('[sync] write failed', e.message));
  send(res, 200, JSON.stringify(viewFor(stored, profile)), { 'content-type': 'application/json', 'cache-control': 'no-store' });
}
