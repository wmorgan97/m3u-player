# M3U Player

A free IPTV player for iPhone, built as an installable web app (PWA). It has the features other IPTV apps charge for, with no subscription, no ads and no account. Everything stays on your device.

## Features

**Sources**
- **Xtream Codes login** (server / username / password): Live TV, Movies and Series, plus account info (expiry, connections)
- M3U / M3U8 links, files, or pasted text; Xtream `get.php` links are upgraded to the full API automatically
- Auto-refresh: Xtream daily, M3U optional

**Watching**
- Native iPhone playback (AirPlay, Picture-in-Picture, lock-screen controls); hls.js in other browsers
- **TV guide (EPG)**: now/next on every channel, full guide grid, per-channel schedule. XMLTV `.xml` / `.xml.gz`, auto-detected from the playlist or Xtream
- **Catch-up / timeshift**: play past programmes, or restart the current one from the beginning (Xtream archive and M3U `catchup` attributes)
- **Movies & Series**: posters, plot, cast, rating, seasons and episodes, auto-play next episode
- **Continue watching** with resume position; watched episodes are ticked
- **Multi-view**: 2 or 4 channels at once, tap to choose which one you hear
- Audio track and subtitle selection, playback speed, **sleep timer**
- Favorites, recently watched, search across all playlists
- Previous / next channel; `.ts` IPTV links are tried as `.m3u8` first (playable on iPhone)
- Anything that won't play in the browser: one tap to **Open in VLC / Infuse**

**Organising**
- **Parental control**: lock groups behind a PIN
- Hide groups, reorder groups, rename / delete groups
- Edit channels (name, group, logo, link, guide ID), add channels by hand
- Multi-select → favorite, move to group, copy/merge into another playlist, export, delete (with undo)
- Sort A–Z or by group, remove duplicates
- Export as `.m3u` (share sheet / Files / AirDrop / VLC, download, or copy)
- **Backup & restore** everything to one file, to move to another device

## Install on iPhone

1. Open the app's URL in **Safari**.
2. Tap **Share → Add to Home Screen**.

It opens full-screen like a native app, starts offline, and iOS keeps its data.

## The free proxy (recommended)

Browsers block https:// web apps from talking to http:// servers, and many IPTV servers don't allow web apps at all. Most Xtream providers use http://, so you'll need this for Xtream logins, TV guides and http streams.

[`proxy/`](proxy/README.md) has a tiny Cloudflare Worker you can deploy for free in about 5 minutes (copy and paste, no coding). Then enter its address in **Settings → Proxy**.

Without it, M3U files with https:// streams still work fully.

## Hosting

`.github/workflows/pages.yml` runs the tests and deploys to **GitHub Pages** on every push to `main`. One-time setup: repo **Settings → Pages → Source: GitHub Actions**. Pages on a private repo needs a paid GitHub plan; otherwise make the repo public (it holds no secrets).

Any static host works too (Netlify, Cloudflare Pages, Vercel): serve the repo root. There's no build step.

## What a web app can't do

- **Recording to the phone.** Use catch-up instead where your provider offers it.
- Raw MPEG-TS-only streams and some codecs (e.g. MKV movies with unusual audio) don't play in Safari. Use the *Open in VLC / Infuse* buttons for those.
- Custom headers (`#EXTVLCOPT:http-user-agent=…`) are kept in exports but can't be applied by a browser.

## Development

```sh
npm start        # serves on http://localhost:8080
npm test         # unit tests: M3U parser, XMLTV guide, Xtream API mapping
```

Open the LAN address on your phone to test on a real device. To update hls.js: bump it in `package.json`, `npm install`, `npm run vendor`.

```
index.html           app shell
css/app.css          styles
js/app.js            views and actions: playlists, movies/series, guide, multi-view, settings
js/player.js         playback (native HLS, hls.js, fallbacks, tracks, PiP, external players)
js/xtream.js         Xtream Codes API client
js/epg.js            streaming XMLTV parser, now/next, catch-up URLs
js/m3u.js            M3U parser / writer
js/net.js            fetch with proxy fallback
js/db.js             IndexedDB storage
js/ui.js             sheets, menus, forms, toasts, icons
sw.js                offline cache for the app shell
proxy/worker.js      optional Cloudflare Worker proxy
vendor/hls.min.js    hls.js (vendored, no build step)
```
