// Real fixtures: what is actually being played (from a fixtures feed, see server/fixtures.js), and which of your channels
// shows it (from the TV guide). Pure, so the server, the app and the tests share it.
//
// A fixture: { id, sport, comp, compRank, start, end, status: 'scheduled'|'live'|'final'|'postponed', minute?,
//   home?: { name, short, crest, score? }, away?: {…}, title?, session?, venue?, round? }
// (team sports have home/away; F1 sessions and UFC cards have a title instead).

import { IMPORTANCE, isLiveEvent, isNotGame, isWomens, classify } from './sports.js';

// ---------- the feed (ESPN's public site API) ----------

const HOUR = 3600_000;
// How long each sport's events run, for "is it still on" and channel matching (the feed only gives the start).
const LENGTH = { football: 2 * HOUR, f1: 2 * HOUR, tennis: 3 * HOUR, nfl: 3.5 * HOUR, nba: 2.5 * HOUR, ufc: 5 * HOUR, rugby: 2 * HOUR };
const SESSION = { FP1: 'Practice 1', FP2: 'Practice 2', FP3: 'Practice 3', Qual: 'Qualifying', Race: 'Race', Sprint: 'Sprint', SS: 'Sprint Qualifying', SQ: 'Sprint Qualifying', SR: 'Sprint' };
const SESSION_LEN = { Race: 2 * HOUR, Sprint: HOUR, 'Sprint Qualifying': HOUR, Qualifying: 1.25 * HOUR };
// "MSC Cruises São Paulo Grand Prix" -> "São Paulo Grand Prix": title sponsors in front of the race name.
const SPONSOR = /^(?:msc cruises|heineken|qatar airways|etihad airways|aramco|pirelli|lenovo|crypto\.com|aws|louis vuitton|rolex|singapore airlines|gulf air|stc|tag heuer|moët & chandon|santander|emirates|liqui moly|airbnb|formula 1)\s+/i;

const statusOf = st => {
  const t = st?.type || {};
  if (/POSTPONED|CANCELED|CANCELLED|ABANDONED|SUSPENDED|FORFEIT/i.test(t.name || '')) return 'postponed';
  return t.state === 'in' ? 'live' : t.state === 'post' ? 'final' : 'scheduled';
};
const team = c => {
  const t = c?.team || {};
  const out = { name: t.displayName || t.name || '', short: t.shortDisplayName || t.displayName || '', crest: t.logo || '' };
  if (t.name && t.name !== out.name) out.nick = t.name;   // NFL/NBA: "Bills" for "Buffalo Bills"
  if (c?.score != null && c.score !== '') out.score = String(c.score?.displayValue ?? c.score);
  return out;
};

/**
 * One ESPN scoreboard event -> fixtures (F1: one per session). src: { key, sport, comp, rank?, rankAs? }.
 * Returns [] for anything it can't read, never throws.
 */
export function normaliseEspn(ev, src) {
  try {
    const compRank = src.rank ?? IMPORTANCE[src.rankAs || src.comp] ?? 0;
    const base = { sport: src.sport, comp: src.comp, compRank, ...(src.women ? { women: true } : {}) };
    const comps = ev.competitions || [];
    if (src.sport === 'f1') {
      const gp = String(ev.name || ev.shortName || '').replace(SPONSOR, '').replace(SPONSOR, '').trim();
      const country = String(ev.circuit?.address?.country || '').trim();
      // Everyone who took part in any session (for "follow a driver"; the server fills it in for weekends not started).
      const drivers = [...new Set(comps.flatMap(c => (c.competitors || []).map(x => x.athlete?.displayName || x.athlete?.fullName).filter(Boolean)))];
      return comps.map(c => {
        const session = SESSION[c.type?.abbreviation] || c.type?.abbreviation || 'Session';
        const start = Date.parse(c.date || c.startDate);
        const st = statusOf(c.status);
        const order = /^(?:Race|Sprint)$/.test(session) && st !== 'scheduled' ? f1Order(c) : [];
        return { id: `${src.key}:${c.id}`, ...base, start, end: start + (SESSION_LEN[session] || HOUR), status: st,
          ...(st === 'live' && c.status?.type?.shortDetail ? { minute: c.status.type.shortDetail } : {}),
          title: gp, session, venue: ev.circuit?.fullName || '', ...(country ? { country } : {}), ...(order.length ? { order } : {}), ...(drivers.length ? { drivers } : {}) };
      }).filter(f => f.start);
    }
    if (src.sport === 'tennis') return tennisDays(ev, src, base);
    if (src.sport === 'golf') {
      // A tournament: one item per round day (the feed gives the days, not tee times; the guide gives the coverage).
      const from = Date.parse(ev.date), to = Date.parse(ev.endDate) || from;
      if (!from) return [];
      const st = statusOf(ev.status), out = [];
      for (let t = from, r = 1; t <= to && r <= 4; t += 24 * HOUR, r++) {
        const now = Date.now(), status = st === 'final' ? 'final' : t + 24 * HOUR <= now ? 'final' : st === 'live' && t <= now ? 'live' : 'scheduled';
        out.push({ id: `${src.key}:${ev.id}:${r}`, ...base, start: t, end: t + 24 * HOUR, allDay: true, status, title: String(ev.name || ''), session: `Round ${r}` });
      }
      return out;
    }
    if (src.sport === 'ufc') {
      // A fight card: the event's date is the prelims, the last bouts' time the main card.
      const times = comps.map(c => Date.parse(c.date)).filter(Boolean);
      const prelims = Date.parse(ev.date) || Math.min(...times), start = times.length ? Math.max(...times) : prelims;
      if (!start) return [];
      const st = statusOf(ev.status || comps[0]?.status);
      const fights = ufcCard(comps, start), on = fights.find(b => b.status === 'live');
      return [{ id: `${src.key}:${ev.id}`, ...base, start, prelims, end: start + 3 * HOUR, status: st,
        ...(st === 'live' && on ? { minute: `${on.a.short || on.a.name} v ${on.b.short || on.b.name}${on.detail ? ` · ${on.detail}` : ''}` } : {}),
        title: String(ev.name || '').replace(/\s+vs\.?\s+/i, ' v '), venue: comps[0]?.venue?.fullName || '', ...(fights.length ? { fights } : {}) }];
    }
    const c = comps[0];
    if (!c) return [];
    const home = c.competitors?.find(x => x.homeAway === 'home') || c.competitors?.[0];
    const away = c.competitors?.find(x => x.homeAway === 'away') || c.competitors?.[1];
    if (!home || !away) return [];
    const start = Date.parse(c.date || ev.date);
    if (!start) return [];
    const st = statusOf(c.status || ev.status);
    const h = team(home), a = team(away);
    if (st === 'scheduled') { delete h.score; delete a.score; }
    const round = String(c.altGameNote || '').split(',').slice(1).join(',').trim();
    return [{ id: `${src.key}:${ev.id}`, ...base, start, end: start + (LENGTH[src.sport] || 2 * HOUR), status: st,
      ...(st === 'live' && c.status?.type?.shortDetail ? { minute: c.status.type.shortDetail } : {}),
      home: h, away: a, venue: c.venue?.fullName || '', ...(round ? { round } : {}) }];
  } catch { return []; }
}

