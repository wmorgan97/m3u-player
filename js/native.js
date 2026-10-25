// Bridges to the native iPhone app (Capacitor). In the browser, isNative is
// false and none of this is used.

const cap = globalThis.Capacitor;
export const isNative = !!(cap?.isNativePlatform?.() && typeof cap.nativePromise === 'function');

const call = (plugin, method, options) => cap.nativePromise(plugin, method, options);

function base64ToBytes(b64) {
  if (Uint8Array.fromBase64) return Uint8Array.fromBase64(b64);
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

// GET through iOS networking: no CORS, and http:// is allowed. Returns a fetch Response.
export async function nativeGet(url, timeout) {
  const r = await call('CapacitorHttp', 'request', {
    url, method: 'GET', responseType: 'arraybuffer', headers: {}, connectTimeout: timeout, readTimeout: timeout,
  });
  // JSON responses arrive already parsed; everything else as base64 bytes.
  const body = typeof r.data === 'string' ? base64ToBytes(r.data) : new TextEncoder().encode(JSON.stringify(r.data ?? null));
  const headers = new Headers();
  for (const [k, v] of Object.entries(r.headers || {})) { try { headers.set(k, String(v)); } catch {} }
  return new Response(body, { status: r.status >= 200 && r.status < 600 && r.status !== 204 ? r.status : 200, headers });
}

// #EXTVLCOPT:http-user-agent=… / http-referrer=… become real request headers in the native player.
export function headersFromOpts(opts = []) {
  const names = { 'http-user-agent': 'User-Agent', 'http-referrer': 'Referer', 'http-referer': 'Referer', 'http-origin': 'Origin' };
  const headers = {};
  for (const o of opts) {
    const i = o.indexOf('=');
    const name = names[o.slice(0, i).trim().toLowerCase()];
    if (name) headers[name] = o.slice(i + 1).trim();
  }
  return headers;
}

export const nativePlay = c => call('NativePlayer', 'play', { url: c.url, title: c.name, headers: headersFromOpts(c.opts) });
// The app's own full-screen player on VLC's engine: plays .ts/.mkv/HEVC/DTS straight from the provider.
export const nativeVlc = opts => call('NativePlayer', 'vlc', opts);
export const nativeVlcClose = () => call('NativePlayer', 'close', {});
export const nativeShare = (name, text) => call('NativePlayer', 'share', { name, text });
