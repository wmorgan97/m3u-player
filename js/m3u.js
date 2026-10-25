// Parsing and writing of M3U / M3U8 playlists, including the extended
// IPTV attributes (tvg-id, tvg-name, tvg-logo, group-title).

const ATTR_RE = /([A-Za-z0-9_-]+)=("([^"]*)"|'([^']*)'|([^\s,]+))/g;

export const uid = () =>
  globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2) + Date.now().toString(36);

export function makeChannel(fields = {}) {
  return { id: uid(), name: '', url: '', group: '', logo: '', tvgId: '', tvgName: '', duration: -1, attrs: {}, opts: [], ...fields };
}

export function parseAttrs(str) {
  const attrs = {};
  for (const m of str.matchAll(ATTR_RE)) attrs[m[1].toLowerCase()] = m[3] ?? m[4] ?? m[5] ?? '';
  return attrs;
}

// Splits `-1 tvg-name="A, B",Title` at the first comma that isn't inside a quoted value.
function splitTitle(body) {
  let quote = null;
  for (let i = 0; i < body.length; i++) {
    const ch = body[i];
    if (quote) { if (ch === quote) quote = null; }
    else if ((ch === '"' || ch === "'") && body[i - 1] === '=') quote = ch;
    else if (ch === ',') return [body.slice(0, i), body.slice(i + 1)];
  }
  return [body, ''];
}

function parseExtinf(body) {
  const [head, title] = splitTitle(body);
  const m = head.match(/^\s*(-?\d+(?:\.\d+)?)?/);
  return {
    duration: m[1] !== undefined ? Number(m[1]) : -1,
    attrs: parseAttrs(head.slice(m[0].length)),
    title: title.trim(),
  };
}

export function nameFromUrl(url) {
  try {
    const u = new URL(url);
    const last = decodeURIComponent(u.pathname.split('/').filter(Boolean).pop() || '');
    return last.replace(/\.[a-z0-9]{2,5}$/i, '') || u.hostname;
  } catch {
    return url;
  }
}

// True when the text is an HLS stream manifest rather than a list of channels.
export function isHlsMedia(text) {
  return /#EXT-X-(TARGETDURATION|STREAM-INF|MEDIA-SEQUENCE)/.test(text);
}

export function parseM3U(text) {
  const lines = String(text).replace(/^﻿/, '').split(/\r\n|\r|\n/);
  const header = {};
  const channels = [];
  let info = null, group = null, opts = [];

  for (const raw of lines) {
    const line = raw.trim();
    if (!line) continue;
    if (/^#EXTM3U/i.test(line)) { Object.assign(header, parseAttrs(line.slice(7))); continue; }
    if (/^#EXTINF:/i.test(line)) { info = parseExtinf(line.slice(8)); continue; }
    if (/^#EXTGRP:/i.test(line)) { group = line.slice(8).trim(); continue; }
    if (/^#EXTVLCOPT:/i.test(line)) { opts.push(line.slice(11).trim()); continue; }
    if (line.startsWith('#')) continue;

    const { duration = -1, attrs = {}, title = '' } = info || {};
    const { 'tvg-id': tvgId = '', 'tvg-name': tvgName = '', 'tvg-logo': logo = '', 'group-title': groupTitle = '', ...rest } = attrs;
    channels.push(makeChannel({
      name: title || tvgName || nameFromUrl(line),
      url: line,
      group: groupTitle || group || '',
      logo, tvgId, tvgName, duration, attrs: rest, opts,
    }));
    info = null; group = null; opts = [];
  }
  return { header, channels };
}

const clean = v => String(v).replace(/"/g, "'").replace(/[\r\n]+/g, ' ');
const attrString = attrs => Object.entries(attrs)
  .filter(([, v]) => v !== undefined && v !== null && v !== '')
  .map(([k, v]) => ` ${k}="${clean(v)}"`)
  .join('');

export function toM3U(channels, header = {}) {
  const out = ['#EXTM3U' + attrString(header)];
  for (const c of channels) {
    const attrs = { 'tvg-id': c.tvgId, 'tvg-name': c.tvgName, 'tvg-logo': c.logo, ...c.attrs, 'group-title': c.group };
    const duration = Number.isFinite(c.duration) ? c.duration : -1;
    out.push(`#EXTINF:${duration}${attrString(attrs)},${String(c.name).replace(/[\r\n]+/g, ' ')}`);
    for (const o of c.opts || []) out.push('#EXTVLCOPT:' + o);
    out.push(c.url);
  }
  return out.join('\n') + '\n';
}

// Groups in order of first appearance; ungrouped channels have name ''.
export function groupsOf(channels) {
  const counts = new Map();
  for (const c of channels) counts.set(c.group || '', (counts.get(c.group || '') || 0) + 1);
  return [...counts].map(([name, count]) => ({ name, count }));
}

export function dedupe(channels) {
  const seen = new Set();
  return channels.filter(c => !seen.has(c.url) && seen.add(c.url));
}
