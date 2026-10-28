// Sports hub: what to search the TV guide for, how to read what comes back (which competition, a tidy title, is it a
// real event or a highlights show / placeholder), and how to lay it out (live now, then upcoming by day, filter chips).
// Pure, so it can be tested; app.js does the fetching and rendering.
//
// Items are { channel, prog: [start, end, title, desc, extra?] } — the shape findOnTv / matchUpcoming return.

/** Searches sent to the guide (whole words, any case). Football first so the biggest section fills in first. */
export const SPORT_QUERIES = [
  'Premier League', 'Champions League', 'Europa League', 'FA Cup', 'EFL', 'Championship', 'Carabao Cup', 'LaLiga', 'La Liga',
  'Serie A', 'Bundesliga', 'Scottish Premiership', 'SPFL',
  'F1', 'Formula 1', 'Grand Prix', 'MotoGP', 'NFL', 'NBA', 'NHL', 'MLB', 'UFC', 'Boxing', 'WWE',
  'Cricket', 'Rugby', 'Six Nations', 'Golf', 'Tennis', 'Snooker', 'Darts',
];

/** Filter chips, in this order; a chip only shows when something matches it. */
export const SPORTS = [
  { id: 'football', label: 'Football' }, { id: 'f1', label: 'F1' }, { id: 'motogp', label: 'MotoGP' },
  { id: 'nfl', label: 'NFL' }, { id: 'nba', label: 'NBA' }, { id: 'nhl', label: 'NHL' }, { id: 'mlb', label: 'MLB' },
  { id: 'ufc', label: 'UFC' }, { id: 'boxing', label: 'Boxing' }, { id: 'wwe', label: 'WWE' },
  { id: 'cricket', label: 'Cricket' }, { id: 'rugby', label: 'Rugby' }, { id: 'golf', label: 'Golf' },
  { id: 'tennis', label: 'Tennis' }, { id: 'snooker', label: 'Snooker' }, { id: 'darts', label: 'Darts' },
];

