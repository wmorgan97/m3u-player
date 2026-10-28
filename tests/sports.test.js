import { test } from 'node:test';
import assert from 'node:assert/strict';
import { _selfTest, classify, cleanTitle, isNoise, channelLabel, groupByDay, otherLanguage } from '../js/sports.js';

test('sports self-test', () => assert.equal(_selfTest(), 'ok'));

test('competitions from real guide titles', () => {
  assert.equal(classify('PDC ANZ Premier League Darts : Week 5')?.comp, 'Darts');
  assert.equal(classify('Indian Premier League T20')?.comp, 'Cricket');
  assert.equal(classify('Korea Championship, LET Golf')?.comp, 'Golf');
  assert.equal(classify('EFL Championship - Bradford City vs. Peterborough United')?.comp, 'EFL');
  assert.equal(classify('Ratchaburi FC (THA) vs Kashiwa Reysol (JPN) - AFC Champions League Elite 2026/2027'), null);
  assert.equal(classify('Kick-boxing: WGP Kickboxing Brazil 22'), null);
});

test('tidy titles', () => {
  assert.equal(cleanTitle('Premier League - PL: Liverpool v Brighton'), 'Liverpool v Brighton');
  assert.equal(cleanTitle('PSG vs Barcelona - MD3 UEFA Champions League 2026/27'), 'PSG v Barcelona');
  assert.equal(cleanTitle('Formula 1 : USA Practice 1'), 'USA Practice 1');
  assert.equal(cleanTitle('Live from Newmarket'), 'Live from Newmarket');
});

test('noise: highlights, placeholders, old repeats', () => {
  const now = Date.UTC(2026, 9, 28);
  const p = t => [now, now + 3600_000, t];
  assert.ok(isNoise(p('UEFA Champions League Highlights Show'), {}, now));
  assert.ok(isNoise(p('### PREMIER LEAGUE ###'), {}, now));
  assert.ok(isNoise(p('Boxing 1 | SMITH vs PUELLO     UK 5:00pm'), { name: 'Boxing 1 | SMITH vs PUELLO     UK 5:00pm' }, now));
  assert.ok(isNoise(p('NEXT | MEXICO: RACE | Sun 01 Nov 13:50 EST (US)'), {}, now));
  assert.ok(isNoise(p('Boxing : 2004: Oscar De La Hoya vs. Bernard Hopkins'), {}, now));
  assert.ok(isNoise(p('Premier League Tv'), { name: 'Premier League TV' }, now));
  assert.ok(!isNoise(p('Live Premier League: Arsenal v Chelsea'), { name: 'Sky Sports PL' }, now));
});

test('labels, language, days', () => {
  assert.equal(channelLabel('UK: SKY SPORTS MAIN EVENT ᴿᴬᵂ'), 'Sky Sports Main Event');
  assert.equal(channelLabel('ITV1 HD'), 'ITV1 HD');
  assert.ok(otherLanguage({ name: 'FR: CANAL+ SPORT', group: 'Sports' }));
  assert.ok(!otherLanguage({ name: 'Sky Sports F1', group: 'UK | Sports' }));
  const now = new Date(2026, 9, 28, 12).getTime(), H = 3600_000;
  const days = groupByDay([{ prog: [now + H] }, { prog: [now + 20 * H] }, { prog: [now + 44 * H] }], now);
  assert.deepEqual(days.map(d => d.label).slice(0, 2), ['Today', 'Tomorrow']);
});
