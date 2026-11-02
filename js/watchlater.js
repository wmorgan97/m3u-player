// Watch later: films and series you've saved to watch, shared with your other devices through /sync (server/sync.js)
// as a map keyed "<movie|series>:<id>" (the provider's stream / series id, the same on every device):
//   { "movie:123": { kind, id, name, logo, year, rating, group, ext, at }, "series:9": { deleted: true, at } }
// Newest edit wins; a removal goes up as a { deleted } marker. The TV app (tv/Repo.kt) uses the same keys and rules.
// Pure, so it can be tested; app.js does the storage and the drawing.

/** The key of a film or series: its provider id when it has one (Xtream), else its plain title. */
export function wlKey(it) {
  if (!it || (it.kind !== 'movie' && it.kind !== 'series')) return '';
  const id = it.sid;
  if (id !== null && id !== undefined && String(id) !== '') return `${it.kind}:${id}`;
  const t = String(it.name || '').toLowerCase().replace(/&/g, 'and').replace(/[^a-z0-9]+/g, '');
  return t ? `${it.kind}:t:${t}` : '';
}

/** What's saved for an item (enough to draw its poster and open it again on any device). */
export function wlEntry(it, at = Date.now()) {
  const ext = it.kind === 'movie' ? (String(it.url || '').match(/\.(\w{2,5})(?:[?#].*)?$/)?.[1] || it.ext || '') : '';
  const e = { kind: it.kind, id: it.sid ?? '', name: it.name || '', logo: it.logo || '', year: it.year ? +it.year || undefined : undefined,
    rating: it.rating || undefined, group: it.group || '', ext: ext || undefined, at };
  if (it.sid === undefined && it.url) e.url = it.url;   // a plain playlist's film: its own link
  return Object.fromEntries(Object.entries(e).filter(([, v]) => v !== undefined && v !== ''));
}

/** A film's provider id from its link (".../movie/<user>/<pass>/<id>.<ext>"), or ''. */
export const movieIdOf = url => String(url || '').match(/\/movie\/[^?#]*\/(\d+)\.\w+(?:[?#]|$)/)?.[1] || '';

/** When each film was finished (newest), by provider id, from the shared watch progress. */
function finishedMovies(progress) {
  const out = new Map();
  for (const [u, p] of Object.entries(progress || {})) {
    if (!p?.done) continue;
    const id = movieIdOf(u);
    if (id && (p.at || 0) > (out.get(id) || 0)) out.set(id, p.at || 0);
  }
  return out;
}

/**
 * The Watch later row: most recently added first. A film you've finished since adding it drops off by itself
 * (started ones stay); removed ones are { deleted } markers and never shown.
 * Returns [{ key, ...entry }].
 */
export function watchLaterList(map, progress) {
  const done = finishedMovies(progress);
  return Object.entries(map || {})
    .filter(([k, v]) => v && !v.deleted && v.name && !(v.kind === 'movie' && (done.get(String(v.id)) || 0) > (v.at || 0)) && /^(movie|series):/.test(k))
    .map(([key, v]) => ({ key, ...v }))
    .sort((a, b) => (b.at || 0) - (a.at || 0));
}

/** Takes in the server's merged map: newest edit per key wins (the server already merged; this keeps local edits made since). */
export function adoptWatchLater(mine, remote) {
  const out = { ...(mine || {}) };
  for (const [k, v] of Object.entries(remote || {})) if (v && (!out[k] || (v.at || 0) > (out[k].at || 0))) out[k] = v;
  return out;
}

export function _selfTest() {
  const m = { kind: 'movie', sid: 12, name: 'Heat', url: 'http://x/movie/u/p/12.mkv', logo: 'l', year: '1995' };
  const ok = wlKey(m) === 'movie:12' && wlEntry(m, 5).ext === 'mkv' && movieIdOf(m.url) === '12';
  return ok ? 'ok' : 'failed';
}
