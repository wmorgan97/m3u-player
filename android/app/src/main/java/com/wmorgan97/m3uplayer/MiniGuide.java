package com.wmorgan97.m3uplayer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The mini guide (OK while watching live): a panel along the bottom, the video still playing above it.
 *
 *   BBC One HD                                                   [ Start over ]  [ All channels ]
 *   20:00 – 21:00   The News                                                         25 min left
 *   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━──────────────────────────────────────────────
 *   Next  21:00   Gardeners' World
 *   [ logo ] [ logo ] [▐ logo ▌] [ logo ] [ logo ] [ lo…
 *    names    …        focused     …
 *
 * The strip holds the channels of the list you're watching (left/right; the one playing is marked in blue). The details
 * above always describe the focused tile, so you can read what's on before you switch. OK switches; up reaches the
 * buttons (Start over when the channel keeps an archive; All channels = the full list); down or Back closes.
 * Focus is drawn, not Android focus: a solid white tile with dark text, slightly larger, as everywhere in the player.
 */
final class MiniGuide extends LinearLayout {
    interface Host {
        void pick(int index);          // index within the rows
        void startOver(int index);
        void allChannels();
        void closed();
    }

    private static final int SLOTS = 7;            // tiles built; ~5.5 fit, the cut-off one says "there's more"
    private static final int TILE_W = 144, TILE_GAP = 12;
    private final Host host;
    private JSONArray rows = new JSONArray();
    private int playing, sel, first;
    private int pill = -1;                          // -1: in the strip; else the button focused
    private final TextView name, num, time, nowTitle, left, next;
    private final ProgressBar progress;
    private final TextView startBtn, allBtn;
    private final LinearLayout strip;
    private View fadeL, fadeR;

    /** A fade as tall as the strip: it takes no height of its own (a plain View would ask for the whole screen). */
    private static final class Fade extends View {
        Fade(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getMode(h) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(h) : 0);
        }
    }
    private final Tile[] tiles = new Tile[SLOTS];

    private final class Tile {
        final LinearLayout root; final ImageView logo; final TextView title, sub, initials; final ProgressBar bar; final GradientDrawable bg, logoBg;
        int row = -1;
        Tile(Context c) {
            root = new LinearLayout(c); root.setOrientation(VERTICAL);
            root.setPadding(dp(9), dp(9), dp(9), dp(9));
            bg = new GradientDrawable(); bg.setCornerRadius(dp(10));
            root.setBackground(bg);
            // The logo sits in its own dark box, so white logos stay visible on the focused (white) tile.
            FrameLayout box = new FrameLayout(c);
            logoBg = new GradientDrawable(); logoBg.setColor(0xFF232733); logoBg.setCornerRadius(dp(7));
            box.setBackground(logoBg); box.setPadding(dp(8), dp(5), dp(8), dp(5));
            // No logo (or one that won't load): the channel's initials in its place, so every tile has the same shape.
            initials = text(c, 15, 0xFFB4BBC8, true); initials.setGravity(Gravity.CENTER);
            box.addView(initials, new FrameLayout.LayoutParams(-1, -1));
            logo = new ImageView(c) {
                @Override public void setVisibility(int v) { super.setVisibility(v); initials.setVisibility(v == VISIBLE && getTag() != null && !"".equals(getTag()) ? GONE : VISIBLE); }
            };
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(logo, new FrameLayout.LayoutParams(-1, -1));
            root.addView(box, new LayoutParams(-1, dp(34)));
            title = text(c, 14, Color.WHITE, true); title.setPadding(0, dp(7), 0, 0);
            sub = text(c, 13, 0xFF9AA3B5, false); sub.setPadding(0, dp(2), 0, 0);
            root.addView(title); root.addView(sub);
            bar = progressBar(c, 0x33FFFFFF, 0xFF5B93FF);
            LayoutParams bp = new LayoutParams(-1, dp(3)); bp.setMargins(0, dp(6), 0, 0);
            root.addView(bar, bp);
        }
    }

    MiniGuide(Context c, Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
        GradientDrawable panel = new GradientDrawable(); panel.setColor(PlayerActivity.PANEL); panel.setCornerRadius(dp(12)); panel.setStroke(dp(1), 0x1FFFFFFF);
        setBackground(panel);
        setPadding(dp(24), dp(16), dp(24), dp(14));
        setClipChildren(false); setClipToPadding(false);

        // Details of the focused channel, with its buttons on the right.
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.TOP);
        LinearLayout col = new LinearLayout(c); col.setOrientation(VERTICAL);
        LinearLayout l1 = new LinearLayout(c); l1.setGravity(Gravity.CENTER_VERTICAL);
        num = text(c, 18, 0xFF9AA3B5, true); num.setPadding(0, 0, dp(12), 0);
        name = text(c, 20, Color.WHITE, true);
        l1.addView(num); l1.addView(name, new LayoutParams(0, -2, 1));
        col.addView(l1);
        LinearLayout l2 = new LinearLayout(c); l2.setGravity(Gravity.CENTER_VERTICAL); l2.setPadding(0, dp(5), 0, 0);
        time = text(c, 15, 0xFF9AA3B5, false); time.setPadding(0, 0, dp(14), 0);
        nowTitle = text(c, 17, 0xFFD5DAE3, false);
        left = text(c, 14, 0xFF9AA3B5, false); left.setPadding(dp(16), 0, 0, 0);
        l2.addView(time); l2.addView(nowTitle, new LayoutParams(0, -2, 1)); l2.addView(left);
        col.addView(l2);
        progress = progressBar(c, 0x33FFFFFF, 0xFF5B93FF);
        LayoutParams pp = new LayoutParams(-1, dp(3)); pp.setMargins(0, dp(8), 0, 0);
        col.addView(progress, pp);
        next = text(c, 14, 0xFF9AA3B5, false); next.setPadding(0, dp(8), 0, 0);
        col.addView(next);
        head.addView(col, new LayoutParams(0, -2, 1));
        LinearLayout btns = new LinearLayout(c); btns.setClipChildren(false); btns.setClipToPadding(false);
        startBtn = button(c, "Start over"); allBtn = button(c, "All channels");
        LayoutParams b1 = new LayoutParams(-2, dp(40)); b1.setMargins(dp(32), 0, 0, 0);
        LayoutParams b2 = new LayoutParams(-2, dp(40)); b2.setMargins(dp(10), 0, 0, 0);
        btns.addView(startBtn, b1); btns.addView(allBtn, b2);
        head.addView(btns);
        head.setClipChildren(false); head.setClipToPadding(false);
        addView(head);

        // The strip: a fixed set of tiles re-filled as you move, sliding one place when the window shifts.
        FrameLayout clip = new FrameLayout(c);
        clip.setClipChildren(true);
        clip.setPadding(0, dp(5), 0, dp(5));   // room for the focused tile's 5% growth
        strip = new LinearLayout(c);
        strip.setClipChildren(false); strip.setClipToPadding(false);
        strip.setPadding(dp(4), 0, 0, 0);
        for (int i = 0; i < SLOTS; i++) {
            tiles[i] = new Tile(c);
            LayoutParams tl = new LayoutParams(dp(TILE_W), -2); tl.setMargins(i == 0 ? 0 : dp(TILE_GAP), 0, 0, 0);
            strip.addView(tiles[i].root, tl);
        }
        clip.addView(strip, new FrameLayout.LayoutParams(-2, -2));
        // Soft fades at the strip's ends (where more channels lie that way): it reads as a row that scrolls, not a cut.
        fadeL = new Fade(c); fadeR = new Fade(c);
        fadeL.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{PlayerActivity.PANEL, PlayerActivity.PANEL & 0x00FFFFFF}));
        fadeR.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, new int[]{PlayerActivity.PANEL, PlayerActivity.PANEL & 0x00FFFFFF}));
        clip.addView(fadeL, new FrameLayout.LayoutParams(dp(56), -1, Gravity.START));
        clip.addView(fadeR, new FrameLayout.LayoutParams(dp(56), -1, Gravity.END));
        LayoutParams cl = new LayoutParams(-1, -2); cl.setMargins(-dp(4), dp(8), -dp(4), 0);
        addView(clip, cl);
        setVisibility(GONE);
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Opens on the channel playing ([playing] = its row). */
    void open(JSONArray rows, int playing) {
        this.rows = rows; this.playing = playing;
        sel = Math.max(0, Math.min(rows.length() - 1, playing));
        first = clampFirst(sel - 2);
        pill = -1;
        bind(0);
        setAlpha(0f); setTranslationY(dp(16));
        setVisibility(VISIBLE);
        animate().alpha(1f).translationY(0).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
    }

    /** New guide data (the programme changed while it was open): same place, fresh text. */
    void refresh(JSONArray rows, int playing) {
        if (!isOpen() || rows.length() != this.rows.length()) return;
        this.rows = rows; this.playing = playing;
        bind(0);
    }

    void close() {
        if (!isOpen()) return;
        animate().cancel();
        animate().alpha(0f).translationY(dp(10)).setDuration(120).withEndAction(() -> setVisibility(GONE)).start();
        host.closed();
    }

    /** Remote keys while open (down events). Everything is consumed: nothing leaks to the player underneath. */
    boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (k == KeyEvent.KEYCODE_BACK) { close(); return true; }
        if (pill >= 0) {
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) { pill = -1; bindPills(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int want = k == KeyEvent.KEYCODE_DPAD_LEFT ? 0 : 1;
                if (want == 0 && startBtn.getVisibility() != VISIBLE) want = 1;
                pill = want; bindPills(); return true;
            }
            if (ok && e.getRepeatCount() == 0) { if (pill == 0) host.startOver(sel); else host.allChannels(); return true; }
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1); return true;
            case KeyEvent.KEYCODE_DPAD_UP: pill = startBtn.getVisibility() == VISIBLE ? 0 : 1; bindPills(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: close(); return true;
        }
        if (ok && e.getRepeatCount() == 0) { host.pick(sel); return true; }
        return true;
    }

    private void move(int d) {
        int n = rows.length();
        if (n == 0) return;
        int s = sel + d;
        if (s < 0 || s >= n) return;   // the ends stop (a wrap would jump the whole strip)
        sel = s;
        // Keep one tile of look-ahead on each side: the window slides when the focus reaches its second-last tile.
        int f = first;
        if (sel >= first + visible() - 1) f = sel - visible() + 2;
        if (sel <= first) f = sel - 1;
        f = clampFirst(f);
        int shift = f - first;
        first = f;
        bind(shift);
    }

    private int visible() { return 5; }
    private int clampFirst(int f) { return Math.max(0, Math.min(f, Math.max(0, rows.length() - visible()))); }

    private void bind(int shift) {
        JSONObject r = rows.optJSONObject(sel);
        if (r != null) bindHead(r);
        for (int i = 0; i < SLOTS; i++) bindTile(tiles[i], first + i);
        fadeL.setVisibility(first > 0 ? VISIBLE : INVISIBLE);
        fadeR.setVisibility(rows.length() > first + visible() ? VISIBLE : INVISIBLE);
        if (shift != 0) {
            strip.animate().cancel();
            strip.setTranslationX(shift * dp(TILE_W + TILE_GAP));
            strip.animate().translationX(0).setDuration(140).setInterpolator(new DecelerateInterpolator()).start();
        }
        bindPills();
    }

    private void bindHead(JSONObject r) {
        String n = r.optString("n", "");
        num.setText(n); num.setVisibility(n.isEmpty() ? GONE : VISIBLE);
        name.setText(r.optString("t"));
        long s = r.optLong("ps"), e = r.optLong("pe"), now = System.currentTimeMillis();
        String t = r.optString("nt", "");
        boolean timed = !t.isEmpty() && s > 0 && e > s;
        nowTitle.setText(!t.isEmpty() ? t : "No guide information");
        nowTitle.setTextColor(!t.isEmpty() ? 0xFFD5DAE3 : 0xFF9AA3B5);
        time.setText(timed ? hm(s) + " – " + hm(e) : "");
        time.setVisibility(timed ? VISIBLE : GONE);
        long mins = timed ? Math.max(1, (e - now + 59_999) / 60_000) : 0;
        left.setText(timed ? (mins >= 60 ? (mins / 60) + " h " + (mins % 60) + " min left" : mins + " min left") : "");
        left.setVisibility(timed ? VISIBLE : GONE);
        progress.setVisibility(timed ? VISIBLE : INVISIBLE);
        if (timed) progress.setProgress((int) (1000 * Math.max(0, Math.min(1, (now - s) / (double) (e - s)))));
        String nx = r.optString("nx", "");
        next.setText(nx.isEmpty() ? "" : "Next" + (e > 0 ? "  " + hm(e) : "") + "   " + nx);
        next.setVisibility(nx.isEmpty() ? INVISIBLE : VISIBLE);   // invisible, not gone: the panel keeps its height
        startBtn.setVisibility(r.optBoolean("cu") ? VISIBLE : GONE);
        if (pill == 0 && startBtn.getVisibility() != VISIBLE) pill = 1;
    }

    private void bindTile(Tile t, int i) {
        JSONObject r = i < rows.length() ? rows.optJSONObject(i) : null;
        t.root.setVisibility(r == null ? INVISIBLE : VISIBLE);
        t.row = r == null ? -1 : i;
        if (r == null) return;
        boolean focused = i == sel && pill < 0, isPlaying = i == playing;
        String n = r.optString("n", "");
        t.title.setText(n.isEmpty() ? r.optString("t") : n + "   " + r.optString("t"));
        String nt = r.optString("nt", "");
        t.sub.setText(nt.isEmpty() ? r.optString("s", "") : nt);
        long s = r.optLong("ps"), e = r.optLong("pe"), now = System.currentTimeMillis();
        boolean timed = !nt.isEmpty() && s > 0 && e > s;
        t.bar.setVisibility(timed ? VISIBLE : INVISIBLE);
        if (timed) t.bar.setProgress((int) (1000 * Math.max(0, Math.min(1, (now - s) / (double) (e - s)))));
        // Focused: solid white, dark text, a touch larger. Others: dark glass with a 2dp outline; the channel on now in blue.
        t.bg.setColor(focused ? Color.WHITE : 0x8C0E1014);
        // Up on the buttons: the tile they act on keeps a white outline, so you still see which channel "Start over" means.
        boolean remembered = i == sel && pill >= 0;
        t.bg.setStroke(dp(2), focused || remembered ? Color.WHITE : isPlaying ? 0xCC5B93FF : 0x33FFFFFF);
        t.title.setTextColor(focused ? 0xFF0E1014 : isPlaying ? 0xFF8FB4FF : Color.WHITE);
        t.sub.setTextColor(focused ? 0xFF4A505C : 0xFF9AA3B5);
        tint(t.bar, focused ? 0x26000000 : 0x33FFFFFF);
        float sc = focused ? 1.06f : 1f;
        if (t.root.getScaleX() != sc) t.root.animate().scaleX(sc).scaleY(sc).setDuration(110).start();
        t.root.setElevation(focused ? dp(8) : 0);
        String logo = r.optString("a", "");
        Object tag = t.logo.getTag();
        if (!logo.equals(tag == null ? "" : tag)) {   // (a tile keeps its logo box even without a logo: the row stays even)
            t.initials.setText(initials(r.optString("t")));
            if (logo.isEmpty()) { t.logo.setTag(""); t.logo.setImageDrawable(null); t.logo.setVisibility(VISIBLE); }
            else PlayerActivity.Logos.show(t.logo, logo, dp(110), dp(26));   // (its visibility drives the initials)
        }
    }

    /** "Sky Sports Main Event" -> "SSM"; "BBC One HD" -> "BO" (HD/UHD/FHD/4K dropped); at most three letters. */
    static String initials(String name) {
        StringBuilder b = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            if (w.isEmpty() || w.matches("(?i)(hd|uhd|fhd|sd|4k|\\+1)")) continue;
            if (Character.isLetterOrDigit(w.charAt(0))) b.append(Character.toUpperCase(w.charAt(0)));
            if (b.length() == 3) break;
        }
        return b.toString();
    }

    private void bindPills() {
        style(startBtn, pill == 0);
        style(allBtn, pill == 1);
        for (int i = 0; i < SLOTS; i++) if (tiles[i].row >= 0) bindTile(tiles[i], tiles[i].row);
    }

    // ---------- small builders (the player's look: white focus, 2dp outline otherwise) ----------

    private TextView button(Context c, String label) {
        TextView b = text(c, 15, Color.WHITE, true);
        b.setText(label); b.setGravity(Gravity.CENTER); b.setPadding(dp(20), 0, dp(20), 0);
        style(b, false);
        return b;
    }
    private void style(TextView b, boolean on) {
        GradientDrawable d = new GradientDrawable(); d.setCornerRadius(dp(20));
        if (on) d.setColor(Color.WHITE); else { d.setColor(0x8C0E1014); d.setStroke(dp(2), 0x8CFFFFFF); }
        b.setBackground(d);
        b.setTextColor(on ? 0xFF0E1014 : Color.WHITE);
        float sc = on ? 1.05f : 1f;
        if (b.getScaleX() != sc) b.animate().scaleX(sc).scaleY(sc).setDuration(110).start();
    }

    private static void tint(ProgressBar p, int track) {
        Drawable d = p.getProgressDrawable();
        if (d instanceof LayerDrawable) { Drawable t = ((LayerDrawable) d).getDrawable(0); if (t instanceof GradientDrawable) ((GradientDrawable) t).setColor(track); }
    }

    private ProgressBar progressBar(Context c, int track, int fill) {
        ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(1000);
        GradientDrawable tr = new GradientDrawable(); tr.setColor(track); tr.setCornerRadius(dp(2));
        GradientDrawable fl = new GradientDrawable(); fl.setColor(fill); fl.setCornerRadius(dp(2));
        p.setProgressDrawable(new LayerDrawable(new Drawable[]{tr, new ClipDrawable(fl, Gravity.START, ClipDrawable.HORIZONTAL)}));
        return p;
    }

    private TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
    private static String hm(long ms) { return new java.text.SimpleDateFormat("HH:mm", Locale.UK).format(new java.util.Date(ms)); }
}
