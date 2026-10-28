// New episodes of favourite series. The provider's episode list for each favourite is compared with the one seen last
// time; anything new is announced once. Pure functions here (tested below); app.js does the fetching and notifying.

/** Every episode id in an Xtream get_series_info reply, with its season/number/title, in season order. */
export function episodeList(info) {
  const eps = info?.episodes || {};
  const out = [];
  for (const season of Object.keys(eps).sort((a, b) => a - b)) {
    for (const ep of eps[season] || []) {
      if (ep?.id == null) continue;
      out.push({ id: String(ep.id), season: Number(season) || Number(ep.season) || 1, num: Number(ep.episode_num) || 0, title: ep.title || '' });
    }
  }
  return out;
}

/**
 * Compares a fresh episode list with what was seen before.
 * seen: { ids: [..] } or undefined (never checked: nothing is "new" yet, the list just becomes the baseline).
 * Returns { added: [episodes], seen: { ids } } — added is empty on the first check, or if the provider returned nothing
 * (an outage must not make the next good reply look like a whole series of new episodes).
 */
export function diffEpisodes(seen, list) {
  if (!list.length) return { added: [], seen: seen || { ids: [] } };
  const ids = list.map(e => e.id);
  if (!seen || !Array.isArray(seen.ids)) return { added: [], seen: { ids } };
  const before = new Set(seen.ids);
  const added = list.filter(e => !before.has(e.id));
  return { added: added.length > 6 ? [] : added, seen: { ids } };   // (a re-numbered catalogue, not 7+ new episodes)
}

/** "Show — S2 E5 · Title" / "Show — 3 new episodes" */
export function newEpisodeText(show, added) {
  if (added.length === 1) {
    const e = added[0];
    return { title: `New episode: ${show}`, body: `S${e.season} E${e.num}${e.title && !/^episode \d+$/i.test(e.title) ? ` · ${e.title}` : ''}` };
  }
  const last = added[added.length - 1];
  return { title: `${added.length} new episodes: ${show}`, body: `Up to S${last.season} E${last.num}` };
}

export function _selfTest() {
  const info = { episodes: { 1: [{ id: 11, episode_num: 1, title: 'Pilot' }, { id: 12, episode_num: 2, title: 'Two' }], 2: [{ id: 21, episode_num: 1, title: 'Episode 1' }] } };
  const l = episodeList(info);
  const first = diffEpisodes(undefined, l);
  const more = episodeList({ episodes: { ...info.episodes, 2: [...info.episodes[2], { id: 22, episode_num: 2, title: 'The Return' }] } });
  const second = diffEpisodes(first.seen, more);
  const empty = diffEpisodes(second.seen, []);
  const checks = [
    l.length === 3 && l[2].season === 2,
    first.added.length === 0 && first.seen.ids.length === 3,
    second.added.length === 1 && second.added[0].id === '22',
    empty.added.length === 0 && empty.seen.ids.length === 4,
    newEpisodeText('Show', second.added).body === 'S2 E2 · The Return',
    newEpisodeText('Show', [{ season: 2, num: 1, title: 'Episode 1' }]).body === 'S2 E1',
    newEpisodeText('Show', [{ season: 2, num: 1 }, { season: 2, num: 2 }]).title === '2 new episodes: Show',
    diffEpisodes({ ids: ['1'] }, Array.from({ length: 8 }, (_, i) => ({ id: String(i + 10) }))).added.length === 0,
  ];
  return checks.every(Boolean) ? 'ok' : `failed: ${checks.map((c, i) => (c ? '' : i)).filter(String).join(',')}`;
}
