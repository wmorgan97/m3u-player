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
const VOD_SEG_S = 4;                     // VOD: fixed 4-second segments, so the whole playlist is known up front
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
const probes = new Map();   // target -> Promise<{ duration, video, audio: [], subs: [] }>
const probeDuration = target => probeFile(target).then(p => p.duration);
// Everything the app needs to know about a file: length, picture quality, audio tracks, text subtitles.
function probeFile(target) {
  if (probes.has(target)) return probes.get(target);
  const job = new Promise(resolve => {
    execFile(FFPROBE, ['-v', 'error', '-user_agent', 'Mozilla/5.0', '-show_entries',
      'format=duration:stream=index,codec_type,codec_name,width,height,color_transfer,profile,channels:stream_tags=language,title', '-of', 'json', target],
      { timeout: 25_000, windowsHide: true, maxBuffer: 4 << 20 }, (err, out) => {
        if (err) return resolve({ duration: 0, audio: [], subs: [] });
        let j; try { j = JSON.parse(out); } catch { return resolve({ duration: 0, audio: [], subs: [] }); }
        const streams = j.streams || [];
        const v = streams.find(x => x.codec_type === 'video');
        const name = x => [x.tags?.title, x.tags?.language].filter(Boolean).join(' · ');
        let ai = 0, si = 0;
        const audio = streams.filter(x => x.codec_type === 'audio').map(x => ({ i: ai++, label: name(x) || `Audio ${ai}`, lang: x.tags?.language || '', codec: x.codec_name, channels: x.channels || 0 }));
        const TEXT = new Set(['subrip', 'srt', 'ass', 'ssa', 'webvtt', 'mov_text', 'text']);
        const subs = streams.filter(x => x.codec_type === 'subtitle').map(x => ({ i: si++, label: name(x) || `Subtitles ${si}`, lang: x.tags?.language || '', codec: x.codec_name, text: TEXT.has(x.codec_name) }));
        resolve({ duration: Number(j.format?.duration) || 0, audio, subs,
          video: v ? { codec: v.codec_name, width: v.width, height: v.height, hdr: /smpte2084|arib-std-b67/.test(v.color_transfer || '') } : null });
      });
  });
  probes.set(target, job);
  job.then(p => { if (!p.duration) probes.delete(target); });
  setTimeout(() => probes.delete(target), 3600_000).unref();
  return job;
}

// All the WebVTT written so far for subtitle stream idx (every run's file), cues merged and sorted; the
// app polls this while it plays and adds cues it hasn't seen. A half-written last cue is left out.
async function mergedVtt(s, idx) {
  let names = [];
  try { names = (await fs.readdir(s.dir)).filter(n => n.startsWith(`subs${idx}-`) && n.endsWith('.vtt')); } catch {}
  const cues = new Map();
  for (const n of names) {
    let text = '';
    try { text = await fs.readFile(join(s.dir, n), 'utf8'); } catch { continue; }
    const blocks = text.replace(/\r/g, '').split(/\n\n+/);
    if (!/\n\n\s*$/.test(text)) blocks.pop();   // incomplete tail
    for (const b of blocks) {
      const m = /(\d{2}:)?\d{2}:\d{2}\.\d{3} --> (\d{2}:)?\d{2}:\d{2}\.\d{3}/.exec(b);
      if (!m) continue;
      const lines = b.split('\n'), ti = lines.findIndex(l => l.includes('-->'));
      const body = lines.slice(ti + 1).join('\n').trim();
      if (body) cues.set(`${lines[ti].slice(0, 12)}|${body}`, lines[ti] + '\n' + body);
    }
  }
  const toMs = t => { const p = t.split(':').map(Number); return p.length === 3 ? (p[0] * 3600 + p[1] * 60 + p[2]) * 1000 : (p[0] * 60 + p[1]) * 1000; };
  const sorted = [...cues.values()].sort((a, b) => toMs(a.split(' --> ')[0]) - toMs(b.split(' --> ')[0]));
  return 'WEBVTT\n\n' + sorted.join('\n\n') + '\n\n';
}

