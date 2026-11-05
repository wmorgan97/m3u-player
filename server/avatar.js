// Profile pictures ("Who's watching?"). The phone crops and shrinks a photo itself (~512px JPEG), then uploads it here;
// the picture is stored once per profile and every device gets it through the profile list, which carries a versioned
// path (profiles[id].avatar = "/avatar/<id>?v=<hash>"), so a new picture is a new URL and the old one can be cached
// for good. Devices ask for the size they draw (w=…), made once by the image resizer (server/img.js).
//
//   POST /avatar/<id>?key=<PROXY_KEY>            body: the image (JPEG, PNG or WebP, at most 1 MB) -> { avatar, profile }
//   POST /avatar/<id>?key=…&remove=1             back to the initial on its colour -> { avatar: null, profile }
//   GET  /avatar/<id>?key=…&v=<hash>[&w=<px>]    the picture (cached for a year when v is the current one)
//   GET  /avatars?key=…                          the owner's ready-made pictures, in rows: [{ theme, items: [{ id, url }] }]
//   GET  /avatar/preset/<id>?key=…[&w=<px>]      one of them (the id is its content hash: cached for a year)
//
// Stored as data/avatars/<id>.<jpg|png|webp>, with EXIF / XMP / text metadata (camera, place, time) taken out.
// Ready-made pictures: data/avatars/presets/<theme>/<id>.jpg (square, 512px) plus index.json for the rows, written by
// scripts/import-avatars.mjs from a folder of the owner's pictures. None there: devices offer colour + initial only.

import { promises as fs, createReadStream } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ensure } from './img.js';
import { setProfileAvatar, storedProfiles, profileId, MAIN } from './sync.js';

const PROXY_KEY = process.env.PROXY_KEY || '';
const DIR = join(process.env.SYNC_DATA_DIR || fileURLToPath(new URL('../data/', import.meta.url)), 'avatars');
export const MAX_AVATAR = 1 << 20;
const EXTS = ['jpg', 'png', 'webp'], TYPES = { jpg: 'image/jpeg', png: 'image/png', webp: 'image/webp' };

/** The picture's format from its first bytes (never from what the request says it is). */
export function sniff(b) {
  if (b.length > 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'jpg';
  if (b.length > 8 && b.readUInt32BE(0) === 0x89504e47 && b.readUInt32BE(4) === 0x0d0a1a0a) return 'png';
  if (b.length > 12 && b.toString('latin1', 0, 4) === 'RIFF' && b.toString('latin1', 8, 12) === 'WEBP') return 'webp';
  return null;
}

/** The same picture without its metadata (EXIF, XMP, comments, text chunks). Null if the file is broken. */
export function stripMeta(b, ext) {
  try {
    if (ext === 'jpg') {
      const out = [b.subarray(0, 2)];
      let i = 2;
      while (i < b.length) {
        if (b[i] !== 0xff) return null;
        const m = b[i + 1];
        if (m === 0xff) { i++; continue; }                                    // fill byte
        if (m === 0xd9 || m === 0xda) { out.push(b.subarray(i)); return Buffer.concat(out); }   // end / image data: the rest as is
        if ((m >= 0xd0 && m <= 0xd7) || m === 0x01) { out.push(b.subarray(i, i + 2)); i += 2; continue; }
        const len = b.readUInt16BE(i + 2);
        if (len < 2 || i + 2 + len > b.length) return null;
        // APP1 (EXIF / XMP), APP3-APP15 and comments go; APP0 (JFIF) and APP2 (colour profile) stay.
        if (!((m >= 0xe1 && m <= 0xef && m !== 0xe2) || m === 0xfe)) out.push(b.subarray(i, i + 2 + len));
        i += 2 + len;
      }
      return null;
    }
    if (ext === 'png') {
      const DROP = new Set(['eXIf', 'tEXt', 'zTXt', 'iTXt', 'tIME']);
      const out = [b.subarray(0, 8)];
      let i = 8, end = false;
      while (i + 12 <= b.length) {
        const len = b.readUInt32BE(i), type = b.toString('latin1', i + 4, i + 8);
        if (i + 12 + len > b.length) return null;
        if (!DROP.has(type)) out.push(b.subarray(i, i + 12 + len));
        i += 12 + len;
        if (type === 'IEND') { end = true; break; }
      }
      return end ? Buffer.concat(out) : null;
    }
    if (ext === 'webp') {
      const out = [];
      let i = 12, vp8x = null;
      while (i + 8 <= b.length) {
        const type = b.toString('latin1', i, i + 4), len = b.readUInt32LE(i + 4), size = 8 + len + (len & 1);
        if (i + 8 + len > b.length) return null;
        if (type !== 'EXIF' && type !== 'XMP ') {
          const c = Buffer.from(b.subarray(i, Math.min(b.length, i + size)));
          if (type === 'VP8X') { c[8] &= ~0x0c; vp8x = c; }                     // no EXIF / XMP flags any more
          out.push(c);
        }
        i += size;
      }
      if (!out.length || (vp8x && out.length < 2)) return null;
      const body = Buffer.concat(out), head = Buffer.alloc(12);
      head.write('RIFF', 0, 'latin1'); head.writeUInt32LE(body.length + 4, 4); head.write('WEBP', 8, 'latin1');
      return Buffer.concat([head, body]);
    }
  } catch {}
  return null;
}

function readBuf(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0, over = false;
    req.on('data', c => { if (over) return; size += c.length; if (size > limit) { over = true; resolve(null); } else chunks.push(c); });
    req.on('end', () => { if (!over) resolve(Buffer.concat(chunks)); });
    req.on('error', reject);
  });
}
async function current(id) {
  for (const ext of EXTS) { const f = join(DIR, `${id}.${ext}`); if (await fs.stat(f).then(() => true, () => false)) return { file: f, ext }; }
  return null;
}
const PRESETS = join(DIR, 'presets');
/** The ready-made pictures in themed rows: [{ theme, items: [{ id, url }] }] (presets/index.json, from the import script). */
let presetMemo = { mtime: -1, rows: [], where: new Map() };
async function presetIndex() {
  const st = await fs.stat(join(PRESETS, 'index.json')).catch(() => null);
  if (!st) return { rows: [], where: new Map() };
  if (st.mtimeMs !== presetMemo.mtime) {
    let raw = [];
    try { raw = JSON.parse(await fs.readFile(join(PRESETS, 'index.json'), 'utf8')); } catch {}
    const rows = [], where = new Map();
    for (const r of Array.isArray(raw) ? raw : []) {
      if (!/^[\w-]{1,40}$/.test(r?.dir || '')) continue;
      const items = [];
      for (const it of r.items || []) if (/^[0-9a-f]{6,40}$/.test(it?.id || '')) { items.push({ id: it.id, url: `/avatar/preset/${it.id}` }); where.set(it.id, join(PRESETS, r.dir, `${it.id}.jpg`)); }
      if (items.length) rows.push({ theme: String(r.theme || 'More').slice(0, 40), items });
    }
    presetMemo = { mtime: st.mtimeMs, rows, where };
  }
  return presetMemo;
}
export async function presetList() { return (await presetIndex()).rows; }
async function sendPicture(req, res, url, file, type, cache, ver) {
  const w = Math.max(0, Math.min(1280, Number(url.searchParams.get('w')) || 0));
  if (w) { const small = await ensure(file, w, false, ver); if (small) { file = small; type = 'image/jpeg'; } }   // no ffmpeg: the original
  const st = await fs.stat(file);
  res.writeHead(200, { 'content-type': type, 'content-length': st.size, 'cache-control': cache, 'access-control-allow-origin': '*', 'x-content-type-options': 'nosniff' });
  if (req.method === 'HEAD') return res.end();
  createReadStream(file).on('error', () => res.destroy()).pipe(res);
}
const removeFiles = (id, keep) => Promise.all(EXTS.filter(e => e !== keep).map(e => fs.rm(join(DIR, `${id}.${e}`), { force: true })));

