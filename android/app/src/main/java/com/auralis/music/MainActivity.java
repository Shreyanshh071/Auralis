package com.auralis.music;

import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Let the web player start/continue audio without a fresh user gesture for
        // every track, so programmatic play() calls (autoplay-next, and the
        // MediaSession lock-screen controls) are not blocked by the WebView.
        // Capacitor already sets this during bridge init; reasserting it here keeps
        // the intent explicit and independent of that default.
        WebView webView = getBridge() != null ? getBridge().getWebView() : null;
        if (webView != null) {
            WebSettings settings = webView.getSettings();
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
    }

    // Resume the WebView's timers when the activity leaves the foreground. Pausing
    // them would freeze the JS-driven playback clock and can stop media, so keeping
    // them running is what lets playback continue when the app is backgrounded or
    // the screen is locked. (resumeTimers() is global to all WebViews in the app.)
    @Override
    public void onPause() {
        super.onPause();
        WebView webView = getBridge() != null ? getBridge().getWebView() : null;
        if (webView != null) {
            webView.resumeTimers();
        }
    }
}
