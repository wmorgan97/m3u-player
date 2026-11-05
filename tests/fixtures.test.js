import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normaliseEspn, fixtureTitle, isOn, foldName, teamVariants, titleHasTeams, followMatches, programmeIndex, channelsFor, matchChannels, sortFixtures, clubIndex, clubChannelsFor, eventChannelsFor, nameDate, isEventGroup,
  teamChannelFor, teamChannelIndex, isTeamChannelGroup, fixtureForProgramme, dedupeFollows, followTeamKey } from '../js/fixtures.js';

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

test('new competitions: WSL is women\'s, golf is one item per round day', () => {
  const [w] = normaliseEspn(match('9', '2026-11-01T12:00Z', team('1', 'Chelsea', 'Chelsea'), team('2', 'Arsenal', 'Arsenal'), status('pre', 'STATUS_SCHEDULED', '')), { key: 'eng.w.1', sport: 'football', comp: 'WSL', rank: 60, women: true });
  assert.equal(w.women, true);
  assert.equal(w.compRank, 60);
  assert.ok(followMatches('Chelsea Women', w));
  assert.ok(!followMatches('Chelsea', w));
  const men = { ...w, women: undefined };
  assert.ok(followMatches('Chelsea', men));
  assert.ok(!followMatches('Chelsea Women', men));
  const g = normaliseEspn({ id: '5', name: 'Abu Dhabi Aldar Championship', date: '2026-11-05T05:00Z', endDate: '2026-11-08T05:00Z', status: status('pre', 'STATUS_SCHEDULED') }, { key: 'golf.eur', sport: 'golf', comp: 'DP World Tour', rank: 54 });
  assert.deepEqual(g.map(f => f.session), ['Round 1', 'Round 2', 'Round 3', 'Round 4']);
  assert.ok(g.every(f => f.allDay && f.end - f.start === 24 * 3600_000));
  for (const [slug, comp] of [['eng.3', 'League One'], ['eng.4', 'League Two'], ['eng.5', 'National League'], ['sco.2', 'Scottish Championship'], ['eng.trophy', 'EFL Trophy']]) {
    const [f] = normaliseEspn(match('1', '2026-11-03T19:45Z', team('1', 'Rochdale', 'Rochdale'), team('2', 'Doncaster Rovers', 'Doncaster'), status('pre', 'STATUS_SCHEDULED', '')), { key: slug, sport: 'football', comp });
    assert.equal(f.comp, comp);
  }
});

test('evidence: a pre-match show or another sport is never the source; club channels confirmed by their guide', () => {
  const H = 3600_000, ko = Date.UTC(2026, 10, 3, 19, 45), now = ko - 3 * H;
  const fx = { id: 'eng.2:1', sport: 'football', comp: 'Championship', start: ko, end: ko + 2 * H, status: 'scheduled', home: team('1', 'Blackburn Rovers', 'Blackburn'), away: team('2', 'Stoke City', 'Stoke') };
  const p = (id, name, start, end, title) => ({ id, name, start, end, title, desc: '' });
  const byHour = programmeIndex([
    p('ss', 'Sky Sports Football', ko - H, ko + 10 * 60_000, 'EFL Countdown: Blackburn v Stoke'),       // build-up show, over by 10 minutes in
    p('ss2', 'Sky Sports+', ko - 15 * 60_000, ko + 2 * H, 'Live EFL: Blackburn v Stoke'),
    p('nfl', 'Sky Sports NFL', ko, ko + 3 * H, 'NFL: Blackburn Rovers v Stoke City'),                 // made-up, but another sport
  ], now);
  assert.deepEqual(channelsFor(fx, byHour).map(c => [c.id, c.ev]), [['ss2', 'live']]);
  const chans = [{ id: 'blackburnrovers.efl', name: 'Blackburn Rovers' }, { id: 'stokecity.efl', name: 'UK: STOKE CITY TV' }, { id: 'bbc1', name: 'BBC One' }];
  const clubs = clubIndex(chans, [fx]);
  const progs = { 'blackburnrovers.efl': [[ko - 30 * 60_000, ko + 2 * H, 'vs Stoke City (H)', '']], 'stokecity.efl': [[ko - 30 * 60_000, ko + 2 * H, 'Stoke City Highlights', '']] };
  const l = clubChannelsFor(fx, clubs, id => ({ name: id, progs: progs[id] || [] }), now);
  assert.deepEqual(l.map(c => [c.id, c.ev]), [['blackburnrovers.efl', 'club']]);   // Stoke's slot is highlights: no evidence
  const all = matchChannels([fx], byHour, clubs, id => ({ name: id, progs: progs[id] || [] }), now)[fx.id];
  assert.deepEqual(all.map(c => c.ev), ['live', 'club']);
});