// Live: one ffmpeg per channel writing a rolling window. VOD: the file is cut into fixed 4s segments
// (seg0, seg1, …) in one or more "runs"; a run can start at any segment index (-ss), so a seek far
// ahead or back just restarts the run there while already-made segments stay on disk. The playlist the
// player gets is synthesised from the file's duration (see vodManifest), so it is a plain VOD to every
// player: real length, instant seeking, no live behaviour.
function ffmpegArgs(dir, target, { vod = false, fromIdx = 0, audio = 'aac', track = 0, subs = [] } = {}) {
  return [
    '-hide_banner', '-loglevel', 'error', '-nostdin',
    '-user_agent', 'Mozilla/5.0', '-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_at_eof', '1', '-reconnect_delay_max', '2',
    '-rw_timeout', '15000000',                                   // 15s: a dead upstream fails fast instead of hanging
    // Live TS: open fast with a small probe. Files (mkv/mp4): probe properly, or ffmpeg misses the B-frame
    // reorder depth and writes frames with no decode timestamps, which freezes players after half a second.
    ...(vod ? [] : ['-probesize', '500000', '-analyzeduration', '500000']),
    ...(vod && fromIdx > 0 ? ['-ss', String(fromIdx * VOD_SEG_S)] : []),
    '-fflags', '+genpts+flush_packets', '-i', target,
    '-map', '0:v:0?', '-map', `0:a:${track}?`,
    ...(REENCODE
      ? ['-c:v', 'libx264', '-preset', 'veryfast', '-tune', 'zerolatency', '-profile:v', 'high', '-level', '4.1', '-pix_fmt', 'yuv420p',
         '-g', '25', '-keyint_min', '25', '-sc_threshold', '0', '-crf', '22', '-maxrate', '5M', '-bufsize', '10M', '-threads', '4']
      : ['-c:v', 'copy']),
    ...(audio === 'none' ? ['-an'] : audio === 'copy' ? ['-c:a', 'copy'] : ['-c:a', 'aac', '-b:a', '128k', '-ac', '2']),
    '-flush_packets', '1', '-muxdelay', '0',
    ...(vod
      // A seek run keeps the file's own timestamps (copyts) so segment N holds time N*4s onwards. The run from
      // the start doesn't: copyts there leaves the first B-frames with negative times, which stalls players.
      ? [...(fromIdx > 0 ? ['-copyts'] : []), '-f', 'hls', '-hls_time', String(VOD_SEG_S), '-hls_list_size', '0', '-start_number', String(fromIdx),
         '-hls_flags', 'split_by_time+temp_file', '-hls_segment_type', 'mpegts']
      // ~45 minutes of segments stay on disk (delete_segments trims older ones): pause, rewind and
      // jump-back work in the app; the player still starts near the live edge (EXT-X-START).
      : ['-f', 'hls', '-hls_time', REENCODE ? '1' : '2', '-hls_init_time', '1', '-hls_list_size', '1000',
         '-hls_flags', 'delete_segments+append_list+omit_endlist+independent_segments', '-hls_segment_type', 'mpegts']),
    '-hls_segment_filename', join(dir, 'seg%d.ts'), join(dir, 'index.m3u8'),
    // VOD: every text subtitle stream is written alongside as WebVTT from the same pass (no second download
    // of the file, no second provider connection). One file per run, merged when served.
    ...(vod ? subs.flatMap(i => ['-map', `0:s:${i}`, '-c:s', 'webvtt', '-f', 'webvtt', '-y', join(dir, `subs${i}-${fromIdx}.vtt`)]) : []),
  ];
}

async function startSession(id, target, vod = false, start = 0, audio = 'aac', track = 0) {
  // A fresh folder per start, so restarting a crashed stream never races the previous folder's cleanup.
  const dir = join(ROOT, `${id}-${Date.now().toString(36)}`);
  await fs.mkdir(dir, { recursive: true });
  const probe = vod ? await probeFile(target) : null;
  const subs = (probe?.subs || []).filter(s => s.text).map(s => s.i);
  const proc = spawn(FFMPEG, ffmpegArgs(dir, target, { vod, fromIdx: 0, audio, track, subs }), { windowsHide: true });
  const session = { proc, dir, lastAccess: Date.now(), exited: false, errTail: '', startedAt: Date.now(), vod, start, host: new URL(target).hostname, target, runStart: 0, duration: probe?.duration || 0, audio, track, subs };
  console.log(`[hls] start ${id} ${target.slice(0, 100)}`);
  proc.stderr.on('data', d => { session.errTail = (session.errTail + d).slice(-1500); });
  proc.on('exit', () => { session.exited = true; });
  proc.on('error', () => { session.exited = true; });
  sessions.set(id, session);
  return session;
}

