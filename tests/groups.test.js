// Manage groups helpers (js/groups.js): search, ordering, bulk ops, moving, new-group policy.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { foldName, matchesQuery, letterOf, orderGroups, bulkHidden, changedCount, moveName, newGroupsToHide } from '../js/groups.js';

const G = [{ name: 'UK| Sky Sports', count: 5 }, { name: '🇺🇸 News', count: 50 }, { name: 'Béla Docs', count: 5 }, { name: '24/7 Movies', count: 9 }];

test('search folds case, accents and separators, matches shown title or raw name', () => {
  assert.equal(foldName('UK| Sky  Sports'), 'uk sky sports');
  assert.ok(matchesQuery('UK · Sky Sports', 'UK| Sky Sports', 'uk sky'));
  assert.ok(matchesQuery('UK · Sky Sports', 'UK| Sky Sports', 'UK|SKY'));
  assert.ok(matchesQuery('Béla Docs', 'Béla Docs', 'bela'));
  assert.ok(matchesQuery('x', 'x', '   '));
  assert.ok(!matchesQuery('UK · Sky', 'UK| Sky', 'uk news'));
});
test('jump-rail letters', () => {
  assert.equal(letterOf('🇺🇸 News'), 'N');
  assert.equal(letterOf('Éclair'), 'E');
  assert.equal(letterOf('24/7'), '#');
  assert.equal(letterOf(''), '#');
});
test('ordering: provider, A-Z, count (stable), custom with unknown names after', () => {
  const t = g => g.name.replace(/^[^\p{L}\p{N}]+/u, '');
  assert.deepEqual(orderGroups(G).map(g => g.name), G.map(g => g.name));
  assert.deepEqual(orderGroups(G, { sort: 'az' }, t).map(g => g.name), ['24/7 Movies', 'Béla Docs', '🇺🇸 News', 'UK| Sky Sports']);
  assert.deepEqual(orderGroups(G, { sort: 'count' }).map(g => g.name), ['🇺🇸 News', '24/7 Movies', 'UK| Sky Sports', 'Béla Docs']);
  assert.deepEqual(orderGroups(G, { sort: 'custom', order: ['Béla Docs', 'gone', 'UK| Sky Sports'] }).map(g => g.name), ['Béla Docs', 'UK| Sky Sports', '🇺🇸 News', '24/7 Movies']);
  assert.deepEqual(orderGroups(G, { sort: 'custom', order: [] }).map(g => g.name), G.map(g => g.name));
  const copy = [...G]; orderGroups(copy, { sort: 'az' }); assert.deepEqual(copy, G);   // no mutation
});
test('bulk ops only touch this tab; "only" with nothing selected is a no-op', () => {
  const all = ['a', 'b', 'c'], other = 'movie-only';
  const h = new Set(['a', other]);
  assert.deepEqual([...bulkHidden(h, all, [], 'showAll')], [other]);
  assert.deepEqual([...bulkHidden(h, all, [], 'hideAll')].sort(), ['a', 'b', 'c', other]);
  assert.deepEqual([...bulkHidden(h, all, ['b'], 'only')].sort(), ['a', 'c', other]);
  assert.deepEqual([...bulkHidden(h, all, [], 'only')].sort(), ['a', other]);
  assert.deepEqual([...bulkHidden(h, all, ['b', 'c'], 'hide')].sort(), ['a', 'b', 'c', other]);
  assert.deepEqual([...bulkHidden(h, all, ['a'], 'show')], [other]);
  assert.equal(changedCount(h, bulkHidden(h, all, [], 'hideAll'), all), 2);
});
test('moveName', () => {
  const l = ['a', 'b', 'c', 'd'];
  assert.deepEqual(moveName(l, 'c', 'top'), ['c', 'a', 'b', 'd']);
  assert.deepEqual(moveName(l, 'a', 'bottom'), ['b', 'c', 'd', 'a']);
  assert.deepEqual(moveName(l, 'b', 1), ['a', 'c', 'b', 'd']);
  assert.deepEqual(moveName(l, 'a', -1), l);
  assert.deepEqual(moveName(l, 'z', 'top'), l);
});
test('show newly added groups: baseline first, then hide only the fresh ones', () => {
  assert.deepEqual(newGroupsToHide(undefined, ['a', 'b'], 'hide'), { hide: [], known: ['a', 'b'] });
  assert.deepEqual(newGroupsToHide(['a', 'b'], ['a', 'b', 'c'], 'hide'), { hide: ['c'], known: ['a', 'b', 'c'] });
  assert.deepEqual(newGroupsToHide(['a', 'b'], ['a', 'b', 'c'], 'show'), { hide: [], known: ['a', 'b', 'c'] });
  assert.deepEqual(newGroupsToHide(['a', 'b'], ['a'], 'hide'), { hide: [], known: ['a', 'b'] });   // a group that left keeps its place
});
