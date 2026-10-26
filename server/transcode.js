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
import { Readable } from 'node:stream';
import { request as httpsRequest } from 'node:https';
import { request as httpRequest } from 'node:http';

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
const ENC_SEG_S = 2;                     // …2-second ones while converting, so the first picture comes sooner
const HEAD_SEGS = 8;                     // a conversion run starts with this many segments at the fastest preset, then re-runs at normal quality
const READY_SEGMENTS = REENCODE ? 2 : 1;   // copy-mode segments are keyframe-aligned (2-4s): one is buffer enough
const START_BEHIND_LIVE_S = REENCODE ? 3 : 4; // start playback this far behind the live edge so there's always buffer in hand

const TYPES = {
  '.m3u8': 'application/vnd.apple.mpegurl',
  '.ts': 'video/mp2t', '.m4s': 'video/iso.segment', '.mp4': 'video/mp4',
};

const sessions = new Map(); // id -> { proc, dir, lastAccess, exited, errTail, startedAt }
let canTonemap = false;   // zscale+tonemap filters present in this ffmpeg build (HDR -> SDR when converting)
execFile(FFMPEG, ['-hide_banner', '-filters'], { windowsHide: true, maxBuffer: 4 << 20 }, (e, out) => { canTonemap = !e && /\bzscale\b/.test(out) && /\btonemap\b/.test(out); console.log(`[hls] tone mapping ${canTonemap ? 'available' : 'not available'}`); });
const starting = new Map(); // id -> promise of the session: a warm + play for the same channel share one start
try { rmSync(ROOT, { recursive: true, force: true }); mkdirSync(ROOT, { recursive: true }); } catch {}
// A restarted server must not leave the previous one's ffmpeg children running: on Windows they outlive the
// parent and would keep holding the provider's single connection.
if (process.platform === 'win32') execFile('taskkill', ['/IM', 'ffmpeg.exe', '/F', '/T'], { windowsHide: true }, () => {});
process.on('exit', () => { for (const s of sessions.values()) { try { s.proc?.kill(); } catch {} } });
for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => process.exit(0));

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
      'format=duration,bit_rate:stream=index,codec_type,codec_name,width,height,color_transfer,profile,pix_fmt,field_order,channels:stream_tags=language,title', '-of', 'json', target],
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
        resolve({ duration: Number(j.format?.duration) || 0, bitrate: Number(j.format?.bit_rate) || 0, audio, subs,
          video: v ? { codec: v.codec_name, width: v.width, height: v.height, profile: v.profile || '', pix: v.pix_fmt || '', bit10: /p10|p12|10le|12le/.test(v.pix_fmt || '') || /10/.test(v.profile || ''),
            chroma444: /422|444/.test(v.pix_fmt || ''), interlaced: /^(tt|bb|tb|bt)$/.test(v.field_order || ''), hdr: /smpte2084|arib-std-b67/.test(v.color_transfer || '') } : null });
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
// Video filters when converting: deinterlace, HDR -> SDR tone map, cap at 1080p, 8-bit 4:2:0 for every decoder.
function encodeFilters(v = {}) {
  const f = [];
  if (v.interlaced) f.push('yadif');
  if (v.hdr && canTonemap) f.push('zscale=t=linear:npl=100', 'format=gbrpf32le', 'zscale=p=bt709', 'tonemap=hable:desat=0', 'zscale=t=bt709:m=bt709:r=tv');
  if ((v.height || 0) > 1080) f.push('scale=-2:1080');
  f.push('format=yuv420p');
  return f.join(',');
}
// Audio: re-encoded audio follows the source's own timestamps (aresample async), so gaps and drift in an
// IPTV feed can't pull it out of step with the picture; av (ms) is the viewer's own sync correction.
function audioArgs(audio, av) {
  if (audio === 'none') return ['-an'];
  if (audio === 'copy' && !av) return ['-c:a', 'copy'];
  const f = `aresample=async=1000:min_hard_comp=0.050${av ? `,asetpts=PTS+${(av / 1000).toFixed(3)}/TB` : ''}`;
  return ['-af', f, '-c:a', 'aac', '-b:a', '192k', '-ac', '2'];
}
// One id recipe for start, stop and subtitles, so each finds the same session.
function sessionKey(href, p) {
  const st = Math.max(0, Math.floor(Number(p.get('start')) || 0)) * (p.get('vod') === '1' || p.get('start') ? 1 : 0);
  const au = ['none', 'copy'].includes(p.get('audio')) ? p.get('audio') : 'aac';
  const tr = Math.max(0, Math.min(15, Number(p.get('track')) || 0));
  const av = avOf(p);
  return sessionId((st ? `${href}#${st}` : href) + (au !== 'aac' ? `#${au}` : '') + (tr ? `#a${tr}` : '') + (p.get('enc') === '1' ? '#enc' : '') + (av ? `#av${av}` : '') + (p.get('fmp4') === '1' && p.get('enc') !== '1' ? '#f4' : '') + (p.get('surround') === '1' ? '#sr' : ''));
}
const avOf = p => Math.max(-3000, Math.min(3000, Math.round((Number(p.get('av')) || 0) / 50) * 50));

