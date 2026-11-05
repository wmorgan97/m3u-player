// Turns the owner's folder of pictures into the ready-made profile pictures the apps offer (server/avatar.js).
//
//   node scripts/import-avatars.mjs "<source folder>" [<presets dir>]     (default: data/avatars/presets)
//
// Each subfolder is a themed row titled with its name ("Creatures", "Kids", "Robots", …); pictures directly in the
// folder go in a "More" row. Rows are in name order, except "Creatures" first and "More" last.
// Every picture is made to look like part of one set: a white or transparent border around the subject is trimmed
// (a circle drawn on a white canvas becomes just the circle), anything transparent sits on the same soft dark
// backdrop, and it's cropped square around the subject and saved as a 512px JPEG with no metadata:
// presets/<theme>/<id>.jpg, with index.json listing the rows. The id is a hash of the source file, so running it again
// only does new pictures; pictures (and rows) whose source has gone are removed. JPEG / PNG / WebP / HEIC.
// Copy the presets dir to the server's data/avatars/presets. Needs ffmpeg (FFMPEG=…); HEIC on a Mac via `sips` if needed.

import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, renameSync, mkdtempSync, statSync } from 'node:fs';
import { join, extname, basename, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const FFMPEG = process.env.FFMPEG || 'ffmpeg', SIZE = 512, MAX_IN = 1024;
const PICS = /\.(jpe?g|png|webp|heic|heif)$/i;
// The shared backdrop: #2B303C at the centre to #151820 at the edge.
const BG_IN = [0x2b, 0x30, 0x3c], BG_OUT = [0x15, 0x18, 0x20];

/** Decodes any picture to RGBA (at most 1024px a side) through ffmpeg's PAM output. */
export function decode(path) {
  const r = spawnSync(FFMPEG, ['-hide_banner', '-loglevel', 'error', '-nostdin', '-i', path, '-frames:v', '1',
    '-vf', `scale='min(${MAX_IN},iw)':'min(${MAX_IN},ih)':force_original_aspect_ratio=decrease,format=rgba`, '-c:v', 'pam', '-f', 'image2pipe', '-'], { maxBuffer: 64 << 20 });
  if (r.status !== 0 || !r.stdout?.length) return null;
  const buf = r.stdout, end = buf.indexOf('ENDHDR\n');
  if (end < 0) return null;
  const head = buf.toString('latin1', 0, end);
  const w = +head.match(/WIDTH (\d+)/)?.[1], h = +head.match(/HEIGHT (\d+)/)?.[1], depth = +head.match(/DEPTH (\d+)/)?.[1];
  if (!w || !h || depth !== 4) return null;
  return { w, h, px: buf.subarray(end + 7, end + 7 + w * h * 4) };
}

/** Makes a white / near-white background that touches the edges transparent (flood fill from the border). */
function clearWhiteBorder({ w, h, px }) {
  const white = i => px[i + 3] > 200 && px[i] > 232 && px[i + 1] > 232 && px[i + 2] > 232;
  const seen = new Uint8Array(w * h), stack = [];
  const push = (x, y) => { const k = y * w + x; if (!seen[k] && white(k * 4)) { seen[k] = 1; stack.push(k); } };
  for (let x = 0; x < w; x++) { push(x, 0); push(x, h - 1); }
  for (let y = 0; y < h; y++) { push(0, y); push(w - 1, y); }
  while (stack.length) {
    const k = stack.pop(), x = k % w, y = (k - x) / w;
    px[k * 4 + 3] = 0;
    if (x > 0) push(x - 1, y); if (x < w - 1) push(x + 1, y); if (y > 0) push(x, y - 1); if (y < h - 1) push(x, y + 1);
  }
}

/** The square (in source pixels) to keep: around the subject when the background was trimmed, else the centre. */
export function subjectSquare(img) {
  const { w, h, px } = img;
  const corners = [[0, 0], [w - 1, 0], [0, h - 1], [w - 1, h - 1]].map(([x, y]) => (y * w + x) * 4);
  const clear = corners.filter(i => px[i + 3] < 24).length >= 2;
  const whiteBg = !clear && corners.filter(i => px[i] > 232 && px[i + 1] > 232 && px[i + 2] > 232).length >= 3;
  if (whiteBg) clearWhiteBorder(img);
  if (!clear && !whiteBg) { const s = Math.min(w, h); return { x: (w - s) / 2, y: (h - s) / 2, s, trimmed: false }; }
  let x0 = w, y0 = h, x1 = -1, y1 = -1;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) if (px[(y * w + x) * 4 + 3] > 24) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
  if (x1 < 0) { const s = Math.min(w, h); return { x: (w - s) / 2, y: (h - s) / 2, s, trimmed: true }; }
  const s = Math.max(x1 - x0 + 1, y1 - y0 + 1) * 1.15;   // room round the subject, so it sits inside the circle
  return { x: (x0 + x1 + 1) / 2 - s / 2, y: (y0 + y1 + 1) / 2 - s / 2, s, trimmed: true };
}

