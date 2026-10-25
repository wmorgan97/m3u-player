import { parseM3U, toM3U, isHlsMedia, groupsOf, dedupe, uid, makeChannel, nameFromUrl } from './m3u.js';
import { db } from './db.js';
import { Player, externalPlayers } from './player.js';
import { $, esc, icon, toast, sheet, closeSheet, menu, confirmDialog, formSheet } from './ui.js';

const PAGE = 150;
const UNGROUPED = 'Ungrouped';
const MAX_RECENTS = 50;

const state = {
  playlists: [],
  favorites: [],
  recents: [],
  settings: { proxy: '', tsToHls: true },
  current: null,   // playlist being viewed (real, or virtual 'fav' / 'recent')
  group: null,     // null = all groups
  query: '',
  filtered: [],
  rendered: 0,
  selecting: false,
  selected: new Set(),
  playing: null,   // { list, index }
};
const favUrls = new Set();
const syncFavs = () => { favUrls.clear(); for (const c of state.favorites) favUrls.add(c.url); };

const view = $('#view');
const player = new Player($('#video'));
const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });
const observer = new IntersectionObserver(es => { if (es.some(e => e.isIntersecting)) renderMore(); }, { rootMargin: '800px' });

const plural = (n, word) => `${n.toLocaleString()} ${word}${n === 1 ? '' : 's'}`;
const host = url => { try { return new URL(url).hostname; } catch { return ''; } };
const mixedContent = url => location.protocol === 'https:' && /^http:/i.test(url);
const cloneChannel = c => ({ ...c, id: uid(), attrs: { ...c.attrs }, opts: [...(c.opts || [])] });
const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const isStandalone = matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;

// ---------- boot ----------
async function init() {
  const [playlists, favorites, recents, settings] = await Promise.all([
    db.allPlaylists(), db.get('favorites'), db.get('recents'), db.get('settings'),
  ]);
  state.playlists = playlists.sort((a, b) => a.createdAt - b.createdAt);
  state.favorites = favorites || [];
  state.recents = recents || [];
  Object.assign(state.settings, settings);
  player.tsToHls = state.settings.tsToHls;
  syncFavs();
  bindPlayer();
  $('#back').innerHTML = icon('back');
  $('#back').onclick = () => { location.hash = '#/'; };
  window.addEventListener('hashchange', route);
  route();
  if ('serviceWorker' in navigator && location.protocol !== 'file:') navigator.serviceWorker.register('sw.js').catch(() => {});
}

async function save(p) {
  p.updatedAt = Date.now();
  if (p.id === 'fav') { state.favorites = p.channels; syncFavs(); await db.set('favorites', p.channels); }
  else if (p.id === 'recent') { state.recents = p.channels; await db.set('recents', p.channels); }
  else await db.putPlaylist(p);
}

async function createPlaylist(name, channels, extra = {}) {
  const p = { id: uid(), name: name || 'Playlist', channels, header: {}, createdAt: Date.now(), updatedAt: Date.now(), ...extra };
  await db.putPlaylist(p);
  state.playlists.push(p);
  navigator.storage?.persist?.().catch(() => {});
  return p;
}

function virtualPlaylist(id) {
  return id === 'fav'
    ? { id, name: 'Favorites', virtual: true, header: {}, channels: state.favorites }
    : { id, name: 'Recently watched', virtual: true, header: {}, channels: state.recents };
}

// ---------- routing ----------
function route() {
  const [, kind, id] = (location.hash || '#/').slice(1).split('/');
  state.selecting = false;
  state.selected.clear();
  observer.disconnect();
  if (kind === 'p') {
    const p = state.playlists.find(p => p.id === id);
    if (p) return showPlaylist(p);
    location.hash = '#/';
    return;
  }
  if (kind === 'fav' || kind === 'recent') return showPlaylist(virtualPlaylist(kind));
  showHome();
}

function rerender() {
  if (state.current) refreshPlaylistView();
  else showHome();
}