// VOD: restart the run at segment index n (seek). Existing segments stay; the new run writes seg n, n+1, …
function restartRun(s, n) {
  try { s.proc.kill(); } catch {}
  const proc = spawn(FFMPEG, ffmpegArgs(s.dir, s.target, { vod: true, fromIdx: n, audio: s.audio, track: s.track, subs: s.subs || [] }), { windowsHide: true });
  Object.assign(s, { proc, exited: false, errTail: '', startedAt: Date.now(), runStart: n });
  proc.stderr.on('data', d => { s.errTail = (s.errTail + d).slice(-1500); });
  proc.on('exit', () => { if (s.proc === proc) s.exited = true; });
  proc.on('error', () => { if (s.proc === proc) s.exited = true; });
  s.runAt = Date.now();
  console.log(`[hls] vod run from segment ${n} (${n * VOD_SEG_S}s)`);
}

// Highest segment index the current run has finished (from ffmpeg's own playlist).
async function producedUpTo(s) {
  try {
    const t = await fs.readFile(join(s.dir, 'index.m3u8'), 'utf8');
    const m = [...t.matchAll(/seg(\d+)\.ts/g)];
    return m.length ? Number(m[m.length - 1][1]) : s.runStart - 1;
  } catch { return s.runStart - 1; }
}

