// Xtream Codes API client (player_api.php): live TV, movies, series, EPG
// and catch-up URLs, as offered by most IPTV providers.
import { fetchJSON } from './net.js';
import { makeChannel } from './m3u.js';

export function normalizeServer(server) {
  let base = server.trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(base)) base = 'http://' + base;
  return base.replace(/\/(player_api|get|xmltv)\.php.*$/i, '');
}

// Recognises get.php / player_api.php links so they can use the richer API.
export function parseXtreamLink(url) {
  try {
    const u = new URL(url);
    const user = u.searchParams.get('username');
    if (user && /\/(get|player_api)\.php$/i.test(u.pathname)) {
      return { server: u.origin + u.pathname.replace(/\/(get|player_api)\.php$/i, ''), user, pass: u.searchParams.get('password') || '' };
    }
  } catch {}
  return null;
}

// "YYYY-MM-DD:HH-MM" in the server's time zone, as timeshift URLs expect.
export function formatServerTime(date, timeZone) {
  let parts;
  try {
    parts = new Intl.DateTimeFormat('en-GB', { timeZone: timeZone || undefined, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(date);
  } catch {
    parts = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(date);
  }
  const get = type => parts.find(p => p.type === type).value;
  return `${get('year')}-${get('month')}-${get('day')}:${get('hour')}-${get('minute')}`;
}

export class Xtream {
  constructor({ server, user, pass }) {
    this.server = normalizeServer(server);
    this.user = user;
    this.pass = pass;
  }

  get creds() { return `${encodeURIComponent(this.user)}/${encodeURIComponent(this.pass)}`; }

  api(action, params = {}) {
    const q = new URLSearchParams({ username: this.user, password: this.pass, ...(action ? { action } : {}), ...params });
    return fetchJSON(`${this.server}/player_api.php?${q}`, { timeout: 60000 });
  }

  async login() {
    const info = await this.api('');
    if (!info?.user_info || Number(info.user_info.auth) !== 1) throw new Error('Login failed. Check the server, username and password.');
    const status = info.user_info.status;
    if (status && status !== 'Active') throw new Error(`This account is ${status.toLowerCase()}.`);
    return info;
  }

  categories(type) { return this.api(`get_${type}_categories`).then(list => Array.isArray(list) ? list : []); }
  liveStreams() { return this.api('get_live_streams').then(list => Array.isArray(list) ? list : []); }
  vodStreams() { return this.api('get_vod_streams').then(list => Array.isArray(list) ? list : []); }
  seriesList() { return this.api('get_series').then(list => Array.isArray(list) ? list : []); }
  vodInfo(id) { return this.api('get_vod_info', { vod_id: id }); }
  seriesInfo(id) { return this.api('get_series_info', { series_id: id }); }

  liveUrl(id, ext = 'm3u8') { return `${this.server}/live/${this.creds}/${id}.${ext}`; }
  movieUrl(id, ext = 'mp4') { return `${this.server}/movie/${this.creds}/${id}.${ext || 'mp4'}`; }
  episodeUrl(id, ext = 'mp4') { return `${this.server}/series/${this.creds}/${id}.${ext || 'mp4'}`; }
  epgUrl() { return `${this.server}/xmltv.php?username=${encodeURIComponent(this.user)}&password=${encodeURIComponent(this.pass)}`; }
  timeshiftUrl(id, start, minutes, timeZone) {
    return `${this.server}/timeshift/${this.creds}/${minutes}/${formatServerTime(start, timeZone)}/${id}.ts`;
  }
}

// Orders items by their category's position in the provider's category list.
function byCategory(cats, items) {
  const order = new Map(cats.map((c, i) => [String(c.category_id), i]));
  const name = new Map(cats.map(c => [String(c.category_id), c.category_name]));
  const rank = it => order.get(String(it.category_id)) ?? 1e9;
  return { sorted: [...items].sort((a, b) => rank(a) - rank(b)), name: it => name.get(String(it.category_id)) || '' };
}

export function liveChannels(xt, cats, streams) {
  const { sorted, name } = byCategory(cats, streams);
  return sorted.map(s => makeChannel({
    name: String(s.name || `Channel ${s.stream_id}`).trim(),
    url: xt.liveUrl(s.stream_id),
    group: name(s),
    logo: s.stream_icon || '',
    tvgId: s.epg_channel_id || '',
    xt: { id: s.stream_id, archive: Number(s.tv_archive) ? Number(s.tv_archive_duration) || 1 : 0 },
  }));
}

const year = s => (String(s || '').match(/\b(19|20)\d\d\b/) || [''])[0];

export function movieItems(xt, cats, streams) {
  const { sorted, name } = byCategory(cats, streams);
  return sorted.map(s => ({
    id: `m${s.stream_id}`, kind: 'movie', sid: s.stream_id,
    name: String(s.name || '').trim(), logo: s.stream_icon || '', group: name(s),
    rating: Number(s.rating) || 0, year: year(s.year || s.releaseDate || s.name),
    url: xt.movieUrl(s.stream_id, s.container_extension), added: Number(s.added) || 0,
  }));
}

export function seriesItems(cats, list) {
  const { sorted, name } = byCategory(cats, list);
  return sorted.map(s => ({
    id: `s${s.series_id}`, kind: 'series', sid: s.series_id,
    name: String(s.name || '').trim(), logo: s.cover || '', group: name(s),
    rating: Number(s.rating) || 0, year: year(s.releaseDate || s.release_date || s.year),
    plot: s.plot || '', genre: s.genre || '',
  }));
}
