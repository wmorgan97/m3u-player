// Live transcoding: repackages a raw MPEG-TS IPTV stream into HLS with ffmpeg so
// Safari (iPhone) can play it in the browser. Video is copied (no re-encode, light
// CPU); audio is converted to AAC so MP2/AC3 soundtracks play. One ffmpeg process
// per distinct stream, killed after it goes idle.
//
//   GET /hls?url=<stream>&key=<PROXY_KEY>   -> 302 to /hls/<id>/index.m3u8
//   GET /hls/<id>/<file>                    -> the generated playlist / segments

import { spawn, execFile } from 'node:child_process';
import { promises as fs, createReadStream, rmSync, mkdirSync } from 'node:fs';
import { join, sep, extname } from 'node:path';
import { tmpdir } from 'node:os';
import { isIP } from 'node:net';
import { createHmac } from 'node:crypto';

const FFMPEG = process.env.FFMPEG || 'C:\\ffmpeg\\ffmpeg.exe';
const FFPROBE = process.env.FFPROBE || FFMPEG.replace(/ffmpeg(\.exe)?$/i, 'ffprobe$1');
const PROXY_KEY = process.env.PROXY_KEY || '';
const ALLOW_PRIVATE = process.env.ALLOW_PRIVATE === '1';
const ROOT = join(tmpdir(), 'm3u-hls');
const IDLE_MS = 20_000;        // kill ffmpeg this long after the last segment fetch
const MAX_SESSIONS = 3;        // concurrent transcodes: IPTV accounts allow few provider connections
const START_TIMEOUT_MS = 25_000;
// Re-encoding forces a keyframe every second (fast starts on huge-GOP sources) but costs ~4 cores and is SLOWER
// for 1080p50 sports feeds; copy mode measured 0.9-2.1s cold on this provider. Opt in with TRANSCODE_VIDEO=1.
const REENCODE = process.env.TRANSCODE_VIDEO === '1';
const READY_SEGMENTS = REENCODE ? 2 : 1;   // copy-mode segments are keyframe-aligned (2-4s): one is buffer enough
const START_BEHIND_LIVE_S = REENCODE ? 3 : 4; // start playback this far behind the live edge so there's always buffer in hand

const TYPES = {
  '.m3u8': 'application/vnd.apple.mpegurl',
  '.ts': 'video/mp2t', '.m4s': 'video/iso.segment', '.mp4': 'video/mp4',
};

const sessions = new Map(); // id -> { proc, dir, lastAccess, exited, errTail, startedAt }
const starting = new Map(); // id -> promise of the session: a warm + play for the same channel share one start
try { rmSync(ROOT, { recursive: true, force: true }); mkdirSync(ROOT, { recursive: true }); } catch {}

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

// Session ids are an HMAC of the stream url with the server key: segment paths can't be guessed,
// so /hls/<id>/<file> needs no key in the manifest the native player fetches.
function sessionId(url) {
  return createHmac('sha256', PROXY_KEY || 'm3u').update(url).digest('base64url').slice(0, 22);
}

function send(res, status, body, headers = {}) {
  res.writeHead(status, { 'content-type': 'text/plain; charset=utf-8', 'access-control-allow-origin': '*', ...headers });
  res.end(body);
}

// VOD: the real length of a film/episode, read from the file header (cached). The app draws its timeline from
// this, and seeks by asking for a new session that starts at that point (-ss), so every position is reachable
// at once instead of only what has been remuxed so far.
const durations = new Map();
function probeDuration(target) {
  if (durations.has(target)) return durations.get(target);
  const job = new Promise(resolve => {
    execFile(FFPROBE, ['-v', 'error', '-user_agent', 'Mozilla/5.0', '-show_entries', 'format=duration', '-of', 'csv=p=0', target],
      { timeout: 20_000, windowsHide: true }, (err, out) => resolve(err ? 0 : Number(String(out).trim()) || 0));
  });
  durations.set(target, job);
  job.then(d => { if (!d) durations.delete(target); });
  setTimeout(() => durations.delete(target), 3600_000).unref();
  return job;
}

