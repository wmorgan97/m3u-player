import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normaliseEspn, fixtureTitle, isOn, foldName, teamVariants, titleHasTeams, followMatches, programmeIndex, channelsFor, matchChannels, sortFixtures } from '../js/fixtures.js';

// Trimmed from ESPN's scoreboard replies of 1 Nov 2026 (site.api.espn.com/apis/site/v2/sports/…/scoreboard).
const team = (id, displayName, shortDisplayName, name = displayName) => ({ id, displayName, shortDisplayName, name, logo: `https://a.espncdn.com/i/teamlogos/soccer/500/${id}.png` });
const status = (state, name, shortDetail) => ({ type: { state, name, shortDetail, completed: state === 'post' } });
const match = (id, date, home, away, st, extra = {}) => ({ id, date, name: `${away.displayName} at ${home.displayName}`, competitions: [{ id, date, status: st, venue: { fullName: 'Anfield' },
  competitors: [{ homeAway: 'home', score: '0', team: home }, { homeAway: 'away', score: '1', team: away }], ...extra }] });
const PL = { key: 'eng.1', sport: 'football', comp: 'Premier League' };
const LIV = team('364', 'Liverpool', 'Liverpool'), ARS = team('359', 'Arsenal', 'Arsenal');
const MUN = team('360', 'Manchester United', 'Man Utd'), TOT = team('367', 'Tottenham Hotspur', 'Spurs'), MCI = team('382', 'Manchester City', 'Man City');

