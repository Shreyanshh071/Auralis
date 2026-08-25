package com.auralis.music.data.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 100% Unrestricted Mobile Web Audio Engine with Background Playback Support.
 * 
 * Features:
 * - Direct routing via first-party mobile web client (m.youtube.com).
 * - Full Background Playback: Uses Partial WakeLock and spoofed visibility state to prevent OS throttling.
 * - Hardware WebLayer integration at 0-alpha base.
 * - Live bidirectional JS bridge for time, duration, and state synchronisation.
 */
class YouTubeAudioEngine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var isPolling = false
    private var currentVideoId: String? = null
    private var onTrackCompletedCallback: (() -> Unit)? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (isPolling) {
                pollMediaState()
                mainHandler.postDelayed(this, 500)
            }
        }
    }

    private inner class WebAppInterface {
        @JavascriptInterface
        fun onStateChange(state: Int) {
            mainHandler.post {
                when (state) {
                    0 -> { // Ended
                        _isPlaying.value = false
                        _isBuffering.value = false
                        onTrackCompletedCallback?.invoke()
                    }
                    1 -> { // Playing
                        _isPlaying.value = true
                        _isBuffering.value = false
                        acquireWakeLock()
                    }
                    2 -> { // Paused
                        _isPlaying.value = false
                        _isBuffering.value = false
                        releaseWakeLock()
                    }
                    3 -> { // Buffering
                        _isBuffering.value = true
                    }
                }
            }
        }

        @JavascriptInterface
        fun updateTime(currentSec: Double, durationSec: Double) {
            mainHandler.post {
                if (currentSec >= 0) {
                    _playbackPositionMs.value = (currentSec * 1000).toLong()
                }
                if (durationSec > 0 && !durationSec.isNaN()) {
                    _durationMs.value = (durationSec * 1000).toLong()
                }
            }
        }
    }

    init {
        mainHandler.post {
            getOrCreateWebView(context)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Auralis:BackgroundPlayback")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours timeout
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .build()
                audioManager?.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (_: Exception) {}
    }

    fun getOrCreateWebView(ctx: Context): View {
        if (webView == null) {
            val targetContext = if (ctx is android.app.Activity) ctx else ctx.applicationContext
            webView = WebView(targetContext).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                settings.apply {
                    javaScriptEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
                    allowFileAccess = true
                    allowContentAccess = true
                }

                try {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                } catch (_: Exception) {}

                addJavascriptInterface(WebAppInterface(), "AuralisBridge")

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request?.grant(request.resources)
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: return true
                        if (!msg.contains("generate_204") && !msg.contains("was preloaded using link preload")) {
                            Log.d("AuralisPlayback", "[WebView JS] $msg")
                        }
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d("AuralisPlayback", "[YouTube Engine Page Finished] $url")

                        // Injected media hook for background-capable mobile playback
                        view?.evaluateJavascript("""
                            (function() {
                                // 1. Spoof visibility state so YouTube never pauses when backgrounded
                                try {
                                    Object.defineProperty(document, 'hidden', { get: function() { return false; } });
                                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; } });
                                    Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; } });
                                } catch(e) {}

                                // 2. Block background visibility change events
                                window.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                                document.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                                window.addEventListener('pagehide', function(e) { e.stopImmediatePropagation(); }, true);
                                window.addEventListener('blur', function(e) { e.stopImmediatePropagation(); }, true);

                                // 3. Intercept pause calls to prevent YouTube auto-pausing in background
                                var originalPause = HTMLMediaElement.prototype.pause;
                                HTMLMediaElement.prototype.pause = function() {
                                    if (window._auralisUserPaused) {
                                        return originalPause.apply(this, arguments);
                                    }
                                    // Block automatic background pause
                                };

                                function autoPlay() {
                                    // Dismiss cookies / consent prompts
                                    var consent = document.querySelector('button[aria-label*="Agree"], button[aria-label*="Accept"], .yt-spec-button-shape-next--call-to-action');
                                    if (consent) consent.click();

                                    var v = document.querySelector('video');
                                    var playBtn = document.querySelector('.player-control-play-pause-icon, button.ytp-play-button, button[aria-label="Play"]');
                                    if (playBtn && v && v.paused && !window._auralisUserPaused) {
                                        playBtn.click();
                                    }
                                    if (v) {
                                        v.muted = false;
                                        v.volume = 1.0;
                                        if (v.paused && !window._auralisUserPaused) {
                                            v.play().catch(function(e) {});
                                        }

                                        if (!v._auralisAttached) {
                                            v._auralisAttached = true;
                                            v.ontimeupdate = function() {
                                                if (window.AuralisBridge) window.AuralisBridge.updateTime(v.currentTime, v.duration);
                                            };
                                            v.onplay = function() {
                                                if (window.AuralisBridge) window.AuralisBridge.onStateChange(1);
                                            };
                                            v.onpause = function() {
                                                if (window.AuralisBridge) window.AuralisBridge.onStateChange(2);
                                            };
                                            v.onwaiting = function() {
                                                if (window.AuralisBridge) window.AuralisBridge.onStateChange(3);
                                            };
                                            v.onended = function() {
                                                if (window.AuralisBridge) window.AuralisBridge.onStateChange(0);
                                            };
                                        }
                                    }
                                }
                                autoPlay();
                                setTimeout(autoPlay, 300);
                                setTimeout(autoPlay, 800);
                                setTimeout(autoPlay, 1500);
                            })();
                        """.trimIndent(), null)

                        startPolling()
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        Log.e("AuralisPlayback", "[WebView Error] ${error?.description} for ${request?.url}")
                    }
                }
            }
        }
        return webView!!
    }

    private fun startPolling() {
        if (!isPolling) {
            isPolling = true
            mainHandler.post(pollRunnable)
        }
    }

    private fun stopPolling() {
        isPolling = false
        mainHandler.removeCallbacks(pollRunnable)
    }

    private fun pollMediaState() {
        webView?.evaluateJavascript("""
            (function() {
                var v = document.querySelector('video');
                if (!v) return null;
                if (v.muted) v.muted = false;
                if (v.volume < 1.0) v.volume = 1.0;
                
                if (!v._auralisAttached) {
                    v._auralisAttached = true;
                    v.ontimeupdate = function() {
                        if (window.AuralisBridge) window.AuralisBridge.updateTime(v.currentTime, v.duration);
                    };
                    v.onplay = function() {
                        if (window.AuralisBridge) window.AuralisBridge.onStateChange(1);
                    };
                    v.onpause = function() {
                        if (window.AuralisBridge) window.AuralisBridge.onStateChange(2);
                    };
                    v.onwaiting = function() {
                        if (window.AuralisBridge) window.AuralisBridge.onStateChange(3);
                    };
                    v.onended = function() {
                        if (window.AuralisBridge) window.AuralisBridge.onStateChange(0);
                    };
                }

                return JSON.stringify({
                    currentTime: v.currentTime,
                    duration: v.duration,
                    paused: v.paused,
                    ended: v.ended,
                    readyState: v.readyState
                });
            })();
        """.trimIndent()) { result ->
            if (result != null && result != "null" && result.length > 2) {
                try {
                    val cleanJson = if (result.startsWith("\"") && result.endsWith("\"")) {
                        result.substring(1, result.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                    } else result

                    val obj = JSONObject(cleanJson)
                    val curr = obj.optDouble("currentTime", 0.0)
                    val dur = obj.optDouble("duration", 0.0)
                    val paused = obj.optBoolean("paused", true)
                    val ended = obj.optBoolean("ended", false)
                    val readyState = obj.optInt("readyState", 0)

                    _playbackPositionMs.value = (curr * 1000).toLong()
                    if (dur > 0 && !dur.isNaN()) {
                        _durationMs.value = (dur * 1000).toLong()
                    }
                    _isPlaying.value = !paused && !ended
                    _isBuffering.value = readyState < 3 && !paused && !ended

                    if (ended) {
                        _isPlaying.value = false
                        releaseWakeLock()
                        onTrackCompletedCallback?.invoke()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun setOnTrackCompletedCallback(callback: () -> Unit) {
        this.onTrackCompletedCallback = callback
    }

    fun loadVideo(videoId: String) {
        currentVideoId = videoId
        mainHandler.post {
            requestAudioFocus()
            acquireWakeLock()
            _playbackPositionMs.value = 0L
            _isBuffering.value = true
            _isPlaying.value = true
            getOrCreateWebView(context)
            val url = "https://m.youtube.com/watch?v=$videoId"
            Log.d("AuralisPlayback", "[YouTube Engine] Loading mobile web stream: $url")
            val extraHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
            )
            webView?.loadUrl(url, extraHeaders)
            startPolling()
        }
    }

    fun play() {
        mainHandler.post {
            requestAudioFocus()
            acquireWakeLock()
            webView?.evaluateJavascript("""
                (function() {
                    window._auralisUserPaused = false;
                    var v = document.querySelector('video');
                    if (v) {
                        v.muted = false;
                        v.volume = 1.0;
                        v.play().catch(function(e) {});
                    }
                })();
            """.trimIndent(), null)
            _isPlaying.value = true
        }
    }

    fun pause() {
        mainHandler.post {
            releaseWakeLock()
            webView?.evaluateJavascript("""
                (function() {
                    window._auralisUserPaused = true;
                    var v = document.querySelector('video');
                    if (v) {
                        HTMLMediaElement.prototype.pause.call(v);
                    }
                })();
            """.trimIndent(), null)
            _isPlaying.value = false
        }
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(positionMs: Long) {
        val seconds = positionMs.coerceAtLeast(0L) / 1000f
        mainHandler.post {
            webView?.evaluateJavascript("""
                (function() {
                    var v = document.querySelector('video');
                    if (v) {
                        v.currentTime = $seconds;
                        v.muted = false;
                        v.volume = 1.0;
                    }
                })();
            """.trimIndent(), null)
            _playbackPositionMs.value = positionMs
        }
    }

    fun release() {
        mainHandler.post {
            stopPolling()
            releaseWakeLock()
            pause()
            webView?.destroy()
            webView = null
        }
    }
}