async function startSession(id, target, vod = false, start = 0) {
  // A fresh folder per start, so restarting a crashed stream never races the previous folder's cleanup.
  const dir = join(ROOT, `${id}-${Date.now().toString(36)}`);
  await fs.mkdir(dir, { recursive: true });
  const args = [
    '-hide_banner', '-loglevel', 'error', '-nostdin',
    '-user_agent', 'Mozilla/5.0', '-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_at_eof', '1', '-reconnect_delay_max', '2',
    '-rw_timeout', '15000000',                                   // 15s: a dead upstream fails fast instead of hanging
    '-probesize', '500000', '-analyzeduration', '500000',     // open the input fast (don't over-probe a known TS)
    ...(vod && start > 0 ? ['-ss', String(start)] : []),   // VOD seek: begin remuxing from this point
    '-fflags', '+genpts+flush_packets', '-i', target,
    '-map', '0:v:0?', '-map', '0:a:0?',
    ...(REENCODE
      ? ['-c:v', 'libx264', '-preset', 'veryfast', '-tune', 'zerolatency', '-profile:v', 'high', '-level', '4.1', '-pix_fmt', 'yuv420p',
         '-g', '25', '-keyint_min', '25', '-sc_threshold', '0', '-crf', '22', '-maxrate', '5M', '-bufsize', '10M', '-threads', '4']
      : ['-c:v', 'copy']),
    '-c:a', 'aac', '-b:a', '128k', '-ac', '2',
    '-flush_packets', '1', '-muxdelay', '0',
    // ~45 minutes of segments stay on disk (delete_segments trims older ones): pause, rewind and
    // jump-back work in the app; the player still starts near the live edge (EXT-X-START).
    '-f', 'hls', '-hls_time', REENCODE ? '1' : '2', '-hls_init_time', '1',
    // Live: a rolling window. VOD (movies, episodes, catch-up): keep every segment and close the playlist
    // at the end, so the player gets a seekable timeline with a real duration instead of a "live" stream.
    ...(vod ? ['-hls_list_size', '0', '-hls_playlist_type', 'event', '-hls_flags', 'append_list+independent_segments', '-copyts', '-start_at_zero']
            : ['-hls_list_size', '1000', '-hls_flags', 'delete_segments+append_list+omit_endlist+independent_segments']),
    '-hls_segment_type', 'mpegts',
    '-hls_segment_filename', join(dir, 'seg%d.ts'), join(dir, 'index.m3u8'),
  ];
  const proc = spawn(FFMPEG, args, { windowsHide: true });
  const session = { proc, dir, lastAccess: Date.now(), exited: false, errTail: '', startedAt: Date.now(), vod, start };
  console.log(`[hls] start ${id} ${target.slice(0, 100)}`);
  proc.stderr.on('data', d => { session.errTail = (session.errTail + d).slice(-1500); });
  proc.on('exit', () => { session.exited = true; });
  proc.on('error', () => { session.exited = true; });
  sessions.set(id, session);
  return session;
}

function stopSession(id) {
  const s = sessions.get(id);
  if (!s) return Promise.resolve();
  sessions.delete(id);
  const exited = s.exited ? Promise.resolve() : new Promise(r => { s.proc.once('exit', r); setTimeout(r, 1500); });
  try { s.proc.kill(); } catch {}
  exited.then(() => fs.rm(s.dir, { recursive: true, force: true }).catch(() => {}));
  return exited;
}
// Providers allow one connection: before opening a new stream, close every other one and WAIT for the
// processes to exit so the old connection is really gone (rapid channel skipping otherwise trips rate limits).
async function closeOthers(keepId) {
  const others = [...sessions.keys()].filter(k => k !== keepId);
  if (others.length) { await Promise.all(others.map(stopSession)); await new Promise(r => setTimeout(r, 120)); }
}

setInterval(() => {
  const now = Date.now();
  for (const [id, s] of sessions) if (now - s.lastAccess > IDLE_MS) stopSession(id);
}, 10_000).unref();