function setTopbar({ title, back = false, actions = [] }) {
  $('#title').textContent = title;
  $('#back').hidden = !back;
  const box = $('#actions');
  box.innerHTML = actions.map((a, i) =>
    `<button class="icon-btn ${a.active ? 'active' : ''}" data-i="${i}" aria-label="${esc(a.label)}" title="${esc(a.label)}">${icon(a.icon)}</button>`).join('');
  box.onclick = e => { const b = e.target.closest('button'); if (b) actions[+b.dataset.i].fn(); };
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
  view.innerHTML = `
    ${isIOS && !isStandalone && !dismissed ? `<div class="banner">
      ${icon('share')}<span><b>Install on your iPhone:</b> tap Share, then “Add to Home Screen”. It opens full-screen and iOS keeps your playlists safe.</span>
      <button class="icon-btn" id="tip-close" aria-label="Dismiss">${icon('close')}</button></div>` : ''}
    <div class="section-title">Library</div>
    <ul class="cards">
      ${card('#/fav', 'star', 'Favorites', plural(state.favorites.length, 'channel'))}
      ${card('#/recent', 'clock', 'Recently watched', plural(state.recents.length, 'channel'))}
    </ul>
    <div class="section-title">Playlists</div>
    ${pl.length
      ? `<ul class="cards">${pl.map(p => card(`#/p/${p.id}`, 'list', p.name,
          `${plural(p.channels.length, 'channel')} · ${plural(groupsOf(p.channels).length, 'group')}${p.sourceUrl ? ' · ' + esc(host(p.sourceUrl)) : ''}`, p.id)).join('')}</ul>`
      : `<div class="empty">${icon('tv', 'big')}<p><b>No playlists yet</b></p>
          <p class="muted">Add an M3U / M3U8 link, an Xtream login, or a playlist file.</p>
          <button class="btn primary" id="add-first">Add playlist</button></div>`}`;

  $('#add-first')?.addEventListener('click', addPlaylistSheet);
  $('#tip-close')?.addEventListener('click', () => {
    try { localStorage.setItem('install-tip', 'off'); } catch {}
    $('.banner').remove();
  });
  view.onclick = e => {
    const m = e.target.closest('[data-menu]');
    if (m) return playlistMenu(state.playlists.find(p => p.id === m.dataset.menu));
    const go = e.target.closest('[data-go]');
    if (go) location.hash = go.dataset.go;
  };
}

// ---------- playlist view ----------
function showPlaylist(p) {
  state.current = p;
  state.group = null;
  state.query = '';
  view.onclick = null;
  view.innerHTML = `
    <div class="filters">
      <label class="search">${icon('search')}<input id="q" type="search" placeholder="Search" autocomplete="off" autocorrect="off" spellcheck="false"></label>
      <div class="chips" id="chips"></div>
    </div>
    <ul class="list" id="list"></ul>
    <div id="sentinel" class="sentinel"></div>
    <div class="selbar" id="selbar" hidden></div>`;
  $('#q').oninput = debounce(e => { state.query = e.target.value.trim().toLowerCase(); applyFilter(); }, 120);
  $('#chips').onclick = e => {
    const b = e.target.closest('.chip');
    if (!b) return;
    state.group = b.hasAttribute('data-all') ? null : b.dataset.group;
    renderChips();
    applyFilter();
  };
  $('#list').onclick = onListClick;
  observer.observe($('#sentinel'));
  refreshPlaylistView();
  window.scrollTo(0, 0);
}

function refreshPlaylistView() {
  updateTopbar();
  renderChips();
  applyFilter();
}

function updateTopbar() {
  const p = state.current;
  setTopbar({
    title: p.name,
    back: true,
    actions: [
      p.id !== 'recent' && p.channels.length && { icon: 'select', label: state.selecting ? 'Done selecting' : 'Select', active: state.selecting, fn: toggleSelecting },
      { icon: 'more', label: 'Playlist options', fn: () => playlistMenu(p) },
    ].filter(Boolean),
  });
}

function renderChips() {
  const p = state.current;
  const groups = groupsOf(p.channels);
  if (state.group !== null && !groups.some(g => g.name === state.group)) state.group = null;
  const chip = (label, n, active, attr) =>
    `<button class="chip ${active ? 'active' : ''}" ${attr}>${esc(label)}<span>${n.toLocaleString()}</span></button>`;
  const showGroups = groups.length > 1 || (groups[0] && groups[0].name);
  $('#chips').hidden = !showGroups;
  $('#chips').innerHTML = chip('All', p.channels.length, state.group === null, 'data-all') +
    (showGroups ? groups.map(g => chip(g.name || UNGROUPED, g.count, state.group === g.name, `data-group="${esc(g.name)}"`)).join('') : '');
}

