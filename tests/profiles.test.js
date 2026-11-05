// Viewer profiles on the sync server (server/sync.js): the default profile is the old top level, untouched.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { EventEmitter } from 'node:events';
import { merge, mergeState, mergeProfiles, viewFor, stripProfiles, MAIN } from '../server/sync.js';

const now = Date.now();
// A copy of the real file's shape, with made-up content.
const owner = () => ({
  favorites: [{ name: 'BBC One HD', url: 'http://prov.test/u/p/1' }], favAt: now - 5000,
  recents: [{ name: 'Sky Sports', url: 'http://prov.test/u/p/2', at: now - 4000 }], recReset: now - 90000,
  progress: { 'http://prov.test/movie/u/p/7.mkv': { t: 1200, d: 6000, at: now - 3000 } },
  sources: { 'x:prov.test|u|both': { name: 'Provider', kind: 'xtream', xtream: { server: 'http://prov.test', user: 'u', pass: 'p' }, content: 'both', at: 1 } },
  reminders: { 'http://prov.test/u/p/1|9999999999999': { url: 'http://prov.test/u/p/1', start: 9999999999999, title: 'News', at: now - 100 } },
  follows: { arsenal: { q: 'Arsenal', at: now - 100 } },
  watchlater: { 'movie:7': { kind: 'movie', id: '7', name: 'Heat', at: now - 100 } },
  tvs: { SHIELD: { name: 'SHIELD', at: now - 100 } },
});
const mazie = { mz1: { name: 'Mazie', color: 3, at: now - 10 } };

test('old clients (no profile) keep reading and writing the top level, and never see other profiles', () => {
  let s = mergeState(owner(), { profiles: mazie }, 'mz1');
  s = mergeState(s, { favorites: [{ name: 'E4', url: 'http://prov.test/u/p/9' }], favAt: now }, 'mz1');
  const before = JSON.stringify(stripProfiles(s));
  const s2 = mergeState(s, { progress: { 'http://prov.test/movie/u/p/8.mkv': { t: 5, d: 100, at: now } } });   // no profile
  assert.equal(s2.progress['http://prov.test/movie/u/p/8.mkv'].t, 5);
  assert.equal(s2.p.mz1.progress, undefined, 'the old client wrote to main only');
  assert.notEqual(JSON.stringify(stripProfiles(s2)), before);
  const v = viewFor(s2);
  assert.equal(v.p, undefined, 'the reply never carries other profiles');
  assert.equal(v.favorites[0].name, 'BBC One HD');
  assert.equal(v.profiles.mz1.name, 'Mazie');
  // An unknown profile parameter value falls back to main rather than creating anything.
  assert.deepEqual(viewFor(s2, '../etc').favorites, v.favorites);
});

test('migration is a no-op for the owner: main data byte-identical, reversible by stripping', () => {
  const o = owner();
  let s = mergeState(o, { profiles: mazie });
  s = mergeState(s, { favorites: [{ name: 'E4', url: 'x' }], favAt: now, follows: { chelsea: { q: 'Chelsea', at: now } } }, 'mz1');
  assert.deepEqual(stripProfiles(s), o, 'the owner\'s data is exactly as it was');
  assert.deepEqual(viewFor(s, MAIN).favorites, o.favorites);
});

test('each profile has its own lists; shared things stay shared', () => {
  let s = mergeState(owner(), { profiles: mazie }, 'mz1');
  const v0 = viewFor(s, 'mz1');
  assert.deepEqual([v0.favorites, v0.recents, v0.progress, v0.follows, v0.watchlater, v0.reminders], [[], [], {}, {}, {}, {}], 'a new profile starts empty');
  assert.equal(v0.sources['x:prov.test|u|both'].name, 'Provider', 'playlists are shared');
  assert.equal(v0.tvs.SHIELD.name, 'SHIELD');
  s = mergeState(s, { recents: [{ name: 'E4', url: 'http://e4', at: now }], sources: { 'u:http://new': { name: 'New', kind: 'url', sourceUrl: 'http://new', at: now } } }, 'mz1');
  assert.equal(viewFor(s, 'mz1').recents.length, 1);
  assert.equal(viewFor(s).recents[0].name, 'Sky Sports', 'main recents untouched');
  assert.ok(viewFor(s).sources['u:http://new'], 'a playlist added on Mazie\'s profile is everyone\'s');
});

