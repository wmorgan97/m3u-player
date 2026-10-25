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

## Host it on your own VPS (recommended)

One command installs the app, a built-in proxy and automatic HTTPS on a Debian/Ubuntu VPS. No domain needed (it uses a free `<your-ip>.sslip.io` address), and no Cloudflare.

```sh
git clone https://github.com/wmorgan97/m3u-player.git
sudo bash m3u-player/deploy/install.sh            # or: ... install.sh tv.yourdomain.com
```

It prints your app address and a **server key**. Open the address in Safari on your iPhone, Add to Home Screen, open the app and enter the key once.

- The built-in proxy is what lets http:// IPTV providers, Xtream logins and TV guides work in a web app. It only works with your key, so nobody else can use your VPS as a proxy.
- If nginx/Apache already uses ports 80/443, the installer leaves them alone and tells you what to point at `127.0.0.1:8787`.
- Update: `cd m3u-player && git pull && sudo systemctl restart m3u-player`
- If the repo is private, `git clone` asks for your GitHub username and a [personal access token](https://github.com/settings/tokens) as the password.

**Do I need the server after installing?** The app itself runs from your phone's storage once it's on the Home Screen, and all your data stays on the phone. The server is used for app updates and as the proxy for http:// providers. If your provider is https:// and allows web apps, the app works without it.

**Alternatives:** GitHub Pages or any static host (`.github/workflows/pages.yml` can deploy to Pages: enable Settings → Pages → Source: GitHub Actions and set the Actions variable `DEPLOY_PAGES` to `true`), plus the free [Cloudflare Worker proxy](proxy/README.md) entered under Settings → Proxy.

## What a web app can't do

- **Recording to the phone.** Use catch-up instead where your provider offers it.
- Raw MPEG-TS-only streams and some codecs (e.g. MKV movies with unusual audio) don't play in Safari. Use the *Open in VLC / Infuse* buttons for those.
- Custom headers (`#EXTVLCOPT:http-user-agent=…`) are kept in exports but can't be applied by a browser.

## Development

```sh
npm start        # app + proxy on http://localhost:8080 (server/server.js, no dependencies)
npm test         # unit tests: M3U parser, XMLTV guide, Xtream API, server/proxy
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
server/server.js     self-hosting server: app files + built-in proxy
deploy/install.sh    one-command VPS install (systemd + Caddy HTTPS)
proxy/worker.js      optional Cloudflare Worker proxy
vendor/hls.min.js    hls.js (vendored, no build step)
```
