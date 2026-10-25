import { parseM3U, toM3U, isHlsMedia, groupsOf, dedupe, uid, makeChannel, nameFromUrl } from './m3u.js';
import { db } from './db.js';
import { Player, externalPlayers } from './player.js';
import { net, fetchText, isMixed, streamUrl } from './net.js';
import { Xtream, parseXtreamLink, liveChannels, movieItems, seriesItems } from './xtream.js';
import { loadEpg, Guide, wantedFor, archiveDays, m3uCatchupUrl, normName } from './epg.js';
import { groupSeries, vodMeta } from './vod.js';
import { $, esc, icon, toast, sheet, closeSheet, menu, confirmDialog, formSheet } from './ui.js';
import { isNative, nativePlay, nativeShare } from './native.js';

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
  settings: { proxy: '', streamProxy: 'http', tsToHls: true, pinHash: '', density: 'standard', logos: true, numbers: false, showNext: true, mergeDupes: true, resumeCard: true, accent: '' },
  current: null,   // playlist being viewed (real, or virtual 'fav' / 'recent' / 'all')
  tab: 'live',     // 'live' | 'movies' | 'series' (Xtream playlists)
  group: null,     // null = all groups
  query: '',
  filtered: [],
  rendered: 0,
  selecting: false,
  reordering: false,   // drag-to-reorder mode inside a group
  selected: new Set(),
  playing: null,   // { list, index }
  unlocked: false, // parental PIN entered this session
  sleepTimer: null,
  sleepAt: 0,
};
const favUrls = new Set();
const syncFavs = () => { favUrls.clear(); for (const c of state.favorites) favUrls.add(c.url); };
const guides = new Map();    // playlist id -> Guide
const vodCache = new Map();  // `${playlistId}:${tab}` -> items
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
  window.addEventListener('hashchange', route);
  window.addEventListener('resize', debounce(sizeGuide, 100));
  route();
  if (!isNative && 'serviceWorker' in navigator && location.protocol !== 'file:') {
    // A new version took over: reload once so the phone runs it now, not on the next open (never mid-stream).
    const hadController = !!navigator.serviceWorker.controller;
    navigator.serviceWorker.addEventListener('controllerchange', () => { if (hadController && !state.playing && !init.reloaded) { init.reloaded = true; location.reload(); } });
    navigator.serviceWorker.register('sw.js').then(r => r.update()).catch(() => {});
  }
  if (!isNative) await detectServerProxy();
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

async function checkServerProxy(key = state.settings.serverKey) {
  const base = `${location.origin}${location.pathname.replace(/[^/]*$/, '')}proxy`;
  const res = await fetch(`${base}${key ? `?key=${encodeURIComponent(key)}` : ''}`, { cache: 'no-store' });
  const info = res.ok && /json/.test(res.headers.get('content-type') || '') ? await res.json() : null;
  return info?.m3uProxy ? { base, ...info } : null;
}