test('event / league-pass channels: matchup in the name, its date must agree, placeholders never match', () => {
  const ko = Date.UTC(2026, 10, 1, 23, 30);
  const f = { id: 'nba:401909861', sport: 'nba', comp: 'NBA', start: ko, end: ko + 3 * 3600_000, status: 'scheduled', home: { name: 'Utah Jazz', short: 'Jazz', nick: 'Jazz' }, away: { name: 'Los Angeles Lakers', short: 'Lakers', nick: 'Lakers' } };
  const c = (name, group, tvgId = '') => ({ name, group, tvgId, url: name });
  const chans = [c('Lakers @ Jazz', '🏀 NBA League Pass', 'teamarr-event-401909861'), c('NBA 05: Lakers vs Jazz start:2026-11-01 23:30:00', '🏀 NBA League Pass'),
    c('NBA 06: Lakers vs Jazz start:2026-10-20 23:30:00', '🏀 NBA League Pass'), c('NBA 07: No Game Today', '🏀 NBA League Pass'), c('Boston Celtics', '🏀 NBA League Pass')];
  assert.deepEqual(eventChannelsFor(f, chans).map(x => [x.channel.name, x.how]), [['Lakers @ Jazz', 'id'], ['NBA 05: Lakers vs Jazz start:2026-11-01 23:30:00', 'name']]);
  const ufc = { id: 'ufc:9', sport: 'ufc', comp: 'UFC', title: 'UFC 333: Volkanovski v Evloev', start: Date.UTC(2026, 9, 24, 18), end: 0, status: 'scheduled' };
  const ppv = [c('PPV EVENT 03: PRELIMS UFC 333 (10.24 12:00 PM ET)', '🎟️ PPV Event'), c('PPV EVENT 05: UFC 333 Volkanovski vs. Evloev (10.24 2:00 PM ET)', '🎟️ PPV Event'), c('PPV EVENT 06: UFC 332 Smith vs. Jones (09.12 2:00 PM ET)', '🎟️ PPV Event')];
  assert.deepEqual(eventChannelsFor(ufc, ppv).map(x => x.channel.name.slice(0, 13)), ['PPV EVENT 05:', 'PPV EVENT 03:']);   // main card first
  assert.deepEqual(nameDate('National League 4 | Barrow vs Eastleigh // UK Sat 31 Oct 3:00pm'), [9, 31]);
  assert.deepEqual(nameDate('Harvest Hustle @ Oct 23 7:00 PM :Flo Racing 03'), [9, 23]);
  assert.equal(nameDate('Lakers @ Jazz'), null);
  assert.ok(isEventGroup('🏀 NBA League Pass') && isEventGroup('🎟️ PPV Event') && isEventGroup('UK| CHAMPIONSHIP PPV') && !isEventGroup('UK | Sports'));
});

test('real-data regressions (1-3 Nov 2026 guide)', () => {
  // "Birmingham" is one club; MLS "New England v Miami" is not the NFL's Patriots at Dolphins.
  const H = 3600_000, ko = Date.UTC(2026, 10, 3, 20);
  const p = (id, title, start = ko - 15 * 60_000) => ({ id, name: id, start, end: start + 2.5 * H, title, desc: '' });
  const byHour = programmeIndex([p('ssplus', 'Live EFL: Birmingham v Millwall'), p('wbz', 'NFL Football - New England Patriots at Miami Dolphins ᴸᶦᵛᵉ')], ko - H);
  const bir = { id: 'b', sport: 'football', start: ko, end: ko + 2 * H, status: 'scheduled', home: team('1', 'Birmingham City', 'Birmingham'), away: team('2', 'Millwall', 'Millwall') };
  const mls = { id: 'm', sport: 'football', start: ko, end: ko + 2 * H, status: 'scheduled', home: team('3', 'New England Revolution', 'New England'), away: team('4', 'Inter Miami CF', 'Miami') };
  assert.deepEqual(channelsFor(bir, byHour).map(c => c.id), ['ssplus']);
  assert.deepEqual(channelsFor(mls, byHour), []);
});

