// Tiny IndexedDB wrapper. Playlists can hold tens of thousands of channels,
// which is far more than localStorage allows.

let opening;
function open() {
  return opening ??= new Promise((resolve, reject) => {
    const req = indexedDB.open('m3u-player', 1);
    req.onupgradeneeded = () => {
      req.result.createObjectStore('playlists', { keyPath: 'id' });
      req.result.createObjectStore('kv');
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function run(store, mode, fn) {
  const conn = await open();
  return new Promise((resolve, reject) => {
    const tx = conn.transaction(store, mode);
    const req = fn(tx.objectStore(store));
    tx.oncomplete = () => resolve(req?.result);
    tx.onerror = tx.onabort = () => reject(tx.error);
  });
}

export const db = {
  allPlaylists: () => run('playlists', 'readonly', s => s.getAll()),
  putPlaylist: p => run('playlists', 'readwrite', s => s.put(p)),
  deletePlaylist: id => run('playlists', 'readwrite', s => s.delete(id)),
  get: key => run('kv', 'readonly', s => s.get(key)),
  set: (key, value) => run('kv', 'readwrite', s => s.put(value, key)),
  clear: () => Promise.all([run('playlists', 'readwrite', s => s.clear()), run('kv', 'readwrite', s => s.clear())]),
};