async function waitForPlaylist(dir, timeout, alive = () => true, ready = READY_SEGMENTS) {
  const file = join(dir, 'index.m3u8');
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (!alive()) return false;   // the user moved on to another channel
    try {
      const t = await fs.readFile(file, 'utf8');
      if ((t.match(/\.ts/g) || []).length >= ready) return true;
    } catch {}
    await new Promise(r => setTimeout(r, 80));
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
    let st;
    try { st = await fs.stat(f); } catch { return send(res, 404, 'Not ready'); }
    res.writeHead(200, { 'content-type': TYPES[extname(name)] || 'application/octet-stream', 'content-length': st.size, 'access-control-allow-origin': '*', 'cache-control': 'no-store' });
    if (req.method === 'HEAD') return res.end();
    return createReadStream(f).on('error', () => res.destroy()).pipe(res);
  }

  // start (or reuse) a transcode and serve its playlist. /hls/warm just starts it (pre-warm) and returns.
  const warm = parts.length === 2 && parts[1] === 'warm';
  const stop = parts.length === 2 && parts[1] === 'stop';
  const info = parts.length === 2 && parts[1] === 'info';
  const target = url.searchParams.get('url');
  const key = url.searchParams.get('key') || '';
  if (!target) return send(res, 400, 'Missing url');
  if (stop) {   // the app switched away: free the provider connection right now instead of after the idle timeout
    if (PROXY_KEY && key !== PROXY_KEY) return send(res, 403, 'Wrong or missing proxy key');
    try {
      const href = new URL(target).href, st = Math.max(0, Math.floor(Number(url.searchParams.get('start')) || 0));
      stopSession(sessionId(st ? `${href}#${st}` : href));
    } catch {}
    return send(res, 204, '');
  }
  if (PROXY_KEY && key !== PROXY_KEY) return send(res, 403, 'Wrong or missing proxy key');
  let t;
  try { t = new URL(target); } catch { return send(res, 400, 'Bad url'); }
  if (!/^https?:$/.test(t.protocol)) return send(res, 400, 'Only http and https links');
  if (!ALLOW_PRIVATE && isPrivateHost(t.hostname)) return send(res, 403, 'Private addresses are blocked');
  if (info) return send(res, 200, JSON.stringify({ duration: await probeDuration(t.href) }), { 'content-type': 'application/json', 'cache-control': 'no-store' });

  const vod = url.searchParams.get('vod') === '1';
  const start = vod ? Math.max(0, Math.floor(Number(url.searchParams.get('start')) || 0)) : 0;
  const id = sessionId(start ? `${t.href}#${start}` : t.href);
  let s = sessions.get(id);
  if (s && !s.exited && !warm) {
    // Stalled: ffmpeg alive but the playlist hasn't been touched for a while -> upstream froze. Restart it.
    try {
      const m = await fs.stat(join(s.dir, 'index.m3u8'));
      if (!s.vod && Date.now() - m.mtimeMs > 12_000 && Date.now() - s.startedAt > 20_000) { console.log(`[hls] stalled ${id}, restarting`); s.exited = true; }
    } catch {}
  }
  if (s && s.exited) { stopSession(id); s = null; }
  if (!s) {
    // The app sends a warm on touch and the play a few ms later: both must wait for ONE start, or the
    // second ffmpeg is orphaned holding the only provider connection.
    if (!starting.has(id)) {
      starting.set(id, (async () => { await closeOthers(id); return startSession(id, t.href, vod, start); })().finally(() => starting.delete(id)));
    }
    s = await starting.get(id);
  }
  s.lastAccess = Date.now();
  if (warm) return send(res, 204, '');

  // Buffer level chosen by the app: how many segments before we hand over the playlist, and how far
  // behind the live edge to start. More = fewer stalls, slower channel change.
  const ready = Math.min(4, Math.max(1, Number(url.searchParams.get('ready')) || READY_SEGMENTS));
  const behind = Math.min(30, Math.max(2, Number(url.searchParams.get('behind')) || START_BEHIND_LIVE_S));
  const ok = await waitForPlaylist(s.dir, START_TIMEOUT_MS, () => sessions.get(id) === s, ready);
  if (sessions.get(id) !== s) return send(res, 409, 'Superseded by another channel');
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
  let body = manifest.split('\n').map(line => {
    const t = line.trim();
    return (!t || t.startsWith('#')) ? line : `/hls/${id}/${t}`;
  }).join('\n');
  // Start a few seconds behind the live edge so the player always has segments in hand (no edge-chasing rebuffers).
  // Live starts a few seconds behind the edge; VOD must start at the very beginning of the session, otherwise
  // Safari treats the still-growing playlist as live and jumps to its newest part.
  if (!/#EXT-X-START/.test(body)) body = body.replace('#EXTM3U', s.vod ? '#EXTM3U\n#EXT-X-START:TIME-OFFSET=0,PRECISE=YES' : `#EXTM3U\n#EXT-X-START:TIME-OFFSET=-${behind},PRECISE=NO`);
  res.writeHead(200, { 'content-type': 'application/vnd.apple.mpegurl', 'access-control-allow-origin': '*', 'cache-control': 'no-store' });
  res.end(req.method === 'HEAD' ? undefined : body);
}