// ---------- F1, tennis, UFC: real replies recorded once (tests/espn-samples, trimmed; ESPN site API, personal use) ----------
import { readFileSync } from 'node:fs';
import { athletesOf, namesPerson } from '../js/fixtures.js';
import { fillDrivers } from '../server/fixtures.js';
const rec = name => JSON.parse(readFileSync(new URL(`./espn-samples/${name}.json`, import.meta.url), 'utf8')).events;
const all = (name, src) => rec(name).flatMap(ev => normaliseEspn(ev, src));
const F1 = { key: 'f1', sport: 'f1', comp: 'F1' }, ATP = { key: 'tennis.atp', sport: 'tennis', comp: 'ATP' }, UFC = { key: 'ufc', sport: 'ufc', comp: 'UFC' };

test('recorded F1: a session each, circuit + country, the race result top 3, drivers carried to the next weekend', () => {
  const l = all('f1', F1);
  assert.deepEqual(l.map(fixtureTitle), ['Mexico City Grand Prix · Practice 1', 'Mexico City Grand Prix · Qualifying', 'Mexico City Grand Prix · Race',
    'São Paulo Grand Prix · Practice 1', 'São Paulo Grand Prix · Qualifying', 'São Paulo Grand Prix · Race']);
  const race = l[2], sp = l[5];
  assert.equal(race.country, 'Mexico');
  assert.equal(sp.venue, 'Autodromo Jose Carlos Pace');
  assert.equal(sp.start, Date.UTC(2026, 10, 8, 17));
  assert.deepEqual(race.order.map(d => [d.pos, d.short, d.flag]), [[1, 'M. Verstappen', 'NED'], [2, 'L. Hamilton', 'GBR'], [3, 'K. Antonelli', 'ITA']]);
  assert.equal(l[1].order, undefined);   // qualifying: no running order shown
  assert.equal(sp.drivers, undefined);
  fillDrivers(l);
  assert.ok(sp.drivers.includes('Lewis Hamilton'));
  // follow a driver (full name or surname), a team, or F1 itself; not someone else
  for (const q of ['Lewis Hamilton', 'Hamilton', 'verstappen', 'McLaren', 'Ferrari', 'F1', 'Formula 1']) assert.ok(followMatches(q, sp), q);
  for (const q of ['Arsenal', 'Lewis', 'Alcaraz', 'UFC']) assert.ok(!followMatches(q, sp), q);
});

test('F1 live: leader gap from the feed when it has one', () => {
  const gp = { id: '1', name: 'Heineken Las Vegas Grand Prix', competitions: [{ id: 'r', type: { abbreviation: 'Race' }, date: '2026-11-22T04:00Z', status: status('in', 'STATUS_IN_PROGRESS', 'Lap 31/50'),
    competitors: [{ order: 2, athlete: { displayName: 'Lando Norris', shortName: 'L. Norris' }, statistics: [{ name: 'behindTime', displayValue: '1.204' }] },
      { order: 1, athlete: { displayName: 'Max Verstappen', shortName: 'M. Verstappen' } }, { order: 3, athlete: { displayName: 'Oscar Piastri', shortName: 'O. Piastri' }, statistics: [{ name: 'behindTime', displayValue: '+1 Lap' }] },
      { order: 4, athlete: { displayName: 'George Russell' } }] }] };
  const [f] = normaliseEspn(gp, F1);
  assert.equal(f.title, 'Las Vegas Grand Prix');
  assert.equal(f.minute, 'Lap 31/50');
  assert.deepEqual(f.order.map(d => [d.short, d.gap]), [['M. Verstappen', undefined], ['L. Norris', '+1.204'], ['O. Piastri', '+1 Lap']]);
  assert.ok(isOn(f, Date.UTC(2026, 10, 22, 5)));
});