test('normalise: a live league match', () => {
  const [f] = normaliseEspn(match('401879246', '2026-11-01T16:30Z', LIV, ARS, status('in', 'STATUS_SECOND_HALF', "81'")), PL);
  assert.equal(f.id, 'eng.1:401879246');
  assert.equal(f.status, 'live');
  assert.equal(f.minute, "81'");
  assert.equal(f.compRank, 100);
  assert.equal(f.start, Date.UTC(2026, 10, 1, 16, 30));
  assert.equal(f.end - f.start, 2 * 3600_000);
  assert.deepEqual([f.home.name, f.home.score, f.away.name, f.away.score], ['Liverpool', '0', 'Arsenal', '1']);
  assert.match(f.home.crest, /^https:\/\/a\.espncdn\.com\//);
  assert.equal(f.venue, 'Anfield');
  assert.equal(fixtureTitle(f), 'Liverpool v Arsenal');
});

test('normalise: scheduled has no score, cup round from the note, postponed, competition rank override', () => {
  const ev = match('1', '2026-11-06T19:30Z', team('1', 'Brighton & Hove Albion', 'Brighton'), team('2', 'Cambridge United', 'Cambridge'), status('pre', 'STATUS_SCHEDULED', '7:30 PM'), { altGameNote: 'English FA Cup, First Round' });
  const [f] = normaliseEspn(ev, { key: 'eng.fa', sport: 'football', comp: 'FA Cup' });
  assert.equal(f.status, 'scheduled');
  assert.equal(f.home.score, undefined);
  assert.equal(f.round, 'First Round');
  assert.equal(f.compRank, 88);
  const [p] = normaliseEspn(match('2', '2026-11-06T19:30Z', LIV, ARS, status('post', 'STATUS_POSTPONED', 'Postponed')), { key: 'x', sport: 'football', comp: 'Conference League', rank: 80 });
  assert.equal(p.status, 'postponed');
  assert.equal(p.compRank, 80);
  const [c] = normaliseEspn(match('3', '2026-11-03T19:45Z', LIV, ARS, status('pre', 'STATUS_SCHEDULED', '')), { key: 'eng.2', sport: 'football', comp: 'Championship', rankAs: 'EFL' });
  assert.equal(c.compRank, 72);
});

test('normalise: F1 sessions (sponsor dropped) and a UFC card, junk ignored', () => {
  const gp = { id: '600', name: 'MSC Cruises São Paulo Grand Prix', date: '2026-11-06T15:30Z', circuit: { fullName: 'Interlagos' }, competitions: [
    { id: 'a', type: { abbreviation: 'FP1' }, date: '2026-11-06T15:30Z', status: status('pre', 'STATUS_SCHEDULED') },
    { id: 'b', type: { abbreviation: 'Qual' }, date: '2026-11-07T18:00Z', status: status('pre', 'STATUS_SCHEDULED') },
    { id: 'c', type: { abbreviation: 'Race' }, date: '2026-11-08T17:00Z', status: status('in', 'STATUS_IN_PROGRESS', 'Lap 12') }] };
  const f1 = normaliseEspn(gp, { key: 'f1', sport: 'f1', comp: 'F1' });
  assert.deepEqual(f1.map(fixtureTitle), ['São Paulo Grand Prix · Practice 1', 'São Paulo Grand Prix · Qualifying', 'São Paulo Grand Prix · Race']);
  assert.equal(f1[2].status, 'live');
  assert.equal(f1[2].minute, 'Lap 12');
  const card = { id: '9', name: 'UFC 334: Gane vs. Hokit', date: '2026-11-14T22:00Z', status: status('pre', 'STATUS_SCHEDULED'), competitions: [
    { date: '2026-11-14T22:00Z', competitors: [] }, { date: '2026-11-15T02:00Z', competitors: [] }] };
  const [u] = normaliseEspn(card, { key: 'ufc', sport: 'ufc', comp: 'UFC' });
  assert.equal(u.title, 'UFC 334: Gane v Hokit');
  assert.equal(u.start, Date.UTC(2026, 10, 15, 2));      // main card
  assert.equal(u.prelims, Date.UTC(2026, 10, 14, 22));
  assert.deepEqual(normaliseEspn({}, PL), []);
  assert.deepEqual(normaliseEspn(null, PL), []);
});

test('isOn and order', () => {
  const now = Date.UTC(2026, 10, 1, 17);
  assert.ok(isOn({ status: 'live', start: now, end: now }, now));
  assert.ok(isOn({ status: 'scheduled', start: now - 60_000, end: now + 3600_000 }, now));   // feed a minute behind
  assert.ok(!isOn({ status: 'final', start: now - 60_000, end: now + 3600_000 }, now));
  const s = sortFixtures([{ start: 2, compRank: 1, title: 'b' }, { start: 1, compRank: 1, title: 'c' }, { start: 2, compRank: 9, title: 'a' }]);
  assert.deepEqual(s.map(f => f.title), ['c', 'a', 'b']);
});

test('team names: folding and aliases', () => {
  assert.equal(foldName('Brighton & Hove Albion'), 'brighton and hove albion');
  assert.equal(foldName("Nott'm Forest"), 'nottm forest');
  assert.equal(foldName('Man Utd'), 'man united');
  assert.equal(foldName('Paris Saint-Germain'), 'paris st germain');
  assert.equal(foldName('Atlético de Madrid ᴸᶦᵛᵉ'), 'atletico de madrid');
  const mun = teamVariants(MUN), tot = teamVariants(TOT);
  for (const v of ['manchester united', 'man united']) assert.ok(mun.includes(v), v);
  assert.ok(!mun.includes('manchester'), 'Manchester alone is ambiguous');
  for (const v of ['tottenham hotspur', 'tottenham', 'spurs']) assert.ok(tot.includes(v), v);
  assert.ok(teamVariants(team('1', 'Leicester City', 'Leicester')).includes('leicester'));
  assert.ok(teamVariants(team('1', 'VfB Stuttgart', 'Stuttgart')).includes('stuttgart'));
  assert.ok(teamVariants(team('1', 'Internazionale', 'Inter Milan')).includes('internazionale'));
  assert.ok(teamVariants({ name: 'Buffalo Bills', short: 'Bills', nick: 'Bills' }).includes('bills'));
});

test('titles naming both teams (real guide titles)', () => {
  const yes = [
    ['Live PL: Man Utd v Spurs', MUN, TOT], ['Premier League - PL: Manchester United v Tottenham Hotspur ᴸᶦᵛᵉ', MUN, TOT],
    ['Fútbol Premier League - Tottenham vs. Manchester United ᴸᶦᵛᵉ', MUN, TOT], ['Arsenal @ Liverpool ᴸᶦᵛᵉ', LIV, ARS],
    ['Bundesliga : Eintracht Frankfurt - HSV', team('1', 'Eintracht Frankfurt', 'Frankfurt'), team('2', 'Hamburg SV', 'Hamburg')],
    ['UEFA Champions League Soccer : Feyenoord vs. Internazionale', team('1', 'Feyenoord', 'Feyenoord'), team('2', 'Internazionale', 'Inter Milan')],
    ['Live UCL: Villarreal v Paris SG', team('1', 'Villarreal', 'Villarreal'), team('2', 'Paris Saint-Germain', 'PSG')],
    ['Live UCL: Man City v Man Utd', MCI, MUN],
  ];
  for (const [t, h, a] of yes) assert.ok(titleHasTeams(t, h, a), t);
  const no = [
    ['Live PL: Manchester City v Tottenham', MUN, TOT],       // the other Manchester
    ['Premier League: Arsenal v Chelsea', LIV, ARS],
    ['Liverpool Legends', LIV, ARS],
    ['Manchester United Review', MUN, TOT],
  ];
  for (const [t, h, a] of no) assert.ok(!titleHasTeams(t, h, a), t);
});

test('follows', () => {
  const f = { home: MUN, away: TOT, comp: 'Premier League' };
  for (const q of ['Man Utd', 'Manchester United', 'man united', 'Spurs', 'Tottenham']) assert.ok(followMatches(q, f), q);
  for (const q of ['Manchester', 'Chelsea', 'Man Utd Women', 'Spurs W']) assert.ok(!followMatches(q, f), q);
  assert.ok(followMatches('Formula 1', { sport: 'f1', comp: 'F1', title: 'Mexico City Grand Prix', session: 'Race' }));
  assert.ok(!followMatches('Arsenal', { sport: 'f1', comp: 'F1', title: 'Mexico City Grand Prix', session: 'Race' }));
});

test('channel matching: kick-off window, both teams, no replays / placeholders / women, live first', () => {
  const H = 3600_000, ko = Date.UTC(2026, 10, 1, 16, 30), now = ko - 2 * H;
  const fx = normaliseEspn(match('1', '2026-11-01T16:30Z', LIV, ARS, status('pre', 'STATUS_SCHEDULED', '')), PL)[0];
  const p = (id, name, start, end, title, desc = '') => ({ id, name, start, end, title, desc });
  const progs = [
    p('sky.me', 'Sky Sports Main Event', ko - 30 * 60_000, ko + 2 * H, 'Live PL: Liverpool v Arsenal'),
    p('sky.pl', 'Sky Sports Premier League', ko - 60 * 60_000, ko + 2.5 * H, 'Premier League - PL: Liverpool v Arsenal'),   // on air, not tagged live
    p('sky.pl', 'Sky Sports Premier League', ko + 5 * H, ko + 7 * H, 'Premier League - PL: Liverpool v Arsenal'),       // the repeat
    p('evt.1', 'EPL - Liverpool', ko, ko + 2 * H, 'Arsenal @ Liverpool ᴸᶦᵛᵉ'),
    p('ppv.1', 'PPV 1', ko - H, ko + 3 * H, 'Next | Liverpool v Arsenal | 16:30 (GMT)'),                              // placeholder
    p('ppv.2', 'PPV 2', ko, ko + 2 * H, 'End | Liverpool v Arsenal | 18:30 (GMT)'),
    p('sky.mix', 'Sky Sports Mix', ko, ko + 2 * H, 'Liverpool v Arsenal Highlights'),
    p('bbc', 'BBC One', ko, ko + 2 * H, "Women's Super League: Liverpool v Arsenal"),
    p('lfc', 'LFC TV', ko, ko + 2 * H, 'Liverpool v Arsenal 2004'),                                                      // old match
    p('tnt', 'TNT Sports 1', ko, ko + 2 * H, 'Live: Liverpool v Everton'),
    p('far', 'Sky Sports Football', ko + 3 * H, ko + 5 * H, 'Live: Liverpool v Arsenal'),                             // wrong time
  ];
  const byHour = programmeIndex(progs, now);
  const l = channelsFor(fx, byHour);
  assert.deepEqual(l.map(c => c.id), ['evt.1', 'sky.me', 'sky.pl']);
  assert.deepEqual(l.map(c => c.live), [true, true, false]);
  assert.deepEqual(Object.keys(matchChannels([fx, { ...fx, id: 'x', status: 'final' }], byHour)), [fx.id]);
});

test('channel matching: F1 needs the session and the race, UFC the card', () => {
  const H = 3600_000, t = Date.UTC(2026, 10, 1, 20);
  const race = { id: 'f1:c', sport: 'f1', comp: 'F1', title: 'Mexico City Grand Prix', session: 'Race', start: t, end: t + 2 * H, status: 'scheduled' };
  const quali = { ...race, id: 'f1:q', session: 'Qualifying' };
  const p = (id, title) => ({ id, name: id, start: t - 30 * 60_000, end: t + 2 * H, title });
  const byHour = programmeIndex([p('skyf1', 'Live Mexico City F1 Grand Prix'), p('f1tv', 'Mexico City Grand Prix Race ᴸᶦᵛᵉ'), p('euro', 'Eiskunstlauf - ISU Grand Prix: Skate Canada'),
    p('moto', 'Live | Petronas Grand Prix of Malaysia'), p('old', 'F1 Channel: 2026 United States Grand Prix'), p('ufc', 'UFC 334: Gane v Hokit')], t - 2 * H);
  assert.deepEqual(channelsFor(race, byHour).map(c => c.id).sort(), ['f1tv', 'skyf1']);
  assert.deepEqual(channelsFor(quali, byHour), []);
  const card = { id: 'ufc:9', sport: 'ufc', comp: 'UFC', title: 'UFC 334: Gane v Hokit', start: t + H, prelims: t - H, end: t + 4 * H, status: 'scheduled' };
  assert.deepEqual(channelsFor(card, byHour).map(c => c.id), ['ufc']);
});
