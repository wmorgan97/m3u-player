// Channel-logo match report: runs the server's logo resolver over a list of channel names and prints match rates
// and a sample of misses.
//
//   node scripts/logo-report.mjs [names.txt|channels.json] [out.txt]
//
// names.txt: one channel name per line (optionally "name<TAB>tvg-id"); channels.json: [{ name, tvgId|id }].
// Without a file it uses a built-in list of typical UK provider names ("UK: BBC One HD", "|UK| SKY CINEMA ACTION FHD").
// The downloaded index is kept next to the report for a day, so re-running never hammers GitHub.

import { promises as fs } from 'node:fs';
import { dirname, join } from 'node:path';
import { homedir } from 'node:os';
import { buildIndex, downloadIndex, resolveLogo } from '../server/logos.js';

const [, , input, outArg] = process.argv;
const out = outArg || join(homedir(), 'm3u-shots', 'logos', 'report.txt');
const cacheFile = join(dirname(out), 'index-cache.json');

const UK = ['BBC One', 'BBC Two', 'BBC Three', 'BBC Four', 'BBC News', 'BBC Parliament', 'CBBC', 'CBeebies', 'BBC Scotland', 'BBC One Scotland',
  'BBC One Wales', 'BBC One Northern Ireland', 'BBC One London', 'BBC One Yorkshire', 'BBC Alba', 'ITV1', 'ITV2', 'ITV3', 'ITV4', 'ITV Quiz', 'ITVBe',
  'ITV1 London', 'STV', 'UTV', 'Channel 4', 'E4', 'E4 Extra', 'More4', 'Film4', '4seven', 'Channel 5', '5Star', '5USA', '5Action', 'Sky One', 'Sky Showcase',
  'Sky Atlantic', 'Sky Max', 'Sky Witness', 'Sky Comedy', 'Sky Crime', 'Sky Documentaries', 'Sky Nature', 'Sky History', 'Sky Arts', 'Sky Kids', 'Sky Replay',
  'Sky News', 'Sky Mix', 'Sky Sci-Fi', 'Sky Cinema Premiere', 'Sky Cinema Action', 'Sky Cinema Comedy', 'Sky Cinema Family', 'Sky Cinema Drama',
  'Sky Cinema Thriller', 'Sky Cinema Greats', 'Sky Cinema Hits', 'Sky Cinema Animation', 'Sky Cinema Sci-Fi & Horror', 'Sky Cinema Select',
  'Sky Sports Main Event', 'Sky Sports Premier League', 'Sky Sports Football', 'Sky Sports F1', 'Sky Sports Cricket', 'Sky Sports Golf', 'Sky Sports Arena',
  'Sky Sports Action', 'Sky Sports Mix', 'Sky Sports Tennis', 'Sky Sports Racing', 'Sky Sports News', 'Sky Sports+', 'TNT Sports 1', 'TNT Sports 2',
  'TNT Sports 3', 'TNT Sports 4', 'TNT Sports Ultimate', 'Premier Sports 1', 'Premier Sports 2', 'Eurosport 1', 'Eurosport 2', 'Viaplay Sports 1',
  'Racing TV', 'LFC TV', 'MUTV', 'Chelsea TV', 'Dave', 'Gold', 'W', 'Alibi', 'Eden', 'Yesterday', 'Drama', 'Quest', 'Quest Red', 'Really', 'DMAX',
  'Food Network', 'HGTV', 'Discovery Channel', 'Discovery History', 'Discovery Science', 'Animal Planet', 'National Geographic', 'Nat Geo Wild',
  'Comedy Central', 'Comedy Central Extra', 'MTV', 'MTV Music', 'MTV Hits', 'Kerrang', 'Kiss', 'Magic', 'Smash Hits', '4Music', 'Clubland TV',
  'Now 80s', 'Now 90s', 'Now 70s', 'Cartoon Network', 'Cartoonito', 'Boomerang', 'Nickelodeon', 'Nick Jr', 'Nicktoons', 'Disney Channel', 'Disney Junior',
  'Pop', 'Tiny Pop', 'CNN', 'BBC World News', 'Al Jazeera English', 'GB News', 'TalkTV', 'Bloomberg', 'CNBC', 'Euronews', 'Sky Arts', 'Blaze',
  'Challenge', 'Pick', 'Together TV', 'Legend', 'Great TV', 'Great Movies', 'Great Action', 'Talking Pictures TV', 'Paramount Network', 'CBS Reality',
  'CBS Justice', 'Crime & Investigation', 'History', 'Lifetime', 'Sony Movies', 'Sony Movies Action', 'TCM', 'Universal TV', 'SyFy', 'E!', 'Fox',
  'Boxnation', 'BT Sport 1', 'Free Sports', 'Channel 4 +1', 'E4 +1', 'More4 +1', 'Film4 +1', 'ITV2 +1', 'ITV3 +1', 'Dave +1', 'Gold +1', 'Alibi +1',
  'Channel 5 +1', 'Sky Witness +1', 'Comedy Central +1', 'W +1', 'Yesterday +1', 'Quest +1', 'S4C', 'RTE One', 'RTE Two', 'Virgin Media One', 'TG4'];