function applyFilter() {
  const { current: p, group, query } = state;
  state.filtered = p.channels.filter(c =>
    (group === null || (c.group || '') === group) &&
    (!query || c.name.toLowerCase().includes(query) || (c.group || '').toLowerCase().includes(query)));
  $('#q').placeholder = `Search ${plural(p.channels.length, 'channel')}`;
  const list = $('#list');
  list.innerHTML = '';
  state.rendered = 0;
  if (state.filtered.length) renderMore();
  else {
    const msg = p.channels.length ? 'No matches.'
      : p.id === 'fav' ? 'Tap the star on any channel to add it here.'
      : p.id === 'recent' ? 'Channels you play show up here.'
      : 'This playlist is empty. Use ⋯ → Add channel.';
    list.innerHTML = `<li class="empty small">${esc(msg)}</li>`;
  }
  renderSelbar();
}

function renderMore() {
  const list = $('#list');
  if (!list || state.rendered >= state.filtered.length) return;
  const end = Math.min(state.rendered + PAGE, state.filtered.length);
  list.insertAdjacentHTML('beforeend', state.filtered.slice(state.rendered, end).map((c, k) => rowHtml(c, state.rendered + k)).join(''));
  state.rendered = end;
}

function logoHtml(c) {
  const initials = c.name.split(/\s+/).map(w => (w.match(/[\p{L}\p{N}]/u) || [''])[0]).join('').slice(0, 2).toUpperCase() || '?';
  return `<span class="logo" data-initials="${esc(initials)}">${c.logo
    ? `<img src="${esc(c.logo)}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" onerror="this.remove()">` : ''}</span>`;
}

const nowChannel = () => state.playing && state.playing.list[state.playing.index];

function rowHtml(c, i) {
  const selected = state.selected.has(c.id);
  const playing = nowChannel()?.url === c.url;
  return `<li class="row${selected ? ' selected' : ''}${playing ? ' playing' : ''}" data-i="${i}">
    ${state.selecting ? `<span class="check">${selected ? icon('check') : ''}</span>` : ''}
    ${logoHtml(c)}
    <span class="row-text"><span class="row-name">${esc(c.name)}</span>${c.group ? `<span class="muted">${esc(c.group)}</span>` : ''}</span>
    ${state.selecting ? '' : `<button class="icon-btn star${favUrls.has(c.url) ? ' on' : ''}" data-act="fav" aria-label="Favorite">${icon('star')}</button>
    <button class="icon-btn" data-act="more" aria-label="Channel options">${icon('more')}</button>`}
  </li>`;
}

function onListClick(e) {
  const row = e.target.closest('.row');
  if (!row) return;
  const i = +row.dataset.i;
  const c = state.filtered[i];
  const act = e.target.closest('[data-act]')?.dataset.act;
  if (state.selecting) {
    if (state.selected.has(c.id)) state.selected.delete(c.id); else state.selected.add(c.id);
    row.classList.toggle('selected', state.selected.has(c.id));
    row.querySelector('.check').innerHTML = state.selected.has(c.id) ? icon('check') : '';
    return renderSelbar();
  }
  if (act === 'fav') return toggleFavorite(c, e.target.closest('button'));
  if (act === 'more') return channelMenu(c);
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
  button?.classList.toggle('on', adding);
  if (state.current?.id === 'fav') { state.current.channels = state.favorites; refreshPlaylistView(); }
  toast(adding ? 'Added to Favorites' : 'Removed from Favorites');
}

async function addFavorites(channels) {
  const fresh = dedupe(channels).filter(c => !favUrls.has(c.url)).map(cloneChannel);
  state.favorites = [...state.favorites, ...fresh];
  syncFavs();
  await db.set('favorites', state.favorites);
  toast(`Added ${plural(fresh.length, 'channel')} to Favorites`);
}

// ---------- selection ----------
function toggleSelecting() {
  state.selecting = !state.selecting;
  state.selected.clear();
  updateTopbar();
  applyFilter();
}

