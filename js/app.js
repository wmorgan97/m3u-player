import { parseM3U, toM3U, isHlsMedia, groupsOf, dedupe, uid, makeChannel, nameFromUrl } from './m3u.js';
import { db } from './db.js';
import { Player, externalPlayers } from './player.js';
import { net, fetchText, isMixed, streamUrl } from './net.js';
import { Xtream, parseXtreamLink, liveChannels, movieItems, seriesItems } from './xtream.js';
import { loadEpg, Guide, wantedFor, archiveDays, m3uCatchupUrl, normName } from './epg.js';
import { groupSeries, vodMeta, displayTitle } from './vod.js';
import { $, esc, icon, toast, sheet, closeSheet, menu, confirmDialog, formSheet } from './ui.js';
import { isNative, isTV, nativePlay, nativeShare, nativeVlc, headersFromOpts } from './native.js';

const PAGE = 150;
const UNGROUPED = 'Ungrouped';
const MAX_RECENTS = 50;
const MAX_PROGRESS = 400;
const MINUTE = 60000, HOUR = 3600000, DAY = 86400000;

const state = {
  playlists: [],
  favorites: [],
  recents: [],
  progress: {},    // url -> { t, d, at, done, item }  (movies & episodes)
  settings: { proxy: '', streamProxy: 'http', tsToHls: true, pinHash: '', nativeControls: false, buffer: 'auto', vodPlayer: 'app', vodLanguage: 'en', density: 'standard', logos: true, numbers: false, showNext: true, mergeDupes: true, resumeCard: true, accent: '' },
  current: null,   // playlist being viewed (real, or virtual 'fav' / 'recent' / 'all')
  tab: 'live',     // 'live' | 'movies' | 'series' (Xtream playlists)
  group: null,     // null = all groups
  query: '',
  filtered: [],
  rendered: 0,
  selecting: false,
  reordering: false,   // drag-to-reorder mode inside a group
  vodSort: 'default',  // a movie/series group's library: default | new | rating | az | year
  selected: new Set(),
  playing: null,   // { list, index }
  unlocked: false, // parental PIN entered this session
  sleepTimer: null,
  sleepAt: 0,
};
const favUrls = new Set();
const syncFavs = () => { favUrls.clear(); for (const c of state.favorites) favUrls.add(c.url); };
const guides = new Map();    // playlist id -> Guide
const vodCache = new Map();  // `${playlistId}:${tab}` -> items[] (direct from provider) or a server-backed catalogue (see loadVod)
const vodPending = new Set();  // catalogue requests in flight: `${key}|g:<group>` / `${key}|q:<text>`
const xtClients = new Map(); // playlist id -> Xtream

const view = $('#view');
const video = $('#video');
const player = new Player(video);
const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });
const observer = new IntersectionObserver(es => { if (es.some(e => e.isIntersecting)) renderMore(); }, { rootMargin: '800px' });

const plural = (n, word) => `${n.toLocaleString()} ${word}${n === 1 ? '' : 's'}`;
const host = url => { try { return new URL(url).hostname; } catch { return ''; } };
const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
const fmtTime = t => new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const fmtRange = p => `${fmtTime(p[0])}–${fmtTime(p[1])}`;
const fmtDur = s => { s = Math.floor(s); const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, sec = s % 60; return (h ? `${h}:${String(m).padStart(2, '0')}` : `${m}`) + `:${String(sec).padStart(2, '0')}`; };
function fmtDay(t) {
  const d = new Date(t), today = new Date();
  const diff = Math.round((new Date(d.toDateString()) - new Date(today.toDateString())) / DAY);
  return diff === 0 ? 'Today' : diff === -1 ? 'Yesterday' : diff === 1 ? 'Tomorrow' : d.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'short' });
}
const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const isStandalone = isNative || matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
if (isNative) document.documentElement.classList.add('native');
const isLiveChannel = c => !c.kind || c.kind === 'live';

const byId = id => state.playlists.find(p => p.id === id);
const srcOf = c => c.src || (state.current && !state.current.virtual ? state.current.id : null);
const playlistOf = c => byId(srcOf(c));
const guideFor = c => guides.get(srcOf(c));
function xtOf(p) {
  if (p?.kind !== 'xtream') return null;
  if (!xtClients.has(p.id)) xtClients.set(p.id, new Xtream(p.xtream));
  return xtClients.get(p.id);
}
const cloneChannel = c => ({ ...c, id: uid(), src: srcOf(c) || undefined, attrs: { ...c.attrs }, opts: [...(c.opts || [])] });

// ---------- boot ----------
async function init() {
  const [playlists, favorites, recents, settings, progress, meta, reminders] = await Promise.all([
    db.allPlaylists(), db.get('favorites'), db.get('recents'), db.get('settings'), db.get('progress'), db.get('syncMeta'), db.get('reminders'),
  ]);
  state.reminders = reminders || [];
  state.intros = (await db.get('intros')) || {};
  state.downloads = (await db.get('downloads')) || {};
  for (const m of Object.values(state.downloads)) if (!m.done && !m.error) m.error = 'Interrupted';   // the app was closed mid-download
  scheduleReminders();
  Object.assign(syncMeta, meta);
  state.playlists = playlists.sort((a, b) => a.createdAt - b.createdAt);
  state.favorites = favorites || [];
  state.recents = recents || [];
  state.progress = progress || {};
  Object.assign(state.settings, settings);
  applySettings();
  syncFavs();
  bindPlayer();
  new ResizeObserver(([e]) => document.documentElement.style.setProperty('--chrome-h', `${e.target.offsetHeight}px`)).observe($('.chrome'));
  bindKeys();
  bindTopSearch();
  document.querySelectorAll('#mainnav [data-icon]').forEach(el => { el.innerHTML = icon(el.dataset.icon); });
  window.addEventListener('hashchange', route);
  window.addEventListener('resize', debounce(sizeGuide, 100));
  route();
  if (!isNative && 'serviceWorker' in navigator && location.protocol !== 'file:') {
    // A new version took over: reload once so the phone runs it now, not on the next open (never mid-stream).
    const hadController = !!navigator.serviceWorker.controller;
    navigator.serviceWorker.addEventListener('controllerchange', () => { if (hadController && !state.playing && !init.reloaded) { init.reloaded = true; location.reload(); } });
    navigator.serviceWorker.register('sw.js').then(r => r.update()).catch(() => {});
  }
  await detectServerProxy();
  detectedServer = true;
  serverKnown();
  tmdbStatus();
  if (!state.current) homeRefresh();   // Home was drawn before the server was known: now fill Movies & Series
  // The first screen rendered before we knew the server was there: ask for its guide data now.
  if (state.current && !location.hash.startsWith('#/guide')) ensureGuide(state.filtered.slice(0, state.rendered));
  else if (location.hash.startsWith('#/guide') && state.current) ensureGuide(guideScope(state.current));
  if (resumeChannel) ensureGuide([resumeChannel]);
  syncNow();
  document.addEventListener('visibilitychange', () => { if (!document.hidden) { syncNow(); scheduleReminders(); } });
  await Promise.all(state.playlists.map(p => loadGuide(p.id)));
  rerenderRows();
  backgroundRefresh();
}

// The VPS server (server/server.js) has a built-in proxy at ./proxy, used automatically.
const serverProxy = { available: false, needsKey: false, base: '' };
const serverProxyUrl = () => `${serverProxy.base}?${state.settings.serverKey ? `key=${encodeURIComponent(state.settings.serverKey)}&` : ''}url={url}`;

async function checkServerProxy(key = state.settings.serverKey, url = state.settings.serverUrl) {
  // The native app isn't served by your server, so it needs its address; the web app uses its own origin.
  const base = isNative ? (url ? `${url.replace(/\/+$/, '')}/proxy` : '') : `${location.origin}${location.pathname.replace(/[^/]*$/, '')}proxy`;
  if (!base) return null;
  const res = await fetch(`${base}${key ? `?key=${encodeURIComponent(key)}` : ''}`, { cache: 'no-store' });
  const info = res.ok && /json/.test(res.headers.get('content-type') || '') ? await res.json() : null;
  return info?.m3uProxy ? { base, ...info } : null;
}

async function detectServerProxy() {
  if (location.protocol === 'file:' && !isNative) return;
  try {
    const info = await checkServerProxy();
    if (!info) return;
    Object.assign(serverProxy, { available: true, base: info.base, needsKey: info.keyRequired && !info.keyValid });
    applySettings();
    if (!state.current && serverProxy.needsKey) showHome();
  } catch {}
}

// ---------- sync across devices ----------
// Favourites, recently watched and progress are also kept on the VPS (server/sync.js), so the
// phone, iPad and laptop all match. The server merges; the app sends its state and adopts the result.
const syncMeta = { favAt: 0, recReset: 0, srcDel: {} };
// A playlist's identity across devices: its Xtream server + username, or its playlist link.
// The same login is often added twice (one "Live TV only", one "VOD only"), so what it keeps is part of it.
function sourceBase(p) {
  if (p.virtual) return '';
  if (p.kind === 'xtream' && p.xtream?.server) { try { return `x:${new URL(p.xtream.server).host.toLowerCase()}|${p.xtream.user}`; } catch { return ''; } }
  return p.sourceUrl ? `u:${p.sourceUrl}` : '';
}
function sourceKey(p) { const b = sourceBase(p); return b ? `${b}|${p.content || 'both'}` : ''; }
function localSources() {
  const out = {};
  for (const p of state.playlists) {
    const k = sourceKey(p);
    if (k) out[k] = { name: p.name, kind: p.kind === 'xtream' ? 'xtream' : 'url', xtream: p.kind === 'xtream' ? p.xtream : undefined, sourceUrl: p.sourceUrl, content: p.content || 'both', epgUrl: p.epgUrl, at: p.createdAt || 1 };
  }
  for (const [k, at] of Object.entries(syncMeta.srcDel || {})) if (!out[k]) out[k] = { deleted: true, at };
  return out;
}
// Playlists added on another device: add them here too (once; a failure is retried at the next sync).
const adoptingSources = new Set();
async function adoptSources(remote) {
  const have = new Set(state.playlists.map(sourceKey).filter(Boolean));
  // Already covered here: the same login keeping everything, or (for a remote "everything") any copy of it.
  const covered = (k, v) => { const base = k.slice(0, k.lastIndexOf('|')); return have.has(`${base}|both`) || ((v.content || 'both') === 'both' && [...have].some(h => h.startsWith(`${base}|`))); };
  for (const [k, v] of Object.entries(remote || {})) {
    if (!v || v.deleted || have.has(k) || covered(k, v) || adoptingSources.has(k) || (syncMeta.srcDel?.[k] || 0) >= (v.at || 0)) continue;
    adoptingSources.add(k);
    try {
      let channels, extra, header = {};
      if (v.kind === 'xtream' && v.xtream?.server) ({ channels, extra } = await xtreamPlaylist(v.xtream));
      else if (v.sourceUrl) { const r = await loadFromUrl(v.sourceUrl); ({ channels, header } = r); extra = { sourceUrl: v.sourceUrl, refreshedAt: Date.now() }; }
      else continue;
      if (state.playlists.some(p => sourceKey(p) === k)) continue;
      const content = v.content || 'both';
      const p = await createPlaylist(v.name || 'Playlist', applyContent(content, channels), { header, content, ...extra, ...(v.epgUrl ? { epgUrl: v.epgUrl } : {}), createdAt: v.at || Date.now() });
      toast(`Added “${playlistTitle(p)}” from your other devices`);
      if (!state.current && !state.playing) rerender();
    } catch (e) { report?.('sync-source', e.message); } finally { adoptingSources.delete(k); }
  }
}
const canSync = () => serverProxy.available && !serverProxy.needsKey;
const serverApi = path => serverProxy.base.replace(/proxy$/, path) + (state.settings.serverKey ? `?key=${encodeURIComponent(state.settings.serverKey)}` : '');
let syncing = null, syncAgain = false;
async function syncNow() {
  if (!canSync()) return;
  if (syncing) { syncAgain = true; return syncing; }
  syncing = (async () => {
    try {
      const res = await fetch(serverApi('sync'), { method: 'POST', cache: 'no-store', body: JSON.stringify({
        favorites: state.favorites, favAt: syncMeta.favAt, recents: state.recents, recReset: syncMeta.recReset, progress: state.progress, sources: localSources() }) });
      if (!res.ok) return;
      const d = await res.json();
      const urls = list => (list || []).map(c => c.url).join('\n');
      let changed = false;
      if (Array.isArray(d.favorites) && urls(d.favorites) !== urls(state.favorites)) {
        state.favorites = d.favorites; syncFavs(); db.set('favorites', d.favorites); changed = true;
        if (state.current?.id === 'fav') { state.current.channels = state.favorites; refreshPlaylistView(); }
      }
      if (Array.isArray(d.recents) && urls(d.recents) !== urls(state.recents)) { state.recents = d.recents; db.set('recents', d.recents); changed = true; }
      if (d.progress) { state.progress = d.progress; db.set('progress', d.progress).catch(() => {}); }
      if (d.sources) adoptSources(d.sources);
      Object.assign(syncMeta, { favAt: d.favAt || syncMeta.favAt, recReset: d.recReset || syncMeta.recReset });
      db.set('syncMeta', syncMeta).catch(() => {});
      // Never redraw Home under a playing channel or while you're typing; keep the scroll position.
      if (changed && !state.current && !state.playing && !document.activeElement?.matches('input, textarea')) rerender();
    } catch {} finally {
      syncing = null;
      if (syncAgain) { syncAgain = false; syncNow(); }
    }
  })();
  return syncing;
}
const syncSoon = debounce(syncNow, 1500);
const syncLater = debounce(syncNow, 20000);   // watch progress ticks every few seconds
function favoritesChanged() { syncMeta.favAt = Date.now(); syncSoon(); }

// ---------- TMDB: trending, backdrops, plots, cast, trailers (the key lives on your server) ----------
let tmdbOn = null;   // null = unknown yet
async function tmdbStatus() {
  if (!canSync()) return false;
  if (tmdbOn === null) { try { tmdbOn = !!(await (await fetch(serverApi('tmdb/status'), { cache: 'no-store' })).json()).configured; } catch { tmdbOn = false; } }
  return tmdbOn;
}
async function tmdbSheet() {
  if (!canSync()) { toast('Connect your server first'); return; }
  const on = await tmdbStatus();
  const v = await formSheet({
    title: 'Film & TV info (TMDB)',
    intro: `Adds what's trending this week, big backdrop images, plots, cast and trailers. Get a free key at themoviedb.org: Settings, API. Either the API Read Access Token (the long one) or the API Key works. It's kept on your server only.${on ? ' A key is already saved: enter a new one to replace it, or leave it empty to remove it.' : ''}`,
    fields: [{ name: 'key', label: 'TMDB key', value: '', required: !on, placeholder: on ? 'Saved on your server' : 'Paste your TMDB key' }],
    submit: 'Save',
  });
  if (!v) return;
  toast('Checking the key with TMDB…', { busy: true });
  try {
    const res = await fetch(serverApi('tmdb/key'), { method: 'POST', body: JSON.stringify({ key: v.key.trim() }) });
    const d = await res.json().catch(() => ({}));
    if (!res.ok) { toast(d.error || `The server replied ${res.status}`); return; }
    tmdbOn = d.configured;
    // Fresh overviews (with trending) for every catalogue.
    vodCache.clear();
    for (const p of state.playlists) for (const k of ['movies', 'series']) db.del(`vod:${vodKey(p, k)}`).catch(() => {});
    toast(d.configured ? 'TMDB connected: trending and artwork are loading' : 'TMDB key removed');
    rerender();
  } catch (e) { toast(e.message); }
}
const tmdbInfoCache = new Map();
async function tmdbInfo(it) {
  if (!(await tmdbStatus())) return null;
  const kind = it.kind === 'series' ? 'tv' : 'movie';
  const name = displayTitle(it.name), year = Number(it.year) || vodMeta(it.name).year || '';
  const k = `${kind}|${name}|${year}`;
  if (!tmdbInfoCache.has(k)) tmdbInfoCache.set(k, fetch(`${serverApi('tmdb/info')}&kind=${kind}&name=${encodeURIComponent(name)}&year=${year}`).then(r => r.ok ? r.json() : null).then(d => (d?.tid ? d : null)).catch(() => null));
  return tmdbInfoCache.get(k);
}
// Fill gaps in the provider's own details with TMDB's (the provider's stay when it has them).
function withTmdb(i, t) {
  if (!t) return i;
  return { ...i, tmdb: t,
    backdrop_path: t.backdrop ? [t.backdrop] : i.backdrop_path,
    plot: i.plot || i.description || t.overview, genre: t.genres?.join(', ') || i.genre,
    cast: i.cast || i.actors || t.cast?.join(', '), director: i.director || t.director,
    youtube_trailer: i.youtube_trailer || t.trailer, duration: i.duration || (t.runtime ? fmtDur(t.runtime * 60) : '') };
}

async function enterServerKey() {
  const v = await formSheet({
    title: isNative ? 'Your server' : 'Server key',
    fields: [
      isNative && { name: 'url', label: 'Server address (for TV guide, sync, movies & downloads)', value: state.settings.serverUrl || '', required: true, type: 'url', placeholder: 'https://your-server.ts.net:8443' },
      { name: 'key', label: 'Server key (shown at the end of the server install)', value: state.settings.serverKey || '', required: true },
    ].filter(Boolean),
    submit: 'Connect',
  });
  if (!v) return;
  const info = await checkServerProxy(v.key, v.url).catch(() => null);
  if (!info?.keyValid) { toast(isNative ? 'Couldn’t connect with that address and key.' : 'That key didn’t work. Check it and try again.'); return; }
  if (isNative) { state.settings.serverUrl = v.url.replace(/\/+$/, ''); Object.assign(serverProxy, { available: true, base: info.base }); }
  state.settings.serverKey = v.key;
  serverProxy.needsKey = false;
  await db.set('settings', state.settings);
  applySettings();
  toast('Connected to your server');
  syncNow();
  rerender();
}

// Last-resort playback: route a raw stream through our own server's /hls repackager
// (ffmpeg -> HLS) so Safari can play MPEG-TS it otherwise refuses. Our server only.
// Buffer levels: segments the server waits for before playback starts, and how far behind live to start.
// Auto begins fast and steps up on its own when a channel keeps stalling.
const BUFFER_LEVELS = { fast: { ready: 1, behind: 4, sync: 2 }, balanced: { ready: 2, behind: 8, sync: 3 }, stable: { ready: 3, behind: 14, sync: 5 } };
let autoLevel = 'fast', stallTimes = [];
const bufferLevel = () => BUFFER_LEVELS[state.settings.buffer === 'auto' || !state.settings.buffer ? autoLevel : state.settings.buffer] || BUFFER_LEVELS.fast;
function noteStall() {
  if (!state.playing || state.settings.buffer !== 'auto' && state.settings.buffer) return;
  const now = Date.now();
  if (now - lastTune < 12_000) return;   // starting up, not a stall
  stallTimes = [...stallTimes.filter(t => now - t < 120_000), now];
  if (stallTimes.length >= 3 && autoLevel !== 'stable') {
    autoLevel = autoLevel === 'fast' ? 'balanced' : 'stable';
    stallTimes = [];
    player.liveSync = bufferLevel().sync;
    toast(`Kept stalling: using a ${autoLevel} buffer from now on`, { action: 'Reload now', fn: () => playChannel(state.playing.list, state.playing.index) });
  }
}
let transcodeVod = false;   // set per play: movies, episodes and catch-up get an on-demand (seekable) stream
const liveEnc = new Set();  // live channel urls that showed no picture: converted to H.264 from then on
const liveFmp4 = new Set(); // Safari: live channels that showed no picture in TS: re-packaged as fMP4 (HEVC), untouched
// The server hands VOD over as a complete playlist with the real length, so the video element's own
// time and duration are the truth; seeking anywhere is a plain seek (the server makes segments on demand).
let vodTrack = 0, vodInfo = null, subTrackEl = null, vodEnc = false, vodInfoFor = '';   // audio track in use, the file's probe, subtitles, "convert video for this device"
// Can this device decode the file's video as-is? Chrome has no HEVC/10-bit; Safari on Apple silicon has HEVC.
const IS_SAFARI = /^((?!chrome|chromium|crios|fxios|android).)*safari/i.test(navigator.userAgent);
function canDecode(v) {
  if (!v) return true;
  if (v.chroma444) return false;   // 4:2:2 / 4:4:4 sources: convert (interlaced plays fine as it is)
  if (v.codec === 'h264') return !v.bit10;
  // Only Apple's own player is trusted with HEVC/10-bit. Chrome claims support, then plays sound with no picture.
  if (!IS_SAFARI) return false;
  if (v.codec === 'hevc') return true;   // Safari plays HEVC (8- and 10-bit) as it is: the server packages it as fMP4
  const type = v.codec === 'hevc' ? `video/mp4; codecs="${v.bit10 ? 'hvc1.2.4.L153.B0' : 'hvc1.1.6.L123.B0'}"`
    : v.codec === 'av1' ? `video/mp4; codecs="av01.0.08M.${v.bit10 ? '10' : '08'}"` : v.codec === 'vp9' ? `video/mp4; codecs="vp09.00.10.${v.bit10 ? '10' : '08'}"` : '';
  return !!type && !!video.canPlayType(type);
}
const vodPos = () => video.currentTime;
const vodTotal = () => (Number.isFinite(video.duration) ? video.duration : 0) || seekRange()?.end || 0;
// "1080p · HEVC · HDR" from the server's probe of the file.
function qualityLabel() {
  const v = vodInfo?.video;
  if (!v) return '';
  const h = v.height >= 2000 ? '4K' : v.height ? `${v.height}p` : '';
  const codec = { hevc: 'HEVC', h264: 'H.264', av1: 'AV1', vp9: 'VP9' }[v.codec] || '';
  return [h, codec, v.hdr ? 'HDR' : ''].filter(Boolean).join(' · ');
}
// Two minutes before an episode ends, ask the server to start cutting the next one so it opens instantly.
let warmedNext = '';
function warmNextEpisode() {
  const pl = state.playing, c = nowChannel();
  if (!pl || !c || c.kind !== 'episode' || !Number.isFinite(video.duration) || video.duration - video.currentTime > 120) return;
  const next = pl.list[pl.index + 1];
  if (!next || warmedNext === next.url) return;
  warmedNext = next.url;
  const tc = transcodeUrl(next.url);
  if (tc) fetch(tc.replace('/hls?', '/hls/warm?'), { keepalive: true }).catch(() => {});
}
// ---- Next episode countdown ----
let nextUpTimer = 0;
function showNextUp(next) {
  const el = $('#next-up');
  if (!el) return step(1);
  let left = 10;
  const draw = () => { el.innerHTML = `<div class="nu-card">${logoHtml(next)}<div class="nu-text"><span class="muted small-caps">Next episode</span><b>${esc(next.name)}</b><span class="muted">Starting in ${left}s</span></div>
    <div class="nu-actions"><button class="btn primary" data-nu="play">${icon('play')} Play now</button><button class="btn" data-nu="cancel">Cancel</button></div></div>`; };
  draw();
  el.hidden = false;
  el.onclick = e => { const b = e.target.closest('[data-nu]'); if (!b) return; hideNextUp(); if (b.dataset.nu === 'play') step(1); };
  clearInterval(nextUpTimer);
  nextUpTimer = setInterval(() => { left--; if (left <= 0) { hideNextUp(); step(1); } else draw(); }, 1000);
}
function hideNextUp() { clearInterval(nextUpTimer); const el = $('#next-up'); if (el) { el.hidden = true; el.innerHTML = ''; } }

// ---- Skip intro, learned from you ----
// Providers don't mark intros. The first time you jump forward past one on an episode (20s–4min, within the
// first 6 minutes), the span is remembered for that series and offered as "Skip intro" on its other episodes.
const seriesKeyOf = c => c?.kind === 'episode' ? String(c.group || c.name).replace(/\s*·\s*Season.*$/i, '').toLowerCase() : null;
let seekFrom = 0, lastT = 0;
function learnIntro() {
  const c = nowChannel();
  const from = lastT, to = video.currentTime;
  const key = seriesKeyOf(c);
  if (!key || !Number.isFinite(from)) return;
  const len = to - from;
  if (from < 360 && len >= 20 && len <= 240) {
    state.intros ||= {};
    state.intros[key] = { from: Math.max(0, Math.floor(from)), to: Math.floor(to), at: Date.now() };
    db.set('intros', state.intros).catch(() => {});
    toast('Got it: this will be offered as "Skip intro" on other episodes');
  }
}
function updateSkipIntro() {
  const c = nowChannel(), el = $('#skip-intro');
  const t = video.currentTime;
  if (el) {
    const intro = c && state.intros?.[seriesKeyOf(c)];
    const show = intro && t >= intro.from - 3 && t < intro.to - 3 && !(state.progress[c.url]?.skipped === intro.to);
    el.hidden = !show;
    if (show) el.onclick = () => { seekVod(intro.to); el.hidden = true; };
  }
  if (!video.seeking) lastT = t;
}
// Audio sync: a per-channel / per-title correction in ms (+ = sound later, - = sound earlier), applied by the
// server when it repackages the stream. Remembered, like TiviMate's audio delay.
const avSyncFor = url => Number(state.settings.avSync?.[url]) || 0;
function setAvSync(c, ms) {
  if (avSyncFor(c.url) === ms) return;
  unwarm(c.url);   // free the provider connection held by the old timing before the new one opens
  state.settings.avSync = { ...(state.settings.avSync || {}) };
  if (ms) state.settings.avSync[c.url] = ms; else delete state.settings.avSync[c.url];
  db.set('settings', state.settings);
  const pos = transcodeVod ? vodPos() : 0;
  toast(ms ? `Audio ${ms > 0 ? 'later' : 'earlier'} by ${Math.abs(ms) / 1000}s` : 'Audio sync reset');
  const list = state.playing.list, index = state.playing.index;
  if (transcodeVod) player.play(streamOf(c)).then(ok => { if (ok && pos > 2) seekVod(pos); }).catch(() => playChannel(list, index));
  else playChannel(list, index);
}
function avSyncMenu(c) {
  const cur = avSyncFor(c.url);
  const steps = [-1000, -500, -300, -200, -100, 0, 100, 200, 300, 500, 1000];
  const name = ms => ms === 0 ? 'In sync (no change)' : `${ms < 0 ? 'Sound earlier' : 'Sound later'} ${Math.abs(ms) / 1000}s`;
  menu('Audio sync', [
    { label: 'Lips move before the sound? Pick “Sound earlier”.', fn: () => avSyncMenu(c) },
    ...steps.map(ms => ({ label: `${ms === cur ? '✓ ' : ''}${name(ms)}`, fn: () => setAvSync(c, ms) })),
    cur && !steps.includes(cur) && { label: `✓ ${name(cur)}`, fn: () => {} },
  ].filter(Boolean));
}
function switchAudioTrack(i) {
  const c = nowChannel();
  if (!c || !transcodeVod) return;
  const pos = vodPos();
  vodTrack = i;
  toast(`Audio: ${vodInfo?.audio?.[i]?.label || `track ${i + 1}`}`);
  const list = state.playing.list, index = state.playing.index;
  player.play(streamOf(c)).then(ok => { if (ok && pos > 2) seekVod(pos); }).catch(() => playChannel(list, index));
}
// Subtitles: the server writes the file's text subtitles as WebVTT while it cuts the video; the app polls
// that file every few seconds and adds new cues to a text track, so they keep pace with playback.
let subPoll = 0, subWait = 0, subTrack = null, subIdx = -1;
const subCueKeys = new Set();
function clearSubtitles() {
  clearInterval(subPoll); clearTimeout(subWait); subPoll = subWait = 0; subIdx = -1;
  if (subTrack) { subTrack.mode = 'disabled'; try { [...subTrack.cues || []].forEach(cue => subTrack.removeCue(cue)); } catch {} }
  subCueKeys.clear();
  if (subTrackEl) { subTrackEl.remove(); subTrackEl = null; }
}
const vttMs = t => { const p = t.trim().split(':').map(Number); return p.length === 3 ? p[0] * 3600 + p[1] * 60 + p[2] : p[0] * 60 + p[1]; };
async function pullSubtitles(c, i) {
  const tc = transcodeUrl(c.url);
  if (!tc || nowChannel() !== c || subIdx !== i) return;
  try {
    const r = await fetch(tc.replace('/hls?', '/hls/subs?') + `&s=${i}`, { cache: 'no-store' });
    if (!r.ok) return;
    const text = await r.text();
    if (nowChannel() !== c || subIdx !== i || !subTrack) return;
    let added = 0;
    for (const block of text.split(/\n\n+/)) {
      const lines = block.split('\n'), ti = lines.findIndex(l => l.includes('-->'));
      if (ti < 0) continue;
      const [a, b] = lines[ti].split('-->');
      const body = lines.slice(ti + 1).join('\n').trim();
      const key = `${a.trim()}|${body}`;
      if (!body || subCueKeys.has(key)) continue;
      subCueKeys.add(key);
      try { subTrack.addCue(new VTTCue(vttMs(a), vttMs(b.split(' ')[0]), body)); added++; } catch {}
    }
    if (added && subCueKeys.size === added) toast(`Subtitles: ${vodInfo?.subs?.[i]?.label || 'on'}`);
  } catch {}
}
function useSubtitles(i) {
  const c = nowChannel();
  clearSubtitles();
  if (i < 0 || !c) return;
  subIdx = i;
  if (!subTrack) subTrack = video.addTextTrack('subtitles', 'Subtitles', 'en');
  subTrack.mode = 'showing';
  for (const t of video.textTracks) if (t !== subTrack) t.mode = 'disabled';
  toast('Loading subtitles…', { busy: true });
  pullSubtitles(c, i);
  subPoll = setInterval(() => pullSubtitles(c, i), 3000);
  // never leave the spinner up: a quiet stretch of the film simply has no lines yet
  subWait = setTimeout(() => { if (subIdx === i && !subCueKeys.size) toast('Subtitles on – lines appear as they come'); }, 12000);
}

function seekVod(t) {
  const total = vodTotal();
  video.currentTime = Math.max(0, Math.min(t, total ? total - 0.5 : t));
  if (video.paused) video.play().catch(() => {});
}
function transcodeUrl(url) {
  if (isNative) return null;                 // the native app plays raw streams itself
  if (!serverProxy.available) return null;   // only our own server exposes /hls
  const key = state.settings.serverKey || '';
  if (serverProxy.needsKey && !key) return null;
  const b = bufferLevel();
  if (/^blob:/.test(url)) return null;       // a downloaded file plays from storage
  return `/hls?${key ? `key=${encodeURIComponent(key)}&` : ''}ready=${b.ready}&behind=${b.behind}${transcodeVod ? `&vod=1${vodTrack ? `&track=${vodTrack}` : ''}${vodEnc ? '&enc=1' : IS_SAFARI && vodInfo?.video?.codec === 'hevc' ? '&fmp4=1' : ''}${IS_SAFARI && !vodEnc ? '&surround=1' : ''}` : liveEnc.has(url) ? '&enc=1' : liveFmp4.has(url) ? '&fmp4=1' : ''}${avSyncFor(url) ? `&av=${avSyncFor(url)}` : ''}&url=${encodeURIComponent(url)}`;
}

