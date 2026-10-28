package com.wmorgan97.m3uplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/**
 * Pause / rewind on live TV: the bar along the bottom (where the channel banner sits) while you're paused or behind live.
 *
 *   Paused   20:59                                                                  [ −4:12 ]
 *   20:41  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━●─────────────────────────────────  21:04
 *                                              OK  Play      ◀ ▶  10 s      Back  Live
 *
 * The line is everything that can be replayed: from the oldest moment kept (left) to live (right). Blue up to where you
 * are, a lighter grey ahead of you up to live. The pill says where you are: LIVE (with a red dot) or how far behind.
 */
final class ShiftBar extends LinearLayout {
    private final TextView state, at, pill, from, to, hint;
    private final Line line;

    ShiftBar(Context c) {
        super(c);
        setOrientation(VERTICAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(PlayerActivity.PANEL); bg.setCornerRadius(dp(12)); bg.setStroke(dp(1), 0x1FFFFFFF);
        setBackground(bg);
        setPadding(dp(24), dp(16), dp(24), dp(14));

        LinearLayout r1 = new LinearLayout(c); r1.setGravity(Gravity.CENTER_VERTICAL);
        state = text(c, 20, Color.WHITE, true);
        at = text(c, 17, 0xFF9AA3B5, false); at.setPadding(dp(14), 0, 0, 0);
        pill = text(c, 15, Color.WHITE, true);
        pill.setGravity(Gravity.CENTER); pill.setMinWidth(dp(76));
        pill.setPadding(dp(14), dp(5), dp(14), dp(5));
        r1.addView(state); r1.addView(at, new LayoutParams(0, -2, 1)); r1.addView(pill);
        addView(r1);

        LinearLayout r2 = new LinearLayout(c); r2.setGravity(Gravity.CENTER_VERTICAL); r2.setPadding(0, dp(12), 0, 0);
        from = text(c, 14, 0xFF9AA3B5, false); from.setPadding(0, 0, dp(14), 0);
        to = text(c, 14, 0xFF9AA3B5, false); to.setPadding(dp(14), 0, 0, 0);
        line = new Line(c);
        r2.addView(from); r2.addView(line, new LayoutParams(0, dp(16), 1)); r2.addView(to);
        addView(r2);

        hint = text(c, 13, 0xFF7D8596, false);
        hint.setGravity(Gravity.END); hint.setPadding(0, dp(8), 0, 0);
        addView(hint, new LayoutParams(-1, -2));
        setVisibility(GONE);
    }

    /**
     * [oldest] .. [live] are wall-clock times (ms) of what's kept; [pos] is the moment on screen. [behind] how far behind
     * live you are (0 = live). [paused] changes the wording; [canBack]: there is something to rewind into.
     */
    void set(String channel, boolean paused, long oldest, long pos, long live, long behind, boolean canBack, String archive) {
        boolean isLive = behind < 1500 && !paused;
        state.setText(paused ? "Paused" : isLive ? channel : "Behind live");   // at live the pill says LIVE; the label names the channel
        at.setText(isLive ? "" : hm(pos));
        if (isLive) {
            SpannableString s = new SpannableString("●  LIVE");
            s.setSpan(new ForegroundColorSpan(0xFFFF4550), 0, 1, 0);
            pill.setText(s);
            pill.setBackground(round(0x33FFFFFF, 0));
        } else {
            pill.setText("−" + clock(behind / 1000));
            pill.setBackground(round(0x8C0E1014, 0x8CFFFFFF));
        }
        from.setText(hm(oldest));
        to.setText(hm(live));
        float span = Math.max(1, live - oldest);
        line.set((pos - oldest) / span, canBack || !isLive);
        // Hints for the keys that do something here, quietly, in the order they sit on the remote.
        StringBuilder h = new StringBuilder();
        if (archive != null) h.append(archive).append("        ");   // at the start of the recording: further back = the archive
        h.append(paused ? "OK  Play" : "OK  Pause");
        if (archive == null && !isLive) h.append("        ◀ ▶  10 s");
        else if (archive == null && canBack) h.append("        ◀  Back 10 s");
        if (!isLive) h.append("        Back  Live");
        hint.setText(h);
    }

    /** The timeline: a thin rounded line, blue to the position, grey after, with a white knob. */
    private final class Line extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), done = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float f = 1; private boolean showKnob = true;
        Line(Context c) { super(c); track.setColor(0x4DFFFFFF); done.setColor(0xFF5B93FF); knob.setColor(Color.WHITE); }
        void set(float frac, boolean knobOn) { f = Math.max(0, Math.min(1, frac)); showKnob = knobOn; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float h = dp(4), cy = getHeight() / 2f, kr = dp(7), w = getWidth() - 2 * kr;
            float x0 = kr, x = kr + w * f;
            r.set(x0, cy - h / 2, x0 + w, cy + h / 2); c.drawRoundRect(r, h / 2, h / 2, track);
            r.set(x0, cy - h / 2, x, cy + h / 2); c.drawRoundRect(r, h / 2, h / 2, done);
            if (showKnob) c.drawCircle(x, cy, kr, knob);
        }
    }

    private GradientDrawable round(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(16));
        if (stroke != 0) d.setStroke(dp(2), stroke);
        return d;
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
    static String hm(long ms) { return new java.text.SimpleDateFormat("HH:mm", Locale.UK).format(new java.util.Date(ms)); }
    static String clock(long s) {
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