const FORMATS = [n => `UK: ${n} HD`, n => `|UK| ${n.toUpperCase()} FHD`, n => `UK ● ${n} ᴴᴰ`, n => `UK| ${n}`, n => `${n} HD`, n => `[UK] ${n} 4K`];

async function names() {
  if (!input) return UK.map((n, i) => ({ name: FORMATS[i % FORMATS.length](n), base: n, uk: true }));
  const text = await fs.readFile(input, 'utf8');
  if (/^\s*[[{]/.test(text)) {
    const j = JSON.parse(text);
    return (Array.isArray(j) ? j : j.channels || []).map(c => ({ name: c.name || '', id: c.tvgId || c.id || c.epg_channel_id || '', uk: /^\W*(uk|gb)\b/i.test(c.name || '') || /\.uk$/i.test(c.tvgId || '') }));
  }
  return text.split('\n').filter(Boolean).map(l => { const [name, id = ''] = l.split('\t'); return { name, id, uk: /^\W*(uk|gb)\b/i.test(name) || /\.uk$/i.test(id) }; });
}

async function index() {
  try { const st = await fs.stat(cacheFile); if (Date.now() - st.mtimeMs < 86400_000) return JSON.parse(await fs.readFile(cacheFile, 'utf8')); } catch {}
  const raw = await downloadIndex();
  await fs.mkdir(dirname(cacheFile), { recursive: true });
  await fs.writeFile(cacheFile, JSON.stringify(raw));
  return raw;
}

const raw = await index();
const idx = buildIndex(raw);
const list = await names();
const rows = list.map(c => ({ ...c, r: resolveLogo(idx, { name: c.name, id: c.id }) }));
const pct = (a, b) => b ? `${(100 * a / b).toFixed(1)}%` : '-';
const stat = (label, rs) => {
  const tv = rs.filter(x => x.r?.via === 'tv-logos').length, ip = rs.filter(x => x.r?.via === 'iptv-org').length;
  return `${label.padEnd(14)} ${String(rs.length).padStart(5)} channels  matched ${pct(tv + ip, rs.length).padStart(6)}  (tv-logos ${tv}, iptv-org ${ip}, none ${rs.length - tv - ip})`;
};
const misses = rows.filter(x => !x.r);
const lines = [
  `Channel logo match report  ${new Date().toISOString().slice(0, 16)}`,
  `Input: ${input || 'built-in list of typical UK provider names (no real channel snapshot on this Mac)'}`,
  `Index: ${Object.entries(raw.tvlogos).map(([k, v]) => `${k} ${v.length}`).join(', ')} tv-logos files; ${raw.logos.length} iptv-org logos; ${raw.channels.length} iptv-org channels`,
  '',
  stat('All', rows),
  stat('UK', rows.filter(x => x.uk)),
  '',
  `Misses (${misses.length}${misses.length > 60 ? ', first 60' : ''}):`,
  ...misses.slice(0, 60).map(x => `  ${x.name}${x.id ? `  [${x.id}]` : ''}`),
  '',
  'Sample matches:',
  ...rows.filter(x => x.r).slice(0, 40).map(x => `  ${x.name.padEnd(40)} ${x.r.via.padEnd(9)} ${x.r.src.replace(/^https:\/\/raw\.githubusercontent\.com\/tv-logo\/tv-logos\/main\//, '')}`),
];
await fs.mkdir(dirname(out), { recursive: true });
await fs.writeFile(out, lines.join('\n') + '\n');
console.log(lines.slice(0, 7).join('\n'));
console.log(`\nWritten to ${out}`);
