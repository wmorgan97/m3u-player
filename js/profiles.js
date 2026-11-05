// Viewer profiles ("Who's watching?"): each person sharing the server gets their own Continue watching, progress,
// favourites, Watch later, recents, follows and reminders; playlists and settings stay shared (server/sync.js).
// The profile list is synced ({ <id>: { name, color, at } }); which profile this device uses is kept on the device.
export const MAIN = 'main', MAX_PROFILES = 5;
// Calm, distinct tile colours (initial on colour), readable with white text.
export const PROFILE_COLORS = ['#3d7be0', '#d4813a', '#2a9d8f', '#8a6fd1', '#c95b8a', '#5b8c3a', '#c0504d', '#4f6d8f'];

export const initialOf = name => (String(name || '').trim().match(/\p{L}|\p{N}/u)?.[0] || '?').toLocaleUpperCase();
export const colorOf = p => PROFILE_COLORS[(p?.color | 0) % PROFILE_COLORS.length];
export const newProfileId = (now = Date.now()) => `p${now.toString(36)}${Math.random().toString(36).slice(2, 5)}`;

/** The profiles to show, main first, then in the order they were added. Main always exists (named "Main" until set). */
export function profileList(map) {
  const m = map && typeof map === 'object' ? map : {};
  const main = { id: MAIN, name: m[MAIN]?.name || 'Main', color: m[MAIN]?.color | 0, named: !!m[MAIN]?.name };
  const rest = Object.entries(m).filter(([id, v]) => id !== MAIN && v && !v.deleted && v.name)
    .map(([id, v]) => ({ id, name: v.name, color: v.color | 0 })).sort((a, b) => (a.id < b.id ? -1 : 1));
  return [main, ...rest];
}
/** A colour not used yet (by index), for a new profile. */
export function nextColor(map) {
  const used = new Set(profileList(map).map(p => p.color));
  for (let i = 0; i < PROFILE_COLORS.length; i++) if (!used.has(i)) return i;
  return profileList(map).length % PROFILE_COLORS.length;
}
/** Merges a profile list from the server into this device's copy (newest edit per id wins, removed stays removed). */
export function adoptProfiles(mine, remote) {
  const out = { ...(mine || {}) };
  for (const [id, v] of Object.entries(remote || {})) {
    if (!v || typeof v !== 'object' || out[id]?.deleted) continue;
    if (!out[id] || (v.at || 0) > (out[id].at || 0) || v.deleted) out[id] = v;
  }
  return out;
}
/** Whether to ask "Who's watching?" when the app opens: more than one profile and none remembered here. */
export const shouldAsk = (map, device) => profileList(map).length > 1 && !(device?.remember && profileList(map).some(p => p.id === device.id));
