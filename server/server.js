// M3U Player server: serves the app and a built-in proxy, with no dependencies.
//
//   node server/server.js            (Node 18+)
//
// Environment:
//   PORT           port to listen on (default 8080)
//   HOST           address to bind (default 0.0.0.0; the installer uses 127.0.0.1 behind Caddy)
//   PROXY_KEY      if set, the proxy only works for requests carrying ?key=<PROXY_KEY>
//   ALLOW_PRIVATE  set to 1 to let the proxy reach private/LAN addresses (off by default)
//
// The proxy (GET /proxy?url=<link>) lets the app reach http:// IPTV servers and
// servers without CORS headers. HLS playlists are rewritten so their segments
// also go through it. GET /proxy without a url reports whether a key is needed.

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Readable } from 'node:stream';
import { isIP } from 'node:net';
import { handleHls } from './transcode.js';

const root = fileURLToPath(new URL('..', import.meta.url));
const port = Number(process.env.PORT) || 8080;
const hostAddr = process.env.HOST || '0.0.0.0';
const proxyKey = process.env.PROXY_KEY || '';
const allowPrivate = process.env.ALLOW_PRIVATE === '1';

// Only the app's own files are served, never server code, tests or git data.
const PUBLIC = /^\/(index\.html|manifest\.webmanifest|sw\.js|(css|js|vendor|icons)\/[\w.-]+)$/;
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.webmanifest': 'application/manifest+json', '.json': 'application/json',
};
const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET, HEAD, OPTIONS',
  'access-control-allow-headers': '*',
  'access-control-expose-headers': '*',
};
// Response headers not passed through from upstream (hop-by-hop, or unsafe to forward).
const DROP = new Set(['connection', 'keep-alive', 'transfer-encoding', 'content-encoding', 'content-length', 'set-cookie',
  'content-security-policy', 'x-frame-options', 'strict-transport-security', 'alt-svc']);

export function isPrivateHost(hostname) {
  const h = hostname.replace(/^\[|\]$/g, '').toLowerCase();
  if (h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h.endsWith('.internal')) return true;
  if (isIP(h) === 4) {
    const [a, b] = h.split('.').map(Number);
    return a === 0 || a === 10 || a === 127 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127);
  }
  if (isIP(h) === 6) return h === '::1' || h === '::' || /^(fc|fd|fe80)/.test(h) || h.startsWith('::ffff:');
  return false;
}

export function rewriteHls(text, base, wrap) {
  return text.split('\n').map(line => {
    const t = line.trim();
    if (!t) return line;
    if (t.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (_, uri) => `URI="${wrap(new URL(uri, base).href)}"`);
    return wrap(new URL(t, base).href);
  }).join('\n');
}

function send(res, status, body, headers = {}) {
  res.writeHead(status, { 'content-type': 'text/plain; charset=utf-8', ...CORS, ...headers });
  res.end(body);
}

async function handleProxy(req, res, url) {
  const target = url.searchParams.get('url');
  const key = url.searchParams.get('key') || '';
  if (!target) {
    return send(res, 200, JSON.stringify({ m3uProxy: true, keyRequired: !!proxyKey, keyValid: !proxyKey || key === proxyKey }),
      { 'content-type': 'application/json', 'cache-control': 'no-store' });
  }
  if (proxyKey && key !== proxyKey) return send(res, 403, 'Wrong or missing proxy key');
  let t;
  try { t = new URL(target); } catch { return send(res, 400, 'Bad url'); }
  if (!/^https?:$/.test(t.protocol)) return send(res, 400, 'Only http and https links');
  if (!allowPrivate && isPrivateHost(t.hostname)) return send(res, 403, 'Private addresses are blocked');

  const ctrl = new AbortController();
  res.on('close', () => ctrl.abort());
  const headers = { 'user-agent': req.headers['user-agent'] || 'Mozilla/5.0' };
  if (req.headers.range) headers.range = req.headers.range;

  let upstream;
  try {
    upstream = await fetch(t, { method: req.method === 'HEAD' ? 'HEAD' : 'GET', headers, redirect: 'follow', signal: ctrl.signal });
  } catch (e) {
    if (!res.headersSent) send(res, 502, `Upstream error: ${e.cause?.code || e.message}`);
    return;
  }

  const out = { ...CORS };
  upstream.headers.forEach((v, k) => { if (!DROP.has(k)) out[k] = v; });
  const type = upstream.headers.get('content-type') || '';
  const finalUrl = upstream.url || t.href;
  const maybeHls = /mpegurl/i.test(type) || /\.m3u8$/i.test(new URL(finalUrl).pathname);

  if (maybeHls && req.method !== 'HEAD' && upstream.ok) {
    const text = await upstream.text();
    const wrap = u => `${url.pathname}?${proxyKey ? `key=${encodeURIComponent(key)}&` : ''}url=${encodeURIComponent(u)}`;
    const body = text.includes('#EXT-X-') ? rewriteHls(text, finalUrl, wrap) : text;
    res.writeHead(upstream.status, { ...out, 'content-length': Buffer.byteLength(body) });
    return res.end(body);
  }

  const len = upstream.headers.get('content-length');
  if (len && !upstream.headers.get('content-encoding')) out['content-length'] = len;
  res.writeHead(upstream.status, out);
  if (!upstream.body || req.method === 'HEAD') return res.end();
  Readable.fromWeb(upstream.body).on('error', () => res.destroy()).pipe(res);
}

async function handleStatic(req, res, url) {
  let path = decodeURIComponent(url.pathname);
  if (path === '/' || path === '') path = '/index.html';
  if (!PUBLIC.test(path)) return send(res, 404, 'Not found');
  const file = normalize(join(root, path));
  if (!file.startsWith(root.endsWith(sep) ? root : root + sep)) return send(res, 404, 'Not found');
  try {
    const body = await readFile(file);
    res.writeHead(200, {
      'content-type': TYPES[extname(file)] || 'application/octet-stream',
      'cache-control': 'no-cache',
      'x-content-type-options': 'nosniff',
    });
    res.end(req.method === 'HEAD' ? undefined : body);
  } catch {
    send(res, 404, 'Not found');
  }
}

export function createAppServer() {
  return createServer((req, res) => {
    let url;
    try { url = new URL(req.url, 'http://local'); } catch { return send(res, 400, 'Bad request'); }   // e.g. '//' would crash the server
    if (req.method === 'OPTIONS') return send(res, 204, '');
    if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, 'Method not allowed');
    const handler = url.pathname === '/proxy' ? handleProxy
      : (url.pathname === '/hls' || url.pathname.startsWith('/hls/')) ? handleHls
      : handleStatic;
    handler(req, res, url).catch(err => {
      if (!res.headersSent) send(res, 500, 'Server error');
      else res.destroy();
      console.error(err);
    });
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === normalize(process.argv[1])) {
  createAppServer().listen(port, hostAddr, () => {
    console.log(`M3U Player on http://${hostAddr === '0.0.0.0' ? 'localhost' : hostAddr}:${port}${proxyKey ? ' (proxy key required)' : ''}`);
  });
}

process.on('uncaughtException', e => console.error('[uncaught]', e));   // one bad request must never take the app down
