# M3U Player

An installable web app (PWA) for playing and organising M3U / M3U8 IPTV playlists, built mobile-first for iPhone.

## Features

**Watch**
- Add playlists from a link, an **Xtream** login (server / username / password), a file, or pasted text
- Browse by group, search thousands of channels instantly, favorites and recently watched
- Native HLS playback on iPhone (with Picture-in-Picture and AirPlay); hls.js in other browsers
- Previous / next channel, lock-screen controls
- `.ts` IPTV links are automatically tried as `.m3u8` first (playable on iPhone)
- Any stream that won't play in the browser: one tap to **Open in VLC / Infuse**, or copy the link

**Edit**
- Edit channel name, group, stream URL and logo; add channels by hand
- Multi-select → add to favorites, move to group, copy to another/new playlist (merge), export, delete (with undo)
- Rename / delete groups, sort A–Z or by group, remove duplicates, refresh from the source URL
- Export as `.m3u`: share sheet (Save to Files, AirDrop, open in VLC), download, or copy as text

Everything is stored on the device (IndexedDB). Nothing is sent anywhere.

## Install on iPhone

1. Open the app's URL in **Safari**.
2. Tap **Share → Add to Home Screen**.

It then opens full-screen like a native app, works offline (the app itself), and iOS won't clear its data.

## Hosting

The included workflow (`.github/workflows/pages.yml`) runs the tests and deploys to **GitHub Pages** on every push to `main`.
One-time setup: repo **Settings → Pages → Source: GitHub Actions**. (Pages on a private repo needs a paid GitHub plan; otherwise make the repo public. It holds no secrets.)

Any static host works too (Netlify, Cloudflare Pages, Vercel): just serve the repo root. There's no build step.

## Browser limitations (why some streams need VLC)

A web app plays by the browser's rules:

- **http:// streams on an https:// site** are often blocked as mixed content. Many IPTV providers only offer http.
- **Raw MPEG-TS (`.ts`)** isn't playable in Safari. The app tries the `.m3u8` variant first, which most Xtream panels provide.
- **Downloading a playlist** from a server that doesn't send CORS headers fails in the browser. Use the File tab, or set a CORS proxy in Settings. (Playback of HLS on iPhone is *not* affected by CORS.)
- Custom headers (`#EXTVLCOPT:http-user-agent=…`) are kept in exports but can't be applied by a browser.

For those cases, the app hands the stream to VLC or Infuse. A native SwiftUI app would remove these limits; the parser and data model here would port directly.

## Development

```sh
npm start        # serves on http://localhost:8080
npm test         # parser unit tests (node --test)
```

Open the LAN address on your phone to test on a real device. To update hls.js: bump it in `package.json`, `npm install`, `npm run vendor`.

```
index.html           app shell
css/app.css          styles
js/app.js            views, routing, playlist/channel actions
js/m3u.js            M3U parser / writer
js/player.js         playback (native HLS, hls.js, fallbacks, external players)
js/db.js             IndexedDB storage
js/ui.js             sheets, menus, forms, toasts, icons
sw.js                offline cache for the app shell
vendor/hls.min.js    hls.js (vendored, no build step)
```
