// Personal proxy for M3U Player — runs free on Cloudflare Workers.
//
// It lets the web app (served over https) reach IPTV servers that only speak
// http:// or don't send CORS headers: playlists, the Xtream API, TV guides,
// and the streams themselves. HLS playlists are rewritten so every segment
// also goes through the proxy.
//
// Deploy: see proxy/README.md. Then in the app: Settings → Proxy →
//   https://<your-worker>.workers.dev/?url={url}
// Optional: set a secret named KEY in Cloudflare and add &key=<it> to that
// address so nobody else can use your proxy.

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET, HEAD, OPTIONS',
  'access-control-allow-headers': '*',
  'access-control-expose-headers': '*',
};

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const self = new URL(request.url);
    const target = self.searchParams.get('url');
    const key = self.searchParams.get('key');
    if (env.KEY && key !== env.KEY) return new Response('Forbidden', { status: 403, headers: CORS });
    if (!target || !/^https?:\/\//i.test(target)) {
      return new Response('M3U Player proxy is running. Use ?url=<link>', { status: 400, headers: CORS });
    }

    const headers = { 'user-agent': request.headers.get('user-agent') || 'Mozilla/5.0' };
    const range = request.headers.get('range');
    if (range) headers.range = range;

    let upstream;
    try {
      upstream = await fetch(target, { method: request.method, headers, redirect: 'follow' });
    } catch (e) {
      return new Response(`Upstream error: ${e.message}`, { status: 502, headers: CORS });
    }

    const out = new Headers(upstream.headers);
    for (const [k, v] of Object.entries(CORS)) out.set(k, v);
    for (const h of ['content-security-policy', 'x-frame-options', 'set-cookie']) out.delete(h);

    const type = out.get('content-type') || '';
    const path = new URL(upstream.url).pathname;
    const maybeHls = /mpegurl/i.test(type) || /\.m3u8$/i.test(path);
    if (maybeHls && request.method === 'GET' && upstream.ok) {
      const text = await upstream.text();
      out.delete('content-length');
      out.delete('content-encoding');
      const body = text.includes('#EXT-X-') ? rewrite(text, upstream.url, u => proxied(self, u, key)) : text;
      return new Response(body, { status: upstream.status, headers: out });
    }
    return new Response(upstream.body, { status: upstream.status, headers: out });
  },
};

function proxied(self, url, key) {
  return `${self.origin}${self.pathname}?url=${encodeURIComponent(url)}${key ? `&key=${encodeURIComponent(key)}` : ''}`;
}

// Points every segment, variant playlist and key in an HLS playlist at the proxy.
function rewrite(text, base, wrap) {
  return text.split('\n').map(line => {
    const t = line.trim();
    if (!t) return line;
    if (t.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (_, uri) => `URI="${wrap(new URL(uri, base).href)}"`);
    return wrap(new URL(t, base).href);
  }).join('\n');
}
