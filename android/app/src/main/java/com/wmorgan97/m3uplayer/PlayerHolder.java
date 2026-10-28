package com.wmorgan97.m3uplayer;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;

/**
 * One ExoPlayer for the whole app, built while you browse and handed to the player screen when you press play:
 * the screen opens with a ready player instead of building one. Tuned for live TV: the picture starts after
 * ~0.7s of buffered stream (ExoPlayer's default waits for 2.5s), then the buffer grows behind playback.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerHolder {
    private static ExoPlayer spare;

    public static synchronized void warm(Context c) { if (spare == null) spare = build(c.getApplicationContext()); }

    static synchronized ExoPlayer take(Context c) {
        ExoPlayer p = spare != null ? spare : build(c.getApplicationContext());
        spare = null;
        return p;
    }

    static synchronized void giveBack(ExoPlayer p) {
        if (p == null || p == spare) return;   // handed back twice: it's already the spare (or gone), don't release it again
        try { p.stop(); p.clearMediaItems(); }
        catch (Exception e) {
            // A player in a bad state is no use as the spare: log it and release it rather than hand it to the next screen.
            android.util.Log.w("M3U", "player hand-back failed: " + e.getClass().getSimpleName());
            try { p.release(); } catch (Exception ignored) { /* already broken; nothing more to free */ }
            return;
        }
        if (spare == null) spare = p; else p.release();
    }

    public static ExoPlayer create(Context c) { return build(c.getApplicationContext()); }

    private static ExoPlayer build(Context c) {
        DefaultLoadControl load = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(12_000, 40_000, 700, 1500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false)
            .build();
        ExoPlayer p = new ExoPlayer.Builder(c, new DefaultRenderersFactory(c).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true))
            .setLoadControl(load)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .build();
        p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        return p;
    }

    /** MPEG-TS streams: read far less of the stream before deciding where it starts (the default reads ~110KB first). */
    public static DefaultExtractorsFactory extractors() {
        return new DefaultExtractorsFactory()
            .setTsExtractorTimestampSearchBytes(TsExtractor.TS_PACKET_SIZE * 60)
            .setTsExtractorFlags(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM);
    }
}
