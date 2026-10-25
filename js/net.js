// Network helpers. Requests go direct first, then through the user's proxy
// (if set) when the browser can't reach the server itself: http:// servers
// on an https:// page, or servers that don't send CORS headers.

import { isNative, nativeGet } from './native.js';

export const net = { proxy: '', streamProxy: 'http' };

// The native app isn't subject to browser blocking, so nothing counts as mixed content there.
export const isMixed = url => !isNative && globalThis.location?.protocol === 'https:' && /^http:/i.test(url);

export function viaProxy(url) {
  const p = net.proxy.trim();
  if (!p) return url;
  return p.includes('{url}') ? p.replace('{url}', encodeURIComponent(url)) : p + encodeURIComponent(url);
}

// streamProxy: 'off' | 'http' (only http:// streams on an https:// page) | 'all'
export function streamUrl(url) {
  if (isNative || !net.proxy.trim() || net.streamProxy === 'off') return url;
  return net.streamProxy === 'all' || isMixed(url) ? viaProxy(url) : url;
}

class HttpError extends Error {
  constructor(status) {
    super(status === 401 || status === 403 ? `Access denied by the server (${status}). Check the login details.` : `The server replied ${status}.`);
    this.status = status;
  }
}

async function attempt(url, timeout) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeout);
  try {
    const res = await fetch(url, { cache: 'no-store', signal: ctrl.signal });
    if (!res.ok) throw new HttpError(res.status);
    return res;
  } finally {
    clearTimeout(timer);
  }
}

export async function fetchResponse(url, { timeout = 30000 } = {}) {
  if (isNative) {
    let res;
    try {
      res = await nativeGet(url, timeout);
    } catch {
      throw new Error("Couldn't reach the server. Check the address and your internet connection.");
    }
    if (!res.ok) throw new HttpError(res.status);
    return res;
  }
  let error;
  if (!isMixed(url)) {
    try { return await attempt(url, timeout); } catch (e) { error = e; }
  }
  if (net.proxy.trim()) {
    try { return await attempt(viaProxy(url), timeout); } catch (e) { error = e instanceof HttpError || !error ? e : error; }
  }
  if (error instanceof HttpError) throw error;
  if (error?.name === 'AbortError') throw new Error('The server took too long to respond.');
  throw new Error(isMixed(url)
    ? 'This server uses http://, which browsers block on a secure (https://) site. Set up the free proxy in Settings to fix this.'
    : "Couldn't reach the server. It may be offline, or it doesn't allow web apps. Setting up the free proxy in Settings usually fixes this.");
}

export const fetchText = async (url, opts) => (await fetchResponse(url, opts)).text();

export async function fetchJSON(url, opts) {
  const text = await fetchText(url, opts);
  try {
    return JSON.parse(text);
  } catch {
    throw new Error('The server sent an unexpected response.');
  }
}