// fmp4: HEVC for Safari. Apple's players only take HEVC in fragmented-MP4 segments with the hvc1 tag, never in
// MPEG-TS, so the untouched video is packaged that way instead of being converted.
function ffmpegArgs(dir, target, { vod = false, fromIdx = 0, audio = 'aac', track = 0, subs = [], enc = false, height = 0, vinfo = null, segLen = VOD_SEG_S, fast = false, av = 0, fmp4 = false } = {}) {
  // init.mp4 by full path: on Windows a bare name lands in the server's own folder.
  const segType = fmp4 && !enc ? ['-hls_segment_type', 'fmp4', '-hls_fmp4_init_filename', join(dir, 'init.mp4')] : ['-hls_segment_type', 'mpegts'];
  const ext = fmp4 && !enc ? 'm4s' : 'ts';
  return [
    '-hide_banner', '-loglevel', 'error', '-nostdin',
    '-user_agent', 'Mozilla/5.0', '-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_at_eof', '1', '-reconnect_delay_max', '2',
    '-rw_timeout', '15000000',                                   // 15s: a dead upstream fails fast instead of hanging
    // Live TS: open fast with a small probe. Files (mkv/mp4): probe properly, or ffmpeg misses the B-frame
    // reorder depth and writes frames with no decode timestamps, which freezes players after half a second.
    ...(vod ? ['-probesize', '10000000', '-analyzeduration', '2000000'] : ['-probesize', '500000', '-analyzeduration', '500000']),
    ...(vod && fromIdx > 0 ? ['-ss', String(fromIdx * segLen)] : []),
    ...(vod && fast ? ['-t', String(HEAD_SEGS * segLen)] : []),   // the quick head of a run
    '-fflags', '+genpts+flush_packets', '-i', target,
    '-map', '0:v:0?', '-map', `0:a:${track}?`,
    ...(enc
      // The device can't decode this video (HEVC / 10-bit / AV1): convert to H.264, at most 1080p, with a
      // keyframe at every segment boundary so every segment starts clean.
      // Quality close to the source: CRF 19-20 and a generous bitrate ceiling (the old 6 Mbit/s cap blurred 1080p sport).
      ? ['-c:v', 'libx264', '-preset', fast ? 'ultrafast' : height > 1000 ? 'superfast' : 'veryfast', '-profile:v', 'high', '-level', '4.2',
         '-vf', encodeFilters({ ...(vinfo || {}), height }), '-crf', fast ? '23' : height > 1000 || !height ? '20' : '19',
         '-maxrate', height && height <= 720 ? '8M' : '14M', '-bufsize', height && height <= 720 ? '16M' : '28M', '-threads', '6',
         ...(fast ? ['-x264-params', 'rc-lookahead=4'] : vod ? [] : ['-x264-params', 'rc-lookahead=10:bframes=2']),
         ...(vod ? ['-force_key_frames', `expr:gte(t,n_forced*${segLen})`] : ['-g', '50', '-keyint_min', '25']), '-sc_threshold', '0']
      : REENCODE
      ? ['-c:v', 'libx264', '-preset', 'veryfast', '-tune', 'zerolatency', '-profile:v', 'high', '-level', '4.1', '-pix_fmt', 'yuv420p',
         '-g', '25', '-keyint_min', '25', '-sc_threshold', '0', '-crf', '22', '-maxrate', '5M', '-bufsize', '10M', '-threads', '4']
      : ['-c:v', 'copy', ...(fmp4 && (!vod || vinfo?.codec === 'hevc') ? ['-tag:v', 'hvc1'] : [])]),
    ...audioArgs(audio, av),
    '-flush_packets', '1', '-muxdelay', '0',
    ...(vod
      // A seek run keeps the file's own timestamps (copyts) so segment N holds time N*4s onwards. The run from
      // the start doesn't: copyts there leaves the first B-frames with negative times, which stalls players.
      ? [...(fromIdx > 0 ? ['-copyts'] : []), '-f', 'hls', '-hls_time', String(segLen), '-hls_list_size', '0', '-start_number', String(fromIdx),
         '-hls_flags', 'split_by_time+temp_file', ...segType]
      // ~45 minutes of segments stay on disk (delete_segments trims older ones): pause, rewind and
      // jump-back work in the app; the player still starts near the live edge (EXT-X-START).
      : ['-f', 'hls', '-hls_time', REENCODE ? '1' : '2', '-hls_init_time', '1', '-hls_list_size', '1000',
         '-hls_flags', 'delete_segments+append_list+omit_endlist+independent_segments', ...segType]),
    '-hls_segment_filename', join(dir, `seg%d.${ext}`), join(dir, 'index.m3u8'),
    // VOD: every text subtitle stream is written alongside as WebVTT from the same pass (no second download
    // of the file, no second provider connection). One file per run, merged when served.
    ...(vod ? subs.flatMap(i => ['-map', `0:s:${i}`, '-c:s', 'webvtt', '-f', 'webvtt', '-y', join(dir, `subs${i}-${fromIdx}.vtt`)]) : []),
  ];
}

