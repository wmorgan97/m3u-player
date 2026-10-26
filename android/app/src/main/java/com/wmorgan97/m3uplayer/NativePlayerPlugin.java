package com.wmorgan97.m3uplayer;

import android.content.Intent;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONObject;

/**
 * The same "NativePlayer" the iPhone app has, so the web app's code is shared:
 * - vlc: open the full-screen TV player (ExoPlayer) on a stream, or swap the stream in place when it's already open
 *   (channel up/down, next episode, a pick from its list).
 * - close: close it.
 * Player events go back to window.__nativePlayer(event): progress {t, d}, closed {t, d}, ended, error {message},
 * step {dir}, jump {index}.
 */
@CapacitorPlugin(name = "NativePlayer")
public class NativePlayerPlugin extends Plugin {
    static NativePlayerPlugin instance;

    @Override
    public void load() { instance = this; }

    @PluginMethod
    public void vlc(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) { call.reject("Missing url"); return; }
        Intent i = new Intent(getContext(), PlayerActivity.class);
        i.putExtra("item", call.getData().toString());
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        getActivity().runOnUiThread(() -> {
            if (PlayerActivity.current != null) PlayerActivity.current.load(call.getData());
            else getActivity().startActivity(i);
            call.resolve();
        });
    }

    @PluginMethod
    public void close(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (PlayerActivity.current != null) PlayerActivity.current.finish();
            call.resolve();
        });
    }

    @PluginMethod
    public void play(PluginCall call) { vlc(call); }

    @PluginMethod
    public void share(PluginCall call) { call.reject("Sharing isn't available on the TV"); }

    static void emit(JSONObject event) {
        NativePlayerPlugin p = instance;
        if (p == null || p.getBridge() == null) return;
        String js = "window.__nativePlayer && window.__nativePlayer(" + event + ")";
        p.getBridge().executeOnMainThread(() -> p.getBridge().getWebView().evaluateJavascript(js, null));
    }

    static JSObject item(String json) {
        try { return new JSObject(json); } catch (Exception e) { return new JSObject(); }
    }

    static JSArray array(Object o) {
        try { return o instanceof JSArray ? (JSArray) o : new JSArray(String.valueOf(o)); } catch (Exception e) { return new JSArray(); }
    }
}