async function detectServerProxy() {
  if (location.protocol === 'file:') return;
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
const syncMeta = { favAt: 0, recReset: 0 };
const canSync = () => serverProxy.available && !serverProxy.needsKey;
const serverApi = path => serverProxy.base.replace(/proxy$/, path) + (state.settings.serverKey ? `?key=${encodeURIComponent(state.settings.serverKey)}` : '');
let syncing = null, syncAgain = false;
async function syncNow() {
  if (!canSync()) return;
  if (syncing) { syncAgain = true; return syncing; }
  syncing = (async () => {
    try {
      const res = await fetch(serverApi('sync'), { method: 'POST', cache: 'no-store', body: JSON.stringify({
        favorites: state.favorites, favAt: syncMeta.favAt, recents: state.recents, recReset: syncMeta.recReset, progress: state.progress }) });
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

async function enterServerKey() {
  const v = await formSheet({
    title: 'Server key',
    fields: [{ name: 'key', label: 'Shown at the end of the server install', value: state.settings.serverKey || '', required: true }],
    submit: 'Connect',
  });
  if (!v) return;
  const info = await checkServerProxy(v.key).catch(() => null);
  if (!info?.keyValid) { toast('That key didn’t work. Check it and try again.'); return; }
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
function transcodeUrl(url) {
  if (isNative) return null;                 // the native app plays raw streams itself
  if (!serverProxy.available) return null;   // only our own server exposes /hls
  const key = state.settings.serverKey || '';
  if (serverProxy.needsKey && !key) return null;
  return `/hls?${key ? `key=${encodeURIComponent(key)}&` : ''}url=${encodeURIComponent(url)}`;
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
  const tc = url && transcodeUrl(url);
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
  net.proxy = state.settings.proxy || (serverProxy.available && !serverProxy.needsKey ? serverProxyUrl() : '');
  net.streamProxy = state.settings.streamProxy;
  player.tsToHls = state.settings.tsToHls;
  player.wrap = streamUrl;
  player.preferNative = isNative;
  player.transcode = transcodeUrl;
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
  if (kind !== 'guide') guideDay = 0;
  if (kind === 'p' || kind === 'guide') {
    const p = byId(id);
    if (p) return kind === 'p' ? showPlaylist(p) : showGuide(p);
    location.hash = '#/';
    return;
  }
  if (kind === 'fav' || kind === 'recent' || kind === 'all') return showPlaylist(virtualPlaylist(kind));
  if (kind === 'multi') return showMulti();
  showHome();
  const y = viewMemo.get(memoKey)?.y;
  if (y) requestAnimationFrame(() => scrollTo(0, y));
}

function rerender() {
  const [, kind] = (location.hash || '#/').slice(1).split('/');
  if (kind === 'guide') return state.current && showGuide(state.current);
  if (kind === 'multi') return;
  if (state.current) refreshPlaylistView({ keep: true });   // an edit: stay on the same row
  else { const y = scrollY; showHome(); scrollTo(0, y); }
}

function setTopbar({ title, back = false, actions = [] }) {
  $('#title').textContent = title;
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
  return Object.values(state.progress).filter(e => !e.done && e.t > 30).sort((a, b) => b.at - a.at).slice(0, 20);
}

// A display name for a playlist — never a raw URL/host (which is what a nameless
// import defaults to), so the home list stays readable.
const URLISH = /^(https?:\/\/)?[a-z0-9-]+(\.[a-z0-9-]+)+(\/|:|$)/i;
function playlistTitle(p) {
  const n = (p.name || '').trim();
  return !n || URLISH.test(n) ? 'My playlist' : n;
}

function playlistSub(p) {
  const parts = [plural(p.channels.length, 'channel')];
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

// Home tiles fill top-to-bottom, then the next column (1 top-left, 2 under it). At most 4 rows, so the
// block stays a fixed height: more groups add columns that scroll sideways instead of lengthening the page.
const browseRows = n => (n <= 2 ? n : Math.min(4, Math.ceil(n / 2)));

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
  // Snapshot: playing a channel moves it to the front of state.recents, so tiles must keep their own channel.
  const recentStrip = state.recents.slice(0, 12);
  // Browse: the groups the user pinned to Home (in their order); until they pin any, the biggest live groups.
  const pinnedAny = pl.some(p => p.homeGroups?.length);
  const browse = pl.flatMap(p => {
    const k = m3uKinds(p);
    const gs = cachedGroups(p, k ? k.live : p.channels, k ? 'live' : 'all').filter(g => g.name && visibleGroup(p, g.name));
    if (pinnedAny) {
      const want = p.homeGroups || [];
      return want.map(n => gs.find(g => g.name === n)).filter(Boolean).map(g => ({ pid: p.id, name: g.name, count: g.count }));
    }
    return gs.sort((a, b) => b.count - a.count).map(g => ({ pid: p.id, name: g.name, count: g.count }));
  }).slice(0, pinnedAny ? 40 : 12);
  const browseEdit = pl.length ? `<button class="link" data-edit-groups="${esc(pl[0].id)}">${pinnedAny ? 'Edit' : 'Choose'}</button>` : '';
  view.innerHTML = `
    ${isIOS && !isStandalone && !dismissed ? `<div class="banner">
      ${icon('share')}<span><b>Install on your iPhone:</b> tap Share, then “Add to Home Screen”. It opens full-screen and iOS keeps your playlists safe.</span>
      <button class="icon-btn" id="tip-close" aria-label="Dismiss">${icon('close')}</button></div>` : ''}
    ${serverProxy.needsKey ? `<div class="banner">
      ${icon('lock')}<span><b>Connect to your server:</b> enter the server key from the install, so http:// providers and TV guides work.</span>
      <button class="btn small primary" id="server-key">Enter key</button></div>` : ''}
    ${pl.length ? `<label class="search home-search">${icon('search')}<input id="home-q" type="search" placeholder="Search all channels" autocomplete="off" autocorrect="off" spellcheck="false"></label>` : ''}
    ${resumeCardHtml(recentStrip[0])}
    ${state.recents.length ? `<div class="section-title">Jump back in</div>
      <div class="strip">${recentStrip.map((c, i) => `<button class="rc" data-recent="${i}">${logoHtml(c)}<span class="rc-name">${esc(c.name)}</span></button>`).join('')}</div>` : ''}
    ${browse.length || pinnedAny ? `<div class="section-title split">Browse${browseEdit}</div>
      <div class="browse${browse.length > 8 ? ' scrolls' : ''}" style="--rows:${browseRows(browse.length)}">${browse.map(b => `<button class="bt" data-browse="${esc(b.pid)}" data-group="${esc(b.name)}" style="--hue:${hueOf(b.name)}">
        <span class="bt-name">${esc(prettyGroup(b.name))}</span><span class="muted">${b.count.toLocaleString()}</span></button>`).join('')}</div>` : ''}
    ${cw.length ? `<div class="section-title">Continue watching</div>
      <div class="strip">${cw.map((e, i) => `<button class="cw" data-cw="${i}">
        <span class="cw-img">${e.item.logo ? `<img src="${esc(e.item.logo)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : icon('film')}</span>
        <span class="bar"><i style="width:${Math.min(100, (e.t / e.d) * 100).toFixed(1)}%"></i></span>
        <span class="cw-name">${esc(e.item.name)}</span><span class="muted">${fmtDur(e.d - e.t)} left</span></button>`).join('')}</div>` : ''}
    <div class="section-title">Library</div>
    <ul class="cards tiles">
      ${card('#/fav', 'star', 'Favorites', plural(state.favorites.length, 'item'))}
      ${card('#/recent', 'clock', 'Recently watched', plural(state.recents.length, 'item'))}
      ${pl.length ? card('#/all', 'search', 'All channels', 'Every playlist in one list') : ''}
      ${pl.length ? card('#/multi', 'grid', 'Multi-view', 'Watch up to 4 channels at once') : ''}
    </ul>
    <div class="section-title">Playlists</div>
    ${pl.length
      ? `<ul class="cards">${pl.map(p => card(`#/p/${p.id}`, p.kind === 'xtream' ? 'tv' : 'list', playlistTitle(p), playlistSub(p), p.id)).join('')}</ul>`
      : `<div class="empty">${icon('tv', 'big')}<p><b>No playlists yet</b></p>
          <p class="muted">Add an Xtream login, an M3U / M3U8 link, or a playlist file.</p>
          <button class="btn primary" id="add-first">Add playlist</button></div>`}`;

  $('#add-first')?.addEventListener('click', addPlaylistSheet);
  $('#server-key')?.addEventListener('click', enterServerKey);
  $('#tip-close')?.addEventListener('click', () => {
    try { localStorage.setItem('install-tip', 'off'); } catch {}
    $('.banner').remove();
  });
  $('#home-q')?.addEventListener('keydown', e => {
    if (e.key === 'Enter' && e.target.value.trim()) { pendingQuery = e.target.value.trim(); location.hash = '#/all'; }
  });
  view.onclick = e => {
    const m = e.target.closest('[data-menu]');
    if (m) return playlistMenu(byId(m.dataset.menu));
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
  const memo = !pendingGroup && !pendingQuery ? viewMemo.get(location.hash) : null;
  state.group = pendingGroup ?? memo?.group ?? null; pendingGroup = null;
  state.query = pendingQuery ? pendingQuery.toLowerCase() : memo?.query || '';
  if (memo && !pendingQuery) pendingQuery = memo.query || '';
  const k = m3uKinds(p);
  const showTabs = p.kind === 'xtream' || p._hasVod;
  const tabBtn = (t, label) => `<button data-tab="${t}"${t === 'live' ? ' class="active"' : ''}>${label}</button>`;
  view.innerHTML = `
    ${showTabs ? `<div class="seg" id="seg">
      ${tabBtn('live', 'Live TV')}${(p.kind === 'xtream' || k?.movies.length) ? tabBtn('movies', 'Movies') : ''}${(p.kind === 'xtream' || k?.series.length) ? tabBtn('series', 'Series') : ''}</div>` : ''}
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
  if (memo?.tab && memo.tab !== 'live' && $(`#seg [data-tab="${memo.tab}"]`)) $(`#seg [data-tab="${memo.tab}"]`).click();
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
  if (p.kind === 'xtream') return state.tab === 'live' ? p.channels : vodCache.get(vodKey(p, state.tab)) || [];
  const k = m3uKinds(p);                       // M3U: split by inferred kind, separators already removed
  if (k) return k[state.tab] || k.live;
  return p.channels;
}

async function loadVod(p, tab, { force = false } = {}) {
  const key = vodKey(p, tab);
  if (vodCache.has(key) && !force) return;
  if (!force) {
    const cached = await db.get(`vod:${key}`);
    if (cached && Date.now() - cached.at < DAY) {
      vodCache.set(key, cached.items);
      if (state.current === p && state.tab === tab) refreshPlaylistView();
      return;
    }
  }
  const x = xtOf(p);
  try {
    const items = tab === 'movies'
      ? movieItems(x, await x.categories('vod'), await x.vodStreams())
      : seriesItems(await x.categories('series'), await x.seriesList());
    items.forEach(it => { it.src = p.id; });
    vodCache.set(key, items);
    db.set(`vod:${key}`, { at: Date.now(), items }).catch(() => {});
    if (state.current === p && state.tab === tab) refreshPlaylistView();
  } catch (err) {
    if (state.current === p && state.tab === tab) $('#list').innerHTML = `<li class="empty small">${esc(err.message)}</li>`;
  }
}

function renderChips() {
  const p = state.current;
  const groups = cachedGroups(p, currentItems(), p.kind === 'xtream' ? 'x:' + state.tab : state.tab).filter(g => !p.hiddenGroups?.includes(g.name));
  if (state.group !== null && !groups.some(g => g.name === state.group)) state.group = null;
  const locked = g => !p.virtual && p.lockedGroups?.includes(g) && !state.unlocked;
  const visibleCount = groups.filter(g => !locked(g.name)).reduce((n, g) => n + g.count, 0);
  const chip = (label, n, active, attr, lock) =>
    `<button class="chip ${active ? 'active' : ''}" ${attr}>${lock ? icon('lock') : ''}${esc(label)}<span>${lock ? '' : n.toLocaleString()}</span></button>`;
  const showGroups = groups.length > 1 || (groups[0] && groups[0].name);
  const pinned = new Set(p.homeGroups || []);
  if (pinned.size) groups.sort((a, b) => pinned.has(b.name) - pinned.has(a.name));   // stable: starred groups first, then your order
  $('#chips').hidden = !showGroups;
  $('#chips').innerHTML = chip('All', visibleCount, state.group === null, 'data-all') +
    (showGroups ? groups.map(g => chip(g.name ? prettyGroup(g.name) : UNGROUPED, g.count, state.group === g.name,
      `data-group="${esc(g.name)}" ${locked(g.name) ? 'data-locked="1"' : ''}`, locked(g.name))).join('') : '');
  $('#chips .chip.active')?.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'instant' });
}

function applyFilter({ keep = false } = {}) {
  const p = state.current;
  const keepRows = keep ? state.rendered : 0, keepY = scrollY;
  const q = state.query;
  const items = currentItems();
  state.filtered = items.filter(c => {
    const g = c.group || '';
    if (state.group === null ? !visibleGroup(p, g) : g !== state.group) return false;
    if (!q) return true;
    if (/^\d+$/.test(q) && c.num) return String(c.num).startsWith(q);   // typing a number zaps by channel number
    return c.name.toLowerCase().includes(q) || g.toLowerCase().includes(q);
  });
  const what = state.tab === 'movies' ? 'movie' : state.tab === 'series' ? 'series' : p.id === 'fav' ? 'item' : 'channel';
  $('#q').placeholder = `Search ${what === 'series' ? `${items.length.toLocaleString()} series` : plural(items.length, what)}`;
  const list = $('#list');
  list.classList.toggle('grid', state.tab !== 'live' && state.current.kind === 'xtream');
  list.innerHTML = '';
  state.rendered = 0;
  if (state.filtered.length) {
    renderMore();
    while (state.rendered < Math.min(keepRows, state.filtered.length)) renderMore();
    if (keep) scrollTo(0, keepY);
  } else {
    const loading = state.tab !== 'live' && !vodCache.has(vodKey(p, state.tab));
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
  const end = Math.min(state.rendered + PAGE, state.filtered.length);
  const html = (state.tab === 'live' || state.current?.kind !== 'xtream') ? rowHtml : posterHtml;
  const shown = state.filtered.slice(state.rendered, end);
  list.insertAdjacentHTML('beforeend', shown.map((c, k) => html(c, state.rendered + k)).join(''));
  state.rendered = end;
  if (state.tab === 'live') ensureGuide(shown);
}

// Fill in what's on for rows already on screen, touching only that line (no rebuild, no flicker, taps keep working).
function updateRowsInPlace() {
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
    <span class="row-text"><span class="row-name">${esc(c.name)}${archiveDays(c) ? '<span class="tag">Catch-up</span>' : ''}</span><span class="row-sub">${sub}</span>${progressBar(c)}</span>
    ${state.reordering ? `<span class="rhandle" aria-label="Drag to reorder">${icon('grip')}</span>` : state.selecting ? '' : `<button class="icon-btn star${favUrls.has(c.url) ? ' on' : ''}" data-act="fav" aria-label="Favorite">${icon('star')}</button>
    <button class="icon-btn" data-act="more" aria-label="Options">${icon('more')}</button>`}
  </li>`;
}

function posterHtml(it, i) {
  const m = it.kind || it.year !== undefined ? null : vodMeta(it.name);   // plain M3U movie: tidy "Title (2023) HD"
  const name = m?.title || it.name, year = it.year ?? m?.year;
  return `<li class="poster" data-i="${i}">
    <span class="poster-img" data-initials="${initials(it.name)}">${it.logo ? `<img src="${esc(it.logo)}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}
      ${it.rating ? `<span class="badge">★ ${it.rating.toFixed(1)}</span>` : ''}${progressBar(it)}</span>
    <span class="poster-name">${esc(name)}</span>
    ${year ? `<span class="muted">${esc(year)}</span>` : ''}
  </li>`;
}

function onListClick(e) {
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
  if (state.reordering) toast('Drag ⠿ to move channels. Tip: you can also long-press any channel.');
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
    press = { li, x: e.clientX, y: e.clientY, timer: setTimeout(() => { press = null; suppressClick = true; start(li, e); }, 450) };
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
function externalItems(c) {
  return [
    ...externalPlayers(c.url).map(x => ({ label: `Open in ${x.name}`, fn: () => { location.href = x.href; } })),
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
    editable && isLiveChannel(c) && { label: 'Move to top of group', fn: () => moveInGroup(c, true) },
    editable && isLiveChannel(c) && { label: 'Move to bottom of group', fn: () => moveInGroup(c, false) },
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
  const i = info.info || {};
  const pr = state.progress[m.url];
  const resumable = pr && !pr.done && pr.t > 30;
  const meta = [String(i.releasedate || i.release_date || '').slice(0, 4) || m.year, i.duration, i.genre || m.genre].filter(Boolean);
  return `<div class="detail" data-id="${esc(m.id)}">
    <div class="detail-top">
      <span class="detail-poster" data-initials="${initials(m.name)}">${(i.movie_image || i.cover_big || m.logo) ? `<img src="${esc(i.movie_image || i.cover_big || m.logo)}" alt="" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}</span>
      <div class="detail-head">
        <div class="detail-title">${esc(m.name)}</div>
        <div class="muted">${meta.map(esc).join(' · ')}</div>
        ${m.rating ? `<div class="rating">★ ${m.rating.toFixed(1)}</div>` : ''}
      </div>
    </div>
    ${i.plot || i.description || m.plot ? `<p class="detail-plot">${esc(i.plot || i.description || m.plot)}</p>` : ''}
    ${i.cast || i.actors ? `<p class="muted">Cast: ${esc(i.cast || i.actors)}</p>` : ''}
    ${i.director ? `<p class="muted">Director: ${esc(i.director)}</p>` : ''}
    <div class="detail-actions">
      <button class="btn primary" data-play>${icon('play')} ${resumable ? `Resume ${fmtDur(pr.t)}` : 'Play'}</button>
      ${resumable ? '<button class="btn" data-restart>Start over</button>' : ''}
      <button class="btn" data-fav>${favUrls.has(m.url) ? '★ Saved' : '☆ Save'}</button>
      <button class="btn" data-more>More</button>
    </div>
  </div>`;
}

async function movieSheet(m) {
  let info = {};
  const open = (keepScroll = false) => sheet(detailHtml(m, info), {
    keepScroll,
    onMount: el => {
      el.querySelector('[data-play]').onclick = () => { closeSheet(); playChannel([m], 0); };
      el.querySelector('[data-restart]')?.addEventListener('click', () => { delete state.progress[m.url]; closeSheet(); playChannel([m], 0); });
      el.querySelector('[data-fav]').onclick = async () => { await toggleFavorite(m, null); open(true); };
      el.querySelector('[data-more]').onclick = () => menu(m.name, [
        info.info?.youtube_trailer && { label: 'Watch trailer', fn: () => window.open(`https://www.youtube.com/watch?v=${encodeURIComponent(info.info.youtube_trailer)}`, '_blank') },
        ...externalItems(m),
      ]);
    },
  });
  open();
  const x = xtOf(playlistOf(m));
  if (!x || !m.sid) return;
  try {
    info = await x.vodInfo(m.sid) || {};
    if ($('#sheet .detail')?.dataset.id === m.id) open();
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
  const eps = info?.episodes || {};
  const seasons = Object.keys(eps).sort((a, b) => a - b);
  const episodesOf = season => (eps[season] || []).map(ep => s.seasons ? { ...ep, src: p.id, duration: '', group: `${s.name} · Season ${season}` } : ({
    id: `e${ep.id}`, kind: 'episode', src: p.id,
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

  const render = (keepScroll = false) => {
    const list = episodesOf(season);
    sheet(`<div class="detail" data-id="${esc(s.id)}">
      <div class="detail-top">
        <span class="detail-poster" data-initials="${initials(s.name)}">${(i.cover || s.logo) ? `<img src="${esc(i.cover || s.logo)}" alt="" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}</span>
        <div class="detail-head">
          <div class="detail-title">${esc(s.name)}</div>
          <div class="muted">${[s.year, i.genre || s.genre, plural(seasons.length, 'season')].filter(Boolean).map(esc).join(' · ')}</div>
          ${s.rating ? `<div class="rating">★ ${s.rating.toFixed(1)}</div>` : ''}
        </div>
      </div>
      ${i.plot || s.plot ? `<p class="detail-plot">${esc(i.plot || s.plot)}</p>` : ''}
      ${seasons.length > 1 ? `<div class="chips season-chips">${seasons.map(se => `<button class="chip ${se === season ? 'active' : ''}" data-season="${esc(se)}">Season ${esc(se)}</button>`).join('')}</div>` : ''}
      ${list.length ? `<ul class="eps">${list.map((e, k) => {
        const pr = state.progress[e.url];
        return `<li class="ep" data-e="${k}">
          <span class="ep-num">${esc(e.num)}</span>
          <span class="row-text"><span class="row-name">${esc(e.name)}</span><span class="muted">${esc(e.duration)}${pr?.done ? ' · Watched' : pr?.t > 30 ? ` · ${fmtDur(pr.d - pr.t)} left` : ''}</span>${progressBar(e)}</span>
          ${pr?.done ? `<span class="ep-done">${icon('check')}</span>` : icon('play')}
        </li>`;
      }).join('')}</ul>` : '<p class="muted">No episodes listed.</p>'}
    </div>`, {
      keepScroll,
      onMount: el => {
        el.onclick = e => {
          const sc = e.target.closest('[data-season]');
          if (sc) { season = sc.dataset.season; return render(true); }
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

function programmeSheet(c, prog) {
  const now = Date.now();
  const live = prog[0] <= now && prog[1] > now;
  const past = prog[1] <= now;
  sheet(`<div class="detail">
      <div class="detail-title">${esc(prog[2])}</div>
      <div class="muted">${esc(c.name)} · ${fmtDay(prog[0])} ${fmtRange(prog)}</div>
      ${prog[3] ? `<p class="detail-plot">${esc(prog[3])}</p>` : ''}
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
  const groups = groupsOf(items);
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
          const p = await createPlaylist(name, channels, { header, ...extra });
          closeSheet();
          location.hash = `#/p/${p.id}`;
          toast(`Added ${plural(p.channels.length, 'channel')}`);
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
      p.channels = mergeRefresh(p, liveChannels(x, cats, streams));
      p.userInfo = info.user_info;
      p.serverInfo = info.server_info;
    } else {
      const { header, channels } = parseSource(await fetchText(p.sourceUrl, { timeout: 60000 }), p.sourceUrl);
      p.channels = mergeRefresh(p, channels);
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
      <p class="muted">The iPhone app connects to any IPTV server directly, http:// included. No proxy needed.</p>` : `<div class="section-title">Proxy</div>
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
      <label class="switch"><span><b>Continue watching on Home</b><span class="muted">Your last channel at the top of Home, one tap to play.</span></span><input type="checkbox" name="resumeCard" ${s.resumeCard !== false ? 'checked' : ''}></label>
      <div class="section-title">Playback</div>
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
      el.querySelector('form').onsubmit = async e => {
        e.preventDefault();
        const f = new FormData(e.target);
        const mergeWas = state.settings.mergeDupes !== false;
        Object.assign(state.settings, {
          ...(f.has('proxy') ? { proxy: String(f.get('proxy')).trim(), streamProxy: String(f.get('streamProxy')) } : {}),
          tsToHls: f.get('tsToHls') === 'on',
          density: String(f.get('density')), accent: String(f.get('accent')), logos: f.get('logos') === 'on', numbers: f.get('numbers') === 'on',
          showNext: f.get('showNext') === 'on', mergeDupes: f.get('mergeDupes') === 'on', resumeCard: f.get('resumeCard') === 'on',
        });
        if (mergeWas !== state.settings.mergeDupes) state.playlists.forEach(invalidate);
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

function setStatus(text, isError = false) {
  const el = $('#now-status');
  const c = nowChannel();
  statusError = isError;
  if (!text && c) {
    const g = isLiveChannel(c) && guideFor(c);
    const now = g && g.nowNext(c).now;
    text = now ? `${now[2]} · ${fmtRange(now)}` : c.group || '';
    if (state.sleepAt) text = `${text ? `${text} · ` : ''}Sleep in ${Math.ceil((state.sleepAt - Date.now()) / MINUTE)} min`;
  }
  el.textContent = text || '';
  el.classList.toggle('error', isError);
}

let lastProgressSave = 0;
function trackProgress(force = false) {
  const c = nowChannel();
  if (!c || !c.kind || c.kind === 'live' || c.kind === 'catchup') return;
  const d = video.duration;
  if (!Number.isFinite(d) || d < 60) return;
  if (!force && Date.now() - lastProgressSave < 5000) return;
  lastProgressSave = Date.now();
  const t = video.currentTime;
  state.progress[c.url] = { t, d, at: Date.now(), done: t / d > 0.93, item: { id: c.id, kind: c.kind, name: c.name, url: c.url, logo: c.logo, group: c.group, src: c.src } };
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
  video.addEventListener('waiting', () => { if (state.playing) setStatus('Buffering…'); });
  video.addEventListener('playing', () => { setStatus(''); hideTuning(); });
  video.addEventListener('timeupdate', () => trackProgress());
  video.addEventListener('pause', () => trackProgress(true));
  video.addEventListener('ended', () => {
    trackProgress(true);
    const pl = state.playing;
    if (pl && nowChannel()?.kind === 'episode' && pl.index < pl.list.length - 1) { toast('Playing next episode'); step(1); }
  });
  // One silent retry on a mid-stream fault (provider blip, segment timeout) before showing the error.
  player.onFatal = msg => {
    const c = nowChannel();
    if (c && !retried.has(c.url)) { retried.add(c.url); setStatus('Reconnecting…'); setTimeout(() => { if (nowChannel() === c) playChannel(state.playing.list, state.playing.index); }, 1200); return; }
    if (c && nextBackup(c)) { setStatus('Trying a backup stream…'); setTimeout(() => { if (nowChannel() === c) playChannel(state.playing.list, state.playing.index); }, 300); return; }
    setStatus(msg, true);
  };
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
    if (state.playing && hiddenAt && Date.now() - hiddenAt > 45_000 && player.isLive) playChannel(state.playing.list, state.playing.index);
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
  const audio = player.audioTracks();
  const subs = player.subtitleTracks();
  const hasGuide = isLiveChannel(c) && guideFor(c)?.list(c).length;
  const g = hasGuide && guideFor(c);
  const now = g && g.nowNext(c).now;
  menu(c.name, [
    now && canCatchUp(c, now) && { label: `Watch “${now[2]}” from the start`, fn: () => playCatchup(c, now) },
    hasGuide && { label: archiveDays(c) ? 'TV guide & catch-up' : 'TV guide', fn: () => scheduleSheet(c) },
    audio.length > 1 && { label: 'Audio track…', fn: () => menu('Audio', audio.map(t => ({ label: `${t.active ? '✓ ' : ''}${t.label}`, fn: () => player.setAudioTrack(t.i) }))) },
    subs.length > 0 && { label: 'Subtitles…', fn: () => menu('Subtitles', [
      { label: subs.some(t => t.active) ? 'Off' : '✓ Off', fn: () => player.setSubtitleTrack(-1) },
      ...subs.map(t => ({ label: `${t.active ? '✓ ' : ''}${t.label}`, fn: () => player.setSubtitleTrack(t.i) })),
    ]) },
    Number.isFinite(video.duration) && { label: `Speed (${video.playbackRate}×)…`, fn: () => menu('Playback speed', [0.75, 1, 1.25, 1.5, 2].map(r => ({ label: `${video.playbackRate === r ? '✓ ' : ''}${r}×`, fn: () => { video.playbackRate = r; } }))) },
    { label: state.sleepAt ? `Sleep timer (${Math.ceil((state.sleepAt - Date.now()) / MINUTE)} min left)…` : 'Sleep timer…', fn: sleepMenu },
    { label: 'Picture in Picture', fn: () => player.togglePip().catch(e => toast(e.message)) },
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
  el.innerHTML = `${logoHtml(c)}<span class="tuning-name">${esc(c.name)}</span><span class="muted">${esc(prettyGroup(c.group) || '')}</span>`;
  el.hidden = false; el.classList.remove('out');
}
function hideTuning() { const el = $('#tuning'); if (el && !el.hidden) { el.classList.add('out'); setTimeout(() => { el.hidden = true; }, 320); } }
function playChannel(list, index) {
  const c = list[index];
  if (!c) return;
  trackProgress(true);
  const prev = nowChannel();
  state.playing = { list, index };
  $('#player').hidden = false;
  $('#player-error').hidden = true;
  $('#now-name').textContent = c.name;
  $('#now-logo').outerHTML = logoHtml(c, 'id="now-logo"');
  $('#prev').disabled = index === 0;
  $('#next').disabled = index === list.length - 1;
  if (location.hash.startsWith('#/guide')) sizeGuide();
  setStatus('Connecting…');
  showTuning(c);
  highlightPlaying();
  addRecent(c);
  tuneFrom = prev;
  try {
    if ('mediaSession' in navigator) {
      navigator.mediaSession.metadata = new MediaMetadata({ title: c.name, artist: c.group || 'M3U Player', artwork: c.logo ? [{ src: c.logo }] : [] });
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
function tune(c, list, index) {
  if (nowChannel() !== c) return;
  if (tuneFrom && tuneFrom.url !== c.url) unwarm(streamOf(tuneFrom));   // free the single provider slot right before connecting
  tuneFrom = null;
  player.play(streamOf(c)).then(ok => {
    if (!ok) return;
    setStatus('');
    setTimeout(() => { if (nowChannel() === c && !video.paused && video.currentTime > 2) retried.delete(c.url); }, 15_000);   // healthy again: allow a future retry
    const pr = state.progress[c.url];
    if (pr && !pr.done && pr.t > 30 && Number.isFinite(video.duration)) {
      video.currentTime = pr.t;
      toast(`Resumed at ${fmtDur(pr.t)}`, { action: 'Start over', fn: () => { video.currentTime = 0; } });
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
  const box = $('#player-error');
  const extra = isMixed(c.url) && !net.proxy ? ' This is an http:// stream; set up the free proxy in Settings to play it here.' : '';
  box.innerHTML = `<p>${esc(err.message + extra)}</p>
    <div class="player-error-actions">
      ${isNative ? '<button class="btn small primary" data-native>iPhone player</button>' : ''}
      ${externalPlayers(c.url).map(x => `<a class="btn small" href="${esc(x.href)}">Open in ${x.name}</a>`).join('')}
      <button class="btn small" data-copy>Copy link</button>
      <button class="btn small" data-retry>Retry</button>
    </div>`;
  box.hidden = false;
  box.querySelector('[data-copy]').onclick = () => copyText(c.url, 'Link copied');
  box.querySelector('[data-native]')?.addEventListener('click', () => openNativePlayer(c));
  box.querySelector('[data-retry]').onclick = () => playChannel(state.playing.list, state.playing.index);
  setStatus("Couldn't play this stream", true);
}

function stopPlaying() {
  trackProgress(true);
  const c = nowChannel();
  if (c) unwarm(streamOf(c));   // free the provider connection now, not after the idle timeout
  player.stop();
  state.playing = null;
  $('#player').hidden = true;
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
