// Live transcoding: repackages a raw MPEG-TS IPTV stream into HLS with ffmpeg so
// Safari (iPhone) can play it in the browser. Video is copied (no re-encode, light
// CPU); audio is converted to AAC so MP2/AC3 soundtracks play. One ffmpeg process
// per distinct stream, killed after it goes idle.
//
//   GET /hls?url=<stream>&key=<PROXY_KEY>   -> 302 to /hls/<id>/index.m3u8
//   GET /hls/<id>/<file>                    -> the generated playlist / segments

import { spawn } from 'node:child_process';
import { promises as fs } from 'node:fs';
import { join, sep, extname } from 'node:path';
import { tmpdir } from 'node:os';
import { isIP } from 'node:net';

const FFMPEG = process.env.FFMPEG || 'C:\\ffmpeg\\ffmpeg.exe';
const PROXY_KEY = process.env.PROXY_KEY || '';
const ALLOW_PRIVATE = process.env.ALLOW_PRIVATE === '1';
const ROOT = join(tmpdir(), 'm3u-hls');
const IDLE_MS = 30_000;        // kill ffmpeg this long after the last segment fetch
const MAX_SESSIONS = 4;        // concurrent transcodes
const START_TIMEOUT_MS = 25_000;

const TYPES = {
  '.m3u8': 'application/vnd.apple.mpegurl',
  '.ts': 'video/mp2t', '.m4s': 'video/iso.segment', '.mp4': 'video/mp4',
};

const sessions = new Map(); // id -> { proc, dir, lastAccess, exited, errTail }

function isPrivateHost(hostname) {
  const h = hostname.replace(/^\[|\]$/g, '').toLowerCase();
  if (h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h.endsWith('.internal')) return true;
  if (isIP(h) === 4) {
    const [a, b] = h.split('.').map(Number);
    return a === 0 || a === 10 || a === 127 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || a === 100;
  }
  if (isIP(h) === 6) return h === '::1' || h === '::' || /^(fc|fd|fe80)/.test(h) || h.startsWith('::ffff:');
  return false;
}

function sessionId(url) {
  let h = 5381;
  for (let i = 0; i < url.length; i++) h = ((h * 33) ^ url.charCodeAt(i)) >>> 0;
  return h.toString(36);
}

function send(res, status, body, headers = {}) {
  res.writeHead(status, { 'content-type': 'text/plain; charset=utf-8', 'access-control-allow-origin': '*', ...headers });
  res.end(body);
}

async function startSession(id, target) {
  const dir = join(ROOT, id);
  await fs.mkdir(dir, { recursive: true });
  for (const f of await fs.readdir(dir).catch(() => [])) await fs.rm(join(dir, f), { force: true }).catch(() => {});
  const args = [
    '-hide_banner', '-loglevel', 'error', '-nostdin',
    '-user_agent', 'Mozilla/5.0', '-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_delay_max', '2',
    '-probesize', '1000000', '-analyzeduration', '1000000',   // open the input fast (don't over-probe a known TS)
    '-fflags', '+genpts+nobuffer', '-i', target,
    '-map', '0:v:0?', '-map', '0:a:0?',
    '-c:v', 'copy', '-c:a', 'aac', '-b:a', '128k', '-ac', '2',
    '-f', 'hls', '-hls_time', '1', '-hls_list_size', '10',     // 1s segments start quicker
    '-hls_flags', 'delete_segments+append_list+omit_endlist', '-hls_segment_type', 'mpegts',
    '-hls_segment_filename', join(dir, 'seg%d.ts'), join(dir, 'index.m3u8'),
  ];
  const proc = spawn(FFMPEG, args, { windowsHide: true });
  const session = { proc, dir, lastAccess: Date.now(), exited: false, errTail: '' };
  proc.stderr.on('data', d => { session.errTail = (session.errTail + d).slice(-1500); });
  proc.on('exit', () => { session.exited = true; });
  proc.on('error', () => { session.exited = true; });
  sessions.set(id, session);
  return session;
}

