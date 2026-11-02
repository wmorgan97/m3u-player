import { test } from 'node:test';
import assert from 'node:assert/strict';
import { wlKey, wlEntry, watchLaterList, adoptWatchLater, movieIdOf, _selfTest } from '../js/watchlater.js';
import { merge, mergeWatchLater } from '../server/sync.js';

const film = { kind: 'movie', sid: 101, name: 'EN - Heat (1995)', logo: 'http://img/heat.jpg', year: '1995', rating: 7.9, group: 'Action', url: 'http://prov:8080/movie/u/p/101.mkv' };
const show = { kind: 'series', sid: 7, name: 'Slow Horses', logo: 'http://img/sh.jpg', year: '2022' };

test('keys and entries', () => {
  assert.equal(_selfTest(), 'ok');
  assert.equal(wlKey(film), 'movie:101');
  assert.equal(wlKey(show), 'series:7');
  assert.equal(wlKey({ kind: 'movie', name: 'Plain & Simple', url: 'http://x/a.mp4' }), 'movie:t:plainandsimple');
  assert.equal(wlKey({ kind: 'live', name: 'BBC One' }), '');
  const e = wlEntry(film, 50);
  assert.deepEqual(e, { kind: 'movie', id: 101, name: film.name, logo: film.logo, year: 1995, rating: 7.9, group: 'Action', ext: 'mkv', at: 50 });
  assert.equal(movieIdOf('http://prov/movie/u/p/101.mkv'), '101');
  assert.equal(movieIdOf('http://prov/series/u/p/101.mkv'), '');
});

test('list: newest first, removals hidden, finished films drop off, started ones stay', () => {
  const map = { 'movie:101': wlEntry(film, 100), 'series:7': wlEntry(show, 200), 'movie:5': { deleted: true, at: 300 } };
  assert.deepEqual(watchLaterList(map, {}).map(e => e.key), ['series:7', 'movie:101']);
  // Started (past 5 s), not finished: stays.
  const started = { 'http://prov:8080/movie/u/p/101.mkv': { t: 60, d: 6000, done: false, at: 150 } };
  assert.deepEqual(watchLaterList(map, started).map(e => e.key), ['series:7', 'movie:101']);
  // Finished after it was added: gone, on every device (progress is shared too).
  const finished = { 'http://other-host/movie/u/p/101.mp4': { t: 5990, d: 6000, done: true, at: 150 } };
  assert.deepEqual(watchLaterList(map, finished).map(e => e.key), ['series:7']);
  // Added again after finishing it: back on the list.
  assert.deepEqual(watchLaterList({ ...map, 'movie:101': wlEntry(film, 400) }, finished).map(e => e.key), ['movie:101', 'series:7']);
});

test('adopt: newest edit per key wins', () => {
  const mine = { 'movie:1': { kind: 'movie', name: 'A', at: 10 }, 'movie:2': { deleted: true, at: 30 } };
  const remote = { 'movie:1': { deleted: true, at: 20 }, 'movie:2': { kind: 'movie', name: 'B', at: 25 }, 'series:3': { kind: 'series', name: 'C', at: 5 } };
  const out = adoptWatchLater(mine, remote);
  assert.equal(out['movie:1'].deleted, true);
  assert.equal(out['movie:2'].deleted, true);
  assert.equal(out['series:3'].name, 'C');
});

test('server merge carries watchlater and keeps tombstones', () => {
  const now = Date.now();
  const a = { favorites: [], watchlater: { 'movie:101': { kind: 'movie', id: 101, name: 'Heat', at: now - 1000 } } };
  // Another device removes it, and adds a series.
  const b = { watchlater: { 'movie:101': { deleted: true, at: now }, 'series:7': { kind: 'series', id: 7, name: 'Slow Horses', at: now, evil: 'x' } } };
  const out = merge(a, b);
  assert.deepEqual(out.watchlater['movie:101'], { deleted: true, at: now });
  assert.equal(out.watchlater['series:7'].name, 'Slow Horses');
  assert.equal(out.watchlater['series:7'].evil, undefined);
  // An older edit doesn't win back.
  const again = merge(out, { watchlater: { 'movie:101': { kind: 'movie', id: 101, name: 'Heat', at: now - 500 } } });
  assert.equal(again.watchlater['movie:101'].deleted, true);
  // A device that doesn't know about Watch later leaves it alone.
  assert.deepEqual(merge(again, { favorites: [], favAt: 1 }).watchlater, again.watchlater);
  // Bad keys and nameless entries are ignored; old tombstones expire.
  const w = mergeWatchLater({ 'movie:9': { deleted: true, at: now - 100 * 86400_000 } }, { 'live:1': { name: 'x', at: 1 }, 'movie:2': { at: 1 } });
  assert.deepEqual(w, {});
});