// Pre-warm: ask the server to start repackaging a stream now, so playing it a moment later is near-instant.
const warmed = new Map();   // url -> time
function warm(url) {
  const tc = url && transcodeUrl(url);
  if (!tc || /\.m3u8(\?|#|$)/i.test(url)) return;
  if (Date.now() - (warmed.get(url) || 0) < 20_000) return;
  const cur = nowChannel();
  if (cur && cur.url !== url) unwarm(cur.url);   // providers allow one connection: free it before opening the next
  warmed.set(url, Date.now());
  fetch(tc.replace('/hls?', '/hls/warm?'), { keepalive: true }).catch(() => {});
}
// Tell the server to drop a stream we've switched away from (IPTV accounts have few connections).
function unwarm(url) {
  const tc = url && transcodeUrl(url);   // includes the VOD start offset, so it stops the right session
  if (!tc) return;
  warmed.delete(url);
  fetch(tc.replace('/hls?', '/hls/stop?'), { keepalive: true }).catch(() => {});
}

const ACCENTS = { '': '#5b93ff', teal: '#2ec4b6', green: '#3ccf7a', purple: '#9b7bff', orange: '#ff9f43', red: '#ff5a6e' };
function applySettings() {
  const s = state.settings, root = document.documentElement;
  root.dataset.density = s.density || 'standard';
  root.classList.toggle('no-logos', s.logos === false);
  root.classList.toggle('numbers', !!s.numbers);
  root.style.setProperty('--accent', ACCENTS[s.accent] || ACCENTS['']);
  video.controls = !!s.nativeControls;
  $('.stage')?.classList.toggle('native', !!s.nativeControls);
  net.proxy = state.settings.proxy || (serverProxy.available && !serverProxy.needsKey ? serverProxyUrl() : '');
  net.streamProxy = state.settings.streamProxy;
  player.tsToHls = state.settings.tsToHls;
  player.wrap = streamUrl;
  player.preferNative = isNative;
  player.transcode = transcodeUrl;
  player.liveSync = bufferLevel().sync;
}

// groupsOf() walks every channel; cache the result per playlist+tab and drop it when the playlist changes.
function cachedGroups(p, items, key) {
  if (!p || p.virtual) return groupsOf(items);
  p._groupCache ??= {};
  return p._groupCache[key] ??= groupsOf(items);
}
const invalidate = p => { if (p) { delete p._groupCache; delete p._kinds; delete p._hasVod; } };

async function save(p) {
  p.updatedAt = Date.now();
  invalidate(p);
  if (p.id === 'fav') { state.favorites = p.channels; syncFavs(); await db.set('favorites', p.channels); favoritesChanged(); }
  else if (p.id === 'recent') {
    // An edit (remove/reorder) replaces the list everywhere; stamp times so the server keeps this order.
    syncMeta.recReset = Date.now();
    p.channels.forEach((c, i) => { c.at = syncMeta.recReset - i; });
    state.recents = p.channels; await db.set('recents', p.channels); syncSoon();
  }
  else if (!p.virtual) await db.putPlaylist(p);
}

async function createPlaylist(name, channels, extra = {}) {
  const p = { id: uid(), name: name || 'Playlist', channels, header: {}, createdAt: Date.now(), updatedAt: Date.now(), ...extra };
  await db.putPlaylist(p);
  state.playlists.push(p);
  syncSoon();
  navigator.storage?.persist?.().catch(() => {});
  return p;
}

function virtualPlaylist(id) {
  if (id === 'fav') return { id, name: 'Favorites', virtual: true, header: {}, channels: state.favorites };
  if (id === 'recent') return { id, name: 'Recently watched', virtual: true, readOnly: true, header: {}, channels: state.recents };
  const channels = state.playlists.flatMap(p => p.channels
    .filter(c => visibleGroup(p, c.group || ''))
    .map(c => ({ ...c, group: p.name, src: p.id })));
  return { id: 'all', name: 'All channels', virtual: true, readOnly: true, header: {}, channels };
}

// ---------- groups: hidden & parental lock ----------
function visibleGroup(p, g) {
  if (!p) return true;
  if (p.virtual) return state.unlocked || !state.playlists.some(x => x.lockedGroups?.includes(g));   // locks follow the channel into Favorites, Recents, All
  if (p.hiddenGroups?.includes(g)) return false;
  return !(p.lockedGroups?.includes(g) && !state.unlocked);
}

async function hashPin(pin) {
  try {
    const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`m3u-player:${pin}`));
    return [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
  } catch {
    return `plain:${pin}`;
  }
}

async function unlock() {
  if (state.unlocked || !state.settings.pinHash) return true;
  const v = await formSheet({ title: 'Enter parental PIN', fields: [{ name: 'pin', label: 'PIN', type: 'password', inputmode: 'numeric', required: true }], submit: 'Unlock' });
  if (!v) return false;
  if (await hashPin(v.pin) !== state.settings.pinHash) { toast('Wrong PIN'); return false; }
  state.unlocked = true;
  toast('Unlocked until you close the app');
  return true;
}

async function setPin() {
  const v = await formSheet({
    title: state.settings.pinHash ? 'Change parental PIN' : 'Set parental PIN',
    fields: [
      { name: 'pin', label: 'New PIN (4+ digits)', type: 'password', inputmode: 'numeric', required: true },
      { name: 'again', label: 'Repeat PIN', type: 'password', inputmode: 'numeric', required: true },
    ],
    submit: 'Save PIN',
  });
  if (!v) return false;
  if (v.pin.length < 4 || v.pin !== v.again) { toast(v.pin !== v.again ? "PINs don't match" : 'Use at least 4 digits'); return false; }
  state.settings.pinHash = await hashPin(v.pin);
  state.unlocked = false;
  await db.set('settings', state.settings);
  toast('PIN saved. Lock groups from ⋯ → Manage groups.');
  return true;
}

// ---------- routing ----------
let multiActive = false;
// Where you were on each screen, so Back lands on the same group, search and row.
const viewMemo = new Map();
let memoKey = null;
function rememberView() {
  if (!memoKey) return;
  viewMemo.set(memoKey, state.current && !location.hash.startsWith('#/guide')
    ? { y: scrollY, tab: state.tab, group: state.group, query: state.query, rendered: state.rendered }
    : { y: scrollY });
}
function route() {
  rememberView();
  memoKey = location.hash || '#/';
  const [, kind, id] = (location.hash || '#/').slice(1).split('/');
  state.selecting = false;
  state.reordering = false;
  state.selected.clear();
  observer.disconnect();
  if (multiActive && kind !== 'multi') stopMulti();
  view.onclick = null;
  view.classList.toggle('wide', kind === 'guide' || kind === 'multi');
  view.classList.toggle('pl', kind === 'p' || kind === 'fav' || kind === 'recent' || kind === 'all');   // desktop: groups in a left sidebar
  if (kind !== 'guide') guideDay = 0;
  if (kind === 'p' || kind === 'guide') {
    const p = byId(id);
    if (p) return kind === 'p' ? showPlaylist(p) : showGuide(p);
    location.hash = '#/';
    return;
  }
  if (kind === 'fav' || kind === 'recent' || kind === 'all') return showPlaylist(virtualPlaylist(kind));
  if (kind === 'live' || kind === 'movies' || kind === 'series' || kind === 'tvguide') return openSection(kind);
  if (kind === 'multi') return showMulti();
  if (kind === 'downloads') return showDownloads();
  showHome();
  const y = viewMemo.get(memoKey)?.y;
  if (y) requestAnimationFrame(() => scrollTo(0, y));
}

function rerender() {
  const [, kind] = (location.hash || '#/').slice(1).split('/');
  if (kind === 'guide') return state.current && showGuide(state.current);
  if (kind === 'multi') return;
  if (kind === 'downloads') return showDownloads();
  if (state.current) refreshPlaylistView({ keep: true });   // an edit: stay on the same row
  else { const y = scrollY; showHome(); scrollTo(0, y); }
}

function setTopbar({ title, back = false, actions = [] }) {
  queueMicrotask(updateNav);
  $('#title').textContent = title;
  $('#title').onclick = back ? null : () => { if (location.hash && location.hash !== '#/') location.hash = '#/'; else scrollTo({ top: 0, behavior: 'smooth' }); };
  $('#title').classList.toggle('home-link', !back);
  const b = $('#back');
  b.hidden = !back;
  b.innerHTML = icon('back');
  b.onclick = () => { location.hash = back || '#/'; };
  const box = $('#actions');
  box.innerHTML = actions.map((a, i) =>
    `<button class="icon-btn ${a.active ? 'active' : ''}" data-i="${i}" aria-label="${esc(a.label)}" title="${esc(a.label)}">${icon(a.icon)}</button>`).join('');
  box.onclick = e => { const btn = e.target.closest('button'); if (btn) actions[+btn.dataset.i].fn(); };
  document.title = back ? `${title} · M3U Player` : 'M3U Player';
}

// ---------- home ----------
function card(target, ic, title, sub, menuId) {
  return `<li class="card" data-go="${target}">
    <span class="card-ico">${icon(ic)}</span>
    <span class="card-text"><span class="card-title">${esc(title)}</span><span class="muted">${sub}</span></span>
    ${menuId ? `<button class="icon-btn" data-menu="${menuId}" aria-label="Playlist options">${icon('more')}</button>` : ''}
  </li>`;
}

function continueWatching() {
  return Object.values(state.progress).filter(e => !e.done && !e.hidden && e.t > 30).sort((a, b) => b.at - a.at).slice(0, 20);
}

// A display name for a playlist — never a raw URL/host (which is what a nameless
// import defaults to), so the home list stays readable.
const URLISH = /^(https?:\/\/)?[a-z0-9-]+(\.[a-z0-9-]+)+(\/|:|$)/i;
function playlistTitle(p) {
  const n = (p.name || '').trim();
  return !n || URLISH.test(n) ? 'My playlist' : n;
}

function playlistSub(p) {
  const k = m3uKinds(p);
  const vodCount = k ? k.movies.length + k.series.length : 0;
  const parts = p.content === 'vod'
    ? [k ? `${plural(k.movies.length, 'movie')} · ${plural(k.series.length, 'series')}` : 'Movies & series']
    : [plural(p.channels.length, 'channel'), ...(vodCount ? [`${vodCount.toLocaleString()} VOD`] : [])];
  if (p.kind === 'xtream') {
    parts.unshift('Xtream');
    const exp = Number(p.userInfo?.exp_date);
    if (exp) parts.push(`expires ${new Date(exp * 1000).toLocaleDateString()}`);
  } else {
    parts.push(plural(cachedGroups(p, p.channels, 'all').length, 'group'));
  }
  if (guides.get(p.id)?.channelCount) parts.push('TV guide');
  return parts.join(' · ');
}

// TiviMate-style "pick up where you left off": the last channel, what's on it now, one tap to play.
let resumeChannel = null;
let cwLiveHidden = '';
db.get('cwLiveHidden').then(v => { cwLiveHidden = v || ''; }).catch(() => {});
function resumeCardHtml(c) {
  resumeChannel = null;
  if (!c || state.settings.resumeCard === false || !isLiveChannel(c)) return '';
  resumeChannel = c;
  const g = guideFor(c), nn = g && g.nowNext(c);
  const now = nn?.now;
  const pct = now ? ((Date.now() - now[0]) / (now[1] - now[0])) * 100 : 0;
  ensureGuide([c]);
  return `<button class="resume" data-resume>${logoHtml(c)}
    <span class="row-text"><span class="muted small-caps">Continue watching</span><span class="row-name">${esc(c.name)}</span>
      ${now ? `<span class="muted">${esc(now[2])} · ends ${fmtTime(now[1])}</span><span class="bar"><i style="width:${pct.toFixed(1)}%"></i></span>` : ''}</span>
    <span class="resume-play">${icon('play')}</span></button>`;
}

// Home search: channels from every playlist plus movies and series from each Xtream catalogue (server search).
let homeSearchSeq = 0;
async function homeSearch(q, box = $('#home-results')) {
  if (!box) return;
  const seq = ++homeSearchSeq;
  if (q.length < 2) { box.hidden = true; box.innerHTML = ''; return; }
  const t = q.toLowerCase();
  const chans = [];
  for (const p of state.playlists) {
    for (const c of (m3uKinds(p)?.live || p.channels)) {
      if (chans.length >= 8) break;
      if (isLiveChannel(c) && c.name.toLowerCase().includes(t) && visibleGroup(p, c.group || '')) chans.push({ ...c, src: p.id });
    }
    if (chans.length >= 8) break;
  }
  shelfItems.set('hs-chan', chans);
  const section = (title, key, items, row) => items.length ? `<div class="hs-title">${title}</div>${items.map((it, k) => row(it, key, k)).join('')}` : '';
  const chanRow = (c, key, k) => `<div class="hs-row" tabindex="-1" data-shelf="${key}" data-k="${k}">${logoHtml(c)}<span class="row-text"><span class="row-name">${esc(c.name)}</span><span class="muted">${esc(prettyGroup(c.group) || '')}</span></span>${icon('play')}</div>`;
  const vodRow = (it, key, k) => `<div class="hs-row" tabindex="-1" data-shelf="${key}" data-k="${k}"><span class="hs-poster">${it.logo ? `<img src="${esc(thumb(it.logo, 120))}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}</span><span class="row-text"><span class="row-name">${esc(niceName(it))}</span><span class="muted">${[it.kind === 'series' ? 'Series' : 'Movie', it.rating && it.rating < 9.5 ? `★ ${it.rating.toFixed(1)}` : '', it.copies > 1 ? `${it.copies} versions` : ''].filter(Boolean).map(esc).join(' · ')}</span></span></div>`;
  box.innerHTML = section('Channels', 'hs-chan', chans, chanRow) || '<div class="muted hs-empty">Searching…</div>';
  box.hidden = false;
  if (!canSync()) { if (!chans.length) box.innerHTML = '<div class="muted hs-empty">No channels match.</div>'; return; }
  const movies = [], series = [];
  await Promise.all(state.playlists.filter(p => p.kind === 'xtream' && p.content !== 'live').map(async p => {
    try {
      const [m, s] = await Promise.all([vodApi(p, 'movies', { q }), vodApi(p, 'series', { q })]);
      const cats = vodCache.get(vodKey(p, 'movies'))?.cats || [], scats = vodCache.get(vodKey(p, 'series'))?.cats || [];
      const en = l => state.settings.vodLanguage !== 'all' ? l.filter(it => isEnglishGroup(it.group || '')) : l;
      movies.push(...oneOfEach(en(toItems(p, 'movies', cats, (m.items || []).slice(0, 80)))).slice(0, 8));
      series.push(...oneOfEach(en(toItems(p, 'series', scats, (s.items || []).slice(0, 80)))).slice(0, 8));
    } catch {}
  }));
  if (seq !== homeSearchSeq) return;
  shelfItems.set('hs-movie', movies.slice(0, 8)); shelfItems.set('hs-series', series.slice(0, 8));
  box.innerHTML = (section('Channels', 'hs-chan', chans, chanRow) + section('Movies', 'hs-movie', movies.slice(0, 8), vodRow) + section('Series', 'hs-series', series.slice(0, 8), vodRow)) || '<div class="muted hs-empty">Nothing matches.</div>';
}

// ---------- Home hub ----------
// Everything worth watching in one place: a featured carousel, what's on live now, new and top-rated
// movies and series, and "Because you watched…". Catalogues load in the background and Home refreshes.
let detectedServer = false, serverKnown = null;
const serverReady = new Promise(r => { serverKnown = r; });
const homeRefresh = debounce(() => { if (!state.current && (!location.hash || location.hash === '#/')) { const y = scrollY; showHome(); scrollTo(0, y); } }, 500);
function homeHub(pl) {
  const hub = { hero: [], onNow: [], shelves: { movies: [], series: [] }, vodLink: {} };
  for (const p of pl.filter(p => p.kind === 'xtream' && p.content !== 'live')) {
    for (const tab of ['movies', 'series']) {
      const e = vodCache.get(vodKey(p, tab));
      // Only through your server (the provider's own catalogue is 50-70MB); it may still be connecting.
      // The catalogue saved on this device shows at once; only with nothing saved does Home wait for the server.
      if (!e || (Array.isArray(e) && canSync())) { if (e) vodCache.delete(vodKey(p, tab)); hub.loading = true; loadVod(p, tab).then(homeRefresh).catch(() => {}); continue; }
      if (Array.isArray(e)) continue;
      hub.vodLink[tab] ||= `#/p/${p.id}`;
      const what = tab === 'movies' ? 'movies' : 'series';
      const withArt = l => (l || []).filter(it => it.logo);
      if (e.trending?.length >= 5) hub.shelves[tab].push({ title: `Top 10 ${what} this week`, key: `h-trend-${tab}`, items: e.trending.slice(0, 10), top10: true });
      if (e.trending?.length) hub.hero.push(...e.trending.filter(it => it.tm?.backdrop).slice(0, 4).map(it => ({ ...it, heroLabel: `#${e.trending.indexOf(it) + 1} trending ${tab === 'series' ? 'series' : 'movie'}` })));
      if (e.top?.length) hub.shelves[tab].push({ title: 'Popular now', key: `h-top-${tab}`, items: withArt(e.top).slice(0, 30) });
      const fresh = withArt(e.fresh).length >= 8 ? withArt(e.fresh) : withArt(e.recent);
      if (fresh.length) hub.shelves[tab].push({ title: withArt(e.fresh).length >= 8 ? 'New releases' : `Recently added ${what}`, key: `h-new-${tab}`, items: fresh.slice(0, 30) });
      if (!e.trending?.length) hub.hero.push(...withArt(e.top).slice(0, 4));
    }
  }
  // Because you watched: the newest film or episode with progress, then more from its group.
  const last = Object.values(state.progress).filter(x => x.item && (x.item.kind === 'movie' || x.item.kind === 'episode')).sort((a, b) => b.at - a.at)[0];
  if (last) {
    const p = byId(last.item.src), tab = last.item.kind === 'movie' ? 'movies' : 'series';
    const e = p && vodCache.get(vodKey(p, tab));
    const group = last.item.kind === 'movie' ? last.item.group : null;
    if (e && !Array.isArray(e) && group) {
      const items = e.groups.get(group);
      if (!items) requestVod(p, tab, { group });
      else {
        const more = items.filter(it => it.url !== last.item.url).slice(0, 30);
        if (more.length) hub.shelves.movies.unshift({ title: `Because you watched ${vodMeta(last.item.name).title || last.item.name}`, key: 'h-because', items: more });
      }
    }
  }
  // On now: channels from your Home groups (else recent channels) with a programme on right now.
  const live = pl.flatMap(p => {
    const k = m3uKinds(p), chans = k ? k.live : p.channels;
    const want = new Set(p.homeGroups || []);
    return want.size ? chans.filter(c => want.has(c.group)).map(c => ({ ...c, src: p.id })) : [];
  });
  const pool = (live.length ? live : state.recents.filter(isLiveChannel)).slice(0, 80);
  ensureGuide(pool);
  for (const c of pool) {
    const g = guideFor(c), now = g && g.nowNext(c).now;
    if (now) hub.onNow.push({ c, now });
    if (hub.onNow.length >= 24) break;
  }
  shelfItems.set('onnow', hub.onNow);
  // Featured: a mix of top movies and series, alternating.
  const mix = [];
  const ms = hub.hero.filter(it => it.kind === 'movie'), ss = hub.hero.filter(it => it.kind === 'series');
  for (let i = 0; mix.length < 6 && (i < ms.length || i < ss.length); i++) { if (ms[i]) mix.push(ms[i]); if (ss[i]) mix.push(ss[i]); }
  hub.hero = mix.slice(0, 6);
  shelfItems.set('hero', hub.hero);
  for (const tab of ['movies', 'series']) for (const sh of hub.shelves[tab]) shelfItems.set(sh.key, sh.items);
  // Streaming services: the provider's per-platform groups (Netflix, Disney+…) as one row of tiles.
  hub.services = [];
  for (const p of pl.filter(p => p.kind === 'xtream' && p.content !== 'live')) {
    for (const tab of ['movies', 'series']) {
      const e = vodCache.get(vodKey(p, tab));
      if (!e || Array.isArray(e)) continue;
      for (const [name, re, c1, c2] of SERVICES) {
        const g = e.groupList.find(g => re.test(g.name) && !/kids|anime/i.test(g.name));
        if (g && !hub.services.some(s => s.name === name && s.tab === tab)) hub.services.push({ name, c1, c2, tab, pid: p.id, group: g.name, count: g.count });
      }
    }
  }
  return hub;
}
// What people read: films and series as "Title (Year)", episodes as "Show · S1 E1"; channels unchanged.
function niceName(c) {
  if (!c) return '';
  if (c.kind === 'episode') return cwTitle(c);
  if (c.kind === 'movie' || c.kind === 'series') { const y = c.year || vodMeta(c.name).year; return `${displayTitle(c.name)}${y ? ` (${y})` : ''}`; }
  return c.name;
}
// "MobLand · S1 E1" instead of "EN - MobLand (2025) (GB) - S01E01 - Stick or Twist".
function cwTitle(it) {
  if (it.kind !== 'movie' && it.kind !== 'episode') return it.name;
  const m = vodMeta(it.name), t = displayTitle(it.name);
  return m.episode != null ? `${t} · S${m.season || 1} E${m.episode}` : t;
}
const SERVICES = [
  ['Netflix', /netflix|^(4k-)?nf\b/i, '#e50914', '#5c0408'],
  ['Disney+', /disney|^(4k-)?d\+/i, '#1f4fd8', '#0b1d5c'],
  ['Apple TV+', /apple|^(4k-)?a\+/i, '#6b7280', '#1f2227'],
  ['Prime Video', /prime|amazon|^(4k-)?amz/i, '#1aa3e8', '#0b3f5c'],
  ['Max', /\bhbo\b|\bmax\b/i, '#6d35e8', '#251050'],
  ['Paramount+', /paramount/i, '#0a64ff', '#062a6b'],
  ['Hulu', /hulu/i, '#1ce783', '#0b4d2c'],
  ['Peacock', /peacock/i, '#f2b705', '#4d3a02'],
  ['Sky', /\bsky\b/i, '#00a3ff', '#0a2a5c'],
];
function servicesHtml(list) {
  const tiles = list.filter(s => s.tab === 'movies').concat(list.filter(s => s.tab === 'series' && !list.some(m => m.tab === 'movies' && m.name === s.name)));
  return tiles.length >= 2 ? `<div class="row-head"><h3>Streaming services</h3></div><ul class="shelf-row home-shelf services">${tiles.map((s, k) => `<li class="svc" data-svc="${k}" style="--c1:${s.c1};--c2:${s.c2}"><b>${esc(s.name)}</b><span>${s.count.toLocaleString()} ${s.tab === 'series' ? 'series' : 'titles'}</span></li>`).join('')}</ul>` : '';
}
// Mouse users: arrow buttons on side-scrolling rows (phones and trackpads just swipe).
function addRowArrows(root) {
  if (!matchMedia('(hover: hover) and (pointer: fine)').matches) return;
  root.querySelectorAll('.home-shelf, .cont-row, .discover .shelf-row').forEach(row => {
    if (row.parentNode.classList.contains('rowwrap')) return;
    const w = document.createElement('div');
    w.className = 'rowwrap';
    row.before(w); w.append(row);
    for (const d of [-1, 1]) {
      const b = document.createElement('button');
      b.className = `row-arrow ${d < 0 ? 'l' : 'r'}`; b.type = 'button'; b.setAttribute('aria-label', d < 0 ? 'Scroll left' : 'Scroll right');
      b.innerHTML = icon(d < 0 ? 'prev' : 'next');
      b.onclick = e => { e.stopPropagation(); row.scrollBy({ left: d * row.clientWidth * 0.85, behavior: 'smooth' }); };
      w.append(b);
    }
    const upd = () => { w.classList.toggle('at-start', row.scrollLeft < 8); w.classList.toggle('at-end', row.scrollLeft + row.clientWidth >= row.scrollWidth - 8); };
    row.addEventListener('scroll', upd, { passive: true });
    requestAnimationFrame(upd);
  });
}
// Search in the top bar (computers): channels, movies and series from any page; Enter shows every match.
function bindTopSearch() {
  const q = $('#tq'), box = $('#ts-results');
  if (!q || !box) return;
  $('#topsearch [data-icon]').innerHTML = icon('search');
  const close = () => { box.hidden = true; };
  q.addEventListener('input', debounce(() => homeSearch(q.value.trim(), box), 220));
  q.addEventListener('focus', () => { if (q.value.trim().length >= 2 && box.innerHTML) box.hidden = false; });
  q.addEventListener('keydown', e => {
    if (e.key === 'Escape') { close(); q.blur(); }
    if (e.key === 'Enter' && q.value.trim()) { close(); pendingQuery = q.value.trim(); location.hash = '#/all'; q.blur(); }
    if (e.key === 'ArrowDown') { e.preventDefault(); box.querySelector('.hs-row')?.focus(); }
  });
  box.addEventListener('keydown', e => {
    const rows = [...box.querySelectorAll('.hs-row')], i = rows.indexOf(document.activeElement);
    if (e.key === 'ArrowDown') { e.preventDefault(); rows[i + 1]?.focus(); }
    if (e.key === 'ArrowUp') { e.preventDefault(); (rows[i - 1] || q).focus(); }
    if (e.key === 'Enter' && i >= 0) { e.preventDefault(); rows[i].click(); }
    if (e.key === 'Escape') { close(); q.focus(); }
  });
  box.addEventListener('mousedown', e => e.preventDefault());   // keep focus in the field while clicking a result
  box.addEventListener('click', e => {
    const row = e.target.closest('.hs-row');
    if (!row) return;
    const it = shelfItems.get(row.dataset.shelf)?.[+row.dataset.k];
    close(); q.blur();
    if (!it) return;
    if (it.kind === 'series') return seriesSheet(it);
    if (it.kind === 'movie') return movieSheet(it);
    playChannel([it], 0);
  });
  document.addEventListener('click', e => { if (!e.target.closest('#topsearch')) close(); });
}
function homeShelfHtml(sh) {
  if (sh.top10) return `<div class="row-head"><h3>${esc(sh.title)}</h3><span class="muted small">from TMDB</span></div><ul class="shelf-row home-shelf top10">${sh.items.map((it, k) => `<li class="scard t10" data-shelf="${esc(sh.key)}" data-k="${k}"><span class="t10-n">${k + 1}</span>${cardHtml(it, sh.key, k).replace(/^<li[^>]*>|<\/li>$/g, '')}</li>`).join('')}</ul>`;
  return `<div class="row-head"><h3>${esc(sh.title)}</h3></div><ul class="shelf-row home-shelf">${sh.items.map((it, k) => cardHtml(it, sh.key, k)).join('')}</ul>`;
}
function onNowHtml({ c, now }, k) {
  const pct = ((Date.now() - now[0]) / (now[1] - now[0])) * 100;
  const img = now[4]?.img;
  // Compact and calm: the channel's logo whole (never cropped into a picture), what's on, until when.
  void img;
  return `<li class="onnow nc" data-k="${k}">
    <span class="nc-logo">${logoHtml(c)}</span>
    <span class="nc-text"><b>${esc(now[2])}</b><span class="muted">${esc(c.name)}</span><span class="nc-time"><i class="nc-dot"></i>until ${fmtTime(now[1])}</span></span>
    <span class="bar nc-bar"><i style="width:${pct.toFixed(1)}%"></i></span></li>`;
}
let heroTimer = 0;
function heroHtml(items) {
  clearInterval(heroTimer);
  const go = (car, i) => {
    const n = car.querySelectorAll('.hero-slide').length;
    car.scrollTo({ left: ((i + n) % n) * car.clientWidth, behavior: 'smooth' });
  };
  heroTimer = setInterval(() => {
    const car = $('.hero-car');
    if (!car) return clearInterval(heroTimer);
    if (car.matches(':hover')) return;
    go(car, Math.round(car.scrollLeft / car.clientWidth) + 1);
  }, 8000);
  queueMicrotask(() => {
    const car = $('.hero-car'), dots = $('.hero-dots');
    if (!car || !dots || car.dataset.bound) return;   // a kept carousel already has its handlers
    car.dataset.bound = '1';
    const mark = () => { const i = Math.round(car.scrollLeft / car.clientWidth); dots.querySelectorAll('button').forEach((d, k) => d.classList.toggle('on', k === i)); };
    car.addEventListener('scroll', debounce(mark, 60), { passive: true });
    dots.addEventListener('click', e => { const d = e.target.closest('[data-dot]'); if (d) { e.stopPropagation(); go(car, +d.dataset.dot); } });
  });
  return `<div class="hero-wrap"><div class="hero-car">${items.map((it, k) => `<div class="hero-slide" data-shelf="hero" data-k="${k}" style="--hue:${hueOf(it.name)}">
      ${it.tm?.backdrop ? `<img class="hero-bg sharp" src="${esc(it.tm.backdrop)}" alt="" onerror="this.remove()">` : `<img class="hero-bg" src="${esc(thumb(it.logo, 300))}" alt="" referrerpolicy="no-referrer" onerror="this.remove()">`}
      <div class="hero-body">
        <div class="hero-text">
          <span class="hero-kicker">${icon(it.kind === 'series' ? 'grid' : 'film')} ${esc(it.heroLabel || `Popular ${it.kind === 'series' ? 'series' : 'movie'}`)}</span>
          <div class="hero-title">${esc(displayTitle(it.name))}</div>
          <div class="hero-meta">${[it.year, it.rating ? `★ ${it.rating.toFixed(1)}` : '', ...(it.tm?.genres?.length ? it.tm.genres.slice(0, 2) : [it.genre, prettyGroup(it.group)])].filter(Boolean).map(x => `<span>${esc(x)}</span>`).join('')}</div>
          ${it.tm?.overview || it.plot ? `<p class="hero-plot">${esc(it.tm?.overview || it.plot)}</p>` : ''}
          <div class="hero-actions"><button class="btn primary" data-hero-play="${k}">${icon('play')} ${it.kind === 'series' ? 'Episodes' : 'Play'}</button><button class="btn">More info</button></div>
        </div>
        ${it.tm?.backdrop ? '' : `<span class="poster-img hero-poster"><img src="${esc(thumb(it.logo, 500))}" alt="" referrerpolicy="no-referrer" onerror="this.remove()"></span>`}
      </div></div>`).join('')}</div>
    ${items.length > 1 ? `<div class="hero-dots">${items.map((_, k) => `<button data-dot="${k}" class="${k ? '' : 'on'}" aria-label="Slide ${k + 1}"></button>`).join('')}</div>` : ''}</div>`;
}

// Home tiles fill top-to-bottom, then the next column (1 top-left, 2 under it). At most 4 rows, so the
// block stays a fixed height: more groups add columns that scroll sideways instead of lengthening the page.
const browseRows = n => (n <= 2 ? n : Math.min(4, Math.ceil(n / 2)));

// Home updates without flicker: each top-level section is compared with what is on screen and only sections that
// changed are replaced. Unchanged ones (their images, scroll position, carousel) stay exactly as they are.
const blockKey = el => `${el.tagName}.${el.className}|${el.id}|${el.querySelector('h2, .hero-title')?.textContent || ''}`;
function patchView(root, html) {
  const t = document.createElement('template');
  t.innerHTML = html;
  const fresh = [...t.content.children];
  const old = [...root.children];
  if (!old.length || old.some(el => el._html === undefined)) {   // another page was showing: plain render
    root.innerHTML = '';
    for (const el of fresh) { el._html = el.outerHTML; root.append(el); }
    return;
  }
  const byKey = new Map(old.map(el => [blockKey(el), el]));
  const next = fresh.map(el => {
    const html = el.outerHTML, prev = byKey.get(blockKey(el));
    if (prev && prev._html === html) { byKey.delete(blockKey(el)); return prev; }   // unchanged: keep the live one
    el._html = html;
    return el;
  });
  // Keep nodes in place where possible (moving the hero would restart its images).
  next.forEach((el, i) => { if (root.children[i] !== el) root.insertBefore(el, root.children[i] || null); });
  while (root.children.length > next.length) root.lastElementChild.remove();
}
function showHome() {
  state.current = null;
  setTopbar({
    title: 'M3U Player',
    actions: [
      { icon: 'sliders', label: 'Settings', fn: settingsSheet },
      { icon: 'plus', label: 'Add playlist', fn: addPlaylistSheet },
    ],
  });
  let dismissed = false;
  try { dismissed = localStorage.getItem('install-tip') === 'off'; } catch {}
  const pl = state.playlists;
  const cw = continueWatching();
  // Watchlist = saved films and series; New this week = the newest titles from each Xtream catalogue (loaded on demand).
  const watchlist = state.favorites.filter(f => f.kind === 'movie' || f.kind === 'series').slice(-30).reverse();
  shelfItems.set('home-watch', watchlist);
  const hub = homeHub(pl);
  resumeCardHtml(state.recents[0]);   // sets resumeChannel when the last thing watched was a live channel
  resumeChannel = null;   // Home's Continue watching is films and episodes only (the last channel isn't pushed at you)
  const resumeLive = resumeChannel ? (() => {
    const c = resumeChannel, g = guideFor(c), now = g && g.nowNext(c).now;
    const pct = now ? ((Date.now() - now[0]) / (now[1] - now[0])) * 100 : 0;
    return `<div class="cw cw-live" data-resume role="button" tabindex="0" style="--hue:${hueOf(c.name)}"><button type="button" class="cw-x" data-cw-x="live" aria-label="Remove from Continue watching">${icon('close')}</button><span class="cw-img">${now?.[4]?.img ? `<img src="${esc(now[4].img)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}${logoHtml(c)}<span class="live-badge">LIVE</span><span class="cw-play">${icon('play')}</span>${now ? `<span class="bar"><i style="width:${pct.toFixed(1)}%"></i></span>` : ''}</span>
      <span class="cw-name">${esc(c.name)}</span><span class="muted">${now ? esc(now[2]) : 'Live channel'}</span></div>`;
  })() : '';
  // Snapshot: playing a channel moves it to the front of state.recents, so tiles must keep their own channel.
  const recentStrip = state.recents.filter(isLiveChannel).slice(0, 14);
  // Browse: the groups the user pinned to Home (in their order); until they pin any, the biggest live groups.
  // (Home no longer shows group tiles: no grouping of every channel at each Home render.)
  patchView(view, `
    ${isIOS && !isStandalone && !dismissed ? `<div class="banner">
      ${icon('share')}<span><b>Install on your iPhone:</b> tap Share, then “Add to Home Screen”. It opens full-screen and iOS keeps your playlists safe.</span>
      <button class="icon-btn" id="tip-close" aria-label="Dismiss">${icon('close')}</button></div>` : ''}
    ${isNative && !state.settings.serverUrl ? `<div class="banner">
      ${icon('lock')}<span><b>Connect your server</b> for the TV guide, sync with your other devices, movies &amp; series and downloads.</span>
      <button class="btn small primary" id="server-key">Connect</button></div>` : ''}
    ${serverProxy.needsKey ? `<div class="banner">
      ${icon('lock')}<span><b>Connect to your server:</b> enter the server key from the install, so http:// providers and TV guides work.</span>
      <button class="btn small primary" id="server-key">Enter key</button></div>` : ''}
    ${pl.length ? `<div class="home-top">
      <label class="search home-search">${icon('search')}<input id="home-q" type="search" placeholder="Search channels, movies and series" autocomplete="off" autocorrect="off" spellcheck="false"></label>
      <ul class="lib-row quick">
        <li class="lib" data-go="#/fav">${icon('star')}<span>Favorites</span><b>${state.favorites.length}</b></li>
        <li class="lib" data-go="#/recent">${icon('clock')}<span>History</span></li>
        <li class="lib" data-go="#/multi">${icon('grid')}<span>Multi-view</span></li>
        ${Object.keys(state.downloads || {}).length ? `<li class="lib" data-go="#/downloads">${icon('film')}<span>Downloads</span><b>${Object.keys(state.downloads).length}</b>${dlActive ? '<i class="lib-dot"></i>' : ''}</li>` : ''}
        ${pl.map(p => `<li class="lib pl-chip" data-go="#/p/${esc(p.id)}">${icon(p.kind === 'xtream' ? 'tv' : 'list')}<span>${esc(playlistTitle(p))}</span><button class="pl-more" data-menu="${esc(p.id)}" aria-label="Playlist options">${icon('more')}</button></li>`).join('')}
      </ul></div><div id="home-results" class="home-results" hidden></div>` : ''}
    ${hub.hero.length ? heroHtml(hub.hero) : ''}
    ${resumeChannel || cw.length ? `<section class="hub-sec"><div class="hub-head"><h2>Continue watching</h2></div>
      <div class="cont-row">${resumeLive}${cw.map((e, i) => `<div class="cw" data-cw="${i}" role="button" tabindex="0"><button type="button" class="cw-x" data-cw-x="${i}" aria-label="Remove from Continue watching">${icon('close')}</button>
        <span class="cw-img">${e.item.logo ? `<img src="${esc(thumb(e.item.logo, 500))}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : icon('film')}<span class="cw-play">${icon('play')}</span><span class="bar"><i style="width:${Math.min(100, (e.t / e.d) * 100).toFixed(1)}%"></i></span></span>
        <span class="cw-name">${esc(cwTitle(e.item))}</span><span class="muted">${fmtDur(e.d - e.t)} left</span></div>`).join('')}</div></section>` : ''}
    ${hub.onNow.length ? `<section class="hub-sec"><div class="hub-head"><h2>On now</h2><span class="hub-links">${pl.length ? `<button class="link" data-edit-groups="${esc((liveSource() || pl[0]).id)}">Choose channels</button>` : ''}<a class="link" href="#/tvguide">TV guide</a><a class="link" href="#/live">All channels</a></span></div>
      <ul class="shelf-row home-shelf onnow-row">${hub.onNow.map((x, k) => onNowHtml(x, k)).join('')}</ul></section>` : ''}
    ${hub.shelves.movies.length || hub.loading ? `<section class="hub-sec"><div class="hub-head"><h2>Movies</h2><span class="hub-links"><a class="link" href="#/movies">Browse all</a></span></div>${hub.shelves.movies.map(homeShelfHtml).join('') || '<p class="muted hub-loading">Loading movies…</p>'}${servicesHtml(hub.services)}</section>` : ''}
    ${hub.shelves.series.length || hub.loading ? `<section class="hub-sec"><div class="hub-head"><h2>Series</h2><span class="hub-links"><a class="link" href="#/series">Browse all</a></span></div>${hub.shelves.series.map(homeShelfHtml).join('') || '<p class="muted hub-loading">Loading series…</p>'}</section>` : ''}
    ${watchlist.length ? `<section class="hub-sec"><div class="hub-head"><h2>Watchlist</h2></div><ul class="shelf-row home-shelf">${watchlist.map((it, k) => cardHtml(it, 'home-watch', k)).join('')}</ul></section>` : ''}
    ${pl.length ? '' : `<div class="empty">${icon('tv', 'big')}<p><b>No playlists yet</b></p>
          <p class="muted">Add an Xtream login, an M3U / M3U8 link, or a playlist file.</p>
          <button class="btn primary" id="add-first">Add playlist</button></div>`}`);

  { const b = $('#add-first'); if (b) b.onclick = addPlaylistSheet; }
  { const b = $('#server-key'); if (b) b.onclick = enterServerKey; }
  addRowArrows(view);
  if ($('#tip-close')) $('#tip-close').onclick = (() => {
    try { localStorage.setItem('install-tip', 'off'); } catch {}
    $('.banner').remove();
  });
  if ($('#home-q')) $('#home-q').onkeydown = (e => {
    if (e.key === 'Enter' && e.target.value.trim()) { pendingQuery = e.target.value.trim(); location.hash = '#/all'; }
  });
  if ($('#home-q') && !$('#home-q').oninput) $('#home-q').oninput = debounce(e => homeSearch(e.target.value.trim()), 250);
  view.onclick = e => {
    const hp = e.target.closest('[data-hero-play]');
    if (hp) { const it = shelfItems.get('hero')?.[+hp.dataset.heroPlay]; if (it) return it.kind === 'series' ? seriesSheet(it) : playBestVersion(it); }
    const sv = e.target.closest('[data-svc]');
    if (sv) {
      const all = hub.services, tiles = all.filter(s => s.tab === 'movies').concat(all.filter(s => s.tab === 'series' && !all.some(m => m.tab === 'movies' && m.name === s.name)));
      const s = tiles[+sv.dataset.svc];
      if (s) { pendingTab = s.tab; pendingGroup = s.group; location.hash = `#/p/${s.pid}`; }
      return;
    }
    const on = e.target.closest('.onnow');
    if (on) { const list = (shelfItems.get('onnow') || []).map(x => x.c); return playChannel(list, +on.dataset.k); }
    const sc = e.target.closest('.scard, .hs-row, .hero-slide');
    if (sc) {
      const it = shelfItems.get(sc.dataset.shelf)?.[+sc.dataset.k];
      if (!it) return;
      if (it.kind === 'series') return seriesSheet(it);
      if (it.kind === 'movie') return movieSheet(it);
      return playChannel([it], 0);
    }
    const m = e.target.closest('[data-menu]');
    if (m) return playlistMenu(byId(m.dataset.menu));
    const x = e.target.closest('[data-cw-x]');
    if (x) {
      e.stopPropagation();
      if (x.dataset.cwX === 'live') { cwLiveHidden = resumeChannel?.url || ''; db.set('cwLiveHidden', cwLiveHidden).catch(() => {}); }
      else {
        const entry = cw[+x.dataset.cwX];
        // Hidden, not forgotten: the resume point stays, and playing it again brings it back. Syncs to your other devices.
        if (entry) { state.progress[entry.item.url] = { ...state.progress[entry.item.url], hidden: true, at: Date.now() }; db.set('progress', state.progress).catch(() => {}); syncSoon(); }
      }
      toast('Removed from Continue watching');
      // Take just this card out (no redraw: the other cards and their buttons stay exactly as they are).
      const card = x.closest('.cw'), row = card?.parentNode;
      card?.remove();
      if (row && !row.querySelector('.cw')) row.closest('.hub-sec')?.remove();
      return;
    }
    const c = e.target.closest('[data-cw]');
    if (c) { const entry = cw[+c.dataset.cw]; return playChannel([entry.item], 0); }
    if (e.target.closest('[data-resume]')) return playChannel(recentStrip, 0);
    const r = e.target.closest('[data-recent]');
    if (r) return playChannel(recentStrip, +r.dataset.recent);
    const b = e.target.closest('[data-browse]');
    if (b) { pendingGroup = b.dataset.group; location.hash = `#/p/${b.dataset.browse}`; return; }
    const eg = e.target.closest('[data-edit-groups]');
    if (eg) return groupsSheet(byId(eg.dataset.editGroups), 'live');
    const go = e.target.closest('[data-go]');
    if (go) location.hash = go.dataset.go;
  };
}
let pendingQuery = '';
let pendingGroup = null;   // a group to pre-select when the next playlist opens (home "Browse" tiles)
let pendingTab = null;     // a tab (live / movies / series) to open the next playlist on (main navigation)

// ---------- main navigation: Home · Live TV · Movies · Series · Guide ----------
const liveSource = () => state.playlists.find(p => p.content !== 'vod' && (m3uKinds(p)?.live.length || (p.kind === 'xtream' && p.channels.length)));
const vodSource = tab => state.playlists.find(p => p.kind === 'xtream' && p.content !== 'live') || state.playlists.find(p => m3uKinds(p)?.[tab]?.length);
function openSection(kind) {
  if (kind === 'tvguide') { const p = liveSource(); return location.replace(p ? `#/guide/${p.id}` : '#/'); }
  const p = kind === 'live' ? liveSource() : vodSource(kind);
  if (!p) { location.replace('#/'); return kind === 'live' ? toast('Add a playlist with live channels first') : noVodSheet(kind); }
  pendingTab = kind;
  location.replace(`#/p/${p.id}`);
}
// Movies/Series tapped with no playlist that has them: say why, and offer the fix.
function noVodSheet(kind) {
  const liveOnly = state.playlists.find(p => !p.virtual && p.content === 'live' && (p.kind === 'xtream' || p.sourceUrl));
  sheet(`<div class="sheet-title">No ${kind} yet</div>
    ${liveOnly ? `<p class="muted sheet-intro">“${esc(playlistTitle(liveOnly))}” is set to keep Live TV only on this device. Most providers include movies and series with the same login.</p>
      <div class="form-actions"><button class="btn" data-add>Add another playlist</button><button class="btn primary" data-widen>Turn on movies &amp; series</button></div>`
    : `<p class="muted sheet-intro">None of the playlists on this device has ${kind}. Each device keeps its own playlists: add the same Xtream login (or M3U link) you use on your other devices.</p>
      <div class="form-actions"><button class="btn primary" data-add>Add playlist</button></div>`}`, {
    onMount: el => {
      el.querySelector('[data-add]')?.addEventListener('click', () => { closeSheet(); addPlaylistSheet(); });
      el.querySelector('[data-widen]')?.addEventListener('click', async () => {
        closeSheet();
        liveOnly.content = 'both';
        await save(liveOnly);
        toast('Loading movies & series…', { busy: true });
        try { await refreshSource(liveOnly); } catch {}
        pendingTab = kind;
        location.hash = `#/p/${liveOnly.id}`;
      });
    },
  });
}
function updateNav() {
  const hash = location.hash || '#/';
  const on = hash === '#/' ? 'home' : hash.startsWith('#/guide') ? 'guide' : state.current ? (state.tab === 'live' ? 'live' : state.tab) : '';
  document.querySelectorAll('#mainnav [data-nav]').forEach(a => a.classList.toggle('on', a.dataset.nav === on));
}

// ---------- playlist view ----------
// IPTV providers pad M3U lists with "category header" rows (### NEWS ###, ===== SPORTS =====)
// that have no real stream. Hide them. And tidy raw group names ("UK | SPORT" -> "UK · SPORT").
const SEP_RE = /^\s*[#*=_~·•▬—–-]{2,}|[#*=_~·•▬—–-]{2,}\s*$/;
const PROMO_RE = /⬇️|release update|learn that|^epgenius$/i;   // provider notice rows, not channels
const isSeparator = c => !c || SEP_RE.test(c.name || '') || !/[a-z0-9]/i.test(c.name || '')
  || PROMO_RE.test(c.group || '') || PROMO_RE.test(c.name || '') || /epgenius\.org/i.test(c.url || '');
// Display name for a group: "UK| Sky Sports ᴳᴬᴺᴶᴬ" -> "UK · Sky Sports". Drops provider tags written in
// superscript/modifier letters, keeps emoji (they make good tile icons).
const prettyGroup = name => String(name || '')
  .replace(/[ᴬ-ᵪ⁰-₟ᵃ-ᶿⱽ²³¹]+/g, '')
  .replace(/\s*[|/►▶]\s*/g, ' · ').replace(/\s+/g, ' ').replace(/\s·\s*$/, '').trim();
// Movie/series group names for people: "EN - IMDB TOP 250" -> "IMDb Top 250", "NETFLIX MOVIES ᴵⱽ ˢ" -> "Netflix Movies".
const KEEP_UPPER = new Set(['4K', 'UHD', 'HD', 'FHD', 'HDR', 'HEVC', 'TV', 'UK', 'US', 'USA', 'DC', 'BBC', 'ITV', 'HBO', 'AMC', 'FX', 'MTV', 'CNN', 'ESPN', 'NBA', 'NFL', 'NHL', 'MLB', 'UFC', 'WWE', 'PPV', 'VIP', '3D', 'AI', 'II', 'III', 'IV', 'VOD', '24/7']);
const TMDB_LABELS = new Map();   // 'tmdb:genre:28' -> 'Action'
function groupLabel(name) {
  if (String(name).startsWith('tmdb:')) return TMDB_LABELS.get(name) || 'TMDB';
  let s = prettyGroup(name).replace(/^(?:EN|ENG|UK|US|USA)\s*[-:·]\s*/i, '').replace(/\s*\((?:multi[- ]?subs?)\)/i, ' · Multi-subs');
  if (s === s.toUpperCase() || s === s.toLowerCase()) {
    s = s.toLowerCase().replace(/[a-z0-9+&'’]+/g, w => {
      const u = w.toUpperCase();
      if (KEEP_UPPER.has(u)) return u;
      if (u === 'IMDB') return 'IMDb';
      if (/^(and|of|the|in|on|for|a|to|by)$/.test(w)) return w;
      return w[0].toUpperCase() + w.slice(1);
    }).replace(/^./, c => c.toUpperCase()).replace(/\bDisney\+/i, 'Disney+').replace(/\bApple\+/i, 'Apple TV+');
  }
  return s.trim() || UNGROUPED;
}
// Library sections for the movie/series sidebar.
const GROUP_SECTIONS = [
  ['Featured', /new release|latest|recent|trend|popular|top ?\d|top movies|top series|imdb|box ?office|cinema|theat|award|oscar|best/i],
  ['Streaming services', /netflix|disney|apple|amazon|prime|\bhbo|\bmax\b|paramount|hulu|peacock|viaplay|\bsky\b|now ?tv|britbox|starz|showtime|crave|stan\b|binge|mubi|shudder|discovery\+|^(4k-)?(nf|amz|d\+|a\+)\b/i],
  ['Kids & family', /kid|family|child|cartoon|anima|pixar|dreamworks|junior/i],
  ['Genres', /action|comedy|horror|thriller|drama|sci-?fi|science|fantasy|romance|romantic|documentar|docu|western|\bwar\b|crime|mystery|music|stand-?up|christmas|holiday|marvel|\bdc\b|sport|adventure|biograph|history|faith/i],
  ['4K & quality', /4k|uhd|hdr|dolby|hevc|bluray|blu-ray|remux|3d/i],
];
const SECTION_ORDER = ['Discover', 'Genres', 'Featured', 'Streaming services', 'Kids & family', '4K & quality', 'More'];
const sectionOf = name => { if (/^tmdb:\w+:list:/.test(name)) return 'Discover'; if (/^tmdb:\w+:genre:/.test(name)) return 'Genres'; for (const [label, re] of GROUP_SECTIONS) if (re.test(name)) return label; return 'More'; };

// Country/region of a group: a "UK|" style prefix, or a flag emoji; '' when neither.
const FLAG_CC = { '🇬🇧': 'UK', '🇺🇸': 'US', '🇨🇦': 'CA', '🇦🇺': 'AU', '🇮🇪': 'IE', '🇳🇿': 'NZ', '🇪🇸': 'ES', '🇫🇷': 'FR', '🇩🇪': 'DE', '🇮🇹': 'IT', '🇵🇹': 'PT', '🇧🇷': 'BR', '🇮🇳': 'IN' };
function regionOf(name) {
  const n = String(name || '').trim();
  const pre = n.match(/^([A-Za-z]{2,3})\s*\|/);
  if (pre) return pre[1].toUpperCase();
  const flag = n.match(/\p{Regional_Indicator}\p{Regional_Indicator}/u);
  if (flag) return FLAG_CC[flag[0]] || flag[0];
  return '';
}
// Classify an M3U entry as live TV vs VOD from its URL — reliable, unlike the group name.
// Live streams are .ts/.m3u8 (or Xtream /live/ paths); VOD is a movie/series file.
function channelKind(c) {
  const u = (c.url || '').toLowerCase().split(/[?#]/)[0];
  if (/\/series\//.test(u)) return 'series';
  if (/\/movie\//.test(u) || /\.(mp4|mkv|avi|mov|m4v|flv|wmv|mpg|mpeg)$/.test(u)) return 'movies';
  return 'live';   // .ts, .m3u8, /live/, or anything else is a live channel
}
// Providers list the same channel several times in a group (backup feeds). Show it once; the copies
// become automatic fallbacks, tried in order if a stream fails (see tune()).
const dupKey = c => `${c.group || ''}\0${(c.tvgId || '').toLowerCase()}\0${normName(c.name)}`;
function mergeDuplicates(list) {
  const out = [], seen = new Map();
  for (const c of list) delete c.alts;   // rebuilt every time, never stale
  for (const c of list) {
    const k = dupKey(c);
    const first = seen.get(k);
    if (first) { first.alts ||= []; if (c.url !== first.url && !first.alts.includes(c.url)) first.alts.push(c.url); continue; }
    seen.set(k, c);
    out.push(c);
  }
  return out;
}
// Split an M3U playlist's channels into live/movies/series once, caching on the playlist.
function m3uKinds(p) {
  if (p.kind === 'xtream' || p.virtual) return null;
  if (!p._kinds) {
    const k = { live: [], movies: [], series: [] };
    for (const c of p.channels) { if (!isSeparator(c)) k[channelKind(c)].push(c); }
    if (state.settings.mergeDupes !== false) k.live = mergeDuplicates(k.live);
    k.live.forEach((c, i) => { c.num = Number(c.attrs?.['tvg-chno']) || i + 1; });
    k.series = groupSeries(k.series);            // "Show S01E04" rows become one series with seasons
    for (const s of k.series) if (s.kind === 'series') s.src = p.id;
    p._kinds = k;
    p._hasVod = (k.movies.length + k.series.length) >= 10;
  }
  return p._kinds;
}

function showPlaylist(p) {
  state.current = p;
  state.tab = 'live';
  // A tab picked from the main navigation (Live TV / Movies / Series) always opens at the top; only coming
  // back to the same page (Back, reopening it) restores where you were.
  const fromNav = !!pendingTab;
  const memo = !pendingGroup && !pendingQuery && !fromNav ? viewMemo.get(location.hash) : null;
  // A remembered group only comes back for Live TV; Movies and Series always open on their front page.
  state.group = pendingGroup ?? (memo && (memo.tab || 'live') === 'live' ? memo.group : null) ?? null; pendingGroup = null;
  state.query = pendingQuery ? pendingQuery.toLowerCase() : memo?.query || '';
  if (memo && !pendingQuery) pendingQuery = memo.query || '';
  const k = m3uKinds(p);
  const vodOn = p.content !== 'live';
  const showTabs = vodOn && (p.kind === 'xtream' || p._hasVod);
  // A VOD-only playlist (movies/series from one provider, live from another) opens on Movies, not an empty Live tab.
  const liveEmpty = p.content === 'vod' || (k && !k.live.length);
  const firstTab = pendingTab || memo?.tab || (liveEmpty ? (!k || k.movies.length ? 'movies' : 'series') : 'live');
  pendingTab = null;   // Xtream has no M3U split (k is null)
  const tabBtn = (t, label) => `<button data-tab="${t}"${t === firstTab ? ' class="active"' : ''}>${label}</button>`;
  view.innerHTML = `
    ${showTabs ? `<div class="seg" id="seg">
      ${(p.kind === 'xtream' || !liveEmpty) ? tabBtn('live', 'Live TV') : ''}${(p.kind === 'xtream' || k?.movies.length) ? tabBtn('movies', 'Movies') : ''}${(p.kind === 'xtream' || k?.series.length) ? tabBtn('series', 'Series') : ''}</div>` : ''}
    <div class="filters">
      <label class="search">${icon('search')}<input id="q" type="search" placeholder="Search" autocomplete="off" autocorrect="off" spellcheck="false" value="${esc(pendingQuery)}"></label>
      <div class="chips" id="chips"></div>
    </div>
    <ul class="list" id="list"></ul>
    <div id="sentinel" class="sentinel"></div>
    <div class="selbar" id="selbar" hidden></div>`;
  pendingQuery = '';
  $('#q').oninput = debounce(e => { state.query = e.target.value.trim().toLowerCase(); requestAnimationFrame(() => { applyFilter(); toListTop(); }); }, 120);
  $('#chips').onclick = async e => {
    if (e.target.closest('[data-more-groups]')) { state.allGroups = !state.allGroups; renderChips(); return; }
    const b = e.target.closest('.chip');
    if (!b) return;
    if (b.dataset.locked && !await unlock()) return;
    state.group = b.hasAttribute('data-all') ? null : b.dataset.group;
    if (state.group === null) state.reordering = false;
    updateTopbar();
    renderChips();
    applyFilter();
    toListTop();
  };
  $('#seg')?.addEventListener('click', e => {
    const b = e.target.closest('[data-tab]');
    if (!b || b.dataset.tab === state.tab) return;
    state.tab = b.dataset.tab;
    state.group = null;
    state.selecting = false;
    $('#seg').querySelectorAll('button').forEach(x => x.classList.toggle('active', x === b));
    queueMicrotask(updateNav);
    refreshPlaylistView();
    if (state.tab !== 'live' && p.kind === 'xtream') loadVod(p, state.tab);
  });
  $('#list').onclick = onListClick;
  $('#list').onkeydown = e => { if ((e.key === 'Enter' || e.key === ' ') && e.target.classList.contains('row')) { e.preventDefault(); e.target.click(); } };
  bindRowDrag();
  // start the transcoder on the confirmed tap (pointerup lands just before click), without killing the
  // current stream on a mere scroll-touch
  $('#list').onpointerup = e => { const el = e.target.closest('.row'); if (el && !state.selecting) warm(state.filtered[+el.dataset.i]?.url); };
  observer.observe($('#sentinel'));
  if (firstTab !== 'live' && $(`#seg [data-tab="${firstTab}"]`)) { state.tab = firstTab; refreshPlaylistView(); if (p.kind === 'xtream') loadVod(p, firstTab); }
  else refreshPlaylistView();
  if (memo) { while (state.rendered < Math.min(memo.rendered || 0, state.filtered.length)) renderMore(); requestAnimationFrame(() => scrollTo(0, memo.y)); }
  else window.scrollTo(0, 0);
  if (!p.virtual) maybeUpdateGuide(p);
}

// New results (another group, a search) start at their first row, not wherever the old list was scrolled.
function toListTop() {
  const list = $('#list');
  if (list && scrollY > list.offsetTop - 160) scrollTo(0, Math.max(0, list.offsetTop - 160));
}

function refreshPlaylistView({ keep = false } = {}) {
  updateTopbar();
  renderChips();
  applyFilter({ keep });
}

function updateTopbar() {
  const p = state.current;
  const live = state.tab === 'live';
  setTopbar({
    title: playlistTitle(p),
    back: '#/',
    actions: [
      live && !p.virtual && { icon: 'guide', label: 'TV guide', fn: () => { location.hash = `#/guide/${p.id}`; } },
      canReorder() && { icon: 'grip', label: state.reordering ? 'Done reordering' : 'Reorder channels', active: state.reordering, fn: toggleReordering },
      live && !p.readOnly && p.channels.length && !state.reordering && { icon: 'select', label: state.selecting ? 'Done selecting' : 'Select', active: state.selecting, fn: toggleSelecting },
      { icon: 'more', label: 'Playlist options', fn: () => playlistMenu(p) },
    ].filter(Boolean),
  });
}

const vodKey = (p, tab) => `${p.id}:${tab}`;

function currentItems() {
  const p = state.current;
  if (p.kind === 'xtream') {
    if (state.tab === 'live') return p.channels;
    const entry = vodCache.get(vodKey(p, state.tab));
    if (!entry) return [];
    if (Array.isArray(entry)) return entry;
    // Server-backed catalogue: a group or a search is fetched when first shown; "All" is the newest titles.
    if (state.query && !/^\d+$/.test(state.query)) return entry.searches.get(state.query) || (requestVod(p, state.tab, { q: state.query }), []);
    if (state.group !== null) return entry.groups.get(state.group) || (requestVod(p, state.tab, { group: state.group }), []);
    return entry.recent;
  }
  const k = m3uKinds(p);                       // M3U: split by inferred kind, separators already removed
  if (k) return k[state.tab] || k.live;
  return p.channels;
}

// ---- server-backed catalogue (server/catalog.js) ----
async function vodApi(p, tab, extra = {}) {
  const x = xtOf(p);
  const res = await fetch(serverApi('vod'), { method: 'POST', cache: 'no-store', body: JSON.stringify({ server: x.server, user: x.user, pass: x.pass, kind: tab, ...extra }) });
  if (!res.ok) throw new Error((await res.text()) || `The server replied ${res.status}`);
  return res.json();
}
const toItems = (p, tab, cats, list) => {
  const x = xtOf(p);
  const items = tab === 'movies' ? movieItems(x, cats, list) : seriesItems(cats, list);
  items.forEach(it => { it.src = p.id; });
  return items;
};
// ---- movie & series languages ----
// "English only" (the default) hides groups whose name marks another language or country ("FR - …",
// "NORDIC …", "TURKISH …", Arabic/Hebrew/Greek/Cyrillic/Persian names) and orders what's left so the
// big English collections come first: top picks and new releases, streaming services, English genres,
// studios, kids & anime, documentaries, then sport and extras.
const NON_LATIN = /[\u0370-\u03FF\u0400-\u04FF\u0590-\u05FF\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]/;
const LANG_PREFIX = /^\s*(?:(ES|LA|BR|AF|FR|QC|IN|SO|PK|BN|DE|IT|NL|BE|PL|GR|IL|RU|MT|BG|RO|AL|EX|KU|TR|IR|CN|PH|JP|KR)\s*-|PT\/BR\b)/i;
const LANG_WORD = /(?:^|[^A-ZÀ-ÿ])(NORDIC|SVENSKA?|DANSKE?|NORGE|NORSK|SUOMEN|SUOMI|ÍSLANDS|TURKISH|TURKSIH|GREECE|GREEK|ITALY|FRANCE|QUÉBEC|ALBANIA|INDIAN?|HINDI|SOMALIA|PAKISTAN|NETHERLANDS|VIDEOLAND|BELGIUM|GERMANY|POLSKA|ESPAÑA|LATINO|PERSIAN|KURDISH|HEBREW|ROMANIAN|BULGARIYA|HUNGARY|RUSS[AI]+N|AFRICA|CHINA|PHILIPPINES|MALTA|JOYN|ASIA|MENA|ARABIA)(?:$|[^A-ZÀ-ÿ])/i;
const isEnglishGroup = n => !NON_LATIN.test(n) && !LANG_PREFIX.test(n) && !LANG_WORD.test(n);
const GROUP_RANKS = [/\bTOP\b|NEW RELEASE|TRENDING|IMDB/i, /NETFLIX|AMAZON|DISNEY|APPLE|HBO|PARAMOUNT\+|PEACOCK|\bSKY\b|SHOWTIME|VIAPLAY|DISCOVERY|OSN/i,
  /^EN -|ENGLISH/i, /MARVEL|UNIVERSAL|PARAMOUNT PICTURES|DREAMWORKS|BOND|COLLECTION/i, /KIDS|ANIM|NICKELODEON|CRUNCHYROLL/i, /DOCU/i,
  /SOCCER|WWE|UFC|BOXING|FIFA|STAND-UP|CONCERT|MUSIC|WORKOUT|PODCAST|REALITY|CHRISTMAS/i];
const groupRank = n => { const i = GROUP_RANKS.findIndex(r => r.test(n || '')); return i < 0 ? GROUP_RANKS.length : i; };

function catalogueEntry(p, tab, d) {
  const all = d.cats || [];
  const en = state.settings.vodLanguage !== 'all';
  const cats = en ? all.filter(c => isEnglishGroup(c.category_name || '')) : all;
  const ok = new Set(cats.map(c => c.category_name || ''));
  const keep = list => (en ? list.filter(it => ok.has(it.group || '')) : list);
  const sorted = en ? [...cats].sort((a, b) => groupRank(a.category_name) - groupRank(b.category_name) || b.count - a.count) : cats;
  // With TMDB: its lists and genres lead; of the provider's own groups only the best stay in view (one per
  // name, big enough, not technical duplicates); the rest wait under "All provider groups".
  const provider = sorted.map(c => ({ name: c.category_name || '', count: c.count, id: c.category_id }));
  const tmdbGroups = (d.tmdbCats || []).map(c => ({ name: `tmdb:${tab === 'series' ? 'tv' : 'mv'}:${c.id}`, count: c.count, label: c.name }));
  tmdbGroups.forEach(g => TMDB_LABELS.set(g.name, g.label));
  let shown = provider, more = [];
  if (tmdbGroups.length) {
    const seenLabel = new Set(), quality = [];
    shown = []; 
    for (const g of [...provider].sort((a, b) => b.count - a.count)) {
      const sec = sectionOf(g.name), label = groupLabel(g.name).toLowerCase().replace(/[^a-z0-9+]+/g, '');
      const cream = g.count >= 25 && !seenLabel.has(label) && !/hevc|dolby|multi-?sub|remux|3d|stand-?up|docu|xxx|adult|test/i.test(g.name)
        && (sec === 'Featured' || sec === 'Streaming services' || sec === 'Kids & family' || (sec === '4K & quality' && quality.length < 2 && quality.push(g)));
      if (cream) { seenLabel.add(label); shown.push(g); } else more.push(g);
    }
    shown = provider.filter(g => shown.includes(g));   // back in the sidebar's ranked order
    more = provider.filter(g => more.includes(g));
  }
  return { cats: all, keep, moreGroups: more, total: en ? cats.reduce((n, c) => n + c.count, 0) : d.total || 0,
    recent: keep(toItems(p, tab, all, d.recent || [])), top: keep(toItems(p, tab, all, d.top || [])), fresh: keep(toItems(p, tab, all, d.fresh || [])),
    // Trending keeps TMDB's order (toItems sorts by group), and each title its TMDB extras.
    trending: keep((d.trending || []).map(raw => { const it = toItems(p, tab, all, [raw])[0]; return it && { ...it, tm: raw.tm }; }).filter(Boolean)), groups: new Map(), searches: new Map(),
    groupList: [...tmdbGroups, ...shown] };
}
async function refreshVodInBackground(p, tab) {
  if (!detectedServer) await Promise.race([serverReady, new Promise(r => setTimeout(r, 15000))]);
  if (!canSync()) return;
  try {
    const d = await vodApi(p, tab);
    const key = vodKey(p, tab), old = vodCache.get(key);
    const entry = catalogueEntry(p, tab, d);
    if (old && !Array.isArray(old)) { entry.groups = old.groups; entry.searches = old.searches; }   // keep groups already loaded
    vodCache.set(key, entry);
    db.set(`vod:${key}`, { at: Date.now(), overview: d }).catch(() => {});
    homeRefresh();
  } catch {}
}
// Front-page rows from the last visit (first 24 titles of each), saved on this device so a cold start shows
// every row at once instead of "Loading…"; each row still refreshes itself when it scrolls into view.
const shelfPrev = new Map();   // catalogue key -> Map(group -> items)
const shelfSaveTimers = new Map();
function rememberShelf(key, group, items) {
  if (!shelfPrev.has(key)) shelfPrev.set(key, new Map());
  shelfPrev.get(key).set(group, items.slice(0, 24));
  clearTimeout(shelfSaveTimers.get(key));
  shelfSaveTimers.set(key, setTimeout(() => db.set(`shelves:${key}`, Object.fromEntries(shelfPrev.get(key))).catch(() => {}), 2000));
}
const sameShelf = (a, b) => !!a && !!b && a.length === b.length && a.every((it, i) => it.url === b[i].url && it.logo === b[i].logo && it.name === b[i].name);

async function requestVod(p, tab, { group, q }) {
  const key = vodKey(p, tab), entry = vodCache.get(key);
  const tag = `${key}|${q !== undefined ? 'q:' + q : 'g:' + group}`;
  if (!entry || Array.isArray(entry) || vodPending.has(tag)) return;
  vodPending.add(tag);
  let unchanged = false;   // the row already shows exactly these titles (from the saved copy): nothing to redraw
  try {
    if (!detectedServer) await Promise.race([serverReady, new Promise(r => setTimeout(r, 10000))]);   // cold start: rows can ask before the server is found
    const cat = q === undefined ? entry.cats.find(c => (c.category_name || '') === group) : null;
    const isTm = q === undefined && String(group).startsWith('tmdb:');
    const d = await vodApi(p, tab, q !== undefined ? { q } : isTm ? { tmdb: group.split(':').slice(2).join(':') } : { group: cat?.category_id ?? '' });
    // TMDB groups keep TMDB's order (most popular first) and their extras; toItems would sort by provider group.
    const items = isTm ? (d.items || []).map(raw => { const it = toItems(p, tab, entry.cats, [raw])[0]; return it && { ...it, tm: raw.tm }; }).filter(Boolean)
      : toItems(p, tab, entry.cats, d.items || []);
    if (q !== undefined) entry.searches.set(q, oneOfEach(entry.keep ? entry.keep(items) : items));
    else {
      const was = shelfPrev.get(key)?.get(group);
      entry.groups.set(group, isTm && entry.keep ? entry.keep(items) : items);
      const now = entry.groups.get(group).slice(0, 24);
      if (sameShelf(was, now)) unchanged = true; else rememberShelf(key, group, now);
    }
  } catch (err) {
    console.warn('row load failed', group ?? q, err.message);
    if (q !== undefined || !shelfPrev.get(key)?.has(group)) toast(err.message);   // a row already showing its saved titles fails quietly
  } finally {
    vodPending.delete(tag);
    if (state.current === p && state.tab === tab) {
      // On the Movies/Series front page only the row that just loaded is redrawn. Redrawing the whole page here
      // rebuilt ~1000 posters every time a row loaded while scrolling: the stutter on iPhone.
      const shelf = q === undefined && discoverMode() && [...document.querySelectorAll('#list > .shelf[data-group]')].find(el => el.dataset.group === group);
      if (shelf && (unchanged || !entry.groups.has(group))) {}   // same titles, or the refresh failed: keep what's shown
      else if (shelf) {
        const g = entry.groupList.find(x => x.name === group) || (entry.moreGroups || []).find(x => x.name === group);
        const t = document.createElement('template');
        t.innerHTML = shelfHtml(groupLabel(group), 'g:' + group, (entry.groups.get(group) || []).slice(0, 24), { total: g?.count || 0, group }).trim();
        const fresh = t.content.firstElementChild;
        shelf.replaceWith(fresh);
        addRowArrows(fresh);
      } else if (!discoverMode() || q !== undefined) applyFilter({ keep: true });
    }
    if (!state.current) homeRefresh();
  }
}

const vodLoading = new Map();   // one load per catalogue at a time
function loadVod(p, tab, opts = {}) {
  const key = vodKey(p, tab);
  if (!opts.force && vodLoading.has(key)) return vodLoading.get(key);
  const job = loadVodNow(p, tab, opts).finally(() => vodLoading.delete(key));
  vodLoading.set(key, job);
  return job;
}
async function loadVodNow(p, tab, { force = false } = {}) {
  const key = vodKey(p, tab);
  if (vodCache.has(key) && !force) return;
  // Instant start: the catalogue saved on this device is shown at once (up to a week old); when it is more than
  // a few hours old a fresh copy is fetched in the background and swapped in.
  if (!force) {
    const [cached, shelves] = await Promise.all([db.get(`vod:${key}`), shelfPrev.has(key) ? null : db.get(`shelves:${key}`).catch(() => null)]);
    if (shelves && !shelfPrev.has(key)) shelfPrev.set(key, new Map(Object.entries(shelves)));
    if (cached?.overview?.v === 4 && Date.now() - cached.at < 7 * DAY) {
      vodCache.set(key, catalogueEntry(p, tab, cached.overview));
      if (state.current === p && state.tab === tab) refreshPlaylistView();
      if (Date.now() - cached.at > 6 * 3600_000) setTimeout(() => refreshVodInBackground(p, tab), 4000);
      return;
    }
  }
  // Nothing saved yet and the server not answered (a reload on Movies/Series): wait for it, rather than pulling
  // the provider's whole 50-70MB catalogue straight to this device.
  if (!detectedServer) await Promise.race([serverReady, new Promise(r => setTimeout(r, 10000))]);
  if (vodCache.has(key) && !force) return;
  if (!force) {
    const cached = await db.get(`vod:${key}`);
    if (!canSync() && cached?.items && Date.now() - cached.at < DAY) {
      vodCache.set(key, cached.items);
      if (state.current === p && state.tab === tab) refreshPlaylistView();
      return;
    }
  }
  const x = xtOf(p);
  try {
    // With the VPS: the server fetches the provider's 50-70MB catalogue, slims and caches it, and the
    // phone gets a small gzipped list (server/catalog.js). Without it, straight from the provider.
    if (canSync()) {
      const d = await vodApi(p, tab, force ? { force: 1 } : {});
      const entry = catalogueEntry(p, tab, d);
      vodCache.set(key, entry);
      db.set(`vod:${key}`, { at: Date.now(), overview: d }).catch(() => {});
    } else {
      const [cats, list] = tab === 'movies' ? await Promise.all([x.categories('vod'), x.vodStreams()]) : await Promise.all([x.categories('series'), x.seriesList()]);
      const items = tab === 'movies' ? movieItems(x, cats, list) : seriesItems(cats, list);
      items.forEach(it => { it.src = p.id; });
      vodCache.set(key, items);
      db.set(`vod:${key}`, { at: Date.now(), items }).catch(() => {});
    }
    if (state.current === p && state.tab === tab) refreshPlaylistView();
  } catch (err) {
    if (state.current === p && state.tab === tab) $('#list').innerHTML = `<li class="empty small">${esc(err.message)}</li>`;
  }
}

function renderChips() {
  const p = state.current;
  const entry = p.kind === 'xtream' && state.tab !== 'live' ? vodCache.get(vodKey(p, state.tab)) : null;
  const groups = (entry && !Array.isArray(entry) ? entry.groupList : cachedGroups(p, currentItems(), p.kind === 'xtream' ? 'x:' + state.tab : state.tab)).filter(g => !p.hiddenGroups?.includes(g.name));
  if (state.group !== null && !groups.some(g => g.name === state.group) && !entry?.moreGroups?.some(g => g.name === state.group)) state.group = null;
  const locked = g => !p.virtual && p.lockedGroups?.includes(g) && !state.unlocked;
  const visibleCount = entry && !Array.isArray(entry) ? entry.total : groups.filter(g => !locked(g.name)).reduce((n, g) => n + g.count, 0);
  const chip = (label, n, active, attr, lock) =>
    `<button class="chip ${active ? 'active' : ''}" ${attr}>${lock ? icon('lock') : ''}${esc(label)}<span>${lock ? '' : n.toLocaleString()}</span></button>`;
  const showGroups = groups.length > 1 || (groups[0] && groups[0].name);
  const pinned = new Set(p.homeGroups || []);
  if (pinned.size) groups.sort((a, b) => pinned.has(b.name) - pinned.has(a.name));   // stable: starred groups first, then your order
  $('#chips').hidden = !showGroups;
  const groupChip = g => chip(g.name ? (entry ? groupLabel(g.name) : prettyGroup(g.name)) : UNGROUPED, g.count, state.group === g.name,
    `data-group="${esc(g.name)}" ${locked(g.name) ? 'data-locked="1"' : ''}`, locked(g.name));
  let body = '';
  if (showGroups && entry && !Array.isArray(entry) && !pinned.size) {
    // Movies & series: grouped into sections (Featured, Streaming services, Kids & family, Genres…).
    const order = SECTION_ORDER, by = new Map(order.map(k => [k, []]));
    for (const g of groups) by.get(sectionOf(g.name)).push(g);
    const tm = groups.some(g => g.name.startsWith('tmdb:'));
    body = order.filter(k => by.get(k).length).map(k => `<div class="chip-sec">${esc(k === 'Genres' && tm ? 'Genres' : k)}${k === 'Discover' ? '<span>TMDB</span>' : ''}</div>${by.get(k).map(groupChip).join('')}`).join('');
    const more = (entry.moreGroups || []).filter(g => !p.hiddenGroups?.includes(g.name));
    if (more.length) {
      body += `<button class="chip chip-more" data-more-groups>${state.allGroups ? 'Hide' : 'All'} provider groups<span>${more.length}</span></button>`;
      if (state.allGroups || more.some(g => g.name === state.group)) body += more.map(groupChip).join('');
    }
  } else if (showGroups) body = groups.map(groupChip).join('');
  $('#chips').innerHTML = chip(entry ? `All ${state.tab === 'series' ? 'series' : 'movies'}` : 'All', visibleCount, state.group === null, 'data-all') + body;
  const active = $('#chips .chip.active'), bar = $('#chips');
  if (active && bar) {   // bring the selected group into view inside the chip row / sidebar, never scroll the page
    if (bar.scrollWidth > bar.clientWidth) bar.scrollLeft = active.offsetLeft - bar.clientWidth / 2 + active.offsetWidth / 2;
    if (bar.scrollHeight > bar.clientHeight) bar.scrollTop = active.offsetTop - bar.clientHeight / 2;
  }
}

// Movies/Series front page for a server-backed catalogue: a hero, "Recently added", then a shelf per
// group (loaded as you scroll). Picking a group chip or searching switches to the normal poster grid.
const shelfItems = new Map();   // shelf key -> items shown in it (for taps)
let shelfObserver = null;
const discoverMode = () => {
  const p = state.current;
  if (!p || p.kind !== 'xtream' || state.tab === 'live' || state.group !== null || state.query) return false;
  const e = vodCache.get(vodKey(p, state.tab));
  return !!e && !Array.isArray(e);
};
function cardHtml(it, key, k) {
  return `<li class="scard" data-shelf="${esc(key)}" data-k="${k}">
    <span class="poster-img${it.logo ? '' : ' no-img'}" data-initials="${initials(displayTitle(it.name))}" data-title="${esc(displayTitle(it.name))}" style="--hue:${hueOf(it.name)}">${it.logo ? `<img src="${esc(thumb(it.logo, 300))}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" onerror="this.closest('.scard')?.classList.add('noart');this.parentNode?.classList.add('no-img');this.remove()">` : ''}
      ${it.rating && it.rating < 9.5 ? `<span class="badge">★ ${it.rating.toFixed(1)}</span>` : ''}${progressBar(it)}</span>
    <span class="poster-name">${esc(it.kind === 'movie' || it.kind === 'series' ? displayTitle(it.name) : it.name)}</span>${it.year ? `<span class="muted">${esc(it.year)}</span>` : ''}</li>`;
}
function shelfHtml(title, key, items, { total = 0, group = null, pending = false } = {}) {
  shelfItems.set(key, items);
  const more = group !== null && total > items.length;
  return `<li class="shelf" data-key="${esc(key)}" ${group !== null ? `data-group="${esc(group)}"` : ''}>
    <div class="shelf-head"><h3>${esc(title)}</h3>${group !== null ? `<button class="btn small" data-see="${esc(group)}">See all${total ? ` ${total.toLocaleString()}` : ''}</button>` : ''}</div>
    <ul class="shelf-row">${items.map((it, k) => cardHtml(it, key, k)).join('')}${pending && !items.length ? '<li class="shelf-empty muted">Loading…</li>' : ''}${more ? `<li class="scard more" data-see="${esc(group)}"><span class="poster-img more-tile"><b>+${(total - items.length).toLocaleString()}</b><span>more</span></span></li>` : ''}</ul>
  </li>`;
}
function renderDiscover() {
  const p = state.current, tab = state.tab, entry = vodCache.get(vodKey(p, tab)), list = $('#list');
  shelfItems.clear();
  shelfObserver?.disconnect();
  const hidden = new Set(p.hiddenGroups || []);
  const groups = entry.groupList.filter(g => !hidden.has(g.name));
  const what = tab === 'movies' ? 'movies' : 'series';
  list.className = 'list discover';
  view.classList.add('vod');
  // Featured: TMDB's trending titles with backdrops when connected, else the most popular.
  const art = l => (l || []).filter(it => it.logo);
  const heroItems = (entry.trending?.some(it => it.tm?.backdrop)
    ? entry.trending.filter(it => it.tm?.backdrop).slice(0, 6).map(it => ({ ...it, heroLabel: `#${entry.trending.indexOf(it) + 1} trending this week` }))
    : art(entry.top).slice(0, 6));
  shelfItems.set('hero', heroItems);
  const fresh = art(entry.fresh).length >= 8 ? art(entry.fresh) : art(entry.recent);
  // Streaming services and section shelves, in the sidebar's order.
  const svc = [];
  for (const [name, re, c1, c2] of SERVICES) { const g = groups.find(g => re.test(g.name) && !/kids|anime/i.test(g.name)); if (g && !svc.some(s => s.group === g.name)) svc.push({ name, c1, c2, group: g.name, count: g.count }); }
  const order = SECTION_ORDER;
  // Shelves: TMDB lists and genres, then Featured, Kids… (streaming groups are the tiles above, not rows).
  const ordered = [...groups].filter(g => sectionOf(g.name) !== 'Streaming services' || !svc.some(s => s.group === g.name))
    .sort((a, b) => order.indexOf(sectionOf(a.name)) - order.indexOf(sectionOf(b.name)));
  const top10 = entry.trending?.length >= 5 ? entry.trending.slice(0, 10) : [];
  shelfItems.set('t10', top10);
  const saved = shelfPrev.get(vodKey(p, tab));
  list.innerHTML = `
    ${heroItems.length ? `<li class="disc-hero">${heroHtml(heroItems)}</li>` : ''}
    ${top10.length ? `<li class="shelf"><div class="shelf-head"><h3>Top 10 ${what} this week</h3><span class="muted">from TMDB</span></div><ul class="shelf-row top10">${top10.map((it, k) => `<li class="scard t10" data-shelf="t10" data-k="${k}"><span class="t10-n">${k + 1}</span>${cardHtml(it, 't10', k).replace(/^<li[^>]*>|<\/li>$/g, '')}</li>`).join('')}</ul></li>` : ''}
    ${entry.top?.length ? shelfHtml('Popular now', 'top', art(entry.top).slice(0, 40)) : ''}
    ${fresh.length ? shelfHtml(art(entry.fresh).length >= 8 ? 'New releases' : 'Recently added', 'recent', fresh.slice(0, 40)) : ''}
    ${svc.length >= 2 ? `<li class="shelf"><div class="shelf-head"><h3>Streaming services</h3></div><ul class="shelf-row services">${svc.map(s => `<li class="svc" data-see="${esc(s.group)}" style="--c1:${s.c1};--c2:${s.c2}"><b>${esc(s.name)}</b><span>${s.count.toLocaleString()} ${what}</span></li>`).join('')}</ul></li>` : ''}
    ${ordered.slice(0, 40).map(g => { const items = (entry.groups.get(g.name) || saved?.get(g.name) || []).slice(0, 24); return shelfHtml(groupLabel(g.name), 'g:' + g.name, items, { total: g.count, group: g.name, pending: !items.length }); }).join('')}
    ${groups.length > 40 ? `<li class="muted center shelf-note">${(groups.length - 40).toLocaleString()} more groups in the sidebar.</li>` : ''}`;
  requestAnimationFrame(() => addRowArrows(list));
  // Load a group's titles when its shelf scrolls near the viewport.
  shelfObserver = new IntersectionObserver(es => {
    for (const e of es) {
      if (!e.isIntersecting) continue;
      const g = e.target.dataset.group;
      if (g !== undefined && !entry.groups.has(g)) requestVod(p, tab, { group: g });
      shelfObserver.unobserve(e.target);
    }
  }, { rootMargin: '600px' });
  list.querySelectorAll('.shelf[data-group]').forEach(el => shelfObserver.observe(el));
}

function applyFilter({ keep = false } = {}) {
  const p = state.current;
  const keepRows = keep ? state.rendered : 0, keepY = scrollY;
  if (discoverMode()) {
    const entry = vodCache.get(vodKey(p, state.tab));
    state.filtered = entry.recent;
    $('#q').placeholder = `Search ${state.tab === 'series' ? `${entry.total.toLocaleString()} series` : plural(entry.total, 'movie')}`;
    renderDiscover();
    if (keep) scrollTo(0, keepY);
    renderSelbar();
    return;
  }
  shelfObserver?.disconnect();
  $('#list')?.classList.remove('discover');   // leaving the front page: the grid/list layout takes over cleanly
  view.classList.toggle('vod', state.tab !== 'live' && p.kind === 'xtream');
  const q = state.query;
  const items = currentItems();
  state.filtered = items.filter(c => {
    const g = c.group || '';
    // A TMDB group (genre / list) holds titles from many provider groups: its own fetch already filtered them.
    if (state.group === null ? !visibleGroup(p, g) : g !== state.group && !String(state.group).startsWith('tmdb:')) return false;
    if (!q) return true;
    if (/^\d+$/.test(q) && c.num) return String(c.num).startsWith(q);   // typing a number zaps by channel number
    return c.name.toLowerCase().includes(q) || g.toLowerCase().includes(q);
  });
  const what = state.tab === 'movies' ? 'movie' : state.tab === 'series' ? 'series' : p.id === 'fav' ? 'item' : 'channel';
  const entry = p.kind === 'xtream' && state.tab !== 'live' ? vodCache.get(vodKey(p, state.tab)) : null;
  const count = entry && !Array.isArray(entry) ? entry.total : items.length;
  $('#q').placeholder = `Search ${what === 'series' ? `${count.toLocaleString()} series` : plural(count, what)}`;
  const list = $('#list');
  list.classList.remove('discover');
  const vodGrid = state.tab !== 'live' && state.current.kind === 'xtream';
  list.classList.toggle('grid', vodGrid);
  if (vodGrid && state.vodSort !== 'default') {
    const yr = it => Number(it.year) || 0, by = {
      new: (a, b) => (b.added || 0) - (a.added || 0), rating: (a, b) => (b.rating || 0) - (a.rating || 0),
      az: (a, b) => collator.compare(vodMeta(a.name).title || a.name, vodMeta(b.name).title || b.name), year: (a, b) => yr(b) - yr(a),
    }[state.vodSort];
    if (by) state.filtered = [...state.filtered].sort(by);
  }
  list.innerHTML = '';
  state.rendered = 0;
  // A movie/series group (or a search) is a library page: title, count and sorting above the posters.
  if (vodGrid && (state.group !== null || state.query)) {
    const title = state.query ? `Results for “${esc(state.query)}”` : esc(state.group ? groupLabel(state.group) : UNGROUPED);
    list.insertAdjacentHTML('beforeend', `<li class="lib-head"><div><h2>${title}</h2><span class="muted">${plural(state.filtered.length, state.tab === 'series' ? 'series' : 'movie').replace('seriess', 'series')}</span></div>
      <label class="lib-sort">Sort<select data-sort-vod>${[['default', 'Featured'], ['new', 'Newest added'], ['rating', 'Top rated'], ['year', 'Release year'], ['az', 'A–Z']].map(([v, l]) => `<option value="${v}" ${state.vodSort === v ? 'selected' : ''}>${l}</option>`).join('')}</select></label></li>`);
    list.onchange = e => { if (e.target.matches('[data-sort-vod]')) { state.vodSort = e.target.value; applyFilter(); } };
  }
  if (state.filtered.length) {
    renderMore();
    while (state.rendered < Math.min(keepRows, state.filtered.length)) renderMore();
    if (keep) scrollTo(0, keepY);
  } else {
    const loading = state.tab !== 'live' && (!vodCache.has(vodKey(p, state.tab)) || [...vodPending].some(t => t.startsWith(vodKey(p, state.tab) + '|')));
    const msg = loading ? `Loading ${state.tab}…`
      : items.length ? 'No matches.'
      : p.id === 'fav' ? 'Tap the star on any channel or movie to add it here.'
      : p.id === 'recent' ? 'What you play shows up here.'
      : state.tab !== 'live' ? `This provider has no ${state.tab}.`
      : 'This playlist is empty. Use ⋯ → Add channel.';
    list.innerHTML = `<li class="empty small">${esc(msg)}</li>`;
  }
  renderSelbar();
}

function renderMore() {
  const list = $('#list');
  if (!list || state.rendered >= state.filtered.length) return;
  if (list.classList.contains('discover')) return;   // the Movies/Series front page has its own shelves, never the poster grid
  const end = Math.min(state.rendered + PAGE, state.filtered.length);
  const html = (state.tab === 'live' || state.current?.kind !== 'xtream') ? rowHtml : posterHtml;
  const shown = state.filtered.slice(state.rendered, end);
  list.insertAdjacentHTML('beforeend', shown.map((c, k) => html(c, state.rendered + k)).join(''));
  state.rendered = end;
  if (state.tab === 'live') ensureGuide(shown);
}

// Fill in what's on for rows already on screen, touching only that line (no rebuild, no flicker, taps keep working).
// Under the docked player (wide screens): what's on now with its description, then what's coming up;
// earlier programmes are listed when the channel has catch-up. Tap any programme for details.
// ---------- Offline downloads ----------
// A title is fetched from the server as one playable MP4 (H.264/AAC, 720p by default) and kept in the
// browser's storage in 8 MB pieces; the Downloads page plays it from there with no connection at all.
const DL_CHUNK = 8 << 20;
let dlActive = null;
// Fetch the download, waiting and retrying while the provider's one connection is in use (the server says
// "busy", or the stream was cut when playback started). Exact copies resume from the byte they stopped at;
// conversions restart from the beginning. onStart(restart) lets the caller reset its output.
async function dlFetchLoop(meta, ctrl, onStart, onChunk) {
  for (let attempt = 0; attempt < 80; attempt++) {
    const resume = meta.q === 'file' && meta.bytes > 0;
    let res;
    try {
      res = await fetch(`${serverApi('download')}&q=${meta.q}&url=${encodeURIComponent(meta.orig)}`, { signal: ctrl.signal, cache: 'no-store', headers: resume ? { range: `bytes=${meta.bytes}-` } : {} });
    } catch (e) {
      if (e.name === 'AbortError') throw e;
      res = null;
    }
    if (!res || res.status === 409 || res.status >= 500) {
      meta.waiting = res ? 'Waiting: something from this provider is playing' : 'Waiting for the connection…';
      refreshDownloadsUi();
      await new Promise((r, rej) => { const t = setTimeout(r, 15000); ctrl.signal.addEventListener('abort', () => { clearTimeout(t); rej(new DOMException('Cancelled', 'AbortError')); }, { once: true }); });
      continue;
    }
    if (!res.ok) throw new Error((await res.text()) || `The server replied ${res.status}`);
    delete meta.waiting;
    const restart = !(resume && res.status === 206);
    if (restart) { meta.bytes = 0; meta.parts = 0; }
    await onStart(restart);
    meta.duration = Number(res.headers.get('x-duration')) || meta.duration || 0;
    meta.estimate = Number(res.headers.get('x-estimate')) || meta.estimate || 0;
    const reader = res.body.getReader();
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) return;                                   // finished cleanly
        await onChunk(value);
      }
    } catch (e) {
      if (e.name === 'AbortError') throw e;
      meta.waiting = 'Connection lost: resuming…';          // the stream was cut (playback took the connection): go round again
      refreshDownloadsUi();
      await new Promise(r => setTimeout(r, 8000));
    }
  }
  throw new Error('Gave up after waiting too long for the provider connection');
}
// Speed over the last ~5 seconds, plus ETA from the size estimate.
function noteSpeed(meta, delta) {
  const now = Date.now();
  meta.samples = (meta.samples || []).filter(s => now - s.t < 5000);
  meta.samples.push({ t: now, b: delta });
  const span = now - (meta.samples[0]?.t || now);
  meta.speed = span > 500 ? meta.samples.reduce((n, s) => n + s.b, 0) / (span / 1000) : meta.speed || 0;
  meta.startedAt ||= now;
}
const fmtSpeed = bps => bps >= 1e6 ? `${(bps * 8 / 1e6).toFixed(1)} Mbps` : `${Math.round(bps * 8 / 1e3)} kbps`;
const fmtEta = s => !Number.isFinite(s) || s < 0 ? '' : s >= 3600 ? `${Math.floor(s / 3600)}h ${Math.round((s % 3600) / 60)}m` : s >= 60 ? `${Math.round(s / 60)} min` : `${Math.round(s)}s`;
const fmtBytes = n => n >= 1e9 ? `${(n / 1e9).toFixed(2)} GB` : `${Math.round(n / 1e6)} MB`;
async function startDownload(item, q = '720') {
  if (!canSync()) return toast('Downloads need your server connection');
  if (dlActive) return toast('One download at a time: wait for the current one to finish');
  const id = `dl-${uid()}`;
  const meta = { id, name: item.name, logo: item.logo || '', group: item.group || '', year: item.year || '', kind: item.kind === 'episode' ? 'episode' : 'movie', q, bytes: 0, parts: 0, done: false, at: Date.now(), orig: item.url, src: item.src };
  state.downloads[id] = meta;
  await db.set('downloads', state.downloads);
  try { await navigator.storage?.persist?.(); } catch {}
  toast(`Downloading ${item.name}`, { action: 'View', fn: () => { location.hash = '#/downloads'; } });
  runDownload(meta);
}
async function runDownload(meta) {
  const ctrl = new AbortController();
  dlActive = { id: meta.id, ctrl };
  delete meta.error;
  try {
    let buf = [], size = 0, lastSave = 0;
    const flush = async () => { if (size) { await db.set(`dlc:${meta.id}:${meta.parts}`, new Blob(buf, { type: 'video/mp4' })); meta.parts++; buf = []; size = 0; } };
    await dlFetchLoop(meta, ctrl, async restart => {
      if (restart) { for (let i = 0; i < 400; i++) db.del(`dlc:${meta.id}:${i}`).catch(() => {}); buf = []; size = 0; }
    }, async value => {
      buf.push(value); size += value.length; meta.bytes += value.length; noteSpeed(meta, value.length);
      if (size >= DL_CHUNK) {
        await flush();
        if (Date.now() - lastSave > 3000) { lastSave = Date.now(); db.set('downloads', state.downloads).catch(() => {}); refreshDownloadsUi(); }
      }
    });
    await flush();
    if (meta.bytes < 1e6) throw new Error('The server sent no video (the file may be unavailable)');
    meta.done = true; meta.finishedAt = Date.now();
  } catch (e) {
    meta.error = e.name === 'AbortError' ? 'Cancelled' : (e.message || 'Failed');
  }
  delete meta.samples;
  dlActive = null;
  await db.set('downloads', state.downloads);
  refreshDownloadsUi();
  toast(meta.done ? `Downloaded: ${meta.name} (${fmtBytes(meta.bytes)})` : `Download stopped: ${meta.error}`);
}
async function offlineBlob(meta) {
  const parts = [];
  for (let i = 0; i < meta.parts; i++) parts.push(await db.get(`dlc:${meta.id}:${i}`));
  return new Blob(parts.filter(Boolean), { type: 'video/mp4' });
}
async function playOffline(meta) {
  if (meta.disk) return playDiskFile(meta);
  const url = URL.createObjectURL(await offlineBlob(meta));
  playChannel([{ id: meta.id, kind: meta.kind, name: meta.name, logo: meta.logo, group: meta.group, year: meta.year, url, pkey: meta.orig, offline: true, src: meta.src }], 0);
}
async function deleteDownload(meta) {
  if (dlActive?.id === meta.id) dlActive.ctrl.abort();
  for (let i = 0; i < meta.parts; i++) await db.del(`dlc:${meta.id}:${i}`).catch(() => {});
  if (meta.disk) await db.del(`dlh:${meta.id}`).catch(() => {});   // the file itself stays on disk
  delete state.downloads[meta.id];
  await db.set('downloads', state.downloads);
}
function downloadMenu(item) {
  const disk = !!window.showSaveFilePicker;   // Chrome/Edge on a computer: save straight to a file you choose
  const pick = (q, label) => ({ label, fn: () => (disk ? saveToDisk(item, q) : startDownload(item, q)) });
  menu(`${disk ? 'Save to disk' : 'Download'}: ${item.name}`, [
    pick('720', 'Standard (720p, ~1 GB per 2 hours)'),
    pick('1080', 'High (1080p, ~2.5 GB per 2 hours)'),
    pick('orig', disk ? 'Best for playback (H.264 up to 1080p)' : 'Original quality (biggest)'),
    disk && { label: 'Exact original file (4K stays 4K; plays in VLC/TV apps, maybe not in the browser)', fn: () => saveToDisk(item, 'file') },
    disk && { label: 'Keep inside the app instead (browser storage)', fn: () => menu('Quality', [
      { label: 'Standard (720p)', fn: () => startDownload(item, '720') }, { label: 'High (1080p)', fn: () => startDownload(item, '1080') }, { label: 'Original', fn: () => startDownload(item, 'orig') }]) },
  ]);
}
// Computer: the MP4 streams from the server straight into a file on the hard drive (no storage cap, plays in
// anything). The file handle is kept so the Downloads page can play it later without asking again.
async function saveToDisk(item, q) {
  if (!canSync()) return toast('Downloads need your server connection');
  if (dlActive) return toast('One download at a time: wait for the current one to finish');
  const safe = item.name.replace(/[\\/:*?"<>|]+/g, ' ').replace(/\s+/g, ' ').trim();
  const ext = q === 'file' ? ((/\.(mkv|mp4|avi|mov|ts|m4v)(\?|$)/i.exec(item.url) || [])[1] || 'mkv').toLowerCase() : 'mp4';
  let handle;
  try { handle = await showSaveFilePicker({ suggestedName: `${safe}.${ext}`, types: [{ description: ext === 'mp4' ? 'MP4 video' : `${ext.toUpperCase()} video`, accept: { [ext === 'mp4' ? 'video/mp4' : 'application/octet-stream']: [`.${ext}`] } }] }); } catch { return; }
  const id = `dl-${uid()}`;
  const meta = { id, name: item.name, logo: item.logo || '', group: item.group || '', year: item.year || '', kind: item.kind === 'episode' ? 'episode' : 'movie', q, bytes: 0, parts: 0, done: false, at: Date.now(), orig: item.url, src: item.src, disk: true, file: handle.name };
  state.downloads[id] = meta;
  await db.set('downloads', state.downloads);
  try { await db.set(`dlh:${id}`, handle); } catch {}
  toast(`Saving ${handle.name}`, { action: 'View', fn: () => { location.hash = '#/downloads'; } });
  const ctrl = new AbortController();
  dlActive = { id, ctrl };
  let writable;
  try {
    let lastSave = 0;
    await dlFetchLoop(meta, ctrl, async restart => {
      if (restart || !writable) { try { await writable?.abort(); } catch {} writable = await handle.createWritable(); }   // a fresh file; a resume keeps appending
    }, async value => {
      await writable.write(value);
      meta.bytes += value.length; noteSpeed(meta, value.length);
      if (Date.now() - lastSave > 3000) { lastSave = Date.now(); db.set('downloads', state.downloads).catch(() => {}); refreshDownloadsUi(); }
    });
    await writable.close();
    if (meta.bytes < 1e6) throw new Error('The server sent no video (the file may be unavailable)');
    meta.done = true; meta.finishedAt = Date.now();
  } catch (e) {
    meta.error = e.name === 'AbortError' ? 'Cancelled' : (e.message || 'Failed');
    try { await writable?.abort(); } catch {}
  }
  delete meta.samples;
  dlActive = null;
  await db.set('downloads', state.downloads);
  refreshDownloadsUi();
  toast(meta.done ? `Saved ${handle.name} (${fmtBytes(meta.bytes)})` : `Save stopped: ${meta.error}`);
}
async function playDiskFile(meta) {
  const handle = await db.get(`dlh:${meta.id}`).catch(() => null);
  if (!handle) return toast('That file can’t be reopened from here. Open it from Finder instead.');
  try {
    if ((await handle.queryPermission({ mode: 'read' })) !== 'granted' && (await handle.requestPermission({ mode: 'read' })) !== 'granted') return toast('Permission to read the file was refused');
    const file = await handle.getFile();
    const url = URL.createObjectURL(file);
    playChannel([{ id: meta.id, kind: meta.kind, name: meta.name, logo: meta.logo, group: meta.group, year: meta.year, url, pkey: meta.orig, offline: true, src: meta.src }], 0);
  } catch { toast('That file isn’t there any more (moved or deleted?)'); }
}
function refreshDownloadsUi() { if (location.hash === '#/downloads') showDownloads({ keep: true }); }
setInterval(() => { if (dlActive && location.hash === '#/downloads') showDownloads({ keep: true }); }, 1000);
let spaceLine = '';
async function showDownloads({ keep = false } = {}) {
  state.current = null;
  setTopbar({ title: 'Downloads', back: '#/' });
  const list = Object.values(state.downloads).sort((a, b) => b.at - a.at);
  if (!keep || !spaceLine) { try { const e = await navigator.storage?.estimate?.(); if (e?.quota) spaceLine = `${fmtBytes(e.usage || 0)} used of ${fmtBytes(e.quota)} available on this device`; } catch {} }
  const space = spaceLine;
  const y = scrollY;
  const detail = m => {
    const pct = m.estimate ? Math.min(99, (m.bytes / m.estimate) * 100) : 0;
    const eta = m.estimate && m.speed ? (m.estimate - m.bytes) / m.speed : NaN;
    const elapsed = m.startedAt ? (Date.now() - m.startedAt) / 1000 : 0;
    return `<span class="dl-stats">
      <b>${m.estimate ? `${pct.toFixed(0)}%` : fmtBytes(m.bytes)}</b>
      ${m.waiting ? `<span class="dl-wait">${esc(m.waiting)}</span>` : ''}
      <span>${fmtBytes(m.bytes)}${m.estimate ? ` of ~${fmtBytes(m.estimate)}` : ''}</span>
      ${m.speed ? `<span>${fmtSpeed(m.speed)}</span>` : ''}
      ${fmtEta(eta) ? `<span>${fmtEta(eta)} left</span>` : ''}
      ${elapsed > 5 ? `<span class="muted">${fmtEta(elapsed)} elapsed</span>` : ''}
      <span class="muted">${m.q === 'file' ? 'exact copy' : m.q === 'orig' ? 'original · H.264' : m.q + 'p · H.264'}${m.disk ? ' · to disk' : ' · in app'}</span>
    </span>`;
  };
  view.innerHTML = `
    <p class="muted">${esc(space || 'Downloaded titles play without a connection.')}${window.showSaveFilePicker ? ' On this computer, downloads go straight to a file you choose.' : ''}</p>
    ${list.length ? `<ul class="cards">${list.map(m => `<li class="card dl" data-dl="${m.id}">
      <span class="hs-poster">${m.logo ? `<img src="${esc(m.logo)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}</span>
      <span class="card-text"><span class="card-title">${esc(niceName(m))}</span>
        ${m.done ? `<span class="muted">${m.disk ? `On disk: ${esc(m.file)} · ` : ''}${fmtBytes(m.bytes)} · ${m.q === 'file' ? 'Exact original' : m.q === 'orig' ? 'Original' : m.q + 'p'}${m.duration ? ` · ${fmtDur(m.duration)}` : ''}${m.startedAt && m.finishedAt ? ` · took ${fmtEta((m.finishedAt - m.startedAt) / 1000)}` : ''}</span>`
          : m.error ? `<span class="muted">Stopped: ${esc(m.error)} · ${fmtBytes(m.bytes)} saved</span>` : detail(m)}
        ${!m.done && !m.error ? `<span class="bar dl-bar">${m.estimate ? `<i style="width:${Math.min(99, (m.bytes / m.estimate) * 100).toFixed(1)}%"></i>` : '<i class="indeterminate"></i>'}</span>` : ''}</span>
      ${m.done ? `<button class="btn primary small" data-act="play">${icon('play')} Play</button>` : m.error ? '<button class="btn small" data-act="retry">Retry</button>' : '<button class="btn small" data-act="cancel">Cancel</button>'}
      <button class="icon-btn" data-act="delete" aria-label="Delete">${icon('close')}</button>
    </li>`).join('')}</ul>`
    : `<div class="empty">${icon('film', 'big')}<p><b>No downloads yet</b></p><p class="muted">Open a film or episode and choose Download. Keep the app open while it downloads.</p></div>`}`;
  if (keep) scrollTo(0, y);
  view.onclick = async e => {
    const li = e.target.closest('[data-dl]'), act = e.target.closest('[data-act]')?.dataset.act;
    if (!li) return;
    const m = state.downloads[li.dataset.dl];
    if (!m) return;
    if (act === 'play' || (!act && m.done)) return playOffline(m);
    if (act === 'cancel' && dlActive?.id === m.id) return dlActive.ctrl.abort();
    if (act === 'retry') { if (dlActive) return toast('One download at a time'); m.bytes = 0; m.parts = 0; runDownload(m); return showDownloads(); }
    if (act === 'delete') { if (!await confirmDialog(m.disk ? `Remove “${m.name}” from this list? The file on your disk is kept.` : `Delete the download of “${m.name}”?`, m.disk ? 'Remove' : 'Delete', true)) return; await deleteDownload(m); showDownloads(); }
  };
}

// Now-playing page for a film or episode (under the video): poster, title, chips, description, cast,
// Save / trailer / start over, then more titles from the same group.
const vodInfoCache = new Map();
async function renderVodPanel(c) {
  const el = $('#vod-panel');
  if (!el) return;
  if (!c || isLiveChannel(c) || c.kind === 'catchup') { el.hidden = true; el.innerHTML = ''; return; }
  const p = playlistOf(c), x = xtOf(p);
  let info = vodInfoCache.get(c.sid) || {};
  const draw = () => {
    if (nowChannel() !== c) return;
    const i = info.info || {};
    const pr = state.progress[c.url];
    const meta = [c.year || String(i.releasedate || i.release_date || '').slice(0, 4), i.duration, i.genre || c.genre, c.kind === 'episode' ? c.group : prettyGroup(c.group)].filter(Boolean);
    const plot = i.plot || i.description || c.plot || '';
    const entry = p && vodCache.get(vodKey(p, c.kind === 'episode' ? 'series' : 'movies'));
    const more = entry && !Array.isArray(entry) ? (entry.groups.get(c.group) || entry.recent).filter(it => it.url !== c.url).slice(0, 18) : [];
    shelfItems.set('vp-more', more);
    el.innerHTML = `
      <div class="vp-top">
        <span class="vp-poster poster-img" data-initials="${initials(c.name)}">${(i.movie_image || c.logo) ? `<img src="${esc(i.movie_image || c.logo)}" alt="" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}</span>
        <div class="vp-text">
          <h2 class="vp-title">${esc(niceName(c))}</h2>
          <div class="vp-meta">${meta.map(m => `<span class="pill">${esc(m)}</span>`).join('')}${c.rating ? `<span class="pill rating">★ ${c.rating.toFixed(1)}</span>` : ''}${qualityLabel() ? `<span class="pill q">${esc(qualityLabel())}</span>` : ''}${vodInfo?.audio?.length > 1 ? `<span class="pill">${vodInfo.audio.length} audio</span>` : ''}${vodInfo?.subs?.some(s => s.text) ? '<span class="pill">Subtitles</span>' : ''}</div>
          ${plot ? `<p class="vp-plot" data-plot>${esc(plot)}</p>` : ''}
          ${i.cast || i.actors || i.director ? `<p class="muted vp-cast">${i.director ? `Director: ${esc(i.director)}` : ''}${(i.cast || i.actors) ? `${i.director ? ' · ' : ''}Cast: ${esc(i.cast || i.actors)}` : ''}</p>` : ''}
          <div class="vp-actions">
            <button class="btn" data-vp="fav">${favUrls.has(c.url) ? '★ Saved' : '☆ Save'}</button>
            ${i.youtube_trailer ? '<button class="btn" data-vp="trailer">Trailer</button>' : ''}
            ${pr && pr.t > 30 ? '<button class="btn" data-vp="restart">Start over</button>' : ''}
            ${c.kind === 'episode' && state.playing && state.playing.index < state.playing.list.length - 1 ? `<button class="btn primary" data-vp="next">${icon('next')} Next episode</button>` : ''}
            ${!c.offline && canSync() ? '<button class="btn" data-vp="dl">Download</button>' : ''}
          </div>
        </div>
      </div>
      ${more.length ? `<div class="section-title">More in ${esc(prettyGroup(c.group) || 'this group')}</div><ul class="shelf-row">${more.map((it, k) => cardHtml(it, 'vp-more', k)).join('')}</ul>` : ''}`;
    el.hidden = false;
    el.onclick = e => {
      const b = e.target.closest('[data-vp]');
      if (b) {
        if (b.dataset.vp === 'fav') toggleFavorite(c, null).then(draw);
        if (b.dataset.vp === 'trailer') window.open(`https://www.youtube.com/watch?v=${encodeURIComponent(i.youtube_trailer)}`, '_blank');
        if (b.dataset.vp === 'restart') seekVod(0);
        if (b.dataset.vp === 'next') step(1);
        if (b.dataset.vp === 'dl') downloadMenu(c);
        return;
      }
      if (e.target.closest('[data-plot]')) e.target.closest('[data-plot]').classList.toggle('open');
      const card = e.target.closest('.scard');
      if (card) { const it = shelfItems.get('vp-more')?.[+card.dataset.k]; if (it) it.kind === 'series' ? seriesSheet(it) : playChannel([it], 0); }
    };
  };
  draw();
  if (x && c.sid && c.kind === 'movie' && !vodInfoCache.has(c.sid)) {
    try { info = (await x.vodInfo(c.sid)) || {}; vodInfoCache.set(c.sid, info); draw(); } catch {}
  }
  if (p && p.kind === 'xtream' && !vodCache.has(vodKey(p, c.kind === 'episode' ? 'series' : 'movies'))) loadVod(p, c.kind === 'episode' ? 'series' : 'movies').then(draw).catch(() => {});
}

function renderEpgPanel() {
  const el = $('#epg-panel');
  if (!el) return;
  const c = nowChannel();
  const g = c && isLiveChannel(c) && guideFor(c);
  const list = g ? g.list(c) : [];
  if (!list.length) { el.hidden = true; el.innerHTML = ''; return; }
  const now = Date.now();
  const i = list.findIndex(p => p[1] > now);
  const cur = i >= 0 && list[i][0] <= now ? list[i] : null;
  const upcoming = list.slice(cur ? i + 1 : Math.max(i, 0), (cur ? i + 1 : Math.max(i, 0)) + 8);
  const earlier = archiveDays(c) ? list.slice(Math.max(0, (cur ? i : i < 0 ? list.length : i) - 6), cur ? i : i < 0 ? list.length : i).reverse() : [];
  const row = (p, k, cls = '') => `<li class="epg-row ${cls}" data-k="${k}"><span class="epg-time">${fmtTime(p[0])}</span><span class="row-text"><span class="row-name">${esc(p[2])}</span>${p[3] ? `<span class="muted clamp">${esc(p[3])}</span>` : ''}</span>${cls === 'past' ? `<span class="tag">${icon('play')}</span>` : ''}</li>`;
  el.innerHTML = `
    ${cur ? `<div class="epg-cur${cur[4]?.img ? ' has-img' : ''}" data-k="${i}">
      ${cur[4]?.img ? `<img class="epg-cur-img" src="${esc(cur[4].img)}" alt="" referrerpolicy="no-referrer" loading="lazy" onerror="this.remove()">` : ''}
      <div class="epg-cur-head"><span class="tag live">ON NOW</span><span class="muted">${fmtRange(cur)}</span>${cur[4]?.new ? '<span class="pill new">NEW</span>' : ''}${cur[4]?.s ? `<span class="pill">S${cur[4].s} E${cur[4].e}</span>` : ''}</div>
      <div class="epg-cur-title">${esc(cur[2])}</div>
      <span class="bar"><i style="width:${(((now - cur[0]) / (cur[1] - cur[0])) * 100).toFixed(1)}%"></i></span>
      ${cur[3] ? `<p class="epg-cur-desc">${esc(cur[3])}</p>` : ''}
    </div>` : ''}
    ${upcoming.length ? `<div class="section-title">Up next</div><ul class="epg-list">${upcoming.map(p => row(p, list.indexOf(p))).join('')}</ul>` : ''}
    ${earlier.length ? `<div class="section-title">Earlier · catch-up</div><ul class="epg-list">${earlier.map(p => row(p, list.indexOf(p), 'past')).join('')}</ul>` : ''}`;
  el.hidden = false;
  el.onclick = e => { const k = e.target.closest('[data-k]'); if (k) programmeSheet(c, list[+k.dataset.k]); };
}
setInterval(renderEpgPanel, 60_000);

function updateRowsInPlace() {
  renderEpgPanel();
  document.querySelectorAll('#list .row, #ov-list .row').forEach(r => {
    const c = r.dataset.k !== undefined ? ovShown[+r.dataset.k] : state.filtered[+r.dataset.i];
    const sub = r.querySelector('.row-sub');
    if (!c || !sub) return;
    const html = nowLine(c);
    if (html && sub.dataset.epg !== html) { sub.innerHTML = html; sub.dataset.epg = html; }
  });
}

function rerenderRows() {
  if (!$('#list') || !state.current) return;
  if (discoverMode()) return;   // the Movies/Series front page is shelves, not rows: nothing to redraw here
  const keep = state.rendered;
  $('#list').innerHTML = '';
  state.rendered = 0;
  while (state.rendered < Math.min(keep, state.filtered.length)) renderMore();
}

function initials(name) {
  return esc(String(name).split(/\s+/).map(w => (w.match(/[\p{L}\p{N}]/u) || [''])[0]).join('').slice(0, 2).toUpperCase() || '?');
}

// A stable per-channel hue so logo-less channels get a distinct coloured tile, not grey.
const hueOf = s => { let h = 7; for (const ch of String(s || '')) h = (h * 31 + ch.charCodeAt(0)) >>> 0; return h % 360; };
function logoHtml(c, extra = '') {
  return `<span class="logo" ${extra} style="--hue:${hueOf(c.name)}" data-initials="${initials(c.name)}">${c.logo
    ? `<img src="${esc(c.logo)}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}</span>`;
}

const nowChannel = () => state.playing && state.playing.list[state.playing.index];

function nowLine(c) {
  const g = isLiveChannel(c) && guideFor(c);
  if (!g) return '';
  const { now, next } = g.nowNext(c);
  if (!now) return next ? `<span class="muted">Next ${fmtTime(next[0])}: ${esc(next[2])}</span>` : '';
  const pct = ((Date.now() - now[0]) / (now[1] - now[0])) * 100;
  const then = state.settings.showNext !== false && next ? `<span class="epg-next"> · ${fmtTime(next[0])} ${esc(next[2])}</span>` : '';
  return `<span class="epg-now"><span class="muted">${esc(now[2])}${then}</span><span class="bar"><i style="width:${pct.toFixed(1)}%"></i></span></span>`;
}

function progressBar(c) {
  const pr = state.progress[c.url];
  return pr && !pr.done && pr.t > 30 ? `<span class="bar"><i style="width:${Math.min(100, (pr.t / pr.d) * 100).toFixed(1)}%"></i></span>` : '';
}

function rowHtml(c, i) {
  const selected = state.selected.has(c.id);
  const playing = nowChannel()?.url === c.url;
  const epg = nowLine(c);
  const sub = epg || (!state.group && c.group ? `<span class="muted">${esc(prettyGroup(c.group))}</span>` : '');
  return `<li class="row${selected ? ' selected' : ''}${playing ? ' playing' : ''}" data-i="${i}" role="button" tabindex="0" aria-label="${esc(c.name)}">
    ${state.selecting ? `<span class="check">${selected ? icon('check') : ''}</span>` : ''}
    ${state.settings.numbers && c.num ? `<span class="ch-num">${c.num}</span>` : ''}${logoHtml(c)}
    <span class="row-text"><span class="row-name">${esc(niceName(c))}${archiveDays(c) ? '<span class="tag">Catch-up</span>' : ''}</span><span class="row-sub">${sub}</span>${progressBar(c)}</span>
    ${state.reordering ? `<span class="rhandle" aria-label="Drag to reorder">${icon('grip')}</span>` : state.selecting ? '' : `<button class="icon-btn star${favUrls.has(c.url) ? ' on' : ''}" data-act="fav" aria-label="Favorite">${icon('star')}</button>
    <button class="icon-btn" data-act="more" aria-label="Options">${icon('more')}</button>`}
  </li>`;
}

function posterHtml(it, i) {
  const m = vodMeta(it.name);   // "TOP - Dune (2021) 4K (US)" -> "Dune", 2021
  const name = it.kind === 'episode' ? it.name : displayTitle(it.name), year = it.year ?? m.year;
  return `<li class="poster" data-i="${i}">
    <span class="poster-img" data-initials="${initials(it.name)}">${it.logo ? `<img src="${esc(thumb(it.logo, 300))}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}
      ${it.rating ? `<span class="badge">★ ${it.rating.toFixed(1)}</span>` : ''}${progressBar(it)}</span>
    <span class="poster-name">${esc(name)}</span>
    ${year ? `<span class="muted">${esc(year)}</span>` : ''}
  </li>`;
}

function onListClick(e) {
  const see = e.target.closest('[data-see]');
  if (see) { state.group = see.dataset.see; updateTopbar(); renderChips(); applyFilter(); toListTop(); return; }
  const hp = e.target.closest('[data-hero-play]');
  if (hp) { const it = shelfItems.get('hero')?.[+hp.dataset.heroPlay]; if (it) return it.kind === 'series' ? seriesSheet(it) : playBestVersion(it); }
  const card = e.target.closest('.scard, .hero, .hero-slide');
  if (card) {
    const it = shelfItems.get(card.dataset.shelf)?.[+card.dataset.k];
    if (!it) return;
    const heroBtn = e.target.closest('[data-hero]')?.dataset.hero;
    if (heroBtn === 'play' && it.kind !== 'series') return playChannel([it], 0);
    return it.kind === 'series' ? seriesSheet(it) : movieSheet(it);
  }
  const el = e.target.closest('.row, .poster');
  if (!el) return;
  const i = +el.dataset.i;
  const c = state.filtered[i];
  if (!c) return;
  if (el.classList.contains('poster')) return c.kind === 'series' ? seriesSheet(c) : movieSheet(c);
  if (state.reordering || suppressClick) { suppressClick = false; return; }
  const act = e.target.closest('[data-act]')?.dataset.act;
  if (state.selecting) {
    if (state.selected.has(c.id)) state.selected.delete(c.id); else state.selected.add(c.id);
    el.classList.toggle('selected', state.selected.has(c.id));
    el.querySelector('.check').innerHTML = state.selected.has(c.id) ? icon('check') : '';
    return renderSelbar();
  }
  if (act === 'fav') return toggleFavorite(c, e.target.closest('button'));
  if (act === 'more') return channelMenu(c);
  if (c.kind === 'series') return seriesSheet(c);
  playChannel(state.filtered, i);
}

function highlightPlaying() {
  const url = nowChannel()?.url;
  document.querySelectorAll('#list .row').forEach(r => r.classList.toggle('playing', state.filtered[+r.dataset.i]?.url === url));
}

// ---------- favorites ----------
async function toggleFavorite(c, button) {
  const adding = !favUrls.has(c.url);
  state.favorites = adding ? [...state.favorites, cloneChannel(c)] : state.favorites.filter(f => f.url !== c.url);
  syncFavs();
  await db.set('favorites', state.favorites);
  favoritesChanged();
  button?.classList.toggle('on', adding);
  if (state.current?.id === 'fav') { state.current.channels = state.favorites; refreshPlaylistView({ keep: true }); }
  toast(adding ? 'Added to Favorites' : 'Removed from Favorites', { action: 'Undo', fn: () => toggleFavorite(c, button) });
}

async function addFavorites(channels) {
  const fresh = dedupe(channels).filter(c => !favUrls.has(c.url)).map(cloneChannel);
  state.favorites = [...state.favorites, ...fresh];
  syncFavs();
  await db.set('favorites', state.favorites);
  favoritesChanged();
  toast(`Added ${plural(fresh.length, 'channel')} to Favorites`);
}

// ---------- selection ----------
// ---------- reorder channels in a group (TiviMate-style) ----------
const canReorder = () => { const p = state.current; return p && !p.virtual && !p.readOnly && state.tab === 'live' && state.group !== null && !state.query; };
function toggleReordering() {
  state.reordering = !state.reordering;
  state.selecting = false;
  updateTopbar();
  applyFilter({ keep: true });
  if (state.reordering) toast('Reorder mode: drag ⠿ (or press and hold a channel) to move it. Tap ⠿ in the top bar when done.', { action: 'Done', fn: toggleReordering });
}
// Puts the group's channels in `ordered` order, keeping the group's slots in the playlist; backup copies
// of a merged channel travel with it.
async function reorderGroup(p, group, ordered) {
  const rank = new Map();
  ordered.forEach((c, i) => { if (!rank.has(dupKey(c))) rank.set(dupKey(c), i); });
  const slots = [], members = [];
  p.channels.forEach((c, i) => { if ((c.group || '') === group) { slots.push(i); members.push(c); } });
  members.sort((a, b) => (rank.get(dupKey(a)) ?? 1e9) - (rank.get(dupKey(b)) ?? 1e9));
  const next = [...p.channels];
  slots.forEach((slot, k) => { next[slot] = members[k]; });
  const before = p.channels;
  p.channels = next;
  await save(p);
  return before;
}
async function moveInGroup(c, toTop) {
  const p = state.current, group = c.group || '';
  const list = (m3uKinds(p)?.live || p.channels).filter(x => (x.group || '') === group && x !== c);
  const before = await reorderGroup(p, group, toTop ? [c, ...list] : [...list, c]);
  rerender();
  toast(toTop ? 'Moved to top of group' : 'Moved to bottom of group', { action: 'Undo', fn: async () => { p.channels = before; await save(p); rerender(); } });
}
// Move channels within a group: drag the ⠿ handle in Reorder mode, or long-press any row (TiviMate-style).
// While dragging, holding near the top or bottom edge scrolls the page, and the row follows.
let drag = null, press = null;
function bindRowDrag() {
  const list = $('#list');
  const placeAt = (x, y) => {
    const over = document.elementFromPoint(x, y)?.closest('#list .row');
    if (!over || over === drag.li) return;
    const r = over.getBoundingClientRect();
    over.parentNode.insertBefore(drag.li, y < r.top + r.height / 2 ? over : over.nextSibling);
  };
  const visibleTop = () => Math.max($('.chrome').getBoundingClientRect().bottom, $('.filters')?.getBoundingClientRect().bottom || 0);
  const autoScroll = () => {
    if (!drag) return;
    const top = visibleTop(), EDGE = 100;
    const v = drag.y < top + EDGE ? -(top + EDGE - drag.y) : drag.y > innerHeight - EDGE ? drag.y - (innerHeight - EDGE) : 0;
    if (v && (v > 0 || scrollY > 0)) {
      scrollBy(0, Math.max(-30, Math.min(30, v / 2.5)));
      if (v > 0) renderMore();
      placeAt(innerWidth / 2, Math.min(Math.max(drag.y, top + 8), innerHeight - 8));   // finger may be over the search bar: use the nearest row
    }
    setTimeout(autoScroll, 16);
  };
  const start = (li, e) => {
    drag = { li, x: e.clientX, y: e.clientY };
    li.classList.add('dragging');
    try { li.setPointerCapture(e.pointerId); } catch {}
    navigator.vibrate?.(10);
    setTimeout(autoScroll, 16);
  };
  list.addEventListener('pointerdown', e => {
    const li = e.target.closest('.row');
    if (!li || !canReorder() || state.selecting) return;
    if (state.reordering && e.target.closest('.rhandle')) { e.preventDefault(); return start(li, e); }
    if (e.target.closest('button')) return;
    // Long-press (0.45s without moving) picks the row up.
    press = { li, x: e.clientX, y: e.clientY, timer: setTimeout(() => { press = null; suppressClick = true; start(li, e); }, e.pointerType === 'mouse' ? 300 : 450) };
  });
  // iOS: once a row is picked up, the finger moves the row, not the page.
  list.addEventListener('touchmove', e => { if (drag) e.preventDefault(); }, { passive: false });
  bindRowDrag.placeAt = placeAt;
  if (bindRowDrag.bound) return;
  bindRowDrag.bound = true;
  // Window-level (added once), so the drag keeps working when the finger leaves the list (over the search bar, above it).
  addEventListener('pointermove', e => {
    if (press && Math.hypot(e.clientX - press.x, e.clientY - press.y) > 8) { clearTimeout(press.timer); press = null; }   // it's a scroll
    if (drag) { drag.x = e.clientX; drag.y = e.clientY; bindRowDrag.placeAt(innerWidth / 2, e.clientY); }
  });
  const drop = async () => {
    if (press) { clearTimeout(press.timer); press = null; }
    if (!drag) return;
    drag.li.classList.remove('dragging');
    drag = null;
    const p = state.current;
    const ordered = [...$('#list').querySelectorAll('.row')].map(r => state.filtered[+r.dataset.i]).concat(state.filtered.slice(state.rendered));
    const before = await reorderGroup(p, state.group, ordered);
    applyFilter({ keep: true });
    if (!state.reordering) toast('Channel moved', { action: 'Undo', fn: async () => { p.channels = before; await save(p); rerender(); } });
  };
  addEventListener('pointerup', drop);
  addEventListener('pointercancel', drop);
}
let suppressClick = false;   // the click that ends a long-press drag must not play the channel

function toggleSelecting() {
  state.selecting = !state.selecting;
  state.selected.clear();
  updateTopbar();
  applyFilter();
}

function renderSelbar() {
  const bar = $('#selbar');
  if (!bar) return;
  bar.hidden = !state.selecting;
  if (!state.selecting) return;
  const n = state.selected.size;
  const all = n > 0 && n >= state.filtered.length;
  bar.innerHTML = `<button class="btn small" data-sa="all">${all ? 'Select none' : 'Select all'}</button>
    <span class="selcount">${n.toLocaleString()} selected</span>
    <button class="btn small primary" data-sa="actions" ${n ? '' : 'disabled'}>Actions</button>`;
  bar.onclick = e => {
    const b = e.target.closest('[data-sa]');
    if (!b) return;
    if (b.dataset.sa === 'all') {
      if (all) state.selected.clear(); else state.filtered.forEach(c => state.selected.add(c.id));
      return applyFilter();
    }
    selectionMenu();
  };
}

function selectionMenu() {
  const p = state.current;
  const chosen = p.channels.filter(c => state.selected.has(c.id));
  menu(`${plural(chosen.length, 'channel')} selected`, [
    p.id !== 'fav' && { label: 'Add to Favorites', fn: () => addFavorites(chosen) },
    { label: 'Copy to playlist…', fn: () => copyToMenu(chosen) },
    { label: 'Move to group…', fn: () => moveToGroup(p, chosen) },
    { label: 'Export / share selected…', fn: () => exportMenu(`${p.name} (selection)`, chosen, p.header) },
    { label: `Delete ${plural(chosen.length, 'channel')}`, danger: true, fn: () => deleteChannels(p, new Set(chosen.map(c => c.id))) },
  ]);
}

async function moveToGroup(p, channels) {
  const v = await formSheet({
    title: 'Move to group',
    fields: [{ name: 'group', label: 'Group name (leave empty for none)', value: channels[0]?.group || '', list: groupsOf(p.channels).map(g => g.name).filter(Boolean) }],
    submit: 'Move',
  });
  if (!v) return;
  channels.forEach(c => { c.group = v.group; });
  await save(p);
  rerender();
  toast(`Moved ${plural(channels.length, 'channel')}`);
}

function copyToMenu(channels) {
  menu(`Copy ${plural(channels.length, 'channel')} to…`, [
    { label: '＋ New playlist…', fn: async () => {
      const v = await formSheet({ title: 'New playlist', fields: [{ name: 'name', label: 'Name', value: 'My playlist', required: true }], submit: 'Create' });
      if (!v) return;
      const p = await createPlaylist(v.name, channels.map(cloneChannel));
      toast(`Created “${p.name}”`, { action: 'Open', fn: () => { location.hash = `#/p/${p.id}`; } });
    } },
    ...state.playlists.filter(p => p !== state.current).map(p => ({
      label: p.name,
      fn: async () => {
        p.channels.push(...channels.map(cloneChannel));
        await save(p);
        toast(`Copied to “${p.name}”`);
      },
    })),
  ]);
}

async function deleteChannels(p, ids) {
  const before = p.channels;
  const all = new Set(ids);
  for (const c of p.channels) if (ids.has(c.id)) copiesOf(p, c).forEach(x => all.add(x.id));
  const urls = p.channels.filter(c => all.has(c.id)).map(c => c.url);
  if (p.sourceUrl || p.kind === 'xtream') p.deletedUrls = [...new Set([...(p.deletedUrls || []), ...urls])].slice(-5000);   // stays deleted after a refresh
  p.channels = p.channels.filter(c => !all.has(c.id));
  const removed = before.length - p.channels.length;
  await save(p);
  state.selected.clear();
  rerender();
  toast(`Deleted ${plural(removed, 'item')}`, {
    action: 'Undo',
    fn: async () => { p.channels = before; if (p.deletedUrls) p.deletedUrls = p.deletedUrls.filter(u => !urls.includes(u)); await save(p); rerender(); },
  });
}

// ---------- channel actions ----------
// Hand a stream to another app. On a Mac, VLC gets a one-track .xspf playlist (VLC opens those files) and
// the link is copied too, for File > Open Network in VLC.
function openExternal(x, c) {
  if (!x.file) { location.href = x.href; return; }
  const xml = s => String(s).replace(/[<>&"']/g, ch => `&#${ch.charCodeAt(0)};`);
  const body = `<?xml version="1.0" encoding="UTF-8"?>\n<playlist xmlns="http://xspf.org/ns/0/" version="1"><trackList><track><location>${xml(c.url)}</location><title>${xml(c.name || 'Stream')}</title></track></trackList></playlist>\n`;
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([body], { type: 'application/xspf+xml' }));
  a.download = `${String(c.name || 'stream').replace(/[\\/:*?"<>|]+/g, ' ').trim().slice(0, 80) || 'stream'}.xspf`;
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 60_000);
  navigator.clipboard?.writeText(c.url).catch(() => {});
  toast('Click the downloaded file to play it in VLC. The link is copied too (VLC: File > Open Network).', { duration: 9000 });
}
function externalItems(c) {
  return [
    ...externalPlayers(c.url).map(x => ({ label: `Open in ${x.name}`, fn: () => openExternal(x, c) })),
    { label: 'Copy stream link', fn: () => copyText(c.url, 'Link copied') },
  ];
}

function channelMenu(c) {
  const p = state.current;
  const editable = p && !p.readOnly && p.channels.includes(c);
  const fromPlayer = nowChannel() === c;
  const hasGuide = isLiveChannel(c) && guideFor(c)?.list(c).length;
  menu(c.name, [
    !fromPlayer && { label: 'Play', fn: () => { const i = state.filtered.indexOf(c); playChannel(i >= 0 ? state.filtered : [c], Math.max(i, 0)); } },
    hasGuide && { label: archiveDays(c) ? 'TV guide & catch-up' : 'TV guide', fn: () => scheduleSheet(c) },
    { label: favUrls.has(c.url) ? 'Remove from Favorites' : 'Add to Favorites', fn: () => toggleFavorite(c, null).then(highlightStars) },
    isLiveChannel(c) && { label: 'Watch in Multi-view', fn: () => addToMulti(c) },
    p?.id === 'fav' && { label: 'Move to list…', fn: () => favListMenu(c) },
    canReorder() && !state.reordering && { label: 'Reorder channels (drag)…', fn: () => { toggleReordering(); const li = $(`#list .row[data-i="${state.filtered.indexOf(c)}"]`); li?.scrollIntoView({ block: 'center' }); } },
    editable && isLiveChannel(c) && { label: 'Move to top of group', fn: () => moveInGroup(c, true) },
    editable && isLiveChannel(c) && { label: 'Move to bottom of group', fn: () => moveInGroup(c, false) },
    editable && isLiveChannel(c) && canSync() && epgUrls(p).length && { label: 'Fix TV guide match…', fn: () => guideMatchSheet(c, p) },
    editable && { label: 'Edit…', fn: () => editChannel(c, p) },
    ...externalItems(c),
    !c.kind && { label: 'Copy to playlist…', fn: () => copyToMenu([c]) },
    editable && { label: 'Delete', danger: true, fn: () => deleteChannels(p, new Set([c.id])) },
  ]);
}

// Favourites can be organised into lists: each list is just the favourite's group, shown as the chips in Favorites.
function favListMenu(c) {
  const lists = groupsOf(state.favorites).map(g => g.name).filter(n => n && n !== c.group);
  const moveTo = async name => {
    c.group = name;
    syncFavs();
    await db.set('favorites', state.favorites);
    favoritesChanged();
    rerender();
    toast(`Moved to ${prettyGroup(name)}`);
  };
  menu('Move to list', [
    ...lists.map(n => ({ label: prettyGroup(n), fn: () => moveTo(n) })),
    { label: 'New list…', fn: async () => { const v = await formSheet({ title: 'New list', fields: [{ name: 'name', label: 'List name', required: true, placeholder: 'e.g. Sports' }], submit: 'Create' }); if (v?.name) moveTo(v.name); } },
  ]);
}

// Pick which guide channel a channel uses: search the provider's guide by name, tap the right one.
function guideMatchSheet(c, p) {
  const g = guideFor(c), current = g?.key(c);
  sheet(`<div class="sheet-title">TV guide for ${esc(c.name)}</div>
    <p class="muted">${current ? `Now using <b>${esc(current)}</b>.` : 'No guide match yet.'} Search the guide and tap the right channel.</p>
    <label class="search">${icon('search')}<input id="gm-q" type="search" value="${esc(c.tvgName || c.name)}" autocomplete="off" autocorrect="off" spellcheck="false"></label>
    <ul class="list gm-list" id="gm-list"><li class="empty small">Searching…</li></ul>`, {
    onMount: el => {
      let seq = 0;
      const search = async q => {
        const my = ++seq, list = el.querySelector('#gm-list');
        if (q.trim().length < 2) { list.innerHTML = ''; return; }
        try {
          const r = await fetch(serverApi('guide'), { method: 'POST', body: JSON.stringify({ urls: epgUrls(p), search: q }) });
          const d = await r.json();
          if (my !== seq) return;
          list.innerHTML = (d.matches || []).map(m => `<li class="row" data-gid="${esc(m.id)}"><span class="row-text"><span class="row-name">${esc(m.name)}${m.id === current ? '<span class="tag">current</span>' : ''}</span><span class="muted">${esc(m.id)}</span></span></li>`).join('') || '<li class="empty small">Nothing in the guide matches that.</li>';
        } catch { list.innerHTML = '<li class="empty small">Couldn’t search the guide.</li>'; }
      };
      el.querySelector('#gm-q').oninput = debounce(e => search(e.target.value), 250);
      search(el.querySelector('#gm-q').value);
      el.querySelector('#gm-list').onclick = async e => {
        const li = e.target.closest('[data-gid]');
        if (!li) return;
        const id = li.dataset.gid;
        for (const x of [c, ...copiesOf(p, c)]) Object.assign(x, { tvgId: id, edited: true });
        await save(p);
        guideAsked.get(p.id)?.clear();
        const gg = guides.get(p.id); if (gg) gg.keys = new WeakMap();
        closeSheet();
        ensureGuide([c]);
        toast(`Guide set to ${li.querySelector('.row-name').firstChild.textContent}`);
        rerender();
      };
    },
  });
}

function highlightStars() {
  document.querySelectorAll('#list .row').forEach(r => {
    const c = state.filtered[+r.dataset.i];
    r.querySelector('.star')?.classList.toggle('on', !!c && favUrls.has(c.url));
  });
}

function channelFields(c, p) {
  return [
    { name: 'name', label: 'Name', value: c.name, required: true },
    { name: 'group', label: 'Group', value: c.group, list: groupsOf(p.channels).map(g => g.name).filter(Boolean) },
    { name: 'url', label: 'Stream URL', value: c.url, type: 'url', required: true, placeholder: 'https://…/stream.m3u8' },
    { name: 'logo', label: 'Logo URL (optional)', value: c.logo, type: 'url' },
    { name: 'tvgId', label: 'TV guide ID (optional)', value: c.tvgId },
  ];
}

async function editChannel(c, p) {
  const v = await formSheet({ title: 'Edit channel', fields: channelFields(c, p), submit: 'Save' });
  if (!v) return;
  const copies = copiesOf(p, c);
  Object.assign(c, v, { edited: true });
  for (const x of copies) Object.assign(x, { name: v.name, group: v.group, logo: v.logo, tvgId: v.tvgId, edited: true });
  await save(p);
  rerender();
  toast('Saved');
}

async function addChannel(p) {
  const v = await formSheet({ title: 'Add channel', fields: channelFields(makeChannel({ group: state.group || '' }), p), submit: 'Add' });
  if (!v) return;
  p.channels.push(makeChannel(v));
  await save(p);
  rerender();
  toast('Channel added');
}

async function copyText(text, message) {
  try {
    await navigator.clipboard.writeText(text);
    toast(message);
  } catch {
    window.prompt('Copy this:', text);
  }
}

function shareOrDownload(file, title) {
  if (isNative) return download(file);
  if (navigator.canShare?.({ files: [file] })) navigator.share({ files: [file], title }).catch(() => {});
  else download(file);
}

function download(file) {
  // The app's web view can't download files; hand them to the iOS share sheet instead.
  if (isNative) return file.text().then(text => nativeShare(file.name, text)).catch(e => toast(e.message || 'Couldn’t share the file'));
  const a = document.createElement('a');
  a.href = URL.createObjectURL(file);
  a.download = file.name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 10000);
}

// ---------- movies & series ----------
function detailHtml(m, info = {}) {
  const i = withTmdb(info.info || {}, info.tmdb);
  const pr = state.progress[m.url];
  const resumable = pr && !pr.done && pr.t > 30;
  const meta = [String(i.releasedate || i.release_date || '').slice(0, 4) || m.year, i.duration, i.genre || m.genre].filter(Boolean);
  const art = i.backdrop_path?.[0] || i.movie_image || i.cover_big || m.logo;
  return `<div class="detail film" data-id="${esc(m.id)}">
    <div class="film-hero" style="--hue:${hueOf(m.name)}">${art ? `<img class="film-bg" src="${esc(art)}" alt="" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}</div>
    <div class="detail-top">
      <span class="detail-poster" data-initials="${initials(m.name)}">${(i.movie_image || i.cover_big || m.logo) ? `<img src="${esc(i.movie_image || i.cover_big || m.logo)}" alt="" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}</span>
      <div class="detail-head">
        <div class="detail-title">${esc(displayTitle(m.name))}</div>
        ${i.tmdb?.tagline ? `<div class="muted detail-tagline">${esc(i.tmdb.tagline)}</div>` : ''}
        <div class="film-meta">${meta.map(x => `<span class="pill">${esc(x)}</span>`).join('')}${i.tmdb?.vote ? `<span class="pill rating">★ ${i.tmdb.vote.toFixed(1)} · ${i.tmdb.votes >= 1000 ? `${(i.tmdb.votes / 1000).toFixed(1)}k` : i.tmdb.votes} votes</span>` : m.rating ? `<span class="pill rating">★ ${m.rating.toFixed(1)}</span>` : ''}</div>
        ${pr && !pr.done && pr.t > 30 ? `<span class="bar film-bar"><i style="width:${Math.min(100, (pr.t / pr.d) * 100).toFixed(1)}%"></i></span><span class="muted">${fmtDur(pr.d - pr.t)} left</span>` : ''}
      </div>
    </div>
    ${info.versions?.length > 1 ? (() => { const b = qualityOf(info.versions[0]), cur = qualityOf(m); return `<button class="vbanner${info.versions[0].url !== m.url && b.score > cur.score ? ' up' : ''}" data-versions><span class="vq">${esc(b.res)}</span><span><b>${info.versions.length} versions</b>${info.versions[0].url !== m.url && b.score > cur.score ? ` · ${esc(b.tags.join(' · '))} available` : ` · ${esc(info.versions.map(v => qualityOf(v).res).filter((r, k, a) => a.indexOf(r) === k).join(', '))}`}</span>${icon('next')}</button>`; })() : ''}
    ${i.plot || i.description || m.plot ? `<p class="detail-plot">${esc(i.plot || i.description || m.plot)}</p>` : ''}
    ${i.cast || i.actors ? `<p class="muted">Cast: ${esc(i.cast || i.actors)}</p>` : ''}
    ${i.director ? `<p class="muted">Director: ${esc(i.director)}</p>` : ''}
    <div class="detail-actions">
      <button class="btn primary" data-play>${icon('play')} ${resumable ? `Resume ${fmtDur(pr.t)}` : 'Play'}</button>
      ${resumable ? '<button class="btn" data-restart>Start over</button>' : ''}
      <button class="btn" data-fav>${favUrls.has(m.url) ? '★ Saved' : '☆ Save'}</button>
      ${canSync() && !m.offline ? '<button class="btn" data-dl>Download</button>' : ''}
      ${i.youtube_trailer ? '<button class="btn" data-trailer>Trailer</button>' : ''}
      <button class="btn" data-more>More</button>
    </div>
  </div>`;
}

// ---------- Versions: every copy of a film (4K HDR, 1080p, per-platform…), best first ----------
// Quality comes from the provider's own labels in the title and group ("4K-NF", "UHD", "HDR", "Dolby"…).
function qualityOf(it) {
  const t = `${it.name || ''} ${it.group || ''}`;
  const res = /\b(4k|uhd|2160p?)\b/i.test(t) ? '4K' : /\b(1080p?|fhd|full ?hd)\b/i.test(t) ? '1080p' : /\b720p?\b/i.test(t) ? '720p' : /\b(sd|480p?)\b/i.test(t) ? 'SD' : 'HD';
  const hdr = /hdr|dolby ?vision|\bdv\b/i.test(t), hevc = /hevc|h\.?265|x265/i.test(t) || res === '4K';
  const atmos = /atmos|dolby(?! ?vision)|ddp|eac3|5\.1|7\.1/i.test(t);
  const lang = isEnglishGroup(it.group || '') ? '' : (String(it.group || '').match(/^([A-Za-z]{2,3})\b/)?.[1] || 'Other language').toUpperCase();
  // Safari / the iPhone app play 4K HEVC untouched; Chrome would convert it, so there an untouched copy wins.
  const plain = IS_SAFARI || isNative || !hevc;
  const score = { '4K': 40, '1080p': 30, HD: 25, '720p': 20, SD: 10 }[res] + (hdr ? 4 : 0) + (atmos ? 1 : 0) - (plain ? 0 : 35) - (lang ? 60 : 0);
  const tags = [res, hdr && 'HDR', atmos && 'Dolby audio', lang].filter(Boolean);
  return { res, hdr, hevc, atmos, lang, score, tags, converts: !plain };
}
// One entry per title in search results: the copies ("EN - Inception", "4K-EN - Inception", "NF - Inception 2010")
// collapse into the best English one, which remembers how many there are. Play then offers the versions.
function oneOfEach(items) {
  const by = new Map();
  for (const it of items) {
    if (it.kind !== 'movie' && it.kind !== 'series') { by.set(Symbol(), it); continue; }
    const y = it.year || vodMeta(it.name).year || '';
    const k = `${it.kind}|${displayTitle(it.name).toLowerCase().replace(/&/g, 'and').replace(/[^a-z0-9]+/g, '')}`;
    const prev = by.get(k);
    if (prev && prev.year && y && Math.abs(Number(prev.year) - Number(y)) > 1) { by.set(`${k}|${y}`, it); continue; }   // a remake
    if (!prev) { by.set(k, { ...it, copies: 1 }); continue; }
    const best = qualityOf(it).score > qualityOf(prev).score ? { ...it } : prev;
    best.copies = prev.copies + 1;
    best.year ||= prev.year || y || undefined;   // keep a year from whichever copy has one
    by.set(k, best);
  }
  return [...by.values()];
}
// Posters through the server's thumbnail cache (small JPEGs at the size shown): smooth scrolling, instant repeats.
function thumb(url, w = 300) {
  if (!url || !/^https?:/i.test(url) || !canSync()) return url;
  const base = serverApi('img');
  return `${base}${base.includes('?') ? '&' : '?'}w=${w}&u=${encodeURIComponent(url)}`;
}
const versionsCache = new Map();
async function versionsOf(m) {
  const p = playlistOf(m);
  if (!p || p.kind !== 'xtream' || !canSync() || m.kind !== 'movie') return [m];
  if (!versionsCache.has(m.url)) versionsCache.set(m.url, (async () => {
    try {
      const d = await vodApi(p, 'movies', { versions: m.name, year: m.year || '' });
      const entry = vodCache.get(vodKey(p, 'movies'));
      const list = toItems(p, 'movies', entry?.cats || [], d.items || []);
      if (!list.some(v => v.url === m.url)) list.unshift(m);
      // English only (like the rest of Movies) unless languages are set to "All"; the one you opened always stays.
      const en = state.settings.vodLanguage !== 'all' ? list.filter(v => v.url === m.url || isEnglishGroup(v.group || '')) : list;
      return en.map(v => ({ v, q: qualityOf(v) })).sort((a, b) => b.q.score - a.q.score).map(x => x.v);
    } catch (e) { console.warn('versions:', e?.message || e); versionsCache.delete(m.url); return [m]; }
  })());
  return versionsCache.get(m.url);
}
// Play: with more than one copy, choose (best first, marked); with one, just play.
async function playBestVersion(m, { resume = true } = {}) {
  const all = await versionsOf(m);
  // Copies with the same quality are the same film in different groups: show one row each (the first is the
  // best-ranked), noting how many; the other copies stay available as backups if that one fails.
  const seenQ = new Map(), vs = [];
  for (const v of all) {
    const k = qualityOf(v).tags.join('|');
    if (seenQ.has(k)) { seenQ.get(k).n++; const kept = seenQ.get(k).v; kept.alts = [...(kept.alts || []), v.url]; continue; }
    const kept = { ...v, alts: undefined };
    seenQ.set(k, { v: kept, n: 1 }); vs.push(kept);
  }
  if (vs.length < 2) return playChannel([vs[0] || m], 0);
  const q = vs.map(qualityOf), best = 0, copies = vs.map(v => seenQ.get(qualityOf(v).tags.join('|')).n);
  sheet(`<div class="sheet-title">Choose quality</div><p class="muted sheet-intro">${esc(niceName(m))}</p>
    <div class="vlist">${vs.map((v, i) => `<button class="vrow${i === best ? ' best' : ''}" data-v="${i}">
      <span class="vq">${esc(q[i].res)}</span>
      <span class="vtext"><b>${q[i].tags.map(esc).join(' · ')}${i === best ? ' <em>Recommended</em>' : ''}</b><span class="muted">${copies[i] > 1 ? `${copies[i]} copies` : esc(groupLabel(v.group || ''))}${q[i].converts ? ' · converted in this browser' : ''}${state.progress[v.url]?.t > 30 && !state.progress[v.url]?.done ? ` · ${fmtDur(state.progress[v.url].d - state.progress[v.url].t)} left` : ''}</span></span>
      ${icon('play')}</button>`).join('')}</div>`, {
    onMount: el => {
      el.querySelector('.vlist').onclick = e => { const b = e.target.closest('[data-v]'); if (!b) return; closeSheet(); playChannel([vs[+b.dataset.v]], 0); };
    },
  });
  void resume;
}

async function movieSheet(m) {
  let info = {};
  const open = (keepScroll = false) => sheet(detailHtml(m, info), {
    keepScroll,
    onMount: el => {
      el.querySelector('[data-play]').onclick = () => { const pr = state.progress[m.url]; if (pr && !pr.done && pr.t > 30) { closeSheet(); return playChannel([m], 0); } closeSheet(); playBestVersion(m); };
      el.querySelector('[data-versions]')?.addEventListener('click', () => { closeSheet(); playBestVersion(m); });
      el.querySelector('[data-restart]')?.addEventListener('click', () => { delete state.progress[m.url]; closeSheet(); playChannel([m], 0); });
      el.querySelector('[data-fav]').onclick = async () => { await toggleFavorite(m, null); open(true); };
      el.querySelector('[data-dl]')?.addEventListener('click', () => downloadMenu(m));
      el.querySelector('[data-trailer]')?.addEventListener('click', () => { const k = withTmdb(info.info || {}, info.tmdb).youtube_trailer; if (k) window.open(`https://www.youtube.com/watch?v=${encodeURIComponent(k)}`, '_blank'); });
      el.querySelector('[data-more]').onclick = () => menu(m.name, [
        info.info?.youtube_trailer && { label: 'Watch trailer', fn: () => window.open(`https://www.youtube.com/watch?v=${encodeURIComponent(info.info.youtube_trailer)}`, '_blank') },
        ...externalItems(m),
      ]);
    },
  });
  open();
  const still = () => $('#sheet .detail')?.dataset.id === m.id;
  tmdbInfo(m).then(t => { if (t) { info = { ...info, tmdb: t }; if (still()) open(true); } });
  versionsOf(m).then(vs => { if (vs.length > 1) { info = { ...info, versions: vs }; if (still()) open(true); } });
  const x = xtOf(playlistOf(m));
  if (!x || !m.sid) return;
  try {
    const pi = await x.vodInfo(m.sid) || {};
    info = { ...pi, tmdb: info.tmdb, versions: info.versions };
    if (still()) open(true);
  } catch {}
}

async function seriesSheet(s) {
  const p = playlistOf(s);
  const x = xtOf(p);
  sheet(`<div class="detail" data-id="${esc(s.id)}"><div class="detail-title">${esc(s.name)}</div><p class="muted">Loading episodes…</p></div>`);
  let info;
  try {
    info = s.seasons ? { episodes: s.seasons, info: {} } : await x.seriesInfo(s.sid);
  } catch (err) {
    if ($('#sheet .detail')?.dataset.id === s.id) $('#sheet .detail .muted').textContent = err.message;
    return;
  }
  if ($('#sheet .detail')?.dataset.id !== s.id) return;
  const tm = await Promise.race([tmdbInfo(s), new Promise(r => setTimeout(r, 2500))]).catch(() => null);
  if ($('#sheet .detail')?.dataset.id !== s.id) return;
  if (tm) info = { ...info, info: withTmdb(info.info || {}, tm) };
  const eps = info?.episodes || {};
  const seasons = Object.keys(eps).sort((a, b) => a - b);
  const episodesOf = season => (eps[season] || []).map(ep => s.seasons ? { ...ep, src: p.id, duration: '', group: `${s.name} · Season ${season}` } : ({
    id: `e${ep.id}`, kind: 'episode', src: p.id, season,
    name: ep.title || `${s.name} S${season}E${ep.episode_num}`,
    url: x.episodeUrl(ep.id, ep.container_extension),
    logo: ep.info?.movie_image || s.logo,
    group: `${s.name} · Season ${season}`,
    num: ep.episode_num, duration: ep.info?.duration || '', plot: ep.info?.plot || '',
  }));
  // Start on the season of the most recently watched episode.
  const urls = new Map(seasons.flatMap(se => episodesOf(se).map(e => [e.url, se])));
  const last = Object.values(state.progress).filter(e => urls.has(e.item.url)).sort((a, b) => b.at - a.at)[0];
  let season = last ? urls.get(last.item.url) : seasons[0];
  const i = info.info || {};

  // "Continue": the most recently watched unfinished episode, else the first unwatched one.
  const allEps = seasons.flatMap(se => episodesOf(se));
  const continueEp = (last && !state.progress[last.item.url]?.done ? allEps.find(e => e.url === last.item.url) : null) || allEps.find(e => !state.progress[e.url]?.done) || allEps[0];
  const render = (keepScroll = false) => {
    const list = episodesOf(season);
    const art = i.backdrop_path?.[0] || i.cover || s.logo;
    const watched = allEps.filter(e => state.progress[e.url]?.done).length;
    sheet(`<div class="detail film series" data-id="${esc(s.id)}">
      <div class="film-hero" style="--hue:${hueOf(s.name)}">${art ? `<img class="film-bg" src="${esc(art)}" alt="" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}</div>
      <div class="detail-top">
        <span class="detail-poster" data-initials="${initials(s.name)}">${(i.cover || s.logo) ? `<img src="${esc(i.cover || s.logo)}" alt="" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}</span>
        <div class="detail-head">
          <div class="detail-title">${esc(displayTitle(s.name))}</div>
          <div class="film-meta">${[s.year, i.genre || s.genre, plural(seasons.length, 'season'), plural(allEps.length, 'episode')].filter(Boolean).map(m => `<span class="pill">${esc(m)}</span>`).join('')}${s.rating ? `<span class="pill rating">★ ${s.rating.toFixed(1)}</span>` : ''}</div>
          ${watched ? `<span class="muted">${watched} of ${allEps.length} watched</span>` : ''}
        </div>
      </div>
      ${i.plot || s.plot ? `<p class="detail-plot clamp3" data-plot>${esc(i.plot || s.plot)}</p>` : ''}
      ${continueEp ? `<div class="detail-actions"><button class="btn primary" data-continue>${icon('play')} ${state.progress[continueEp.url]?.t > 30 && !state.progress[continueEp.url]?.done ? 'Continue' : 'Play'} S${esc(continueEp.season ?? seasons.find(se => episodesOf(se).includes(continueEp)))} E${esc(continueEp.num)}</button><button class="btn" data-sfav>${favUrls.has(s.url || s.id) ? '★ Saved' : '☆ Save'}</button></div>` : ''}
      ${seasons.length > 1 ? `<div class="chips season-chips">${seasons.map(se => `<button class="chip ${se === season ? 'active' : ''}" data-season="${esc(se)}">Season ${esc(se)}</button>`).join('')}</div>` : ''}
      ${list.length ? `<ul class="eps cards-eps">${list.map((e, k) => {
        const pr = state.progress[e.url];
        const still = e.logo && e.logo !== s.logo && e.logo !== i.cover ? e.logo : '';
        return `<li class="epc${pr?.done ? ' done' : ''}" data-e="${k}">
          <span class="ep-still" data-initials="${esc(e.num)}">${still ? `<img src="${esc(still)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}${pr && !pr.done && pr.t > 30 ? `<span class="bar"><i style="width:${Math.min(100, (pr.t / pr.d) * 100).toFixed(1)}%"></i></span>` : ''}<span class="ep-play">${pr?.done ? icon('check') : icon('play')}</span></span>
          <span class="row-text"><span class="muted ep-label">Episode ${esc(e.num)}${e.duration ? ` · ${esc(e.duration)}` : ''}${pr?.done ? ' · Watched' : pr?.t > 30 ? ` · ${fmtDur(pr.d - pr.t)} left` : ''}</span><span class="row-name">${esc(epTitle(e.name) || `Episode ${e.num}`)}</span>${e.plot ? `<span class="muted clamp">${esc(e.plot)}</span>` : ''}</span>
        </li>`;
      }).join('')}</ul>` : '<p class="muted">No episodes listed.</p>'}
    </div>`, {
      keepScroll,
      onMount: el => {
        el.onclick = e => {
          const sc = e.target.closest('[data-season]');
          if (sc) { season = sc.dataset.season; return render(true); }
          if (e.target.closest('[data-plot]')) return e.target.closest('[data-plot]').classList.toggle('open');
          if (e.target.closest('[data-continue]')) { const se = seasons.find(x => episodesOf(x).some(y => y.url === continueEp.url)) || season; const l = episodesOf(se); closeSheet(); return playChannel(l, l.findIndex(y => y.url === continueEp.url)); }
          if (e.target.closest('[data-sfav]')) { toggleFavorite({ ...s, url: s.url || s.id }, null).then(() => render(true)); return; }
          const ep = e.target.closest('[data-e]');
          if (ep) { closeSheet(); playChannel(list, +ep.dataset.e); }
        };
      },
    });
  };
  render();
}

// ---------- TV guide ----------
function epgUrls(p) {
  if (p.epgUrl) return p.epgUrl.split(/[\s,]+/).filter(Boolean);
  const header = p.header?.['url-tvg'] || p.header?.['x-tvg-url'] || '';
  if (header) return header.split(/[\s,]+/).filter(Boolean);
  const x = xtOf(p);
  return x ? [x.epgUrl()] : [];
}

async function loadGuide(pid) {
  if (guides.has(pid)) return guides.get(pid);
  try {
    const data = await db.get(`epg:${pid}`);
    if (data) guides.set(pid, new Guide(data));
  } catch {}
  return guides.get(pid);
}

// Channels worth parsing guide data for: the ones the user can actually see. Hidden groups never
// count; on huge playlists only the pinned Home groups and favourites do, so the 19k-channel
// case stays cheap. Programmes are matched by id and name, so the set is what bounds the work.
const GUIDE_SCOPE_MAX = 6000;
function guideChannels(p) {
  const hidden = new Set(p.hiddenGroups || []);
  const live = (m3uKinds(p)?.live || p.channels).filter(c => !hidden.has(c.group));
  if (live.length <= GUIDE_SCOPE_MAX) return live;
  const pinned = new Set(p.homeGroups || []);
  return live.filter(c => pinned.has(c.group) || favUrls.has(c.url));
}

// With the VPS server, it downloads and filters the guide (server/guide.js) and the phone gets a small
// JSON. Otherwise parse in a Web Worker so the main thread never stalls (inline where workers are missing).
async function parseEpg(urls, opts) {
  if (canSync()) {
    const res = await fetch(serverApi('guide'), { method: 'POST', cache: 'no-store', body: JSON.stringify({ urls, ids: [...opts.wanted.ids], names: [...opts.wanted.names] }) });
    if (!res.ok) throw new Error((await res.text()) || `The server replied ${res.status}`);
    return res.json();
  }
  return parseEpgLocally(urls, opts);
}
function parseEpgLocally(urls, opts) {
  if (typeof Worker === 'undefined') return loadEpg(urls, opts);
  return new Promise((resolve, reject) => {
    let w;
    try { w = new Worker(new URL('./epg-worker.js', import.meta.url), { type: 'module' }); }
    catch { return loadEpg(urls, opts).then(resolve, reject); }
    w.onmessage = e => { w.terminate(); e.data.ok ? resolve(e.data.data) : reject(new Error(e.data.error)); };
    w.onerror = () => { w.terminate(); loadEpg(urls, opts).then(resolve, reject); };
    w.postMessage({ urls, from: opts.from, to: opts.to, wanted: { ids: [...opts.wanted.ids], names: [...opts.wanted.names] }, proxy: net.proxy });
  });
}

// ---- on-demand guide (VPS server) ----
// The server keeps the whole provider guide indexed; the app asks for the channels it is showing
// (the rows on screen, the guide grid) in small batches and merges them into one Guide per playlist.
const GUIDE_FRESH = 3 * HOUR;
const guideAsked = new Map();   // playlist id -> Map of channel key -> when it was last requested
const guideQueue = new Map();   // playlist id -> channels waiting for the next batch
let guideFlush = null;
const chanKey = c => (c.tvgId || '').toLowerCase() || normName(c.tvgName || c.name);

function ensureGuide(channels) {
  if (!canSync()) return;
  for (const c of channels) {
    if (!isLiveChannel(c)) continue;
    const p = playlistOf(c);
    if (!p || p.virtual || !epgUrls(p).length) continue;
    const g = guides.get(p.id);
    if (g && Date.now() - g.at < GUIDE_FRESH && g.key(c)) continue;
    const k = chanKey(c);
    let asked = guideAsked.get(p.id);
    if (!asked) guideAsked.set(p.id, asked = new Map());
    if (!k || asked.get(k) > Date.now() - GUIDE_FRESH) continue;   // re-asked once the data is a few hours old
    asked.set(k, Date.now());
    let q = guideQueue.get(p.id);
    if (!q) guideQueue.set(p.id, q = []);
    q.push(c);
  }
  if (guideQueue.size && !guideFlush) guideFlush = new Promise(r => setTimeout(r, 120)).then(flushGuide).finally(() => { guideFlush = null; if (guideQueue.size) ensureGuide([]); });
  return guideFlush;
}

async function flushGuide() {
  for (const [pid, list] of [...guideQueue]) {
    guideQueue.delete(pid);
    const p = byId(pid);
    if (!p) continue;
    for (let i = 0; i < list.length; i += 600) {
      const w = wantedFor(list.slice(i, i + 600));
      try {
        const res = await fetch(serverApi('guide'), { method: 'POST', cache: 'no-store', body: JSON.stringify({ urls: epgUrls(p), ids: [...w.ids], names: [...w.names] }) });
        if (!res.ok) throw new Error(await res.text());
        mergeGuide(p, await res.json());
      } catch {
        list.slice(i).forEach(c => guideAsked.get(pid)?.delete(chanKey(c)));   // try again next time they're shown
        refreshAfterGuide(pid);   // never leave the guide screen on "Loading…"
        break;
      }
    }
  }
}

const persistGuide = debounce(pid => { const g = guides.get(pid); if (g) db.set(`epg:${pid}`, { programmes: g.programmes, names: g.names, at: g.at }).catch(() => {}); }, 2000);
const refreshAfterGuide = debounce(pid => {
  if (location.hash === `#/guide/${pid}`) {
    // Only rebuild the grid while it's still loading, or before you've started using it.
    const el = $('#guide');
    if (!el || (el.scrollTop < 5 && !guideState?.group)) { const left = el?.scrollLeft; showGuide(byId(pid)); if (left && $('#guide')) $('#guide').scrollLeft = left; }
    return;
  }
  if (state.current && !$('#guide')) updateRowsInPlace();
  const card = !state.current && $('.resume');
  if (card && resumeChannel) card.outerHTML = resumeCardHtml(resumeChannel);   // fill in what's on, in place
}, 250);
function mergeGuide(p, d) {
  let g = guides.get(p.id);
  if (!g) guides.set(p.id, g = new Guide({ at: d.at }));
  Object.assign(g.programmes, d.programmes);
  Object.assign(g.names, d.names);
  g.at = d.at;
  g.keys = new WeakMap();
  persistGuide(p.id);
  refreshAfterGuide(p.id);
}

const guideUpdates = new Map();
function updateGuide(p, { silent = false } = {}) {
  if (canSync() && epgUrls(p).length) {   // the server already has the latest guide: just re-ask for what's on screen
    guideAsked.delete(p.id);
    const g = guides.get(p.id);
    if (g) g.at = 0;
    if (!silent) toast('Updating TV guide…', { busy: true });
    return Promise.resolve(ensureGuide(location.hash === `#/guide/${p.id}` ? guideScope(p) : state.filtered))
      .then(() => { if (!silent) toast(guides.get(p.id)?.channelCount ? 'TV guide updated' : 'No guide data for these channels'); });
  }
  if (guideUpdates.has(p.id)) return guideUpdates.get(p.id);
  const urls = epgUrls(p);
  if (!urls.length) {
    if (!silent) setEpgUrl(p);
    return Promise.resolve();
  }
  if (!silent) toast('Updating TV guide…', { busy: true });
  const maxArchive = Math.min(3, p.channels.reduce((m, c) => Math.max(m, archiveDays(c)), 1));
  const scope = guideChannels(p);
  const job = parseEpg(urls, { from: Date.now() - maxArchive * DAY, to: Date.now() + 2 * DAY, wanted: wantedFor(scope) })
    .then(async data => {
      data.at = Date.now();
      data.scope = scope.length;
      await db.set(`epg:${p.id}`, data);
      const g = new Guide(data);
      guides.set(p.id, g);
      p.epgFailedAt = 0;
      if (!silent || g.channelCount) toast(g.channelCount ? `TV guide updated for ${plural(g.channelCount, 'channel')}` : 'The guide has no programmes for these channels');
      if (state.current?.id === p.id || state.current?.virtual) rerender();
    })
    .catch(err => {
      p.epgFailedAt = Date.now();
      if (!silent) toast(`TV guide: ${err.message}`);
    })
    .finally(() => guideUpdates.delete(p.id));
  guideUpdates.set(p.id, job);
  return job;
}

// Automatic guide loads download the whole XMLTV file (20MB+ for big providers), which competes with
// the video for bandwidth on a phone. Above this size the guide only loads when asked, from the guide screen.
const AUTO_GUIDE_MAX = 8000;
function maybeUpdateGuide(p) {
  if (canSync()) return;   // on-demand: rows fetch their own guide data as they appear
  if ((p.channels?.length || 0) > AUTO_GUIDE_MAX) return;
  const g = guides.get(p.id);
  const stale = !g || Date.now() - g.at > 12 * HOUR;
  if (stale && epgUrls(p).length && !(Date.now() - (p.epgFailedAt || 0) < HOUR)) updateGuide(p, { silent: true });
}

async function setEpgUrl(p) {
  const v = await formSheet({
    title: 'TV guide (EPG) source',
    fields: [{ name: 'url', label: 'XMLTV link(s), .xml or .xml.gz', value: p.epgUrl || epgUrls(p).join(', '), placeholder: 'https://example.com/guide.xml.gz', type: 'text' }],
    submit: 'Save & load',
  });
  if (!v) return;
  p.epgUrl = v.url;
  await save(p);
  if (v.url || epgUrls(p).length) updateGuide(p);
}

function catchupUrlFor(c, prog) {
  const p = playlistOf(c);
  const x = xtOf(p);
  if (c.xt && x) return x.timeshiftUrl(c.xt.id, new Date(prog[0]), Math.ceil((prog[1] - prog[0]) / MINUTE), p.serverInfo?.timezone);
  return m3uCatchupUrl(c, prog[0], prog[1]);
}

function canCatchUp(c, prog) {
  const days = archiveDays(c);
  return days > 0 && prog[0] < Date.now() && prog[0] > Date.now() - days * DAY;
}

function playCatchup(c, prog) {
  playChannel([{ id: uid(), kind: 'catchup', name: `${prog[2]}`, url: catchupUrlFor(c, prog), logo: c.logo, group: `${c.name} · ${fmtDay(prog[0])} ${fmtRange(prog)}`, src: srcOf(c) }], 0);
}

// Programme page: picture (when the guide has one), channel, date and time, series/episode and NEW
// chips, title, summary, then Watch / catch-up / Remind.
function programmeSheet(c, prog) {
  const now = Date.now();
  const live = prog[0] <= now && prog[1] > now;
  const past = prog[1] <= now;
  const x = prog[4] || {};
  const pct = live ? (((now - prog[0]) / (prog[1] - prog[0])) * 100).toFixed(1) : 0;
  sheet(`<div class="detail prog">
      <div class="prog-hero${x.img ? '' : ' no-img'}" style="--hue:${hueOf(c.name)}">
        ${x.img ? `<img src="${esc(x.img)}" alt="" referrerpolicy="no-referrer" loading="lazy" onerror="this.parentNode?.classList.add('no-img'); this.remove()">` : ''}
        <div class="prog-hero-top">${logoHtml(c)}<span class="prog-ch">${esc(c.name)}</span>${live ? '<span class="tag live">ON NOW</span>' : ''}</div>
        <div class="prog-hero-bottom">
          <div class="prog-when"><b>${esc(fmtDay(prog[0]))}</b><span>${fmtRange(prog)}</span></div>
          <div class="detail-title">${esc(prog[2])}</div>
          ${live ? `<span class="bar prog-bar"><i style="width:${pct}%"></i></span>` : ''}
        </div>
      </div>
      ${x.new || x.s ? `<div class="prog-chips">${x.new ? '<span class="pill new">NEW</span>' : ''}${x.s ? `<span class="pill">Series ${x.s} · Episode ${x.e}</span>` : ''}</div>` : ''}
      ${prog[3] ? `<div class="section-title">Summary</div><p class="detail-plot">${esc(prog[3])}</p>` : '<p class="muted">No description in the guide.</p>'}
      <div class="detail-actions">
        ${live ? `<button class="btn primary" data-live>${icon('play')} Watch live</button>` : ''}
        ${(live || past) && canCatchUp(c, prog) ? `<button class="btn ${live ? '' : 'primary'}" data-cu>${live ? 'Watch from start' : `${icon('play')} Watch (catch-up)`}</button>` : ''}
        ${!live && !past ? `<button class="btn ${hasReminder(c, prog) ? '' : 'primary'}" data-remind>${hasReminder(c, prog) ? 'Remove reminder' : `${icon('clock')} Remind me`}</button>` : ''}
        ${past && !canCatchUp(c, prog) ? '<p class="muted">This channel doesn’t offer catch-up.</p>' : ''}
      </div></div>`, {
    onMount: el => {
      el.querySelector('[data-live]')?.addEventListener('click', () => { closeSheet(); playChannel([c], 0); });
      el.querySelector('[data-cu]')?.addEventListener('click', () => { closeSheet(); playCatchup(c, prog); });
      el.querySelector('[data-remind]')?.addEventListener('click', async () => { await toggleReminder(c, prog); programmeSheet(c, prog); });
    },
  });
}

// ---------- reminders ----------
// Fire a few minutes before a programme starts: a phone notification when the installed app has
// permission, and always a tap-to-watch toast if the app is open.
const REMIND_BEFORE = 3 * MINUTE;
const reminderKey = (c, prog) => `${c.url}|${prog[0]}`;
const hasReminder = (c, prog) => (state.reminders || []).some(r => r.key === reminderKey(c, prog));
let reminderTimer = 0;
async function toggleReminder(c, prog) {
  state.reminders ||= [];
  const key = reminderKey(c, prog);
  if (hasReminder(c, prog)) {
    state.reminders = state.reminders.filter(r => r.key !== key);
    toast('Reminder removed');
  } else {
    if ('Notification' in window && Notification.permission === 'default') { try { await Notification.requestPermission(); } catch {} }
    state.reminders.push({ key, at: prog[0], title: prog[2], channel: cloneChannel(c) });
    toast(`Reminder set for ${fmtTime(prog[0])}`);
  }
  await db.set('reminders', state.reminders);
  scheduleReminders();
}
function scheduleReminders() {
  clearTimeout(reminderTimer);
  const now = Date.now();
  state.reminders = (state.reminders || []).filter(r => r.at > now - 10 * MINUTE);
  const next = state.reminders.filter(r => !r.fired).sort((a, b) => a.at - b.at)[0];
  if (!next) return;
  reminderTimer = setTimeout(() => {
    next.fired = true;
    const ch = next.channel;
    toast(`${next.title} starts at ${fmtTime(next.at)} on ${ch.name}`, { action: 'Watch', fn: () => playChannel([ch], 0) });
    try {
      if (Notification.permission === 'granted') {
        navigator.serviceWorker?.ready.then(reg => reg.showNotification(`${next.title} starts soon`, { body: `${fmtTime(next.at)} on ${ch.name}`, icon: 'icons/icon-192.png', tag: next.key }))
          .catch(() => new Notification(`${next.title} starts soon`, { body: `${fmtTime(next.at)} on ${ch.name}` }));
      }
    } catch {}
    db.set('reminders', state.reminders).catch(() => {});
    scheduleReminders();
  }, Math.max(0, next.at - REMIND_BEFORE - now));
}

function scheduleSheet(c) {
  const g = guideFor(c);
  const list = g?.list(c) || [];
  const now = Date.now();
  let day = '';
  sheet(`<div class="sheet-title">${esc(c.name)}</div>
    ${archiveDays(c) ? `<p class="muted center">Tap a past programme to watch it (catch-up: ${plural(archiveDays(c), 'day')}).</p>` : ''}
    <ul class="sched">${list.map((p, k) => {
      const d = fmtDay(p[0]);
      const head = d !== day ? `<li class="sched-day">${esc((day = d))}</li>` : '';
      const live = p[0] <= now && p[1] > now;
      const cu = p[1] <= now && canCatchUp(c, p);
      return `${head}<li class="sched-item${live ? ' live' : ''}${p[1] <= now ? ' past' : ''}" data-k="${k}">
        <span class="sched-time">${fmtTime(p[0])}</span>
        <span class="row-text"><span class="row-name">${esc(p[2])}</span>${p[3] ? `<span class="muted clamp">${esc(p[3])}</span>` : ''}</span>
        ${live ? '<span class="tag live">LIVE</span>' : cu ? `<span class="tag">${icon('play')}</span>` : ''}
      </li>`;
    }).join('')}</ul>`, {
    onMount: el => {
      el.querySelector('.sched-item.live')?.scrollIntoView({ block: 'center' });
      el.onclick = e => { const it = e.target.closest('[data-k]'); if (it) programmeSheet(c, list[+it.dataset.k]); };
    },
  });
}

// Guide grid: channels down, time across.
const PX_PER_MIN = 4;
const CH_W = 132;
let guideState = null;
let guideDay = 0, guideTick = 0;   // 0 = today (from 2h ago), 1 = tomorrow, …
const startOfDay = t => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };

// Channels the guide grid covers: visible live channels, pinned Home groups first, capped so the grid stays fast.
const GUIDE_GRID_MAX = 2500;
function guideScope(p) {
  const live = (m3uKinds(p)?.live || p.channels).filter(c => isLiveChannel(c) && visibleGroup(p, c.group || ''));
  const pinned = new Set(p.homeGroups || []);
  return (pinned.size ? [...live.filter(c => pinned.has(c.group)), ...live.filter(c => !pinned.has(c.group))] : live).slice(0, GUIDE_GRID_MAX);
}

function showGuide(p) {
  state.current = p;
  state.tab = 'live';
  setTopbar({ title: `${playlistTitle(p)} · Guide`, back: `#/p/${p.id}`, actions: [
    { icon: 'refresh', label: 'Update guide', fn: () => updateGuide(p) },
  ] });
  const scope = guideScope(p);
  const pending = ensureGuide(scope);
  const g = guides.get(p.id);
  const channels = g ? scope.filter(c => g.list(c).length) : [];
  if (!channels.length && pending) {
    view.innerHTML = `<div class="empty">${icon('guide', 'big')}<p><b>Loading TV guide…</b></p><p class="muted">Getting what’s on for your channels.</p></div>`;
    return;
  }
  if (!channels.length) {
    view.innerHTML = `<div class="empty">${icon('guide', 'big')}<p><b>No TV guide yet</b></p>
      <p class="muted">${epgUrls(p).length ? 'Load the guide from your provider.' : 'This playlist doesn’t include a guide link. Add an XMLTV (EPG) link.'}</p>
      <div class="form-actions center-actions">${epgUrls(p).length ? '<button class="btn primary" data-load>Load TV guide</button>' : ''}<button class="btn" data-set>Set guide link…</button></div></div>`;
    view.onclick = e => {
      if (e.target.closest('[data-load]')) updateGuide(p);
      if (e.target.closest('[data-set]')) setEpgUrl(p);
    };
    return;
  }
  const groups = groupsOf(channels);
  const t0 = guideDay ? startOfDay(Date.now() + guideDay * DAY) : Math.floor((Date.now() - 2 * HOUR) / (30 * MINUTE)) * 30 * MINUTE;
  const t1 = guideDay ? t0 + DAY : t0 + 30 * HOUR;
  const dayLabel = d => d === 0 ? 'Today' : d === 1 ? 'Tomorrow' : new Date(Date.now() + d * DAY).toLocaleDateString(undefined, { weekday: 'long' });
  const width = CH_W + ((t1 - t0) / MINUTE) * PX_PER_MIN;
  const x = t => ((t - t0) / MINUTE) * PX_PER_MIN;
  guideState = { p, g, all: channels, channels, t0, t1, rendered: 0, group: null, q: '' };
  let times = '';
  for (let t = t0; t < t1; t += 30 * MINUTE) times += `<span style="left:${x(t)}px">${new Date(t).getHours() === 0 && new Date(t).getMinutes() === 0 ? `${fmtDay(t)} ` : ''}${fmtTime(t)}</span>`;
  view.innerHTML = `
    <div class="gtools"><div class="chips gdays">${[0, 1, 2].map(d => `<button class="chip${d === guideDay ? ' active' : ''}" data-day="${d}">${dayLabel(d)}</button>`).join('')}</div>
      <label class="search gq">${icon('search')}<input id="gq" type="search" placeholder="Search programmes" autocomplete="off" autocorrect="off" spellcheck="false"></label></div>
    ${groups.length > 1 ? `<div class="chips" id="gchips"><button class="chip active" data-all>All<span>${channels.length}</span></button>${groups.map(gr => `<button class="chip" data-group="${esc(gr.name)}">${esc(gr.name || UNGROUPED)}<span>${gr.count}</span></button>`).join('')}</div>` : ''}
    <div class="guide" id="guide">
      <div class="guide-inner" style="width:${width}px">
        <div class="guide-times"><span class="guide-corner"><button class="btn small" data-jump>Now</button></span><div class="guide-ticks">${times}</div></div>
        <div id="grows"></div>
        <div class="guide-now" style="left:${CH_W + x(Date.now())}px"></div>
      </div>
    </div>`;
  const el = $('#guide');
  sizeGuide();
  renderGuideRows();
  const jump = () => { el.scrollLeft = Math.max(0, x(Date.now() - 30 * MINUTE)); };
  jump();
  el.onscroll = () => { if (el.scrollTop + el.clientHeight > el.scrollHeight - 400) renderGuideRows(); };
  // The "now" line and the live highlight move with the clock.
  clearInterval(guideTick);
  guideTick = setInterval(() => {
    const line = $('#guide .guide-now');
    if (!line) return clearInterval(guideTick);
    line.style.left = `${CH_W + x(Date.now())}px`;
    line.hidden = Date.now() < t0 || Date.now() > t1;
  }, 30_000);
  const applyGuideFilter = () => {
    const gs = guideState, t = gs.q.trim().toLowerCase();
    gs.channels = gs.all.filter(c => (gs.group === null || (c.group || '') === gs.group)
      && (!t || c.name.toLowerCase().includes(t) || gs.g.list(c).some(p => p[1] > t0 && p[0] < t1 && p[2].toLowerCase().includes(t))));
    gs.rendered = 0;
    $('#grows').innerHTML = gs.channels.length ? '' : '<p class="muted center gempty">Nothing on matching that.</p>';
    el.scrollTop = 0;
    renderGuideRows();
  };
  $('#gq').oninput = debounce(e => { guideState.q = e.target.value; applyGuideFilter(); }, 150);
  view.onclick = e => {
    const day = e.target.closest('[data-day]');
    if (day) { guideDay = +day.dataset.day; return showGuide(p); }
    if (e.target.closest('[data-jump]')) return jump();
    const chip = e.target.closest('#gchips .chip');
    if (chip) {
      guideState.group = chip.hasAttribute('data-all') ? null : chip.dataset.group;
      $('#gchips').querySelectorAll('.chip').forEach(c => c.classList.toggle('active', c === chip));
      return applyGuideFilter();
    }
    const ch = e.target.closest('[data-ch]');
    const row = e.target.closest('.grow');
    if (!row) return;
    const c = guideState.channels[+row.dataset.c];
    if (ch) return playChannel(guideState.channels, +row.dataset.c);
    const prog = e.target.closest('[data-p]');
    if (prog) programmeSheet(c, guideState.g.list(c)[+prog.dataset.p]);
  };
}

function renderGuideRows() {
  const gs = guideState;
  const rows = $('#grows');
  if (!gs || !rows || gs.rendered >= gs.channels.length) return;
  const end = Math.min(gs.rendered + 40, gs.channels.length);
  const now = Date.now();
  const x = t => ((t - gs.t0) / MINUTE) * PX_PER_MIN;
  let html = '';
  for (let i = gs.rendered; i < end; i++) {
    const c = gs.channels[i];
    const progs = gs.g.list(c).map((p, k) => {
      if (p[1] <= gs.t0 || p[0] >= gs.t1) return '';
      const left = Math.max(0, x(p[0])), right = Math.min(x(gs.t1), x(p[1]));
      const cls = p[0] <= now && p[1] > now ? ' live' : p[1] <= now ? ' past' : '';
      return `<button class="gprog${cls}" data-p="${k}" style="left:${left}px;width:${Math.max(2, right - left - 2)}px"><b>${esc(p[2])}</b><span>${fmtRange(p)}</span></button>`;
    }).join('');
    html += `<div class="grow" data-c="${i}"><button class="gch" data-ch>${logoHtml(c)}<span>${esc(c.name)}</span></button><div class="gline">${progs}</div></div>`;
  }
  rows.insertAdjacentHTML('beforeend', html);
  gs.rendered = end;
}

function sizeGuide() {
  const el = $('#guide');
  if (el) el.style.height = `${Math.max(240, innerHeight - el.getBoundingClientRect().top - 8)}px`;
}

// ---------- multi-view ----------
const multi = { tiles: [null, null, null, null], layout: 4, audio: 0, players: [] };

function showMulti() {
  stopPlaying();
  state.current = null;
  multiActive = true;
  setTopbar({ title: 'Multi-view', back: '#/', actions: [
    { icon: 'grid', label: 'Layout', fn: () => menu('Layout', [
      { label: '2 channels', fn: () => { multi.layout = 2; showMulti(); } },
      { label: '4 channels', fn: () => { multi.layout = 4; showMulti(); } },
    ]) },
  ] });
  stopMulti(true);
  view.innerHTML = `<div class="multi n${multi.layout}">${Array.from({ length: multi.layout }, (_, i) => `
    <div class="tile${i === multi.audio ? ' audio' : ''}" data-t="${i}">
      <video playsinline muted autoplay></video>
      <button class="tile-add">${icon('plus')}<span>Add channel</span></button>
      <div class="tile-bar"><span class="tile-name"></span><button class="icon-btn" data-tile-more aria-label="Tile options">${icon('more')}</button></div>
    </div>`).join('')}</div>
    <p class="muted center">Tap a tile to hear it. Others stay muted.</p>`;
  multi.players = [...view.querySelectorAll('.tile video')].map(v => {
    const pl = new Player(v);
    pl.tsToHls = player.tsToHls;
    pl.wrap = streamUrl;
    pl.preferNative = isNative;
    pl.transcode = transcodeUrl;
    return pl;
  });
  multi.tiles.slice(0, multi.layout).forEach((c, i) => startTile(i, c));
  view.onclick = e => {
    const tile = e.target.closest('.tile');
    if (!tile) return;
    const i = +tile.dataset.t;
    if (e.target.closest('[data-tile-more]')) {
      return menu(multi.tiles[i]?.name || 'Tile', [
        { label: 'Change channel…', fn: () => pickChannel(i) },
        { label: 'Full screen', fn: () => fullscreen(tile.querySelector('video')) },
        { label: 'Remove', danger: true, fn: () => { multi.tiles[i] = null; startTile(i, null); } },
      ]);
    }
    if (!multi.tiles[i]) return pickChannel(i);
    setTileAudio(i);
  };
}

function addToMulti(c) {
  const free = multi.tiles.slice(0, multi.layout).findIndex(t => !t);
  multi.tiles[free >= 0 ? free : 0] = c;
  if (multi.tiles.filter(Boolean).length === 1) multi.audio = free >= 0 ? free : 0;
  location.hash = '#/multi';
}

function fullscreen(v) {
  if (v.webkitEnterFullscreen) v.webkitEnterFullscreen();
  else v.requestFullscreen?.();
}

function setTileAudio(i) {
  multi.audio = i;
  view.querySelectorAll('.tile').forEach((t, k) => {
    t.classList.toggle('audio', k === i);
    t.querySelector('video').muted = k !== i;
  });
  view.querySelector(`.tile[data-t="${i}"] video`)?.play().catch(() => {});
}

function startTile(i, c) {
  const tile = view.querySelector(`.tile[data-t="${i}"]`);
  const pl = multi.players[i];
  if (!tile || !pl) return;
  tile.classList.toggle('empty', !c);
  tile.querySelector('.tile-name').textContent = c ? c.name : '';
  if (!c) return pl.stop();
  tile.querySelector('video').muted = i !== multi.audio;
  pl.play(c.url).catch(err => { if (multi.tiles[i] === c) tile.querySelector('.tile-name').textContent = `${c.name}: ${err.message}`; });
}

function stopMulti(keepTiles = false) {
  multi.players.forEach(p => p.stop());
  multi.players = [];
  if (!keepTiles) multiActive = false;
}

function pickChannel(i) {
  const pool = dedupe([...state.favorites.filter(isLiveChannel), ...state.playlists.flatMap(p => p.channels
    .filter(c => visibleGroup(p, c.group || ''))
    .map(c => ({ ...c, src: p.id })))]);
  sheet(`<div class="sheet-title">Choose a channel</div>
    <label class="search">${icon('search')}<input id="pick-q" type="search" placeholder="Search ${plural(pool.length, 'channel')}" autocomplete="off" autocorrect="off" spellcheck="false"></label>
    <ul class="list pick" id="pick-list"></ul>`, {
    onMount: el => {
      let shown = [];
      const render = q => {
        q = q.toLowerCase();
        shown = pool.filter(c => !q || c.name.toLowerCase().includes(q) || (c.group || '').toLowerCase().includes(q)).slice(0, 100);
        el.querySelector('#pick-list').innerHTML = shown.map((c, k) => `<li class="row" data-k="${k}">${logoHtml(c)}<span class="row-text"><span class="row-name">${esc(c.name)}</span><span class="muted">${esc(c.group || '')}</span></span></li>`).join('')
          || '<li class="empty small">No matches.</li>';
      };
      render('');
      el.querySelector('#pick-q').oninput = debounce(e => render(e.target.value.trim()), 120);
      el.querySelector('#pick-list').onclick = e => {
        const r = e.target.closest('[data-k]');
        if (!r) return;
        const c = shown[+r.dataset.k];
        closeSheet();
        multi.tiles[i] = c;
        startTile(i, c);
        if (multi.tiles.filter(Boolean).length === 1) setTileAudio(i);
      };
    },
  });
}

// ---------- playlist actions ----------
function playlistMenu(p) {
  const real = !p.virtual;
  const viewing = state.current === p;
  const live = !viewing || state.tab === 'live';
  const narrowed = viewing && (state.group !== null || state.query);
  const resort = async compare => {
    const before = p.channels;
    p.channels = [...p.channels].sort(compare); await save(p); rerender();
    toast('Sorted', { action: 'Undo', fn: async () => { p.channels = before; await save(p); rerender(); } });
  };
  menu(p.name, [
    p.kind === 'xtream' && { label: 'Account info', fn: () => accountSheet(p) },
    real && live && { label: 'TV guide', fn: () => { location.hash = `#/guide/${p.id}`; } },
    real && { label: guides.get(p.id) ? `Update TV guide (${fmtDay(guides.get(p.id).at)} ${fmtTime(guides.get(p.id).at)})` : 'Load TV guide', fn: () => updateGuide(p) },
    real && { label: 'Set TV guide (EPG) link…', fn: () => setEpgUrl(p) },
    real && (p.sourceUrl || p.kind === 'xtream') && { label: 'Refresh channels now', fn: () => refreshSource(p) },
    real && p.sourceUrl && p.kind !== 'xtream' && { label: p.autoRefresh ? '✓ Auto-refresh daily' : 'Auto-refresh daily', fn: async () => { p.autoRefresh = !p.autoRefresh; await save(p); toast(p.autoRefresh ? 'Will refresh once a day (your edits are replaced)' : 'Auto-refresh off'); } },
    viewing && !live && { label: `Reload ${state.tab} from provider`, fn: () => { vodCache.delete(vodKey(p, state.tab)); applyFilter(); loadVod(p, state.tab, { force: true }); } },
    real && { label: `Keep: ${{ live: 'Live TV only', vod: 'VOD only' }[p.content] || 'Live TV and VOD'}…`, fn: () => menu('Keep in this playlist', [
      ['both', 'Live TV and VOD'], ['live', 'Live TV only'], ['vod', 'VOD only (movies & series)'],
    ].map(([v, label]) => ({ label: `${(p.content || 'both') === v ? '✓ ' : ''}${label}`, fn: async () => {
      p.content = v;
      await save(p);
      // Widening needs the provider's full list again; narrowing just drops rows.
      if (p.sourceUrl || p.kind === 'xtream') refreshSource(p); else { p.channels = applyContent(v, p.channels); await save(p); rerender(); }
    } }))) },
    canReorder() && { label: state.reordering ? 'Done reordering' : 'Reorder channels in this group (drag)', fn: toggleReordering },
    viewing && live && state.group === null && !p.virtual && { label: 'Reorder channels… (open a group first)', fn: () => toast('Pick a group chip, then ⠿ in the top bar or ⋯ → Reorder channels') },
    real && live && { label: 'Add channel…', fn: () => addChannel(p) },
    live && p.channels.length && { label: 'Export / share M3U…', fn: () => exportMenu(p.name, p.channels, p.header) },
    narrowed && live && state.filtered.length && { label: `Export this view (${state.filtered.length.toLocaleString()})…`, fn: () => exportMenu(`${p.name} - ${state.group || 'search'}`, state.filtered, p.header) },
    !p.readOnly && live && p.channels.length > 1 && { label: 'Sort A–Z', fn: () => resort((a, b) => collator.compare(a.name, b.name)) },
    !p.readOnly && live && p.channels.length > 1 && { label: 'Sort by group', fn: () => resort((a, b) => collator.compare(a.group || '￿', b.group || '￿')) },
    real && live && p.channels.length > 1 && { label: 'Remove duplicates', fn: async () => {
      const before = p.channels;
      p.channels = dedupe(p.channels);
      await save(p);
      rerender();
      toast(`Removed ${plural(before.length - p.channels.length, 'duplicate')}`, { action: 'Undo', fn: async () => { p.channels = before; await save(p); rerender(); } });
    } },
    real && { label: 'Manage groups (hide, lock, reorder)…', fn: () => groupsSheet(p, viewing ? state.tab : 'live') },
    real && { label: 'Rename…', fn: async () => {
      const v = await formSheet({ title: 'Rename playlist', fields: [{ name: 'name', label: 'Name', value: p.name, required: true }] });
      if (!v) return;
      p.name = v.name;
      await save(p);
      rerender();
    } },
    real && { label: 'Delete playlist', danger: true, fn: async () => {
      if (!await confirmDialog(`Delete “${p.name}”? Favorites are kept.`, 'Delete', true)) return;
      await db.deletePlaylist(p.id);
      const sk = sourceKey(p);   // other devices: don't add it back here (they keep their own copy)
      if (sk) { syncMeta.srcDel = { ...(syncMeta.srcDel || {}), [sk]: Date.now() }; db.set('syncMeta', syncMeta).catch(() => {}); syncSoon(); }
      for (const k of ['movies', 'series']) db.del(`vod:${vodKey(p, k)}`).catch(() => {});
      db.del(`epg:${p.id}`).catch(() => {});
      state.playlists = state.playlists.filter(x => x !== p);
      guides.delete(p.id);
      if (viewing) location.hash = '#/'; else showHome();
      toast('Playlist deleted');
    } },
    p.id === 'recent' && p.channels.length && { label: 'Clear history', danger: true, fn: async () => {
      const before = p.channels;
      p.channels = [];
      await save(p);
      rerender();
      toast('History cleared', { action: 'Undo', fn: async () => { p.channels = before; await save(p); rerender(); } });
    } },
  ]);
}

function accountSheet(p) {
  const u = p.userInfo || {};
  const exp = Number(u.exp_date);
  const rows = [
    ['Status', u.status || '—'],
    ['Expires', exp ? new Date(exp * 1000).toLocaleString() : 'Never'],
    ['Connections', u.max_connections ? `${u.active_cons ?? 0} in use of ${u.max_connections}` : '—'],
    ['Trial', u.is_trial === '1' ? 'Yes' : 'No'],
    ['Server', p.xtream.server],
    ['Username', p.xtream.user],
    ['Server time zone', p.serverInfo?.timezone || '—'],
    ['Last refreshed', p.refreshedAt ? new Date(p.refreshedAt).toLocaleString() : '—'],
  ];
  sheet(`<div class="sheet-title">Account</div><dl class="info">${rows.map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join('')}</dl>
    <div class="form-actions"><button class="btn" data-refresh>Refresh now</button></div>`, {
    onMount: el => { el.querySelector('[data-refresh]').onclick = async () => { closeSheet(); await refreshSource(p); accountSheet(p); }; },
  });
}

function groupsSheet(p, tab, { scroll = 0, q = '' } = {}) {
  const items = tab === 'live' ? p.channels : vodCache.get(vodKey(p, tab)) || [];
  const groups = Array.isArray(items) ? groupsOf(items) : items.groupList;
  const hidden = new Set(p.hiddenGroups || []);
  const locked = new Set(p.lockedGroups || []);
  const what = tab === 'live' ? 'channel' : tab === 'movies' ? 'movie' : 'show';
  const shown = groups.length - groups.filter(g => hidden.has(g.name)).length;
  // Provider lists prefix groups with a country/region ("UK | SPORTS"). Offer those prefixes as
  // one-tap filters: tap UK -> only UK groups stay visible; tap more to add; All restores.
  const byPrefix = new Map();
  for (const g of groups) { const k = regionOf(g.name) || 'OTHER'; if (!byPrefix.has(k)) byPrefix.set(k, []); byPrefix.get(k).push(g.name); }
  const prefixes = [...byPrefix.entries()].sort((a, b) => (a[0] === 'OTHER') - (b[0] === 'OTHER') || b[1].length - a[1].length);
  const prefixOn = k => byPrefix.get(k).some(n => !hidden.has(n));
  const chipsHtml = prefixes.length > 1 ? `<div class="chips pchips">
      <button class="chip ${hidden.size === 0 ? 'active' : ''}" data-prefix="*">All</button>
      ${prefixes.map(([k, names]) => `<button class="chip ${prefixOn(k) && hidden.size ? 'active' : ''}" data-prefix="${esc(k)}">${k === 'OTHER' ? 'Other / US' : esc(k)}<span>${names.length}</span></button>`).join('')}
    </div>` : '';
  // Bulk visibility: type a word (e.g. "UK") to filter the list, then keep only those / hide those / show all.
  sheet(`<div class="sheet-title">Groups${tab === 'live' ? '' : ` · ${tab}`}</div>
    <p class="muted center">${shown} of ${groups.length} groups shown. Tick = shown everywhere. ★ = on your Home screen${(p.homeGroups || []).length ? ` (${p.homeGroups.length} pinned)` : ''}.</p>
    ${chipsHtml}
    <label class="search gsearch">${icon('search')}<input id="g-q" type="search" placeholder="Filter groups, e.g. UK" autocomplete="off" autocorrect="off" spellcheck="false"></label>
    <div class="gbulk">
      <button class="btn small primary" data-bulk="only">Show only these</button>
      <button class="btn small" data-bulk="hide">Hide these</button>
      <button class="btn small" data-bulk="show">Show these</button>
      <button class="btn small" data-bulk="all">Show all</button>
    </div>
    ${tab === 'live' ? `<div class="gbulk gsort"><span class="muted">Order:</span>
      <button class="btn small" data-sort="shown">Shown first</button>
      <button class="btn small" data-sort="az">A–Z</button>
      <button class="btn small" data-sort="count">Biggest first</button>
      <span class="muted gdrag-hint">${icon('grip')} drag to arrange</span></div>` : ''}
    <ul class="glist">${groups.map((g, i) => `<li data-i="${i}" data-gname="${esc((g.name || UNGROUPED).toLowerCase())}" class="${hidden.has(g.name) ? 'is-hidden' : ''}">
      <input type="checkbox" class="gcheck" data-act="eye" data-g="${i}" ${hidden.has(g.name) ? '' : 'checked'} aria-label="Show ${esc(g.name || UNGROUPED)}">
      <span class="row-text"><span class="row-name">${locked.has(g.name) ? icon('lock') : ''}${esc(prettyGroup(g.name) || UNGROUPED)}</span><span class="muted">${plural(g.count, what)}${hidden.has(g.name) ? ' · hidden' : ''}</span></span>
      ${tab === 'live' ? `<button class="icon-btn star${(p.homeGroups || []).includes(g.name) ? ' on' : ''}" data-act="pin" data-g="${i}" aria-label="Show on Home">${icon('star')}</button>` : ''}
      <button class="btn small" data-act="more" data-g="${i}">Options</button>
      ${tab === 'live' ? `<span class="ghandle" data-act="drag" aria-label="Drag to reorder">${icon('grip')}</span>` : ''}
    </li>`).join('')}</ul>`, {
    onMount: el => {
      const rows = [...el.querySelectorAll('.glist > li')];
      const visibleRows = () => rows.filter(li => !li.hidden);
      const qEl = el.querySelector('#g-q');
      const filterRows = () => { const t = qEl.value.trim().toLowerCase(); rows.forEach(li => { li.hidden = !!t && !li.dataset.gname.includes(t); }); };
      qEl.oninput = filterRows;
      // After a change the sheet is rebuilt: keep the filter text and the scroll position, so nothing jumps.
      if (q) { qEl.value = q; filterRows(); }
      if (scroll) el.scrollTop = scroll;
      let memo = { scroll, q };
      const remember = () => { memo = { scroll: el.scrollTop, q: qEl.value }; };
      const reopen = () => groupsSheet(p, tab, memo);
      const setHidden = async (fn) => {
        const set = new Set(p.hiddenGroups || []);
        fn(set);
        p.hiddenGroups = [...set];
        await save(p);
        rerender();
        reopen();
      };
      // Reorder groups: `order` is the full list of group names in the wanted order.
      const applyOrder = async order => {
        const rank = new Map(order.map((n, k) => [n, k]));
        p.channels = [...p.channels].sort((a, c) => (rank.get(a.group || '') ?? 1e9) - (rank.get(c.group || '') ?? 1e9));
        await save(p);
        rerender();
        reopen();
      };
      // Drag a row by its handle to reorder (touch and mouse).
      const list = el.querySelector('.glist');
      let drag = null;
      list.onpointerdown = e => {
        const h = e.target.closest('[data-act="drag"]');
        if (!h) return;
        drag = { li: h.closest('li'), x: e.clientX, y: e.clientY };
        drag.li.classList.add('dragging');
        h.setPointerCapture(e.pointerId);
        e.preventDefault();
        setTimeout(autoScroll, 16);
      };
      const placeAt = (x, y) => {
        const over = document.elementFromPoint(x, y)?.closest('.glist > li');
        if (!over || over === drag.li) return;
        const r = over.getBoundingClientRect();
        list.insertBefore(drag.li, y < r.top + r.height / 2 ? over : over.nextSibling);
      };
      // Holding a group near the top or bottom edge scrolls the list, faster the closer you get,
      // so a group can be carried any distance in one drag.
      const EDGE = 110;
      const autoScroll = () => {
        if (!drag) return;
        const r = el.getBoundingClientRect(), lr = list.getBoundingClientRect();
        const top = Math.max(r.top, 0), bottom = Math.min(r.bottom, innerHeight);
        const v = drag.y < top + EDGE ? -(top + EDGE - drag.y) : drag.y > bottom - EDGE ? drag.y - (bottom - EDGE) : 0;
        if (v) {
          el.scrollTop += Math.max(-28, Math.min(28, v / 2.5));
          // The finger may be over the sheet's header (or above the sheet): place against the nearest visible row.
          placeAt(drag.x, Math.min(Math.max(drag.y, Math.max(top, lr.top) + 6), Math.min(bottom, lr.bottom) - 6));
        }
        setTimeout(autoScroll, 16);
      };
      list.onpointermove = e => {
        if (!drag) return;
        drag.x = e.clientX; drag.y = e.clientY;
        placeAt(e.clientX, e.clientY);
      };
      list.onpointerup = list.onpointercancel = async () => {
        if (!drag) return;
        remember();
        drag.li.classList.remove('dragging');
        drag = null;
        await applyOrder([...list.children].map(li => groups[+li.dataset.i].name));
      };
      el.onclick = async e => {
        remember();   // menus close the sheet before their action runs, so note the place now
        const srt = e.target.closest('[data-sort]');
        if (srt) {
          const names = groups.map(g => g.name);
          const s = srt.dataset.sort;
          if (s === 'az') return applyOrder([...names].sort((a, b) => collator.compare(prettyGroup(a), prettyGroup(b))));
          if (s === 'count') return applyOrder([...groups].sort((a, b) => b.count - a.count).map(g => g.name));
          if (s === 'shown') return applyOrder([...names.filter(n => !hidden.has(n)), ...names.filter(n => hidden.has(n))]);
        }
        const pc = e.target.closest('[data-prefix]');
        if (pc) {
          const k = pc.dataset.prefix;
          if (k === '*') return setHidden(s => s.clear());
          const names = byPrefix.get(k) || [];
          // First tap from "everything visible" = only this country. After that, taps add or remove a country.
          if (hidden.size === 0) return setHidden(s => { groups.forEach(g => s.add(g.name)); names.forEach(n => s.delete(n)); });
          if (prefixOn(k)) return setHidden(s => names.forEach(n => s.add(n)));
          return setHidden(s => names.forEach(n => s.delete(n)));
        }
        const bulk = e.target.closest('[data-bulk]');
        if (bulk) {
          const names = visibleRows().map(li => groups[rows.indexOf(li)].name);
          const act = bulk.dataset.bulk;
          if (act === 'all') return setHidden(s => s.clear());
          if (act === 'only') return setHidden(s => { groups.forEach(g => s.add(g.name)); names.forEach(n => s.delete(n)); });
          if (act === 'hide') return setHidden(s => names.forEach(n => s.add(n)));
          if (act === 'show') return setHidden(s => names.forEach(n => s.delete(n)));
        }
        const b = e.target.closest('[data-act]');
        if (!b) return;
        const i = +b.dataset.g;
        const g = groups[i];
        if (b.dataset.act === 'eye') return setHidden(s => { if (s.has(g.name)) s.delete(g.name); else s.add(g.name); });
        if (b.dataset.act === 'drag') return;
        if (b.dataset.act === 'pin') {
          const set = new Set(p.homeGroups || []);
          if (set.has(g.name)) set.delete(g.name); else set.add(g.name);
          p.homeGroups = groups.map(x => x.name).filter(n => set.has(n));   // keep the list's order
          await save(p);
          b.classList.toggle('on', set.has(g.name));   // in place: no re-render, so the list stays where you are
          if (!state.current) showHome();             // Home's tiles sit under the sheet
          return;
        }
        const toggle = async (key, name) => {
          const set = new Set(p[key] || []);
          if (set.has(name)) set.delete(name); else set.add(name);
          p[key] = [...set];
          await save(p);
          rerender();
          reopen();
        };
        const inGroup = p.channels.filter(c => (c.group || '') === g.name);
        const others = groups.map(x => x.name).filter(n => n !== g.name);
        menu(prettyGroup(g.name) || UNGROUPED, [
          { label: hidden.has(g.name) ? 'Show group' : 'Hide group', fn: () => toggle('hiddenGroups', g.name) },
          tab === 'live' && { label: 'Move to top', fn: () => applyOrder([g.name, ...others]) },
          tab === 'live' && { label: 'Move to bottom', fn: () => applyOrder([...others, g.name]) },
          { label: locked.has(g.name) ? 'Unlock group' : 'Lock with PIN', fn: async () => {
            if (locked.has(g.name)) { if (await unlock()) toggle('lockedGroups', g.name); return; }
            if (!state.settings.pinHash && !await setPin()) return;
            toggle('lockedGroups', g.name);
          } },
          tab === 'live' && { label: 'Rename…', fn: async () => {
            const v = await formSheet({ title: 'Rename group', fields: [{ name: 'name', label: 'Group name', value: g.name }], submit: 'Rename' });
            if (!v) return;
            inGroup.forEach(c => { c.group = v.name; });
            await save(p);
            if (state.group === g.name) state.group = v.name;
            rerender();
            toast('Group renamed');
            reopen();
          } },
          tab === 'live' && { label: `Delete group and its ${plural(g.count, 'channel')}`, danger: true, fn: async () => {
            if (!await confirmDialog(`Delete “${g.name || UNGROUPED}” and its ${plural(g.count, 'channel')}?`, 'Delete', true)) return;
            await deleteChannels(p, new Set(inGroup.map(c => c.id)));
            reopen();
          } },
        ]);
      };
    },
  });
}

function exportMenu(name, channels, header = {}) {
  const fileName = `${name.replace(/[\\/:*?"<>|]+/g, '_').trim() || 'playlist'}.m3u`;
  const file = () => new File([toM3U(channels, header)], fileName, { type: 'audio/x-mpegurl' });
  menu(`Export ${plural(channels.length, 'channel')}`, [
    (isNative || navigator.canShare) && { label: 'Share… (Save to Files, AirDrop, VLC)', fn: () => shareOrDownload(file(), name) },
    !isNative && { label: 'Download .m3u file', fn: () => download(file()) },
    { label: 'Copy as text', fn: () => copyText(toM3U(channels, header), 'Playlist copied') },
  ]);
}

// ---------- importing ----------
function parseSource(text, sourceUrl) {
  if (sourceUrl && isHlsMedia(text)) {
    return { header: {}, channels: [makeChannel({ name: nameFromUrl(sourceUrl), url: sourceUrl })] };
  }
  const parsed = parseM3U(text);
  if (!parsed.channels.length) throw new Error('No channels found in that playlist.');
  return parsed;
}

async function loadFromUrl(url) {
  try {
    return parseSource(await fetchText(url, { timeout: 60000 }), url);
  } catch (err) {
    // A single HLS stream can still play natively even when we can't read it.
    if (/\.m3u8(\?|$)/i.test(url) && !/get\.php/i.test(url)) {
      return { header: {}, channels: [makeChannel({ name: nameFromUrl(url), url })], single: true };
    }
    throw err;
  }
}

async function xtreamPlaylist(creds) {
  const x = new Xtream(creds);
  const info = await x.login();
  const [cats, streams] = await Promise.all([x.categories('live'), x.liveStreams()]);
  return {
    channels: liveChannels(x, cats, streams),
    extra: { kind: 'xtream', xtream: { server: x.server, user: x.user, pass: x.pass }, userInfo: info.user_info, serverInfo: info.server_info, refreshedAt: Date.now() },
  };
}

const ADD_HINTS = {
  xtream: 'The server address, username and password from your IPTV provider. You get Live TV, Movies, Series, TV guide and catch-up.',
  url: 'A link to an .m3u / .m3u8 playlist, or to a single stream.',
  file: 'Pick an .m3u or .m3u8 file from Files, iCloud Drive, etc.',
  paste: 'Paste playlist text, or simply one stream link per line.',
};

function addPlaylistSheet() {
  sheet(`<div class="sheet-title">Add playlist</div>
    <div class="tabs">
      <button class="tab active" data-tab="xtream">Xtream</button><button class="tab" data-tab="url">Link</button>
      <button class="tab" data-tab="file">File</button><button class="tab" data-tab="paste">Paste</button>
    </div>
    <form class="form" id="addform">
      <label>Name<input name="name" placeholder="Optional" autocorrect="off"></label>
      <label>Keep<select name="content">
        <option value="both">Live TV and VOD (movies &amp; series)</option>
        <option value="live">Live TV only</option>
        <option value="vod">VOD only (movies &amp; series)</option>
      </select></label>
      <div class="pane" data-pane="xtream">
        <label>Server<input name="server" inputmode="url" placeholder="http://provider.example:8080" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
        <label>Username<input name="user" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
        <label>Password<input name="pass" type="password"></label>
      </div>
      <div class="pane" data-pane="url" hidden><label>Playlist URL<input name="url" type="url" inputmode="url" placeholder="https://example.com/playlist.m3u" autocapitalize="off" autocorrect="off" spellcheck="false"></label></div>
      <div class="pane" data-pane="file" hidden><label>Playlist file<input name="file" type="file"></label></div>
      <div class="pane" data-pane="paste" hidden><label>M3U text<textarea name="text" rows="6" placeholder="#EXTM3U&#10;#EXTINF:-1 group-title=&quot;News&quot;,My Channel&#10;https://example.com/live.m3u8" spellcheck="false"></textarea></label></div>
      <p class="muted" id="add-hint">${ADD_HINTS.xtream}</p>
      <div class="form-actions"><button type="button" class="btn" data-empty>Create empty</button><button class="btn primary" data-submit>Add</button></div>
    </form>`, {
    onMount: el => {
      let tab = 'xtream';
      const form = el.querySelector('form');
      const hint = el.querySelector('#add-hint');
      const submit = el.querySelector('[data-submit]');
      el.querySelector('.tabs').onclick = e => {
        const b = e.target.closest('.tab');
        if (!b) return;
        tab = b.dataset.tab;
        el.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t === b));
        el.querySelectorAll('.pane').forEach(p => { p.hidden = p.dataset.pane !== tab; });
        hint.className = 'muted';
        hint.textContent = ADD_HINTS[tab];
      };
      el.querySelector('[data-empty]').onclick = async () => {
        const p = await createPlaylist(form.name.value.trim() || 'My playlist', []);
        closeSheet();
        location.hash = `#/p/${p.id}`;
      };
      form.onsubmit = async e => {
        e.preventDefault();
        const f = new FormData(form);
        let name = String(f.get('name')).trim();
        submit.disabled = true;
        submit.textContent = 'Loading…';
        hint.className = 'muted';
        hint.textContent = 'Connecting…';
        try {
          let channels, header = {}, extra = {};
          if (tab === 'xtream') {
            const [server, user, pass] = ['server', 'user', 'pass'].map(k => String(f.get(k)).trim());
            if (!server || !user) throw new Error('Enter the server and username.');
            ({ channels, extra } = await xtreamPlaylist({ server, user, pass }));
            name ||= host(extra.xtream.server) || 'IPTV';
          } else if (tab === 'url') {
            const url = String(f.get('url')).trim();
            if (!url) throw new Error('Enter a playlist link.');
            const creds = parseXtreamLink(url);
            let result = null;
            if (creds) { try { result = await xtreamPlaylist(creds); } catch {} }
            if (result) ({ channels, extra } = result);
            else {
              const r = await loadFromUrl(url);
              ({ channels, header } = r);
              extra = { sourceUrl: r.single ? undefined : url, refreshedAt: Date.now() };
              if (r.single) name ||= channels[0].name;
            }
            name ||= host(url) || 'Playlist';
          } else if (tab === 'file') {
            const file = f.get('file');
            if (!file?.size) throw new Error('Choose a file.');
            ({ channels, header } = parseSource(await file.text()));
            name ||= file.name.replace(/\.[^.]+$/, '');
          } else {
            ({ channels, header } = parseSource(String(f.get('text'))));
            name ||= 'Pasted playlist';
          }
          const content = String(f.get('content') || 'both');
          const p = await createPlaylist(name, applyContent(content, channels), { header, content, ...extra });
          closeSheet();
          location.hash = `#/p/${p.id}`;
          toast(content === 'vod' ? `Added ${plural(p.channels.length, 'item')}` : `Added ${plural(p.channels.length, 'channel')}`);
        } catch (err) {
          hint.className = 'error';
          hint.textContent = err.message;
          submit.disabled = false;
          submit.textContent = 'Add';
        }
      };
    },
  });
}

// "Keep" choice for a playlist: both | live | vod. For an M3U the rows are filtered by what their link
// is; for Xtream the live list is all live, and the VOD tabs are simply shown or hidden.
function applyContent(content, channels) {
  if (!content || content === 'both') return channels;
  return channels.filter(c => isSeparator(c) ? false : (channelKind(c) === 'live') === (content === 'live'));
}

// A refresh takes the provider's new list but keeps what you changed: your order, renamed or moved
// channels (edited), and deletions. New channels are added at the end.
function mergeRefresh(p, fresh) {
  const deleted = new Set(p.deletedUrls || []);
  const byUrl = new Map(fresh.map(c => [c.url, c]));
  const seen = new Set(), out = [];
  for (const old of p.channels) {
    const n = byUrl.get(old.url);
    if (!n || seen.has(old.url)) continue;
    seen.add(old.url);
    out.push(old.edited ? { ...n, id: old.id, name: old.name, group: old.group, logo: old.logo || n.logo, tvgId: old.tvgId || n.tvgId, edited: true } : { ...n, id: old.id });
  }
  for (const n of fresh) if (!seen.has(n.url) && !deleted.has(n.url)) out.push(n);
  return out;
}
// Backup copies of a merged channel (same group, id and name) follow its edits and deletions.
const copiesOf = (p, c) => { const k = dupKey(c); return p.channels.filter(x => x !== c && dupKey(x) === k); };

async function refreshSource(p, { silent = false } = {}) {
  const redraw = () => { if (!silent || state.current === p) rerender(); };
  if (!silent) toast('Refreshing channels…', { busy: true });
  try {
    const before = p.channels;
    if (p.kind === 'xtream') {
      const x = xtOf(p);
      const info = await x.login();
      const [cats, streams] = await Promise.all([x.categories('live'), x.liveStreams()]);
      p.channels = mergeRefresh(p, applyContent(p.content, liveChannels(x, cats, streams)));
      p.userInfo = info.user_info;
      p.serverInfo = info.server_info;
    } else {
      const { header, channels } = parseSource(await fetchText(p.sourceUrl, { timeout: 60000 }), p.sourceUrl);
      p.channels = mergeRefresh(p, applyContent(p.content, channels));
      p.header = header;
    }
    p.refreshedAt = Date.now();
    await save(p);
    redraw();
    if (!silent) toast(`Updated: ${plural(p.channels.length, 'channel')}`, { action: 'Undo', fn: async () => { p.channels = before; await save(p); rerender(); } });
  } catch (err) {
    if (!silent) toast(err.message);
  }
}

async function backgroundRefresh() {
  for (const p of state.playlists) {
    const auto = p.kind === 'xtream' || (p.autoRefresh && p.sourceUrl);
    if (auto && Date.now() - (p.refreshedAt || p.createdAt) > DAY) await refreshSource(p, { silent: true });
    maybeUpdateGuide(p);
  }
}

// ---------- settings, backup ----------
async function settingsSheet() {
  const s = state.settings;
  const total = state.playlists.reduce((n, p) => n + p.channels.length, 0);
  let usage = '';
  try {
    const est = await navigator.storage?.estimate?.();
    if (est?.usage) usage = ` · ${(est.usage / 1048576).toFixed(1)} MB used`;
  } catch {}
  sheet(`<div class="sheet-title">Settings</div>
    <form class="form" id="settings">
      ${isNative ? `<div class="section-title">Connection</div>
      <p class="muted">The iPhone app plays streams directly from your provider. Your server adds the TV guide, sync with your other devices, movies &amp; series and downloads${state.settings.serverUrl ? ` (connected: ${esc(state.settings.serverUrl)})` : ''}.</p>
      <button type="button" class="btn" id="server-key-2">${state.settings.serverUrl ? 'Change server' : 'Connect your server'}</button>` : `<div class="section-title">Proxy</div>
      ${serverProxy.available
        ? `<p class="muted">Your server’s built-in proxy is ${serverProxy.needsKey ? '<b>waiting for its key</b>' : 'active'}. Leave the address below empty to use it.</p>
           <button type="button" class="btn" id="server-key-2">${serverProxy.needsKey ? 'Enter server key' : 'Change server key'}</button>`
        : `<p class="muted">Lets the app reach http:// IPTV servers and servers that block web apps. Host the app on your own server (it has a proxy built in) or set up a free one: <a href="https://github.com/wmorgan97/m3u-player/blob/main/proxy/README.md" target="_blank" rel="noopener">instructions</a>.</p>`}
      <label>Proxy address<input name="proxy" value="${esc(s.proxy)}" placeholder="https://m3u-proxy.you.workers.dev/?url={url}" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
      <label>Send streams through the proxy<select name="streamProxy">
        ${[['http', 'Only http:// streams (recommended)'], ['all', 'All streams'], ['off', 'Never']].map(([v, l]) => `<option value="${v}" ${s.streamProxy === v ? 'selected' : ''}>${l}</option>`).join('')}
      </select></label>`}
      <div class="section-title">Appearance</div>
      <label>Row size<select name="density">${[['compact', 'Compact'], ['standard', 'Standard'], ['large', 'Large']].map(([v, l]) => `<option value="${v}" ${(s.density || 'standard') === v ? 'selected' : ''}>${l}</option>`).join('')}</select></label>
      <label>Accent colour<select name="accent">${[['', 'Blue'], ['teal', 'Teal'], ['green', 'Green'], ['purple', 'Purple'], ['orange', 'Orange'], ['red', 'Red']].map(([v, l]) => `<option value="${v}" ${(s.accent || '') === v ? 'selected' : ''}>${l}</option>`).join('')}</select></label>
      <label class="switch"><span><b>Channel logos</b></span><input type="checkbox" name="logos" ${s.logos !== false ? 'checked' : ''}></label>
      <label class="switch"><span><b>Channel numbers</b></span><input type="checkbox" name="numbers" ${s.numbers ? 'checked' : ''}></label>
      <label class="switch"><span><b>Show what’s on next</b><span class="muted">After the current programme on each channel row.</span></span><input type="checkbox" name="showNext" ${s.showNext !== false ? 'checked' : ''}></label>
      <div class="section-title">Channels</div>
      <label class="switch"><span><b>Merge duplicate channels</b><span class="muted">Show each channel once; the copies become backup streams, used automatically if one fails.</span></span><input type="checkbox" name="mergeDupes" ${s.mergeDupes !== false ? 'checked' : ''}></label>
      <label class="switch"><span><b>Prefer 4K live channels</b><span class="muted">Where a channel also comes in 4K, play that copy (Safari and the iPhone app; the normal copy stays as backup).</span></span><input type="checkbox" name="prefer4k" ${s.prefer4k !== false ? 'checked' : ''}></label>
      <label class="switch"><span><b>Continue watching on Home</b><span class="muted">Your last channel at the top of Home, one tap to play.</span></span><input type="checkbox" name="resumeCard" ${s.resumeCard !== false ? 'checked' : ''}></label>
      <div class="section-title">Playback</div>
      ${isNative ? '' : (() => {
        const opts = [['app', 'This app (built-in player)'], ['vlc', 'VLC (free; plays every format)'], ['infuse', 'Infuse'], ...(/Macintosh/.test(navigator.userAgent) ? [['iina', 'IINA (Mac, free)']] : [])];
        const sel = (name, cur) => `<select name="${name}">${opts.map(([v, l]) => `<option value="${v}" ${(cur || 'app') === v ? 'selected' : ''}>${l}</option>`).join('')}</select>`;
        return `<label>Play films &amp; series in${sel('vodPlayer', s.vodPlayer)}</label>
          <label>Play live TV in${sel('livePlayer', s.livePlayer)}</label>
          <p class="muted small">Another player opens the provider's stream itself, untouched. ${/Macintosh/.test(navigator.userAgent) && !isIOS ? 'On a Mac, VLC gets a small playlist file: click it in your downloads and VLC plays the stream.' : ''}</p>`;
      })()}
      ${canSync() ? `<label class="switch"><span><b>Film &amp; TV info (TMDB)</b><span class="muted">Trending this week, backdrops, plots, cast and trailers.</span></span><button type="button" class="btn small" id="tmdb-btn">${tmdbOn ? 'Connected' : 'Set up'}</button></label>` : ''}
      <label>Movies &amp; series languages<select name="vodLanguage">
        ${[['en', 'English only'], ['all', 'All languages']].map(([v, l]) => `<option value="${v}" ${(s.vodLanguage || 'en') === v ? 'selected' : ''}>${l}</option>`).join('')}
      </select></label>
      <label>Buffering<select name="buffer">
        ${[['auto', 'Auto (fast, steps up if a channel keeps stalling)'], ['fast', 'Fast switching (~4s buffer)'], ['balanced', 'Balanced (~8s buffer)'], ['stable', 'Stable (~14s buffer, slower switching)']].map(([v, l]) => `<option value="${v}" ${(s.buffer || 'auto') === v ? 'selected' : ''}>${l}</option>`).join('')}
      </select></label>
      <label class="switch"><span><b>Use iPhone’s own player controls</b><span class="muted">Instead of the app’s controls (pause live, rewind, channel list on the video).</span></span>
        <input type="checkbox" name="nativeControls" ${s.nativeControls ? 'checked' : ''}></label>
      <label class="switch"><span><b>Try HLS for .ts links</b><span class="muted">Most IPTV providers also serve each .ts channel as .m3u8, which iPhone can play. Tried first, with the original link as backup.</span></span>
        <input type="checkbox" name="tsToHls" ${s.tsToHls ? 'checked' : ''}></label>
      <div class="form-actions"><button class="btn primary">Save settings</button></div>
    </form>
    <div class="section-title">Parental control</div>
    <p class="muted">${s.pinHash ? 'A PIN is set. Lock groups from a playlist’s ⋯ → Manage groups.' : 'Set a PIN, then lock adult or other groups from ⋯ → Manage groups.'}</p>
    <div class="form-actions"><button class="btn" id="pin">${s.pinHash ? 'Change PIN' : 'Set PIN'}</button>${s.pinHash ? '<button class="btn danger" id="pin-off">Remove PIN</button>' : ''}</div>
    <div class="section-title">Backup</div>
    <p class="muted">${plural(state.playlists.length, 'playlist')}, ${plural(total, 'channel')}${usage}. Stored only on this device. Back up to move everything to another phone or browser.</p>
    <div class="form-actions"><button class="btn" id="backup">Back up</button><label class="btn file-btn">Restore<input type="file" id="restore" accept=".json,application/json" hidden></label></div>
    <div class="form-actions"><button class="btn danger" id="wipe">Erase all data</button></div>`, {
    onMount: el => {
      el.querySelector('#server-key-2')?.addEventListener('click', enterServerKey);
      el.querySelector('#tmdb-btn')?.addEventListener('click', tmdbSheet);
      el.querySelector('form').onsubmit = async e => {
        e.preventDefault();
        const f = new FormData(e.target);
        const mergeWas = state.settings.mergeDupes !== false, langWas = state.settings.vodLanguage;
        Object.assign(state.settings, {
          ...(f.has('proxy') ? { proxy: String(f.get('proxy')).trim(), streamProxy: String(f.get('streamProxy')) } : {}),
          tsToHls: f.get('tsToHls') === 'on', nativeControls: f.get('nativeControls') === 'on', buffer: String(f.get('buffer') || 'auto'), vodLanguage: String(f.get('vodLanguage') || 'en'), vodPlayer: String(f.get('vodPlayer') || state.settings.vodPlayer || 'app'), prefer4k: f.get('prefer4k') === 'on', livePlayer: String(f.get('livePlayer') || state.settings.livePlayer || 'app'),
          density: String(f.get('density')), accent: String(f.get('accent')), logos: f.get('logos') === 'on', numbers: f.get('numbers') === 'on',
          showNext: f.get('showNext') === 'on', mergeDupes: f.get('mergeDupes') === 'on', resumeCard: f.get('resumeCard') === 'on',
        });
        if (mergeWas !== state.settings.mergeDupes) state.playlists.forEach(invalidate);
        if (langWas !== state.settings.vodLanguage) vodCache.clear();   // rebuilt with the new language filter on next view
        applySettings();
        rerender();
        await db.set('settings', state.settings);
        closeSheet();
        toast('Settings saved');
      };
      el.querySelector('#pin').onclick = async () => {
        if (state.settings.pinHash && !await unlock()) return;
        if (await setPin()) settingsSheet();
      };
      el.querySelector('#pin-off')?.addEventListener('click', async () => {
        if (!await unlock()) return;
        state.settings.pinHash = '';
        await db.set('settings', state.settings);
        toast('PIN removed. Locked groups are now open.');
        rerender();
      });
      el.querySelector('#backup').onclick = () => {
        const data = { app: 'm3u-player', version: 1, exportedAt: new Date().toISOString(), playlists: state.playlists, favorites: state.favorites, recents: state.recents, progress: state.progress, settings: state.settings };
        shareOrDownload(new File([JSON.stringify(data)], `m3u-player-backup-${new Date().toISOString().slice(0, 10)}.json`, { type: 'application/json' }), 'M3U Player backup');
      };
      el.querySelector('#restore').onchange = async e => {
        const file = e.target.files[0];
        if (!file) return;
        try {
          const data = JSON.parse(await file.text());
          if (data.app !== 'm3u-player' || !Array.isArray(data.playlists)) throw new Error('That isn’t an M3U Player backup.');
          if (!await confirmDialog(`Replace everything on this device with the backup (${plural(data.playlists.length, 'playlist')})?`, 'Restore', true)) return;
          await db.clear();
          for (const p of data.playlists) await db.putPlaylist(p);
          await Promise.all([db.set('favorites', data.favorites || []), db.set('recents', data.recents || []), db.set('progress', data.progress || {}), db.set('settings', data.settings || {})]);
          location.hash = '#/';
          location.reload();
        } catch (err) {
          toast(err.message);
        }
      };
      el.querySelector('#wipe').onclick = async () => {
        if (!await confirmDialog('Erase every playlist, favorite and setting on this device?', 'Erase', true)) return;
        await db.clear();
        location.hash = '#/';
        location.reload();
      };
    },
  });
}

// ---------- playback ----------
let statusError = false;
// Channel list over the video (TiviMate-style): search, type a number, tap to switch. The video keeps playing.
let ovShown = [];
function channelsOverlay() {
  const pl = state.playing;
  if (!pl || pl.list.length < 2) return;
  const list = pl.list;
  let q = '';
  const fill = el => {
    const t = q.trim().toLowerCase();
    const num = /^\d+$/.test(t);
    ovShown = (t ? list.filter(c => num ? String(c.num || '').startsWith(t) : c.name.toLowerCase().includes(t) || (c.group || '').toLowerCase().includes(t)) : list).slice(0, 400);
    el.querySelector('#ov-list').innerHTML = ovShown.map((c, k) => `<li class="row${c === list[pl.index] ? ' playing' : ''}" data-k="${k}">
      ${c.num ? `<span class="ch-num">${c.num}</span>` : ''}${logoHtml(c)}
      <span class="row-text"><span class="row-name">${esc(c.name)}</span><span class="row-sub">${nowLine(c) || (c.group ? `<span class="muted">${esc(prettyGroup(c.group))}</span>` : '')}</span></span></li>`).join('')
      || '<li class="empty small">No matches.</li>';
    ensureGuide(ovShown);
    if (!t) el.querySelector('#ov-list .playing')?.scrollIntoView({ block: 'center' });
  };
  sheet(`<div class="sheet-title">Channels</div>
    <label class="search ovsearch">${icon('search')}<input id="ov-q" type="search" inputmode="search" placeholder="Search, or type a channel number" autocomplete="off" autocorrect="off" spellcheck="false"></label>
    <ul class="list ovlist" id="ov-list"></ul>`, {
    onMount: el => {
      fill(el);
      el.querySelector('#ov-q').oninput = debounce(e => { q = e.target.value; fill(el); }, 100);
      el.querySelector('#ov-list').onclick = e => {
        const li = e.target.closest('[data-k]');
        if (!li) return;
        const c = ovShown[+li.dataset.k];
        closeSheet();
        playChannel(list, list.indexOf(c));
      };
    },
    onClose: () => { ovShown = []; },
  });
}

// ---------- the app's own player controls ----------
// Tap the video: channel + what's on, play/pause (pause live), back 30s, a scrub bar over the server's
// 45-minute buffer, LIVE to jump back to the edge, channel list, mute, AirPlay, PiP, full screen.
let ctlHide = 0, keepAlive = 0, pausedAt = 0, pauseTimer = 0;
// A paused film gives the provider connection back after a couple of seconds (scrubbing doesn't count).
function releaseOnPause() {
  clearTimeout(pauseTimer);
  pauseTimer = setTimeout(() => {
    const c = nowChannel();
    if (!c || !video.paused || !transcodeVod || c.offline) return;
    const tc = transcodeUrl(streamOf(c));
    if (tc) fetch(tc.replace('/hls?', '/hls/pause?'), { keepalive: true }).catch(() => {});
  }, 2500);
}
const seekRange = () => { try { const s = video.seekable; return s.length ? { start: s.start(0), end: s.end(s.length - 1) } : null; } catch { return null; } };
function showControls() {
  const ctl = $('#ctl');
  if (!ctl || state.settings.nativeControls || !state.playing) return;
  ctl.hidden = false;
  updateControls();
  clearTimeout(ctlHide);
  if (!video.paused) ctlHide = setTimeout(hideControls, 3500);
}
function hideControls() { const ctl = $('#ctl'); if (ctl) ctl.hidden = true; }
function updateControls() {
  const ctl = $('#ctl');
  if (!ctl || ctl.hidden) return;
  const c = nowChannel();
  if (!c) return;
  const live = isLiveChannel(c) && !Number.isFinite(video.duration);
  const r = seekRange();
  const total = live ? 0 : vodTotal();
  const pos = live ? video.currentTime : vodPos();
  const behind = live && r ? Math.max(0, r.end - video.currentTime) : 0;
  const atLive = !live || behind < 10;
  ctl.querySelector('[data-c="play"]').innerHTML = icon(video.paused ? 'play' : 'pause');
  ctl.querySelector('[data-c="mute"]').innerHTML = icon(video.muted ? 'volume-off' : 'volume');
  $('#ctl-name').textContent = niceName(c);
  const g = isLiveChannel(c) && guideFor(c), nn = g && g.nowNext(c), now = nn?.now;
  $('#ctl-prog').textContent = now ? `${now[2]} · ends ${fmtTime(now[1])}` : [qualityLabel(), c.group ? prettyGroup(c.group) : ''].filter(Boolean).join(' · ');
  $('#ctl-pct').style.width = now ? `${(((Date.now() - now[0]) / (now[1] - now[0])) * 100).toFixed(1)}%` : '0';
  $('.ctl-pbar').hidden = !now;
  const seek = $('#ctl-seek');
  if (!seek.matches(':active')) {
    if (live) { if (r && r.end > r.start) seek.value = Math.round(((video.currentTime - r.start) / (r.end - r.start)) * 1000); }
    else if (total) seek.value = Math.round((pos / total) * 1000);
    seek.style.setProperty('--p', `${seek.value / 10}%`);   // the filled part of the custom track
  }
  $('#ctl-time').textContent = live ? (atLive ? '' : `-${fmtDur(behind)}`) : `${fmtDur(pos)} / ${fmtDur(total)}`;
  const lv = $('#ctl-live');
  lv.hidden = !live;
  lv.classList.toggle('on', atLive);
  ctl.querySelector('[data-c="prev"]').disabled = !state.playing || state.playing.index === 0;
  ctl.querySelector('[data-c="next"]').disabled = !state.playing || state.playing.index >= state.playing.list.length - 1;
}
// Full screen toggles: the stage (video + our controls) on desktop, iOS's own player on iPhone.
function toggleFullscreen() {
  const stage = $('.stage');
  if (document.fullscreenElement || document.webkitFullscreenElement) return (document.exitFullscreen || document.webkitExitFullscreen)?.call(document);
  if (stage.requestFullscreen) return stage.requestFullscreen().catch(() => {});
  if (stage.webkitRequestFullscreen) return stage.webkitRequestFullscreen();
  if (video.webkitEnterFullscreen) video.webkitEnterFullscreen();   // iPhone: Apple's player
}
let mouseIdle = 0;
function bindControls() {
  const ctl = $('#ctl'), stage = $('.stage');
  // Moving the mouse over the video wakes the controls (and the cursor); they fade again when it stops.
  stage.addEventListener('pointermove', e => {
    if (e.pointerType !== 'mouse' || !state.playing || state.settings.nativeControls) return;
    if (ctl.hidden) showControls(); else { clearTimeout(ctlHide); if (!video.paused) ctlHide = setTimeout(hideControls, 3500); }
    stage.classList.remove('idle');
    clearTimeout(mouseIdle);
    mouseIdle = setTimeout(() => { if (ctl.hidden) stage.classList.add('idle'); }, 3600);
  });
  stage.addEventListener('pointerleave', () => { if (!video.paused) { clearTimeout(ctlHide); ctlHide = setTimeout(hideControls, 1200); } });
  document.addEventListener('fullscreenchange', () => { stage.classList.toggle('fs', !!document.fullscreenElement); showControls(); });
  for (const b of ctl.querySelectorAll('[data-c]')) if (b.dataset.c !== 'live') b.innerHTML = icon({ list: 'list', mute: 'volume', airplay: 'airplay', pip: 'pip', full: 'expand', more: 'more', prev: 'prev', next: 'next', play: 'pause', back30: 'back30' }[b.dataset.c]);
  if (!video.webkitShowPlaybackTargetPicker) ctl.querySelector('[data-c="airplay"]').hidden = true;
  if (!(document.pictureInPictureEnabled || video.webkitSupportsPresentationMode?.('picture-in-picture'))) ctl.querySelector('[data-c="pip"]').hidden = true;
  stage.addEventListener('click', e => {
    if (e.target.closest('.ctl button, #ctl-seek') || !state.playing || state.settings.nativeControls) return;
    ctl.hidden ? showControls() : hideControls();
  });
  ctl.addEventListener('click', e => {
    const b = e.target.closest('[data-c]');
    if (!b) return;
    e.stopPropagation();
    const r = seekRange();
    switch (b.dataset.c) {
      case 'play': video.paused ? video.play().catch(() => {}) : video.pause(); break;
      case 'prev': step(-1); break;
      case 'next': step(1); break;
      case 'back30': if (isLiveChannel(nowChannel())) video.currentTime = Math.max(r?.start ?? 0, video.currentTime - 30); else seekVod(vodPos() - 30); if (video.paused) video.play().catch(() => {}); break;
      case 'live': if (r) video.currentTime = Math.max(r.start, r.end - 3); video.play().catch(() => {}); break;
      case 'list': channelsOverlay(); break;
      case 'mute': video.muted = !video.muted; break;
      case 'airplay': try { video.webkitShowPlaybackTargetPicker(); } catch {} break;
      case 'pip': player.togglePip?.().catch(e => toast(e.message)); break;
      case 'full': toggleFullscreen(); break;
      case 'more': playerMenu(); break;
    }
    showControls();
  });
  const seek = $('#ctl-seek');
  seek.addEventListener('input', () => {
    if (isLiveChannel(nowChannel())) { const r = seekRange(); if (r) video.currentTime = r.start + (seek.value / 1000) * (r.end - r.start); }
    showControls();
  });
  seek.addEventListener('input', () => seek.style.setProperty('--p', `${seek.value / 10}%`));
  seek.addEventListener('change', () => { if (!isLiveChannel(nowChannel())) { const total = vodTotal(); if (total) seekVod((seek.value / 1000) * total); } });
  video.addEventListener('pause', () => { showControls(); clearTimeout(ctlHide); releaseOnPause(); });
  video.addEventListener('play', () => { clearTimeout(pauseTimer); showControls(); });
  setInterval(() => {
    updateControls();
    // While paused or behind live, keep the server's stream alive so the buffer is there when you resume.
    const c = nowChannel();
    if (c && !c.offline && isLiveChannel(c) && !video.paused && seekRange() && seekRange().end - video.currentTime > 10 && Date.now() - keepAlive > 8000) {
      keepAlive = Date.now();
      const tc = transcodeUrl(streamOf(c));
      if (tc) fetch(tc.replace('/hls?', '/hls/warm?'), { keepalive: true }).catch(() => {});
    }
  }, 1000);
}

// Desktop keyboard: ↑/↓ or PageUp/PageDown change channel, Space pauses, M mutes, F full screen,
// L channel list, / focuses search, Esc closes, typing digits zaps by channel number.
let zapBuf = '', zapTimer = 0;
function bindKeys() {
  document.addEventListener('keydown', e => {
    const typing = e.target.matches('input, textarea, select');
    const sheetOpen = !$('#sheet').hidden;
    if (e.key === 'Escape') { if (sheetOpen) closeSheet(); else if (state.playing && !typing) stopPlaying(); return; }
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k' && matchMedia('(min-width: 900px)').matches) { e.preventDefault(); $('#tq')?.focus(); $('#tq')?.select(); return; }
    if (typing || sheetOpen || e.metaKey || e.ctrlKey || e.altKey) return;
    if (e.key === '/') { e.preventDefault(); ($('#q') || (matchMedia('(min-width: 900px)').matches ? $('#tq') : $('#home-q')) || $('#gq'))?.focus(); return; }
    if (/^\d$/.test(e.key) && (state.playing || state.current)) {
      zapBuf += e.key; clearTimeout(zapTimer);
      toast(`Channel ${zapBuf}…`);
      zapTimer = setTimeout(() => {
        const n = +zapBuf; zapBuf = '';
        const list = state.playing?.list || state.filtered;
        const i = list.findIndex(c => c.num === n);
        if (i >= 0) playChannel(list, i); else toast(`No channel ${n}`);
      }, 900);
      return;
    }
    if (!state.playing) return;
    switch (e.key) {
      case 'ArrowUp': case 'PageUp': e.preventDefault(); step(-1); break;
      case 'ArrowDown': case 'PageDown': e.preventDefault(); step(1); break;
      case ' ': case 'k': e.preventDefault(); video.paused ? video.play().catch(() => {}) : video.pause(); showControls(); break;
      case 'ArrowLeft': e.preventDefault(); if (isLiveChannel(nowChannel())) video.currentTime = Math.max(seekRange()?.start ?? 0, video.currentTime - 30); else seekVod(vodPos() - 30); showControls(); break;
      case 'ArrowRight': e.preventDefault(); if (isLiveChannel(nowChannel())) { const r = seekRange(); if (r) video.currentTime = Math.min(r.end - 3, video.currentTime + 30); } else seekVod(vodPos() + 30); showControls(); break;
      case 'm': video.muted = !video.muted; showControls(); break;
      case 'f': toggleFullscreen(); break;
      case 'l': channelsOverlay(); break;
    }
  });
  // double-click the video = full screen (desktop)
  video.addEventListener('dblclick', toggleFullscreen);
}

function setStatus(text, isError = false) {
  const el = $('#now-status');
  const c = nowChannel();
  statusError = isError;
  if (!text && c) {
    const g = isLiveChannel(c) && guideFor(c);
    const now = g && g.nowNext(c).now;
    text = now ? `${now[2]} · ${fmtRange(now)}` : isLiveChannel(c) ? prettyGroup(c.group) || '' : vodLine(c);
    if (state.sleepAt) text = `${text ? `${text} · ` : ''}Sleep in ${Math.ceil((state.sleepAt - Date.now()) / MINUTE)} min`;
  }
  el.textContent = text || '';
  el.classList.toggle('error', isError);
}

let lastProgressSave = 0;
function trackProgress(force = false) {
  const c = nowChannel();
  if (!c || !c.kind || c.kind === 'live' || c.kind === 'catchup') return;
  const d = vodTotal();
  if (!Number.isFinite(d) || d < 60) return;
  if (!force && Date.now() - lastProgressSave < 5000) return;
  lastProgressSave = Date.now();
  writeProgress(c, vodPos(), d);
}
function writeProgress(c, t, d) {
  state.progress[c.pkey || c.url] = { t, d, v: 3, at: Date.now(), done: t / d > 0.93, item: { id: c.id, kind: c.kind, name: c.name, url: c.pkey || c.url, logo: c.logo, group: c.group, src: c.src } };
  const entries = Object.entries(state.progress);
  if (entries.length > MAX_PROGRESS) state.progress = Object.fromEntries(entries.sort((a, b) => b[1].at - a[1].at).slice(0, MAX_PROGRESS));
  db.set('progress', state.progress).catch(() => {});
  syncLater();
}

function bindPlayer() {
  // Swipe up/down on the video for the next/previous channel (inline only: iOS owns full-screen gestures).
  let sx = 0, sy = 0, st = 0;
  const surface = video.parentElement;
  surface.addEventListener('touchstart', e => { const t = e.touches[0]; sx = t.clientX; sy = t.clientY; st = Date.now(); }, { passive: true });
  surface.addEventListener('touchend', e => {
    const t = e.changedTouches[0], dx = t.clientX - sx, dy = t.clientY - sy;
    if (e.touches.length || Date.now() - st > 600 || Math.abs(dy) < 70 || Math.abs(dx) > Math.abs(dy) / 2) return;
    if (state.playing?.list.length > 1 && player.isLive) step(dy < 0 ? 1 : -1);
  }, { passive: true });
  video.addEventListener('waiting', () => { if (state.playing) { setStatus(vodEnc && video.currentTime < 1 ? 'Converting for this device…' : 'Buffering…'); noteStall(); } });
  video.addEventListener('playing', () => { setStatus(''); hideTuning(); showControls(); });
  video.addEventListener('timeupdate', () => trackProgress());
  video.addEventListener('pause', () => trackProgress(true));
  video.addEventListener('ended', () => {
    trackProgress(true);
    const pl = state.playing;
    if (pl && nowChannel()?.kind === 'episode' && pl.index < pl.list.length - 1) showNextUp(pl.list[pl.index + 1]);
  });
  video.addEventListener('timeupdate', updateSkipIntro);
  video.addEventListener('seeking', learnIntro);
  // One silent retry on a mid-stream fault (provider blip, segment timeout) before showing the error.
  player.onFatal = msg => {
    const c = nowChannel();
    if (c && !retried.has(c.url)) { retried.add(c.url); setStatus('Reconnecting…'); setTimeout(() => { if (nowChannel() === c) playChannel(state.playing.list, state.playing.index); }, 1200); return; }
    if (c && nextBackup(c)) { setStatus('Trying a backup stream…'); setTimeout(() => { if (nowChannel() === c) playChannel(state.playing.list, state.playing.index); }, 300); return; }
    setStatus(msg, true);
  };
  // No picture: sound plays and the clock runs but the video never gets a frame (a codec this device can't
  // decode). Switch that channel/title to server-side H.264 conversion and play again.
  // A slow feed (sound arriving before picture, buffering) is NOT this: only convert once the clock has run
  // at least 6s with sound playing and the video still has no size and no decoded frames.
  let blankSince = 0, blankFrom = 0;
  setInterval(() => {
    const c = nowChannel();
    if (!c || video.paused || c.offline || video.readyState < 3 || Date.now() - lastTune < 8000) { blankSince = 0; return; }
    const q = video.getVideoPlaybackQuality?.();
    if (video.videoWidth > 0 || (q && q.totalVideoFrames > 5)) { blankSince = 0; return; }
    if (!blankSince) { blankSince = Date.now(); blankFrom = video.currentTime; return; }
    if (Date.now() - blankSince < 8000 || video.currentTime - blankFrom < 6) return;
    blankSince = 0;
    if (isLiveChannel(c) ? liveEnc.has(c.url) : vodEnc) return;   // already converting: nothing more we can do
    // Safari never converts: first the same stream untouched in fMP4 (how Apple takes HEVC), then a choice.
    if (IS_SAFARI) {
      if (isLiveChannel(c) && !liveFmp4.has(c.url)) { liveFmp4.add(c.url); toast('Switching to Safari’s HEVC format…'); playChannel(state.playing.list, state.playing.index); return; }
      showPlayerError(c, new Error('Safari can’t show this stream’s picture. VLC plays it untouched.'));
      return;
    }
    if (isLiveChannel(c)) liveEnc.add(c.url); else vodEnc = true;
    toast('No picture from this stream: converting it for your device…');
    playChannel(state.playing.list, state.playing.index);
  }, 1000);
  // Feed health: through our server the live feed's speed is measured (seconds of video per second). Two slow
  // readings in a row (under 0.9x: it will stall) -> switch to another copy of the channel before it freezes.
  let slowCount = 0, switchedAt = 0, healthFor = '';
  setInterval(async () => {
    const c = nowChannel();
    if (!c || !state.playing || video.paused || document.hidden || !isLiveChannel(c) || isNative) { slowCount = 0; return; }
    const url = streamOf(c), tc = transcodeUrl(url);
    if (!tc || /\.m3u8(\?|#|$)/i.test(url)) return;
    if (healthFor !== url) { healthFor = url; slowCount = 0; }
    let speed = null;
    try { const r = await fetch(tc.replace('/hls?', '/hls/health?'), { cache: 'no-store' }); if (r.ok) speed = (await r.json()).speed; } catch {}
    if (speed === null || nowChannel() !== c) return;
    slowCount = speed < 0.9 ? slowCount + 1 : 0;
    if (slowCount < 2 || Date.now() - switchedAt < 60_000) return;
    const others = liveCopies(c).filter(u => u !== url);
    if (!others.length) { if (slowCount === 2) toast(`This channel's feed is slow (${speed}×) and has no other copy to switch to`); return; }
    slowCount = 0; switchedAt = Date.now();
    c.alts = others; backupIdx.set(c, 0);   // try the other copies in order, from the first
    if (!nextBackup(c)) return;
    toast('Feed was slowing down: switched to another copy');
    playChannel(state.playing.list, state.playing.index);
  }, 8000);
  // Stall watchdog: live video whose clock hasn't advanced for 12s while it should be playing -> reload the stream.
  let lastT = 0, lastTick = Date.now();
  setInterval(() => {
    if (!state.playing || video.paused || document.hidden) { lastT = video.currentTime; lastTick = Date.now(); return; }
    if (video.currentTime !== lastT) { lastT = video.currentTime; lastTick = Date.now(); return; }
    if (Date.now() - lastTick > 12_000) { lastTick = Date.now(); setStatus('Reconnecting…'); playChannel(state.playing.list, state.playing.index); }
  }, 3000);
  // Back from the background after a while: a live stream will be dead, so restart it instead of spinning.
  let hiddenAt = 0;
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { hiddenAt = Date.now(); return; }
    if (state.playing && hiddenAt && Date.now() - hiddenAt > 45_000 && player.isLive && isLiveChannel(nowChannel())) playChannel(state.playing.list, state.playing.index);
  });
  $('#prev').innerHTML = icon('prev');
  $('#next').innerHTML = icon('next');
  $('#now-more').innerHTML = icon('more');
  $('#close').innerHTML = icon('close');
  $('#prev').onclick = () => step(-1);
  $('#next').onclick = () => step(1);
  // warm the neighbour on touch-down so the ffmpeg start overlaps the tap
  $('#prev').onpointerdown = () => { const p = state.playing; if (p) warm(p.list[p.index - 1]?.url); };
  $('#next').onpointerdown = () => { const p = state.playing; if (p) warm(p.list[p.index + 1]?.url); };
  $('#close').onclick = stopPlaying;
  $('#now-more').onclick = playerMenu;
  bindControls();
  $('#now-list').innerHTML = icon('list');
  $('#now-list').onclick = channelsOverlay;
  $('.now').onclick = channelsOverlay;
  setInterval(() => { if (state.playing && !statusError) setStatus(''); }, 30000);
  try {
    navigator.mediaSession?.setActionHandler('previoustrack', () => step(-1));
    navigator.mediaSession?.setActionHandler('nexttrack', () => step(1));
  } catch {}
}

