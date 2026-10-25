// XMLTV programme guide. Provider guides can be 100MB+, so the file is
// streamed (and un-gzipped) in chunks, and only programmes for channels in
// the playlist, within the time window the app shows, are kept.
import { fetchResponse } from './net.js';

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };

export function decode(s) {
  return s
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/&(#x[0-9a-f]+|#\d+|\w+);/gi, (m, e) => {
      if (e[0] !== '#') return ENTITIES[e.toLowerCase()] ?? m;
      const code = e[1].toLowerCase() === 'x' ? parseInt(e.slice(2), 16) : Number(e.slice(1));
      try { return String.fromCodePoint(code); } catch { return m; }
    });
}

// "20251025120000 +0100" -> ms since epoch
export function parseTime(s) {
  const m = /^(\d{4})(\d\d)(\d\d)(\d\d)(\d\d)(\d\d)?\s*([+-]\d{4})?/.exec(String(s).trim());
  if (!m) return NaN;
  let t = Date.UTC(+m[1], m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
  if (m[7]) {
    const sign = m[7][0] === '-' ? -1 : 1;
    t -= sign * (Number(m[7].slice(1, 3)) * 60 + Number(m[7].slice(3))) * 60000;
  }
  return t;
}

// Channel names from playlists and guides differ in small ways ("UK| BBC 1 ᴴᴰ", "BBC One HD",
// "BBC 1 (Backup)"): strip the noise and spell numbers as digits so they still match.
const NUM_WORDS = { one: '1', two: '2', three: '3', four: '4', five: '5', six: '6', seven: '7', eight: '8', nine: '9', ten: '10' };
export const normName = s => String(s || '').toLowerCase()
  .replace(/[\u1D2C-\u1D6A\u00B2\u00B3\u00B9\u2070-\u209F]+/g, '')          // superscript provider tags
  .replace(/^\s*(?:uk|gb|us|usa|ca|ie|au|nz|eu)\s*[:|\-]\s*/, '')               // "UK| " / "UK: " prefixes
  .replace(/\(.*?\)|\[.*?\]/g, '')                                             // (backup) [vip]
  .replace(/\b(hd|fhd|uhd|4k|sd|hevc|h265|backup|raw|vip|low|50fps)\b/g, '')
  .replace(/\b(one|two|three|four|five|six|seven|eight|nine|ten)\b/g, w => NUM_WORDS[w])
  .replace(/[^\p{L}\p{N}+]+/gu, '');                                           // keep "+" so "+1" channels stay distinct

const attr = (tag, name) => { const m = new RegExp(`\\b${name}="([^"]*)"`).exec(tag); return m ? decode(m[1]) : ''; };
const child = (block, name) => { const m = new RegExp(`<${name}\\b[^>]*>([\\s\\S]*?)</${name}>`).exec(block); return m ? decode(m[1]).trim() : ''; };

export function wantedFor(channels) {
  const ids = new Set(), names = new Set();
  for (const c of channels) {
    if (c.tvgId) ids.add(c.tvgId.toLowerCase());
    if (c.name) names.add(normName(c.name));
    if (c.tvgName) names.add(normName(c.tvgName));
  }
  return { ids, names };
}

// Feed text chunks in any size; programmes are [start, stop, title, description].
export class XmltvParser {
  constructor({ from = -Infinity, to = Infinity, wanted = null } = {}) {
    this.from = from;
    this.to = to;
    this.wanted = wanted;
    this.accept = new Set(wanted?.ids || []);
    this.names = {};
    this.programmes = {};
    this.buf = '';
  }

  feed(text) {
    let buf = this.buf + text;
    let pos = 0;
    for (;;) {
      const c = buf.indexOf('<channel', pos);
      const p = buf.indexOf('<programme', pos);
      const start = c < 0 ? p : p < 0 ? c : Math.min(c, p);
      if (start < 0) { pos = Math.max(pos, buf.length - 16); break; }
      const isProg = start === p;
      const close = isProg ? '</programme>' : '</channel>';
      const end = buf.indexOf(close, start);
      if (end < 0) { pos = start; break; }
      const block = buf.slice(start, end);
      if (isProg) this.programme(block); else this.channel(block);
      pos = end + close.length;
    }
    this.buf = buf.slice(pos);
  }

  channel(block) {
    const id = attr(block.slice(0, block.indexOf('>') + 1), 'id').toLowerCase();
    if (!id) return;
    for (const m of block.matchAll(/<display-name\b[^>]*>([\s\S]*?)<\/display-name>/g)) {
      const n = normName(decode(m[1]));
      if (!n) continue;
      this.names[n] ??= id;
      if (this.wanted?.names.has(n)) this.accept.add(id);
    }
  }

  programme(block) {
    const open = block.slice(0, block.indexOf('>') + 1);
    const ch = attr(open, 'channel').toLowerCase();
    if (!ch || (this.wanted && !this.accept.has(ch))) return;
    const start = parseTime(attr(open, 'start'));
    const stop = parseTime(attr(open, 'stop'));
    if (!(stop > this.from) || !(start < this.to)) return;
    // Programmes are [start, stop, title, description, extra?] with extra = { img, new, s, e } when present.
    let desc = child(block, 'desc');
    const extra = {};
    const img = /<icon\s+src="([^"]+)"/.exec(block);
    if (img) extra.img = decode(img[1]);
    if (/<new\s*\/>/.test(block)) extra.new = 1;
    const se = /^S(\d{1,3})\s*E(\d{1,4})\b[\s:.\-]*/i.exec(desc) || /^(?:Series|Season)\s*(\d{1,3})[,:]?\s*Episode\s*(\d{1,4})\b[\s:.\-]*/i.exec(desc);
    if (se) { extra.s = +se[1]; extra.e = +se[2]; desc = desc.slice(se[0].length); }
    const p = [start, stop, child(block, 'title'), desc.slice(0, 600)];
    if (Object.keys(extra).length) p.push(extra);
    (this.programmes[ch] ??= []).push(p);
  }

  finish() {
    for (const list of Object.values(this.programmes)) list.sort((a, b) => a[0] - b[0]);
    return { programmes: this.programmes, names: this.names };
  }
}

