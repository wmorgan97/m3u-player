// Poster and artwork thumbnails. Providers serve posters at full size (often 1000-2000px, 200KB-1MB each);
// a phone scrolling a shelf of 30 of them stutters decoding them. This shrinks each image once to the width
// the app asks for (ffmpeg, already on the server), keeps it on disk, and serves it with a long cache life,
// so every later view (and every other device) gets a small JPEG instantly.
//
//   GET /img?key=<PROXY_KEY>&w=<120-1280>&u=<image url>   -> image/jpeg (or a redirect to the original on failure)

import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { promises as fs, createReadStream } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const PROXY_KEY = process.env.PROXY_KEY || '';
const FFMPEG = process.env.FFMPEG || 'C:\\ffmpeg\\ffmpeg.exe';
const DIR = fileURLToPath(new URL('../data/img/', import.meta.url));
const WIDTHS = [120, 200, 300, 400, 500, 800, 1280];
const MAX_JOBS = 6;

let running = 0;
const waiting = [];
const inflight = new Map();   // cache file -> Promise<boolean>

function slot() {
  if (running < MAX_JOBS) { running++; return Promise.resolve(); }
  return new Promise(r => waiting.push(r));
}
function release() { const next = waiting.shift(); if (next) next(); else running--; }

function shrink(src, w, out) {
  return new Promise(resolve => {
    const tmp = `${out}.${process.pid}.tmp`;
    const p = spawn(FFMPEG, ['-hide_banner', '-loglevel', 'error', '-nostdin', '-user_agent', 'Mozilla/5.0', '-rw_timeout', '15000000',
      '-i', src, '-frames:v', '1', '-vf', `scale='min(${w},iw)':-2:flags=lanczos`, '-q:v', '4', '-f', 'image2', '-c:v', 'mjpeg', '-y', tmp],
      { windowsHide: true });
    const timer = setTimeout(() => { try { p.kill(); } catch {} }, 20_000);
    p.on('error', () => { clearTimeout(timer); resolve(false); });
    p.on('exit', async code => {
      clearTimeout(timer);
      if (code !== 0) { fs.rm(tmp, { force: true }).catch(() => {}); return resolve(false); }
      try { await fs.rename(tmp, out); resolve(true); } catch { resolve(false); }
    });
  });
}

export async function handleImg(req, res, url, send, allowed) {
  if (PROXY_KEY && url.searchParams.get('key') !== PROXY_KEY) return send(res, 403, 'Wrong or missing key');
  let src;
  try { src = new URL(url.searchParams.get('u') || ''); } catch { return send(res, 400, 'Bad image url'); }
  if (!/^https?:$/.test(src.protocol) || !allowed(src)) return send(res, 400, 'Image url not allowed');
  const asked = Number(url.searchParams.get('w')) || 300;
  const w = WIDTHS.find(x => x >= asked) || 1280;
  const file = join(DIR, `${createHash('sha1').update(`${src.href}|${w}`).digest('hex')}.jpg`);
  let ok = await fs.stat(file).then(() => true, () => false);
  if (!ok) {
    if (!inflight.has(file)) {
      inflight.set(file, (async () => {
        await fs.mkdir(DIR, { recursive: true });
        await slot();
        try { return await shrink(src.href, w, file); } finally { release(); }
      })().finally(() => setTimeout(() => inflight.delete(file), 1000)));
    }
    ok = await inflight.get(file);
  }
  if (!ok) { res.writeHead(302, { location: src.href, 'cache-control': 'public, max-age=3600' }); return res.end(); }   // the browser loads the original instead
  const st = await fs.stat(file);
  res.writeHead(200, { 'content-type': 'image/jpeg', 'content-length': st.size, 'cache-control': 'public, max-age=2592000, immutable', 'access-control-allow-origin': '*' });
  if (req.method === 'HEAD') return res.end();
  createReadStream(file).on('error', () => res.destroy()).pipe(res);
}
