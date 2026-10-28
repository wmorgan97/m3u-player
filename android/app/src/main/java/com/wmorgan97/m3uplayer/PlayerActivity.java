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
 *   controls hidden: OK shows them · left/right skip 10s (held: 30s) · up/down change channel · Back closes
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
    private final Runnable spinOn = () -> this.spinner.setVisibility(View.VISIBLE);
    private final Runnable startRun = () -> begin(token);
    private View errorPanel;
    private TextView errorText;
    private Button retryBtn, lastBtn;

    private View overlay, nextPanel, infoBar;
    private TextView infoNum, infoName, infoNow, infoTime, infoNext, infoClock;
    private android.widget.ImageView infoLogo;
    private ProgressBar infoProgress;
    private long loadAt;   // when the current channel was asked for (for the switch-time log)
    private final Runnable infoOff = () -> infoBar.setVisibility(View.GONE);
    private android.widget.ListView channelList;   // live TV: TiviMate-style channel panel on OK
    private TextView clockLabel;
    private TextView title, subtitle, badge, liveTag, elapsed, total, hud, nextCount, nextName;
    private SeekBar seek;
    private ProgressBar spinner;
    private ImageButton playBtn, prevBtn, nextBtn, backBtn, fwdBtn, audioBtn, subsBtn, aspectBtn, infoBtn, listBtn, sleepBtn;
    private TextView noticeView;          // reminders: "Starting now on …  ·  OK to watch"
    private Runnable noticeAction;
    private final Runnable noticeOff = () -> { noticeView.setVisibility(View.GONE); noticeAction = null; };
    private long sleepAt = 0;             // sleep timer: when playback stops (0 = off)
    private final Runnable sleepRun = () -> { if (player != null) player.pause(); finish(); };
    private Button playNow;
    private int aspect = 0;
    private static final String[] ASPECTS = {"Best fit", "Fill screen", "Stretch"};
    private long pendingSeek = -1;
    private int countdown;

    private final Runnable hideRun = this::hideOverlay;
    private final Runnable tick = new Runnable() { public void run() { if (player == null) return; onTick(); ui.postDelayed(this, 1000); } };
    private final Runnable commitSeek = () -> { if (pendingSeek >= 0 && player != null) { player.seekTo(pendingSeek); pendingSeek = -1; } };
    private final Runnable hudOff = () -> hud.setVisibility(View.GONE);
    private String zapDigits = "";
    private final Runnable zapRun = () -> {
        if (!zapDigits.isEmpty()) send("zap", "num", Integer.parseInt(zapDigits));   // only digits 0-9 go in, at most 4: always parses
        zapDigits = "";
    };
    private final Runnable countdownRun = new Runnable() { public void run() {
        countdown--;
        nextCount.setText("Next episode in " + countdown);
        if (countdown <= 0) playNextNow(); else ui.postDelayed(this, 1000);
    } };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        current = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        buildUi();
        player = PlayerHolder.take(this);   // built ahead of time while you browse, so the player screen opens with it ready
        playerView.setPlayer(player);
        player.addListener(listener = new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                if (loadAt > 0) { android.util.Log.i("M3U", "picture in " + (System.currentTimeMillis() - loadAt) + "ms"); loadAt = 0; }
                frameShown = true;
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
            @Override public void onIsPlayingChanged(boolean playing) {
                playBtn.setImageResource(playing ? androidx.media3.ui.R.drawable.exo_icon_pause : androidx.media3.ui.R.drawable.exo_icon_play);
                if (!playing && overlay.getVisibility() == View.VISIBLE) ui.removeCallbacks(hideRun);
            }
            @Override public void onVideoSizeChanged(VideoSize size) { videoW = size.width; videoH = size.height; if (state == S_PLAYING) updateBadge(videoW, videoH); }
            @Override public void onPlayerError(PlaybackException e) { onError(e); }
        });
        load(NativePlayerPlugin.item(getIntent().getStringExtra("item")));
        ui.post(tick);
    }

    // ---------- stream ----------

    public void load(JSObject next) {
        if (player == null) return;   // destroyed
        loadAt = System.currentTimeMillis();
        item = next;
        live = next.optBoolean("live", true);
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
        ui.removeCallbacks(countdownRun);
        nextPanel.setVisibility(View.GONE);
        if (channelList != null) channelList.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        frameShown = false;
        videoW = videoH = 0;
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
        if (burst) { player.stop(); ui.postDelayed(startRun, ZAP_SETTLE_MS); } else begin(token);
        if (live) { hideOverlay(); showBanner(); } else { showOverlay(); }   // live: the channel banner, as on a TV; films: the controls
        justShown = live;
    }

    /** Opens the stream for load number t, unless a newer load has replaced it. */
    private void begin(int t) {
        if (t != token || player == null) return;
        if (stopped) { resumePending = true; return; }   // in the background: connect when the screen comes back
        frameShown = false;
        applyTrackPrefs();
        JSObject next = item;
        Map<String, String> headers = new HashMap<>();
        JSONObject h = next.optJSONObject("headers");
        String agent = "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36";
        if (h != null) for (Iterator<String> it = h.keys(); it.hasNext(); ) {
            String k = it.next(), v = h.optString(k);
            if (k.equalsIgnoreCase("User-Agent")) agent = v; else headers.put(k, v);
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setUserAgent(agent).setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(8_000).setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(headers);
        String url = next.optString("url");
        MediaItem.Builder media = new MediaItem.Builder().setUri(url);
        if (url.toLowerCase(Locale.ROOT).contains(".m3u8")) media.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaSource(new DefaultMediaSourceFactory(http, PlayerHolder.extractors()).createMediaSource(media.build()));
        double start = next.optDouble("start", 0);
        long at = resumeAtMs >= 0 ? resumeAtMs : start > 1 ? (long) (start * 1000) : 0;
        if (!live && at > 0) player.seekTo(at);
        boolean paused = !live && resumePaused;
        resumeAtMs = -1; resumePaused = false;
        player.prepare();
        if (paused) player.pause(); else player.play();
    }

    /** UI follows the state: the spinner only once a wait passes 400ms (fast zaps never flash it); the badge only once there's a picture. */
    private void setState(int s) {
        state = s;
        ui.removeCallbacks(spinOn);
        if (s == S_PREPARING || s == S_BUFFERING) { if (spinner.getVisibility() != View.VISIBLE) ui.postDelayed(spinOn, SPINNER_DELAY_MS); }
        else spinner.setVisibility(View.GONE);
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
            player.stop();   // drop the dead connection now, so the retry isn't refused for using a second one
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

    /** The channel banner (TiviMate-style): number, logo, name; what's on with times and progress; what's next; the clock. */
    private void showBanner() {
        String num = item.optString("num", "");
        infoNum.setText(num);
        infoNum.setVisibility(num.isEmpty() ? View.GONE : View.VISIBLE);
        infoName.setText(item.optString("title", ""));
        Logos.show(infoLogo, item.optString("art", ""), dp(120), dp(68));
        long s = item.optLong("nowStart", 0), e = item.optLong("nowEnd", 0), now = System.currentTimeMillis();
        String nowTitle = item.optString("nowTitle", "");
        infoNow.setText(nowTitle.isEmpty() ? item.optString("subtitle", "") : nowTitle);
        boolean timed = !nowTitle.isEmpty() && s > 0 && e > s;
        infoTime.setText(timed ? clockHm(s) + " – " + clockHm(e) : "");
        infoTime.setVisibility(timed ? View.VISIBLE : View.GONE);
        if (timed) { infoProgress.setVisibility(View.VISIBLE); infoProgress.setProgress((int) (1000 * Math.max(0, Math.min(1, (now - s) / (double) (e - s))))); }
        else infoProgress.setVisibility(View.GONE);
        String next = item.optString("nextTitle", "");
        // The next programme starts when this one ends, so its time is nowEnd.
        infoNext.setText(next.isEmpty() ? "" : "Next" + (e > 0 ? "  " + clockHm(e) : "") + "   " + next);
        infoNext.setVisibility(next.isEmpty() ? View.GONE : View.VISIBLE);
        infoClock.setText(clockHm(now));
        infoBar.setVisibility(View.VISIBLE);
        ui.removeCallbacks(infoOff); ui.postDelayed(infoOff, 4500);
    }
    private static String clockHm(long ms) { return new java.text.SimpleDateFormat("HH:mm", Locale.UK).format(new java.util.Date(ms)); }

    private JSArray list() { return NativePlayerPlugin.array(item.opt("list")); }

    private void onError(PlaybackException e) {
        android.util.Log.w("M3U", "playback error " + e.getErrorCodeName());   // the code name only: messages can carry the stream URL
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
        setState(S_FAILED);
        ui.removeCallbacksAndMessages(RETRY);
        player.stop();   // let go of the provider's one connection
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
        if (!live && item.optBoolean("hasNext") && "Episodes".equals(item.optString("listTitle"))) startCountdown();
        else showOverlay(true);
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
        long t = Math.max(0, player.getCurrentPosition()), d = Math.max(0, player.getDuration());
        if (!live && d > 0) {
            if (pendingSeek < 0 && !seek.isPressed()) { seek.setMax((int) (d / 1000)); seek.setProgress((int) (t / 1000)); elapsed.setText(clock(t / 1000)); }
            total.setText(clock(d / 1000));
        }
        if (++ticks % 5 == 0 && !live && d > 0 && player.isPlaying()) event("progress", t / 1000.0, d / 1000.0);
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
        send("step", "dir", dir);
    }

    private void updateBadge(int w, int h) {
        if (w <= 0 || h <= 0) return;
        int big = Math.max(w, h), small = Math.min(w, h);
        badge.setText(big >= 3200 || small >= 1800 ? "4K" : big >= 1200 || small >= 700 ? "HD" : "SD");
        badge.setVisibility(View.VISIBLE);
    }

    // ---------- remote ----------

    /** OK on any remote: the D-pad centre, a keyboard Enter, or a numpad Enter. Every OK check goes through here. */
    private static boolean isOk(int k) { return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER; }

    private boolean okHeld = false;
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (noticeAction != null && isOk(k)) {
            if (e.getAction() == KeyEvent.ACTION_UP) { Runnable r = noticeAction; noticeOff.run(); ui.removeCallbacks(noticeOff); r.run(); }
            return true;
        }
        if (noticeAction != null && k == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_DOWN) { noticeOff.run(); ui.removeCallbacks(noticeOff); return true; }
        if (e.getAction() == KeyEvent.ACTION_UP && live && isOk(k)
            && errorPanel.getVisibility() != View.VISIBLE && overlay.getVisibility() != View.VISIBLE && channelList.getVisibility() != View.VISIBLE && nextPanel.getVisibility() != View.VISIBLE) {
            if (okHeld) { okHeld = false; return true; }
            if (list().length() > 1) openChannels(); else showOverlay();
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
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: seekBy(30_000); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: seekBy(-30_000); return true;
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
        if (nextPanel.getVisibility() == View.VISIBLE && k == KeyEvent.KEYCODE_BACK) { cancelNext(); return true; }
        if (channelList.getVisibility() == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_RIGHT) { closeChannels(); return true; }
            return super.dispatchKeyEvent(e);   // up/down move through the list, OK switches (see openChannels)
        }
        if (nextPanel.getVisibility() == View.VISIBLE) return super.dispatchKeyEvent(e);
        // Live TV, like a TV: up/down zap whatever is on screen; OK = channel list; hold OK = the controls; left/right = banner.
        if (live && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) && !(shown && !justShown && overlay.findFocus() != null && overlay.findFocus() != playBtn)) {
            if (e.getRepeatCount() == 0 || e.getRepeatCount() % 3 == 0) step(k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);   // held: one channel per ~3 repeats
            return true;
        }
        if (live && isOk(k) && e.getRepeatCount() == 1) { showOverlay(); okHeld = true; return true; }
        if (live && isOk(k) && e.getRepeatCount() > 1) return true;
        if (!shown) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (live) return true;   // acted on at key-up (openChannels), unless it turns into a hold
                    showOverlay(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (live) { showBanner(); return true; }
                    seekBy((k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) * (e.getRepeatCount() > 3 ? 30_000 : 10_000)); return true;
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                    showOverlay(); return true;
                case KeyEvent.KEYCODE_BACK:
                    if (infoBar.getVisibility() == View.VISIBLE) { infoBar.setVisibility(View.GONE); return true; }
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
    private void hideOverlay() {
        overlay.setVisibility(View.GONE); ui.removeCallbacks(hideRun);
        if (playerView.getSubtitleView() != null) playerView.getSubtitleView().setBottomPaddingFraction(0.08f);
    }

    // ---------- next episode ----------

    private void startCountdown() {
        int i = item.optInt("current") - item.optInt("listStart") + 1;
        JSArray l = list();
        String name = "Next episode";
        JSONObject row = i >= 0 && i < l.length() ? l.optJSONObject(i) : null;   // a missing row just keeps the generic name
        if (row != null) name = row.optString("t", name);
        nextName.setText(name);
        countdown = 10;
        nextCount.setText("Next episode in 10");
        hideOverlay();
        nextPanel.setVisibility(View.VISIBLE);
        playNow.requestFocus();
        ui.removeCallbacks(countdownRun);
        ui.postDelayed(countdownRun, 1000);
    }
    private void playNextNow() { ui.removeCallbacks(countdownRun); nextPanel.setVisibility(View.GONE); step(1); }
    private void cancelNext() { ui.removeCallbacks(countdownRun); nextPanel.setVisibility(View.GONE); showOverlay(true); }

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
        new AlertDialog.Builder(this, R.style.TvDialog)
            .setTitle(heading)
            .setSingleChoiceItems(rows, checked, (d, w) -> { d.dismiss(); if (player != null) pick.on(w); })
            .setNegativeButton("Cancel", null).show();
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
        player.stop();
        resumePending = true;
    }

    @Override
    public void finish() {
        if (!finished && player != null) {
            finished = true;
            double d = player.getDuration() > 0 ? player.getDuration() / 1000.0 : 0;
            event("closed", Math.max(0, player.getCurrentPosition()) / 1000.0, d);
        }
        super.finish();
    }

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
            listener = null;
            playerView.setPlayer(null);
            PlayerHolder.giveBack(p);
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

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setShutterBackgroundColor(Color.BLACK);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

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
        retryBtn = new Button(this);
        retryBtn.setText("Try again");
        retryBtn.setAllCaps(false);
        retryBtn.setOnClickListener(v -> tryAgain());
        lastBtn = new Button(this);
        lastBtn.setText("Last channel");
        lastBtn.setAllCaps(false);
        lastBtn.setOnClickListener(v -> {
            errorPanel.setVisibility(View.GONE);
            send("last");
        });
        eb.addView(retryBtn);
        eb.addView(lastBtn);
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

        // "Next episode in 10"
        LinearLayout np = new LinearLayout(this);
        nextPanel = np;
        np.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pb = new GradientDrawable(); pb.setColor(0xF0181B22); pb.setCornerRadius(dp(16));
        np.setBackground(pb);
        np.setPadding(dp(22), dp(18), dp(22), dp(18));
        nextCount = text(15, 0xB3FFFFFF, false);
        nextName = text(20, Color.WHITE, true);
        np.addView(nextCount);
        np.addView(nextName);
        LinearLayout nb = new LinearLayout(this);
        nb.setPadding(0, dp(14), 0, 0);
        playNow = new Button(this);
        playNow.setText("Play now");
        playNow.setAllCaps(false);
        playNow.setOnClickListener(v -> playNextNow());
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(v -> cancelNext());
        nb.addView(playNow);
        nb.addView(cancel);
        np.addView(nb);
        np.setVisibility(View.GONE);
        FrameLayout.LayoutParams npl = new FrameLayout.LayoutParams(dp(360), -2, Gravity.BOTTOM | Gravity.END);
        npl.setMargins(0, 0, dp(48), dp(48));
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
        ib.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable ibBg = new GradientDrawable(); ibBg.setColor(0xE6181B22); ibBg.setCornerRadius(dp(12)); ibBg.setStroke(dp(1), 0x1FFFFFFF);
        ib.setBackground(ibBg);
        ib.setPadding(dp(24), dp(16), dp(24), dp(16));
        infoNum = text(34, Color.WHITE, true);
        infoNum.setMinWidth(dp(64));
        infoNum.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nump = new LinearLayout.LayoutParams(-2, -2); nump.setMargins(0, 0, dp(16), 0);
        ib.addView(infoNum, nump);
        infoLogo = new android.widget.ImageView(this);
        infoLogo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        GradientDrawable logoBg = new GradientDrawable(); logoBg.setColor(0xFF232733); logoBg.setCornerRadius(dp(10));
        infoLogo.setBackground(logoBg);
        infoLogo.setClipToOutline(true);
        infoLogo.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams lgp = new LinearLayout.LayoutParams(dp(120), dp(68)); lgp.setMargins(0, 0, dp(24), 0);
        ib.addView(infoLogo, lgp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        infoName = text(24, Color.WHITE, true);
        col.addView(infoName);
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
