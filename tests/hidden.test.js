// Hidden groups / channels sync (server/sync.js): per-key newest edit wins, tombstones, profiles, old clients.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { merge, mergeState, viewFor, mergeHidden } from '../server/sync.js';

const now = Date.now(), G = (k, g) => `g\u0001${k}\u0001${g}`, C = u => `c\u0001${u}`;
const K = 'x:prov.test|u|both';

test('two devices union their hides', () => {
  let s = mergeState({}, { hidden: { [G(K, 'Adult')]: { h: 1, at: 10 } } });
  s = mergeState(s, { hidden: { [G(K, 'News')]: { h: 1, at: 20 }, [C('http://a/1')]: { h: 1, at: 21 } } });
  assert.deepEqual(Object.keys(s.hidden).sort(), [G(K, 'Adult'), G(K, 'News'), C('http://a/1')].sort());
});
test('hide then show on another device propagates; an older hide cannot resurrect it', () => {
  let s = mergeState({}, { hidden: { [G(K, 'News')]: { h: 1, at: now - 2000 } } });
  s = mergeState(s, { hidden: { [G(K, 'News')]: { h: 0, at: now - 1000 } } });
  assert.equal(s.hidden[G(K, 'News')].h, 0);
  s = mergeState(s, { hidden: { [G(K, 'News')]: { h: 1, at: now - 2000 } } });   // a device that was offline
  assert.equal(s.hidden[G(K, 'News')].h, 0);
  s = mergeState(s, { hidden: { [G(K, 'News')]: { h: 1, at: now } } });
  assert.equal(s.hidden[G(K, 'News')].h, 1);
});
test('show markers expire after 90 days, hides never do; junk keys are ignored', () => {
  const h = mergeHidden({}, { [G(K, 'Old')]: { h: 0, at: now - 100 * 86400_000 }, [G(K, 'Keep')]: { h: 1, at: 1 }, bad: { h: 1, at: 5 }, [G(K, 'x')]: 'no' });
  assert.deepEqual(Object.keys(h), [G(K, 'Keep')]);
});
test('profiles keep their own hidden lists; main is the top level', () => {
  const profiles = { mz: { name: 'Mazie', color: 1, at: 1 } };
  let s = mergeState({}, { profiles, hidden: { [G(K, 'A')]: { h: 1, at: 5 } } }, 'mz');
  s = mergeState(s, { hidden: { [G(K, 'B')]: { h: 1, at: 6 } } });
  assert.deepEqual(Object.keys(viewFor(s).hidden), [G(K, 'B')]);
  assert.deepEqual(Object.keys(viewFor(s, 'mz').hidden), [G(K, 'A')]);
  assert.deepEqual(viewFor(mergeState(s, { profiles }, 'mz'), 'mz').hidden[G(K, 'A')], { h: 1, at: 5 });
});
test('old clients that never send hidden leave it alone; unknown keys are preserved', () => {
  let s = mergeState({ futureThing: { x: 1 } }, { hidden: { [G(K, 'A')]: { h: 1, at: 5 } } });
  s = mergeState(s, { favorites: [], favAt: 1 });
  assert.equal(s.hidden[G(K, 'A')].h, 1);
  assert.deepEqual(s.futureThing, { x: 1 });
  assert.equal(merge({ hidden: s.hidden }, {}).hidden, s.hidden);
});

test('laptop + phone round trip through the POST path, per profile', () => {
  const profiles = { mz: { name: 'Mazie', color: 1, at: 1 } };
  const post = (st, body, prof) => { const n = mergeState(st.s, body, prof); st.s = n; return viewFor(n, prof); };
  const st = { s: {} };
  post(st, { profiles });
  // phone already had its own local hide (at 1: union on first sync); laptop hides Adult later
  post(st, { hidden: { [G(K, 'Sport')]: { h: 1, at: 1 } } });
  let v = post(st, { hidden: { [G(K, 'Adult')]: { h: 1, at: now } } });
  assert.equal(v.hidden[G(K, 'Adult')].h, 1);
  assert.equal(v.hidden[G(K, 'Sport')].h, 1, 'the phone sees both: union');
  // phone shows Adult; laptop pulls it
  post(st, { hidden: { [G(K, 'Adult')]: { h: 0, at: now + 5 } } });
  v = post(st, {});
  assert.equal(v.hidden[G(K, 'Adult')].h, 0);
  // Mazie's profile is untouched by main's hides, and vice versa
  assert.deepEqual(viewFor(st.s, 'mz').hidden, {});
  post(st, { hidden: { [G(K, 'Kids')]: { h: 1, at: now } } }, 'mz');
  assert.equal(post(st, {}).hidden[G(K, 'Kids')], undefined);
});