function playerMenu() {
  const c = nowChannel();
  if (!c) return;
  const fileAudio = transcodeVod && vodInfo?.audio?.length > 1 ? vodInfo.audio : null;
  const fileSubs = transcodeVod ? (vodInfo?.subs || []).filter(s => s.text) : [];
  const audio = fileAudio ? fileAudio.map(t => ({ i: t.i, label: `${t.label}${t.channels > 2 ? ` · ${t.channels}ch` : ''}`, active: t.i === vodTrack })) : player.audioTracks();
  const subs = fileSubs.length ? fileSubs.map(t => ({ i: t.i, label: t.label, active: subIdx === t.i })) : player.subtitleTracks();
  const hasGuide = isLiveChannel(c) && guideFor(c)?.list(c).length;
  const g = hasGuide && guideFor(c);
  const now = g && g.nowNext(c).now;
  menu(c.name, [
    now && canCatchUp(c, now) && { label: `Watch “${now[2]}” from the start`, fn: () => playCatchup(c, now) },
    hasGuide && { label: archiveDays(c) ? 'TV guide & catch-up' : 'TV guide', fn: () => scheduleSheet(c) },
    audio.length > 1 && { label: 'Audio track…', fn: () => menu('Audio', audio.map(t => ({ label: `${t.active ? '✓ ' : ''}${t.label}`, fn: () => fileAudio ? switchAudioTrack(t.i) : player.setAudioTrack(t.i) }))) },
    subs.length > 0 && { label: 'Subtitles…', fn: () => menu('Subtitles', [
      { label: subs.some(t => t.active) ? 'Off' : '✓ Off', fn: () => fileSubs.length ? useSubtitles(-1) : player.setSubtitleTrack(-1) },
      ...subs.map(t => ({ label: `${t.active ? '✓ ' : ''}${t.label}`, fn: () => fileSubs.length ? useSubtitles(t.i) : player.setSubtitleTrack(t.i) })),
    ]) },
    transcodeUrl(c.url) && { label: `Audio sync${avSyncFor(c.url) ? ` (${avSyncFor(c.url) > 0 ? '+' : ''}${avSyncFor(c.url) / 1000}s)` : ''}…`, fn: () => avSyncMenu(c) },
    Number.isFinite(video.duration) && { label: `Speed (${video.playbackRate}×)…`, fn: () => menu('Playback speed', [0.75, 1, 1.25, 1.5, 2].map(r => ({ label: `${video.playbackRate === r ? '✓ ' : ''}${r}×`, fn: () => { video.playbackRate = r; } }))) },
    { label: state.sleepAt ? `Sleep timer (${Math.ceil((state.sleepAt - Date.now()) / MINUTE)} min left)…` : 'Sleep timer…', fn: sleepMenu },
    { label: 'Picture in Picture', fn: () => player.togglePip().catch(e => toast(e.message)) },
    !isLiveChannel(c) && !c.offline && canSync() && { label: 'Download for offline…', fn: () => downloadMenu(c) },
    isNative && { label: 'Play in iPhone player (full screen)', fn: () => openNativePlayer(c) },
    isLiveChannel(c) && { label: 'Watch in Multi-view', fn: () => addToMulti(c) },
    { label: favUrls.has(c.url) ? 'Remove from Favorites' : 'Add to Favorites', fn: () => toggleFavorite(c, null).then(highlightStars) },
    ...externalItems(c),
  ]);
}