function selectedChannels() {
  return state.current.channels.filter(c => state.selected.has(c.id));
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
  const chosen = selectedChannels();
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
  p.channels = p.channels.filter(c => !ids.has(c.id));
  const removed = before.length - p.channels.length;
  await save(p);
  state.selected.clear();
  rerender();
  toast(`Deleted ${plural(removed, 'channel')}`, {
    action: 'Undo',
    fn: async () => { p.channels = before; await save(p); rerender(); },
  });
}

// ---------- channel actions ----------
function channelMenu(c) {
  const p = state.current;
  const editable = p && p.id !== 'recent' && p.channels.includes(c);
  const fromPlayer = nowChannel() === c;
  menu(c.name, [
    !fromPlayer && { label: 'Play', fn: () => { const i = state.filtered.indexOf(c); playChannel(i >= 0 ? state.filtered : [c], Math.max(i, 0)); } },
    { label: favUrls.has(c.url) ? 'Remove from Favorites' : 'Add to Favorites', fn: () => toggleFavorite(c, null).then(highlightStars) },
    editable && { label: 'Edit…', fn: () => editChannel(c, p) },
    ...externalPlayers(c.url).map(x => ({ label: `Open in ${x.name}`, fn: () => { location.href = x.href; } })),
    { label: 'Copy stream link', fn: () => copyText(c.url, 'Link copied') },
    { label: 'Copy to playlist…', fn: () => copyToMenu([c]) },
    editable && { label: 'Delete', danger: true, fn: () => deleteChannels(p, new Set([c.id])) },
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
  ];
}

async function editChannel(c, p) {
  const v = await formSheet({ title: 'Edit channel', fields: channelFields(c, p), submit: 'Save' });
  if (!v) return;
  Object.assign(c, v);
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

// ---------- playlist actions ----------
function playlistMenu(p) {
  const real = !p.virtual;
  const viewing = state.current === p;
  const narrowed = viewing && (state.group !== null || state.query);
  const resort = async compare => { p.channels = [...p.channels].sort(compare); await save(p); rerender(); toast('Sorted'); };
  menu(p.name, [
    real && { label: 'Add channel…', fn: () => addChannel(p) },
    p.channels.length && { label: 'Export / share M3U…', fn: () => exportMenu(p.name, p.channels, p.header) },
    narrowed && state.filtered.length && { label: `Export this view (${state.filtered.length.toLocaleString()})…`, fn: () => exportMenu(`${p.name} - ${state.group || 'search'}`, state.filtered, p.header) },
    real && p.sourceUrl && { label: 'Refresh from source', fn: () => refreshSource(p) },
    p.id !== 'recent' && p.channels.length > 1 && { label: 'Sort A–Z', fn: () => resort((a, b) => collator.compare(a.name, b.name)) },
    p.id !== 'recent' && p.channels.length > 1 && { label: 'Sort by group', fn: () => resort((a, b) => collator.compare(a.group || '￿', b.group || '￿')) },
    real && p.channels.length > 1 && { label: 'Remove duplicates', fn: async () => {
      const before = p.channels.length;
      p.channels = dedupe(p.channels);
      await save(p);
      rerender();
      toast(`Removed ${plural(before - p.channels.length, 'duplicate')}`);
    } },
    p.id !== 'recent' && p.channels.length && { label: 'Manage groups…', fn: () => groupsSheet(p) },
    real && { label: 'Rename…', fn: async () => {
      const v = await formSheet({ title: 'Rename playlist', fields: [{ name: 'name', label: 'Name', value: p.name, required: true }] });
      if (!v) return;
      p.name = v.name;
      await save(p);
      rerender();
    } },
    real && { label: 'Delete playlist', danger: true, fn: async () => {
      if (!await confirmDialog(`Delete “${p.name}” and its ${plural(p.channels.length, 'channel')}? Favorites are kept.`, 'Delete', true)) return;
      await db.deletePlaylist(p.id);
      state.playlists = state.playlists.filter(x => x !== p);
      if (viewing) location.hash = '#/'; else showHome();
      toast('Playlist deleted');
    } },
    p.id === 'recent' && p.channels.length && { label: 'Clear history', danger: true, fn: async () => {
      p.channels = [];
      await save(p);
      rerender();
    } },
  ]);
}

function groupsSheet(p) {
  const groups = groupsOf(p.channels);
  sheet(`<div class="sheet-title">Groups</div>
    <ul class="glist">${groups.map((g, i) => `<li>
      <span class="row-text"><span class="row-name">${esc(g.name || UNGROUPED)}</span><span class="muted">${plural(g.count, 'channel')}</span></span>
      <button class="btn small" data-rename="${i}">Rename</button>
      <button class="btn small danger" data-del="${i}">Delete</button></li>`).join('')}</ul>`, {
    onMount: el => {
      el.onclick = async e => {
        const r = e.target.closest('[data-rename]');
        const d = e.target.closest('[data-del]');
        const g = groups[+(r || d)?.dataset[r ? 'rename' : 'del']];
        if (!g) return;
        const inGroup = p.channels.filter(c => (c.group || '') === g.name);
        if (r) {
          const v = await formSheet({ title: 'Rename group', fields: [{ name: 'name', label: 'Group name', value: g.name }], submit: 'Rename' });
          if (!v) return;
          inGroup.forEach(c => { c.group = v.name; });
          await save(p);
          if (state.group === g.name) state.group = v.name;
          rerender();
          toast('Group renamed');
        } else {
          if (!await confirmDialog(`Delete “${g.name || UNGROUPED}” and its ${plural(g.count, 'channel')}?`, 'Delete', true)) return;
          await deleteChannels(p, new Set(inGroup.map(c => c.id)));
        }
      };
    },
  });
}

function exportMenu(name, channels, header = {}) {
  const fileName = `${name.replace(/[\\/:*?"<>|]+/g, '_').trim() || 'playlist'}.m3u`;
  const file = () => new File([toM3U(channels, header)], fileName, { type: 'audio/x-mpegurl' });
  menu(`Export ${plural(channels.length, 'channel')}`, [
    navigator.canShare && { label: 'Share… (Save to Files, AirDrop, VLC)', fn: () => {
      const f = file();
      if (navigator.canShare({ files: [f] })) navigator.share({ files: [f], title: name }).catch(() => {});
      else download(f);
    } },
    { label: 'Download .m3u file', fn: () => download(file()) },
    { label: 'Copy as text', fn: () => copyText(toM3U(channels, header), 'Playlist copied') },
  ]);
}

function download(file) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(file);
  a.download = file.name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 10000);
}