// Spawn one ffmpeg run for a session. A conversion run is two-speed: a short head at the fastest preset so
// the first picture arrives in a few seconds, then (once that head is done) a normal-quality run from where
// the head stopped. The session always points at the live process.
function spawnRun(s, fromIdx, { fast = s.enc } = {}) {
  const opts = { vod: s.vod, fromIdx, audio: s.audio, track: s.track, subs: s.subs || [], enc: s.enc, height: s.height, vinfo: s.vinfo, segLen: s.segLen, fast: s.enc && fast, av: s.av, fmp4: s.fmp4 };
  const proc = spawn(FFMPEG, ffmpegArgs(s.dir, s.target, opts), { windowsHide: true });
  Object.assign(s, { proc, exited: false, errTail: '', startedAt: Date.now(), runStart: fromIdx, runAt: Date.now() });
  proc.stderr.on('data', d => { s.errTail = (s.errTail + d).slice(-1500); });
  proc.on('error', () => { if (s.proc === proc) s.exited = true; });
  proc.on('exit', code => {
    if (s.proc !== proc) return;                          // superseded by a seek
    if (code) console.log(`[hls] ffmpeg run from ${fromIdx} exited ${code}: ${s.errTail.trim().split('\n').slice(-2).join(' | ').slice(0, 300)}`);
    if (opts.fast && code === 0 && sessions.get(s.id) === s) return spawnRun(s, fromIdx + HEAD_SEGS, { fast: false });   // head done: continue at quality
    s.exited = true;
  });
  if (fromIdx || opts.fast) console.log(`[hls] vod run from segment ${fromIdx} (${fromIdx * s.segLen}s)${opts.fast ? ' quick head' : ''}`);
  return proc;
}

