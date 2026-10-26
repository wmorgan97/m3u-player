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
    static PlayerActivity current;

    private ExoPlayer player;
    private PlayerView playerView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private JSObject item = new JSObject();
    private boolean live, finished, retried;
    private int ticks;

    private View overlay, nextPanel;
    private android.widget.ListView channelList;   // live TV: TiviMate-style channel panel on OK
    private TextView title, subtitle, badge, liveTag, elapsed, total, hud, status, nextCount, nextName;
    private SeekBar seek;
    private ProgressBar spinner;
    private ImageButton playBtn, prevBtn, nextBtn, backBtn, fwdBtn, audioBtn, subsBtn, aspectBtn, infoBtn, listBtn;
    private Button playNow;
    private int aspect = 0;
    private static final String[] ASPECTS = {"Best fit", "Fill screen", "Stretch"};
    private long pendingSeek = -1;
    private int countdown;

    private final Runnable hideRun = this::hideOverlay;
    private final Runnable tick = new Runnable() { public void run() { onTick(); ui.postDelayed(this, 1000); } };
    private final Runnable commitSeek = () -> { if (pendingSeek >= 0 && player != null) { player.seekTo(pendingSeek); pendingSeek = -1; } };
    private final Runnable hudOff = () -> hud.setVisibility(View.GONE);
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
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                spinner.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY) { status.setVisibility(View.GONE); retried = false; }
                if (state == Player.STATE_ENDED) onEnded();
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                playBtn.setImageResource(playing ? androidx.media3.ui.R.drawable.exo_icon_pause : androidx.media3.ui.R.drawable.exo_icon_play);
                if (!playing && overlay.getVisibility() == View.VISIBLE) ui.removeCallbacks(hideRun);
            }
            @Override public void onVideoSizeChanged(VideoSize size) { updateBadge(size.width, size.height); }
            @Override public void onPlayerError(PlaybackException e) { onError(e); }
        });
        load(NativePlayerPlugin.item(getIntent().getStringExtra("item")));
        ui.post(tick);
    }

    // ---------- stream ----------

    void load(JSObject next) {
        item = next;
        live = next.optBoolean("live", true);
        finished = false;
        retried = false;
        ui.removeCallbacks(countdownRun);
        nextPanel.setVisibility(View.GONE);
        if (channelList != null) channelList.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
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

        Map<String, String> headers = new HashMap<>();
        JSONObject h = next.optJSONObject("headers");
        String agent = "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36";
        if (h != null) for (Iterator<String> it = h.keys(); it.hasNext(); ) {
            String k = it.next(), v = h.optString(k);
            if (k.equalsIgnoreCase("User-Agent")) agent = v; else headers.put(k, v);
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setUserAgent(agent).setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers);
        String url = next.optString("url");
        MediaItem.Builder media = new MediaItem.Builder().setUri(url);
        if (url.toLowerCase(Locale.ROOT).contains(".m3u8")) media.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(media.build()));
        double start = next.optDouble("start", 0);
        if (!live && start > 1) player.seekTo((long) (start * 1000));
        player.prepare();
        player.play();
        showOverlay();
        justShown = live;
    }

    private JSArray list() { return NativePlayerPlugin.array(item.opt("list")); }

    private void onError(PlaybackException e) {
        // A live stream that fell behind, or a one-off network drop: quietly reconnect once.
        if (!retried && (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW || (e.errorCode >= 2000 && e.errorCode < 3000))) {
            retried = true;
            ui.postDelayed(() -> { if (live) player.seekToDefaultPosition(); player.prepare(); player.play(); }, 1500);
            return;
        }
        status.setText("This stream couldn't be played");
        status.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.GONE);
        showOverlay(true);
        try { NativePlayerPlugin.emit(new JSONObject().put("type", "error").put("message", "This stream couldn't be played (" + e.getErrorCodeName() + ")")); } catch (Exception ignored) {}
    }

    private void onEnded() {
        double d = player.getDuration() > 0 ? player.getDuration() / 1000.0 : 0;
        event("progress", d, d);
        try { NativePlayerPlugin.emit(new JSONObject().put("type", "ended")); } catch (Exception ignored) {}
        if (!live && item.optBoolean("hasNext") && "Episodes".equals(item.optString("listTitle"))) startCountdown();
        else showOverlay(true);
    }

    private void onTick() {
        if (player == null) return;
        long t = Math.max(0, player.getCurrentPosition()), d = Math.max(0, player.getDuration());
        if (!live && d > 0) {
            if (pendingSeek < 0 && !seek.isPressed()) { seek.setMax((int) (d / 1000)); seek.setProgress((int) (t / 1000)); elapsed.setText(clock(t / 1000)); }
            total.setText(clock(d / 1000));
        }
        if (++ticks % 5 == 0 && !live && d > 0 && player.isPlaying()) event("progress", t / 1000.0, d / 1000.0);
    }

    private void event(String type, double t, double d) {
        try { NativePlayerPlugin.emit(new JSONObject().put("type", type).put("t", t).put("d", d)); } catch (Exception ignored) {}
    }

    private void step(int dir) {
        if ((dir > 0 && !item.optBoolean("hasNext")) || (dir < 0 && !item.optBoolean("hasPrev"))) return;
        try { NativePlayerPlugin.emit(new JSONObject().put("type", "step").put("dir", dir)); } catch (Exception ignored) {}
    }

    private void updateBadge(int w, int h) {
        if (w <= 0 || h <= 0) return;
        int big = Math.max(w, h), small = Math.min(w, h);
        badge.setText(big >= 3200 || small >= 1800 ? "4K" : big >= 1200 || small >= 700 ? "HD" : "SD");
        badge.setVisibility(View.VISIBLE);
    }

    // ---------- remote ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        boolean shown = overlay.getVisibility() == View.VISIBLE;
        if (shown) bumpHide();
        switch (k) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                togglePlay(); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_MEDIA_NEXT: step(1); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_MEDIA_PREVIOUS: step(-1); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: seekBy(30_000); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: seekBy(-30_000); return true;
        }
        if (nextPanel.getVisibility() == View.VISIBLE && k == KeyEvent.KEYCODE_BACK) { cancelNext(); return true; }
        if (channelList.getVisibility() == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_RIGHT) { closeChannels(); return true; }
            return super.dispatchKeyEvent(e);   // up/down move through the list, OK switches (see openChannels)
        }
        if (nextPanel.getVisibility() == View.VISIBLE) return super.dispatchKeyEvent(e);
        if (!shown) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (live && list().length() > 1) openChannels(); else showOverlay();   // live: OK = channel list, as in TiviMate
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (live) { showOverlay(); return true; }
                    seekBy((k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) * (e.getRepeatCount() > 3 ? 30_000 : 10_000)); return true;
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (live) step(k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1); else showOverlay();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    finish(); return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (k == KeyEvent.KEYCODE_BACK) { hideOverlay(); return true; }
        boolean onButton = getCurrentFocus() != null && getCurrentFocus() != playerView && overlay.findFocus() != null && !justShown;
        if (live && !onButton && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && list().length() > 1) { openChannels(); return true; }
        if (justShown && k != KeyEvent.KEYCODE_DPAD_CENTER && k != KeyEvent.KEYCODE_ENTER) justShown = false;   // the first arrow moves onto the buttons
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
        if (!was) playBtn.requestFocus();
        ui.removeCallbacks(hideRun);
        if (!keep) ui.postDelayed(hideRun, 5000);
    }
    private void bumpHide() {
        ui.removeCallbacks(hideRun);
        if (player != null && player.isPlaying()) ui.postDelayed(hideRun, 5000);
    }
    private void hideOverlay() { overlay.setVisibility(View.GONE); ui.removeCallbacks(hideRun); }

    // ---------- next episode ----------

    private void startCountdown() {
        int i = item.optInt("current") - item.optInt("listStart") + 1;
        JSArray l = list();
        String name = "Next episode";
        try { if (i >= 0 && i < l.length()) name = l.getJSONObject(i).optString("t", name); } catch (Exception ignored) {}
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
            picks.add(() -> player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()));
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
                picks.add(() -> player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(type, false).setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), index)).build()));
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
        final int cur = Math.max(0, item.optInt("current") - item.optInt("listStart"));
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < l.length(); i++) {
            JSONObject r = l.optJSONObject(i);
            String t = r == null ? "" : r.optString("t"), d = r == null ? "" : r.optString("s");
            rows.add((i == cur ? "▶  " : "") + (d.isEmpty() ? t : t + "\n" + d));
        }
        channelList.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                TextView v = (TextView) super.getView(pos, convert, parent);
                v.setTextColor(pos == cur ? 0xFF7FAAFF : Color.WHITE);   // the channel playing now, in blue
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                v.setMaxLines(2);
                v.setEllipsize(android.text.TextUtils.TruncateAt.END);
                v.setPadding(dp(18), dp(10), dp(18), dp(10));
                return v;
            }
        });
        hideOverlay();
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
            try { NativePlayerPlugin.emit(new JSONObject().put("type", "jump").put("index", item.optInt("listStart") + w)); } catch (Exception ignored) {}
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

    private interface Pick { void on(int which); }
    private void dialog(String heading, String[] rows, int checked, Pick pick) {
        new AlertDialog.Builder(this, R.style.TvDialog)
            .setTitle(heading)
            .setSingleChoiceItems(rows, checked, (d, w) -> { d.dismiss(); pick.on(w); })
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
    protected void onStop() {
        super.onStop();
        if (!isFinishing() && player != null) player.pause();   // Home button: don't keep a stream (and the provider's one connection) open
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
        ui.removeCallbacksAndMessages(null);
        if (!finished && player != null) {
            finished = true;
            double d = player.getDuration() > 0 ? player.getDuration() / 1000.0 : 0;
            event("closed", Math.max(0, player.getCurrentPosition()) / 1000.0, d);
        }
        if (player != null) { player.release(); player = null; }
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
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
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
        lp.setMargins(dp(6), 0, dp(6), 0);
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
        top.setPadding(dp(48), dp(32), dp(48), 0);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        badge = text(12, Color.WHITE, true);
        GradientDrawable outline = new GradientDrawable(); outline.setStroke(dp(1), 0xCCFFFFFF); outline.setCornerRadius(dp(3));
        badge.setBackground(outline);
        badge.setPadding(dp(5), dp(1), dp(5), dp(1));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2); bl.setMargins(0, 0, dp(10), 0);
        titleRow.addView(badge, bl);
        liveTag = text(13, Color.WHITE, true);
        liveTag.setText("●  LIVE");
        android.text.SpannableString ls = new android.text.SpannableString("●  LIVE");
        ls.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF4550), 0, 1, 0);
        liveTag.setText(ls);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2); ll.setMargins(0, 0, dp(12), 0);
        titleRow.addView(liveTag, ll);
        title = text(26, Color.WHITE, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(titleRow);
        subtitle = text(16, 0xCCFFFFFF, false);
        top.addView(subtitle);
        ov.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(48), 0, dp(48), dp(28));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        elapsed = text(15, Color.WHITE, false);
        total = text(15, 0xB3FFFFFF, false);
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
        buttons.setPadding(0, dp(10), 0, 0);
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
        for (View b : new View[]{prevBtn, backBtn, playBtn, fwdBtn, nextBtn}) buttons.addView(b);
        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        for (View b : new View[]{audioBtn, subsBtn, aspectBtn, infoBtn, listBtn}) buttons.addView(b);
        bottom.addView(buttons);
        ov.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        status = text(18, Color.WHITE, true);
        status.setVisibility(View.GONE);
        ov.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(ov, new FrameLayout.LayoutParams(-1, -1));

        hud = text(30, Color.WHITE, true);
        GradientDrawable hb = new GradientDrawable(); hb.setColor(0x99000000); hb.setCornerRadius(dp(14));
        hud.setBackground(hb);
        hud.setPadding(dp(22), dp(10), dp(22), dp(10));
        hud.setVisibility(View.GONE);
        root.addView(hud, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

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
            if (pos == item.optInt("current") - item.optInt("listStart")) return;
            try { NativePlayerPlugin.emit(new JSONObject().put("type", "jump").put("index", item.optInt("listStart") + pos)); } catch (Exception ignored) {}
        });
        root.addView(channelList, new FrameLayout.LayoutParams(dp(420), -1, Gravity.START));

        setContentView(root);
    }
}