// ---------- importing ----------
async function fetchText(url) {
  const get = async u => {
    const res = await fetch(u, { cache: 'no-store' });
    if (!res.ok) throw new Error(`The server replied ${res.status}.`);
    return res.text();
  };
  try {
    return await get(url);
  } catch (err) {
    const proxy = state.settings.proxy.trim();
    if (proxy) {
      try { return await get(proxy.includes('{url}') ? proxy.replace('{url}', encodeURIComponent(url)) : proxy + url); } catch {}
    }
    if (err.message.startsWith('The server replied')) throw err;
    throw new Error(mixedContent(url)
      ? 'This link is http:// and the app is on https://, so the browser blocked it. Download the file and use the File tab, or set a proxy in Settings.'
      : "Couldn't download the playlist. The server probably doesn't let web apps read it. Download it and use the File tab, or set a CORS proxy in Settings.");
  }
}

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
    return parseSource(await fetchText(url), url);
  } catch (err) {
    // A single HLS stream can still play natively even when we can't read it.
    if (/\.m3u8(\?|$)/i.test(url) && !/get\.php/i.test(url)) {
      return { header: {}, channels: [makeChannel({ name: nameFromUrl(url), url })], single: true };
    }
    throw err;
  }
}

function xtreamUrl(server, user, pass) {
  let base = server.trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(base)) base = 'http://' + base;
  return `${base}/get.php?username=${encodeURIComponent(user)}&password=${encodeURIComponent(pass)}&type=m3u_plus&output=m3u8`;
}

const ADD_HINTS = {
  url: 'A link to an .m3u / .m3u8 playlist, or to a single stream.',
  xtream: 'Your IPTV provider’s server address and login. The app fetches the channel list in HLS format, which iPhone plays natively.',
  file: 'Pick an .m3u or .m3u8 file from Files, iCloud Drive, etc.',
  paste: 'Paste playlist text, or simply one stream link per line.',
};