export async function handleAvatar(req, res, url, send) {
  if (PROXY_KEY && url.searchParams.get('key') !== PROXY_KEY) return send(res, 403, 'Wrong or missing key');
  const json0 = (code, o) => send(res, code, JSON.stringify(o), { 'content-type': 'application/json', 'cache-control': 'no-store' });
  if (url.pathname === '/avatars') return json0(200, await presetList());
  const pm = url.pathname.match(/^\/avatar\/preset\/([0-9a-f]{6,40})$/);
  if (pm) {
    if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, 'Method not allowed');
    const file = (await presetIndex()).where.get(pm[1]);
    if (!file || !await fs.stat(file).then(() => true, () => false)) return send(res, 404, 'No such picture');
    return sendPicture(req, res, url, file, 'image/jpeg', 'public, max-age=31536000, immutable', pm[1]);
  }
  const raw = decodeURIComponent(url.pathname.slice('/avatar/'.length));
  const id = profileId(raw);
  if (id !== raw) return send(res, 404, 'No such profile');
  const profiles = await storedProfiles(), prof = profiles[id];
  const exists = id === MAIN || (prof && !prof.deleted);
  const json = (code, o) => send(res, code, JSON.stringify(o), { 'content-type': 'application/json', 'cache-control': 'no-store' });

  if (req.method === 'POST') {
    if (!exists) { req.resume(); return json(404, { error: 'No such profile' }); }
    if (url.searchParams.get('remove') === '1') {
      req.resume();
      await removeFiles(id);
      return json(200, { avatar: null, profile: await setProfileAvatar(id, null) });
    }
    if (+req.headers['content-length'] > MAX_AVATAR) { req.resume(); return json(413, { error: 'Pictures can be at most 1 MB' }); }
    const body = await readBuf(req, MAX_AVATAR);
    if (!body) return json(413, { error: 'Pictures can be at most 1 MB' });
    const ext = sniff(body);
    if (!ext) return json(415, { error: 'Only JPEG, PNG or WebP pictures' });
    const clean = stripMeta(body, ext);
    if (!clean) return json(400, { error: 'That picture couldn’t be read' });
    const hash = createHash('sha1').update(clean).digest('hex').slice(0, 12);
    await fs.mkdir(DIR, { recursive: true });
    const file = join(DIR, `${id}.${ext}`);
    await fs.writeFile(`${file}.tmp`, clean);
    await fs.rename(`${file}.tmp`, file);
    await removeFiles(id, ext);
    const avatar = `/avatar/${id}?v=${hash}`;
    return json(200, { avatar, profile: await setProfileAvatar(id, avatar) });
  }

  if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, 'Method not allowed');
  const cur = exists ? await current(id) : null;
  const ver = (prof?.avatar || '').split('?v=')[1] || '';
  if (!cur || !ver) return send(res, 404, 'No picture', { 'cache-control': 'no-cache' });
  // The current version's URL never changes content: cache it for a year. An old version gets today's picture, briefly.
  const cache = url.searchParams.get('v') === ver ? 'public, max-age=31536000, immutable' : 'public, max-age=60';
  return sendPicture(req, res, url, cur.file, TYPES[cur.ext], cache, ver);
}
