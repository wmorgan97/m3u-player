// "Find a team / show" in the TV guide: the server returns every upcoming programme mentioning it (across the whole
// guide); this keeps the ones on the user's own channels, once each. Pure, so it can be tested; app.js does the I/O.

/**
 * matches: [{ id, name, start, end, title, desc }] from the server's guide search.
 * channels: the user's live channels ({ name, tvgId, tvgName, group, url }).
 * norm: the guide's name normaliser (normName from epg.js).
 * prefer(c): higher = better copy of a channel (e.g. not "(Backup)", in an English group).
 * Returns [{ channel, prog: [start, end, title, desc] }], one per programme (title + start), soonest first.
 */
export function matchUpcoming(matches, channels, norm, prefer = () => 0) {
  const byId = new Map(), byName = new Map();
  for (const c of channels) {
    const add = (m, k, c) => { if (!k) return; const l = m.get(k); if (l) l.push(c); else m.set(k, [c]); };
    add(byId, String(c.tvgId || '').trim().toLowerCase(), c);
    add(byName, norm(c.tvgName || ''), c);
    add(byName, norm(c.name || ''), c);
  }
  const best = new Map();   // title|start -> { channel, prog, score }
  for (const m of matches) {
    const cands = byId.get(String(m.id).toLowerCase()) || byName.get(norm(m.name)) || [];
    for (const c of cands) {
      const key = `${String(m.title).trim().toLowerCase()}|${m.start}`;
      const score = prefer(c);
      const prev = best.get(key);
      if (!prev || score > prev.score) best.set(key, { channel: c, prog: [m.start, m.end, m.title, m.desc || ''], score });
    }
  }
  return [...best.values()].sort((a, b) => a.prog[0] - b.prog[0]).map(({ channel, prog }) => ({ channel, prog }));
}

export function _selfTest() {
  const norm = s => String(s).toLowerCase().replace(/[^a-z0-9]/g, '');
  const chans = [
    { name: 'Sky Sports Main Event', tvgId: 'skysportsmainevent.uk', group: 'UK', url: 'a' },
    { name: 'Sky Sports Main Event (Backup)', tvgId: 'skysportsmainevent.uk', group: 'UK', url: 'b' },
    { name: 'BBC One', tvgId: '', tvgName: 'BBC One', group: 'UK', url: 'c' },
  ];
  const matches = [
    { id: 'skysportsmainevent.uk', name: 'Sky Sports Main Event', start: 2000, end: 3000, title: 'Arsenal v Spurs' },
    { id: 'bbc1.uk', name: 'BBC One', start: 1000, end: 1500, title: 'Match of the Day: Arsenal' },
    { id: 'foreign.tv', name: 'Arena Sport 1', start: 2000, end: 3000, title: 'Arsenal - Spurs' },
  ];
  const r = matchUpcoming(matches, chans, norm, c => (/backup/i.test(c.name) ? 0 : 1));
  const ok = r.length === 2 && r[0].channel.url === 'c' && r[1].channel.url === 'a' && r[1].prog[2] === 'Arsenal v Spurs';
  return ok ? 'ok' : `failed: ${JSON.stringify(r.map(x => [x.channel.url, x.prog[2]]))}`;
}
