// VOD metadata for plain M3U playlists, where movies and episodes are ordinary
// channels named "Title (2023)" or "Show S01E04" and tvg-logo is really a poster.
// All regexes are precompiled; vodMeta is a few passes over a short string, so
// ~20k names parse in well under 100ms.

// Superscript/modifier letters (ᴴᴰ, ᶠᴴᴰ, ˢᴰ) and bracketed tags ([EN], {4K}).
const SUPER_RE = /[ʰ-˿ᴬ-ᵫ⁰-₟]+/g;
const TAG_RE = /\s*(?:\[[^\]]*\]|\{[^}]*\})/g;
// Leading region prefix: "EN | ", "UK - ", "FR: ".
const PREFIX_RE = /^[A-Z]{2,3}\s*[|:\-]\s+/;
// Episode markers: S01E04, S1 E4, S01.E04, 1x04, Season 1 Episode 4, E04 / Ep 4 / Episode 4.
const EP_RE = /(?:^|[\s.\-_(])(?:S(\d{1,2})\s*[.\-_]?\s*E(\d{1,3})|(\d{1,2})x(\d{1,3})|Season\s*(\d{1,2})\s*[.\-_]?\s*(?:Episode|Ep\.?|E)\s*(\d{1,3})|E(?:pisode|p)?\.?\s*(\d{1,3}))(?=[\s.\-_)]|$)/i;
// A 4-digit year as its own token, optionally in () or [].
const YEAR_RE = /(?:^|[\s(\[])((?:19|20)\d{2})(?=[)\]\s]|$)[)\]]?/g;
// Quality / release noise, stripped from the end of the title (repeatedly).
const NOISE_RE = /(?:^|[\s\-|:·.,]+)(?:HD|FHD|UHD|SD|4K|8K|HDR|HDR10|DV|(?:480|720|1080|2160)[pi]|HEVC|x26[45]|H\.?26[45]|WEB-?(?:DL|Rip)|Blu-?Ray|BRRip|HDTV|DVDRip|MULTI|VOSTFR|VF|VO|VOST|DUB(?:BED)?|SUB(?:BED|S)?|LATINO|DUAL)\s*$/i;
const TRIM_RE = /^[\s\-|:·,._]+|[\s\-|:·,._]+$/g;
const WS_RE = /\s+/g;

const num = s => s === undefined ? null : parseInt(s, 10);

function cleanTitle(s) {
  let t = s.replace(WS_RE, ' ').replace(TRIM_RE, '');
  let prev;
  do { prev = t; t = t.replace(NOISE_RE, '').replace(TRIM_RE, ''); } while (t !== prev && t);
  if (t && !t.includes(' ') && t.includes('.')) t = t.replace(/\./g, ' ');   // scene style: The.Show
  return t;
}

// "Show Name S01E04 HD" → { title: 'Show Name', year: null, season: 1, episode: 4 }
export function vodMeta(name) {
  let s = String(name || '').replace(SUPER_RE, '').replace(TAG_RE, '').replace(PREFIX_RE, '');
  let season = null, episode = null, year = null;
  const ep = EP_RE.exec(s);
  if (ep) {
    const [, s1, e1, s2, e2, s3, e3, e4] = ep;
    season = num(s1 ?? s2 ?? s3) ?? 1;                // bare E04 → season 1
    episode = num(e1 ?? e2 ?? e3 ?? e4);
    s = s.slice(0, ep.index);                         // drop marker and any episode title after it
  }
  let last = null;
  for (const m of s.matchAll(YEAR_RE)) last = m;      // last year token wins: "2012 (2009)"
  if (last && last.index > 0) {                       // a leading year is the title ("1917")
    year = +last[1];
    s = s.slice(0, last.index) + ' ' + s.slice(last.index + last[0].length);
  }
  return { title: cleanTitle(s), year, season, episode };
}

export function isVodName(name) {
  const m = vodMeta(name);
  return m.year !== null || m.episode !== null;
}

