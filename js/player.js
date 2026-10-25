// Plays a stream in a <video>: native HLS where the browser has it (Safari,
// iPhone), hls.js elsewhere, and a plain src for progressive files.

const PROGRESSIVE = /\.(mp4|m4v|mov|webm|mkv|mp3|aac|m4a|ogg|oga|opus|flac|wav)(\?|#|$)/i;
const HLS_MIME = 'application/vnd.apple.mpegurl';

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
      : 'The stream server is offline or blocks web browsers (CORS).';
  }
  return `Stream error (${data.details}).`;
}

export function externalPlayers(url) {
  const u = encodeURIComponent(url);
  return [
    { name: 'VLC', href: `vlc-x-callback://x-callback-url/stream?url=${u}` },
    { name: 'Infuse', href: `infuse://x-callback-url/play?url=${u}` },
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
    this.onFatal = null;
    video.addEventListener('error', () => { if (this.ready) this.onFatal?.(mediaErrorMessage(video.error)); });
  }

  // IPTV panels usually serve the same channel as .ts and .m3u8; only the latter plays in Safari.
  candidates(url) {
    const list = [url];
    if (this.tsToHls && /\.ts(\?|$)/i.test(url)) list.unshift(url.replace(/\.ts(\?|$)/i, '.m3u8$1'));
    return list;
  }

  // Resolves true once playing, false if superseded by another play/stop; rejects if every candidate fails.
  async play(url) {
    const token = ++this.token;
    let lastError;
    for (const candidate of this.candidates(url)) {
      try {
        await this.attempt(candidate);
        return token === this.token;
      } catch (e) {
        if (token !== this.token) return false;
        lastError = e;
      }
    }
    throw lastError;
  }

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

  attempt(url) {
    this.teardown();
    const v = this.video;
    const Hls = window.Hls;
    const useHlsJs = !PROGRESSIVE.test(url) && !v.canPlayType(HLS_MIME) && Hls?.isSupported();

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
      const timer = setTimeout(() => settle(reject, new Error('The stream took too long to respond.')), 25000);
      this.cancel = () => settle(reject, new Error('Cancelled'));

      v.addEventListener('loadeddata', ok);
      v.addEventListener('error', bad);

      if (useHlsJs) {
        const hls = this.hls = new Hls({ enableWorker: true, backBufferLength: 30 });
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
}
