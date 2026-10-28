package com.wmorgan97.m3uplayer;

import androidx.appcompat.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/**
 * Full-screen TV player on ExoPlayer, driven by the remote (TiviMate-style):
 *   controls hidden: OK shows them (live: the mini guide) · left/right skip 10s (held: 30s; live: rewind/forward within
 *                    the timeshift) · up/down change channel · Back closes (live, behind: back to live first)
 *   controls shown:  arrows move between buttons · OK presses · Back hides them
 *   play/pause, channel and media keys work at any time.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {
    public static PlayerActivity current;

    private ExoPlayer player;
    private Player.Listener listener;
    private PlayerView playerView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private JSObject item = new JSObject();
    private boolean live, finished, stopped, resumePending;
    // Films sent to the background: where to pick up, and whether it was paused (Home stops the stream; see onStop).
    private long resumeAtMs = -1;
    private boolean resumePaused;
    private int ticks;

    // Playback state, tracked here rather than read off ExoPlayer: ExoPlayer can't tell "connecting to a new channel"
    // from "rebuffering the one you're watching", and the UI treats them differently.
    private static final int S_IDLE = 0, S_PREPARING = 1, S_BUFFERING = 2, S_PLAYING = 3, S_FAILED = 4;
    private int state = S_IDLE;
    private boolean frameShown;        // the current attempt has put a picture on screen
    private long playingSince;         // when it last reached "playing" (a long healthy run resets the retry budget)
    private int videoW, videoH;        // held back until the first frame, so the HD badge never shows for a channel that isn't on yet
    // Every load() takes a new token; anything posted for an older one (retries, watchdog, a coalesced start) does nothing.
    private int token;
    private int attempts;              // reconnects used on this load (max 3: 1s, 2s, 4s)
    private long lastLoadReq;
    private static final int MAX_ATTEMPTS = 3;
    private static final long SPINNER_DELAY_MS = 400, STALL_MS = 12_000, ZAP_BURST_MS = 500, ZAP_SETTLE_MS = 200;
    private final Object RETRY = new Object(), STALL = new Object();   // Handler tokens, so each kind can be cancelled as a group
    // A wait past 400ms: a small ring in the banner while the banner is up (a zap), else the spinner in the middle.
    private final Runnable spinOn = () -> {
        boolean banner = this.infoBar.getVisibility() == View.VISIBLE;
        this.infoSpin.setVisibility(banner ? View.VISIBLE : View.GONE);
        this.spinner.setVisibility(banner ? View.GONE : View.VISIBLE);
    };
    private final Runnable startRun = () -> begin(token);
    private View errorPanel;
    private TextView errorText;
    private TextView retryBtn, lastBtn;

    private View overlay, nextPanel, infoBar;
    private TextView infoNum, infoName, infoNow, infoTime, infoNext, infoClock;
    private android.widget.ImageView infoLogo;
    private ProgressBar infoProgress;
    private long loadAt;   // when the current channel was asked for (for the switch-time log)
    private long loadUp, zapKeyAt;     // uptime of the load, and of the key press that asked for it (0: not a zap)
    private boolean loggedPlaying;

    // Zapping: every press shows its channel's banner at once; a quick run of presses tunes only where it stops.
    private long lastStepAt;
    private boolean zapPending;
    private int zapTarget;             // the list index the run has reached (absolute, as TvActivity numbers them)
    private final Runnable zapCommit = this::commitZap;
    private static final long ZAP_RUN_SETTLE_MS = 250;
    private View tuneScrim;            // dims the last picture while the next channel tunes (no black flash)
    private ProgressBar infoSpin;      // the banner's own small "tuning" ring
    private LinearLayout infoBadges;   // 1080p · 50 fps · HDR10 · DD 5.1, from the stream itself

    // Frame rate: MPEG-TS streams rarely state it, so it's measured from the first ~25 frames' timestamps (median gap).
    private volatile float measuredFps;
    private final long[] fpsGaps = new long[25];
    private int fpsN; private long fpsLast = -1;
    private final androidx.media3.exoplayer.video.VideoFrameMetadataListener fpsMeter = (pts, release, fmt, mf) -> {
        if (measuredFps > 0) return;
        if (fmt != null && fmt.frameRate > 0) { measuredFps = fmt.frameRate; android.util.Log.i("M3U", "frame rate stated: " + measuredFps); ui.post(this::updateBadges); return; }
        if (fpsLast >= 0 && pts > fpsLast && pts - fpsLast < 200_000 && fpsN < fpsGaps.length) fpsGaps[fpsN++] = pts - fpsLast;
        fpsLast = pts;
        if (fpsN == fpsGaps.length) { long[] c = fpsGaps.clone(); java.util.Arrays.sort(c); measuredFps = 1e6f / c[c.length / 2]; android.util.Log.i("M3U", "frame rate measured: " + measuredFps); ui.post(this::updateBadges); }
    };
    private void resetFps() { measuredFps = 0; fpsN = 0; fpsLast = -1; }

    // Pause / rewind on live TV (see LiveBuffer), and the mini guide.
    private LiveBuffer ring;           // the live MPEG-TS channel, recorded as it plays (null: played directly)
    private boolean shifted;           // behind live (paused or rewound)
    private long shiftTarget = NONE;   // a rewind/skip being chosen (stream time); applied when the key settles
    private static final long NONE = Long.MIN_VALUE;
    private ShiftBar shiftBar;
    private TextView behindPill;       // "−4:12", top right, while behind live with nothing else on screen
    private MiniGuide guide;
    private final Runnable shiftCommit = this::commitShift;
    private final Runnable shiftOff = () -> { if (player == null || !player.getPlayWhenReady()) return; shiftBar.setVisibility(View.GONE); syncBehindPill(); };
    private final Runnable shiftTick = new Runnable() { public void run() { if (shiftBar.getVisibility() != View.VISIBLE) return; updateShiftBar(); ui.postDelayed(this, 500); } };
    private final Runnable infoOff = () -> {
        infoBar.setVisibility(View.GONE);
        if (this.infoSpin.getVisibility() == View.VISIBLE) { this.infoSpin.setVisibility(View.GONE); this.spinner.setVisibility(View.VISIBLE); }   // still tuning: the wait moves to the middle
        syncBehindPill();
    };
    private android.widget.ListView channelList;   // live TV: TiviMate-style channel panel on OK
    private TextView clockLabel;
    private TextView title, subtitle, badge, liveTag, elapsed, total, hud;
    private SeekBar seek;
    private ProgressBar spinner;
    private ImageButton playBtn, prevBtn, nextBtn, backBtn, fwdBtn, audioBtn, subsBtn, aspectBtn, infoBtn, listBtn, sleepBtn;
    private TextView noticeView;          // reminders: "Starting now on …  ·  OK to watch"
    private Runnable noticeAction;
    private final Runnable noticeOff = () -> { noticeView.setVisibility(View.GONE); noticeAction = null; };
    private long sleepAt = 0;             // sleep timer: when playback stops (0 = off)
    private final Runnable sleepRun = () -> { if (player != null) player.pause(); finish(); };
    private int aspect = 0;
    private static final String[] ASPECTS = {"Best fit", "Fill screen", "Stretch"};
    private long pendingSeek = -1;

    private final Runnable hideRun = this::hideOverlay;
    private final Runnable tick = new Runnable() { public void run() { if (player == null) return; onTick(); ui.postDelayed(this, 1000); } };
    private final Runnable commitSeek = () -> { if (pendingSeek >= 0 && player != null) { player.seekTo(pendingSeek); pendingSeek = -1; } };
    private final Runnable hudOff = () -> hud.setVisibility(View.GONE);
    private String zapDigits = "";
    private final Runnable zapRun = () -> {
        if (!zapDigits.isEmpty()) { zapKeyAt = android.os.SystemClock.uptimeMillis(); send("zap", "num", Integer.parseInt(zapDigits)); }   // only digits 0-9 go in, at most 4: always parses
        zapDigits = "";
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        current = this;
        LiveBuffer.wipe(this);   // a recording left by a crash
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        buildUi();
        player = PlayerHolder.take(this);   // built ahead of time while you browse, so the player screen opens with it ready
        runningUrl = PlayerHolder.takeHandedUrl();   // or the preview's player, already playing this channel
        if (runningUrl != null) attachWhenSurfaceReady(); else playerView.setPlayer(player);
        ring = LiveBuffer.boundTo(player);   // the preview's player may be playing a channel through its buffer already
        player.setVideoFrameMetadataListener(fpsMeter);
        player.addListener(listener = new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                if (loadAt > 0) {
                    long now = android.os.SystemClock.uptimeMillis();
                    android.util.Log.i("M3U", "picture in " + (System.currentTimeMillis() - loadAt) + "ms" + (zapKeyAt > 0 ? " (key to picture " + (now - zapKeyAt) + "ms)" : "") + (ring != null ? " [buffer]" : ""));
                    loadAt = 0;
                }
                frameShown = true;
                scrim(false);
                updateBadges();
                if (state == S_FAILED) return;
                setState(player.getPlaybackState() == Player.STATE_READY ? S_PLAYING : S_BUFFERING);
            }
            @Override public void onPlaybackStateChanged(int s) {
                if (state == S_FAILED) return;
                if (s == Player.STATE_BUFFERING) {
                    setState(frameShown ? S_BUFFERING : S_PREPARING);
                    armStall();
                } else ui.removeCallbacksAndMessages(STALL);
                // Playing means a picture: READY alone can come a beat before the first frame. Radio has no frame to wait for.
                if (s == Player.STATE_READY && (frameShown || !player.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_VIDEO))) setState(S_PLAYING);
                if (s == Player.STATE_ENDED) { setState(S_IDLE); onEnded(); }
            }
            @Override public void onPlayWhenReadyChanged(boolean pwr, int reason) {
                // With the next episode queued (preloadNext) the player pauses at the end of this one instead of ending.
                if (!pwr && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) onEnded();
            }
            @Override public void onPositionDiscontinuity(Player.PositionInfo o, Player.PositionInfo n, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) checkCredits();   // seeking into the credits shows the card, out of them hides it
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                if (playing && !loggedPlaying && loadUp > 0) {
                    loggedPlaying = true;
                    long now = android.os.SystemClock.uptimeMillis();
                    android.util.Log.i("M3U", "playing in " + (now - loadUp) + "ms" + (zapKeyAt > 0 ? " (key to playing " + (now - zapKeyAt) + "ms)" : ""));
                }
                playBtn.setImageResource(playing ? androidx.media3.ui.R.drawable.exo_icon_pause : androidx.media3.ui.R.drawable.exo_icon_play);
                if (!playing && overlay.getVisibility() == View.VISIBLE) ui.removeCallbacks(hideRun);
            }
            @Override public void onVideoSizeChanged(VideoSize size) { videoW = size.width; videoH = size.height; if (state == S_PLAYING) updateBadge(videoW, videoH); updateBadges(); }
            @Override public void onTracksChanged(Tracks t) { updateBadges(); }
            @Override public void onPlayerError(PlaybackException e) { onError(e); }
        });
        load(NativePlayerPlugin.item(getIntent().getStringExtra("item")));
        ui.post(tick);
    }

    // ---------- stream ----------

    private String runningUrl;

    /** A player handed over from the preview is still drawing into the preview window. It moves to this screen's surface
     *  only once that surface exists: never a moment with no surface, which made the decoder restart and wait for the next
     *  keyframe (the 1-3 s freeze when going full screen). */
    private void attachWhenSurfaceReady() {
        View v = playerView.getVideoSurfaceView();
        if (!(v instanceof android.view.SurfaceView)) { playerView.setPlayer(player); return; }
        android.view.SurfaceHolder h = ((android.view.SurfaceView) v).getHolder();
        if (h.getSurface() != null && h.getSurface().isValid()) { playerView.setPlayer(player); return; }
        h.addCallback(new android.view.SurfaceHolder.Callback() {
            @Override public void surfaceCreated(android.view.SurfaceHolder holder) { h.removeCallback(this); if (player != null) playerView.setPlayer(player); }
            @Override public void surfaceChanged(android.view.SurfaceHolder holder, int f, int w, int hh) {}
            @Override public void surfaceDestroyed(android.view.SurfaceHolder holder) {}
        });
    }   // the stream the player is connected to right now (null when stopped)

    public void load(JSObject next) {
        if (player == null) return;   // destroyed
        // Already playing this exact channel (the preview's player handed over, or the same item again): keep the stream,
        // just show the screen. Going full screen from the preview no longer drops and reopens the connection.
        int ps = player.getPlaybackState();
        boolean same = next.optBoolean("live", true) && runningUrl != null && runningUrl.equals(next.optString("url"))
            && player.getPlayerError() == null && (ps == Player.STATE_READY || ps == Player.STATE_BUFFERING);
        // The next episode, already queued and buffering behind this one during the credits: just move on to it.
        boolean queued = !next.optBoolean("live", true) && preloadUrl != null && preloadUrl.equals(next.optString("url"))
            && player.getMediaItemCount() == 2 && player.getCurrentMediaItemIndex() == 0 && player.getPlayerError() == null;
        loadAt = System.currentTimeMillis();
        loadUp = android.os.SystemClock.uptimeMillis();
        loggedPlaying = false;
        if (zapKeyAt > 0 && loadUp - zapKeyAt > 3000) zapKeyAt = 0;   // not this load's key press
        boolean hadPicture = frameShown && player.getPlaybackState() != Player.STATE_IDLE || tuneScrim.getAlpha() > 0;
        item = next;
        live = next.optBoolean("live", true);
        zapPending = false; ui.removeCallbacks(zapCommit);
        shifted = false; shiftTarget = NONE; ui.removeCallbacks(shiftCommit); ui.removeCallbacks(shiftOff);
        if (guide.isOpen()) guide.close();
        shiftBar.setVisibility(View.GONE); behindPill.setVisibility(View.GONE);
        finished = false;
        attempts = 0;
        playingSince = 0;
        resumePending = false;
        resumeAtMs = -1;   // a new item starts from its own position, not the one we left
        resumePaused = false;
        token++;
        // Whatever was pending belongs to the channel we're leaving.
        ui.removeCallbacks(startRun);
        ui.removeCallbacksAndMessages(RETRY);
        ui.removeCallbacksAndMessages(STALL);
        hideNext(false);
        creditsDismissed = false;
        if (channelList != null) channelList.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        frameShown = false;
        videoW = videoH = 0;
        if (!same) resetFps();
        setState(S_PREPARING);
        title.setText(next.optString("title", ""));
        String sub = next.optString("subtitle", "");
        subtitle.setText(sub);
        subtitle.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        badge.setVisibility(View.GONE);
        liveTag.setVisibility(live ? View.VISIBLE : View.GONE);
        for (View v : new View[]{seek, elapsed, total, backBtn, fwdBtn}) v.setVisibility(live ? View.GONE : View.VISIBLE);
        prevBtn.setAlpha(next.optBoolean("hasPrev") ? 1f : .35f);
        nextBtn.setAlpha(next.optBoolean("hasNext") ? 1f : .35f);
        listBtn.setVisibility(list().length() > 1 ? View.VISIBLE : View.GONE);

        // Holding up/down fires a load every ~150ms and the provider allows one connection: the first press starts at once,
        // but loads that follow quickly drop the stream and wait for the key to settle, so only the last channel connects.
        long now = android.os.SystemClock.uptimeMillis();
        boolean burst = now - lastLoadReq < ZAP_BURST_MS;
        lastLoadReq = now;
        if (!same && !queued && hadPicture) scrim(true);   // the last picture stays, dimmed, until the new one comes
        if (same) {
            frameShown = player.getVideoSize().width > 0;
            videoW = player.getVideoSize().width; videoH = player.getVideoSize().height;
            setState(ps == Player.STATE_READY && frameShown ? S_PLAYING : S_BUFFERING);
            if (!player.getPlayWhenReady()) player.play();
            loadAt = 0;
        }
        else if (queued) playQueued(token);
        else if (burst) { player.stop(); closeRing(); runningUrl = null; ui.postDelayed(startRun, ZAP_SETTLE_MS); } else begin(token);
        if (live) { hideOverlay(); showBanner(); } else { showOverlay(); }   // live: the channel banner, as on a TV; films: the controls
        if (zapKeyAt > 0) android.util.Log.i("M3U", "zap: key to banner " + (android.os.SystemClock.uptimeMillis() - zapKeyAt) + "ms");
        justShown = live;
    }

    /** Opens the stream for load number t, unless a newer load has replaced it. */
    private void begin(int t) {
        if (t != token || player == null) return;
        if (stopped) { resumePending = true; return; }   // in the background: connect when the screen comes back
        frameShown = false;
        if (live) { shifted = false; shiftTarget = NONE; }   // a fresh connection is at live
        applyTrackPrefs();
        JSObject next = item;
        String url = next.optString("url");
        preloadUrl = null;
        player.setPauseAtEndOfMediaItems(false);
        closeRing();
        if (live && bufferable(url)) {
            // Live MPEG-TS: through the buffer (one connection; pause and rewind work). Anything else plays directly.
            Map<String, String> h = headers(next.optJSONObject("headers"));
            String agent = h.remove("User-Agent");
            ring = LiveBuffer.start(this, url, h, agent != null ? agent : AGENT);
            ring.t0 = 0;
            player.setMediaSource(ring.source(0));
            ring.bind(player);
        } else player.setMediaSource(source(url, next.optJSONObject("headers")));
        runningUrl = url;
        double start = next.optDouble("start", 0);
        long at = resumeAtMs >= 0 ? resumeAtMs : start > 1 ? (long) (start * 1000) : 0;
        if (!live && at > 0) player.seekTo(at);
        boolean paused = !live && resumePaused;
        resumeAtMs = -1; resumePaused = false;
        player.prepare();
        if (paused) player.pause(); else player.play();
    }

    /** The player's panels (banner, pause bar, mini guide): near-opaque, so text stays legible over any picture. */
    static final int PANEL = 0xF2181B22;
    private static final String AGENT = "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36";

    /** The item's headers; "User-Agent" (if it sets one) under that exact key. */
    private static Map<String, String> headers(JSONObject h) {
        Map<String, String> out = new HashMap<>();
        if (h != null) for (Iterator<String> it = h.keys(); it.hasNext(); ) {
            String k = it.next(), v = h.optString(k);
            out.put(k.equalsIgnoreCase("User-Agent") ? "User-Agent" : k, v);
        }
        return out;
    }

    /** Live streams that go through LiveBuffer: endless MPEG-TS (Xtream /live/…ts and the like). Not HLS / DASH / files,
     *  and not a channel whose buffer already found it isn't TS ("direct"). */
    private boolean bufferable(String url) {
        if (item.optBoolean("direct")) return false;
        String u = url.toLowerCase(Locale.ROOT);
        if (!u.startsWith("http")) return false;
        String path = u.replaceFirst("[?#].*$", "");
        return !(path.contains(".m3u8") || path.contains(".mpd") || path.contains(".ism") || path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".m3u"));
    }

    /** The stream for [url] with the item's headers (its User-Agent, if it sets one, replaces ours). One shared HTTP
     *  client (PlayerHolder.http): a zap to another channel on the same server reuses its connection set-up. */
    private androidx.media3.exoplayer.source.MediaSource source(String url, JSONObject h) {
        Map<String, String> headers = headers(h);
        String agent = headers.remove("User-Agent");
        androidx.media3.datasource.okhttp.OkHttpDataSource.Factory http = new androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(PlayerHolder.http())
            .setUserAgent(agent != null ? agent : AGENT).setDefaultRequestProperties(headers);
        MediaItem.Builder media = new MediaItem.Builder().setUri(url);
        // Live: play at normal speed always. ExoPlayer otherwise speeds up (up to 3%) to creep back to its live target
        // after you've paused, which you'd hear as a slightly fast programme.
        if (live) media.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build());
        if (url.toLowerCase(Locale.ROOT).contains(".m3u8")) {
            media.setMimeType(MimeTypes.APPLICATION_M3U8);
            return new androidx.media3.exoplayer.hls.HlsMediaSource.Factory(http)
                .setExtractorFactory(new androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES, true))
                .createMediaSource(media.build());
        }
        return new DefaultMediaSourceFactory(http, PlayerHolder.extractors()).createMediaSource(media.build());
    }

    /** UI follows the state: the spinner only once a wait passes 400ms (fast zaps never flash it); the badge only once there's a picture. */
    private void setState(int s) {
        state = s;
        ui.removeCallbacks(spinOn);
        if (s == S_PREPARING || s == S_BUFFERING) { if (spinner.getVisibility() != View.VISIBLE && infoSpin.getVisibility() != View.VISIBLE) ui.postDelayed(spinOn, SPINNER_DELAY_MS); }
        else { spinner.setVisibility(View.GONE); infoSpin.setVisibility(View.GONE); }
        if (s == S_PLAYING) {
            if (playingSince == 0) playingSince = android.os.SystemClock.uptimeMillis();
            updateBadge(videoW, videoH);
        } else if (s != S_BUFFERING) playingSince = 0;
    }

    /** Live: a stream that sits buffering for 12s has stalled without an error; reconnect as if it had failed. */
    private void armStall() {
        ui.removeCallbacksAndMessages(STALL);
        if (!live) return;
        final int t = token;
        ui.postAtTime(() -> {
            if (t != token || player == null || stopped || !player.getPlayWhenReady() || player.getPlaybackState() != Player.STATE_BUFFERING) return;
            android.util.Log.w("M3U", "live stream stalled for " + STALL_MS + "ms");
            player.stop(); runningUrl = null;   // drop the dead connection now, so the retry isn't refused for using a second one
            recover(t, true, "The stream stopped sending video.", null);
        }, STALL, android.os.SystemClock.uptimeMillis() + STALL_MS);
    }

    /** Remembered audio/subtitle languages, applied to every new stream. Subtitles stay off unless you turned them on. */
    private void applyTrackPrefs() {
        android.content.SharedPreferences p = getSharedPreferences("player", MODE_PRIVATE);
        boolean textOn = p.getBoolean("textOn", false);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT)   // picks from the last stream don't apply here
            .setPreferredAudioLanguage(p.getString("audioLang", null))
            .setPreferredTextLanguage(p.getString("textLang", null))
            .setSelectUndeterminedTextLanguage(textOn)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !textOn)
            .build());
    }

    private void savePref(String lang, String key, Boolean textOn) {
        android.content.SharedPreferences.Editor ed = getSharedPreferences("player", MODE_PRIVATE).edit();
        if (key != null && lang != null && !lang.isEmpty() && !lang.equals("und")) ed.putString(key, lang);
        if (textOn != null) ed.putBoolean("textOn", textOn);
        ed.apply();
    }

    /** The channel banner (TiviMate-style): number, logo, name and the stream's quality badges; what's on with times and
     *  progress; what's next; the clock. */
    private void showBanner() {
        if (guideStale()) send("guide");   // the programme has changed since this channel started: fresh now/next first
        bindBanner(item.optString("num", ""), item.optString("title", ""), item.optString("art", ""), item.optString("nowTitle", ""),
            item.optLong("nowStart", 0), item.optLong("nowEnd", 0), item.optString("nextTitle", ""), item.optString("subtitle", ""));
        updateBadges();
    }

    /** A quick run of zaps: the banner of the channel the run has reached, from the list row, before anything tunes. */
    private void showRowBanner(JSONObject r) {
        bindBanner(r.optString("n", ""), r.optString("t", ""), r.optString("a", ""), r.optString("nt", ""),
            r.optLong("ps", 0), r.optLong("pe", 0), r.optString("nx", ""), r.optString("s", ""));
        infoBadges.removeAllViews();   // nothing is known about a stream that isn't open yet
    }

    private boolean guideStale() {
        long e = item.optLong("nowEnd", 0);
        return live && e > 0 && System.currentTimeMillis() >= e;
    }

    private void bindBanner(String num, String name, String art, String nowTitle, long s, long e, String next, String fallback) {
        long now = System.currentTimeMillis();
        infoNum.setText(num);
        infoNum.setVisibility(num.isEmpty() ? View.GONE : View.VISIBLE);
        infoName.setText(name);
        Logos.show(infoLogo, art, dp(120), dp(68));
        infoNow.setText(nowTitle.isEmpty() ? fallback : nowTitle);
        boolean timed = !nowTitle.isEmpty() && s > 0 && e > s;
        infoTime.setText(timed ? clockHm(s) + " – " + clockHm(e) : "");
        infoTime.setVisibility(timed ? View.VISIBLE : View.GONE);
        if (timed) { infoProgress.setVisibility(View.VISIBLE); infoProgress.setProgress((int) (1000 * Math.max(0, Math.min(1, (now - s) / (double) (e - s))))); }
        else infoProgress.setVisibility(View.GONE);
        // The next programme starts when this one ends, so its time is nowEnd.
        infoNext.setText(next.isEmpty() ? "" : "Next" + (e > 0 ? "  " + clockHm(e) : "") + "   " + next);
        infoNext.setVisibility(next.isEmpty() ? View.GONE : View.VISIBLE);
        revealBanner();
    }

    /** The badges for what's playing, as soon as each is known: resolution once there's a picture, then HDR, frame rate,
     *  audio. Outlined, quiet, at the right of the name line. */
    private void updateBadges() {
        if (player == null || infoBadges == null) return;
        boolean known = frameShown || (state == S_PLAYING && videoW == 0);
        java.util.List<String> l = known ? StreamBadges.of(player.getVideoFormat(), videoW, videoH, measuredFps, player.getAudioFormat()) : java.util.Collections.emptyList();
        StringBuilder key = new StringBuilder(); for (String b : l) key.append(b).append('|');
        if (key.toString().equals(infoBadges.getTag())) return;
        infoBadges.setTag(key.toString());
        infoBadges.removeAllViews();
        for (String b : l) {
            TextView t = text(12, 0xFFD5DAE3, true);
            t.setText(b);
            t.setIncludeFontPadding(false);
            GradientDrawable o = new GradientDrawable(); o.setStroke(dp(1), 0x80FFFFFF); o.setCornerRadius(dp(4));
            t.setBackground(o);
            t.setPadding(dp(7), dp(3), dp(7), dp(3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(dp(8), 0, 0, 0);
            infoBadges.addView(t, lp);
        }
    }
    private static String clockHm(long ms) { return new java.text.SimpleDateFormat("HH:mm", Locale.UK).format(new java.util.Date(ms)); }

    private JSArray list() { return NativePlayerPlugin.array(item.opt("list")); }

    private void onError(PlaybackException e) {
        android.util.Log.w("M3U", "playback error " + e.getErrorCodeName());   // the code name only: messages can carry the stream URL
        if (ring != null) {
            Throwable c = e.getCause();
            while (c != null && !(c instanceof LiveBuffer.Gone) && !(c instanceof LiveBuffer.NotTs)) c = c.getCause();
            if (c instanceof LiveBuffer.Gone) {
                // Paused longer than the buffer keeps: carry on from the oldest part still there.
                long[] k = ring.keyAt(ring.oldestTime());
                if (k != null) { ring.t0 = k[1]; boolean pwr = player.getPlayWhenReady(); player.setMediaSource(ring.source(k[0])); player.prepare(); player.setPlayWhenReady(pwr); showHud("Rewound to the oldest part kept"); return; }
            }
            if (c instanceof LiveBuffer.NotTs || ring.failure() instanceof LiveBuffer.NotTs) {
                // Not MPEG-TS after all (an HLS playlist behind a .ts-less link, say): play this channel directly.
                android.util.Log.i("M3U", "timeshift: not TS, playing directly");
                item.put("direct", true);
                closeRing();
                begin(token);
                return;
            }
            if (ring.httpCode > 0) { int code = ring.httpCode; closeRing(); recover(token, true, "The provider refused the stream. It may be in use on another device.", "HTTP " + code); return; }
            closeRing();
        }
        // A live stream that fell behind, or a network drop: reconnect quietly, a few times.
        boolean recoverable = e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW || (e.errorCode >= 2000 && e.errorCode < 3000);
        recover(token, recoverable, why(e), e.getErrorCodeName());
    }

    /** Up to 3 reconnects (1s, 2s, 4s) per load, then the error panel. A long good run in between earns the budget back. */
    private void recover(int t, boolean recoverable, String message, String code) {
        if (t != token || player == null) return;
        ui.removeCallbacksAndMessages(STALL);
        if (playingSince > 0 && android.os.SystemClock.uptimeMillis() - playingSince > 30_000) attempts = 0;
        if (recoverable && attempts < MAX_ATTEMPTS) {
            long delay = 1000L << attempts;
            attempts++;
            setState(S_PREPARING);
            ui.postAtTime(() -> retry(t), RETRY, android.os.SystemClock.uptimeMillis() + delay);
            return;
        }
        fail(message, code);
    }

    private void retry(int t) {
        if (t != token || player == null) return;
        if (stopped) { resumePending = true; return; }
        frameShown = false;
        if (live) begin(t);   // a fresh connection at the live edge
        else { player.prepare(); player.play(); }   // films: carry on from where it stopped
    }

    private void fail(String message, String code) {
        // The 4K copy won't play: carry on with the normal copy of the same channel instead of showing an error.
        String fb = item.optString("fallback", "");
        if (live && !fb.isEmpty()) {
            JSObject next;
            try { next = new JSObject(item.toString()); } catch (Exception e) { next = null; }
            if (next != null) {
                next.put("url", fb); next.remove("fallback"); next.remove("uhd");
                Object fh = item.opt("fallbackHeaders"); if (fh != null) next.put("headers", fh);
                showHud("4K unavailable · playing HD");
                lastLoadReq = 0;
                load(next);
                return;
            }
        }
        setState(S_FAILED);
        ui.removeCallbacksAndMessages(RETRY);
        player.stop(); runningUrl = null;   // let go of the provider's one connection
        hideOverlay();
        if (channelList != null) channelList.setVisibility(View.GONE);
        errorText.setText(message);
        String last = item.optString("last", "");
        lastBtn.setVisibility(live && !last.isEmpty() ? View.VISIBLE : View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
        retryBtn.requestFocus();
        send("error", "message", message + (code != null ? " (" + code + ")" : ""));
    }

    private void tryAgain() {
        errorPanel.setVisibility(View.GONE);
        lastLoadReq = 0;   // a deliberate retry, not a zap: start straight away
        load(item);
    }

    /** Plain words for the panel. Never the exception's own message, which can include the stream URL and its login. */
    private static String why(PlaybackException e) {
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT: return "Couldn't reach the server. Check the connection and try again.";
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS: return "The provider refused the stream. It may be in use on another device.";
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW: return "The stream fell behind and couldn't catch up.";
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED: return "The stream sent data the player couldn't read.";
        }
        if (e.errorCode >= 4000 && e.errorCode < 5000) return "This device can't play this stream's format.";
        return "This stream couldn't be played.";
    }

    private void onEnded() {
        double d = player.getDuration() > 0 ? player.getDuration() / 1000.0 : 0;
        event("progress", d, d);
        send("ended");
        if (live || !hasNextEpisode()) { showOverlay(true); return; }
        if (nextMode == N_CREDITS) { nextMode = N_END; return; }   // the card is up already: its countdown carries on
        if (nextMode != N_NONE) return;
        // Watched the credits through (or a clip too short to have any): straight on, with a short card.
        showNext(autoRun >= STILL_AFTER ? N_STILL : N_END, creditsDismissed ? 5_000 : 10_000);
    }

    private void onTick() {
        if (player == null) return;
        String clk = new java.text.SimpleDateFormat("HH:mm", Locale.UK).format(new java.util.Date());
        if (sleepAt > 0) {
            long left = Math.max(0, sleepAt - System.currentTimeMillis());
            clk += "   ·   Sleep in " + Math.max(1, (left + 59_999) / 60_000) + " min";
            if (left <= 60_000 && left > 59_000) showHud("Sleep in 1 minute");
        }
        clockLabel.setText(clk);
        if (live) syncBehindPill();
        long t = Math.max(0, player.getCurrentPosition()), d = Math.max(0, player.getDuration());
        if (!live && d > 0) {
            if (pendingSeek < 0 && !seek.isPressed()) { seek.setMax((int) (d / 1000)); seek.setProgress((int) (t / 1000)); elapsed.setText(clock(t / 1000)); }
            total.setText(clock(d / 1000));
        }
        if (++ticks % 5 == 0 && !live && d > 0 && player.isPlaying()) event("progress", t / 1000.0, d / 1000.0);
        checkCredits();
    }

    private void event(String type, double t, double d) {
        send(type, "t", Double.isNaN(t) ? 0 : t, "d", Double.isNaN(d) ? 0 : d);   // JSON refuses NaN
    }

    /** Every event to TvActivity goes through here, so a failure is logged (type only) rather than lost. */
    private static void send(String type, Object... kv) {
        try {
            JSONObject o = new JSONObject().put("type", type);
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
            NativePlayerPlugin.emit(o);
        } catch (Exception e) {
            android.util.Log.w("M3U", "player event '" + type + "' not sent: " + e.getClass().getSimpleName());
        }
    }

    private void step(int dir) {
        if ((dir > 0 && !item.optBoolean("hasNext")) || (dir < 0 && !item.optBoolean("hasPrev"))) return;
        long now = android.os.SystemClock.uptimeMillis();
        boolean run = now - lastStepAt < ZAP_BURST_MS;
        lastStepAt = now;
        zapKeyAt = now;
        if (!live || (!run && !zapPending)) { send("step", "dir", dir); return; }   // one press: tune straight away
        // A quick run of presses: each one shows its channel's banner at once (from the list), the stream that the first
        // press opened is dropped (one connection), and only the channel you stop on tunes, 250ms after the last press.
        int total = item.optInt("total", list().length());
        int from = zapPending ? zapTarget : item.optInt("current");
        zapTarget = total > 0 ? Math.floorMod(from + dir, total) : from + dir;
        if (!zapPending) {
            zapPending = true;
            token++;   // whatever the first press started (a coalesced start, retries) is void now
            ui.removeCallbacks(startRun); ui.removeCallbacksAndMessages(RETRY); ui.removeCallbacksAndMessages(STALL);
            if (frameShown) scrim(true);
            player.stop(); closeRing(); runningUrl = null;
            errorPanel.setVisibility(View.GONE);
            setState(S_PREPARING);
        }
        JSONObject r = list().optJSONObject(zapTarget - item.optInt("listStart"));
        if (r != null) showRowBanner(r);
        revealBanner();
        ui.removeCallbacks(zapCommit); ui.postDelayed(zapCommit, ZAP_RUN_SETTLE_MS);
    }

    /** Puts the banner (as bound) on screen for 4.5 s; it takes the bottom from the pause bar, and a wait already
     *  showing moves into it (the small ring by the name) instead of the middle of the picture. */
    private void revealBanner() {
        infoClock.setText(clockHm(System.currentTimeMillis()));
        shiftBar.setVisibility(View.GONE); behindPill.setVisibility(View.GONE);
        infoBar.setVisibility(View.VISIBLE);
        if (spinner.getVisibility() == View.VISIBLE) spinOn.run();   // (a wait still to come picks the banner by itself)
        ui.removeCallbacks(infoOff); ui.postDelayed(infoOff, 4500);
    }

    /** The run of zaps has settled: tune the channel it stopped on (straight away: the settle was the wait). */
    private void commitZap() {
        if (!zapPending || player == null) return;
        zapPending = false;
        lastLoadReq = 0;
        zapKeyAt = android.os.SystemClock.uptimeMillis();
        if (zapTarget == item.optInt("current")) { load(item); return; }   // back where it started: reopen it
        send("jump", "index", zapTarget);
    }

    private void updateBadge(int w, int h) {
        if (w <= 0 || h <= 0) return;
        int big = Math.max(w, h), small = Math.min(w, h);
        badge.setText(StreamBadges.resolution(w, h));   // the banner's words: 4K / 1080p / 720p / SD
        badge.setVisibility(View.VISIBLE);
    }

    // ---------- remote ----------

    /** OK on any remote: the D-pad centre, a keyboard Enter, or a numpad Enter. Every OK check goes through here. */
    private static boolean isOk(int k) { return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER; }

    private boolean okHeld = false;
    private boolean guideOk = false;   // an OK went to the mini guide; its key-up is the guide's too
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (noticeAction != null && isOk(k)) {
            if (e.getAction() == KeyEvent.ACTION_UP) { Runnable r = noticeAction; noticeOff.run(); ui.removeCallbacks(noticeOff); r.run(); }
            return true;
        }
        if (noticeAction != null && k == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_DOWN) { noticeOff.run(); ui.removeCallbacks(noticeOff); return true; }
        if (e.getAction() == KeyEvent.ACTION_DOWN) autoRun = 0;   // someone's there: "Still watching?" counts from here
        // The mini guide takes every key while it's open (channel up/down close it and zap).
        if (guide.isOpen() || (guideOk && isOk(k))) {
            boolean through = k == KeyEvent.KEYCODE_CHANNEL_UP || k == KeyEvent.KEYCODE_CHANNEL_DOWN || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE;
            if (through) guide.close();
            else {
                if (e.getAction() == KeyEvent.ACTION_DOWN) { if (isOk(k)) guideOk = true; if (guide.isOpen()) guide.onKey(e); }
                else if (isOk(k)) guideOk = false;   // the key-up of an OK the guide used: not a new OK for the player
                return true;
            }
        }
        if (e.getAction() == KeyEvent.ACTION_UP && live && isOk(k)
            && errorPanel.getVisibility() != View.VISIBLE && overlay.getVisibility() != View.VISIBLE && channelList.getVisibility() != View.VISIBLE && nextMode == N_NONE) {
            if (okHeld) { okHeld = false; return true; }
            if (shiftBar.getVisibility() == View.VISIBLE) { togglePlay(); return true; }   // paused / rewinding: OK plays and pauses
            if (list().length() > 1) openGuide(); else showOverlay();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP && okHeld && isOk(k)) { okHeld = false; return true; }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        boolean shown = overlay.getVisibility() == View.VISIBLE;
        if (shown) bumpHide();
        if (live && k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
            zapDigits = (zapDigits + (k - KeyEvent.KEYCODE_0)); if (zapDigits.length() > 4) zapDigits = zapDigits.substring(zapDigits.length() - 4);
            showHud(zapDigits);
            ui.removeCallbacks(zapRun); ui.postDelayed(zapRun, 1300);
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                togglePlay(); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_MEDIA_NEXT: step(1); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_MEDIA_PREVIOUS: step(-1); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: if (live) shiftBy(30_000, e.getRepeatCount() == 0); else seekBy(30_000); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: if (live) shiftBy(-30_000, e.getRepeatCount() == 0); else seekBy(-30_000); return true;
        }
        // Error panel: left/right/OK work its buttons, Back closes the player, up/down still change channel.
        if (errorPanel.getVisibility() == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK) { finish(); return true; }
            if (live && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
                if (e.getRepeatCount() == 0 || e.getRepeatCount() % 3 == 0) step(k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        // Next-episode card: left/right between its two buttons, OK presses, Back = Watch credits / Cancel. Nothing else leaks through.
        if (nextMode != N_NONE) {
            if (k == KeyEvent.KEYCODE_BACK) { nextBack(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) { nextPlay.requestFocus(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { nextOther.requestFocus(); return true; }
            if (isOk(k) && e.getRepeatCount() == 0) { (nextOther.isFocused() ? nextOther : nextPlay).performClick(); return true; }
            return true;
        }
        if (channelList.getVisibility() == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_RIGHT) { closeChannels(); return true; }
            return super.dispatchKeyEvent(e);   // up/down move through the list, OK switches (see openChannels)
        }
        // Live TV, like a TV: up/down zap whatever is on screen; OK = the mini guide; hold OK = the controls; left = rewind,
        // right = forward when behind live (else the banner); play/pause pauses live; Back returns to live first.
        if (live && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) && !(shown && !justShown && overlay.findFocus() != null && overlay.findFocus() != playBtn)) {
            if (e.getRepeatCount() == 0 || e.getRepeatCount() % 3 == 0) step(k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);   // held: one channel per ~3 repeats
            return true;
        }
        if (live && isOk(k) && e.getRepeatCount() == 1) { showOverlay(); okHeld = true; return true; }
        if (live && isOk(k) && e.getRepeatCount() > 1) return true;
        if (!shown) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (live) return true;   // acted on at key-up (the mini guide), unless it turns into a hold
                    showOverlay(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // Live: left rewinds (10 s, 30 s once held), right skips forward while behind; at live, right = the banner.
                    if (live) {
                        boolean back = k == KeyEvent.KEYCODE_DPAD_LEFT;
                        if (!back && !shifted && shiftTarget == NONE) { if (e.getRepeatCount() == 0) showBanner(); return true; }
                        shiftBy((back ? -1 : 1) * (e.getRepeatCount() > 3 ? 30_000 : 10_000), e.getRepeatCount() == 0);
                        return true;
                    }
                    seekBy((k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) * (e.getRepeatCount() > 3 ? 30_000 : 10_000)); return true;
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                    showOverlay(); return true;
                case KeyEvent.KEYCODE_BACK:
                    // Behind live: Back goes back to live (one press), before it closes anything.
                    if (live && (shifted || shiftTarget != NONE)) { goLive(); return true; }
                    if (item.optBoolean("fromLive")) { send("goLive"); return true; }   // started over: Back = the channel, live
                    if (infoBar.getVisibility() == View.VISIBLE) { infoOff.run(); ui.removeCallbacks(infoOff); return true; }
                    if (shiftBar.getVisibility() == View.VISIBLE) { shiftBar.setVisibility(View.GONE); return true; }
                    finish(); return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (k == KeyEvent.KEYCODE_BACK) { hideOverlay(); return true; }
        boolean onButton = getCurrentFocus() != null && getCurrentFocus() != playerView && overlay.findFocus() != null && !justShown;
        if (live && !onButton && isOk(k) && list().length() > 1) { openChannels(); return true; }
        if (justShown && !isOk(k)) justShown = false;   // the first arrow moves onto the buttons
        if (seek.hasFocus() && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            seekBy((k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) * (e.getRepeatCount() > 3 ? 30_000 : 10_000));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Skips build up while the key is held and are applied once it settles, so holding right glides instead of stuttering. */
    private void seekBy(long ms) {
        if (live || player == null) return;
        long d = Math.max(0, player.getDuration());
        long base = pendingSeek >= 0 ? pendingSeek : player.getCurrentPosition();
        pendingSeek = Math.max(0, d > 0 ? Math.min(d - 1000, base + ms) : base + ms);
        long delta = pendingSeek - player.getCurrentPosition();
        seek.setProgress((int) (pendingSeek / 1000));
        elapsed.setText(clock(pendingSeek / 1000));
        showHud(clock(pendingSeek / 1000) + "   " + (delta < 0 ? "−" : "+") + clock(Math.abs(delta) / 1000));
        ui.removeCallbacks(commitSeek);
        ui.postDelayed(commitSeek, 600);
    }

    private void togglePlay() {
        if (state == S_FAILED) { tryAgain(); return; }
        if (live) { if (player.getPlayWhenReady()) shiftPause(); else shiftResume(); return; }
        if (player.isPlaying()) { player.pause(); showOverlay(true); } else { player.play(); showOverlay(); }
    }

    private void showHud(String text) {
        hud.setText(text);
        hud.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hudOff);
        ui.postDelayed(hudOff, 1200);
    }

    private void showOverlay() { showOverlay(false); }
    private boolean justShown = false;   // the info bar came up by itself (new channel): OK still means "channel list"
    private void showOverlay(boolean keep) {
        boolean was = overlay.getVisibility() == View.VISIBLE;
        if (nextMode == N_CREDITS) { creditsDismissed = true; hideNext(false); }   // the controls came up over the credits: you're watching them
        overlay.setVisibility(View.VISIBLE);
        infoBar.setVisibility(View.GONE); ui.removeCallbacks(infoOff);   // the controls take the bottom of the screen
        if (playerView.getSubtitleView() != null) playerView.getSubtitleView().setBottomPaddingFraction(0.22f);   // subtitles sit above the controls
        if (!was) playBtn.requestFocus();
        ui.removeCallbacks(hideRun);
        if (!keep) ui.postDelayed(hideRun, 5000);
    }
    private void bumpHide() {
        ui.removeCallbacks(hideRun);
        if (player != null && player.isPlaying()) ui.postDelayed(hideRun, 5000);
    }
    private void hideOverlayOnly() { overlay.setVisibility(View.GONE); ui.removeCallbacks(hideRun); }
    private void hideOverlay() {
        overlay.setVisibility(View.GONE); ui.removeCallbacks(hideRun);
        if (behindPill != null) syncBehindPill();
        if (playerView.getSubtitleView() != null) playerView.getSubtitleView().setBottomPaddingFraction(0.08f);
        ui.post(this::checkCredits);   // a card held back while the controls were up
    }

    // ---------- pause / rewind on live TV ----------
    //
    // Two ways, picked per channel, behind the same keys and bar:
    //  - MPEG-TS channels play through LiveBuffer (recorded as they play): pause carries on recording; left/right/RW/FF
    //    reopen the recording at a keyframe; Back = live. As far back as it has kept (60 min / 2 GB once you've paused).
    //  - HLS channels play directly: pause and rewind work inside the window the provider's playlist keeps (often a
    //    minute or two; some keep hours). Too short a window (under 30 s): pause only.
    // And "Start over" (mini guide, or rewinding past the start of what's kept) plays the programme from the channel's
    // archive, where the provider has one.

    private boolean hlsWindow() {
        return ring == null && live && player.isCurrentMediaItemLive() && player.isCurrentMediaItemDynamic() && player.getDuration() > 30_000;
    }
    private boolean canRewind() { return (ring != null && ring.hasData()) || hlsWindow(); }

    /** Where playback is, in the recording's time (ms): the buffer's stream time, or the HLS window's position. */
    private long shiftPos() {
        if (ring != null) return ring.t0 + Math.max(0, player.getCurrentPosition());
        return Math.max(0, player.getCurrentPosition());
    }
    private long edgePos() {
        if (ring != null) return Math.max(0, ring.edgeTime());
        androidx.media3.common.Timeline tl = player.getCurrentTimeline();
        if (tl.isEmpty()) return 0;
        return tl.getWindow(player.getCurrentMediaItemIndex(), new androidx.media3.common.Timeline.Window()).getDefaultPositionMs();
    }
    private long oldestPos() { return ring != null ? ring.oldestTime() : 0; }
    /** How far behind live the picture is (0 when you haven't paused or rewound). */
    private long behindMs() {
        if (!shifted && shiftTarget == NONE) return 0;
        return Math.max(0, edgePos() - (shiftTarget != NONE ? shiftTarget : shiftPos()));
    }

    private void shiftPause() {
        // A TS channel handed over from the preview plays directly: move it onto a buffer now (pausing on the frame
        // you're on; play continues from a second or two later, when the buffer started).
        if (ring == null && live && bufferable(runningUrl != null ? runningUrl : item.optString("url")) && state != S_FAILED) {
            Map<String, String> h = headers(item.optJSONObject("headers"));
            String agent = h.remove("User-Agent");
            player.stop();
            ring = LiveBuffer.start(this, item.optString("url"), h, agent != null ? agent : AGENT);
            ring.t0 = 0;
            player.setMediaSource(ring.source(0));
            ring.bind(player);
            player.setPlayWhenReady(false);
            player.prepare();
        } else player.pause();
        if (ring != null) ring.keep();
        shifted = true;
        showShift(true);
    }

    private void shiftResume() {
        player.play();
        showShift(false);
    }

    /** Left/right/RW/FF on live: moves the target (held keys add up) and shows it; applied once the key settles. */
    private long atStartSince;   // when a rewind came to rest at the start of the recording (0: it isn't there)
    private void shiftBy(long delta) { shiftBy(delta, true); }
    private void shiftBy(long delta, boolean fresh) {
        if (!canRewind()) {
            if (delta < 0 && fresh && item.optBoolean("catchup")) { startOverAt(item.optInt("current"), 0); return; }   // nothing kept, but an archive
            if (delta < 0) showHud("Nothing to rewind yet"); else showBanner();
            return;
        }
        long edge = edgePos(), oldest = oldestPos();
        long base = shiftTarget != NONE ? shiftTarget : shiftPos();
        long t = base + delta;
        long nowUp = android.os.SystemClock.uptimeMillis();
        if (t < oldest) {
            // Already resting at the start of what's kept (the bar has said so) and a fresh press further back: the
            // channel's archive, from the same moment back. A held key stops at the start; it never jumps streams.
            if (base <= oldest + 1000 && delta < 0 && fresh && atStartSince > 0 && nowUp - atStartSince > 700 && item.optBoolean("catchup")) {
                long wall = System.currentTimeMillis() - (edge - oldest);
                long at = Math.max(0, wall - item.optLong("nowStart") + delta);
                startOverAt(item.optInt("current"), at);
                return;
            }
            t = oldest;
            if (atStartSince == 0) atStartSince = nowUp;
        } else atStartSince = 0;
        if (t > edge - 2500) t = edge;   // forward into the last couple of seconds: that's live
        shiftTarget = t;
        if (ring != null) ring.keep();
        showShift(!player.getPlayWhenReady());
        ui.removeCallbacks(shiftCommit); ui.postDelayed(shiftCommit, 600);
    }

    private void commitShift() {
        if (shiftTarget == NONE || player == null) return;
        long t = shiftTarget, edge = edgePos();
        shiftTarget = NONE;
        if (t >= edge) { goLive(); return; }
        if (ring != null) {
            long[] key = ring.keyAt(t);
            if (key == null) return;
            ring.t0 = key[1];
            boolean pwr = player.getPlayWhenReady();
            player.setMediaSource(ring.source(key[0]));
            player.prepare();
            player.setPlayWhenReady(pwr);   // paused: the frame at the new place shows, still paused
        } else player.seekTo(t);
        shifted = true;
        updateShiftBar();
    }

    /** Back to live: one action from anywhere behind it. */
    private void goLive() {
        ui.removeCallbacks(shiftCommit);
        shiftTarget = NONE;
        boolean was = shifted;
        shifted = false;
        if (ring != null) {
            long[] key = ring.keyAt(ring.edgeTime());
            if (key != null && was) {
                ring.t0 = key[1];
                player.setMediaSource(ring.source(key[0]));
                player.prepare();
            }
        } else if (was) player.seekToDefaultPosition();
        player.play();
        showShift(false);
        ui.removeCallbacks(shiftOff); ui.postDelayed(shiftOff, 2500);   // long enough to read LIVE
    }

    private void startOverAt(int index, long atMs) {
        if (!item.optBoolean("catchup") && index == item.optInt("current")) { showHud("No archive for this channel"); return; }
        zapKeyAt = android.os.SystemClock.uptimeMillis();
        send("startOver", "index", index, "at", atMs);
    }

    /** The bar: [sticky] while paused; otherwise it fades after 4 s (the "−4:12" pill stays at the top). */
    private void showShift(boolean sticky) {
        infoBar.setVisibility(View.GONE); ui.removeCallbacks(infoOff);
        hideOverlay();
        updateShiftBar();
        shiftBar.setVisibility(View.VISIBLE);
        syncBehindPill();
        ui.removeCallbacks(shiftTick); ui.postDelayed(shiftTick, 500);
        ui.removeCallbacks(shiftOff);
        if (!sticky) ui.postDelayed(shiftOff, 4000);
    }

    private void updateShiftBar() {
        if (player == null) return;
        long now = System.currentTimeMillis();
        long edge = edgePos(), oldest = Math.min(oldestPos(), edge), behind = behindMs();
        long pos = edge - behind;
        boolean atStart = canRewind() && pos - oldest <= 1000 && behind > 0;
        String archive = atStart && item.optBoolean("catchup") && item.optLong("nowStart") > 0 ? "◀  Start over from " + clockHm(item.optLong("nowStart")) : null;
        shiftBar.set(item.optString("title", ""), !player.getPlayWhenReady(), now - (edge - oldest), now - behind, now, behind, canRewind() && pos - oldest > 1000, archive);
    }

    /** "−4:12" at the top right while behind live and nothing else is showing; the controls' LIVE tag says the same. */
    private void syncBehindPill() {
        long b = behindMs();
        boolean show = live && shifted && b >= 1500 && shiftBar.getVisibility() != View.VISIBLE && infoBar.getVisibility() != View.VISIBLE
            && overlay.getVisibility() != View.VISIBLE && !guide.isOpen();
        behindPill.setText("−" + clock(b / 1000));
        behindPill.setVisibility(show ? View.VISIBLE : View.GONE);
        if (live) {
            if (shifted && b >= 1500) liveTag.setText("−" + clock(b / 1000));
            else { android.text.SpannableString ls = new android.text.SpannableString("●  LIVE"); ls.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF4550), 0, 1, 0); liveTag.setText(ls); }
        }
    }

    private void closeRing() {
        if (ring != null) { ring.close(); ring = null; }
        shifted = false; shiftTarget = NONE;
    }

    /** Dims (or undims) the last picture while a channel tunes: calm, and plainly "changing", never a black flash. */
    private void scrim(boolean on) {
        tuneScrim.animate().cancel();
        tuneScrim.animate().alpha(on ? 1f : 0f).setDuration(on ? 150 : 220).start();
    }

    // ---------- mini guide ----------

    private void openGuide() {
        if (guideStale()) send("guide");
        hideOverlay();
        infoBar.setVisibility(View.GONE); ui.removeCallbacks(infoOff);
        shiftBar.setVisibility(View.GONE); behindPill.setVisibility(View.GONE);
        guide.open(list(), item.optInt("current") - item.optInt("listStart"));
    }

    /** Fresh now/next from TvActivity (event "guide"): updates what's on screen without touching the stream. */
    public void updateGuide(JSONObject fresh) {
        if (fresh == null || !live || !fresh.optString("url").equals(item.optString("url"))) return;
        for (String k : new String[]{"nowTitle", "nowStart", "nowEnd", "nextTitle", "info", "catchup", "list", "total"}) if (fresh.has(k)) item.put(k, fresh.opt(k));
        if (guide.isOpen()) guide.refresh(list(), item.optInt("current") - item.optInt("listStart"));
    }

    // ---------- next episode ----------
    //
    // During the end credits (last 30 s; 10% of a short episode; at the end of a clip under a minute) a card slides in at
    // the bottom right: the next episode's still, "S1 E2 · Title", [▶ Play next] with the 10 s countdown filling it, and
    // [Watch credits]. Play next / OK = now; Watch credits / Back = carry on, and when it ends a 5 s card takes you on.
    // After 3 episodes in a row with no key pressed, the 4th waits at "Still watching …?".

    private static final int N_NONE = 0, N_CREDITS = 1, N_END = 2, N_STILL = 3;
    private static final int STILL_AFTER = 3;
    private int nextMode = N_NONE;
    private boolean creditsDismissed;   // "Watch credits": no card again until it ends (or you seek out of the credits)
    private int autoRun;                // episodes started by the countdown in a row, with no key pressed
    private String preloadUrl;          // the next episode, queued behind this one in the player
    private View nextScrim;
    private android.widget.ImageView nextThumb;
    private TextView nextLabel, nextName, nextSub, nextPlay, nextOther;
    private android.graphics.drawable.ClipDrawable nextFill;
    private long countLeft, countTotal, countLast;
    private final Runnable countRun = new Runnable() { public void run() {
        if (player == null || nextMode == N_NONE || nextMode == N_STILL) return;
        long now = android.os.SystemClock.uptimeMillis();
        // Paused by you: the countdown waits too. After the end there's nothing left to watch, so it always runs.
        if (nextMode == N_END || player.getPlayWhenReady()) countLeft -= now - countLast;
        countLast = now;
        nextFill.setLevel((int) (10_000 * (1 - Math.max(0, countLeft) / (double) countTotal)));
        if (countLeft <= 0) { autoRun++; goNext(); return; }
        ui.postDelayed(this, 33);
    } };

    private boolean hasNextEpisode() { return item.optBoolean("hasNext") && "Episodes".equals(item.optString("listTitle")); }

    /** How long before the end the card appears: 30 s; 10% under 5 minutes; 0 (only at the end) under a minute. */
    private static long creditsWindow(long d) { return d >= 300_000 ? 30_000 : d >= 60_000 ? d / 10 : 0; }

    /** Runs every second and after every seek: shows the card on entering the credits, hides it on seeking back out. */
    private void checkCredits() {
        if (player == null || live || !hasNextEpisode() || nextMode == N_END) return;
        long d = player.getDuration(), t = player.getCurrentPosition();
        if (d <= 0 || player.getCurrentMediaItemIndex() != 0) return;
        long w = creditsWindow(d);
        boolean in = w > 0 && d - t <= w;
        if (!in) {
            creditsDismissed = false;
            if (nextMode == N_CREDITS) hideNext(true);
            else if (preloadUrl != null) dropPreload();
            return;
        }
        if (nextMode == N_CREDITS) { preloadNext(); return; }
        if (nextMode != N_NONE || creditsDismissed || state != S_PLAYING || !player.getPlayWhenReady()) return;
        // The controls, the channel panel or an error are up: wait (hideOverlay checks again).
        if (overlay.getVisibility() == View.VISIBLE || errorPanel.getVisibility() == View.VISIBLE || channelList.getVisibility() == View.VISIBLE) return;
        showNext(autoRun >= STILL_AFTER ? N_STILL : N_CREDITS, 10_000);
    }

    private void showNext(int mode, long countMs) {
        android.util.Log.i("M3U", "next-episode card: " + (mode == N_CREDITS ? "credits" : mode == N_END ? "end " + countMs / 1000 + "s" : "still watching"));
        nextMode = mode;
        int i = item.optInt("current") - item.optInt("listStart") + 1;
        JSArray l = list();
        JSONObject row = i >= 0 && i < l.length() ? l.optJSONObject(i) : null;   // a missing row just keeps the generic name
        String ep = row == null ? "" : row.optString("e"), name = row == null ? "" : row.optString("n"), t = row == null ? "" : row.optString("t");
        String heading = !ep.isEmpty() ? (name.isEmpty() ? ep : ep + "  ·  " + name) : !t.isEmpty() ? t : "Next episode";
        String art = row == null ? "" : row.optString("a");
        if (art.startsWith("http")) {
            nextThumb.setVisibility(View.VISIBLE);
            nextThumb.setImageDrawable(null);
            coil.Coil.imageLoader(this).enqueue(new coil.request.ImageRequest.Builder(this)
                .data(com.wmorgan97.m3uplayer.tv.Repo.INSTANCE.img(art, 480)).target(nextThumb).crossfade(true).build());
        } else nextThumb.setVisibility(View.GONE);
        if (mode == N_STILL) {
            String show = item.optString("show", "");
            nextLabel.setText(show.isEmpty() ? "Paused" : show);
            nextName.setText("Still watching?");
            nextSub.setText("Up next: " + heading);
            nextSub.setVisibility(View.VISIBLE);
            nextPlay.setText("Continue");
            nextOther.setText("Stop");
            nextFill.setLevel(0);
            if (player.getPlaybackState() != Player.STATE_ENDED) player.pause();   // nobody's pressed anything for 3 episodes
        } else {
            nextLabel.setText("Next episode");
            nextName.setText(heading);
            nextSub.setVisibility(View.GONE);
            nextPlay.setText("▶  Play next");
            nextOther.setText(mode == N_CREDITS ? "Watch credits" : "Cancel");
            nextFill.setLevel(0);
            countTotal = countLeft = countMs;
            countLast = android.os.SystemClock.uptimeMillis();
            ui.removeCallbacks(countRun);
            ui.postDelayed(countRun, 33);
            if (mode == N_CREDITS) preloadNext();
        }
        hideOverlayOnly();
        infoBar.setVisibility(View.GONE);
        // Fade and slide in (250 ms) over a soft scrim, Play next focused.
        nextPanel.animate().cancel(); nextScrim.animate().cancel();
        if (nextPanel.getVisibility() != View.VISIBLE) {
            nextPanel.setAlpha(0f); nextPanel.setTranslationY(dp(24)); nextScrim.setAlpha(0f);
            nextPanel.setVisibility(View.VISIBLE); nextScrim.setVisibility(View.VISIBLE);
        }
        nextPanel.animate().alpha(1f).translationY(0).setDuration(250).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        nextScrim.animate().alpha(1f).setDuration(250).start();
        nextPlay.requestFocus();
        subtitlesAbove(true);
    }

    /** Subtitles move up above the card while it's on screen (it covers the bottom right), and back down after. */
    private void subtitlesAbove(boolean card) {
        if (playerView.getSubtitleView() == null) return;
        playerView.getSubtitleView().setBottomPaddingFraction(card ? 0.44f : overlay.getVisibility() == View.VISIBLE ? 0.22f : 0.08f);
    }

    /** Takes the card away (150 ms fade). [drop]: also un-queue the next episode. */
    private void hideNext(boolean drop) {
        ui.removeCallbacks(countRun);
        if (drop) dropPreload();
        if (nextMode == N_NONE && nextPanel.getVisibility() != View.VISIBLE) return;
        nextMode = N_NONE;
        subtitlesAbove(false);
        nextPanel.animate().cancel(); nextScrim.animate().cancel();
        nextPanel.animate().alpha(0f).translationY(dp(12)).setDuration(150).withEndAction(() -> nextPanel.setVisibility(View.GONE)).start();
        nextScrim.animate().alpha(0f).setDuration(150).withEndAction(() -> nextScrim.setVisibility(View.GONE)).start();
    }

    /** Play next / Continue / the countdown running out. The finished episode is saved as watched first. */
    private void goNext() {
        if (player != null && nextMode != N_END) {   // (at the end, onEnded has already saved it)
            double d = player.getDuration() > 0 ? player.getDuration() / 1000.0 : 0;
            event("progress", d, d);
        }
        hideNext(false);
        step(1);
    }

    private void nextOtherPressed() {
        if (nextMode == N_STILL) { finish(); return; }   // Stop
        nextBack();
    }

    /** Back (or Watch credits / Cancel): during the credits, carry on watching them; after the end, the usual end controls. */
    private void nextBack() {
        int m = nextMode;
        creditsDismissed = true;
        if (m == N_CREDITS) { hideNext(false); return; }   // keep the next episode queued: it plays straight after (5 s card)
        hideNext(true);
        showOverlay(true);
    }

    /** Queues the next episode behind this one so it starts at once. One connection only: not until this episode has
     *  finished downloading (ExoPlayer loads the queue in order, so the next starts loading only after that anyway). */
    private void preloadNext() {
        if (preloadUrl != null || player == null || live || player.getMediaItemCount() != 1) return;
        JSONObject n = item.optJSONObject("nextItem");
        if (n == null || n.optString("url").isEmpty() || n.optDouble("start", 0) > 1) return;   // resuming mid-episode: nothing to gain
        long d = player.getDuration();
        if (d <= 0 || player.getBufferedPosition() < d - 1500) return;   // this episode is still streaming; try again next tick
        player.addMediaSource(source(n.optString("url"), n.optJSONObject("headers")));
        player.setPauseAtEndOfMediaItems(true);   // ends as before (onEnded), rather than rolling on by itself
        preloadUrl = n.optString("url");
        android.util.Log.i("M3U", "next episode queued");
    }

    private void dropPreload() {
        if (preloadUrl == null || player == null) return;
        preloadUrl = null;
        player.setPauseAtEndOfMediaItems(false);
        if (player.getMediaItemCount() > 1 && player.getCurrentMediaItemIndex() == 0) player.removeMediaItems(1, player.getMediaItemCount());
    }

    /** load() of the queued next episode: move onto it (already buffered) instead of opening it again. */
    private void playQueued(int t) {
        if (t != token || player == null) return;
        applyTrackPrefs();
        runningUrl = preloadUrl; preloadUrl = null;
        player.setPauseAtEndOfMediaItems(false);
        player.seekToDefaultPosition(1);
        player.removeMediaItem(0);
        player.play();
        android.util.Log.i("M3U", "next episode from the queue");
    }

    // ---------- pickers ----------

    private void pickTracks(int type) {
        List<String> names = new ArrayList<>();
        List<Runnable> picks = new ArrayList<>();
        int checked = -1;
        if (type == C.TRACK_TYPE_TEXT) {
            names.add("Off");
            picks.add(() -> {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                savePref(null, null, false);
            });
            if (player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) checked = 0;
        }
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                if (g.isTrackSelected(i) && checked < 0 || g.isTrackSelected(i) && type != C.TRACK_TYPE_TEXT) checked = names.size();
                names.add(trackName(f, type, names.size()));
                final int index = i;
                picks.add(() -> {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(type, false).setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), index)).build());
                    // Remember the language, so the next channel or episode starts on it too.
                    if (type == C.TRACK_TYPE_TEXT) savePref(f.language, "textLang", true); else savePref(f.language, "audioLang", null);
                });
            }
        }
        if (names.isEmpty() || (type == C.TRACK_TYPE_TEXT && names.size() == 1)) { showHud(type == C.TRACK_TYPE_TEXT ? "No subtitles in this stream" : "No other audio"); return; }
        dialog(type == C.TRACK_TYPE_TEXT ? "Subtitles" : "Audio", names.toArray(new String[0]), checked, w -> picks.get(w).run());
    }

    private String trackName(Format f, int type, int n) {
        StringBuilder s = new StringBuilder();
        String lang = f.language == null || f.language.equals("und") ? "" : new Locale(f.language).getDisplayLanguage(Locale.ENGLISH);
        s.append(f.label != null ? f.label : !lang.isEmpty() ? lang : "Track " + n);
        if (type == C.TRACK_TYPE_AUDIO) {
            String codec = codecName(f.sampleMimeType);
            if (!codec.isEmpty()) s.append(" · ").append(codec);
            if (f.channelCount > 0) s.append(" · ").append(channels(f.channelCount));
        }
        return s.toString();
    }

    private void aspectNext() {
        aspect = (aspect + 1) % ASPECTS.length;
        playerView.setResizeMode(aspect == 1 ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : aspect == 2 ? AspectRatioFrameLayout.RESIZE_MODE_FILL : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        showHud(ASPECTS[aspect]);
    }

    /** Live TV: the channels down the left, the one playing picked; up/down browse, OK switches, Back / right close. */
    private void openChannels() {
        JSArray l = list();
        final String last = item.optString("last", "");
        final int off = last.isEmpty() ? 0 : 1;   // "Last channel" sits above the list
        final int cur = Math.max(0, item.optInt("current") - item.optInt("listStart")) + off;
        List<String> rows = new ArrayList<>();
        if (!last.isEmpty()) rows.add("↩  Last channel\n" + last);
        for (int i = 0; i < l.length(); i++) {
            JSONObject r = l.optJSONObject(i);
            String t = r == null ? "" : r.optString("t"), d = r == null ? "" : r.optString("s");
            rows.add((i + off == cur ? "▶  " : "") + (d.isEmpty() ? t : t + "\n" + d));
        }
        channelList.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                TextView v = (TextView) super.getView(pos, convert, parent);
                // Slim two-line rows (TiviMate-dense): the name, and what's on in smaller grey text under it.
                String s = rows.get(pos); int nl = s.indexOf('\n');
                android.text.SpannableString sp = new android.text.SpannableString(s);
                if (nl > 0) {
                    sp.setSpan(new android.text.style.RelativeSizeSpan(.8f), nl + 1, s.length(), 0);
                    sp.setSpan(new android.text.style.ForegroundColorSpan(0x99FFFFFF), nl + 1, s.length(), 0);
                }
                v.setText(sp);
                v.setTextColor(s.startsWith("▶") ? 0xFF7FAAFF : Color.WHITE);   // the channel playing now, in blue
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                v.setMaxLines(2);
                v.setLineSpacing(0, 1f);
                v.setEllipsize(android.text.TextUtils.TruncateAt.END);
                v.setMinHeight(0); v.setMinimumHeight(0);
                v.setPadding(dp(18), dp(5), dp(18), dp(5));
                return v;
            }
        });
        hideOverlay();
        infoBar.setVisibility(View.GONE); ui.removeCallbacks(infoOff);
        channelList.setVisibility(View.VISIBLE);
        channelList.setSelection(Math.max(0, cur - 3));
        channelList.post(() -> { channelList.requestFocus(); channelList.setSelection(cur); });
    }
    private void closeChannels() { channelList.setVisibility(View.GONE); }

    private void showList() {
        JSArray l = list();
        String[] rows = new String[l.length()];
        for (int i = 0; i < l.length(); i++) {
            JSONObject r = l.optJSONObject(i);
            String t = r == null ? "" : r.optString("t"), d = r == null ? "" : r.optString("s");
            rows[i] = d.isEmpty() ? t : t + "  ·  " + d;
        }
        int cur = item.optInt("current") - item.optInt("listStart");
        dialog(item.optString("listTitle", "Episodes"), rows, cur, w -> {
            send("jump", "index", item.optInt("listStart") + w);
        });
    }

    /** What's playing, like VLC's media info: codec, size, frame rate, HDR, bitrate, audio format. */
    private void showInfo() {
        StringBuilder s = new StringBuilder();
        String about = item.optString("info", "");
        if (!about.isEmpty()) s.append(about).append("\n\n");
        Format v = player.getVideoFormat(), a = player.getAudioFormat();
        if (v != null) {
            s.append("Video: ").append(codecName(v.sampleMimeType));
            if (v.width > 0) s.append(" · ").append(v.width).append("×").append(v.height);
            if (v.frameRate > 0) s.append(" · ").append(String.format(Locale.ROOT, "%.3g fps", v.frameRate));
            if (v.colorInfo != null && (v.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084 || v.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG))
                s.append(v.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG ? " · HDR (HLG)" : " · HDR10");
            if (v.codecs != null && v.codecs.startsWith("dvh")) s.append(" · Dolby Vision");
            s.append("\n");
        }
        if (a != null) {
            s.append("Audio: ").append(codecName(a.sampleMimeType));
            if (a.channelCount > 0) s.append(" · ").append(channels(a.channelCount));
            if (a.sampleRate > 0) s.append(" · ").append(a.sampleRate / 1000).append(" kHz");
            if (a.language != null && !a.language.equals("und")) s.append(" · ").append(a.language.toUpperCase(Locale.ROOT));
            s.append("\n");
        }
        int br = v != null && v.bitrate > 0 ? v.bitrate : v != null && v.peakBitrate > 0 ? v.peakBitrate : 0;
        if (br > 0) s.append(String.format(Locale.ROOT, "Bitrate: %.1f Mbit/s\n", br / 1_000_000.0));
        if (s.length() == 0) s.append("Stream details appear once it's playing.");
        new AlertDialog.Builder(this, R.style.TvDialog)
            .setTitle(item.optString("title")).setMessage(s.toString().trim()).setPositiveButton("Close", null).show();
    }

    /** A reminder coming up while you watch: shown at the top for 20 seconds; OK runs it, Back dismisses it. */
    public void notice(String text, String hint, Runnable onOk) {
        noticeView.setText(text + "      " + hint);
        noticeView.setVisibility(View.VISIBLE);
        noticeAction = onOk;
        ui.removeCallbacks(noticeOff); ui.postDelayed(noticeOff, 20_000);
    }

    private static final int[] SLEEP_MIN = {0, 15, 30, 45, 60, 90, 120};
    private void pickSleep() {
        String[] rows = {"Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "1½ hours", "2 hours"};
        int cur = 0;
        if (sleepAt > 0) { long left = (sleepAt - System.currentTimeMillis()) / 60_000; for (int i = 1; i < SLEEP_MIN.length; i++) if (left <= SLEEP_MIN[i]) { cur = i; break; } }
        dialog("Sleep timer", rows, cur, w -> {
            ui.removeCallbacks(sleepRun);
            sleepAt = w == 0 ? 0 : System.currentTimeMillis() + SLEEP_MIN[w] * 60_000L;
            if (sleepAt > 0) ui.postDelayed(sleepRun, SLEEP_MIN[w] * 60_000L);
            showHud(w == 0 ? "Sleep timer off" : "Sleep in " + rows[w]);
            onTick();
        });
    }

    private interface Pick { void on(int which); }
    private void dialog(String heading, String[] rows, int checked, Pick pick) {
        AlertDialog dlg = new AlertDialog.Builder(this, R.style.TvDialog)
            .setTitle(heading)
            .setSingleChoiceItems(rows, checked, (d, w) -> { d.dismiss(); if (player != null) pick.on(w); })
            .setNegativeButton("Cancel", null).show();
        // The row the remote is on: a clear outlined highlight (the theme's default is nearly invisible on a TV).
        if (dlg.getListView() != null) { dlg.getListView().setSelector(R.drawable.tv_list_focus); dlg.getListView().setDrawSelectorOnTop(true); }   // on top: the theme gives every row its own opaque background
    }

    private static String codecName(String mime) {
        if (mime == null) return "";
        switch (mime) {
            case MimeTypes.VIDEO_H265: return "HEVC (H.265)";
            case MimeTypes.VIDEO_H264: return "H.264";
            case MimeTypes.VIDEO_AV1: return "AV1";
            case MimeTypes.VIDEO_VP9: return "VP9";
            case MimeTypes.VIDEO_MPEG2: return "MPEG-2";
            case MimeTypes.VIDEO_DOLBY_VISION: return "Dolby Vision";
            case MimeTypes.AUDIO_E_AC3: case MimeTypes.AUDIO_E_AC3_JOC: return "Dolby Digital Plus";
            case MimeTypes.AUDIO_AC3: return "Dolby Digital";
            case MimeTypes.AUDIO_TRUEHD: return "Dolby TrueHD";
            case MimeTypes.AUDIO_DTS: case MimeTypes.AUDIO_DTS_HD: return "DTS";
            case MimeTypes.AUDIO_AAC: return "AAC";
            case MimeTypes.AUDIO_MPEG: case MimeTypes.AUDIO_MPEG_L2: return "MP2 / MP3";
            case MimeTypes.AUDIO_OPUS: return "Opus";
            case MimeTypes.AUDIO_FLAC: return "FLAC";
            default: return mime.replaceFirst("^(video|audio)/", "").toUpperCase(Locale.ROOT);
        }
    }
    private static String channels(int n) { return n == 1 ? "Mono" : n == 2 ? "Stereo" : n == 6 ? "5.1" : n == 8 ? "7.1" : n + " ch"; }

    private static String clock(long s) {
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        String json = intent.getStringExtra("item");
        if (json != null) load(NativePlayerPlugin.item(json));
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        if (resumePending) {
            // Exactly one prepare: a coalesced start or a reconnect still queued would otherwise open a second connection.
            resumePending = false;
            ui.removeCallbacks(startRun);
            ui.removeCallbacksAndMessages(RETRY);
            setState(S_PREPARING);
            begin(token);   // live: a fresh connection at the live edge; films: at resumeAtMs (set in onStop)
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;   // pending starts/reconnects wait for onStart instead of reopening the stream in the background
        ui.removeCallbacksAndMessages(STALL);
        if (isFinishing() || player == null) return;
        // Home button: stop, not pause. A paused stream keeps its HTTP connection, and the provider allows only one.
        // Nothing to bring back after a failure (the error panel stays) or a film that ended.
        if (state == S_FAILED || player.getPlaybackState() == Player.STATE_ENDED) return;
        if (!live && frameShown) {   // a film that got as far as a picture (not one still connecting) has a place to come back to
            resumeAtMs = Math.max(0, player.getCurrentPosition());
            resumePaused = !player.getPlayWhenReady();
            double d = player.getDuration() > 0 ? player.getDuration() / 1000.0 : 0;
            event("progress", resumeAtMs / 1000.0, d);   // save it now: the app may be killed while in the background
        }
        player.stop(); runningUrl = null;
        closeRing();   // the recording goes too: back from Home is a fresh start at live
        resumePending = true;
    }

    @Override
    public void finish() {
        if (!finished && player != null) {
            finished = true;
            double d = player.getDuration() > 0 ? player.getDuration() / 1000.0 : 0;
            event("closed", Math.max(0, player.getCurrentPosition()) / 1000.0, d);
        }
        // Live and playing: the stream carries on in the preview window, which shrinks back into its corner (the reverse of
        // going full screen). This screen fades out over it instead of cutting away.
        int ps = player == null ? Player.STATE_IDLE : player.getPlaybackState();
        boolean toPreview = live && player != null && runningUrl != null && state != S_FAILED && (ps == Player.STATE_READY || ps == Player.STATE_BUFFERING);
        if (toPreview) { handedBack = true; PlayerHolder.returnPlayer(player, runningUrl); }
        else if (player != null) { player.stop(); runningUrl = null; }   // free the provider's one connection now, before the preview may start
        // A closing player screen is no longer "the" player: the next channel must start a new one, not be sent here.
        if (current == this) current = null;
        super.finish();
        if (toPreview) overridePendingTransition(R.anim.m3u_hold, R.anim.m3u_fade_out);
    }
    private boolean handedBack;

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);   // every runnable posted on ui (spinner, retries, watchdog, zaps); each also checks player != null
        if (!finished && player != null) {
            finished = true;
            double d = player.getDuration() > 0 ? player.getDuration() / 1000.0 : 0;
            event("closed", Math.max(0, player.getCurrentPosition()) / 1000.0, d);
        }
        if (player != null) {
            ExoPlayer p = player;
            player = null;   // first, so nothing that runs from here on can reach a player that's been handed back
            if (listener != null) p.removeListener(listener);
            p.clearVideoFrameMetadataListener(fpsMeter);
            listener = null;
            playerView.setPlayer(null);   // (the preview has already put its own surface on a handed-back player)
            if (!handedBack) { closeRing(); PlayerHolder.giveBack(p); }
            else ring = null;   // the preview plays on through it; the buffer closes itself when that player lets go
        }
        if (current == this) current = null;
        super.onDestroy();
    }

    // ---------- layout (built in code: one screen, no XML to keep in step) ----------

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private TextView text(float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** Round icon button: white icon, and a solid white disc with a dark icon when the remote is on it. */
    private ImageButton icon(int res, String label, View.OnClickListener click) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setContentDescription(label);
        b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable on = new GradientDrawable(); on.setShape(GradientDrawable.OVAL); on.setColor(Color.WHITE);
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, f) -> b.setColorFilter(f ? Color.parseColor("#0E1014") : Color.WHITE));
        b.setColorFilter(Color.WHITE);
        b.setFocusable(true);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.setMargins(dp(4), 0, dp(4), 0);   // 48dp discs, 8dp apart: 11 buttons fit 960dp (1080p and 4K alike)
        b.setLayoutParams(lp);
        return b;
    }

    /** A TV button, the same in every panel of the player: focused = solid white pill, dark text, a little bigger, with
     *  a soft glow; otherwise a dark translucent pill with light text and a clear outline. [fill]: drawn over the pill. */
    private TextView pill(String label, android.graphics.drawable.Drawable fill) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(26), 0, dp(26), 0);
        b.setMinWidth(dp(120));
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable on = new GradientDrawable(); on.setColor(Color.WHITE); on.setCornerRadius(dp(24));
        GradientDrawable off = new GradientDrawable(); off.setColor(0x8C0E1014); off.setCornerRadius(dp(24)); off.setStroke(dp(2), 0x8CFFFFFF);
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, off);
        b.setBackground(fill == null ? bg : new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{bg, fill}));
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{0xFF0E1014, Color.WHITE}));
        b.setFocusable(true);
        b.setOutlineAmbientShadowColor(Color.WHITE); b.setOutlineSpotShadowColor(Color.WHITE);   // the glow: a white shadow when raised
        b.setOnFocusChangeListener((v, f) -> {
            v.animate().scaleX(f ? 1.05f : 1f).scaleY(f ? 1.05f : 1f).translationZ(f ? dp(10) : 0).setDuration(120).start();
        });
        return b;
    }
    private LinearLayout.LayoutParams pillParams(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48)); lp.setMargins(left, 0, 0, 0); return lp;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setShutterBackgroundColor(Color.BLACK);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        tuneScrim = new View(this);
        tuneScrim.setBackgroundColor(0x80000000);
        tuneScrim.setAlpha(0f);
        root.addView(tuneScrim, new FrameLayout.LayoutParams(-1, -1));

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFF5B93FF));   // the app's blue
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER);
        root.addView(spinner, sp);

        // Controls
        FrameLayout ov = new FrameLayout(this);
        overlay = ov;
        GradientDrawable topShade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000});
        GradientDrawable bottomShade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x00000000});
        View ts = new View(this); ts.setBackground(topShade);
        View bs = new View(this); bs.setBackground(bottomShade);
        ov.addView(ts, new FrameLayout.LayoutParams(-1, dp(150), Gravity.TOP));
        ov.addView(bs, new FrameLayout.LayoutParams(-1, dp(220), Gravity.BOTTOM));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(48), dp(32), dp(48), 0);   // 48dp sides (overscan-safe), as everywhere in the app
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        badge = text(12, Color.WHITE, true);
        GradientDrawable outline = new GradientDrawable(); outline.setStroke(dp(1), 0xCCFFFFFF); outline.setCornerRadius(dp(3));
        badge.setBackground(outline);
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2); bl.setMargins(0, 0, dp(8), 0);
        titleRow.addView(badge, bl);
        liveTag = text(13, Color.WHITE, true);
        android.text.SpannableString ls = new android.text.SpannableString("●  LIVE");
        ls.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF4550), 0, 1, 0);
        liveTag.setText(ls);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2); ll.setMargins(0, 0, dp(16), 0);
        titleRow.addView(liveTag, ll);
        title = text(26, Color.WHITE, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        clockLabel = text(18, 0xFFD5DAE3, false);
        clockLabel.setPadding(dp(24), 0, 0, 0);
        titleRow.addView(clockLabel);
        top.addView(titleRow);
        subtitle = text(16, 0xFF9AA3B5, false);
        subtitle.setPadding(0, dp(4), 0, 0);
        top.addView(subtitle);
        ov.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(48), 0, dp(48), dp(32));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        elapsed = text(15, Color.WHITE, false);
        total = text(15, 0xFF9AA3B5, false);
        elapsed.setMinWidth(dp(56)); total.setMinWidth(dp(56)); total.setGravity(Gravity.END);   // fixed width: the bar doesn't jump as the digits change
        seek = new SeekBar(this);
        seek.setFocusable(true);
        seek.getProgressDrawable().setTint(0xFF5B93FF);   // the app's blue
        seek.getThumb().setTint(Color.WHITE);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && player != null) { pendingSeek = p * 1000L; elapsed.setText(clock(p)); ui.removeCallbacks(commitSeek); ui.postDelayed(commitSeek, 600); } }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        bar.addView(elapsed);
        bar.addView(seek, new LinearLayout.LayoutParams(0, dp(40), 1));
        bar.addView(total);
        bottom.addView(bar);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(8), 0, 0);
        int R_PLAY = androidx.media3.ui.R.drawable.exo_icon_pause;
        prevBtn = icon(androidx.media3.ui.R.drawable.exo_icon_previous, "Previous", v -> step(-1));
        backBtn = icon(androidx.media3.ui.R.drawable.exo_icon_rewind, "Back 30 seconds", v -> seekBy(-30_000));
        playBtn = icon(R_PLAY, "Play / pause", v -> togglePlay());
        fwdBtn = icon(androidx.media3.ui.R.drawable.exo_icon_fastforward, "Forward 30 seconds", v -> seekBy(30_000));
        nextBtn = icon(androidx.media3.ui.R.drawable.exo_icon_next, "Next", v -> step(1));
        audioBtn = icon(androidx.media3.ui.R.drawable.exo_ic_audiotrack, "Audio", v -> pickTracks(C.TRACK_TYPE_AUDIO));
        subsBtn = icon(androidx.media3.ui.R.drawable.exo_ic_subtitle_on, "Subtitles", v -> pickTracks(C.TRACK_TYPE_TEXT));
        aspectBtn = icon(androidx.media3.ui.R.drawable.exo_icon_fullscreen_enter, "Picture size", v -> aspectNext());
        infoBtn = icon(android.R.drawable.ic_menu_info_details, "Info", v -> showInfo());
        listBtn = icon(android.R.drawable.ic_menu_sort_by_size, "List", v -> showList());
        sleepBtn = icon(android.R.drawable.ic_lock_idle_alarm, "Sleep timer", v -> pickSleep());
        for (View b : new View[]{prevBtn, backBtn, playBtn, fwdBtn, nextBtn}) buttons.addView(b);
        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        for (View b : new View[]{audioBtn, subsBtn, aspectBtn, sleepBtn, infoBtn, listBtn}) buttons.addView(b);
        bottom.addView(buttons);
        ov.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        root.addView(ov, new FrameLayout.LayoutParams(-1, -1));

        // Error panel: what went wrong in plain words, Try again, and (live) Last channel. Back closes the player.
        LinearLayout ep = new LinearLayout(this);
        errorPanel = ep;
        ep.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable epBg = new GradientDrawable(); epBg.setColor(0xF0181B22); epBg.setCornerRadius(dp(16));
        ep.setBackground(epBg);
        ep.setPadding(dp(26), dp(22), dp(26), dp(20));
        TextView eh = text(20, Color.WHITE, true);
        eh.setText("Can't play this");
        errorText = text(16, 0xCCFFFFFF, false);
        errorText.setSingleLine(false);
        errorText.setMaxLines(3);
        errorText.setPadding(0, dp(6), 0, 0);
        ep.addView(eh);
        ep.addView(errorText);
        LinearLayout eb = new LinearLayout(this);
        eb.setPadding(0, dp(16), 0, 0);
        retryBtn = pill("Try again", null);
        retryBtn.setOnClickListener(v -> tryAgain());
        lastBtn = pill("Last channel", null);
        lastBtn.setOnClickListener(v -> {
            errorPanel.setVisibility(View.GONE);
            send("last");
        });
        eb.setClipChildren(false); eb.setClipToPadding(false); ep.setClipChildren(false); ep.setClipToPadding(false);
        eb.addView(retryBtn, pillParams(0));
        eb.addView(lastBtn, pillParams(dp(12)));
        ep.addView(eb);
        ep.setVisibility(View.GONE);
        root.addView(ep, new FrameLayout.LayoutParams(dp(480), -2, Gravity.CENTER));

        hud = text(30, Color.WHITE, true);
        GradientDrawable hb = new GradientDrawable(); hb.setColor(0x99000000); hb.setCornerRadius(dp(14));
        hud.setBackground(hb);
        hud.setPadding(dp(22), dp(10), dp(22), dp(10));
        hud.setVisibility(View.GONE);
        root.addView(hud, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        noticeView = text(18, Color.WHITE, true);
        GradientDrawable noticeBg = new GradientDrawable(); noticeBg.setColor(0xF01A1D24); noticeBg.setCornerRadius(dp(10)); noticeBg.setStroke(dp(1), 0x33FFFFFF);
        noticeView.setBackground(noticeBg);
        noticeView.setPadding(dp(22), dp(12), dp(22), dp(12));
        noticeView.setVisibility(View.GONE);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL); nl.setMargins(0, dp(28), 0, 0);
        root.addView(noticeView, nl);

        // Next-episode card (see showNext), over a soft scrim in the bottom-right corner:
        //   [ still 16:9 ]  Next episode
        //                   S1 E2  ·  Title
        //   [ ▶ Play next ▓▓▓░░ ]  [ Watch credits ]
        nextScrim = new View(this);
        nextScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BR_TL, new int[]{0xCC000000, 0x66000000, 0x00000000}));
        nextScrim.setVisibility(View.GONE);
        root.addView(nextScrim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout np = new LinearLayout(this);
        nextPanel = np;
        np.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pb = new GradientDrawable(); pb.setColor(0xF2181B22); pb.setCornerRadius(dp(18)); pb.setStroke(dp(1), 0x26FFFFFF);
        np.setBackground(pb);
        np.setElevation(dp(16));
        np.setPadding(dp(18), dp(18), dp(18), dp(18));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        nextThumb = new android.widget.ImageView(this);
        nextThumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        GradientDrawable thBg = new GradientDrawable(); thBg.setColor(0xFF232733); thBg.setCornerRadius(dp(10));
        nextThumb.setBackground(thBg);
        nextThumb.setClipToOutline(true);
        LinearLayout.LayoutParams thl = new LinearLayout.LayoutParams(dp(160), dp(90)); thl.setMargins(0, 0, dp(16), 0);
        head.addView(nextThumb, thl);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        nextLabel = text(14, 0xFF9AA3B5, false);
        nextName = text(20, Color.WHITE, true);
        nextName.setSingleLine(false); nextName.setMaxLines(2);
        nextName.setPadding(0, dp(3), 0, 0);
        nextSub = text(15, 0xFFD5DAE3, false);
        nextSub.setPadding(0, dp(4), 0, 0);
        words.addView(nextLabel); words.addView(nextName); words.addView(nextSub);
        head.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        np.addView(head);
        LinearLayout nb = new LinearLayout(this);
        nb.setPadding(0, dp(18), 0, dp(2));
        nb.setClipChildren(false); nb.setClipToPadding(false);
        np.setClipChildren(false); np.setClipToPadding(false);
        // The countdown fills Play next from the left: a light blue on the white (focused) pill, light on the dark one.
        StateListDrawable fillCol = new StateListDrawable();
        GradientDrawable fOn = new GradientDrawable(); fOn.setColor(0xFFC4D6FF); fOn.setCornerRadius(dp(24));
        GradientDrawable fOff = new GradientDrawable(); fOff.setColor(0x40FFFFFF); fOff.setCornerRadius(dp(24));
        fillCol.addState(new int[]{android.R.attr.state_focused}, fOn);
        fillCol.addState(new int[]{}, fOff);
        nextFill = new android.graphics.drawable.ClipDrawable(fillCol, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        nextPlay = pill("▶  Play next", nextFill);
        nextPlay.setOnClickListener(v -> goNext());
        nextOther = pill("Watch credits", null);
        nextOther.setOnClickListener(v -> nextOtherPressed());
        nb.addView(nextPlay, pillParams(0));
        nb.addView(nextOther, pillParams(dp(12)));
        np.addView(nb);
        np.setVisibility(View.GONE);
        FrameLayout.LayoutParams npl = new FrameLayout.LayoutParams(dp(420), -2, Gravity.BOTTOM | Gravity.END);
        npl.setMargins(0, 0, dp(48), dp(32));   // the player's 48dp side / 32dp bottom margins, like the banner and controls
        root.addView(np, npl);

        // Channel panel (live TV)
        channelList = new android.widget.ListView(this);
        GradientDrawable cl = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF20B0D12, 0xE60B0D12});
        channelList.setBackground(cl);
        channelList.setDivider(new ColorDrawable(0x22FFFFFF));
        channelList.setDividerHeight(1);
        StateListDrawable sel = new StateListDrawable();
        GradientDrawable selOn = new GradientDrawable(); selOn.setColor(0x33FFFFFF); selOn.setStroke(dp(2), Color.WHITE); selOn.setCornerRadius(dp(8));
        sel.addState(new int[]{android.R.attr.state_focused}, selOn);
        sel.addState(new int[]{android.R.attr.state_selected}, selOn);
        sel.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        channelList.setSelector(sel);
        channelList.setDrawSelectorOnTop(true);
        channelList.setPadding(dp(12), dp(24), dp(12), dp(24));
        channelList.setClipToPadding(false);
        channelList.setVisibility(View.GONE);
        channelList.setOnItemClickListener((parent, v, pos, id) -> {
            closeChannels();
            int off = item.optString("last", "").isEmpty() ? 0 : 1;
            if (pos < off) { send("last"); return; }
            if (pos - off == item.optInt("current") - item.optInt("listStart")) return;
            send("jump", "index", item.optInt("listStart") + pos - off);
        });
        root.addView(channelList, new FrameLayout.LayoutParams(dp(420), -1, Gravity.START));

        // Channel banner (live TV): a translucent panel across the bottom, inside the 48dp page margins.
        //   [ 101 ] [ logo ]  Channel name                                         21:04
        //                     Programme title                  20:00 – 21:00
        //                     ───────────progress──────────────────────────
        //                     Next  21:00   Following programme
        LinearLayout ib = new LinearLayout(this);
        infoBar = ib;
        // Top-aligned with baselines: the clock sits on the channel name's baseline; number and logo are centred (below).
        ib.setGravity(Gravity.TOP);
        GradientDrawable ibBg = new GradientDrawable(); ibBg.setColor(PANEL); ibBg.setCornerRadius(dp(12)); ibBg.setStroke(dp(1), 0x1FFFFFFF);
        ib.setBackground(ibBg);
        ib.setPadding(dp(24), dp(16), dp(24), dp(16));
        infoNum = text(34, Color.WHITE, true);
        infoNum.setMinWidth(dp(64));
        infoNum.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nump = new LinearLayout.LayoutParams(-2, -2); nump.setMargins(0, 0, dp(16), 0); nump.gravity = Gravity.CENTER_VERTICAL;
        ib.addView(infoNum, nump);
        infoLogo = new android.widget.ImageView(this);
        infoLogo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        GradientDrawable logoBg = new GradientDrawable(); logoBg.setColor(0xFF232733); logoBg.setCornerRadius(dp(10));
        infoLogo.setBackground(logoBg);
        infoLogo.setClipToOutline(true);
        infoLogo.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams lgp = new LinearLayout.LayoutParams(dp(120), dp(68)); lgp.setMargins(0, 0, dp(24), 0); lgp.gravity = Gravity.CENTER_VERTICAL;
        ib.addView(infoLogo, lgp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // Name line: the name, then (right-aligned, above the programme times) the tuning ring or the quality badges.
        LinearLayout l1 = new LinearLayout(this); l1.setGravity(Gravity.CENTER_VERTICAL);
        infoName = text(24, Color.WHITE, true);
        l1.addView(infoName, new LinearLayout.LayoutParams(0, -2, 1));
        infoSpin = new ProgressBar(this);
        infoSpin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xCCFFFFFF));
        infoSpin.setVisibility(View.GONE);
        LinearLayout.LayoutParams isp = new LinearLayout.LayoutParams(dp(20), dp(20)); isp.setMargins(dp(12), 0, 0, 0);
        l1.addView(infoSpin, isp);
        infoBadges = new LinearLayout(this); infoBadges.setGravity(Gravity.CENTER_VERTICAL);
        l1.addView(infoBadges);
        col.addView(l1);
        l1.setBaselineAlignedChildIndex(0);    // the row's baseline is the name's ...
        col.setBaselineAlignedChildIndex(0);   // ... and so is the column's (for the clock)
        LinearLayout l2 = new LinearLayout(this); l2.setGravity(Gravity.CENTER_VERTICAL); l2.setPadding(0, dp(4), 0, 0);
        infoNow = text(18, 0xFFD5DAE3, false);
        infoTime = text(15, 0xFF9AA3B5, false); infoTime.setPadding(dp(16), 0, 0, 0);
        l2.addView(infoNow, new LinearLayout.LayoutParams(0, -2, 1)); l2.addView(infoTime);
        col.addView(l2);
        infoProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        infoProgress.setMax(1000);
        GradientDrawable track = new GradientDrawable(); track.setColor(0x33FFFFFF); track.setCornerRadius(dp(2));
        GradientDrawable fill = new GradientDrawable(); fill.setColor(0xFF5B93FF); fill.setCornerRadius(dp(2));
        infoProgress.setProgressDrawable(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{ track, new android.graphics.drawable.ClipDrawable(fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL) }));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(3)); pp.setMargins(0, dp(8), 0, 0);
        col.addView(infoProgress, pp);
        infoNext = text(14, 0xFF9AA3B5, false); infoNext.setPadding(0, dp(8), 0, 0);
        col.addView(infoNext);
        ib.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        infoClock = text(22, 0xFFD5DAE3, false);
        infoClock.setPadding(dp(24), 0, 0, 0);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2); clp.gravity = Gravity.TOP;
        ib.addView(infoClock, clp);
        ib.setVisibility(View.GONE);
        FrameLayout.LayoutParams ibl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        ibl.setMargins(dp(48), 0, dp(48), dp(32));
        root.addView(ib, ibl);

        // Pause / rewind bar: the banner's place and look (see ShiftBar).
        shiftBar = new ShiftBar(this);
        FrameLayout.LayoutParams sbl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        sbl.setMargins(dp(48), 0, dp(48), dp(32));
        root.addView(shiftBar, sbl);

        // "−4:12": behind live, nothing else up. Top right, inside the overscan margins; quiet (an outlined dark pill).
        behindPill = text(16, Color.WHITE, true);
        GradientDrawable bp = new GradientDrawable(); bp.setColor(0x8C0E1014); bp.setStroke(dp(2), 0x8CFFFFFF); bp.setCornerRadius(dp(18));
        behindPill.setBackground(bp);
        behindPill.setPadding(dp(16), dp(7), dp(16), dp(7));
        behindPill.setVisibility(View.GONE);
        FrameLayout.LayoutParams bpl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        bpl.setMargins(0, dp(32), dp(48), 0);
        root.addView(behindPill, bpl);

        // Mini guide (OK while watching live): bottom panel, same margins as the banner.
        guide = new MiniGuide(this, new MiniGuide.Host() {
            @Override public void pick(int i) {
                guide.close();
                int abs = item.optInt("listStart") + i;
                if (abs == item.optInt("current")) return;
                zapKeyAt = android.os.SystemClock.uptimeMillis();
                lastLoadReq = 0;
                send("jump", "index", abs);
            }
            @Override public void startOver(int i) { guide.close(); startOverAt(item.optInt("listStart") + i, 0); }
            @Override public void allChannels() { guide.close(); openChannels(); }
            @Override public void closed() { if (shifted && player != null && !player.getPlayWhenReady()) showShift(true); else syncBehindPill(); }
        });
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        gl.setMargins(dp(48), 0, dp(48), dp(32));
        guide.setClipChildren(false);
        root.setClipChildren(false);
        root.addView(guide, gl);

        setContentView(root);
    }

    // ---------- channel logos ----------

    /**
     * Channel logos for the banner, without an image library: fetched on one background thread, decoded at about the
     * size shown (120x68dp), kept in a small in-memory LRU keyed by URL. The UI thread only ever sets a ready bitmap.
     * Each ImageView is tagged with the URL it wants, so a slow logo can't land on the banner of a later channel.
     */
    static final class Logos {
        private static final android.util.LruCache<String, android.graphics.Bitmap> cache = new android.util.LruCache<String, android.graphics.Bitmap>(6 * 1024 * 1024) {
            @Override protected int sizeOf(String k, android.graphics.Bitmap b) { return b.getByteCount(); }
        };
        private static final java.util.Set<String> failed = java.util.Collections.synchronizedSet(new java.util.HashSet<>());   // don't refetch a dead logo on every zap
        private static final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
        private static final Handler main = new Handler(Looper.getMainLooper());
        private static final int MAX_BYTES = 2 * 1024 * 1024;

        static void show(android.widget.ImageView v, String url, int w, int h) {
            v.setTag(url);
            v.setImageDrawable(null);
            boolean usable = url != null && (url.startsWith("http://") || url.startsWith("https://")) && !failed.contains(url);
            v.setVisibility(usable ? View.VISIBLE : View.GONE);   // no logo: the name moves up next to the number, no empty box
            if (!usable) return;
            // Through the app's picture loader and the same shrunk address the channel lists use: a logo seen in a list is
            // already on the TV (disk cache), so the banner shows it at once instead of downloading it again each time.
            String small = com.wmorgan97.m3uplayer.tv.Repo.INSTANCE.logo(url);   // the logo's own file (transparency intact), cached on the TV
            if (small != null) {
                coil.Coil.imageLoader(v.getContext()).enqueue(new coil.request.ImageRequest.Builder(v.getContext())
                    .data(small).target(v).crossfade(false).allowHardware(true)
                    .listener(new coil.request.ImageRequest.Listener() {
                        @Override public void onError(coil.request.ImageRequest r, coil.request.ErrorResult e) { if (url.equals(v.getTag())) v.setVisibility(View.GONE); }
                    }).build());
                return;
            }
            android.graphics.Bitmap hit = cache.get(url);
            if (hit != null) { v.setImageBitmap(hit); return; }
            io.execute(() -> {
                android.graphics.Bitmap b = fetch(url, w, h);
                if (b != null) cache.put(url, b); else failed.add(url);
                main.post(() -> {
                    if (!url.equals(v.getTag())) return;   // the banner has moved on to another channel
                    if (b != null) v.setImageBitmap(b); else v.setVisibility(View.GONE);
                });
            });
        }

        private static android.graphics.Bitmap fetch(String url, int w, int h) {
            java.net.HttpURLConnection c = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(5_000); c.setReadTimeout(8_000);
                c.setInstanceFollowRedirects(true);
                if (c.getResponseCode() / 100 != 2) { android.util.Log.w("M3U", "channel logo: HTTP " + c.getResponseCode()); return null; }
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[16 * 1024];
                    for (int n; (n = in.read(buf)) > 0; ) { out.write(buf, 0, n); if (out.size() > MAX_BYTES) { android.util.Log.w("M3U", "channel logo: over 2MB, skipped"); return null; } }
                }
                byte[] data = out.toByteArray();
                // Decode at the smallest power-of-two scale that still covers the box: big source logos stay cheap.
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (o.outWidth <= 0 || o.outHeight <= 0) { android.util.Log.w("M3U", "channel logo: not an image"); return null; }
                int sample = 1;
                while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
                o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = sample;
                return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            } catch (Exception | OutOfMemoryError e) {
                android.util.Log.w("M3U", "channel logo failed: " + e.getClass().getSimpleName());   // never the URL: it can carry a login
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }
}