/** 512×512 RGB: the square of the picture over the shared backdrop (bilinear; outside the picture = backdrop). */
export function render(img, sq) {
  const { w, h, px } = img, out = Buffer.alloc(SIZE * SIZE * 3), k = sq.s / SIZE;
  const at = (x, y, c) => px[(y * w + x) * 4 + c];
  for (let oy = 0; oy < SIZE; oy++) for (let ox = 0; ox < SIZE; ox++) {
    const d = Math.min(1, Math.hypot(ox - SIZE / 2 + 0.5, oy - SIZE / 2 + 0.5) / (SIZE / 2));
    const t = d * d;
    const bg = [0, 1, 2].map(c => BG_IN[c] + (BG_OUT[c] - BG_IN[c]) * t);
    const sx = sq.x + (ox + 0.5) * k - 0.5, sy = sq.y + (oy + 0.5) * k - 0.5;
    const xa = Math.floor(sx), ya = Math.floor(sy), fx = sx - xa, fy = sy - ya;
    let r = 0, g = 0, b = 0, a = 0;
    for (const [dx, dy, wt] of [[0, 0, (1 - fx) * (1 - fy)], [1, 0, fx * (1 - fy)], [0, 1, (1 - fx) * fy], [1, 1, fx * fy]]) {
      const x = xa + dx, y = ya + dy;
      if (x < 0 || y < 0 || x >= w || y >= h || !wt) continue;
      const al = at(x, y, 3) / 255 * wt;
      r += at(x, y, 0) * al; g += at(x, y, 1) * al; b += at(x, y, 2) * al; a += al;
    }
    const o = (oy * SIZE + ox) * 3;
    out[o] = Math.round(r + bg[0] * (1 - a)); out[o + 1] = Math.round(g + bg[1] * (1 - a)); out[o + 2] = Math.round(b + bg[2] * (1 - a));
  }
  return out;
}

function encode(rgb, dest) {
  return spawnSync(FFMPEG, ['-hide_banner', '-loglevel', 'error', '-nostdin', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', `${SIZE}x${SIZE}`, '-i', '-',
    '-frames:v', '1', '-map_metadata', '-1', '-q:v', '3', '-c:v', 'mjpeg', '-f', 'image2', '-y', dest], { input: rgb, stdio: ['pipe', 'ignore', 'pipe'] }).status === 0;
}

function convert(path, dest) {
  let img = decode(path);
  if (!img && /\.hei[cf]$/i.test(path) && process.platform === 'darwin') {   // HEIC ffmpeg can't read: macOS decodes it
    const dir = mkdtempSync(join(tmpdir(), 'avatar-'));
    const png = join(dir, 'in.png');
    if (spawnSync('sips', ['-s', 'format', 'png', path, '--out', png], { stdio: 'ignore' }).status === 0) img = decode(png);
    rmSync(dir, { recursive: true, force: true });
  }
  if (!img) return false;
  return encode(render(img, subjectSquare(img)), dest);
}

const slug = name => name.normalize('NFKD').replace(/[^\w -]+/g, '').trim().replace(/\s+/g, '-').toLowerCase().slice(0, 40) || 'more';
const byName = (a, b) => a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
/** Row order: "Creatures" first, "More" last, the rest by name. */
export const rowOrder = names => [...names].sort((a, b) => {
  const rank = n => (/^creatures$/i.test(n) ? 0 : /^more$/i.test(n) ? 2 : 1);
  return rank(a) - rank(b) || byName(a, b);
});

export function importAvatars(src, out) {
  mkdirSync(out, { recursive: true });
  const groups = new Map();
  for (const f of readdirSync(src).filter(f => !f.startsWith('.')).sort(byName)) {
    const p = join(src, f);
    if (statSync(p).isDirectory()) {
      const files = readdirSync(p).filter(x => !x.startsWith('.') && PICS.test(x)).sort(byName).map(x => join(p, x));
      if (files.length) groups.set(f, [...(groups.get(f) || []), ...files]);
    } else if (PICS.test(f)) groups.set('More', [...(groups.get('More') || []), p]);
  }
  const index = [], keep = new Set(), seen = new Set();
  let made = 0, kept = 0, failed = 0;
  for (const theme of rowOrder([...groups.keys()])) {
    const dir = slug(theme), items = [];
    mkdirSync(join(out, dir), { recursive: true });
    for (const path of groups.get(theme)) {
      const id = createHash('sha1').update(readFileSync(path)).digest('hex').slice(0, 12);
      if (seen.has(id)) continue;                                  // the same picture twice
      const dest = join(out, dir, `${id}.jpg`);
      if (existsSync(dest)) kept++;
      else {
        const tmp = `${dest}.tmp`;
        if (!convert(path, tmp)) { rmSync(tmp, { force: true }); console.warn(`skipped ${path} (couldn't read it)`); failed++; continue; }
        renameSync(tmp, dest);
        made++;
      }
      seen.add(id); keep.add(`${dir}/${id}.jpg`);
      items.push({ id, name: basename(path, extname(path)) });
    }
    if (items.length) index.push({ theme, dir, items });
  }
  let removed = 0;
  for (const d of readdirSync(out)) {
    const p = join(out, d);
    if (!statSync(p).isDirectory()) continue;
    for (const f of readdirSync(p)) if (!keep.has(`${d}/${f}`)) { rmSync(join(p, f), { force: true }); if (f.endsWith('.jpg')) removed++; }
    if (!readdirSync(p).length) rmSync(p, { recursive: true, force: true });
  }
  writeFileSync(join(out, 'index.json'), JSON.stringify(index, null, 1));
  const total = index.reduce((n, r) => n + r.items.length, 0);
  return `${total} pictures in ${index.length} rows in ${out}: ${made} new, ${kept} unchanged, ${removed} removed${failed ? `, ${failed} skipped` : ''}`;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  const [src, out0] = process.argv.slice(2);
  if (!src) { console.error('Usage: node scripts/import-avatars.mjs "<source folder>" [<presets dir>]'); process.exit(1); }
  console.log(importAvatars(src, out0 || fileURLToPath(new URL('../data/avatars/presets/', import.meta.url))));
}