// The whole film as a VOD playlist: N fixed-length segments and an end marker. Segments that don't
// exist yet are made on request (see the segment branch of handleHls).
function vodManifest(id, duration) {
  const n = Math.ceil(duration / VOD_SEG_S);
  let out = `#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:${VOD_SEG_S + 1}\n#EXT-X-MEDIA-SEQUENCE:0\n`;
  for (let i = 0; i < n; i++) {
    const len = i === n - 1 ? Math.max(0.5, duration - i * VOD_SEG_S) : VOD_SEG_S;
    out += `#EXTINF:${len.toFixed(3)},\n/hls/${id}/seg${i}.ts\n`;
  }
  return out + '#EXT-X-ENDLIST\n';
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
async function closeOthers(keepId, host) {
  // The one-connection rule is per provider: a stream from another host (a different provider, or a test
  // file) must not close this one.
  const others = [...sessions.entries()].filter(([k, v]) => k !== keepId && (!host || v.host === host)).map(([k]) => k);
  if (others.length) { await Promise.all(others.map(stopSession)); await new Promise(r => setTimeout(r, 120)); }
}

setInterval(() => {
  const now = Date.now();
  for (const [id, s] of sessions) if (now - s.lastAccess > (s.vod ? 90_000 : IDLE_MS)) stopSession(id);   // a paused film keeps its segments for a while
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
    try { st = await fs.stat(f); } catch {
      const seg = s.vod && /^seg(\d+)\.ts$/.exec(name);
      if (!seg) return send(res, 404, 'Not ready');
      // Not made yet. If it's just ahead of the current run, wait for it; otherwise restart the run there.
      const n = Number(seg[1]);
      const done = await producedUpTo(s);
      // Players ask for two or three segments at once: one restart covers them all.
      const fresh = Date.now() - (s.restartAt || 0) < 4000 && n >= s.runStart && n <= s.runStart + 8;
      if (!fresh && (s.exited || n < s.runStart || n > done + 6)) { s.restartAt = Date.now(); restartRun(s, n); }
      const deadline = Date.now() + 30_000, waitFrom = Date.now();
      while (Date.now() < deadline && sessions.get(id) === s) {
        try { st = await fs.stat(f); break; } catch {}
        if (s.exited && (await producedUpTo(s)) < n) return send(res, 404, 'Segment unavailable');   // run ended before reaching it (past the end of the file)
        await new Promise(r => setTimeout(r, 120));
      }
      if (!st) { console.log(`[hls] vod seg${n} not ready after 30s (run from ${s.runStart}, exited=${s.exited}) ${(s.errTail || '').split('\n').filter(Boolean).pop() || ''}`); return send(res, 504, 'Segment not ready'); }
      const waited = Date.now() - waitFrom;
      if (waited > 1500) console.log(`[hls] vod seg${n} took ${(waited / 1000).toFixed(1)}s (run from ${s.runStart} started ${((Date.now() - (s.runAt || s.startedAt)) / 1000).toFixed(1)}s ago) ${(s.errTail || '').split('\n').filter(Boolean).pop() || ''}`);
      s.lastAccess = Date.now();
    }
    res.writeHead(200, { 'content-type': TYPES[extname(name)] || 'application/octet-stream', 'content-length': st.size, 'access-control-allow-origin': '*', 'cache-control': 'no-store' });
    if (req.method === 'HEAD') return res.end();
    return createReadStream(f).on('error', () => res.destroy()).pipe(res);
  }

  // start (or reuse) a transcode and serve its playlist. /hls/warm just starts it (pre-warm) and returns.
  const warm = parts.length === 2 && parts[1] === 'warm';
  const stop = parts.length === 2 && parts[1] === 'stop';
  const info = parts.length === 2 && parts[1] === 'info';
  const subs = parts.length === 2 && parts[1] === 'subs';
  const target = url.searchParams.get('url');
  const key = url.searchParams.get('key') || '';
  if (!target) return send(res, 400, 'Missing url');
  if (stop) {   // the app switched away: free the provider connection right now instead of after the idle timeout
    if (PROXY_KEY && key !== PROXY_KEY) return send(res, 403, 'Wrong or missing proxy key');
    try {   // same id recipe as a start, so a stop finds the right session
      const href = new URL(target).href, st = Math.max(0, Math.floor(Number(url.searchParams.get('start')) || 0));
      const au = ['none', 'copy'].includes(url.searchParams.get('audio')) ? url.searchParams.get('audio') : 'aac';
      const tr = Math.max(0, Math.min(15, Number(url.searchParams.get('track')) || 0));
      stopSession(sessionId((st ? `${href}#${st}` : href) + (au !== 'aac' ? `#${au}` : '') + (tr ? `#a${tr}` : '')));
    } catch {}
    return send(res, 204, '');
  }
  if (PROXY_KEY && key !== PROXY_KEY) return send(res, 403, 'Wrong or missing proxy key');
  let t;
  try { t = new URL(target); } catch { return send(res, 400, 'Bad url'); }
  if (!/^https?:$/.test(t.protocol)) return send(res, 400, 'Only http and https links');
  if (!ALLOW_PRIVATE && isPrivateHost(t.hostname)) return send(res, 403, 'Private addresses are blocked');
  if (info) return send(res, 200, JSON.stringify(await probeFile(t.href)), { 'content-type': 'application/json', 'cache-control': 'no-store' });
  if (subs) {
    const idx = Math.max(0, Number(url.searchParams.get('s')) || 0);
    const tr = Math.max(0, Math.min(15, Number(url.searchParams.get('track')) || 0));
    const s = sessions.get(sessionId(t.href + (tr ? `#a${tr}` : '')));
    if (!s || !s.vod) return send(res, 404, 'Stream not running');
    s.lastAccess = Date.now();
    return send(res, 200, await mergedVtt(s, idx), { 'content-type': 'text/vtt; charset=utf-8', 'cache-control': 'no-store' });
  }

  const vod = url.searchParams.get('vod') === '1';
  const start = vod ? Math.max(0, Math.floor(Number(url.searchParams.get('start')) || 0)) : 0;
  const audio = ['none', 'copy'].includes(url.searchParams.get('audio')) ? url.searchParams.get('audio') : 'aac';   // diagnostics
  const track = vod ? Math.max(0, Math.min(15, Number(url.searchParams.get('track')) || 0)) : 0;   // which audio track of the file
  const id = sessionId((start ? `${t.href}#${start}` : t.href) + (audio !== 'aac' ? `#${audio}` : '') + (track ? `#a${track}` : ''));
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
      starting.set(id, (async () => { await closeOthers(id, t.hostname); return startSession(id, t.href, vod, start, audio, track); })().finally(() => starting.delete(id)));
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
  if (s.vod && url.searchParams.get('raw') !== '1') {   // raw=1: ffmpeg's own playlist, for diagnostics
    if (!s.duration) s.duration = await probeDuration(t.href);
    if (s.duration > 0) {
      res.writeHead(200, { 'content-type': 'application/vnd.apple.mpegurl', 'access-control-allow-origin': '*', 'cache-control': 'no-store' });
      return res.end(req.method === 'HEAD' ? undefined : vodManifest(id, s.duration));
    }
    // unknown length (probe failed): fall through and serve ffmpeg's growing playlist as before
  }
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
