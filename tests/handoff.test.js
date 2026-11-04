import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handoffEntry, isFresh, hasTv, openTvs, HANDOFF_FRESH } from '../js/handoff.js';
import { merge, mergeHandoff, handoffView } from '../server/sync.js';

const film = { kind: 'movie', name: 'EN - Heat (1995)', url: 'http://prov:8080/movie/u/p/101.mkv', logo: 'http://img/h.jpg', group: 'Action', src: 'pl1' };
const chan = { name: 'BBC One HD', url: 'http://prov:8080/u/p/55', group: 'UK', tvgId: 'bbc1.uk' };

test('entries: films carry the position, live never does, downloads hand over the original link', () => {
  const e = handoffEntry(film, { t: 1234.7, d: 6000, now: 1000, id: 'a' });
  assert.deepEqual(e, { id: 'a', kind: 'movie', url: film.url, name: film.name, logo: film.logo, group: 'Action', from: 'iPhone', at: 1000, src: 'pl1', t: 1234, d: 6000 });
  const l = handoffEntry(chan, { t: 50, now: 1 });
  assert.equal(l.kind, 'live'); assert.equal(l.t, undefined); assert.equal(l.tvgId, 'bbc1.uk');
  const off = handoffEntry({ ...film, url: 'file:///var/mobile/x.mkv', pkey: film.url, offline: true }, { t: 10 });
  assert.equal(off.url, film.url);
  assert.equal(handoffEntry({ ...film, url: 'blob:abc' }), null);
  const ep = handoffEntry({ kind: 'episode', name: 'S1E2', url: 'http://p/series/u/p/9.mp4', season: '1', num: 2 }, { t: 0 });
  assert.equal(ep.kind, 'episode'); assert.equal(ep.season, '1'); assert.equal(ep.num, 2); assert.equal(ep.t, undefined);
});

test('freshness: under a minute, not taken, by the server clock', () => {
  const h = { id: 'x', sent: 10_000 };
  assert.equal(isFresh(h, 10_000 + HANDOFF_FRESH - 1), true);
  assert.equal(isFresh(h, 10_000 + HANDOFF_FRESH + 1), false);
  assert.equal(isFresh({ ...h, taken: 'Shield' }, 10_001), false);
  assert.equal(isFresh(null, 1), false);
});

test('server: newest request wins, the TV marks it taken once, old ones expire', () => {
  const now = Date.now();
  let s = merge({}, { handoff: { id: 'a', kind: 'movie', url: film.url, at: now - 5, sent: now, t: 99 } });
  assert.equal(s.handoff.id, 'a'); assert.equal(s.handoff.t, 99);
  s = merge(s, { handoff: { id: 'old', url: film.url, at: now - 100, sent: now } });
  assert.equal(s.handoff.id, 'a', 'an older request never replaces a newer one');
  s = merge(s, { handoff: { id: 'a', taken: 'Shield', takenAt: now } });
  assert.equal(s.handoff.taken, 'Shield'); assert.equal(s.handoff.url, film.url);
  s = merge(s, { handoff: { id: 'a', taken: 'Other TV' } });
  assert.equal(s.handoff.taken, 'Shield', 'only the first TV takes it');
  assert.equal(mergeHandoff(undefined, { id: 'b', url: 'javascript:alert(1)', at: 1 }), undefined, 'only http(s) links');
  assert.equal(mergeHandoff(undefined, { id: 'c', taken: 'Shield' }), undefined, 'a late "taken" for a gone request is dropped');
  s = merge({ handoff: { id: 'z', url: film.url, at: 1, sent: now - 11 * 60_000 } }, {});
  assert.equal(s.handoff, undefined, 'dropped after 10 minutes');
});

test('server: TVs are remembered per name, newest wins', () => {
  let s = merge({}, { tvs: { Shield: { name: 'Shield', at: 5 } } });
  s = merge(s, { tvs: { Shield: { name: 'Shield', at: 3 }, Bedroom: { at: 7 } } });
  assert.deepEqual(s.tvs, { Shield: { name: 'Shield', at: 5 }, Bedroom: { name: 'Bedroom', at: 7 } });
  assert.equal(hasTv(s.tvs, 10), true);
  assert.equal(hasTv({}, 10), false);
  assert.deepEqual(openTvs(handoffView({ handoff: null })), []);
});