function stopSession(id) {
  const s = sessions.get(id);
  if (!s) return;
  sessions.delete(id);
  try { s.proc.kill(); } catch {}
  fs.rm(s.dir, { recursive: true, force: true }).catch(() => {});
}

setInterval(() => {
  const now = Date.now();
  for (const [id, s] of sessions) if (now - s.lastAccess > IDLE_MS) stopSession(id);
}, 10_000).unref();

async function waitForPlaylist(dir, timeout) {
  const file = join(dir, 'index.m3u8');
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try {
      const t = await fs.readFile(file, 'utf8');
      if ((t.match(/\.ts/g) || []).length >= 2) return true;   // a couple of segments buffered
    } catch {}
    await new Promise(r => setTimeout(r, 250));
  }
  return false;
}

export async function handleHls(req, res, url) {
  const parts = url.pathname.split('/').filter(Boolean); // ['hls'] or ['hls', id, file]

  // serve a generated playlist or segment
  if (parts.length === 3) {
    const [, id, name] = parts;
    const s = sessions.get(id);
    if (!s) return send(res, 404, 'Stream not running');
    if (!/^[\w.-]+$/.test(name)) return send(res, 400, 'Bad name');
    s.lastAccess = Date.now();
    const f = join(s.dir, name);
    if (!f.startsWith(s.dir + sep)) return send(res, 400, 'Bad path');
    try {
      const data = await fs.readFile(f);
      res.writeHead(200, { 'content-type': TYPES[extname(name)] || 'application/octet-stream', 'access-control-allow-origin': '*', 'cache-control': 'no-store' });
      return res.end(req.method === 'HEAD' ? undefined : data);
    } catch { return send(res, 404, 'Not ready'); }
  }

  // start (or reuse) a transcode and redirect to its playlist
  const target = url.searchParams.get('url');
  const key = url.searchParams.get('key') || '';
  if (!target) return send(res, 400, 'Missing url');
  if (PROXY_KEY && key !== PROXY_KEY) return send(res, 403, 'Wrong or missing proxy key');
  let t;
  try { t = new URL(target); } catch { return send(res, 400, 'Bad url'); }
  if (!/^https?:$/.test(t.protocol)) return send(res, 400, 'Only http and https links');
  if (!ALLOW_PRIVATE && isPrivateHost(t.hostname)) return send(res, 403, 'Private addresses are blocked');

  const id = sessionId(t.href);
  console.log(`[hls] start ${id} ${t.href.slice(0, 120)}`);
  let s = sessions.get(id);
  if (s && s.exited) { stopSession(id); s = null; }
  if (!s) {
    if (sessions.size >= MAX_SESSIONS) return send(res, 503, 'Too many streams in progress — stop one and retry');
    s = await startSession(id, t.href);
  }
  s.lastAccess = Date.now();

  const ok = await waitForPlaylist(s.dir, START_TIMEOUT_MS);
  if (!ok) {
    const tail = (s.errTail || '').split('\n').filter(Boolean).pop() || 'the stream did not start';
    console.log(`[hls] FAIL ${id}: ${tail}`);
    stopSession(id);
    return send(res, 502, `Could not start stream: ${tail}`);
  }
  console.log(`[hls] ready ${id}`);
  let manifest;
  try { manifest = await fs.readFile(join(s.dir, 'index.m3u8'), 'utf8'); }
  catch { return send(res, 502, 'Stream not ready'); }
  // Serve the live playlist directly — iOS's native player doesn't reliably follow a redirect —
  // with each segment path made absolute to this session.
  const body = manifest.split('\n').map(line => {
    const t = line.trim();
    return (!t || t.startsWith('#')) ? line : `/hls/${id}/${t}`;
  }).join('\n');
  res.writeHead(200, { 'content-type': 'application/vnd.apple.mpegurl', 'access-control-allow-origin': '*', 'cache-control': 'no-store' });
  res.end(req.method === 'HEAD' ? undefined : body);
}