async function startSession(id, target, vod = false, start = 0, audio = 'aac', track = 0, enc = false, ua = '', av = 0, fmp4 = false, surround = false) {
  // A fresh folder per start, so restarting a crashed stream never races the previous folder's cleanup.
  const dir = join(ROOT, `${id}-${Date.now().toString(36)}`);
  await fs.mkdir(dir, { recursive: true });
  const probe = vod ? await probeFile(target) : null;
  const subs = (probe?.subs || []).filter(s => s.text).map(s => s.i);
  const height = probe?.video?.height || 0;
  const session = { id, proc: null, dir, lastAccess: Date.now(), exited: false, errTail: '', startedAt: Date.now(), vod, start, host: new URL(target).hostname, target, runStart: 0,
    duration: probe?.duration || 0, audio, track, subs, enc, height, vinfo: probe?.video, segLen: enc ? ENC_SEG_S : VOD_SEG_S, ua, av,
    // fMP4 only for HEVC (films: known from the probe; live: the app asks after a TS attempt showed no picture).
    fmp4: fmp4 && !enc && (!vod || probe?.video?.codec === 'hevc') };
  // Safari takes Dolby Digital / Dolby Digital Plus (Atmos too) untouched, in fMP4: keep the film's own surround sound.
  const srcAudio = probe?.audio?.[track]?.codec;
  if (vod && surround && !enc && !av && audio === 'aac' && (srcAudio === 'ac3' || srcAudio === 'eac3')) { session.audio = 'copy'; session.fmp4 = true; }
  if (session.fmp4) console.log(`[hls] ${id}: untouched in fMP4 for Safari (${probe?.video?.codec || 'live'} video, ${session.audio === 'copy' ? `${srcAudio || 'source'} audio kept` : 'AAC audio'})`);
  // A film whose sound is already AAC keeps it untouched (full quality, surround kept, nothing to drift).
  if (vod && audio === 'aac' && !av && probe?.audio?.[track]?.codec === 'aac') session.audio = 'copy';
  sessions.set(id, session);
  if (enc) console.log(`[hls] encoding ${id} (${probe?.video?.codec}${probe?.video?.bit10 ? ' 10-bit' : ''} ${height}p -> h264)`);
  console.log(`[hls] start ${id} ${target.slice(0, 100)}${session.ua ? ` [${session.ua}]` : ''}`);
  if (vod) spawnRun(session, 0);
  else {
    const proc = spawn(FFMPEG, ffmpegArgs(dir, target, { vod: false, audio, track, enc, height, vinfo: probe?.video, av, fmp4: session.fmp4 }), { windowsHide: true });
    session.proc = proc;
    proc.stderr.on('data', d => { session.errTail = (session.errTail + d).slice(-1500); });
    proc.on('exit', () => { session.exited = true; });
    proc.on('error', () => { session.exited = true; });
  }
  return session;
}

// VOD: restart at segment index n (seek). Existing segments stay; the new run writes seg n, n+1, …
function restartRun(s, n) {
  try { s.proc?.kill(); } catch {}
  spawnRun(s, n);
}

