package com.wmorgan97.m3uplayer;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import java.util.ArrayList;
import java.util.List;

/**
 * The quality badges on the channel banner, read from what the stream actually carries (never from the channel's name):
 * resolution, HDR, frame rate, and the audio. Each appears only once the player knows it.
 */
final class StreamBadges {
    private StreamBadges() {}

    /** [w]x[h]: the decoded picture size (VideoSize), which is known before the format's own fields on some streams;
     *  [fpsMeasured]: the frame rate measured from the frames' timestamps, for streams that don't state it (0: not yet). */
    static List<String> of(@Nullable Format v, int w, int h, float fpsMeasured, @Nullable Format a) {
        List<String> out = new ArrayList<>();
        if (w <= 0 && v != null) { w = v.width; h = v.height; }
        String res = resolution(w, h);
        if (res != null) out.add(res);
        if (v != null) {
            String hdr = hdr(v);
            if (hdr != null) out.add(hdr);
            String fps = fps(v.frameRate > 0 ? v.frameRate : fpsMeasured);
            if (fps != null) out.add(fps);
        }
        if (a != null) { String au = audio(a); if (au != null) out.add(au); }
        return out;
    }

    /** 4K / 1440p / 1080p / 720p / SD, by the picture's larger side as well (a 1920x800 film is still 1080p). */
    @Nullable static String resolution(int w, int h) {
        if (w <= 0 || h <= 0) return null;
        int big = Math.max(w, h), small = Math.min(w, h);
        if (big >= 3200 || small >= 1800) return "4K";
        if (big >= 2400 || small >= 1300) return "1440p";
        if (big >= 1700 || small >= 1000) return "1080p";
        if (big >= 1200 || small >= 700) return "720p";
        return "SD";
    }

    @Nullable static String hdr(Format v) {
        String codecs = v.codecs == null ? "" : v.codecs;
        if (MimeTypes.VIDEO_DOLBY_VISION.equals(v.sampleMimeType) || codecs.startsWith("dvh") || codecs.startsWith("dvav") || codecs.startsWith("dav1")) return "Dolby Vision";
        if (v.colorInfo == null) return null;
        if (v.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG) return "HLG";
        if (v.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084) return v.colorInfo.hdrStaticInfo != null ? "HDR10" : "HDR";
        return null;
    }

    /** 25 / 50 / 60 fps (59.94 reads as 60, 23.976 as 24). Unknown or odd values: nothing. */
    @Nullable static String fps(float f) {
        if (f <= 1 || f > 300 || Float.isNaN(f)) return null;
        return Math.round(f) + " fps";
    }

    /** Atmos / DD+ 5.1 / DD 5.1 / TrueHD 7.1 / DTS 5.1 / Stereo / 5.1 / Mono. */
    @Nullable static String audio(Format a) {
        String layout = layout(a.channelCount);
        String m = a.sampleMimeType == null ? "" : a.sampleMimeType;
        String codec;
        switch (m) {
            case MimeTypes.AUDIO_E_AC3_JOC: return "Dolby Atmos";
            case MimeTypes.AUDIO_E_AC3: codec = "DD+"; break;
            case MimeTypes.AUDIO_AC3: codec = "DD"; break;
            case MimeTypes.AUDIO_TRUEHD: codec = "TrueHD"; break;
            case MimeTypes.AUDIO_AC4: codec = "AC-4"; break;
            case MimeTypes.AUDIO_DTS: case MimeTypes.AUDIO_DTS_HD: case MimeTypes.AUDIO_DTS_EXPRESS: codec = "DTS"; break;
            default: codec = null;
        }
        if (codec == null) return layout;          // AAC / MP2 / Opus: the layout says it all
        if (layout == null) return codec;
        return codec + " " + (layout.equals("Stereo") ? "2.0" : layout);   // "DD 2.0", as receivers show it
    }

    @Nullable private static String layout(int n) {
        switch (n) {
            case 1: return "Mono";
            case 2: return "Stereo";
            case 6: return "5.1";
            case 8: return "7.1";
            default: return n > 2 ? n + " ch" : null;
        }
    }
}
