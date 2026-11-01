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
const LENGTH = { football: 2 * HOUR, f1: 2 * HOUR, nfl: 3.5 * HOUR, nba: 2.5 * HOUR, ufc: 5 * HOUR, rugby: 2 * HOUR };
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
    const base = { sport: src.sport, comp: src.comp, compRank };
    const comps = ev.competitions || [];
    if (src.sport === 'f1') {
      const gp = String(ev.name || ev.shortName || '').replace(SPONSOR, '').replace(SPONSOR, '').trim();
      return comps.map(c => {
        const session = SESSION[c.type?.abbreviation] || c.type?.abbreviation || 'Session';
        const start = Date.parse(c.date || c.startDate);
        const st = statusOf(c.status);
        return { id: `${src.key}:${c.id}`, ...base, start, end: start + (SESSION_LEN[session] || HOUR), status: st,
          ...(st === 'live' && c.status?.type?.shortDetail ? { minute: c.status.type.shortDetail } : {}),
          title: gp, session, venue: ev.circuit?.fullName || '' };
      }).filter(f => f.start);
    }
    if (src.sport === 'ufc') {
      // A fight card: the event's date is the prelims, the last bouts' time the main card.
      const times = comps.map(c => Date.parse(c.date)).filter(Boolean);
      const prelims = Date.parse(ev.date) || Math.min(...times), start = times.length ? Math.max(...times) : prelims;
      if (!start) return [];
      const st = statusOf(ev.status || comps[0]?.status);
      return [{ id: `${src.key}:${ev.id}`, ...base, start, prelims, end: start + 3 * HOUR, status: st,
        title: String(ev.name || '').replace(/\s+vs\.?\s+/i, ' v '), venue: comps[0]?.venue?.fullName || '' }];
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

/** A short line for a fixture: "Arsenal v Chelsea", "São Paulo Grand Prix · Qualifying", "UFC 334: Gane v Hokit". */
export const fixtureTitle = f => f.home ? `${f.home.short || f.home.name} v ${f.away.short || f.away.name}` : f.session ? `${f.title} · ${f.session}` : f.title || '';
/** Live now, or due on: started and not reported finished (feeds lag a minute or two). */
export const isOn = (f, now = Date.now()) => f.status === 'live' || (f.status === 'scheduled' && f.start <= now && now < f.end);

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
const AMBIGUOUS = new Set(['manchester', 'sheffield', 'bristol', 'dundee', 'nottingham', 'notts', 'birmingham', 'oxford', 'cambridge', 'leicester tigers', 'real', 'inter', 'sporting', 'athletic', 'paris', 'new york', 'los angeles', 'la']);

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

/** A follow ("Man Utd", "Spurs", "Chelsea Women", "F1") matches this fixture? Women's follows never match (no women's feed). */
export function followMatches(q, f) {
  const k = foldName(q);
  if (!k || /\b(?:women|womens|ladies|wsl|w)\b/.test(k)) return false;
  if (!f.home) {
    const c = classify(q);
    return !!c && c.comp === f.comp;
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
    const it = { ...p, t: foldName(p.title), live: isLiveEvent(prog), women: isWomens(p.title, p.desc) };
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
  const from = (f.prelims || f.start) - 30 * MIN, to = f.start + 30 * MIN;
  const seen = new Set(), out = [];
  const place = f.sport === 'f1' ? foldName(f.title).split(' ').filter(w => w.length >= 4 && !/^(?:grand|prix|city)$/.test(w)) : [];
  if (f.home && !hv) { hv = teamVariants(f.home); av = teamVariants(f.away); }
  for (let h = Math.floor(from / HOUR); h <= Math.floor(to / HOUR); h++) {
    for (const p of byHour.get(h) || []) {
      if (seen.has(p) || p.start >= to || p.end <= from) continue;
      seen.add(p);
      if (p.women) continue;
      if (f.home) { if (!titleHasTeams(p.t, null, null, hv, av)) continue; }
      else if (f.sport === 'f1') {
        // The session, and the race's place ("Mexico") or Formula 1 by name: "ISU Grand Prix" skating and a MotoGP
        // "Grand Prix of Malaysia" are on at the same time too.
        if (classify(p.title)?.sport !== 'f1' || !(F1_SESSION[f.session] || /$^/).test(p.t)) continue;
        if (!place.some(w => ` ${p.t} `.includes(` ${w} `)) && !/\b(?:formula (?:1|one)|f1)\b/.test(p.t)) continue;
        const named = (/((?:[a-z0-9]+ ){0,3})grand prix/.exec(p.t)?.[1] || '').split(' ')
          .filter(w => w.length >= 3 && !/^(?:f1|formula|one|live|the|fia|and|race|day|sunday|saturday|\d+)$/.test(w));
        if (named.length && !named.some(w => place.includes(w))) continue;   // another race: "2026 United States Grand Prix" 
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
    .map(({ id, name, start, end, title, desc, live }) => ({ id, name, start, end, title, desc: (desc || '').slice(0, 300), live }));
}

/** { fixtureId: [channels] } for every fixture with at least one. */
export function matchChannels(fixtures, byHour) {
  const out = {};
  for (const f of fixtures) {
    if (f.status === 'final' || f.status === 'postponed') continue;
    const l = channelsFor(f, byHour);
    if (l.length) out[f.id] = l;
  }
  return out;
}

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
const channelTeam = name => (` ${foldName(name)} `).replace(CHANNEL_NOISE, ' ').replace(/\s+/g, ' ').trim();
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
