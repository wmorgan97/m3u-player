// Caches the app shell so the app opens offline. Streams, logos and
// playlist downloads (other origins) always go straight to the network.

const CACHE = 'm3u-player-v102';
const SHELL = [
  './', 'index.html', 'manifest.webmanifest', 'css/app.css', 'css/polish.css', 'css/desktop.css',
  'js/app.js', 'js/m3u.js', 'js/db.js', 'js/player.js', 'js/ui.js', 'js/net.js', 'js/xtream.js', 'js/epg.js', 'js/epg-worker.js', 'js/vod.js', 'js/native.js', 'vendor/hls.min.js',
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

// Network first, cache as the offline fallback: a phone must never stay stuck on an old (or
// broken) copy of the app. A slow network falls back to the cache after 4 seconds.
self.addEventListener('fetch', e => {
  const req = e.request;
  const url = new URL(req.url);
  // Only the app's own files; never the proxy, transcode, sync or guide endpoints.
  if (req.method !== 'GET' || url.origin !== location.origin
      || url.pathname.endsWith('/proxy') || url.pathname === '/hls' || url.pathname.startsWith('/hls/')
      || url.pathname === '/sync' || url.pathname === '/guide' || url.pathname === '/vod' || url.pathname === '/download') return;
  e.respondWith(caches.open(CACHE).then(async cache => {
    const fresh = fetch(req, { cache: 'no-store' }).then(res => {
      if (res.ok) cache.put(req, res.clone());
      return res;
    });
    const timeout = new Promise(r => setTimeout(r, 4000));
    try {
      const res = await Promise.race([fresh, timeout]);
      if (res) return res;
    } catch {}
    const hit = await cache.match(req, { ignoreSearch: true });
    return hit || fresh;
  }));
});

// A reminder notification opens (or focuses) the app.
self.addEventListener('notificationclick', e => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(list => {
    const c = list.find(w => 'focus' in w);
    return c ? c.focus() : self.clients.openWindow('./');
  }));
});