function sleepMenu() {
  menu('Sleep timer', [
    ...[15, 30, 45, 60, 90, 120].map(m => ({ label: `${m} minutes`, fn: () => setSleep(m) })),
    state.sleepAt && { label: 'Turn off', fn: () => setSleep(0) },
  ]);
}

function setSleep(minutes) {
  clearTimeout(state.sleepTimer);
  state.sleepAt = minutes ? Date.now() + minutes * MINUTE : 0;
  if (minutes) {
    state.sleepTimer = setTimeout(() => { state.sleepAt = 0; stopPlaying(); toast('Sleep timer: playback stopped'); }, minutes * MINUTE);
  }
  setStatus('');
  toast(minutes ? `Stopping in ${minutes} minutes` : 'Sleep timer off');
}

function step(delta) {
  const pl = state.playing;
  if (!pl) return;
  const i = pl.index + delta;
  if (i >= 0 && i < pl.list.length) playChannel(pl.list, i);
}

const retried = new Set();   // channels that already got their one silent retry
// Set-top-box style banner while a channel starts: instant feedback, and no black screen.
function showTuning(c) {
  const el = $('#tuning'); if (!el) return;
  el.innerHTML = `${logoHtml(c)}<span class="tuning-name">${esc(niceName(c))}</span><span class="muted">${esc(prettyGroup(c.group) || '')}</span>`;
  el.hidden = false; el.classList.remove('out');
}
function hideTuning() { const el = $('#tuning'); if (el && !el.hidden) { el.classList.add('out'); setTimeout(() => { el.hidden = true; }, 320); } }
// ---------- errors reported to your server's log ----------
const reported = new Set();
function report(kind, message, where = '') {
  const key = `${kind}|${message}`;
  if (!canSync() || reported.has(key) || reported.size > 40) return;
  reported.add(key);
  fetch(serverApi('log'), { method: 'POST', keepalive: true, body: JSON.stringify({ kind, message: String(message).slice(0, 300), where }) }).catch(() => {});
}
addEventListener('error', e => report('script', e.message, `${(e.filename || '').split('/').pop()}:${e.lineno}`));
addEventListener('unhandledrejection', e => report('promise', e.reason?.message || String(e.reason)));