// Live feed speed: seconds of video the provider delivered per second of wall clock, over the last ~30s.
// Below 1.0 the feed can't keep up and playback will stall sooner or later.
async function liveSpeed(s) {
  let t;
  try { t = await fs.readFile(join(s.dir, 'index.m3u8'), 'utf8'); } catch { return null; }
  s.segSeen ??= -1; s.media ??= 0;
  for (const m of t.matchAll(/#EXTINF:([\d.]+),\s*\r?\n(?:#[^\n]*\r?\n)*seg(\d+)\.(?:ts|m4s)/g)) {
    const n = Number(m[2]);
    if (n > s.segSeen) { s.segSeen = n; s.media += Number(m[1]); }
  }
  const now = Date.now();
  (s.samples ??= []).push([now, s.media]);
  while (s.samples.length > 2 && now - s.samples[1][0] > 30_000) s.samples.shift();
  const [t0, m0] = s.samples[0], dt = (now - t0) / 1000;
  return dt >= 12 ? Math.round(((s.media - m0) / dt) * 100) / 100 : null;
}

// Highest segment index the current run has finished (from ffmpeg's own playlist).
async function producedUpTo(s) {
  try {
    const t = await fs.readFile(join(s.dir, 'index.m3u8'), 'utf8');
    const m = [...t.matchAll(/seg(\d+)\.(?:ts|m4s)/g)];
    return m.length ? Number(m[m.length - 1][1]) : s.runStart - 1;
  } catch { return s.runStart - 1; }
}

// The whole film as a VOD playlist: N fixed-length segments and an end marker. Segments that don't
// exist yet are made on request (see the segment branch of handleHls).
function vodManifest(id, duration, segLen = VOD_SEG_S, fmp4 = false) {
  const n = Math.ceil(duration / segLen);
  let out = `#EXTM3U\n#EXT-X-VERSION:${fmp4 ? 7 : 3}\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:${segLen + 1}\n#EXT-X-MEDIA-SEQUENCE:0\n`;
  if (fmp4) out += `#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-MAP:URI="/hls/${id}/init.mp4"\n`;
  for (let i = 0; i < n; i++) {
    const len = i === n - 1 ? Math.max(0.5, duration - i * segLen) : segLen;
    out += `#EXTINF:${len.toFixed(3)},\n/hls/${id}/seg${i}.${fmp4 ? 'm4s' : 'ts'}\n`;
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
  // file) must not close this one. A download in progress on that provider is stopped too.
  if (host && downloads.get(host)) { try { downloads.get(host).proc.kill(); } catch {} downloads.delete(host); }
  const others = [...sessions.entries()].filter(([k, v]) => k !== keepId && (!host || v.host === host)).map(([k]) => k);
  if (others.length) { await Promise.all(others.map(stopSession)); await new Promise(r => setTimeout(r, 120)); }
}

setInterval(() => {
  const now = Date.now();
  for (const [id, s] of sessions) if (now - s.lastAccess > (s.paused ? 2 * 3600_000 : s.vod ? 90_000 : IDLE_MS)) stopSession(id);   // a paused film keeps its pieces for 2h
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

// Offline download: the whole title as one fragmented MP4 (H.264 + AAC, so any device plays it later),
// streamed as it is made. q = 720 | 1080 | orig (copy the video when it's already H.264 8-bit).
const downloads = new Map();   // host -> { proc }
export async function handleDownload(req, res, url, send, allowed) {
  if (PROXY_KEY && url.searchParams.get('key') !== PROXY_KEY) return send(res, 403, 'Wrong or missing proxy key');
  let t;
  try { t = new URL(url.searchParams.get('url') || ''); } catch { return send(res, 400, 'Bad url'); }
  if (!allowed(t)) return send(res, 403, 'Address not allowed');
  const q = ['720', '1080', 'orig', 'file'].includes(url.searchParams.get('q')) ? url.searchParams.get('q') : '720';
  const track = Math.max(0, Math.min(15, Number(url.searchParams.get('track')) || 0));
  // One connection per provider: while something from this provider is playing, say so instead of fighting it
  // (the app waits and retries; an exact-file copy then resumes from where it stopped).
  // "Playing" means segments were fetched in the last 10s; a stream merely kept warm after you stopped is closed to make room.
  if ([...sessions.values()].some(s => s.host === t.hostname && !s.exited && Date.now() - s.lastAccess < 10_000)) return send(res, 409, 'Busy: something from this provider is playing');
  await closeOthers(null, t.hostname);
  if (q === 'file') {
    // The provider's file exactly as it is (4K stays 4K, HEVC stays HEVC): for saving to a computer and playing in VLC etc.
    await closeOthers(null, t.hostname);
    const ctrl = new AbortController();
    res.on('close', () => { ctrl.abort(); if (downloads.get(t.hostname)?.ctrl === ctrl) downloads.delete(t.hostname); });
    downloads.set(t.hostname, { ctrl, proc: { kill: () => { ctrl.abort(); try { res.destroy(); } catch {} } } });
    const range = /^bytes=\d+-$/.test(req.headers.range || '') ? req.headers.range : null;
    // One plain socket, no keep-alive pool: the provider counts open connections, so nothing may linger.
    const mk = t.protocol === 'https:' ? httpsRequest : httpRequest;
    const upReq = mk(t, { method: 'GET', agent: false, headers: { 'user-agent': 'Mozilla/5.0', connection: 'close', ...(range ? { range } : {}) } });
    ctrl.signal.addEventListener('abort', () => upReq.destroy());
    upReq.on('error', e => { if (!res.headersSent) send(res, 502, `Upstream error: ${e.message}`); else res.destroy(); });
    upReq.on('response', up => {
      if (up.statusCode !== 200 && up.statusCode !== 206) { up.resume(); return send(res, 502, `The provider replied ${up.statusCode}`); }
      const total = up.statusCode === 206 ? (up.headers['content-range'] || '').split('/')[1] : up.headers['content-length'];
      console.log(`[dl] file copy ${t.href.slice(0, 80)} ${total || '?'} bytes${range ? ` (resume ${range})` : ''}`);
      res.writeHead(up.statusCode, { 'content-type': up.headers['content-type'] || 'application/octet-stream', 'access-control-allow-origin': '*', 'cache-control': 'no-store',
        ...(up.statusCode === 206 ? { 'content-range': up.headers['content-range'] } : {}),
        'x-mode': 'file', 'x-estimate': total || '0', 'access-control-expose-headers': 'x-duration, x-mode, x-estimate, content-range' });
      up.on('error', () => res.destroy()).pipe(res);
    });
    upReq.end();
    return;
  }
  const probe = await probeFile(t.href);
  if (!probe.duration) return send(res, 502, 'Could not read the file');
  const v = probe.video || {};
  const copy = q === 'orig' && v.codec === 'h264' && !v.bit10 && !v.chroma444 && !v.interlaced;
  const target = q === 'orig' ? Math.min(v.height || 1080, 1080) : Math.min(Number(q), v.height || Number(q));   // a converted "original" stops at 1080p
  // Rough final size so the app can draw a real progress bar: the file's own bitrate when copying, else the encoder's.
  const kbps = copy ? (probe.bitrate / 1000 || 5000) : (q === '720' ? 2600 : target >= 1080 ? 5200 : 2600) + 128;
  const estimate = Math.round(probe.duration * kbps * 125);
  await closeOthers(null, t.hostname);          // one provider connection: a download takes it
  downloads.get(t.hostname)?.proc.kill();
  const args = ['-hide_banner', '-loglevel', 'error', '-nostdin', '-user_agent', 'Mozilla/5.0', '-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_delay_max', '2',
    '-i', t.href, '-map', '0:v:0', '-map', `0:a:${track}?`,
    ...(copy ? ['-c:v', 'copy'] : ['-c:v', 'libx264', '-preset', target > 1000 ? 'superfast' : 'veryfast', '-profile:v', 'high', '-level', '4.1',
      '-vf', [...(v.interlaced ? ['yadif'] : []), ...(v.hdr && canTonemap ? ['zscale=t=linear:npl=100', 'format=gbrpf32le', 'zscale=p=bt709', 'tonemap=hable:desat=0', 'zscale=t=bt709:m=bt709:r=tv'] : []), ...((v.height || 0) > target ? [`scale=-2:${target}`] : []), 'format=yuv420p'].join(','),
      '-crf', q === '720' ? '24' : '23', '-maxrate', q === '720' ? '3M' : '6M', '-bufsize', '12M', '-threads', '4', '-g', '96', '-sc_threshold', '0']),
    '-c:a', 'aac', '-b:a', '128k', '-ac', '2',
    '-movflags', 'frag_keyframe+empty_moov+default_base_moof', '-f', 'mp4', 'pipe:1'];
  const proc = spawn(FFMPEG, args, { windowsHide: true });
  downloads.set(t.hostname, { proc });
  console.log(`[dl] start ${t.href.slice(0, 80)} q=${q} ${copy ? 'copy' : 'encode'} ${v.codec} ${v.height}p`);
  let errTail = '';
  proc.stderr.on('data', d => { errTail = (errTail + d).slice(-800); });
  res.writeHead(200, { 'content-type': 'video/mp4', 'access-control-allow-origin': '*', 'cache-control': 'no-store', 'x-duration': String(probe.duration), 'x-mode': copy ? 'copy' : 'encode', 'x-estimate': String(estimate), 'access-control-expose-headers': 'x-duration, x-mode, x-estimate' });
  proc.stdout.pipe(res);
  proc.on('exit', code => { if (downloads.get(t.hostname)?.proc === proc) downloads.delete(t.hostname); if (code && !res.writableEnded) console.log(`[dl] failed: ${errTail.split('\n').filter(Boolean).pop() || code}`); res.end(); });
  res.on('close', () => { try { proc.kill(); } catch {} });
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
    if (name === 'init.mp4') {   // fMP4 header: written as the run starts
      for (let i = 0; i < 250 && !st && sessions.get(id) === s; i++) { try { st = await fs.stat(f); } catch { await new Promise(r => setTimeout(r, 120)); } }
      if (!st) return send(res, 404, 'Not ready');
    }
    if (!st) try { st = await fs.stat(f); } catch {
      const seg = s.vod && /^seg(\d+)\.(?:ts|m4s)$/.exec(name);
      if (!seg) return send(res, 404, 'Not ready');
      // Not made yet. If it's just ahead of the current run, wait for it; otherwise restart the run there.
      const n = Number(seg[1]);
      const done = await producedUpTo(s);
      // Players ask for two or three segments at once: one restart covers them all.
      const fresh = Date.now() - (s.restartAt || 0) < 4000 && n >= s.runStart && n <= s.runStart + 8;
      if (!fresh && (s.exited || n < s.runStart || n > done + 6)) { s.restartAt = Date.now(); s.paused = false; restartRun(s, n); }
      const deadline = Date.now() + (s.enc ? 90_000 : 30_000), waitFrom = Date.now();   // converting the first seconds of a 1080p file can take a while
      while (Date.now() < deadline && sessions.get(id) === s) {
        try { st = await fs.stat(f); break; } catch {}
        if (s.exited && Date.now() - s.runAt > 3000 && (await producedUpTo(s)) < n) return send(res, 404, 'Segment unavailable');   // run ended before reaching it (past the end of the file)
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
  const pause = parts.length === 2 && parts[1] === 'pause';
  // Deploys ask this before restarting: streams playing now, or films paused in the last 30 minutes.
  if (url.pathname === '/hls/active') {
    if (PROXY_KEY && url.searchParams.get('key') !== PROXY_KEY) return send(res, 403, 'Wrong or missing key');
    // Someone watching right now (a paused film just reconnects after a restart). Test streams don't count.
    const TEST = /archive\.org|blender\.org|test-videos\.co\.uk/;
    const n = [...sessions.values()].filter(s => !TEST.test(s.host || '') && !s.paused && (!s.exited || Date.now() - s.lastAccess < 60_000)).length;
    return send(res, 200, String(n), { 'cache-control': 'no-store' });
  }
  const target = url.searchParams.get('url');
  const key = url.searchParams.get('key') || '';
  if (!target) return send(res, 400, 'Missing url');
  if (url.pathname === '/hls/health') {   // the app asks while a live channel plays: is the feed keeping up?
    if (PROXY_KEY && key !== PROXY_KEY) return send(res, 403, 'Wrong or missing proxy key');
    let s = null;
    try { s = sessions.get(sessionKey(new URL(target).href, url.searchParams)); } catch {}
    if (!s || s.vod) return send(res, 404, 'Not running');
    const speed = await liveSpeed(s);
    if (speed !== null && speed < 0.9) console.log(`[hls] ${s.id} feed slow: ${speed}x`);
    return send(res, 200, JSON.stringify({ speed }), { 'content-type': 'application/json', 'cache-control': 'no-store' });
  }
  if (stop) {   // the app switched away: free the provider connection right now instead of after the idle timeout
    if (PROXY_KEY && key !== PROXY_KEY) return send(res, 403, 'Wrong or missing proxy key');
    try { stopSession(sessionKey(new URL(target).href, url.searchParams)); } catch {}   // same id recipe as a start
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
    const s = sessions.get(sessionKey(t.href, url.searchParams));
    if (!s || !s.vod) return send(res, 404, 'Stream not running');
    s.lastAccess = Date.now();
    return send(res, 200, await mergedVtt(s, idx), { 'content-type': 'text/vtt; charset=utf-8', 'cache-control': 'no-store' });
  }

  const vod = url.searchParams.get('vod') === '1';
  const start = vod ? Math.max(0, Math.floor(Number(url.searchParams.get('start')) || 0)) : 0;
  const audio = ['none', 'copy'].includes(url.searchParams.get('audio')) ? url.searchParams.get('audio') : 'aac';   // diagnostics
  const track = vod ? Math.max(0, Math.min(15, Number(url.searchParams.get('track')) || 0)) : 0;   // which audio track of the file
  const enc = url.searchParams.get('enc') === '1';   // the device can't decode this video codec: convert to H.264
  const av = avOf(url.searchParams);
  const id = sessionKey(t.href, url.searchParams);
  let s = sessions.get(id);
  // Paused film: drop the provider connection now but keep the pieces already made; play resumes from the next one.
  if (pause) {
    if (s && s.vod && !s.exited) { s.paused = true; s.pausedAt = Date.now(); try { s.proc?.kill(); } catch {} console.log(`[hls] paused ${id}: provider connection released`); }
    return send(res, 204, '');
  }
  if (s && s.paused && warm) { s.lastAccess = Date.now(); return send(res, 204, ''); }   // keep a paused film's session, not its connection
  if (warm && vod) { if (s) s.lastAccess = Date.now(); return send(res, 204, ''); }        // a warm ping never opens a provider connection for a film
  if (s && !s.exited && !warm) {
    // Stalled: ffmpeg alive but the playlist hasn't been touched for a while -> upstream froze. Restart it.
    try {
      const m = await fs.stat(join(s.dir, 'index.m3u8'));
      if (!s.vod && Date.now() - m.mtimeMs > 12_000 && Date.now() - s.startedAt > 20_000) { console.log(`[hls] stalled ${id}, restarting`); s.exited = true; }
    } catch {}
  }
  if (s && s.exited && !s.paused) { stopSession(id); s = null; }
  if (!s) {
    // The app sends a warm on touch and the play a few ms later: both must wait for ONE start, or the
    // second ffmpeg is orphaned holding the only provider connection.
    if (!starting.has(id)) {
      const ua = /iPhone|iPad/.test(req.headers['user-agent'] || '') ? 'iPhone' : /Macintosh/.test(req.headers['user-agent'] || '') ? 'Mac' : 'other';
      starting.set(id, (async () => { await closeOthers(id, t.hostname); return startSession(id, t.href, vod, start, audio, track, enc, `${ua}${warm ? ' warm' : ''}`, av, url.searchParams.get('fmp4') === '1', url.searchParams.get('surround') === '1'); })().finally(() => starting.delete(id)));
    }
    s = await starting.get(id);
  }
  s.lastAccess = Date.now();
  if (warm) return send(res, 204, '');

  // Buffer level chosen by the app: how many segments before we hand over the playlist, and how far
  // behind the live edge to start. More = fewer stalls, slower channel change.
  const ready = Math.min(4, Math.max(1, Number(url.searchParams.get('ready')) || READY_SEGMENTS));
  const behind = Math.min(30, Math.max(2, Number(url.searchParams.get('behind')) || START_BEHIND_LIVE_S));
  if (s.vod && url.searchParams.get('raw') !== '1') {
    if (!s.duration) s.duration = await probeDuration(t.href);
    if (s.duration > 0) {   // the playlist doesn't depend on any segment existing yet (they're made on request)
      console.log(`[hls] vod playlist ${id}`);
      res.writeHead(200, { 'content-type': 'application/vnd.apple.mpegurl', 'access-control-allow-origin': '*', 'cache-control': 'no-store' });
      return res.end(req.method === 'HEAD' ? undefined : vodManifest(id, s.duration, s.segLen, s.fmp4));
    }
  }
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
      return res.end(req.method === 'HEAD' ? undefined : vodManifest(id, s.duration, s.segLen, s.fmp4));
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
    if (t.startsWith('#EXT-X-MAP:')) return line.replace(/URI="[^"]*"/, `URI="/hls/${id}/init.mp4"`);   // fMP4 header
    return (!t || t.startsWith('#')) ? line : `/hls/${id}/${t}`;
  }).join('\n');
  // Start a few seconds behind the live edge so the player always has segments in hand (no edge-chasing rebuffers).
  // Live starts a few seconds behind the edge; VOD must start at the very beginning of the session, otherwise
  // Safari treats the still-growing playlist as live and jumps to its newest part.
  if (!/#EXT-X-START/.test(body)) body = body.replace('#EXTM3U', s.vod ? '#EXTM3U\n#EXT-X-START:TIME-OFFSET=0,PRECISE=YES' : `#EXTM3U\n#EXT-X-START:TIME-OFFSET=-${behind},PRECISE=NO`);
  res.writeHead(200, { 'content-type': 'application/vnd.apple.mpegurl', 'access-control-allow-origin': '*', 'cache-control': 'no-store' });
  res.end(req.method === 'HEAD' ? undefined : body);
}