test('merge and tombstone rules apply per profile', () => {
  let s = mergeState(owner(), { profiles: mazie });
  s = mergeState(s, { favorites: [{ url: 'a' }], favAt: 200 }, 'mz1');
  s = mergeState(s, { favorites: [{ url: 'stale' }], favAt: 100 }, 'mz1');
  assert.deepEqual(viewFor(s, 'mz1').favorites, [{ url: 'a' }], 'newest favourites edit wins inside the profile');
  s = mergeState(s, { follows: { spurs: { q: 'Spurs', at: now - 50 } } }, 'mz1');
  s = mergeState(s, { follows: { spurs: { deleted: true, at: now } } }, 'mz1');
  assert.equal(viewFor(s, 'mz1').follows.spurs.deleted, true, 'an unfollow stays as a marker');
  s = mergeState(s, { follows: { spurs: { q: 'Spurs', at: now - 10 } } }, 'mz1');
  assert.equal(viewFor(s, 'mz1').follows.spurs.deleted, true, 'an older follow from an offline device doesn\'t bring it back');
  s = mergeState(s, { watchlater: { 'movie:7': { deleted: true, at: now } } }, 'mz1');
  assert.equal(viewFor(s).watchlater['movie:7'].name, 'Heat', 'Mazie removing a title leaves the owner\'s list alone');
  s = mergeState(s, { progress: { u1: { t: 10, at: 5 } } }, 'mz1');
  s = mergeState(s, { progress: { u1: { t: 3, at: 4 } } }, 'mz1');
  assert.equal(viewFor(s, 'mz1').progress.u1.t, 10, 'newest progress wins');
});

test('profile list: newest edit wins, at most 5, main can be renamed but not removed, removed ones stay gone', () => {
  let l = mergeProfiles({}, { main: { name: 'Will', color: 0, at: 1 }, a: { name: 'A', at: 1 }, b: { name: 'B', at: 1 }, c: { name: 'C', at: 1 }, d: { name: 'D', at: 1 }, e: { name: 'E', at: 1 } });
  assert.equal(Object.keys(l).length, 5, 'main + 4');
  l = mergeProfiles(l, { a: { name: 'Old', at: 0 } });
  assert.equal(l.a.name, 'A');
  l = mergeProfiles(l, { a: { name: 'Ann', at: 2 }, main: { deleted: true, at: 9 } });
  assert.equal(l.a.name, 'Ann'); assert.equal(l.main.name, 'Will');
  l = mergeProfiles(l, { a: { deleted: true, at: 3 } });
  l = mergeProfiles(l, { a: { name: 'Back', at: 99 } });
  assert.equal(l.a.deleted, true);
  l = mergeProfiles(l, { 'bad id!': { name: 'x', at: 1 }, f: { name: '', at: 1 } });
  assert.equal(l['bad id!'], undefined); assert.equal(l.f, undefined);
});

test('a removed profile takes no writes, its device is told, and its data goes after 30 days', () => {
  let s = mergeState(owner(), { profiles: mazie });
  s = mergeState(s, { favorites: [{ url: 'a' }], favAt: 1 }, 'mz1');
  s = mergeState(s, { profiles: { mz1: { deleted: true, at: now } } });
  s = mergeState(s, { favorites: [{ url: 'b' }], favAt: 2 }, 'mz1');
  assert.deepEqual(s.p.mz1.favorites, [{ url: 'a' }], 'kept (for undo by hand) but not written');
  assert.equal(viewFor(s, 'mz1').profileGone, true);
  s = mergeState(s, {}, MAIN, now + 31 * 86400_000);
  assert.equal(s.p, undefined);
  assert.equal(viewFor(s, 'never-made').profileGone, true);
  assert.equal(mergeState({}, { favorites: [{ url: 'z' }], favAt: 1 }, 'never-made').p, undefined, 'unknown profiles never get data');
});

test('plain merge() still behaves exactly as before for old callers', () => {
  const o = owner();
  assert.deepEqual(merge(o, {}), o);
});

