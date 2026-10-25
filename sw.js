// Caches the app shell so the app opens offline. Streams, logos and
// playlist downloads (other origins) always go straight to the network.

const CACHE = 'm3u-player-v2';
const SHELL = [
  './', 'index.html', 'manifest.webmanifest', 'css/app.css',
  'js/app.js', 'js/m3u.js', 'js/db.js', 'js/player.js', 'js/ui.js', 'js/net.js', 'js/xtream.js', 'js/epg.js', 'vendor/hls.min.js',
  'icons/icon.svg', 'icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png',
];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

// Stale-while-revalidate: serve from cache, refresh it in the background.
self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  e.respondWith(caches.open(CACHE).then(async cache => {
    const hit = await cache.match(req, { ignoreSearch: true });
    const fresh = fetch(req).then(res => {
      if (res.ok) cache.put(req, res.clone());
      return res;
    }).catch(() => hit);
    if (hit) { e.waitUntil(fresh); return hit; }
    return fresh;
  }));
});