// ---------- F1, tennis and UFC: the people in them ----------

const flagOf = a => { const m = /countries\/500\/([a-z]{3})\.png/i.exec(a?.flag?.href || ''); return m ? m[1].toUpperCase() : ''; };
const person = x => {
  const a = x?.athlete || {};
  const out = { name: a.displayName || a.fullName || '', short: a.shortName || '' };
  const fl = flagOf(a);
  if (fl) out.flag = fl;
  return out;
};
const statValue = (x, names) => { for (const s of x?.statistics || []) if (names.includes(s.name) || names.includes(s.abbreviation)) return String(s.displayValue ?? s.value ?? ''); return ''; };
/** A race's running order (Race / Sprint, on or done): the top 3, the gap to the leader when the feed has one. */
function f1Order(c) {
  return (c.competitors || []).filter(x => x.athlete).sort((a, b) => (a.order ?? 99) - (b.order ?? 99)).slice(0, 3).map((x, i) => {
    const p = person(x), gap = i ? statValue(x, ['behindTime', 'gap', 'timeBehind', 'behind', 'GAP']) : '';
    return { ...p, pos: x.order ?? i + 1, ...(gap && gap !== '0' ? { gap: /^[+]/.test(gap) || /lap/i.test(gap) ? gap : `+${gap}` } : {}) };
  });
}
/** 2026 teams, for following a team ("McLaren") rather than a driver. Drivers not listed simply follow by name. */
export const F1_TEAMS = { 'red bull': ['Max Verstappen', 'Isack Hadjar'], ferrari: ['Charles Leclerc', 'Lewis Hamilton'], mercedes: ['George Russell', 'Kimi Antonelli'],
  mclaren: ['Lando Norris', 'Oscar Piastri'], 'aston martin': ['Fernando Alonso', 'Lance Stroll'], alpine: ['Pierre Gasly', 'Franco Colapinto'], williams: ['Alexander Albon', 'Carlos Sainz'],
  'racing bulls': ['Liam Lawson', 'Arvid Lindblad'], haas: ['Esteban Ocon', 'Oliver Bearman'], audi: ['Nico Hülkenberg', 'Gabriel Bortoleto'], sauber: ['Nico Hülkenberg', 'Gabriel Bortoleto'],
  cadillac: ['Sergio Pérez', 'Valtteri Bottas'] };

