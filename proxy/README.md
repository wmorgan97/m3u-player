# Free personal proxy (Cloudflare Workers)

Browsers won't let an https:// web app talk to http:// IPTV servers, and many
servers don't allow web apps at all (CORS). This tiny proxy fixes both, so
Xtream logins, TV guides, playlists and http:// streams all work. It's free
for personal use (Cloudflare's free plan allows 100,000 requests a day).

## Set up (about 5 minutes, no coding)

1. Create a free account at <https://dash.cloudflare.com/sign-up>.
2. In the dashboard: **Workers & Pages → Create → Create Worker**. Give it a
   name (e.g. `m3u-proxy`) and click **Deploy**.
3. Click **Edit code**, delete everything, paste the contents of
   [`worker.js`](worker.js), and click **Deploy**.
4. Optional but recommended: **Settings → Variables and Secrets → Add**,
   type *Secret*, name `KEY`, value any password you like. Deploy again.
5. Copy your worker's address, e.g. `https://m3u-proxy.yourname.workers.dev`.
6. In M3U Player: **Settings → Proxy address**, enter

   ```
   https://m3u-proxy.yourname.workers.dev/?url={url}
   ```

   or, if you set a KEY:

   ```
   https://m3u-proxy.yourname.workers.dev/?key=YOURKEY&url={url}
   ```

## Notes

- **Streams through the proxy**: by default only http:// streams use it
  (the ones the browser would block). You can switch to *all streams* or *off*
  in Settings.
- Video through the proxy uses your Cloudflare quota. One person watching is
  fine; it isn't meant for sharing with lots of people.
- Your IPTV provider sees the proxy's IP address (Cloudflare's), not your
  phone's. A few providers block datacenter IPs; if so, use VLC/Infuse for
  those streams.