// ---------- native iPhone app: the VLC player ----------
// In the native app nothing goes through the server's converter: the VLC engine plays the provider's
// .ts / .mkv / HEVC directly. The web app keeps the list; the player reports progress and channel steps.
function playNative(list, index, c) {
  state.playing = { list, index };
  highlightPlaying();
  addRecent(c);
  const live = isLiveChannel(c);
  const g = live && guideFor(c), now = g && g.nowNext(c).now;
  if (live) ensureGuide([c]);
  const pr = state.progress[c.pkey || c.url];
  const start = !live && pr && !pr.done && pr.t > 30 ? pr.t : 0;
  nativeVlc({
    url: c.url, title: niceName(c), live, start,
    subtitle: now ? `${now[2]} · ends ${fmtTime(now[1])}` : live ? prettyGroup(c.group) || '' : vodLine(c),
    info: now ? now[3] || '' : c.plot || '',
    headers: headersFromOpts(c.opts), hasPrev: index > 0, hasNext: index < list.length - 1, art: c.logo || '',
    ...nativeList(list, index, live),
  }).catch(e => toast(e.message || 'Couldn’t open the player'));
}
// An episode's own name: "Clean Entry" from "EN - S.W.A.T. Exiles (2026) (US) - S01E01 - Clean Entry" ('' when the file has none).
function epTitle(name) {
  const t = String(name || '').split(/\bS\d+\s*E\d+\s*[-–:]?\s*/i).pop().trim();
  return t && t !== String(name).trim() ? t : '';
}
// Second line under a film / episode title in the player: the episode's own name, or rating and length.
// Never the provider's group ("EN - NETFLIX MOVIES"): that's filing, not information.
function vodLine(c) {
  const ep = c.kind === 'episode' ? epTitle(c.name) : '';
  const bits = [ep, c.rating && c.rating < 9.5 ? `★ ${Number(c.rating).toFixed(1)}` : '', c.duration || ''];
  return bits.filter(Boolean).join(' · ');
}
// The player's list button: up to 300 episodes / channels around the one playing (a full channel list can be thousands).
function nativeList(list, index, live) {
  if (list.length < 2) return {};
  const from = Math.max(0, Math.min(index - 150, list.length - 300)), part = list.slice(from, from + 300);
  const row = x => {
    if (x.kind === 'episode') {
      const m = vodMeta(x.name), ep = epTitle(x.name);
      const n = m.episode ?? x.num;
      return { t: n != null ? `${n}. ${ep || `Episode ${n}`}` : x.name, s: [m.season ? `Season ${m.season}` : '', x.duration || ''].filter(Boolean).join(' · ') };
    }
    if (isLiveChannel(x)) { const g = guideFor(x), now = g && g.nowNext(x).now; return { t: x.name, s: now ? now[2] : prettyGroup(x.group) || '' }; }
    return { t: niceName(x), s: vodLine(x) };
  };
  return { list: part.map(row), listStart: from, current: index, listTitle: live ? 'Channels' : list[index]?.kind === 'episode' ? 'Episodes' : 'Up next' };
}
window.__nativePlayer = ev => {
  const pl = state.playing, c = pl && pl.list[pl.index];
  if (!ev || !c) return;
  if (ev.type === 'step') { const i = pl.index + ev.dir; if (i >= 0 && i < pl.list.length) playChannel(pl.list, i); }
  if (ev.type === 'jump' && ev.index >= 0 && ev.index < pl.list.length && ev.index !== pl.index) playChannel(pl.list, ev.index);
  if ((ev.type === 'progress' || ev.type === 'closed') && !isLiveChannel(c) && c.kind !== 'catchup' && ev.d >= 60) writeProgress(c, ev.t, ev.d);
  // 'ended': the player itself counts down "Next episode in 10" and then asks for the next one (a 'step').
  if (ev.type === 'closed') { state.playing = null; highlightPlaying(); if (!state.current) rerender(); }
  if (ev.type === 'error') report('native-play', `${ev.message}: ${c.name}`, isLiveChannel(c) ? 'live' : c.kind);
};