test('recorded tennis: an order of play per tournament day, singles only, seeds, flags, sets', () => {
  const [done, today] = all('tennis-atp', ATP);
  assert.equal(done.id, 'tennis.atp:13-2026:20261104');
  assert.equal(done.title, 'Paris Masters');   // "Rolex" dropped
  assert.equal(done.compRank, 74);
  assert.equal(done.status, 'final');
  const m = done.matches[1];
  assert.deepEqual([m.a.name, m.a.sets, m.a.win, m.b.name, m.b.seed, m.b.sets, m.court], ['Quentin Halys', ['6', '1', '7'], true, 'Frances Tiafoe', 7, ['3', '6', '6'], 'Court Central']);
  assert.deepEqual(m.a.tb, [null, null, 11]);
  assert.equal(today.status, 'scheduled');
  assert.equal(today.session, 'Round 3');
  assert.equal(today.matches.length, 4);
  assert.ok(today.matches.every(x => x.a.sets === undefined));
  assert.equal(today.matches[1].a.seed, 3);
  assert.equal(fixtureTitle(today), 'Paris Masters · Round 3');
  // not "on" just because a "not before" time has passed
  assert.ok(!isOn(today, today.start + 3600_000));
  for (const q of ['Ben Shelton', 'Shelton', 'Musetti', 'Tennis']) assert.ok(followMatches(q, today), q);
  for (const q of ['Paul Smith', 'Sinner', 'F1']) assert.ok(!followMatches(q, today), q);
  assert.ok(athletesOf(today).includes('Tomas Martin Etcheverry'));
  assert.ok(namesPerson('Etcheverry', 'Tomas Martin Etcheverry') && namesPerson('martin etcheverry', 'Tomas Martin Etcheverry') && !namesPerson('Tom', 'Tom Paul'));
});

test('tennis live: the day is live while a match is on, with the set score', () => {
  const ev = rec('tennis-atp')[0];
  const c = ev.groupings.find(g => /singles/.test(g.grouping.slug)).competitions.find(x => x.date.startsWith('2026-11-05'));
  c.status = status('in', 'STATUS_IN_PROGRESS', '2nd Set');
  const [first, second] = [...c.competitors].sort((x, y) => x.order - y.order);
  first.linescores = [{ value: 6 }, { value: 3 }]; second.linescores = [{ value: 4 }, { value: 2 }];
  const today = normaliseEspn(ev, ATP)[1];
  assert.equal(today.status, 'live');
  assert.equal(today.minute, '1 on court');
  const m = today.matches.find(x => x.status === 'live');
  assert.equal(m.detail, '2nd Set');
  assert.equal(m.a.sets.join(' ') + ' / ' + m.b.sets.join(' '), '6 3 / 4 2');
  assert.ok(isOn(today, Date.now()));
});

test('recorded UFC: the card, main event first and highlighted, main card / prelims, records', () => {
  const [u] = all('ufc', UFC);
  assert.equal(u.title, 'UFC Fight Night: Cortes Acosta v Walker');
  assert.equal(u.start, Date.UTC(2026, 10, 8, 1));
  assert.equal(u.prelims, Date.UTC(2026, 10, 7, 22));
  assert.equal(u.fights.length, 5);
  const [main] = u.fights;
  assert.ok(main.main);
  assert.deepEqual([main.a.name, main.b.name, main.weight, main.card, main.a.record], ['Waldo Cortes Acosta', 'Johnny Walker', 'Heavyweight', 'Main card', '17-4-0']);
  assert.deepEqual(u.fights.map(b => b.card), ['Main card', 'Main card', 'Main card', 'Prelims', 'Prelims']);
  assert.equal(u.fights.filter(b => b.main).length, 1);
  for (const q of ['UFC', 'Johnny Walker', 'Jandiroba']) assert.ok(followMatches(q, u), q);
  assert.ok(!followMatches('Arsenal', u));
});

