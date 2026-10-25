// Parses the XMLTV guide off the main thread: provider guides are 20MB+ gzipped
// (hundreds of MB of XML), which would freeze the UI for many seconds.
import { loadEpg } from './epg.js';
import { net } from './net.js';

self.onmessage = async e => {
  const { urls, from, to, wanted, proxy } = e.data;
  net.proxy = proxy || '';
  try {
    const data = await loadEpg(urls, { from, to, wanted: { ids: new Set(wanted.ids), names: new Set(wanted.names) } });
    self.postMessage({ ok: true, data });
  } catch (err) {
    self.postMessage({ ok: false, error: err?.message || String(err) });
  }
};