test('server: /sync with and without &profile, backup made once before the first write', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'm3u-sync-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const original = JSON.stringify(owner());
  await writeFile(join(dir, 'sync.json'), original);
  process.env.SYNC_DATA_DIR = dir;
  const { handleSync } = await import(`../server/sync.js?profiles=${Date.now()}`);
  const call = (method, query, body) => new Promise((resolve, reject) => {
    const req = new EventEmitter();
    req.method = method;
    const res = {};
    const send = (_r, code, text) => resolve({ code, json: JSON.parse(text) });
    handleSync(req, res, new URL(`http://x/sync?${query}`), send).catch(reject);
    queueMicrotask(() => { if (body !== undefined) req.emit('data', Buffer.from(JSON.stringify(body))); req.emit('end'); });
  });
  let r = await call('POST', 'profile=main', { profiles: mazie });
  assert.equal(r.code, 200); assert.equal(r.json.profiles.mz1.name, 'Mazie');
  r = await call('POST', 'profile=mz1', { favorites: [{ url: 'mz-fav' }], favAt: now });
  assert.deepEqual(r.json.favorites, [{ url: 'mz-fav' }]); assert.equal(r.json.profile, 'mz1'); assert.equal(r.json.p, undefined);
  r = await call('GET', '');
  assert.equal(r.json.favorites[0].name, 'BBC One HD'); assert.equal(r.json.p, undefined);
  r = await call('GET', 'profile=mz1');
  assert.deepEqual(r.json.favorites, [{ url: 'mz-fav' }]);
  assert.equal(await readFile(join(dir, 'sync.pre-profiles.json'), 'utf8'), original, 'backup is the untouched original');
  await new Promise(r => setTimeout(r, 50));
  const saved = JSON.parse(await readFile(join(dir, 'sync.json'), 'utf8'));
  assert.deepEqual(stripProfiles(saved), owner(), 'main data on disk unchanged');
  assert.deepEqual(saved.p.mz1.favorites, [{ url: 'mz-fav' }]);
});

import { profileList, nextColor, adoptProfiles, shouldAsk, initialOf } from '../js/profiles.js';
test('client: profile list, colours, remember on this device', () => {
  assert.deepEqual(profileList({}).map(p => p.name), ['Main'], 'one profile until another is added');
  const m = { main: { name: 'Will', color: 0, at: 1 }, pb: { name: 'Mazie', color: 3, at: 2 }, pa: { deleted: true, at: 3 } };
  assert.deepEqual(profileList(m).map(p => p.name), ['Will', 'Mazie']);
  assert.equal(nextColor(m), 1);
  assert.equal(initialOf(' mazie'), 'M'); assert.equal(initialOf(''), '?'); assert.equal(initialOf('Émile'), 'É');
  assert.equal(shouldAsk({}, null), false);
  assert.equal(shouldAsk(m, null), true);
  assert.equal(shouldAsk(m, { id: 'pb', remember: true }), false);
  assert.equal(shouldAsk(m, { id: 'pa', remember: true }), true, 'a remembered profile that was removed asks again');
  const a = adoptProfiles({ pb: { name: 'Maz', at: 5 } }, m);
  assert.equal(a.pb.name, 'Maz', 'a newer local rename is kept');
  assert.equal(adoptProfiles({ pa: { name: 'X', at: 9 } }, m).pa.deleted, true, 'a removal always wins');
});

test('prefs: "Hide scores" syncs per profile, newest edit wins, unknown names and bad values dropped', () => {
  let s = mergeState({}, { prefs: { hideScores: { v: true, at: 10 }, theme: { v: 'x', at: 10 } } });
  assert.deepEqual(s.prefs, { hideScores: { v: true, at: 10 } });
  s = mergeState(s, { prefs: { hideScores: { v: false, at: 5 } } });
  assert.equal(s.prefs.hideScores.v, true);                       // older edit loses
  s = mergeState(s, { prefs: { hideScores: { v: 'yes', at: 20 } } });
  assert.equal(s.prefs.hideScores.v, true);                       // not a boolean
  s = mergeState(s, { prefs: { hideScores: { v: false, at: 30 } } });
  assert.equal(s.prefs.hideScores.v, false);
  s = mergeState(s, { profiles: { kid: { name: 'Kid', at: 1 } } });
  s = mergeState(s, { prefs: { hideScores: { v: true, at: 40 } } }, 'kid');
  assert.equal(s.prefs.hideScores.v, false);                      // main unchanged
  assert.equal(viewFor(s, 'kid').prefs.hideScores.v, true);
  assert.deepEqual(viewFor(s, 'other').prefs, {});
});