test('tennis channels: live coverage naming the place, not the other tour, not a replay', () => {
  const [, today] = all('tennis-atp', ATP);
  const t = today.start, H = 3600_000;
  const p = (id, title, s = t - H, e = t + 6 * H) => ({ id, name: id, start: s, end: e, title });
  const byHour = programmeIndex([p('skyt', 'Live ATP Masters 1000 Tennis: Paris'), p('skyt2', 'ATP Tennis: Paris Masters Highlights'), p('wta', 'Live WTA Tennis: Hong Kong Open'),
    p('old', 'Live ATP Tennis: Shanghai Masters'), p('tomorrow', 'Live ATP Masters 1000 Tennis: Paris', t + 24 * H, t + 30 * H)], t - 2 * H);
  assert.deepEqual(channelsFor(today, byHour).map(c => c.id), ['skyt']);
});

test('request budget: tennis is one request per tour, whatever the window', async () => {
  const { requestsFor, SOURCES } = await import('../server/fixtures.js');
  const atp = SOURCES.find(s => s.key === 'tennis.atp');
  const now = Date.UTC(2026, 10, 5);
  assert.deepEqual(requestsFor(atp, now, now + 7 * 86400_000).map(u => u.replace(/^.*\/sports\//, '')), ['tennis/atp/scoreboard']);
  assert.ok(atp.liveEvery >= 5 * 60_000);
  assert.ok(SOURCES.some(s => s.key === 'tennis.wta') && SOURCES.some(s => s.key === 'f1') && SOURCES.some(s => s.key === 'ufc'));
});

// ---------- sports polish: men's first team only, Premier League club channels, one card per game, one tile per team ----------
const side = (name, extra = {}) => ({ name, short: name, crest: '', ...extra });
test('following "Chelsea" is the men\'s first team: never Chelsea Women or Chelsea U21', () => {
  const t = Date.UTC(2026, 10, 8, 15);
  const men = { id: 'eng.1:1', sport: 'football', comp: 'Premier League', start: t, end: t + 7200_000, status: 'scheduled', home: side('Chelsea'), away: side('Arsenal') };
  const wsl = { ...men, id: 'eng.w.1:1', comp: 'WSL', women: true };
  const named = { ...men, id: 'x:2', home: side('Chelsea Women'), away: side('Arsenal Women') };
  const youth = { ...men, id: 'eng.trophy:1', comp: 'EFL Trophy', home: side('Chelsea U21'), away: side('Exeter City') };
  const academy = { ...men, id: 'x:3', home: side('Chelsea Academy'), away: side('Fulham Academy') };
  assert.ok(followMatches('Chelsea', men));
  for (const f of [wsl, named, youth, academy]) assert.ok(!followMatches('Chelsea', f), f.id);
  assert.ok(followMatches('Chelsea Women', wsl));
  assert.ok(followMatches('Chelsea U21', youth));
  assert.ok(!followMatches('Chelsea U21', men));
  assert.ok(followMatches('Exeter City', youth));   // the senior side in the same game still counts
  // F1, tennis and UFC follows never pick up a club, and a club follow never picks up a person
  const f1 = { id: 'f1:1', sport: 'f1', comp: 'F1', title: 'Las Vegas Grand Prix', session: 'Race', start: t, end: t + 7200_000, status: 'scheduled', drivers: ['Lewis Hamilton'] };
  const ten = { id: 'tennis.wta:1', sport: 'tennis', comp: 'WTA', title: 'WTA Finals', start: t, end: t, status: 'scheduled', matches: [{ a: { name: 'Chelsea Smith' }, b: { name: 'Coco Gauff' } }] };
  const ufc = { id: 'ufc:1', sport: 'ufc', comp: 'UFC', title: 'UFC 334', start: t, end: t, status: 'scheduled', fights: [{ a: { name: 'Jon Jones' }, b: { name: 'Tom Aspinall' } }] };
  for (const f of [f1, ten, ufc]) assert.ok(!followMatches('Chelsea', f), f.id);
  for (const q of ['F1', 'Tennis', 'UFC', 'Hamilton', 'Gauff', 'Aspinall']) assert.ok(!followMatches(q, men), q);
});

test('team channels: Premier League club groups, never a women\'s or U21 channel for a men\'s game; indexed once', () => {
  for (const g of ['UK| PREMIER LEAGUE TEAMS', 'EPL TEAMS', 'UK| PREMIER LEAGUE CLUBS', 'UK| CHAMPIONSHIP PPV', 'UK| SERIE A TEAM PPV']) assert.ok(isTeamChannelGroup(g), g);
  for (const g of ['UK| SPORTS', 'UK| ENTERTAINMENT', 'US| NBA']) assert.ok(!isTeamChannelGroup(g), g);
  const t = Date.UTC(2026, 10, 8, 15);
  const chans = ['UK: Chelsea Women HD', 'UK: Chelsea U21 HD', 'UK: Arsenal Women HD', 'Chelsea Women v Arsenal Women', 'UK: Everton HD', 'Fulham FC', 'UK: Chelsea TV HD']
    .map((name, i) => ({ name, url: `u${i}`, group: 'UK| PREMIER LEAGUE TEAMS' }));
  const ix = teamChannelIndex(chans);
  assert.equal(teamChannelIndex(chans), ix);   // cached per list
  const fx = (h, a, extra = {}) => ({ id: 'x', sport: 'football', start: t, end: t + 7200_000, status: 'scheduled', home: side(h), away: side(a), ...extra });
  assert.equal(teamChannelFor(fx('Chelsea', 'Arsenal'), ix)?.channel.name, 'UK: Chelsea TV HD');
  assert.equal(teamChannelFor(fx('Chelsea', 'Arsenal'), chans.filter(c => !/Chelsea TV/.test(c.name))), null);   // only the women's / U21 ones: none
  assert.equal(teamChannelFor(fx('Chelsea', 'Arsenal', { women: true }), ix)?.channel.name, 'UK: Chelsea Women HD');
  assert.equal(teamChannelFor(fx('Everton', 'Spurs'), ix)?.channel.name, 'UK: Everton HD');
  assert.equal(teamChannelFor(fx('Wolves', 'Fulham'), ix)?.side, 'away');
});

test('one card per game: a guide listing of a fixture is the fixture', () => {
  const t = Date.UTC(2026, 10, 8, 2);
  const nhl = { id: 'nhl:1', sport: 'nhl', comp: 'NHL', start: t, end: t + 3 * 3600_000, status: 'scheduled', home: side('Seattle Kraken', { nick: 'Kraken' }), away: side('Calgary Flames', { nick: 'Flames' }) };
  const pl = { id: 'eng.1:1', sport: 'football', comp: 'Premier League', start: t, end: t + 7200_000, status: 'scheduled', home: side('Manchester United'), away: side('Tottenham Hotspur') };
  assert.equal(fixtureForProgramme('Live NHL: Seattle v Calgary', t + 5 * 60_000, [pl, nhl])?.id, 'nhl:1');
  assert.equal(fixtureForProgramme('Seattle Kraken @ Calgary Flames', t, [nhl])?.id, 'nhl:1');
  assert.equal(fixtureForProgramme('Man Utd v Spurs', t - 30 * 60_000, [pl])?.id, 'eng.1:1');
  assert.equal(fixtureForProgramme('Seattle v Calgary', t + 5 * 3600_000, [nhl]), null);         // another day's game
  assert.equal(fixtureForProgramme('NBA: Seattle v Calgary', t, [nhl]), null);                    // another sport
  assert.equal(fixtureForProgramme("Women's Super League: Man Utd v Spurs", t, [pl]), null);      // the women's game
});

test('one tile per followed team: Lakers and Los Angeles Lakers', () => {
  const t = Date.UTC(2026, 10, 8, 2);
  const nba = { id: 'nba:1', sport: 'nba', comp: 'NBA', start: t, end: t + 3 * 3600_000, status: 'scheduled', home: side('Los Angeles Lakers', { nick: 'Lakers' }), away: side('Boston Celtics', { nick: 'Celtics' }) };
  const pl = { id: 'eng.1:1', sport: 'football', comp: 'Premier League', start: t, end: t + 7200_000, status: 'scheduled', home: side('Manchester United'), away: side('Chelsea') };
  const follows = ['Lakers', 'Los Angeles Lakers', 'Man Utd', 'Manchester United', 'Chelsea', 'Chelsea Women', 'Celtics'].map(q => ({ q }));
  assert.deepEqual(dedupeFollows(follows, [nba, pl]).map(f => f.q), ['Lakers', 'Man Utd', 'Chelsea', 'Chelsea Women', 'Celtics']);
  assert.equal(followTeamKey('Spurs', []), followTeamKey('Tottenham Hotspur', []));   // no fixtures: by alias
});
