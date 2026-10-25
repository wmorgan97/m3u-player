// Tiny IndexedDB wrapper. Playlists can hold tens of thousands of channels,
// which is far more than localStorage allows. Channels are stored in small
// chunks (not one giant record) so a huge playlist does not spike memory and
// crash the browser when it is saved — the failure mode on iPhone at ~19k
// channels, where a single put() of the whole playlist aborted the write.

const CHUNK = 1000;                 // channels per stored record
const chunkKey = (id, i) => `${id}#${String(i).padStart(6, '0')}`;
const chunkRange = id => IDBKeyRange.bound(`${id}#`, `${id}#￿`);

let opening;
function open() {
  return opening ??= new Promise((resolve, reject) => {
    const req = indexedDB.open('m3u-player', 2);
    req.onupgradeneeded = () => {
      const d = req.result;
      if (!d.objectStoreNames.contains('playlists')) d.createObjectStore('playlists', { keyPath: 'id' });
      if (!d.objectStoreNames.contains('kv')) d.createObjectStore('kv');
      if (!d.objectStoreNames.contains('chunks')) d.createObjectStore('chunks'); // key: `${playlistId}#<index>`
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

// Single-store transaction (kv and simple ops). Resolves with the request result.
async function run(store, mode, fn) {
  const conn = await open();
  return new Promise((resolve, reject) => {
    const tx = conn.transaction(store, mode);
    const req = fn(tx.objectStore(store));
    tx.oncomplete = () => resolve(req?.result);
    tx.onerror = tx.onabort = () => reject(tx.error);
  });
}

// Multi-store transaction. `fn` receives the stores in order; its return value
// is resolved once the transaction completes.
async function multi(stores, mode, fn) {
  const conn = await open();
  return new Promise((resolve, reject) => {
    const tx = conn.transaction(stores, mode);
    const out = fn(...stores.map(s => tx.objectStore(s)));
    tx.oncomplete = () => resolve(out);
    tx.onerror = tx.onabort = () => reject(tx.error);
  });
}

// Write a playlist as metadata (no channels) + N small channel chunks, all in
// one transaction. Old chunks for this id are cleared first so a re-save never
// leaves stragglers behind.
// Only chunks whose channels changed are rewritten: hiding a group or pinning it on a 19k-channel
// playlist saves in milliseconds instead of rewriting every chunk.
const chunkCache = new Map();   // pid -> [json of each chunk as last saved]
async function putPlaylist(p) {
  const { channels = [], ...meta } = p;
  meta.channelCount = channels.length;
  const prev = chunkCache.get(p.id);
  const next = [];
  for (let i = 0; i * CHUNK < channels.length; i++) next.push(JSON.stringify(channels.slice(i * CHUNK, (i + 1) * CHUNK)));
  await multi(['playlists', 'chunks'], 'readwrite', (pl, ch) => {
    pl.put(meta);
    if (!prev) ch.delete(chunkRange(p.id));
    next.forEach((json, i) => { if (!prev || json !== prev[i]) ch.put({ pid: p.id, i, channels: JSON.parse(json) }, chunkKey(p.id, i)); });
    for (let i = next.length; i < (prev?.length || 0); i++) ch.delete(chunkKey(p.id, i));
  });
  chunkCache.set(p.id, next);
}

// Read every playlist, reassembling each one's channels from its chunks.
// Legacy v1 records kept the channels inline; those are used as-is and get
// migrated to chunked form the next time they are saved.
async function allPlaylists() {
  const [metas, chunks] = await Promise.all([
    run('playlists', 'readonly', s => s.getAll()),
    run('chunks', 'readonly', s => s.getAll()),
  ]);
  const byId = new Map();
  for (const c of chunks) {
    if (!byId.has(c.pid)) byId.set(c.pid, []);
    byId.get(c.pid)[c.i] = c.channels;
  }
  return metas.map(m => {
    if (Array.isArray(m.channels)) return m;                 // legacy inline record
    const parts = byId.get(m.id) || [];
    chunkCache.set(m.id, parts.map(c => JSON.stringify(c || [])));
    return { ...m, channels: parts.filter(Boolean).flat() };
  });
}

async function deletePlaylist(id) {
  chunkCache.delete(id);
  await multi(['playlists', 'chunks'], 'readwrite', (pl, ch) => {
    pl.delete(id);
    ch.delete(chunkRange(id));
  });
}

export const db = {
  allPlaylists,
  putPlaylist,
  deletePlaylist,
  get: key => run('kv', 'readonly', s => s.get(key)),
  set: (key, value) => run('kv', 'readwrite', s => s.put(value, key)),
  del: key => run('kv', 'readwrite', s => s.delete(key)),
  clear: () => (chunkCache.clear(), Promise.all([
    run('playlists', 'readwrite', s => s.clear()),
    run('chunks', 'readwrite', s => s.clear()),
    run('kv', 'readwrite', s => s.clear()),
  ])),
};