// Live 4K first: "Sky Sports F1" and "Sky Sports F1 ᵁᴴᴰ ᴴᴰᴿ" are the same channel. Where the device plays 4K HEVC untouched
// (Safari, the iPhone app), start the 4K copy; the copy you picked stays as its backup.
const IS_UHD = /\b(4k|uhd|2160p?|3840p?)\b|ᵁᴴᴰ/i;
const liveBase = name => String(name || '').replace(/[ʰ-˿ᴬ-ᵫ⁰-₟]+/g, ' ').replace(/\[[^\]]*\]|\([^)]*\)/g, ' ')
  .replace(/\b(4k|uhd|hdr|fhd|hd|sd|2160p?|3840p?|1080p?|720p?|backup|raw|hevc)\b/gi, ' ').toLowerCase().replace(/[^a-z0-9:]+/g, '');
const uhdIndex = new WeakMap();   // playlist -> its live channels by base name (never saved with the playlist)
function liveIndex(p) {
  let idx = uhdIndex.get(p);
  if (!idx || idx.n !== p.channels.length) {
    idx = { n: p.channels.length, uhd: new Map(), all: new Map() };
    for (const x of (m3uKinds(p)?.live || p.channels)) {
      if (/^#+|#+$/.test(x.name || '')) continue;   // "##### PPV #####" separators
      const k = liveBase(x.name);
      if (!k) continue;
      if (IS_UHD.test(x.name || '') && !idx.uhd.has(k)) idx.uhd.set(k, x);
      const l = idx.all.get(k); if (l) l.push(x); else idx.all.set(k, [x]);
    }
    uhdIndex.set(p, idx);
  }
  return idx;
}
// Other copies of a live channel to fall back to: its merged backups, then same-name channels ("(Backup)", 4K/HD).
function liveCopies(c) {
  const p = playlistOf(c) || state.playlists.find(p => p.channels.some(x => x.url === c.url));
  const same = p ? (liveIndex(p).all.get(liveBase(c.name)) || []).map(x => x.url) : [];
  return [...new Set([...(c.alts || []), ...same])].filter(u => u !== c.url);
}
function uhdCopy(c) {
  if (!isLiveChannel(c) || IS_UHD.test(c.name || '') || state.settings.prefer4k === false || !(IS_SAFARI || isNative || isTV)) return null;
  const p = playlistOf(c) || state.playlists.find(p => p.channels.some(x => x.url === c.url));
  if (!p) return null;
  const u = liveIndex(p).uhd.get(liveBase(c.name));
  return u && u.url !== c.url ? u : null;
}
function playChannel(list, index) {
  let c = list[index];
  if (!c) return;
  const up = !c._fromUhd && uhdCopy(c);
  if (up) {
    list = list.slice();
    c = list[index] = { ...up, name: c.name, logo: c.logo || up.logo, group: c.group, num: c.num, src: c.src, _fromUhd: true, alts: [c.url, ...(c.alts || [])] };
    toast('Playing the 4K version');
  }
  if ((isNative || isTV) && !c.offline) return playNative(list, index, c);   // iPhone: VLC player; Shield: ExoPlayer
  // "Play films & series in VLC / Infuse / IINA": hand the provider's link straight to that app.
  const choice = isLiveChannel(c) ? state.settings.livePlayer : state.settings.vodPlayer;
  if (!c.offline && choice && choice !== 'app' && !/^blob:/.test(c.url)) {
    const ext = externalPlayers(c.url).find(x => x.name.toLowerCase() === choice);
    if (ext) { if (!ext.file) toast(`Opening in ${ext.name}…`); openExternal(ext, c); return; }
  }
  trackProgress(true);
  const prev = nowChannel();
  state.playing = { list, index };
  if (dlActive && !c.offline) { const m = state.downloads[dlActive.id]; if (m && !m.done) toast('Your download pauses while you watch and resumes afterwards'); }
  $('#player').hidden = false;
  document.body.classList.add('playing');
  document.body.classList.toggle('vod-playing', !isLiveChannel(c));   // films: wide "cinema" layout, not the live dock
  $('#player-error').hidden = true;
  $('#now-name').textContent = niceName(c);
  $('#now-logo').outerHTML = logoHtml(c, 'id="now-logo"');
  $('#prev').disabled = index === 0;
  $('#next').disabled = index === list.length - 1;
  if (location.hash.startsWith('#/guide')) sizeGuide();
  setStatus('Connecting…');
  showTuning(c);
  highlightPlaying();
  addRecent(c);
  ensureGuide([c]);
  renderEpgPanel();
  renderVodPanel(c);
  tuneFrom = prev;
  try {
    if ('mediaSession' in navigator) {
      navigator.mediaSession.metadata = new MediaMetadata({ title: niceName(c), artist: isLiveChannel(c) ? prettyGroup(c.group) || 'Live TV' : vodLine(c) || 'M3U Player', artwork: c.logo ? [{ src: c.logo }] : [] });
    }
  } catch {}
  clearTimeout(tuneTimer);
  const settleMs = Date.now() - lastTune < 600 ? 350 : 0;   // rapid skipping: wait for the user to settle
  lastTune = Date.now();
  tuneTimer = setTimeout(() => tune(c, list, index), settleMs);
}
let tuneTimer = 0, lastTune = 0, tuneFrom = null;
// Backup feeds of a merged channel: which one is in use this session (0 = the main url).
const backupIdx = new WeakMap();
const streamOf = c => (backupIdx.get(c) ? c.alts[backupIdx.get(c) - 1] : c.url);
function nextBackup(c) {
  const i = backupIdx.get(c) || 0;
  if (!c.alts || i >= c.alts.length) { backupIdx.delete(c); return false; }
  backupIdx.set(c, i + 1);
  retried.delete(c.url);
  return true;
}
// VOD: ask the server what the file is first (cached, well under a second), so a codec the device can't
// decode is converted from the first segment instead of playing as a black screen.
async function tuneVod(c, list, index) {
  const tc = transcodeUrl(c.url);
  try {
    const r = await Promise.race([fetch(tc.replace('/hls?', '/hls/info?')), new Promise((_, rej) => setTimeout(() => rej(new Error('slow')), 6000))]);
    const d = await r.json();
    if (nowChannel() !== c) return;
    vodInfo = d;
    vodEnc = !canDecode(d.video);
    if (vodEnc) { setStatus('Converting for this device… (first picture can take 10–20 s)'); showTuning(c); }
  } catch { vodInfo = vodInfo || { duration: 0, audio: [], subs: [] }; }
  if (!vodInfo) vodInfo = { duration: 0, audio: [], subs: [] };
  if (nowChannel() !== c) return;
  updateControls(); renderVodPanel(c);
  tune(c, list, index);
}
function tune(c, list, index) {
  if (nowChannel() !== c) return;
  transcodeVod = !isLiveChannel(c);
  player.vod = transcodeVod;
  if (vodInfoFor !== c.url) { vodTrack = 0; vodInfo = null; vodEnc = false; clearSubtitles(); vodInfoFor = c.url; }   // a different title: back to its default track
  if (transcodeVod && !vodInfo && transcodeUrl(c.url)) return tuneVod(c, list, index);   // tuneVod always sets vodInfo, so this runs once
  const pr = state.progress[c.pkey || c.url];
  const resumeAt = pr && pr.v === 3 && !pr.done && pr.t > 30 && pr.t < (pr.d || Infinity) - 60 ? pr.t : 0;   // only positions saved by the current VOD logic
  if (tuneFrom && tuneFrom.url !== c.url) unwarm(streamOf(tuneFrom));   // free the single provider slot right before connecting
  tuneFrom = null;
  player.play(streamOf(c)).then(ok => {
    if (!ok) return;
    setStatus('');
    setTimeout(() => { if (nowChannel() === c && !video.paused && video.currentTime > 2) retried.delete(c.url); }, 15_000);   // healthy again: allow a future retry
    if (resumeAt && transcodeVod) {
      seekVod(resumeAt);
      toast(`Resumed at ${fmtDur(resumeAt)}`, { action: 'Start over', fn: () => seekVod(0) });
    }
  }).catch(err => {
    if (nowChannel() !== c) return;
    if (!retried.has(c.url)) { retried.add(c.url); setStatus('Retrying…'); setTimeout(() => { if (nowChannel() === c) playChannel(list, index); }, 1500); return; }
    if (nextBackup(c)) { setStatus('Trying a backup stream…'); setTimeout(() => { if (nowChannel() === c) playChannel(list, index); }, 300); return; }
    showPlayerError(c, err);
    // The iPhone's own player handles streams the web view can't (http://, custom headers).
    if (isNative) { toast('Opening in the iPhone player'); openNativePlayer(c); }
  });
}

function openNativePlayer(c) {
  if (nowChannel() === c) stopPlaying();
  addRecent(c);
  nativePlay(c).catch(e => toast(e.message || 'Couldn’t open the iPhone player'));
}

function showPlayerError(c, err) {
  report('play', `${err.message}: ${c.name}`, `${isLiveChannel(c) ? 'live' : c.kind}${transcodeVod ? ` vod enc=${vodEnc}` : ''}`);
  const box = $('#player-error');
  const extra = isMixed(c.url) && !net.proxy ? ' This is an http:// stream; set up the free proxy in Settings to play it here.' : '';
  box.innerHTML = `<p>${esc(err.message + extra)}</p>
    <div class="player-error-actions">
      ${isNative ? '<button class="btn small primary" data-native>iPhone player</button>' : ''}
      ${externalPlayers(c.url).map((x, i) => `<a class="btn small" href="${esc(x.href)}" data-ext="${i}">Open in ${x.name}</a>`).join('')}
      <button class="btn small" data-copy>Copy link</button>
      <button class="btn small" data-retry>Retry</button>
    </div>`;
  box.hidden = false;
  box.querySelector('[data-copy]').onclick = () => copyText(c.url, 'Link copied');
  box.querySelectorAll('[data-ext]').forEach(a => { a.onclick = e => { const x = externalPlayers(c.url)[+a.dataset.ext]; if (x?.file) { e.preventDefault(); openExternal(x, c); } }; });
  box.querySelector('[data-native]')?.addEventListener('click', () => openNativePlayer(c));
  box.querySelector('[data-retry]').onclick = () => playChannel(state.playing.list, state.playing.index);
  setStatus("Couldn't play this stream", true);
}

function stopPlaying() {
  trackProgress(true);
  hideControls();
  const panel = $('#epg-panel'); if (panel) { panel.hidden = true; panel.innerHTML = ''; }
  const vp = $('#vod-panel'); if (vp) { vp.hidden = true; vp.innerHTML = ''; }
  hideNextUp();
  const c = nowChannel();
  if (c) unwarm(streamOf(c));   // free the provider connection now, not after the idle timeout
  player.stop();
  state.playing = null;
  $('#player').hidden = true;
  document.body.classList.remove('playing', 'vod-playing');
  highlightPlaying();
  if (location.hash.startsWith('#/guide')) sizeGuide();
}

async function addRecent(c) {
  if (c.kind === 'catchup') return;
  state.recents = [{ ...cloneChannel(c), at: Date.now() }, ...state.recents.filter(r => r.url !== c.url)].slice(0, MAX_RECENTS);
  await db.set('recents', state.recents);
  syncSoon();
}

init().catch(err => {
  view.innerHTML = `<div class="empty"><p><b>Couldn't start</b></p><p class="muted">${esc(err.message)}</p></div>`;
});

// ---------- Performance recorder (first 3 minutes after launch) ----------
// Finds what makes scrolling stutter on a real phone: every frame that freezes for >100ms is logged with what
// the app was doing just before (timed functions below) and whether the user was scrolling; a summary goes to
// the server log every 30s as "[client <device>] perf: …". Costs a few timestamps per call.
const perf = { start: performance.now(), busy: new Map(), last: null, freezes: [], lastScroll: 0, imgs: 0, sent: 0 };
function timed(name, fn) {
  return function (...args) {
    const s = performance.now();
    try { return fn.apply(this, args); } finally {
      const d = performance.now() - s;
      if (d > 4) { perf.busy.set(name, (perf.busy.get(name) || 0) + d); perf.last = { name, end: performance.now(), d }; }
    }
  };
}
showHome = timed('showHome', showHome); renderDiscover = timed('renderDiscover', renderDiscover); applyFilter = timed('applyFilter', applyFilter);
homeHub = timed('homeHub', homeHub); patchView = timed('patchView', patchView); ensureGuide = timed('ensureGuide', ensureGuide);
renderChips = timed('renderChips', renderChips); rerenderRows = timed('rerenderRows', rerenderRows); refreshPlaylistView = timed('refreshPlaylistView', refreshPlaylistView);
catalogueEntry = timed('catalogueEntry', catalogueEntry); m3uKinds = timed('m3uKinds', m3uKinds); cachedGroups = timed('cachedGroups', cachedGroups);
liveIndex = timed('liveIndex', liveIndex); renderMore = timed('renderMore', renderMore);
addEventListener('scroll', () => { perf.lastScroll = performance.now(); }, { passive: true, capture: true });
document.addEventListener('load', e => { if (e.target?.tagName === 'IMG') perf.imgs++; }, true);
(function watchFrames() {
  let prev = performance.now();
  const tick = now => {
    const gap = now - prev; prev = now;
    if (gap > 100 && !document.hidden) {
      const cause = perf.last && now - perf.last.end < gap + 50 ? `${perf.last.name} ${Math.round(perf.last.d)}ms` : 'unattributed';
      perf.freezes.push({ at: Math.round((now - perf.start) / 1000), gap: Math.round(gap), scrolling: now - perf.lastScroll < 600, cause });
    }
    if (now - perf.start < 180_000) requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
})();
const perfTimer = setInterval(() => {
  const elapsed = performance.now() - perf.start;
  if (canSync() && (perf.freezes.length || perf.busy.size)) {
    const f = perf.freezes.splice(0);
    const top = [...perf.busy].sort((a, b) => b[1] - a[1]).slice(0, 6).map(([k, v]) => `${k} ${Math.round(v)}ms`).join(', ');
    const worst = f.sort((a, b) => b.gap - a.gap).slice(0, 6).map(x => `${x.gap}ms@${x.at}s${x.scrolling ? '(scroll)' : ''}:${x.cause}`).join(' | ');
    const msg = `t=${Math.round(elapsed / 1000)}s freezes=${f.length} scrollFreezes=${f.filter(x => x.scrolling).length} imgs=${perf.imgs} busy[${top}] worst[${worst}]`;
    perf.busy.clear(); perf.imgs = 0;
    fetch(serverApi('log'), { method: 'POST', keepalive: true, body: JSON.stringify({ kind: 'perf', message: msg.slice(0, 900), where: isNative ? 'native' : isTV ? 'tv' : 'web' }) }).catch(() => {});
  }
  if (elapsed > 185_000) clearInterval(perfTimer);
}, 30_000);

// ---- Android TV (NVIDIA Shield) ----
// Laid out like Android TV apps (TiviMate): a side menu on the left that opens out when you move onto it, the page
// to its right. The remote moves in rows, never by guesswork:
//   left / right  move along the row you're in; left from the start of a row opens the side menu
//   up / down     go to the row above / below, landing in the same column
//   OK            opens what's picked; hold OK for its options (remove from Continue watching, favourites…)
//   Back          closes what's open, then goes back a page
// Films and channels play in the TV's own player (PlayerActivity), which has its own remote keys.
if (isTV) {
  document.documentElement.classList.add('tv');
  const RAIL = '#mainnav a, #tq, #actions button';
  const PICKABLE = 'a[href], button:not([disabled]), input:not([type=hidden]), select, textarea, [tabindex], .scard, .svc, .hero-slide, .row, .hs-row, .cw, .lib, .card, .epc, [data-go], [data-see], [data-resume]';
  const CARDISH = '.scard, .svc, .hero-slide, .row, .hs-row, .cw, .lib, .card, .epc';
  // Section-header links ("Browse all", "See all 133") aren't stops: the side menu and each row's "+ more" tile cover them.
  const SKIP = '.hero-dots, a.link, .shelf-head, .row-head';
  const visible = el => {
    const r = el.getBoundingClientRect();
    return r.width > 4 && r.height > 4 && (el.checkVisibility ? el.checkVisibility() : !el.closest('[hidden]'));
  };
  const inRail = el => !!el?.closest('.topbar');
  const sheetOpen = () => !$('#sheet').hidden;
  // Everything you can pick on the page (or in an open sheet), minus the small buttons inside cards (hold OK for those).
  let sideColumn = false;
  const picks = () => {
    const root = sheetOpen() ? $('#sheet') : $('#view');
    sideColumn = !sheetOpen() && sidePicks().length > 0;
    // Only what's on screen or within a screen of it (the Guide has thousands of programme cells, Movies ~1000 posters:
    // measuring them all on every press made the remote lag). Position first (one layout pass), the costlier checks after.
    const h = innerHeight, out = [];
    for (const el of root.querySelectorAll(PICKABLE)) {
      const r = el.getBoundingClientRect();
      if (r.bottom < -h || r.top > 2 * h || r.width <= 4 || r.height <= 4) continue;
      if (el.parentElement.closest(CARDISH) || el.closest(SKIP) || (sideColumn && el.closest(SIDE))) continue;
      if (el.checkVisibility && !el.checkVisibility()) continue;
      out.push(el);
    }
    return out;   // banner slides are one row: left/right change slide
  };
  const railPicks = () => [...document.querySelectorAll(RAIL)].filter(visible);
  // A page's own side list (Live TV groups) is a column of its own, like the menu: up/down within it, right into the page.
  const SIDE = '#chips';
  const inSide = el => !!el?.closest(SIDE) && sidePicks().includes(el);
  const sidePicks = () => {   // only when the chips really are a column (the Guide lays the same chips out in a row)
    if (sheetOpen()) return [];
    const chips = [...document.querySelectorAll(`${SIDE} .chip`)].filter(visible);
    return chips.length > 1 && chips[1].getBoundingClientRect().top > chips[0].getBoundingClientRect().top + 4 ? chips : [];
  };
  let lastMain = null;
  // Rows: things whose tops line up (a shelf, even the part scrolled off to the side, a grid line, a row of buttons).
  const lines = items => {
    const rows = [];
    for (const el of items.map(el => ({ el, r: el.getBoundingClientRect() })).sort((a, b) => a.r.top - b.r.top || a.r.left - b.r.left)) {
      const row = rows[rows.length - 1];
      if (row && Math.abs(el.r.top - row.top) < Math.min(row.h, el.r.height) / 2) row.items.push(el); else rows.push({ top: el.r.top, h: el.r.height, items: [el] });
    }
    for (const row of rows) row.items.sort((a, b) => a.r.left - b.r.left);
    return rows;
  };
  let memX = null, lastContent = null;
  window.__tvRows = () => lines(picks()).slice(0, 8).map(r => Math.round(r.top) + ': ' + r.items.slice(0, 6).map(i => (i.el.textContent || i.el.className).trim().replace(/\s+/g, ' ').slice(0, 12)).join(' | '));   // for checking the TV layout remotely
  const focusEl = el => {
    if (!el.matches('a[href], button, input, select, textarea') && !el.hasAttribute('tabindex')) el.tabIndex = -1;
    el.focus({ preventScroll: true });
    if (inRail(el)) { document.body.classList.add('rail-open'); return; }
    document.body.classList.remove('rail-open');
    if (!el.closest('#sheet')) lastContent = el;   // what to come back to when a details page or menu closes
    if (!el.closest('#sheet') && !el.closest(SIDE)) lastMain = el;
    // Instant (holding a key never queues animations) and kept mid-screen, clear of the edges.
    el.scrollIntoView({ block: el.closest('#sheet') ? 'nearest' : 'center', inline: 'nearest' });   // a details page scrolls only as needed
  };
  const enterRail = () => { const a = $('#mainnav a.active') || railPicks()[0]; if (a) focusEl(a); };
  const leaveRail = () => {
    if (lastContent && lastContent.isConnected && visible(lastContent)) return focusEl(lastContent);
    const hero = [...$('#view').querySelectorAll('.hero-slide')].find(visible);   // a page with a banner starts on it
    if (hero && !sheetOpen()) return focusEl(hero);
    const first = lines(picks()).find(r => r.top >= 0)?.items[0]?.el;
    if (first) focusEl(first);
  };
  const move = dir => {
    const cur = document.activeElement && document.activeElement !== document.body && visible(document.activeElement) ? document.activeElement : null;
    if (inRail(cur)) {
      const rail = railPicks(), i = rail.indexOf(cur);
      if (dir === 'ArrowDown' && i < rail.length - 1) focusEl(rail[i + 1]);
      else if (dir === 'ArrowUp' && i > 0) focusEl(rail[i - 1]);
      else if (dir === 'ArrowRight') leaveRail();
      return;
    }
    if (inSide(cur)) {
      const side = sidePicks(), i = side.indexOf(cur);
      if (dir === 'ArrowDown' && i < side.length - 1) focusEl(side[i + 1]);
      else if (dir === 'ArrowUp' && i > 0) focusEl(side[i - 1]);
      else if (dir === 'ArrowLeft') enterRail();
      else if (dir === 'ArrowRight') {
        if (lastMain && lastMain.isConnected && visible(lastMain)) focusEl(lastMain);
        else { const f = lines(picks()).find(r => r.top >= 0)?.items[0]?.el; if (f) focusEl(f); }
      }
      return;
    }
    const rows = lines(picks());
    if (!rows.length) return;
    let ri = -1, ci = -1;
    if (cur) rows.forEach((row, i) => { const j = row.items.findIndex(x => x.el === cur); if (j >= 0) { ri = i; ci = j; } });
    if (ri < 0) {   // nothing picked (or the page redrew under it): start at the first row on screen
      const row = rows.find(r => r.top >= 0) || rows[0];
      return focusEl(row.items[0].el);
    }
    const row = rows[ri];
    if (dir === 'ArrowLeft') {
      if (ci > 0) { focusEl(row.items[ci - 1].el); memX = null; return; }
      if (sheetOpen()) return;
      const side = sidePicks();
      if (side.length) return focusEl(side.find(c => c.classList.contains('active')) || side[0]);
      return enterRail();
    }
    if (dir === 'ArrowRight') { if (ci < row.items.length - 1) { focusEl(row.items[ci + 1].el); memX = null; } return; }
    const target = rows[ri + (dir === 'ArrowDown' ? 1 : -1)];
    if (!target) return;
    const r = cur.getBoundingClientRect(), x = memX ?? r.left + r.width / 2;
    let best = target.items[0], d = Infinity;
    for (const it of target.items) { const dd = Math.abs(it.r.left + it.r.width / 2 - x) + (it.r.right < 0 || it.r.left > innerWidth ? 2000 : 0); if (dd < d) { d = dd; best = it; } }
    memX = x;
    focusEl(best.el);
  };
  // Hold OK: the picked item's options.
  const options = el => {
    if (el.matches('.cw')) {
      const x = el.querySelector('[data-cw-x]');
      return menu(el.querySelector('.cw-name, b, strong')?.textContent?.trim() || 'Continue watching', [
        { label: 'Play', fn: () => el.click() },
        x && { label: 'Remove from Continue watching', danger: true, fn: () => x.click() },
      ]);
    }
    if (el.matches('.row') && el.dataset.i !== undefined && state.filtered[+el.dataset.i]) return channelMenu(state.filtered[+el.dataset.i]);
    const inner = [...el.querySelectorAll('button')].map(b => ({ b, label: b.getAttribute('aria-label') || b.textContent.trim() })).filter(x => x.label);
    if (inner.length) return menu(null, [...(el.matches('button') ? [] : [{ label: 'Open', fn: () => el.click() }]), ...inner.map(x => ({ label: x.label, fn: () => x.b.click() }))]);
    el.click();
  };
  let okHeld = false, okLong = false;
  document.addEventListener('keydown', e => {
    const typing = e.target.matches('input:not([readonly]), textarea, select');
    if (e.key === 'Enter' && !typing) {
      e.preventDefault(); e.stopImmediatePropagation();
      // Android only repeats a key once it's been held (~0.5s), so a second "down" before the "up" is the long press
      // (the TV's WebView doesn't flag repeats, so they're counted here).
      if (!okHeld) { okHeld = true; okLong = false; }
      else if (!okLong) { okLong = true; const el = document.activeElement; if (el && el !== document.body) options(el); }
      return;
    }
    if (!/^Arrow(Up|Down|Left|Right)$/.test(e.key)) return;
    if (typing && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) return;   // moving the cursor in a search box
    e.preventDefault(); e.stopImmediatePropagation();
    const t0 = performance.now();
    move(e.key);
    window.__tvMoveMs = Math.round(performance.now() - t0);
  }, true);
  document.addEventListener('keyup', e => {
    if (e.key !== 'Enter') return;
    okHeld = false;
    if (e.target.matches('input, textarea, select')) return;   // (the search box opens its keyboard itself)
    e.preventDefault();
    if (okLong) { okLong = false; return; }
    const el = document.activeElement;
    if (!el || el === document.body) return;
    el.click();
    if (el.closest('#mainnav')) { lastContent = null; setTimeout(leaveRail, 350); }   // picked a section: into its page
  }, true);
  // Search in the side menu works like a menu item: moving onto it doesn't pop up the TV keyboard (which would
  // swallow the remote's arrows); OK opens the keyboard, and leaving it makes it a menu item again.
  const tq = $('#tq');
  if (tq) {
    tq.readOnly = true;
    tq.addEventListener('focus', () => document.body.classList.add('rail-open'));
    tq.addEventListener('blur', () => { tq.readOnly = true; });
    tq.addEventListener('keyup', e => { if (e.key === 'Enter' && tq.readOnly) { tq.readOnly = false; tq.blur(); tq.focus(); } });
  }
  // Back on the remote (MainActivity asks this first): true = handled here, false = leave the app.
  window.__tvBack = () => {
    if (sheetOpen()) { closeSheet(); setTimeout(leaveRail, 30); return true; }
    if (inRail(document.activeElement) && location.hash && location.hash !== '#/') { leaveRail(); return true; }
    if (location.hash && location.hash !== '#/' && location.hash !== '#') { history.back(); return true; }
    if (!inRail(document.activeElement)) { enterRail(); return true; }   // Home: Back opens the menu, Back again leaves
    return false;
  };
  // A menu or details sheet opens with its first option picked.
  // Details fill in after the sheet opens (and redraw), so this re-runs whenever its content changes; Play comes first.
  let sheetTimer = 0;
  new MutationObserver(() => {
    clearTimeout(sheetTimer);
    sheetTimer = setTimeout(() => {
      if (!sheetOpen() || $('#sheet').contains(document.activeElement)) return;
      const f = [...$('#sheet').querySelectorAll('.detail-actions .btn.primary, .menu-item')].find(visible) || lines(picks())[0]?.items[0]?.el;
      if (f) { focusEl(f); $('#sheet').scrollTop = 0; f.scrollIntoView({ block: 'nearest' }); }
    }, 80);
  }).observe($('#sheet'), { attributes: true, attributeFilter: ['hidden'], childList: true, subtree: true });
  // A new page starts with its first row picked, so the remote always has somewhere to go.
  addEventListener('hashchange', () => setTimeout(() => { if (!inRail(document.activeElement) && !sheetOpen()) leaveRail(); }, 400));
  setTimeout(() => { if (document.activeElement === document.body) leaveRail(); }, 1500);
}