function addPlaylistSheet() {
  sheet(`<div class="sheet-title">Add playlist</div>
    <div class="tabs">
      <button class="tab active" data-tab="url">Link</button><button class="tab" data-tab="xtream">Xtream</button>
      <button class="tab" data-tab="file">File</button><button class="tab" data-tab="paste">Paste</button>
    </div>
    <form class="form" id="addform">
      <label>Name<input name="name" placeholder="Optional" autocorrect="off"></label>
      <div class="pane" data-pane="url"><label>Playlist URL<input name="url" type="url" inputmode="url" placeholder="https://example.com/playlist.m3u" autocapitalize="off" autocorrect="off" spellcheck="false"></label></div>
      <div class="pane" data-pane="xtream" hidden>
        <label>Server<input name="server" inputmode="url" placeholder="http://provider.example:8080" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
        <label>Username<input name="user" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
        <label>Password<input name="pass" type="password"></label>
      </div>
      <div class="pane" data-pane="file" hidden><label>Playlist file<input name="file" type="file"></label></div>
      <div class="pane" data-pane="paste" hidden><label>M3U text<textarea name="text" rows="6" placeholder="#EXTM3U&#10;#EXTINF:-1 group-title=&quot;News&quot;,My Channel&#10;https://example.com/live.m3u8" spellcheck="false"></textarea></label></div>
      <p class="muted" id="add-hint">${ADD_HINTS.url}</p>
      <div class="form-actions"><button type="button" class="btn" data-empty>Create empty</button><button class="btn primary" data-submit>Add</button></div>
    </form>`, {
    onMount: el => {
      let tab = 'url';
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
        try {
          let result, sourceUrl;
          if (tab === 'url' || tab === 'xtream') {
            if (tab === 'url') {
              sourceUrl = String(f.get('url')).trim();
              if (!sourceUrl) throw new Error('Enter a playlist link.');
            } else {
              const [server, user, pass] = ['server', 'user', 'pass'].map(k => String(f.get(k)).trim());
              if (!server || !user) throw new Error('Enter the server and username.');
              sourceUrl = xtreamUrl(server, user, pass);
            }
            result = await loadFromUrl(sourceUrl);
            name ||= result.single ? result.channels[0].name : host(sourceUrl) || 'Playlist';
          } else if (tab === 'file') {
            const file = f.get('file');
            if (!file?.size) throw new Error('Choose a file.');
            result = parseSource(await file.text());
            name ||= file.name.replace(/\.[^.]+$/, '');
          } else {
            result = parseSource(String(f.get('text')));
            name ||= 'Pasted playlist';
          }
          const p = await createPlaylist(name, result.channels, { header: result.header, sourceUrl: result.single ? undefined : sourceUrl });
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

async function refreshSource(p) {
  toast('Refreshing…');
  try {
    const { header, channels } = parseSource(await fetchText(p.sourceUrl), p.sourceUrl);
    const before = p.channels;
    p.channels = channels;
    p.header = header;
    await save(p);
    rerender();
    toast(`Updated: ${plural(channels.length, 'channel')}`, { action: 'Undo', fn: async () => { p.channels = before; await save(p); rerender(); } });
  } catch (err) {
    toast(err.message);
  }
}

// ---------- settings ----------
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
      <label class="switch"><span><b>Try HLS for .ts links</b><span class="muted">Many IPTV providers also serve each .ts channel as .m3u8, which iPhone can play. Tried first, with the original link as backup.</span></span>
        <input type="checkbox" name="tsToHls" ${s.tsToHls ? 'checked' : ''}></label>
      <label>CORS proxy (optional)<input name="proxy" value="${esc(s.proxy)}" placeholder="https://proxy.example/?url={url}" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
      <p class="muted">Only used to download playlists from servers that block web apps. <code>{url}</code> is replaced with the link; otherwise the link is appended.</p>
      <div class="form-actions"><button class="btn primary">Save</button></div>
    </form>
    <div class="section-title">Data</div>
    <p class="muted">${plural(state.playlists.length, 'playlist')}, ${plural(total, 'channel')}${usage}. Everything is stored only on this device.</p>
    <div class="form-actions"><button class="btn danger" id="wipe">Erase all data</button></div>
    <div class="section-title">Tips</div>
    <p class="muted">If a stream won’t play here, use ⋯ → Open in VLC or Infuse. Those apps can play http:// and .ts streams that browsers can’t.</p>`, {
    onMount: el => {
      el.querySelector('form').onsubmit = async e => {
        e.preventDefault();
        const f = new FormData(e.target);
        state.settings = { ...s, proxy: String(f.get('proxy')).trim(), tsToHls: f.get('tsToHls') === 'on' };
        player.tsToHls = state.settings.tsToHls;
        await db.set('settings', state.settings);
        closeSheet();
        toast('Settings saved');
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
function setStatus(text, isError = false) {
  const el = $('#now-status');
  const c = nowChannel();
  el.textContent = text || c?.group || '';
  el.classList.toggle('error', isError);
}

function bindPlayer() {
  const v = $('#video');
  v.addEventListener('waiting', () => { if (state.playing) setStatus('Buffering…'); });
  v.addEventListener('playing', () => setStatus(''));
  player.onFatal = msg => setStatus(msg, true);
  $('#prev').innerHTML = icon('prev');
  $('#next').innerHTML = icon('next');
  $('#now-more').innerHTML = icon('more');
  $('#close').innerHTML = icon('close');
  $('#prev').onclick = () => step(-1);
  $('#next').onclick = () => step(1);
  $('#close').onclick = stopPlaying;
  $('#now-more').onclick = () => { const c = nowChannel(); if (c) channelMenu(c); };
  try {
    navigator.mediaSession?.setActionHandler('previoustrack', () => step(-1));
    navigator.mediaSession?.setActionHandler('nexttrack', () => step(1));
  } catch {}
}

function step(delta) {
  const pl = state.playing;
  if (!pl) return;
  const i = pl.index + delta;
  if (i >= 0 && i < pl.list.length) playChannel(pl.list, i);
}

function playChannel(list, index) {
  const c = list[index];
  if (!c) return;
  state.playing = { list, index };
  $('#player').hidden = false;
  $('#player-error').hidden = true;
  $('#now-name').textContent = c.name;
  $('#now-logo').outerHTML = logoHtml(c).replace('class="logo"', 'class="logo" id="now-logo"');
  $('#prev').disabled = index === 0;
  $('#next').disabled = index === list.length - 1;
  setStatus('Connecting…');
  highlightPlaying();
  addRecent(c);
  try {
    if ('mediaSession' in navigator) {
      navigator.mediaSession.metadata = new MediaMetadata({ title: c.name, artist: c.group || 'M3U Player', artwork: c.logo ? [{ src: c.logo }] : [] });
    }
  } catch {}
  player.play(c.url).then(ok => { if (ok) setStatus(''); }).catch(err => {
    if (nowChannel() === c) showPlayerError(c, err);
  });
}

function showPlayerError(c, err) {
  const box = $('#player-error');
  const extra = mixedContent(c.url) ? ' (This is an http:// stream, which browsers often block on https sites.)' : '';
  box.innerHTML = `<p>${esc(err.message + extra)}</p>
    <div class="player-error-actions">
      ${externalPlayers(c.url).map(x => `<a class="btn small" href="${esc(x.href)}">Open in ${x.name}</a>`).join('')}
      <button class="btn small" data-copy>Copy link</button>
      <button class="btn small" data-retry>Retry</button>
    </div>`;
  box.hidden = false;
  box.querySelector('[data-copy]').onclick = () => copyText(c.url, 'Link copied');
  box.querySelector('[data-retry]').onclick = () => playChannel(state.playing.list, state.playing.index);
  setStatus("Couldn't play this stream", true);
}

function stopPlaying() {
  player.stop();
  state.playing = null;
  $('#player').hidden = true;
  highlightPlaying();
}

async function addRecent(c) {
  state.recents = [cloneChannel(c), ...state.recents.filter(r => r.url !== c.url)].slice(0, MAX_RECENTS);
  await db.set('recents', state.recents);
}

init().catch(err => {
  view.innerHTML = `<div class="empty"><p><b>Couldn't start</b></p><p class="muted">${esc(err.message)}</p></div>`;
});
