package com.wmorgan97.m3uplayer;

import android.os.Bundle;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NativePlayerPlugin.class);   // the TV's own player (see PlayerActivity)
        super.onCreate(savedInstanceState);
        // Back on the TV remote: the page decides first (close a sheet, stop the player, go back a page);
        // only when it has nothing left to close does Back leave the app.
        // Opened from the TV app for one page (settings, the TV guide): go straight there.
        String hash = getIntent().getStringExtra("hash");
        if (hash != null && !hash.isEmpty()) new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
            getBridge().getWebView().evaluateJavascript("location.hash = '" + hash.replace("'", "") + "'", null), 1500);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                getBridge().getWebView().evaluateJavascript("window.__tvBack ? window.__tvBack() : false", result -> {
                    if (!"true".equals(result)) {
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                    }
                });
            }
        });
    }
}