// Checked in order, first match wins: "Premier League Darts" is darts, "Indian Premier League" cricket,
// "World Snooker Championship" snooker (not the EFL Championship).
const COMPETITIONS = [
  ['Darts', 'darts', /\bdarts?\b|\bpdc\b/i],
  ['Snooker', 'snooker', /\bsnooker\b/i],
  ['Cricket', 'cricket', /\bcricket\b|\bt20\b|\bipl\b|indian premier league|the hundred|big bash|\bodi\b|test match|\bashes\b/i],
  ['Six Nations', 'rugby', /six nations/i],
  ['Rugby', 'rugby', /\brugby\b|gallagher premiership|\burc\b|super league|top 14|champions cup rugby/i],
  ['Golf', 'golf', /\bgolf\b|\bpga\b|\blpga\b|dp world tour|ryder cup|\bthe masters\b|\bliv golf\b/i],
  ['Tennis', 'tennis', /\btennis\b|\batp\b|\bwta\b|wimbledon|roland[- ]garros|australian open|us open tennis/i],
  ['MotoGP', 'motogp', /\bmoto ?gp\b|\bmoto[23]\b/i],
  ['F1', 'f1', /\bf1\b|formula (?:1|one)\b/i],
  ['UFC', 'ufc', /\bufc\b/i],
  ['Boxing', 'boxing', /(?<!kick[- ]?)\bboxing\b/i],
  ['WWE', 'wwe', /\bwwe\b|smackdown|wrestlemania/i],
  ['NFL', 'nfl', /\bnfl\b|redzone|super bowl/i],
  ['NBA', 'nba', /\bnba\b/i],
  ['NHL', 'nhl', /\bnhl\b|stanley cup/i],
  ['MLB', 'mlb', /\bmlb\b|world series/i],
  ['Champions League', 'football', /(?<!\b(?:afc|caf|concacaf|women'?s)\s+)champions league/i],
  ['Europa League', 'football', /europa (?:conference )?league/i],
  ['FA Cup', 'football', /\bfa cup\b|emirates fa cup/i],
  ['Carabao Cup', 'football', /carabao cup|\befl cup\b|league cup/i],
  ['Scottish Premiership', 'football', /scottish premiership|\bspfl\b/i],
  ['EFL', 'football', /\befl\b|sky bet|^championship\b|\bleague (?:one|two)\b/i],
  ['Premier League', 'football', /premier league/i],
  ['LaLiga', 'football', /\bla ?liga\b/i],
  ['Serie A', 'football', /\bserie a\b/i],
  ['Bundesliga', 'football', /bundesliga/i],
  // Motor racing without a series named: a Grand Prix is F1 unless it's darts / MotoGP (caught above).
  ['F1', 'f1', /grand prix\b/i],
];
const SPORT_ORDER = new Map(SPORTS.map((s, i) => [s.id, i]));
const COMP_ORDER = new Map(COMPETITIONS.map(([c], i) => [c, i]));

/** Which competition a programme title is from: { comp, sport } or null (not one we follow). */
export function classify(title) {
  const t = String(title || '');
  for (const [comp, sport, re] of COMPETITIONS) if (re.test(t)) return { comp, sport };
  return null;
}

const SUPERSCRIPT = /[ᴬ-ᵪᶜ-ᶿ²³¹⁰-₟ⱽ]+/g;   // provider tags: ᴿᴬᵂ ᵁᴴᴰ ᴸᶦᵛᵉ
// Not an event: highlights, magazines, studio shows, repeats of old games.
const NOT_EVENT = /\b(highlights?|hls|magazine|review|preview|reloaded|rewind|classics?|best (?:goals|of)|goals|gold(?! cup)|en 60|compacto|resumo|top 10|power rankings|fantasy|report|re-?airs?|stock exchange|countdown|draw|press conference|documentary|anthology|replay|encore|retro|in 90|recap|wrap|wins the|story of|look ?back|legends|greatest|the debate|news|daily|tonight show|podcast|uncut|central|centre|center)\b/i;
// Event-channel placeholders: "### PREMIER LEAGUE ###", "- NO EVENT STREAMING -", "NEXT | MEXICO: RACE | Sun 01 Nov …".
const PLACEHOLDER = /^[\s#*=_~-]{2,}|no event|^next\s*\||^\W*$|off ?air|to be announced|^tba$|^tbc$|stream (?:offline|starts)/i;

const fold = s => String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(SUPERSCRIPT, '').toLowerCase().replace(/[^a-z0-9]+/g, '');

/** True for programmes that aren't worth listing: highlights, placeholders, 24-hour blocks, old repeats. */
export function isNoise(prog, channel = {}, now = Date.now()) {
  const title = String(prog[2] || '').replace(SUPERSCRIPT, '').trim();
  if (!title || PLACEHOLDER.test(title) || NOT_EVENT.test(title)) return true;
  if ((title.match(/\|/g) || []).length >= 2) return true;                       // "Boxing 1 | SMITH vs PUELLO UK 5:00pm"
  const t = fold(title);
  if (t && (t === fold(channel.name) || t === fold(channel.tvgName))) return true;  // the channel's own name as the programme
  if (prog[1] - prog[0] > 8 * 3600_000) return true;                               // all-day filler blocks
  const years = (title.match(/\b(?:19|20)\d\d\b/g) || []).map(Number);
  if (years.length && Math.max(...years) < new Date(now).getFullYear() - 1) return true;   // "2004: De La Hoya v Hopkins"
  return false;
}

const MATCHUP = /\S\s+(?:v|vs\.?|@)\s+\S/i;
const SPORT_PREFIX = /^(?:live\s*[:\-–|]\s*|(?:live\s+)?(?:uefa |english |fia |the )?(?:football|soccer|darts|snooker|golf|tennis|boxing|cricket|rugby(?: union| league)?|formula (?:1|one)|f1|moto ?gp|nfl|nba|nhl|mlb|ufc|wwe|premier league|champions league|europa league|fa cup|efl(?: championship)?|sky bet championship|championship|la ?liga|serie a|bundesliga|scottish premiership|spfl)(?:\s+(?:19|20)\d\d(?:[/-]\d{2,4})?)?\s*[:\-–|]\s+)/i;

/** "Premier League - PL: Liverpool v Brighton" -> "Liverpool v Brighton"; "Chelsea vs Tottenham - English Premier
 *  League 2026/2027 - Week 8" -> "Chelsea v Tottenham"; "Formula 1 : USA Practice 1" -> "USA Practice 1". */
export function cleanTitle(title) {
  let t = String(title || '').replace(SUPERSCRIPT, '').replace(/\s+/g, ' ').trim();
  t = t.replace(/^live\s*[:\-–|]\s*/i, '').replace(/\s+[-–(]\s*live\)?$/i, '').trim();
  const parts = t.split(/\s+[-–—|]\s+|:\s+/).map(s => s.trim()).filter(Boolean);
  const vs = parts.find(p => MATCHUP.test(p));
  if (vs) return vs.replace(/\s+vs\.?\s+/i, ' v ').replace(/\s+\((?:md|matchday|week|round)\s*\d+\)$/i, '').trim();
  let prev;
  do { prev = t; t = t.replace(SPORT_PREFIX, '').trim(); } while (t && t !== prev);
  if (/^live\s+/i.test(t) && classify(t.slice(5))) t = t.slice(5).trim();   // "Live DP World Tour Golf…", not "Live from Newmarket"
  return t || String(title || '').trim();
}

/** "UK: SKY SPORTS MAIN EVENT ᴿᴬᵂ" -> "Sky Sports Main Event"; mixed-case names are kept as they are. */
const KEEP_UPPER = new Set(['BBC', 'ITV', 'TNT', 'BT', 'ESPN', 'NFL', 'NBA', 'NHL', 'MLB', 'UFC', 'WWE', 'F1', 'HD', 'UHD', 'FHD', '4K', 'PPV', 'TV', 'UK', 'US', 'USA', 'DAZN', 'NBC', 'CBS', 'ABC', 'TSN', 'MLS', 'PL', 'LFC', 'MUTV', 'RTE', 'S4C', 'STV']);
export function channelLabel(name) {
  let s = String(name || '').replace(SUPERSCRIPT, '').replace(/^\s*[A-Z]{2,3}\s*[:|]\s*/, '').replace(/\s+/g, ' ').trim();
  if (s && s === s.toUpperCase()) s = s.toLowerCase().replace(/[a-z0-9+&'’]+/g, w => KEEP_UPPER.has(w.toUpperCase()) || /\d/.test(w) ? w.toUpperCase() : w[0].toUpperCase() + w.slice(1));
  return s || String(name || '');
}

/** A channel marked as another language by a country code before its name or group: "FR: CANAL+ SPORT", "DE | Sport". */
const OTHER_LANG = /^\s*(?:fr|de|es|it|nl|pt|pl|tr|ar|ru|gr|ro|bg|hu|cz|sk|se|no|dk|fi|be|ch|at|br|mx|la|in|pk|il|ir|al|rs|hr|si|ba|mk|cn|jp|kr|ph|my|th|vn|af|ex|ku|mt|lat|swe|nor|den|ger|ita|esp|por|pol|tur|ara|rus|bra|mex|hrv)\s*[:|\-]/i;
export const otherLanguage = c => OTHER_LANG.test(c.name || '') || OTHER_LANG.test(c.group || '');

/** UK channels (the default for Sports): a UK group or name tag ("UK | Sports", "UK: Sky Sports 1", "(UK)", "🇬🇧"),
 *  or a UK broadcaster's name, as long as nothing marks it as another country ("US:", "AU |", "NZ", foreign language). */
const UK_TAG = /(?:^|[\s|:·\-–\[(/])(?:uk|gb|united kingdom)(?=$|[\s|:·\-–\])/])|🇬🇧/i;
const UK_BRAND = /sky sports|\btnt sports?\b|\bbt sport|\bbbc\b|\bitv ?\d?\b|\bstv\b|channel [45]\b|premier sports|eurosport|viaplay|\blfc tv\b|\bmutv\b|racing tv|\bs4c\b|\bfreesports\b|\bboxnation\b/i;
const NOT_UK = /(?:^|[\s|:·\-–\[(/])(?:us|usa|ca|can|canada|au|aus|australia|nz|new zealand|za|ie|ireland|in|india|my|sg|ph|mena|arab|latino|africa)(?=$|[\s|:·\-–\])/])|🇺🇸|🇨🇦|🇦🇺|🇳🇿|🇮🇪/i;
export const isUkChannel = c => {
  const name = c.name || '', group = c.group || '';
  // Groups are tagged like "CA| TSN+ PPV" / "🇺🇸 TV Guide (USA)"; a name only by a leading tag ("US: ESPN"), so words
  // inside a programme-like name ("Live in 4K") never count.
  if (otherLanguage(c) || NOT_UK.test(group) || /^\s*(?:us|usa|ca|au|nz|ie|za|in)\s*[:|]/i.test(name)) return false;
  return UK_TAG.test(group) || UK_TAG.test(name) || UK_BRAND.test(name);
};

/** Channels worth a "what's on now": a sports group or a sports channel name. */
export const isSportsChannel = c => /\bsports?\b|eurosport|\bbein\b|\bdazn\b|\bespn|premier sports|\bviaplay\b|\bbt sport|sky sports|tnt sports|\bnfl\b|\bnba\b|\bnhl\b|\bmlb\b|\bufc\b|\bwwe\b|\bracing\b|\bgolf\b|\bf1\b|\bppv\b/i.test(`${c.group || ''} ${c.name || ''}`);

const dedupeKey = (title, sport) => `${sport || ''}|${fold(title).replace(/vs?/g, 'v')}`;

/**
 * results: [{ channel, prog }] from every search (duplicates fine). nowList: [{ channel, prog }] — what's on the sports
 * channels right now (unclassified is fine). Returns { live, upcoming, sports } where each entry is
 * { channel, prog, title, comp, sport } (comp/sport null for a sports channel's unclassified programme),
 * live sorted by competition, upcoming by time, sports = chip ids that have anything.
 */
export function buildSports(results, nowList = [], now = Date.now()) {
  const seen = new Set(), liveCh = new Set(), live = [], upcoming = [];
  const all = [...results].sort((a, b) => a.prog[0] - b.prog[0]);
  for (const { channel, prog } of all) {
    if (prog[1] <= now || isNoise(prog, channel, now)) continue;
    const k = classify(prog[2]);
    if (!k) continue;
    const title = cleanTitle(prog[2]);
    const key = dedupeKey(title, k.sport);
    const isLive = prog[0] <= now;
    if (isLive && liveCh.has(channel.url)) continue;
    if (seen.has(key)) continue;   // the same game again later (a repeat) or on another copy of the channel
    seen.add(key);
    const it = { channel, prog, title, comp: k.comp, sport: k.sport };
    if (isLive) { live.push(it); liveCh.add(channel.url); } else upcoming.push(it);
  }
  for (const { channel, prog } of nowList) {
    if (liveCh.has(channel.url) || !(prog[0] <= now && prog[1] > now) || isNoise(prog, channel, now)) continue;
    const k = classify(prog[2]);
    const title = cleanTitle(prog[2]);
    const key = dedupeKey(title, k?.sport);
    if (seen.has(key)) continue;
    seen.add(key); liveCh.add(channel.url);
    live.push({ channel, prog, title, comp: k?.comp || null, sport: k?.sport || null });
  }
  const rank = it => it.sport ? (SPORT_ORDER.get(it.sport) ?? 99) * 100 + (COMP_ORDER.get(it.comp) ?? 99) : 1e6;
  live.sort((a, b) => rank(a) - rank(b) || a.prog[1] - b.prog[1]);
  const have = new Set([...live, ...upcoming].map(it => it.sport).filter(Boolean));
  return { live, upcoming, sports: SPORTS.filter(s => have.has(s.id)).map(s => s.id) };
}

/** Upcoming items in days: [{ key, label: 'Today' | 'Tomorrow' | 'Friday', date: '30 Oct', items }]. */
export function groupByDay(items, now = Date.now()) {
  const day0 = new Date(now); day0.setHours(0, 0, 0, 0);
  const out = new Map();
  for (const it of items) {
    const d = new Date(it.prog[0]); d.setHours(0, 0, 0, 0);
    const diff = Math.round((d - day0) / 86400_000);
    const key = d.toDateString();
    if (!out.has(key)) out.set(key, {
      key, items: [],
      label: diff === 0 ? 'Today' : diff === 1 ? 'Tomorrow' : d.toLocaleDateString([], { weekday: 'long' }),
      date: d.toLocaleDateString([], { day: 'numeric', month: 'short' }),
    });
    out.get(key).items.push(it);
  }
  return [...out.values()];
}

/** The chip filter: 'all' or a sport id. */
export const bySport = (items, sport) => (sport === 'all' ? items : items.filter(it => it.sport === sport));

/** A followed team's matches out of the hub's items (title mentions every word). */
export function teamItems(q, items) {
  const words = String(q || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').split(/[^a-z0-9]+/).filter(w => w.length > 1);
  if (!words.length) return [];
  return items.filter(it => { const t = ` ${String(it.prog[2]).toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, ' ')} `; return words.every(w => t.includes(` ${w} `)); });
}

export function _selfTest() {
  const now = Date.UTC(2026, 9, 28, 12);
  const H = 3600_000;
  const ch = (name, url) => ({ name, url, group: 'UK Sports' });
  const r = [
    { channel: ch('Sky Sports PL', 'a'), prog: [now + H, now + 3 * H, 'Premier League - PL: Liverpool v Brighton', ''] },
    { channel: ch('Sky Sports PL', 'a'), prog: [now + 30 * H, now + 32 * H, 'Premier League: Liverpool vs Brighton', ''] },   // repeat
    { channel: ch('Sky Sports PL', 'a'), prog: [now + 5 * H, now + 6 * H, 'Premier League Highlights', ''] },
    { channel: ch('PPV 1', 'b'), prog: [now - H, now + 20 * H, '### PREMIER LEAGUE ###', ''] },
    { channel: ch('Sky Sports Mix', 'c'), prog: [now - H, now + H, 'Indian Premier League T20', ''] },
    { channel: ch('Sky Sports Darts', 'd'), prog: [now + 2 * H, now + 4 * H, 'PDC Premier League Darts: Week 5', ''] },
    { channel: ch('Sky Sports Golf', 'e'), prog: [now + 2 * H, now + 4 * H, 'Korea Championship, LET Golf', ''] },
    { channel: ch('beIN 2', 'f'), prog: [now + 2 * H, now + 4 * H, 'EFL Championship - Bradford City vs. Peterborough United', ''] },
    { channel: ch('DAZN Ringside', 'g'), prog: [now + 2 * H, now + 3 * H, 'Boxing : 2004: Oscar De La Hoya vs. Bernard Hopkins', ''] },
    { channel: ch('Fightbox', 'h'), prog: [now + 2 * H, now + 3 * H, 'Kick-boxing: WGP Kickboxing Brazil 22', ''] },
  ];
  const s = buildSports(r, [{ channel: ch('Sky Sports News', 'n'), prog: [now - H, now + H, 'Sky Sports News', ''] }], now);
  const got = [s.live.map(x => `${x.comp}:${x.title}`), s.upcoming.map(x => `${x.comp}:${x.title}`)];
  const want = [['Cricket:Indian Premier League T20'], ['Premier League:Liverpool v Brighton', 'Darts:PDC Premier League Darts: Week 5', 'Golf:Korea Championship, LET Golf', 'EFL:Bradford City v Peterborough United']];
  return JSON.stringify(got) === JSON.stringify(want) && channelLabel('UK: SKY SPORTS MAIN EVENT ᴿᴬᵂ') === 'Sky Sports Main Event'
    ? 'ok' : `failed: ${JSON.stringify(got)} ${channelLabel('UK: SKY SPORTS MAIN EVENT ᴿᴬᵂ')}`;
}