// Collapses episode channels into series items; everything else passes through
// unchanged, in order of first appearance. Episodes are channel-shaped (spread
// of the original) plus season/num and a `channel` ref, so playChannel works on them.
export function groupSeries(channels) {
  const out = [];
  const series = new Map();
  for (const c of channels) {
    const m = vodMeta(c.name);
    if (m.episode === null) { out.push(c); continue; }
    const key = (c.group || '') + '\0' + m.title.toLowerCase();
    let s = series.get(key);
    if (!s) {
      s = { id: 'series:' + c.id, kind: 'series', name: m.title, logo: c.logo || '', group: c.group || '', year: m.year, src: c.src, seasons: {}, count: 0 };
      series.set(key, s);
      out.push(s);
    }
    if (!s.logo && c.logo) s.logo = c.logo;
    if (s.year === null && m.year !== null) s.year = m.year;
    (s.seasons[m.season] ||= []).push({ ...c, kind: 'episode', season: m.season, num: m.episode, channel: c });
    s.count++;
  }
  for (const s of series.values()) {
    for (const eps of Object.values(s.seasons)) eps.sort((a, b) => a.num - b.num);
  }
  return out;
}

// node --input-type=module -e "globalThis.__VOD_TEST__=1; await import('./vod.js')"
if (globalThis.__VOD_TEST__) {
  const cases = [
    ['Movie Title (2023)', { title: 'Movie Title', year: 2023, season: null, episode: null }],
    ['EN | Oppenheimer 2023 4K ᴴᴰ', { title: 'Oppenheimer', year: 2023, season: null, episode: null }],
    ['Show Name S01E04', { title: 'Show Name', year: null, season: 1, episode: 4 }],
    ['Show Name - 1x04 - Pilot', { title: 'Show Name', year: null, season: 1, episode: 4 }],
    ['Show Name Season 2 Episode 10 [EN]', { title: 'Show Name', year: null, season: 2, episode: 10 }],
    ['Show Name S3 E7 HD', { title: 'Show Name', year: null, season: 3, episode: 7 }],
    ['Show Name E04', { title: 'Show Name', year: null, season: 1, episode: 4 }],
    ['The.Office.S02E01.720p', { title: 'The Office', year: null, season: 2, episode: 1 }],
    ['Breaking Bad (2008) S05E14 FHD', { title: 'Breaking Bad', year: 2008, season: 5, episode: 14 }],
    ['2012 (2009)', { title: '2012', year: 2009, season: null, episode: null }],
    ['1917', { title: '1917', year: null, season: null, episode: null }],
    ['BBC One HD', { title: 'BBC One', year: null, season: null, episode: null }],
  ];
  let fails = 0;
  for (const [name, want] of cases) {
    const got = vodMeta(name);
    if (JSON.stringify(got) !== JSON.stringify(want)) { fails++; console.log('FAIL', JSON.stringify(name), got, 'want', want); }
  }
  const g = groupSeries([
    { id: 'a', name: 'Show S01E02', url: 'u2', group: 'G', logo: 'p.jpg' },
    { id: 'b', name: 'show s01e01', url: 'u1', group: 'G', logo: '' },
    { id: 'c', name: 'Movie (2020)', url: 'u3', group: 'G', logo: '' },
    { id: 'd', name: 'Show S02E01', url: 'u4', group: 'Other', logo: '' },
  ]);
  const ok = g.length === 3 && g[0].kind === 'series' && g[0].logo === 'p.jpg' && g[0].seasons[1].map(e => e.num).join() === '1,2'
    && g[0].seasons[1][0].channel.url === 'u1' && g[1].name === 'Movie (2020)' && g[2].kind === 'series' && g[2].group === 'Other';
  if (!ok) { fails++; console.log('FAIL groupSeries', JSON.stringify(g)); }
  if (!isVodName('Show S01E04') || !isVodName('Film (1999)') || isVodName('CNN HD')) { fails++; console.log('FAIL isVodName'); }
  console.log(fails ? `vod.js: ${fails} failure(s)` : 'vod.js: all tests passed');
}
