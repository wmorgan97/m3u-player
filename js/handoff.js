// "Play on TV": the phone hands what it's playing (or about to play) to the TV app through the server's /sync
// (server/sync.js, `handoff`). The TV app polls GET /sync?handoff=1 every few seconds while it's open and plays a
// request once, if it's less than a minute old. These helpers are pure (tested in tests/handoff.test.js).

export const HANDOFF_FRESH = 60_000;          // a TV acts only on requests younger than this (server clock)
export const TV_KNOWN_FOR = 60 * 86_400_000;   // "Play on TV" shows once a TV app has synced in the last 60 days

const kindOf = c => c.kind === 'catchup' ? 'catchup' : c.kind === 'episode' ? 'episode' : c.kind === 'movie' ? 'movie' : !c.kind || c.kind === 'live' ? 'live' : 'movie';

/** The request for item c at t seconds (of d). A download plays from the phone's storage: the TV gets its original link. */
export function handoffEntry(c, { t = 0, d = 0, from = 'iPhone', now = Date.now(), id } = {}) {
  const kind = kindOf(c);
  const url = c.pkey || c.url;
  if (!/^https?:\/\//i.test(String(url || ''))) return null;
  const e = { id: id || `h${now.toString(36)}${Math.random().toString(36).slice(2, 7)}`, kind, url, name: c.name || '', logo: c.logo || '', group: c.group || '', from, at: now };
  if (c.src) e.src = c.src;
  if (c.tvgId) e.tvgId = c.tvgId;
  if (kind === 'episode') { if (c.season != null) e.season = c.season; if (c.num != null) e.num = c.num; }
  if (kind !== 'live' && t > 0) { e.t = Math.floor(t); if (d > 0) e.d = Math.floor(d); }
  return e;
}

/** Whether a TV should still act on h (by the server's clock `now`). */
export const isFresh = (h, now) => !!h?.id && !h.taken && now - (h.sent || 0) < HANDOFF_FRESH && now - (h.sent || 0) > -5000;

/** Whether this account has a TV app at all (it synced recently): decides if "Play on TV" is offered. */
export const hasTv = (tvs, now = Date.now()) => Object.values(tvs || {}).some(v => v?.at > now - TV_KNOWN_FOR);

/** TV apps open right now (polling), from GET /sync?handoff=1's `live`. */
export const openTvs = view => Object.keys(view?.live || {});