async function streamInto(parser, url) {
  const res = await fetchResponse(url, { timeout: 60000 });
  const reader = res.body.getReader();
  const first = await reader.read();
  const head = first.value || new Uint8Array();
  let body = new ReadableStream({
    start(ctrl) { if (head.length) ctrl.enqueue(head); if (first.done) ctrl.close(); },
    async pull(ctrl) {
      const { done, value } = await reader.read();
      if (done) ctrl.close(); else ctrl.enqueue(value);
    },
  });
  if (head[0] === 0x1f && head[1] === 0x8b) body = body.pipeThrough(new DecompressionStream('gzip'));
  const text = body.pipeThrough(new TextDecoderStream()).getReader();
  for (;;) {
    const { done, value } = await text.read();
    if (done) break;
    parser.feed(value);
  }
}

export async function loadEpg(urls, options) {
  const parser = new XmltvParser(options);
  let lastError, ok = 0;
  for (const url of urls) {
    try { await streamInto(parser, url); ok++; } catch (e) { lastError = e; }
  }
  if (!ok) throw lastError;
  return parser.finish();
}

export class Guide {
  constructor({ programmes = {}, names = {}, at = 0, scope }) {
    this.programmes = programmes;
    this.names = names;
    this.at = at;
    this.scope = scope;
    this.keys = new WeakMap();
  }

  get channelCount() { return Object.keys(this.programmes).length; }

  key(c) {
    if (this.keys.has(c)) return this.keys.get(c);
    const id = (c.tvgId || '').toLowerCase();
    const key = (id && this.programmes[id] && id) || this.names[normName(c.tvgName)] || this.names[normName(c.name)] || null;
    this.keys.set(c, key);
    return key;
  }

  list(c) { return this.programmes[this.key(c)] || []; }

  // Returns { now, next } programmes at time t.
  nowNext(c, t = Date.now()) {
    const list = this.list(c);
    let lo = 0, hi = list.length - 1, idx = -1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (list[mid][0] <= t) { idx = mid; lo = mid + 1; } else hi = mid - 1;
    }
    const now = idx >= 0 && list[idx][1] > t ? list[idx] : null;
    return { now, next: list[idx + 1] || null };
  }
}

// Days of catch-up a channel offers (Xtream tv_archive, or M3U catchup attributes).
export function archiveDays(c) {
  if (c.xt) return c.xt.archive || 0;
  const a = c.attrs || {};
  if (a['catchup-days']) return Number(a['catchup-days']) || 0;
  return a.catchup || a['catchup-source'] || a['catchup-type'] ? 7 : 0;
}

const pad = n => String(n).padStart(2, '0');

// Builds the catch-up URL for an M3U channel using its catchup / catchup-source attributes.
export function m3uCatchupUrl(c, start, stop, now = Date.now()) {
  const a = c.attrs || {};
  const mode = (a.catchup || a['catchup-type'] || '').toLowerCase();
  // Xtream-style providers ("xc"): /live/user/pass/id.ts -> /timeshift/user/pass/minutes/YYYY-MM-DD:HH-MM/id.ts
  const xc = /^(https?:\/\/[^/]+)\/(?:live\/)?([^/]+)\/([^/]+)\/(\d+)(?:\.\w+)?$/i.exec(c.url);
  if (xc && (mode === 'xc' || mode === 'xtream' || (!a['catchup-source'] && /\/live\//.test(c.url)))) {
    const d = new Date(start);
    const when = `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}:${pad(d.getUTCHours())}-${pad(d.getUTCMinutes())}`;
    return `${xc[1]}/timeshift/${xc[2]}/${xc[3]}/${Math.ceil((stop - start) / 60000)}/${when}/${xc[4]}.ts`;
  }
  let source = a['catchup-source'] || '';
  if (!source || mode === 'shift' || mode === 'timeshift') {
    source = 'utc={utc}&lutc={lutc}';
    source = (c.url.includes('?') ? '&' : '?') + source;
  }
  const s = Math.floor(start / 1000), e = Math.floor(stop / 1000), n = Math.floor(now / 1000);
  const d = new Date(start);
  const values = {
    utc: s, start: s, timestamp: s, lutc: n, now: n, utcend: e, end: e, duration: e - s, offset: n - s,
    Y: d.getUTCFullYear(), m: pad(d.getUTCMonth() + 1), d: pad(d.getUTCDate()),
    H: pad(d.getUTCHours()), M: pad(d.getUTCMinutes()), S: pad(d.getUTCSeconds()),
  };
  const filled = source.replace(/\$?\{\(?(\w+)\)?(?::[^}]*)?\}/g, (m, k) => (k in values ? values[k] : m));
  return /^https?:\/\//i.test(filled) ? filled : c.url + filled;
}
