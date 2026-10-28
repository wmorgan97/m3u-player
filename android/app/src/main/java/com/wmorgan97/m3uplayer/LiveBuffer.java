package com.wmorgan97.m3uplayer;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Pause and rewind for live MPEG-TS channels (Xtream {@code /live/…ts} and other endless {@code .ts} streams), with the
 * provider's one connection.
 *
 * The channel is fetched here, once, by a pump thread, and the player reads it back from this buffer instead of from the
 * network (a local DataSource that waits for new bytes at the live end). So pausing just pauses the player: the pump
 * carries on, and play continues exactly where you paused. Rewinding or going back to live opens the buffer again at a
 * keyframe (found by reading the stream's timestamps as it arrives), which is local and fast.
 *
 * Kept: the newest ~1 minute in memory while you watch live (so a quick rewind always works and the TV's flash storage
 * isn't written all evening for nothing); once you pause or rewind, the whole recording moves to the cache folder on disk,
 * up to 60 minutes or 2 GB (less when the TV is short of space). Everything is deleted when you change channel or leave.
 */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class LiveBuffer {
    private static final String TAG = "M3U";
    static final String SCHEME = "m3u-live";
    private static final int CHUNK = 2 << 20;                       // 2 MB pieces: the unit kept, written and dropped
    static final long MAX_AGE_MS = 60 * 60_000L;
    private static final long MAX_DISK = 2L << 30;                  // 2 GB
    private static final long KEEP_FREE = 1L << 30;                 // never leave the TV with under 1 GB free
    private static final Semaphore ONE = new Semaphore(1);          // one connection to the provider, ever: a new pump waits for the old to close
    private static LiveBuffer current;

    /** The buffer can't be used for this stream (it isn't MPEG-TS): play it directly instead. */
    static final class NotTs extends IOException { NotTs() { super("not an MPEG-TS stream"); } }
    /** The part asked for has been dropped (paused longer than the buffer keeps). */
    static final class Gone extends IOException { Gone() { super("dropped from the buffer"); } }

    final String id = Long.toHexString(SystemClock.uptimeMillis()) + Integer.toHexString((int) (Math.random() * 0xffff));
    private final String url;
    private final Map<String, String> headers;
    private final String agent;
    private final File dir;
    private final long ramCap;

    private final Object lock = new Object();
    private final ArrayList<Chunk> chunks = new ArrayList<>();      // contiguous: chunks.get(i) starts at base + i * CHUNK
    private long base;                                              // offset of the oldest byte still kept
    private long written;                                           // bytes received in all (the live end)
    private boolean spill;                                          // keeping the recording on disk (paused / rewound once)
    private long diskCap;
    private volatile boolean closed;
    private volatile IOException failure;
    volatile int httpCode;                                          // the provider's refusal, if that's why it failed
    private volatile boolean gotData;
    private Thread pump;
    private volatile Call call;

    private static final class Chunk { byte[] mem; File file; int len; }

    // Keyframes (where a rewind can start) and the stream time at each, in ms from the first timestamp.
    private long[] kOff = new long[1024], kTime = new long[1024];
    private int kFirst, kCount;
    private volatile long edgeTime = -1;                            // newest timestamp received

    private LiveBuffer(Context c, String url, Map<String, String> headers, String agent) {
        this.url = url; this.headers = headers; this.agent = agent;
        this.dir = new File(c.getCacheDir(), "timeshift");
        long heap = Runtime.getRuntime().maxMemory();
        this.ramCap = Math.max(16L << 20, Math.min(64L << 20, heap / 6));
    }

    /** Starts recording [url] (closing any other buffer first: one connection). */
    static synchronized LiveBuffer start(Context c, String url, Map<String, String> headers, String agent) {
        if (current != null) current.close();
        LiveBuffer b = new LiveBuffer(c.getApplicationContext(), url, headers, agent);
        current = b;
        b.pump = new Thread(b::run, "m3u-live-pump");
        b.pump.setPriority(Thread.NORM_PRIORITY + 1);
        b.pump.start();
        return b;
    }

    /** Deletes anything a previous run left behind (a crash mid-recording). */
    static void wipe(Context c) {
        File d = new File(c.getCacheDir(), "timeshift");
        File[] l = d.listFiles();
        if (l != null) for (File f : l) if (current == null || !f.getName().startsWith(current.id)) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    boolean isClosed() { return closed; }
    boolean hasData() { return gotData; }
    @Nullable IOException failure() { return failure; }

    /** Stops the pump (closing the provider's connection at once) and deletes the recording. Safe from any thread, twice. */
    void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            lock.notifyAll();
        }
        Call k = call; if (k != null) k.cancel();
        if (pump != null) pump.interrupt();
        synchronized (LiveBuffer.class) { if (current == this) current = null; }
        unbind();
        new Thread(() -> {   // file deletes off the main thread
            synchronized (lock) {
                for (Chunk ch : chunks) { if (ch.file != null) //noinspection ResultOfMethodCallIgnored
                    ch.file.delete(); ch.mem = null; }
                chunks.clear();
            }
        }, "m3u-live-clean").start();
    }

    /** From now on keep the recording on disk (you paused or went back): up to 60 min / 2 GB, less if space is short. */
    void keep() {
        synchronized (lock) {
            if (spill || closed) return;
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            long free = dir.getUsableSpace();
            diskCap = Math.min(MAX_DISK, free - KEEP_FREE);
            if (diskCap < 64L * CHUNK) { Log.w(TAG, "timeshift: only " + (free >> 20) + " MB free, keeping the buffer in memory"); return; }
            spill = true;
            lock.notifyAll();   // the pump writes the finished pieces out
        }
        Log.i(TAG, "timeshift: recording to disk (up to " + (diskCap >> 20) + " MB)");
    }
    boolean keeping() { return spill; }

    // ---------- times ----------

    long edgeTime() { return edgeTime; }

    /** Stream time at the start of what the player is playing now (set by whoever opens the buffer at an offset). */
    volatile long t0;
    static synchronized @Nullable LiveBuffer boundTo(ExoPlayer p) { return current != null && current.bound == p && !current.closed ? current : null; }

    /** The oldest moment a rewind can reach. */
    long oldestTime() {
        synchronized (lock) {
            for (int i = 0; i < kCount; i++) { int j = kFirst + i; if (kOff[j] >= base) return kTime[j]; }
            return Math.max(0, edgeTime);
        }
    }

    /** The newest keyframe at or before [t]: {offset, time}; the oldest one if [t] is before them all; null if none yet. */
    @Nullable long[] keyAt(long t) {
        synchronized (lock) {
            long[] best = null;
            for (int i = 0; i < kCount; i++) {
                int j = kFirst + i;
                if (kOff[j] < base) continue;
                if (best == null || kTime[j] <= t) best = new long[]{kOff[j], kTime[j]};
                if (kTime[j] > t) break;
            }
            return best;
        }
    }

    // ---------- the player's side ----------

    /** A MediaSource that plays the buffer from [offset] onwards (a keyframe from keyAt, or 0 for the start). */
    MediaSource source(long offset) {
        final long from = offset;
        return new ProgressiveMediaSource.Factory(() -> new Reader(this, from), PlayerHolder.extractors())
            .setContinueLoadingCheckIntervalBytes(64 * 1024)   // a smaller step: the loader notices "enough buffered" sooner at the live end
            // No retries: the pump does its own reconnecting, and a local read only fails for good (closed, dropped, not TS).
            .setLoadErrorHandlingPolicy(new androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
                @Override public long getRetryDelayMsFor(LoadErrorInfo info) { return C.TIME_UNSET; }
            })
            .createMediaSource(new MediaItem.Builder().setUri(Uri.parse(SCHEME + "://" + id + "/" + offset)).setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP2T).build());
    }

    /** Copies from [pos], waiting for the pump when it's at the live end. Throws once closed, failed, or dropped. */
    int read(long pos, byte[] b, int off, int len) throws IOException {
        Chunk ch; int at;
        synchronized (lock) {
            while (!closed && pos >= written) {
                if (failure != null) throw failure;
                try { lock.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
            }
            if (closed) throw new IOException("buffer closed");
            if (pos < base) throw new Gone();
            int i = (int) ((pos - base) / CHUNK);
            ch = chunks.get(i);
            at = (int) ((pos - base) % CHUNK);
            int n = Math.min(len, ch.len - at);
            if (ch.mem != null) { System.arraycopy(ch.mem, at, b, off, n); return n; }
            len = n;
        }
        // On disk: read it outside the lock (the pump keeps going). A piece deleted meanwhile reads as dropped.
        try (RandomAccessFile f = new RandomAccessFile(ch.file, "r")) {
            f.seek(at);
            return f.read(b, off, len);
        } catch (IOException e) { throw new Gone(); }
    }

    /** Reads the buffer from [start]. Mid-stream it first hands over the stream's latest PAT and PMT (the tables that say
     *  which packets are video and audio): without them the player would skip the keyframe it was opened at and wait
     *  for the next one. */
    static final class Reader extends BaseDataSource {
        private final LiveBuffer buf; private final long start; private final byte[] prefix;
        private long pos; private int pre; private Uri uri; private boolean open;
        Reader(LiveBuffer buf, long start) { super(false); this.buf = buf; this.start = start; this.prefix = start > 0 ? buf.tables() : new byte[0]; }
        @Override public long open(DataSpec spec) throws IOException {
            uri = spec.uri;
            transferInitializing(spec);
            if (spec.position < prefix.length) { pre = (int) spec.position; pos = start; }
            else { pre = prefix.length; pos = start + spec.position - prefix.length; }
            if (buf.closed) throw new IOException("buffer closed");
            open = true;
            transferStarted(spec);
            return C.LENGTH_UNSET;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (pre < prefix.length) { int n = Math.min(len, prefix.length - pre); System.arraycopy(prefix, pre, b, off, n); pre += n; bytesTransferred(n); return n; }
            int n = buf.read(pos, b, off, len);
            pos += n;
            bytesTransferred(n);
            return n;
        }
        @Nullable @Override public Uri getUri() { return uri; }
        @Override public void close() { uri = null; if (open) { open = false; transferEnded(); } }
    }

    // ---------- tied to a player ----------

    private ExoPlayer bound;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable watch = new Runnable() { public void run() {
        ExoPlayer p = bound; if (p == null || closed) return;
        // The player let go of this buffer (stopped, released, or playing something else): close it, and the connection.
        MediaItem m = p.getCurrentMediaItem();
        boolean ours = m != null && m.localConfiguration != null && (SCHEME + "://" + id).equals(m.localConfiguration.uri.getScheme() + "://" + m.localConfiguration.uri.getHost());
        if (p.getPlaybackState() == Player.STATE_IDLE || !ours) { Log.i(TAG, "timeshift: player let go, closing the buffer"); close(); return; }
        main.postDelayed(this, 2000);
    } };
    /** Closes this buffer when [p] stops using it (wherever the player goes: the preview, the spare, released). Main thread. */
    void bind(ExoPlayer p) { bound = p; main.removeCallbacks(watch); main.postDelayed(watch, 2000); }
    private void unbind() { main.post(() -> { main.removeCallbacks(watch); bound = null; }); }

    // ---------- the pump ----------

    private void run() {
        boolean got = false;
        try { if (!ONE.tryAcquire(3, TimeUnit.SECONDS)) Log.w(TAG, "timeshift: previous connection slow to close"); else got = true; }
        catch (InterruptedException e) { return; }
        try {
            int tries = 0;
            byte[] buf = new byte[64 * 1024];
            while (!closed) {
                long t0 = SystemClock.uptimeMillis();
                long before = written;
                try {
                    Request.Builder r = new Request.Builder().url(url).header("User-Agent", agent).header("Accept-Encoding", "identity");
                    for (Map.Entry<String, String> h : headers.entrySet()) r.header(h.getKey(), h.getValue());
                    Call k = PlayerHolder.http().newCall(r.build());
                    call = k;
                    if (closed) { k.cancel(); break; }
                    try (Response res = k.execute()) {
                        if (!res.isSuccessful()) { httpCode = res.code(); throw new IOException("HTTP " + res.code()); }
                        InputStream in = res.body().byteStream();
                        Log.i(TAG, "timeshift: connected in " + (SystemClock.uptimeMillis() - t0) + "ms");
                        for (int n; !closed && (n = in.read(buf)) != -1; ) if (n > 0) append(buf, n);
                    }
                    if (closed) break;
                    throw new IOException("stream ended");
                } catch (NotTs e) {
                    fail(e); return;
                } catch (IOException e) {
                    if (closed) break;
                    if (httpCode > 0 || (!gotData && tries >= 1)) { fail(e); return; }
                    // A drop mid-stream: reconnect and carry on recording (what's kept stays). 1 s, 2 s, 4 s.
                    if (written - before > 4 * CHUNK) tries = 0;
                    if (++tries > 3) { fail(e); return; }
                    Log.w(TAG, "timeshift: connection dropped (" + e.getClass().getSimpleName() + "), reconnecting");
                    resync = true;
                    try { Thread.sleep(1000L << (tries - 1)); } catch (InterruptedException ie) { break; }
                }
            }
        } finally {
            call = null;
            if (got) ONE.release();
        }
    }

    private void fail(IOException e) {
        synchronized (lock) { if (failure == null) failure = e; lock.notifyAll(); }
        Log.w(TAG, "timeshift: buffer stopped: " + e.getClass().getSimpleName() + (httpCode > 0 ? " " + httpCode : ""));
    }

    /** Pump thread: stores [n] bytes, indexes their timestamps, writes finished pieces out / drops old ones. */
    private void append(byte[] b, int n) throws IOException {
        parse(b, n, written);
        ArrayList<Chunk> flush = null;
        synchronized (lock) {
            if (closed) return;
            int off = 0;
            while (off < n) {
                Chunk last = chunks.isEmpty() ? null : chunks.get(chunks.size() - 1);
                if (last == null || last.len == CHUNK) { last = new Chunk(); last.mem = new byte[CHUNK]; chunks.add(last); }
                int k = Math.min(n - off, CHUNK - last.len);
                System.arraycopy(b, off, last.mem, last.len, k);
                last.len += k; off += k; written += k;
            }
            gotData = true;
            lock.notifyAll();
            if (spill) for (int i = 0; i < chunks.size() - 1; i++) { Chunk c = chunks.get(i); if (c.file == null && c.mem != null) { if (flush == null) flush = new ArrayList<>(); flush.add(c); } }
            trim();
        }
        if (flush != null) for (Chunk c : flush) {
            File f = new File(dir, id + "-" + System.identityHashCode(c) + "-" + SystemClock.uptimeMillis() + ".ts");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(c.mem, 0, c.len); }
            catch (IOException e) { Log.w(TAG, "timeshift: disk write failed, keeping in memory"); //noinspection ResultOfMethodCallIgnored
                f.delete(); synchronized (lock) { spill = false; } break; }
            synchronized (lock) { if (closed) { //noinspection ResultOfMethodCallIgnored
                f.delete(); return; } c.file = f; c.mem = null; }
        }
    }

    /** Drops the oldest pieces past the limits: memory while live; 60 min / the disk cap once keeping. Holds lock. */
    private void trim() {
        while (chunks.size() > 2) {
            long ram = 0, disk = 0;
            for (Chunk c : chunks) { if (c.mem != null) ram += CHUNK; else disk += c.len; }
            boolean over = spill ? (disk > diskCap || ram > ramCap * 2 || tooOld()) : ram > ramCap;
            if (!over) return;
            Chunk c = chunks.remove(0);
            base += c.len;
            if (c.file != null) //noinspection ResultOfMethodCallIgnored
                c.file.delete();
            c.mem = null;
        }
    }
    private boolean tooOld() {
        for (int i = 0; i < kCount; i++) { int j = kFirst + i; if (kOff[j] >= base) return edgeTime - kTime[j] > MAX_AGE_MS; }
        return false;
    }

    // ---------- MPEG-TS: just enough to find keyframes and timestamps ----------

    private final byte[] pkt = new byte[188];
    private int pktLen;
    private boolean synced, resync;
    private int pmtPid = -1, timePid = -1, videoType = -1, packets;
    private boolean timeIsVideo;
    private long lastPts = -1, curTime, lastKeyTime = Long.MIN_VALUE / 2, lastAnyEntry = Long.MIN_VALUE / 2;

    private void parse(byte[] b, int n, long offset) throws NotTs {
        int i = 0;
        if (resync) { resync = false; pktLen = 0; synced = false; }
        while (i < n) {
            if (pktLen == 0 && !synced) {
                // Find the sync byte (0x47 every 188 bytes). Some servers start a response mid-packet.
                if (b[i] != 0x47) { i++; if (!gotData && offset + i > 188 * 20) throw new NotTs(); continue; }
                synced = true;
            }
            int k = Math.min(188 - pktLen, n - i);
            System.arraycopy(b, i, pkt, pktLen, k);
            pktLen += k; i += k;
            if (pktLen < 188) return;
            pktLen = 0;
            if (pkt[0] != 0x47) { synced = false; if (!gotData && packets < 4) throw new NotTs(); continue; }
            packet(offset + i - 188);
        }
    }

    private void packet(long at) {
        packets++;
        boolean pusi = (pkt[1] & 0x40) != 0;
        int pid = ((pkt[1] & 0x1f) << 8) | (pkt[2] & 0xff);
        int afc = (pkt[3] >> 4) & 3;
        int p = 4; boolean rai = false;
        if ((afc & 2) != 0) { int alen = pkt[4] & 0xff; if (alen > 0) rai = (pkt[5] & 0x40) != 0; p = 5 + alen; }
        if ((afc & 1) == 0 || p >= 188) return;
        if (pid == 0 && pusi) { pat(p); return; }
        if (pid == pmtPid && pusi) {
            synchronized (lock) { System.arraycopy(pkt, 0, pmtPkt, 0, 188); haveTables = true; }
            if (timePid < 0) pmt(p);
            return;
        }
        if (pid != timePid || !pusi || p + 14 > 188) return;
        if (pkt[p] != 0 || pkt[p + 1] != 0 || pkt[p + 2] != 1) return;
        if ((pkt[p + 7] & 0x80) == 0) return;   // no PTS
        long pts = ((long) (pkt[p + 9] & 0x0e) << 29) | ((pkt[p + 10] & 0xff) << 22) | ((pkt[p + 11] & 0xfe) << 14) | ((pkt[p + 12] & 0xff) << 7) | ((pkt[p + 13] & 0xfe) >> 1);
        if (lastPts < 0) curTime = 0;
        else {
            long d = (pts - lastPts) & 0x1ffffffffL;
            if (d > 0x100000000L) d -= 0x200000000L;   // signed, across the 33-bit wrap
            long ms = d / 90;
            curTime += (ms > -3000 && ms < 10_000) ? ms : 40;   // a jump (new programme feed): carry on from here
        }
        lastPts = pts;
        long t = curTime;
        if (t > edgeTime) edgeTime = t;
        boolean key = !timeIsVideo || rai || nalKey(p + 9 + (pkt[p + 8] & 0xff));
        // A rewind point at most every 0.5 s; and one every 2 s regardless, for a stream whose keyframes can't be told.
        if ((key && t - lastKeyTime >= 500) || t - lastAnyEntry >= 2000 && t - lastKeyTime >= 8000) {
            if (key) lastKeyTime = t;
            lastAnyEntry = t;
            addKey(at, t);
        }
    }

    private void addKey(long off, long t) {
        synchronized (lock) {
            // Drop entries that point before what's kept, then grow or compact the arrays.
            while (kCount > 0 && kOff[kFirst] < base) { kFirst++; kCount--; }
            if (kFirst + kCount == kOff.length) {
                if (kFirst > kOff.length / 2) { System.arraycopy(kOff, kFirst, kOff, 0, kCount); System.arraycopy(kTime, kFirst, kTime, 0, kCount); kFirst = 0; }
                else { kOff = java.util.Arrays.copyOf(kOff, kOff.length * 2); kTime = java.util.Arrays.copyOf(kTime, kTime.length * 2); }
            }
            kOff[kFirst + kCount] = off; kTime[kFirst + kCount] = t; kCount++;
        }
    }

    private final byte[] patPkt = new byte[188], pmtPkt = new byte[188];
    private volatile boolean haveTables;
    /** Copies of the latest PAT and PMT packets (empty until both have been seen). */
    byte[] tables() {
        synchronized (lock) {
            if (!haveTables) return new byte[0];
            byte[] t = new byte[376]; System.arraycopy(patPkt, 0, t, 0, 188); System.arraycopy(pmtPkt, 0, t, 188, 188); return t;
        }
    }

    private void pat(int p) {
        synchronized (lock) { System.arraycopy(pkt, 0, patPkt, 0, 188); }
        p += 1 + (pkt[p] & 0xff);   // pointer field
        if (p + 12 > 188 || pkt[p] != 0) return;
        int len = ((pkt[p + 1] & 0x0f) << 8) | (pkt[p + 2] & 0xff);
        int end = Math.min(188, p + 3 + len - 4);
        for (int q = p + 8; q + 4 <= end; q += 4) {
            int prog = ((pkt[q] & 0xff) << 8) | (pkt[q + 1] & 0xff);
            if (prog != 0) { pmtPid = ((pkt[q + 2] & 0x1f) << 8) | (pkt[q + 3] & 0xff); return; }
        }
    }

    private void pmt(int p) {
        p += 1 + (pkt[p] & 0xff);
        if (p + 12 > 188 || pkt[p] != 2) return;
        int len = ((pkt[p + 1] & 0x0f) << 8) | (pkt[p + 2] & 0xff);
        int end = Math.min(188, p + 3 + len - 4);
        int pil = ((pkt[p + 10] & 0x0f) << 8) | (pkt[p + 11] & 0xff);
        int audio = -1;
        for (int q = p + 12 + pil; q + 5 <= end; ) {
            int type = pkt[q] & 0xff, pid = ((pkt[q + 1] & 0x1f) << 8) | (pkt[q + 2] & 0xff);
            int es = ((pkt[q + 3] & 0x0f) << 8) | (pkt[q + 4] & 0xff);
            if (type == 0x01 || type == 0x02 || type == 0x1b || type == 0x24 || type == 0x10) { timePid = pid; videoType = type; timeIsVideo = true; return; }
            if (audio < 0 && (type == 0x03 || type == 0x04 || type == 0x0f || type == 0x11 || type == 0x81 || type == 0x87 || type == 0x06)) audio = pid;
            q += 5 + es;
        }
        if (audio >= 0) { timePid = audio; timeIsVideo = false; }   // radio: every audio frame is a place to start
    }

    /** The first bytes of a video frame: does it start with a keyframe (H.264 IDR/SPS, HEVC IRAP/VPS, MPEG-2 sequence header)? */
    private boolean nalKey(int p) {
        for (int q = p; q + 4 < 188; q++) {
            if (pkt[q] != 0 || pkt[q + 1] != 0 || pkt[q + 2] != 1) continue;
            int h = pkt[q + 3] & 0xff;
            if (videoType == 0x1b) { int t = h & 0x1f; if (t == 5 || t == 7) return true; }
            else if (videoType == 0x24) { int t = (h >> 1) & 0x3f; if ((t >= 16 && t <= 21) || t == 32 || t == 33) return true; }
            else if (h == 0xb3) return true;
            q += 3;
        }
        return false;
    }
}