// "Rolex Paris Masters" -> "Paris Masters": title sponsors in front of the tournament.
const T_SPONSOR = /^(?:rolex|prudential|mutua|bnp paribas|reto win|lexus|nitto|cinch|hsbc|terra wortmann|libema|boss|mubadala|adidas|internazionali bnl d'italia)\s+/i;
const T_BIG = /masters|indian wells|miami open|mutua madrid|madrid open|internazionali|italian open|canadian open|national bank|cincinnati|shanghai|wuhan|china open|dubai|qatar|doha/i;
const tennisRank = ev => ev.major ? 90 : /\bfinals?\b/i.test(ev.name || '') && !/next ?gen/i.test(ev.name || '') ? 82 : T_BIG.test(ev.name || '') ? 74 : IMPORTANCE.Tennis ?? 52;
const ukDay = t => new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London', year: 'numeric', month: '2-digit', day: '2-digit' }).format(t).replace(/-/g, '');
const ROUND = { 'Quarterfinal': 'Quarter-final', 'Semifinal': 'Semi-final', 'Final': 'Final' };
/** One tennis match (singles): both players, round, court, sets so far. */
function tennisMatch(c) {
  const st = statusOf(c.status), side = x => {
    const p = person(x), seed = x.curatedRank?.current;
    if (seed && seed < 99) p.seed = seed;
    const sets = (x.linescores || []).map(l => l.value).filter(v => v != null).map(v => String(Math.round(v)));
    if (st !== 'scheduled' && sets.length) { p.sets = sets; const tb = (x.linescores || []).map(l => l.tiebreak ?? null); if (tb.some(v => v != null)) p.tb = tb; }
    if (x.winner && st === 'final') p.win = true;
    return p;
  };
  const cs = [...(c.competitors || [])].sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
  if (cs.length < 2 || !cs[0].athlete || !cs[1].athlete) return null;
  const start = Date.parse(c.date || c.startDate);
  const r = String(c.round?.displayName || '');
  return { id: String(c.id), start, status: st, round: ROUND[r] || r, ...(c.venue?.court ? { court: c.venue.court } : {}),
    ...(st === 'live' && c.status?.type?.shortDetail ? { detail: c.status.type.shortDetail } : {}), a: side(cs[0]), b: side(cs[1]) };
}
/** A tournament -> one item per UK day: its order of play (singles), live while any match is on. */
function tennisDays(ev, src, base) {
  const title = String(ev.name || ev.shortName || '').replace(T_SPONSOR, '').trim();
  const singles = (ev.groupings || []).filter(g => /singles/i.test(g.grouping?.slug || g.grouping?.displayName || '')).flatMap(g => g.competitions || []);
  const byDay = new Map();
  for (const c of singles) {
    const m = tennisMatch(c);
    if (!m || !m.start) continue;
    const d = ukDay(m.start);
    if (!byDay.has(d)) byDay.set(d, []);
    byDay.get(d).push(m);
  }
  const compRank = src.rank ?? tennisRank(ev);
  return [...byDay].map(([d, matches]) => {
    matches.sort((a, b) => a.start - b.start || String(a.court || '').localeCompare(String(b.court || '')));
    const live = matches.some(m => m.status === 'live'), left = matches.filter(m => m.status === 'scheduled');
    const status = live ? 'live' : left.length ? 'scheduled' : 'final';
    // While nothing is on, the next match's time (play resumes); the day ends three hours after its last start.
    const start = live ? matches[0].start : (left[0] || matches[0]).start;
    const rounds = [...new Set(matches.map(m => m.round).filter(Boolean))];
    return { id: `${src.key}:${ev.id}:${d}`, ...base, compRank, start, from: matches[0].start, end: matches[matches.length - 1].start + 3 * HOUR, status,
      ...(live ? { minute: `${matches.filter(m => m.status === 'live').length} on court` } : {}),
      title, session: rounds.length > 2 ? `${rounds[0]} – ${rounds[rounds.length - 1]}` : rounds.join(' · '), venue: String(ev.venue?.displayName || ''), matches };
  });
}
/** A UFC card, main event first: [{ a, b, weight, card: 'Main card' | 'Prelims' | 'Early prelims', main?, status, detail?, win? }]. */
function ufcCard(comps, mainAt) {
  const times = [...new Set(comps.map(c => Date.parse(c.date)).filter(Boolean))].sort((a, b) => b - a);
  const card = t => t >= mainAt ? 'Main card' : t === times[1] || times.length < 3 ? 'Prelims' : 'Early prelims';
  return comps.map((c, i) => ({ c, i })).sort((x, y) => (Date.parse(y.c.date) || 0) - (Date.parse(x.c.date) || 0) || y.i - x.i).map(({ c }, k) => {
    const cs = [...(c.competitors || [])].sort((x, y) => (x.order ?? 0) - (y.order ?? 0));   // as the card's name has them
    if (cs.length < 2) return null;
    const side = x => { const p = person(x); const rec = x.records?.[0]?.summary; if (rec) p.record = rec; if (x.winner) p.win = true; return p; };
    const st = statusOf(c.status);
    return { a: side(cs[0]), b: side(cs[1]), weight: String(c.type?.abbreviation || ''), card: card(Date.parse(c.date)), ...(k === 0 ? { main: true } : {}), status: st,
      ...(st !== 'scheduled' && c.status?.type?.shortDetail && !/^\d+\/\d+ - /.test(c.status.type.shortDetail) ? { detail: c.status.type.shortDetail } : {}) };
  }).filter(Boolean);
}
/** Everyone named in a fixture without teams (drivers, players, fighters), for follows. */
export const athletesOf = f => f.drivers || (f.order || []).map(p => p.name).concat(
  (f.matches || []).flatMap(m => [m.a.name, m.b.name]), (f.fights || []).flatMap(b => [b.a.name, b.b.name]));

/** A follow names this person? "Lewis Hamilton", "Hamilton" (surname, 4+ letters), "Alcaraz"; folded. */
export function namesPerson(q, name) {
  const k = foldName(q), n = foldName(name);
  if (!k || !n) return false;
  if (k === n) return true;
  const w = n.split(' ');
  return k.length >= 4 && w.length > 1 && (k === w.slice(1).join(' ') || k === w[w.length - 1]);
}

/** A short line for a fixture: "Arsenal v Chelsea", "São Paulo Grand Prix · Qualifying", "UFC 334: Gane v Hokit". */
export const fixtureTitle = f => f.home ? `${f.home.short || f.home.name} v ${f.away.short || f.away.name}` : f.session ? `${f.title} · ${f.session}` : f.title || '';
/** Live now, or due on: started and not reported finished (feeds lag a minute or two). */
// (Tennis: only a match in progress. Its later times are "not before" guesses, so a passed time doesn't mean it's on.)
export const isOn = (f, now = Date.now()) => f.status === 'live' || (f.status === 'scheduled' && !f.matches && f.start <= now && now < f.end);

// ---------- team names ----------

// "Brighton & Hove Albion" -> "brighton and hove albion"; "Nott'm Forest" -> "nottm forest"; "Man Utd" -> "man united";
// "Paris Saint-Germain" -> "paris st germain". The same folding for guide titles and for team names.
export const foldName = s => ` ${String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[ᴬ-ᵪᶜ-ᶿ²³¹⁰-₟ⱽ]+/g, '').toLowerCase()
  .replace(/&/g, ' and ').replace(/['’.]/g, '').replace(/[^a-z0-9]+/g, ' ')} `
  .replace(/ utd /g, ' united ').replace(/ saint /g, ' st ').replace(/\s+/g, ' ').trim();

// Names the guide uses that the feed's names don't contain (folded). Generic rules below cover "Leicester City" ->
// "leicester", "VfB Stuttgart" -> "stuttgart", "Real Betis Balompié"… these are the rest.
const ALIASES = [
  ['manchester united', 'man united', 'man u'], ['manchester city', 'man city'], ['tottenham hotspur', 'spurs', 'tottenham'],
  ['wolverhampton wanderers', 'wolves', 'wolverhampton'], ['brighton and hove albion', 'brighton', 'brighton hove albion'],
  ['nottingham forest', 'nottm forest', 'notts forest', 'forest'], ['sheffield united', 'sheff united'], ['sheffield wednesday', 'sheff wed', 'sheffield wed'],
  ['west bromwich albion', 'west brom', 'wba'], ['queens park rangers', 'qpr'], ['middlesbrough', 'boro'], ['afc bournemouth', 'bournemouth'],
  ['preston north end', 'preston', 'pne'], ['milton keynes dons', 'mk dons'], ['crystal palace', 'palace', 'c palace'],
  ['heart of midlothian', 'hearts'], ['hibernian', 'hibs'], ['st mirren'], ['st johnstone'],
  ['paris st germain', 'psg', 'paris sg', 'paris'], ['olympique lyonnais', 'lyon'], ['olympique de marseille', 'marseille'], ['olympique marseille', 'marseille'],
  ['as monaco', 'monaco'], ['losc lille', 'lille'], ['stade rennais', 'rennes'], ['ogc nice', 'nice'], ['rc lens', 'lens'],
  ['internazionale', 'inter', 'inter milan', 'inter milano'], ['ac milan', 'milan'], ['juventus', 'juve'], ['ssc napoli', 'napoli'],
  ['bayern munich', 'bayern munchen', 'bayern', 'fc bayern'], ['borussia dortmund', 'dortmund', 'bvb'], ['borussia monchengladbach', 'gladbach', 'monchengladbach'],
  ['bayer leverkusen', 'leverkusen', 'bayer 04 leverkusen'], ['rb leipzig', 'leipzig'], ['eintracht frankfurt', 'frankfurt'], ['1 fc koln', 'koln', 'cologne', 'fc koln'],
  ['tsg hoffenheim', 'hoffenheim'], ['hamburg sv', 'hamburger sv', 'hsv', 'hamburg'], ['vfl wolfsburg', 'wolfsburg'], ['sv werder bremen', 'werder bremen', 'bremen'], ['werder bremen', 'bremen'], ['fc st pauli', 'st pauli'],
  ['barcelona', 'barca', 'fc barcelona'], ['atletico madrid', 'atletico', 'atleti', 'atletico de madrid'], ['athletic club', 'athletic bilbao', 'bilbao'],
  ['real betis', 'betis'], ['real sociedad', 'sociedad'], ['sevilla', 'seville'], ['celta vigo', 'celta'], ['deportivo alaves', 'alaves'],
  ['sporting cp', 'sporting lisbon', 'sporting'], ['fc porto', 'porto'], ['sl benfica', 'benfica'], ['psv eindhoven', 'psv'], ['feyenoord rotterdam', 'feyenoord'],
  ['club brugge', 'club bruges', 'brugge'], ['red star belgrade', 'crvena zvezda', 'red star'], ['fc copenhagen', 'copenhagen', 'fc kobenhavn', 'kobenhavn'],
  ['red bull salzburg', 'salzburg', 'rb salzburg'], ['shakhtar donetsk', 'shakhtar'], ['dinamo zagreb', 'gnk dinamo'], ['slavia prague', 'slavia praha', 'slavia'],
  ['sparta prague', 'sparta praha', 'sparta'], ['bodo glimt', 'bodo/glimt', 'bodoe glimt'], ['olympiacos', 'olympiakos'], ['union st gilloise', 'union sg', 'usg'],
  ['young boys', 'bsc young boys'], ['ferencvaros', 'ferencvarosi'], ['qarabag', 'qarabag fk'], ['fenerbahce'], ['besiktas'], ['galatasaray'],
  // NFL / NBA: the feed gives "Los Angeles Lakers" (+ nickname), guides say "LA Lakers" — the nickname covers those.
];
const ALIAS_OF = new Map();
for (const [canon, ...rest] of ALIASES) for (const n of [canon, ...rest]) { const k = foldName(n); if (!ALIAS_OF.has(k)) ALIAS_OF.set(k, foldName(canon)); }
const PREFIX = /^(?:afc|fc|cf|ac|as|ssc|ss|us|sc|sv|vfb|vfl|tsg|rc|rcd|ud|cd|sd|ca|1 fc|fk|nk|sk|bsc|kv|krc|kaa|rsc|sl|gnk)\s+/;
const SUFFIX = /\s+(?:fc|afc|cf|sc|ac|bc|fk|city|town|united|rovers|albion|athletic|county|wanderers|hotspur|rugby|rfc|north end|argyle|alexandra|orient|stanley)$/;
// Cores shared by two clubs: "Manchester" alone could be City or United.
const AMBIGUOUS = new Set(['manchester', 'sheffield', 'bristol', 'dundee', 'nottingham', 'notts', 'leicester tigers', 'real', 'inter', 'sporting', 'athletic', 'paris', 'new york', 'los angeles', 'la']);

/** Every way the guide might write a team: its names, nickname, aliases and the core without "FC" / "City" etc. */
export function teamVariants(t) {
  const out = new Set();
  const add = s => { const k = foldName(s); if (k.length >= 3 || /^[a-z]{2}\d|^\d/.test(k)) out.add(k); };
  for (const n of [t?.name, t?.short, t?.nick]) {
    if (!n) continue;
    const k = foldName(n);
    add(k);
    let core = k.replace(PREFIX, '');
    for (let i = 0; i < 2; i++) core = core.replace(SUFFIX, '');
    if (core && !AMBIGUOUS.has(core)) add(core);
    const canon = ALIAS_OF.get(k) || ALIAS_OF.get(core);
    if (canon) { add(canon); for (const [c, ...rest] of ALIASES) if (foldName(c) === canon) rest.forEach(add); }
  }
  for (const v of [...out]) if (AMBIGUOUS.has(v)) out.delete(v);
  return [...out].sort((a, b) => b.length - a.length);
}

// Where a variant appears in a folded title, as whole words: [[from, to], …].
function spans(t, variants) {
  const out = [], s = ` ${t} `;
  for (const v of variants) {
    for (let i = s.indexOf(` ${v} `); i >= 0; i = s.indexOf(` ${v} `, i + 1)) out.push([i, i + v.length]);
  }
  return out;
}
/** True when a guide title names both teams (in either order, not the same words twice). */
export function titleHasTeams(title, home, away, hv = teamVariants(home), av = teamVariants(away)) {
  const t = foldName(title);
  const a = spans(t, hv);
  if (!a.length) return false;
  const b = spans(t, av);
  return b.some(([x0, x1]) => a.some(([y0, y1]) => x1 <= y0 || y1 <= x0));
}

/** A follow ("Man Utd", "Spurs", "Chelsea Women", "F1") matches this fixture? "Chelsea" is the men's team, "Chelsea
 *  Women" (or "… Ladies", "… W", "… WFC") the women's. */
const WOMEN_Q = / (?:women|womens|ladies|wfc|lfc|w)\b/g;
export function followMatches(q, f) {
  let k = foldName(q);
  const women = WOMEN_Q.test(` ${k}`); WOMEN_Q.lastIndex = 0;
  if (women) k = ` ${k}`.replace(WOMEN_Q, '').trim();
  if (!k || women !== !!f.women) return false;
  if (!f.home) {
    const c = classify(q);
    if (c) return c.sport === f.sport && (c.comp === f.comp || ['tennis', 'f1', 'ufc'].includes(f.sport));
    if (women) return false;
    const people = athletesOf(f);
    if (f.sport === 'f1') { const team = F1_TEAMS[k] || F1_TEAMS[k.replace(/ (?:f1|racing|team)$/, '')]; if (team) return true; }
    return people.some(n => namesPerson(k, n));
  }
  const canon = ALIAS_OF.get(k) || k;
  for (const t of [f.home, f.away]) {
    const vs = teamVariants(t);
    if (vs.includes(k) || vs.includes(canon)) return true;
    if (k.length >= 4 && ` ${foldName(t.name)} `.includes(` ${k} `) && !AMBIGUOUS.has(k)) return true;
  }
  return false;
}

// ---------- which channels show it ----------

const MIN = 60_000;
const MATCHUP = /\S\s+(?:v|vs\.?|@|at|[-–])\s+\S/i;
/**
 * A small index of the guide's sport programmes, by hour, so each fixture looks at a handful of programmes instead of
 * every channel: progs = [{ id, name, start, end, title, desc }] (one channel's guide id + display name per programme).
 */
export function programmeIndex(progs, now = Date.now()) {
  const byHour = new Map();
  for (const p of progs) {
    if (p.end <= now - 4 * HOUR) continue;
    // Not sports.js isNoise: an event channel whose programme is its own name ("NFL | 03 - 1pm Titans at Bengals") or
    // a long block is fine here, because a match needs both teams and the kick-off time.
    if (isNotGame(p.title, now) || (!MATCHUP.test(p.title) && !classify(p.title))) continue;
    const prog = [p.start, p.end, p.title, p.desc || ''];
    const it = { ...p, t: foldName(p.title), live: isLiveEvent(prog), women: isWomens(p.title, p.desc), sport: classify(p.title)?.sport || null };
    for (let h = Math.floor(p.start / HOUR); h <= Math.floor((p.end - 1) / HOUR); h++) {
      const l = byHour.get(h);
      if (l) l.push(it); else byHour.set(h, [it]);
    }
  }
  return byHour;
}

const F1_SESSION = { 'Practice 1': /practice 1|\bfp1\b|first practice/, 'Practice 2': /practice 2|\bfp2\b|second practice/, 'Practice 3': /practice 3|\bfp3\b|third practice/,
  Qualifying: /(?<!sprint )qualifying|\bqualy\b/, 'Sprint Qualifying': /sprint (?:qualifying|shootout)/, Sprint: /\bsprint\b(?! (?:qualifying|shootout))/, Race: /\brace\b|grand prix(?!.*(?:practice|qualifying|sprint))/ };

/** Channels showing a fixture: programmes overlapping kick-off ±30 min that name both teams (F1: the session; UFC:
 *  the card). [{ id, name, start, end, title, desc, live }], live-tagged first, at most 24. */
export function channelsFor(f, byHour, hv, av) {
  const span = f.allDay || f.sport === 'tennis';   // a day's coverage, not one kick-off
  const from = (f.prelims || f.from || f.start) - 30 * MIN, to = span ? f.end : f.start + 30 * MIN;
  const seen = new Set(), out = [];
  const place = f.sport === 'f1' ? foldName(f.title).split(' ').filter(w => w.length >= 4 && !/^(?:grand|prix|city)$/.test(w)) : [];
  if (f.home && !hv) { hv = teamVariants(f.home); av = teamVariants(f.away); }
  for (let h = Math.floor(from / HOUR); h <= Math.floor(to / HOUR); h++) {
    for (const p of byHour.get(h) || []) {
      // On at kick-off and still on a quarter of an hour in: not the build-up show before, not the replay after.
      if (seen.has(p) || p.start > to || p.end < (span ? f.start : (f.prelims || f.start) + 15 * MIN)) continue;
      seen.add(p);
      if (p.women !== !!f.women && f.sport !== 'tennis') continue;
      // Both teams, and not another sport's game between same-named cities ("New England v Miami": MLS, not the NFL).
      if (f.home) { if (!titleHasTeams(p.t, null, null, hv, av) || (p.sport && p.sport !== f.sport)) continue; }
      else if (f.sport === 'f1') {
        // The session, and the race's place ("Mexico") or Formula 1 by name: "ISU Grand Prix" skating and a MotoGP
        // "Grand Prix of Malaysia" are on at the same time too.
        if (classify(p.title)?.sport !== 'f1' || !(F1_SESSION[f.session] || /$^/).test(p.t)) continue;
        if (!place.some(w => ` ${p.t} `.includes(` ${w} `)) && !/\b(?:formula (?:1|one)|f1)\b/.test(p.t)) continue;
        const named = (/((?:[a-z0-9]+ ){0,3})grand prix/.exec(p.t)?.[1] || '').split(' ')
          .filter(w => w.length >= 3 && !/^(?:f1|formula|one|live|the|fia|and|race|day|sunday|saturday|\d+)$/.test(w));
        if (named.length && !named.some(w => place.includes(w))) continue;   // another race: "2026 United States Grand Prix" 
      }
      else if (f.sport === 'golf') {
        if (classify(p.title)?.sport !== 'golf' || !p.live) continue;
        const words = foldName(f.title).split(' ').filter(w => w.length >= 5 && !/^(?:championship|open|classic|invitational|tour|world|international)$/.test(w));
        if (words.length && !words.some(w => ` ${p.t} `.includes(` ${w} `))) continue;
      }
      else if (f.sport === 'tennis') {
        // Live tennis naming the tournament's place ("ATP Masters 1000 Paris"), and not the other tour's coverage.
        if (classify(p.title)?.sport !== 'tennis' || !p.live) continue;
        const other = f.comp === 'ATP' ? /\bwta\b|women/ : f.comp === 'WTA' ? /\batp\b|\bmen s\b/ : /$^/;
        if (other.test(p.t)) continue;
        const words = foldName(f.title).split(' ').filter(w => w.length >= 4 && !/^(?:open|masters|tennis|championships?|classic|international|ladies|cup|finals?|1000|500|250)$/.test(w));
        if (words.length && !words.some(w => ` ${p.t} `.includes(` ${w} `))) continue;
      }
      else if (f.sport === 'ufc') {
        if (classify(p.title)?.sport !== 'ufc') continue;
        const num = /\bufc (\d{3})\b/.exec(foldName(f.title))?.[1];
        const names = foldName(f.title.split(':').slice(1).join(' ')).split(' ').filter(w => w.length >= 4 && w !== 'night');
        if (!(num && p.t.includes(num)) && !names.some(w => ` ${p.t} `.includes(` ${w} `)) && !/\b(?:prelims|main card|fight night)\b/.test(p.t)) continue;
      } else continue;
      out.push(p);
    }
  }
  const best = new Map();   // one entry per channel: its live-tagged programme if it has one
  for (const p of out) { const b = best.get(p.id); if (!b || (p.live && !b.live)) best.set(p.id, p); }
  return [...best.values()].sort((a, b) => b.live - a.live || Math.abs(a.start - f.start) - Math.abs(b.start - f.start)).slice(0, 24)
    .map(({ id, name, start, end, title, desc, live }) => ({ id, name, start, end, title, desc: (desc || '').slice(0, 300), live, ev: live ? 'live' : 'listed' }));
}

// ---------- club channels confirmed by their own guide ----------
// A club channel's guide often names only the opponent ("vs Stoke City", "Stoke City (H)"), so the both-teams rule above
// misses it. These are found by the channel's name or guide id ("Blackburn Rovers", "BlackburnRovers.efl"), then
// confirmed by a programme at kick-off that names the other side.
const compact = s => foldName(s).replace(/ /g, '');
function channelKeys(id, name) {
  const keys = new Set();
  const t = channelTeam(name);
  if (t) { keys.add(t.replace(/ /g, '')); keys.add(t.replace(PREFIX, '').replace(SUFFIX, '').replace(/ /g, '')); }
  const fromId = String(id || '').toLowerCase().split('.')[0].replace(/[^a-z0-9]/g, '');
  if (fromId.length >= 4) keys.add(fromId);
  keys.delete('');
  return keys;
}
/** chans: [{ id, name }] (guide id + display name). -> Map(compact team name -> [channel id]) for the fixtures' teams. */
export function clubIndex(chans, fixtures) {
  const wanted = new Set();
  for (const f of fixtures) if (f.home) for (const t of [f.home, f.away]) for (const v of teamVariants(t)) if (v.length >= 4) wanted.add(v.replace(/ /g, ''));
  const out = new Map();
  for (const c of chans) for (const k of channelKeys(c.id, c.name)) if (wanted.has(k)) { const l = out.get(k) || []; if (!l.includes(c.id)) l.push(c.id); out.set(k, l); }
  return out;
}
/** Club channels whose own guide shows this game: [{ id, name, start, end, title, desc, live, ev: 'club' }].
 *  progsOf(id) -> { name, progs: [[start, end, title, desc]] } for a channel in the index. */
export function clubChannelsFor(f, clubs, progsOf, now = Date.now()) {
  if (!f.home || !clubs.size) return [];
  const out = [];
  for (const [side, other] of [[f.home, f.away], [f.away, f.home]]) {
    const ov = teamVariants(other);
    const ids = new Set(teamVariants(side).flatMap(v => clubs.get(v.replace(/ /g, '')) || []));
    for (const id of ids) {
      const ch = progsOf(id);
      for (const p of ch?.progs || []) {
        if (p[0] > f.start + 30 * MIN || p[1] < f.start + 15 * MIN || isNotGame(p[2], now)) continue;
        const sp = classify(p[2])?.sport;
        if (isWomens(p[2], p[3]) !== !!f.women || (sp && sp !== f.sport) || !spans(foldName(p[2]), ov).length) continue;
        out.push({ id, name: ch.name, start: p[0], end: p[1], title: p[2], desc: String(p[3] || '').slice(0, 300), live: isLiveEvent(p), ev: 'club' });
        break;
      }
    }
  }
  return out;
}

/** { fixtureId: [channels] } for every fixture with at least one. */
export function matchChannels(fixtures, byHour, clubs = new Map(), progsOf = () => null, now = Date.now()) {
  const out = {};
  for (const f of fixtures) {
    if (f.status === 'final' || f.status === 'postponed') continue;
    const l = channelsFor(f, byHour);
    for (const c of clubChannelsFor(f, clubs, progsOf, now)) if (!l.some(x => x.id === c.id)) l.push(c);
    if (l.length) out[f.id] = l;
  }
  return out;
}

/** How sure "where to watch" is, best first: a programme naming both teams tagged live; one on air (not tagged); the
 *  club's own channel confirmed by its guide; a club channel by name only (the app's guess, no guide entry). */
export const EVIDENCE = { live: 3, listed: 2, event: 2, club: 1, likely: 0 };

/** Soonest first; at the same time, the bigger competition first. */
export function sortFixtures(list) {
  return [...list].sort((a, b) => a.start - b.start || b.compRank - a.compRank || fixtureTitle(a).localeCompare(fixtureTitle(b)));
}

// ---------- team channels: the fallback when no main channel lists a game ----------
// Providers carry a channel per club ("UK| CHAMPIONSHIP PPV": "Blackburn Rovers", "UK| SPFL PPV": "Celtic TV",
// "UK| SERIE A TEAM PPV": "UK: SERIE A - AC MILAN 4K") or per match ("National League 4 | Barrow vs Eastleigh // …").
const TEAM_GROUP = /\bppv\b|\bteam\b|\bclubs?\b/i;
const CHANNEL_NOISE = / (?:uk|gb|serie a|la ?liga|premier league|efl|spfl|championship|league one|league two|4k|uhd|fhd|hd|sd|raw|ppv|tv|channel|official)\b/g;
export const isTeamChannelGroup = g => TEAM_GROUP.test(g || '');
export const channelTeam = name => (` ${foldName(name)} `).replace(CHANNEL_NOISE, ' ').replace(/\s+/g, ' ').trim();
/** The club's own channel for this fixture (home side first), or a channel named after the match itself. */
export function teamChannelFor(f, chans) {
  if (!f?.home || !f?.away || !chans?.length) return null;
  const hv = teamVariants(f.home), av = teamVariants(f.away);
  const named = side => chans.find(c => { const t = channelTeam(c.name); return t && side.some(v => v === t || v === t.replace(PREFIX, '').replace(SUFFIX, '')); });
  const home = named(hv);
  if (home) return { channel: home, side: 'home' };
  const away = named(av);
  if (away) return { channel: away, side: 'away' };
  const match = chans.find(c => titleHasTeams(c.name, f.home, f.away, hv, av));
  return match ? { channel: match, side: 'match' } : null;
}

// ---------- event / league-pass channels: the matchup is in the channel's name ----------
// "Lakers @ Jazz" (tvg-id teamarr-event-<feed id>), "MLB 01 |  Rays x Brewers start:2026-10-25 01:00:00 stop:…",
// "PPV EVENT 05: UFC 333 Volkanovski vs. Evloev (10.24 2:00 PM ET)", "LIVE EVENT 04 - 1pm Browne v Burden",
// "National League 4 | Barrow vs Eastleigh // UK Sat 31 Oct 3:00pm". Providers rename these daily, so a date in the name
// must agree with the fixture's (a name left over from an earlier game doesn't count); "No Game Today" never matches.
const PASS_GROUP = /league pass|sunday ticket|center ice|mlb\.?tv|\bppv\b|\bevents?\b|college|flo ?sports|b\/r max|dazn|\bteam\b|\bclubs?\b/i;
export const isEventGroup = g => PASS_GROUP.test(g || '');
const MONTHS = { jan: 0, feb: 1, mar: 2, apr: 3, may: 4, jun: 5, jul: 6, aug: 7, sep: 8, oct: 9, nov: 10, dec: 11 };
/** The date a channel name carries, as [month, day] (or null): "start:2026-10-25 …", "(10.24 2:00 PM ET)", "Sat 31 Oct", "@ Oct 23". */
export function nameDate(name) {
  const n = String(name || '');
  let m = /start:\s*(\d{4})-(\d\d)-(\d\d)/i.exec(n);
  if (m) return [+m[2] - 1, +m[3]];
  m = /\((\d{1,2})[./](\d{1,2})\b/.exec(n);
  if (m) return [+m[1] - 1, +m[2]];
  m = /\b(\d{1,2})(?:st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b/i.exec(n);
  if (m) return [MONTHS[m[2].toLowerCase()], +m[1]];
  m = /\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s+(\d{1,2})\b/i.exec(n);
  if (m) return [MONTHS[m[1].toLowerCase()], +m[2]];
  return null;
}
const dateNear = (md, t) => [-DAY_MS, 0, DAY_MS].some(d => { const x = new Date(t + d); return x.getUTCMonth() === md[0] && x.getUTCDate() === md[1]; });
const DAY_MS = 24 * HOUR;
/** Event channels whose name is this fixture: [{ channel, how: 'id' | 'name' }], an exact feed-id match first. */
export function eventChannelsFor(f, chans) {
  if (!chans?.length) return [];
  const espnId = String(f.id).split(':')[1];
  const out = [];
  const hv = f.home && teamVariants(f.home), av = f.away && teamVariants(f.away);
  const num = f.sport === 'ufc' && /\bufc (\d{3})\b/.exec(foldName(f.title))?.[1];
  const names = f.sport === 'ufc' ? foldName(f.title.split(':').slice(1).join(' ')).split(' ').filter(w => w.length >= 4) : [];
  for (const c of chans) {
    if (espnId && String(c.tvgId || '').endsWith(`-${espnId}`)) { out.push({ channel: c, how: 'id' }); continue; }
    const n = String(c.name || '');
    if (/no (?:game|event)s? today|^#|off ?air/i.test(n)) continue;
    let hit = false;
    if (f.home) hit = titleHasTeams(n, null, null, hv, av);
    else if (f.sport === 'ufc') { const t = ` ${foldName(n)} `; hit = (num && t.includes(` ufc ${num} `)) || (names.length >= 2 && names.filter(w => t.includes(` ${w} `)).length >= 2); }
    if (!hit) continue;
    const md = nameDate(n);
    if (md && !dateNear(md, f.start)) continue;
    out.push({ channel: c, how: 'name', prelims: f.sport === 'ufc' && /prelim/i.test(n) });
  }
  return out.sort((a, b) => (a.how === 'id' ? 0 : 1) - (b.how === 'id' ? 0 : 1) || (a.prelims ? 1 : 0) - (b.prelims ? 1 : 0));
}
