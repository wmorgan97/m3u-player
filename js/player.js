// Plays a stream in a <video>: native HLS where the browser has it (Safari,
// iPhone), hls.js elsewhere, and a plain src for progressive files.

const PROGRESSIVE = /\.(mp4|m4v|mov|webm|mkv|avi|mp3|aac|m4a|ogg|oga|opus|flac|wav)(\?|#|$)/i;
const HLS_MIME = 'application/vnd.apple.mpegurl';
const IS_SAFARI = typeof navigator !== 'undefined' && /^((?!chrome|chromium|crios|fxios|android).)*safari/i.test(navigator.userAgent);

function mediaErrorMessage(err) {
  switch (err?.code) {
    case 2: return 'Network error while loading the stream.';
    case 3: return "The stream couldn't be decoded.";
    case 4: return "This stream isn't supported by the browser, or the link is offline.";
    default: return 'Playback failed.';
  }
}

function hlsErrorMessage(data) {
  if (/manifestLoad|levelLoad/i.test(data.details)) {
    return data.response?.code
      ? `The stream server replied ${data.response.code}.`
      : 'The stream server is offline or blocks web browsers.';
  }
  return `Stream error (${data.details}).`;
}

const IS_MAC_DESKTOP = /Macintosh/.test(navigator.userAgent) && !(navigator.maxTouchPoints > 1);
export function externalPlayers(url) {
  const u = encodeURIComponent(url);
  return [
    // VLC for Mac answers no web link (only VLC on iPhone/iPad does): hand it a playlist file instead.
    IS_MAC_DESKTOP ? { name: 'VLC', file: true, href: '#' } : { name: 'VLC', href: `vlc-x-callback://x-callback-url/stream?url=${u}` },
    { name: 'Infuse', href: `infuse://x-callback-url/play?url=${u}` },
    { name: 'IINA', href: `iina://weblink?url=${u}` },
  ];
}

export class Player {
  constructor(video) {
    this.video = video;
    this.hls = null;
    this.token = 0;
    this.ready = false;
    this.cancel = null;
    this.tsToHls = true;
    this.wrap = url => url;   // e.g. route through the proxy
    this.preferNative = false; // the iPhone app's web view: use iOS's own HLS player first
    this.transcode = null;    // last resort: a fn(url) -> our server's /hls repackager, so Safari can play raw TS
    this.onFatal = null;
    video.addEventListener('error', () => { if (this.ready) this.onFatal?.(mediaErrorMessage(video.error)); });
  }

  // IPTV panels usually serve the same channel as .ts and .m3u8; only the latter plays in Safari.
  candidates(url) {
    const list = [url];
    if (this.tsToHls && /\.ts(\?|$)/i.test(url)) list.unshift(url.replace(/\.ts(\?|$)/i, '.m3u8$1'));
    return list.map(this.wrap);
  }

  // Ways to play a URL, best first. Safari's own HLS player is preferred where
  // it exists (AirPlay, background audio); elsewhere hls.js is more reliable.
  modes(url) {
    // Our server's /hls?…url=<file> ends with the file's own name (.mkv/.mp4): it is HLS, never a plain file.
    const isHls = /^\/hls\?/.test(url) || /\.m3u8(\?|#|$)/i.test(url);
    if (!isHls && PROGRESSIVE.test(url)) return ['direct'];
    const native = !!this.video.canPlayType(HLS_MIME);
    const hlsjs = !!window.Hls?.isSupported();
    const order = native && (IS_SAFARI || this.preferNative || !hlsjs) ? ['native', 'hlsjs'] : ['hlsjs', 'native', 'direct'];
    return order.filter(m => (m === 'hlsjs' ? hlsjs : m === 'native' ? native : !native));
  }

  // Resolves true once playing, false if superseded by another play/stop; rejects if every attempt fails.
  async play(url) {
    const token = ++this.token;
    let lastError;
    const tc = this.transcode?.(url);
    const isHls = /\.m3u8(\?|#|$)/i.test(url);
    // A raw MPEG-TS stream (the usual IPTV case) doesn't decode in Safari, and it often
    // "loads" without ever rendering a frame — so the native attempt never errors and a
    // plain fallback is never reached. When we have a server repackager, use it FIRST for
    // anything that isn't already HLS or a plain media file.
    // With a server repackager, every non-HLS source goes through it first (browsers can't play .mkv at all,
    // and it's what gives us conversion, subtitles and the VOD timeline); a downloaded blob plays directly.
    const raw = !!tc && !isHls && !/^blob:/.test(url);
    const tries = [];
    if (/^blob:/.test(url)) tries.push([url, 'direct']);   // a downloaded file: play it straight from storage
    if (raw) for (const mode of this.modes(tc)) tries.push([tc, mode]);
    for (const candidate of this.candidates(url)) {
      for (const mode of this.modes(candidate)) tries.push([candidate, mode]);
    }
    if (!raw && tc) for (const mode of this.modes(tc)) tries.push([tc, mode]);
    // A dead channel fails fast: every way of playing it shares one ~18s budget instead of 25s each.
    // A film being converted on the server needs much longer for its first picture.
    const deadline = Date.now() + (this.vod ? 120000 : 18000);
    for (const [candidate, mode] of tries) {
      const left = deadline - Date.now();
      if (left < 2500 && lastError) break;
      try {
        await this.attempt(candidate, mode, Math.max(2500, left));
        this.lastUrl = candidate;
        return token === this.token;
      } catch (e) {
        if (token !== this.token) return false;
        lastError = e;
      }
    }
    throw lastError;
  }

  // A live stream has no fixed duration; movies and catch-up do.
  get isLive() { return this.ready && !Number.isFinite(this.video.duration); }

  stop() {
    this.token++;
    this.teardown();
  }

  teardown() {
    this.ready = false;
    this.cancel?.();
    if (this.hls) { this.hls.destroy(); this.hls = null; }
    const v = this.video;
    v.pause();
    v.removeAttribute('src');
    v.load();
  }

  attempt(url, mode, timeoutMs = 25000) {
    this.teardown();
    const v = this.video;
    const Hls = window.Hls;
    const useHlsJs = mode === 'hlsjs';

    return new Promise((resolve, reject) => {
      let settled = false;
      const settle = (fn, arg) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        v.removeEventListener('loadeddata', ok);
        v.removeEventListener('error', bad);
        this.cancel = null;
        fn(arg);
      };
      const ok = () => { this.ready = true; settle(resolve); };
      const bad = () => settle(reject, new Error(mediaErrorMessage(v.error)));
      const timer = setTimeout(() => settle(reject, new Error('The stream took too long to respond.')), timeoutMs);
      this.cancel = () => settle(reject, new Error('Cancelled'));

      v.addEventListener('loadeddata', ok);
      v.addEventListener('error', bad);

      if (useHlsJs) {
        // VOD arrives as a growing playlist: start at 0 and never "catch up to live" (no latency chasing, no speed-up).
        // VOD comes as a complete playlist (server/transcode.js), so plain VOD handling; live gets the live tuning.
        const hls = this.hls = this.vod
          ? new Hls({ enableWorker: true, backBufferLength: 60, maxBufferLength: 60, maxMaxBufferLength: 180, fragLoadingMaxRetry: 8, fragLoadingTimeOut: 100000, fragLoadingRetryDelay: 2000,
              manifestLoadingTimeOut: 60000, manifestLoadingMaxRetry: 3, levelLoadingTimeOut: 60000, levelLoadingMaxRetry: 6 })
          : new Hls({ enableWorker: true, backBufferLength: 30, maxBufferLength: 60, maxMaxBufferLength: 120,
              liveSyncDurationCount: this.liveSync || 3, liveMaxLatencyDurationCount: (this.liveSync || 3) + 6, fragLoadingMaxRetry: 6, levelLoadingMaxRetry: 6 });
        hls.on(Hls.Events.ERROR, (_, data) => {
          if (!data.fatal) return;
          if (!settled) return settle(reject, new Error(hlsErrorMessage(data)));
          if (data.type === Hls.ErrorTypes.NETWORK_ERROR) hls.startLoad();
          else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) hls.recoverMediaError();
          else this.onFatal?.(hlsErrorMessage(data));
        });
        hls.loadSource(url);
        hls.attachMedia(v);
      } else {
        v.src = url;
      }
      v.play().catch(() => {});
    });
  }

  get isLive() { return !Number.isFinite(this.video.duration); }

  audioTracks() {
    if (this.hls) return this.hls.audioTracks.map((t, i) => ({ i, label: t.name || t.lang || `Track ${i + 1}`, active: i === this.hls.audioTrack }));
    return [...(this.video.audioTracks || [])].map((t, i) => ({ i, label: t.label || t.language || `Track ${i + 1}`, active: t.enabled }));
  }

  setAudioTrack(i) {
    if (this.hls) this.hls.audioTrack = i;
    else [...(this.video.audioTracks || [])].forEach((t, k) => { t.enabled = k === i; });
  }

  nativeTextTracks() {
    return [...this.video.textTracks].filter(t => t.kind === 'subtitles' || t.kind === 'captions');
  }

  subtitleTracks() {
    if (this.hls) return this.hls.subtitleTracks.map((t, i) => ({ i, label: t.name || t.lang || `Subtitles ${i + 1}`, active: i === this.hls.subtitleTrack }));
    return this.nativeTextTracks().map((t, i) => ({ i, label: t.label || t.language || `Subtitles ${i + 1}`, active: t.mode === 'showing' }));
  }

  // -1 turns subtitles off.
  setSubtitleTrack(i) {
    if (this.hls) {
      this.hls.subtitleTrack = i;
      this.hls.subtitleDisplay = i >= 0;
    } else {
      this.nativeTextTracks().forEach((t, k) => { t.mode = k === i ? 'showing' : 'disabled'; });
    }
  }

  async togglePip() {
    const v = this.video;
    if (document.pictureInPictureElement) return document.exitPictureInPicture();
    if (v.requestPictureInPicture && document.pictureInPictureEnabled) return v.requestPictureInPicture();
    if (v.webkitSetPresentationMode) {
      v.webkitSetPresentationMode(v.webkitPresentationMode === 'picture-in-picture' ? 'inline' : 'picture-in-picture');
      return;
    }
    throw new Error('Picture-in-Picture isn’t available here.');
  }
}
