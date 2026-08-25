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
import java.util.concurrent.atomic.AtomicLong

/**
 * Production-Grade YouTube HTML5 Web Audio Engine.
 * 
 * Features:
 * - Direct mobile YouTube loading (`m.youtube.com/watch?v=...`) with full HTML5 <video> element integration.
 * - Comprehensive logging of readyState, networkState, currentSrc, and play() promise resolution.
 * - Exact `onplay` StateFlow synchronization (isPlaying=true only set when onplay/playing fires).
 * - Monotonic request-ID tracking: stale requests and async callbacks are discarded immediately.
 * - Background keepalive: Partial WakeLock + AudioFocus + visibility spoofing.
 * - 3-tier Ad Interception: network filter, CSS suppression, and JS fast-forwarding/skipping.
 */
class YouTubeAudioEngine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var currentVideoId: String? = null
    private var onTrackCompletedCallback: (() -> Unit)? = null

    // Monotonically increasing playback request/session ID
    private val currentRequestId = AtomicLong(0L)

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

    private inner class WebAppInterface {

        @JavascriptInterface
        fun logVideoState(reqId: Long, exists: Boolean, readyState: Int, networkState: Int, currentSrc: String, paused: Boolean, details: String) {
            Log.d("AuralisPlayback", "[Video State #$reqId] exists=$exists, readyState=$readyState, networkState=$networkState, paused=$paused, src=$currentSrc, msg=$details")
        }

        @JavascriptInterface
        fun logPlaySuccess(reqId: Long, readyState: Int, networkState: Int, currentSrc: String) {
            Log.d("AuralisPlayback", "[JS Play Success #$reqId] readyState=$readyState, networkState=$networkState, src=$currentSrc")
        }

        @JavascriptInterface
        fun logPlayError(reqId: Long, error: String, readyState: Int, networkState: Int, currentSrc: String) {
            Log.e("AuralisPlayback", "[JS Play Error #$reqId] err=$error, readyState=$readyState, networkState=$networkState, src=$currentSrc")
        }

        @JavascriptInterface
        fun onStateChange(state: Int, reqId: Long) {
            mainHandler.post {
                val activeReq = currentRequestId.get()
                if (reqId != activeReq && reqId != 0L) {
                    Log.d("AuralisPlayback", "[Stale onStateChange dropped] reqId=$reqId != activeReq=$activeReq (state=$state)")
                    return@post
                }

                Log.d("AuralisPlayback", "[onStateChange #$reqId] state=$state (1=PLAYING, 2=PAUSED, 3=BUFFERING, 0=ENDED)")
                when (state) {
                    0 -> { // Ended
                        _isPlaying.value = false
                        _isBuffering.value = false
                        releaseWakeLock()
                        onTrackCompletedCallback?.invoke()
                    }
                    1 -> { // Playing - ONLY set isPlaying = true upon verified onplay callback
                        _isPlaying.value = true
                        _isBuffering.value = false
                        acquireWakeLock()
                    }
                    2 -> { // Paused
                        _isPlaying.value = false
                        _isBuffering.value = false
                        releaseWakeLock()
                    }
                    3 -> { // Buffering / Waiting
                        _isBuffering.value = true
                    }
                }
            }
        }

        @JavascriptInterface
        fun onStateChange(state: Int) {
            onStateChange(state, currentRequestId.get())
        }

        @JavascriptInterface
        fun updateTime(currentSec: Double, durationSec: Double, reqId: Long) {
            mainHandler.post {
                val activeReq = currentRequestId.get()
                if (reqId != activeReq && reqId != 0L) return@post

                if (currentSec >= 0) {
                    _playbackPositionMs.value = (currentSec * 1000).toLong()
                }
                if (durationSec > 0 && !durationSec.isNaN()) {
                    _durationMs.value = (durationSec * 1000).toLong()
                }
            }
        }

        @JavascriptInterface
        fun updateTime(currentSec: Double, durationSec: Double) {
            updateTime(currentSec, durationSec, currentRequestId.get())
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
            val targetContext = ctx.applicationContext
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
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        Log.d("AuralisPlayback", "[WebView PageStarted] url=$url (reqId=${currentRequestId.get()})")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val activeReq = currentRequestId.get()
                        Log.d("AuralisPlayback", "[WebView PageFinished] url=$url (reqId=$activeReq)")
                        
                        // Inject playback controller script on finished page
                        injectPlaybackScript(activeReq)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString()?.lowercase() ?: return super.shouldInterceptRequest(view, request)
                        
                        // NEVER block actual song audio/video streams
                        if (url.contains("googlevideo.com/videoplayback")) {
                            return super.shouldInterceptRequest(view, request)
                        }

                        val isAd = url.contains("doubleclick.net") ||
                                url.contains("googleads") ||
                                url.contains("adservice.google") ||
                                url.contains("/pagead/") ||
                                url.contains("pagead2") ||
                                url.contains("youtube.com/api/stats/ads") ||
                                url.contains("youtube.com/pagead/") ||
                                url.contains("youtube.com/ptracking") ||
                                url.contains("googlesyndication.com") ||
                                url.contains("pubads.g.doubleclick.net")

                        if (isAd) {
                            return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true || request?.url?.toString()?.contains("youtube") == true) {
                            Log.e("AuralisPlayback", "[WebView Error] code=${error?.errorCode}, desc=${error?.description}, url=${request?.url}")
                        }
                    }

                    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request?.isForMainFrame == true || request?.url?.toString()?.contains("youtube") == true) {
                            Log.e("AuralisPlayback", "[WebView HTTP Error] status=${errorResponse?.statusCode}, url=${request?.url}")
                        }
                    }
                }
            }
        }
        return webView!!
    }

    private fun injectPlaybackScript(reqId: Long) {
        val js = """
            (function() {
                var reqId = $reqId;
                window._auralisRequestId = reqId;

                // 1. Spoof visibility state so YouTube never pauses when backgrounded
                try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                    Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
                } catch(e) {}

                window.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                document.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                window.addEventListener('pagehide', function(e) { e.stopImmediatePropagation(); }, true);
                window.addEventListener('blur', function(e) { e.stopImmediatePropagation(); }, true);

                // 2. Hide ads and overlay clutter with injected style
                if (!document.getElementById('auralis-css')) {
                    var style = document.createElement('style');
                    style.id = 'auralis-css';
                    style.textContent = `
                        .ad-showing, .ad-interrupting, .video-ads, .ytp-ad-module,
                        .ytp-ad-overlay-container, #player-ads, .ytp-ad-text,
                        ytm-promoted-sparkles-web-renderer, ytm-companion-ad-renderer {
                            display: none !important;
                            visibility: hidden !important;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(style);
                }

                function annihilateAds() {
                    try {
                        var skipButtons = document.querySelectorAll(
                            '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .videoAdUiSkipButton, ' +
                            '.ytp-skip-ad-button, button[class*="skip-button"], .ytp-ad-skip-button-slot button'
                        );
                        skipButtons.forEach(function(btn) { btn.click(); });
                    } catch(e) {}

                    var v = document.querySelector('video');
                    if (v) {
                        var isAd = document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-module, .ytp-ad-text');
                        if (isAd) {
                            try {
                                v.playbackRate = 16.0;
                                v.currentTime = v.duration || 100;
                            } catch(e) {}
                        }
                    }
                }

                var attempts = 0;
                var maxAttempts = 35; // 35 * 150ms = 5.25 seconds

                function tryStartMedia() {
                    attempts++;
                    annihilateAds();

                    var v = document.querySelector('video');
                    if (!v) {
                        if (attempts % 5 === 0 && window.AuralisBridge) {
                            window.AuralisBridge.logVideoState(reqId, false, 0, 0, "", true, "Video element not found (attempt " + attempts + ")");
                        }
                        if (attempts < maxAttempts) {
                            setTimeout(tryStartMedia, 150);
                        } else {
                            if (window.AuralisBridge) {
                                window.AuralisBridge.logVideoState(reqId, false, 0, 0, "", true, "Video element timeout after " + maxAttempts + " attempts");
                            }
                        }
                        return;
                    }

                    // Video element found!
                    v.muted = false;
                    v.volume = 1.0;

                    // Attach listeners once
                    if (!v._auralisAttached) {
                        v._auralisAttached = true;
                        v.addEventListener('play', function() {
                            if (window.AuralisBridge) window.AuralisBridge.onStateChange(1, window._auralisRequestId || reqId);
                        });
                        v.addEventListener('playing', function() {
                            if (window.AuralisBridge) window.AuralisBridge.onStateChange(1, window._auralisRequestId || reqId);
                        });
                        v.addEventListener('pause', function() {
                            if (window.AuralisBridge) window.AuralisBridge.onStateChange(2, window._auralisRequestId || reqId);
                        });
                        v.addEventListener('waiting', function() {
                            if (window.AuralisBridge) window.AuralisBridge.onStateChange(3, window._auralisRequestId || reqId);
                        });
                        v.addEventListener('ended', function() {
                            if (window.AuralisBridge) window.AuralisBridge.onStateChange(0, window._auralisRequestId || reqId);
                        });
                        v.addEventListener('timeupdate', function() {
                            if (window.AuralisBridge) window.AuralisBridge.updateTime(v.currentTime, v.duration, window._auralisRequestId || reqId);
                        });
                        v.addEventListener('error', function(e) {
                            var errDesc = v.error ? "code=" + v.error.code + " msg=" + v.error.message : "unknown error";
                            if (window.AuralisBridge) window.AuralisBridge.logPlayError(reqId, errDesc, v.readyState, v.networkState, v.currentSrc || "");
                        });
                    }

                    // Dismiss consent / popups if present
                    var dismissBtn = document.querySelector('button[aria-label*="Dismiss"], button[aria-label*="No thanks"], ytd-button-renderer#dismiss-button, .yt-spec-button-shape-next--tonal');
                    if (dismissBtn) try { dismissBtn.click(); } catch(e) {}

                    // Click YouTube large play button if present and video is paused
                    var playBtn = document.querySelector('.ytp-large-play-button, .ytp-play-button, button.player-control-play-pause');
                    if (playBtn && v.paused) {
                        try { playBtn.click(); } catch(e) {}
                    }

                    // Call video.play()
                    var playPromise = v.play();
                    if (playPromise !== undefined) {
                        playPromise.then(function() {
                            if (window.AuralisBridge) {
                                window.AuralisBridge.logPlaySuccess(reqId, v.readyState, v.networkState, v.currentSrc || "");
                            }
                        }).catch(function(err) {
                            var errMsg = err ? err.message : "play rejection";
                            if (window.AuralisBridge) {
                                window.AuralisBridge.logPlayError(reqId, errMsg, v.readyState, v.networkState, v.currentSrc || "");
                            }
                            // Autoplay fallback: unmute after play
                            v.muted = true;
                            v.play().then(function() {
                                v.muted = false;
                            }).catch(function() {});
                        });
                    } else {
                        if (window.AuralisBridge) {
                            window.AuralisBridge.logPlaySuccess(reqId, v.readyState, v.networkState, v.currentSrc || "");
                        }
                    }
                }

                tryStartMedia();

                // Ongoing polling for ads
                if (!window._auralisPollInterval) {
                    window._auralisPollInterval = setInterval(function() {
                        annihilateAds();
                    }, 500);
                }
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    fun setOnTrackCompletedCallback(callback: () -> Unit) {
        this.onTrackCompletedCallback = callback
    }

    /**
     * Loads a video with a monotonically increasing session request ID.
     * Starts background playback and logs progress end-to-end.
     */
    fun loadVideo(videoId: String, requestId: Long = currentRequestId.incrementAndGet()) {
        currentRequestId.set(requestId)
        currentVideoId = videoId

        mainHandler.post {
            requestAudioFocus()
            acquireWakeLock()
            _playbackPositionMs.value = 0L
            _isBuffering.value = true
            _isPlaying.value = false // Set isPlaying = true only upon verified onplay callback

            val web = getOrCreateWebView(context) as WebView
            Log.d("AuralisPlayback", "[Track Request #$requestId] Loading https://m.youtube.com/watch?v=$videoId")

            // Stop any existing audio immediately before navigating
            web.evaluateJavascript("var v = document.querySelector('video'); if (v) { v.pause(); v.currentTime = 0; }", null)

            // Direct mobile YouTube navigation
            web.loadUrl("https://m.youtube.com/watch?v=$videoId")
        }
    }

    fun play() {
        mainHandler.post {
            requestAudioFocus()
            acquireWakeLock()
            webView?.evaluateJavascript("""
                (function() {
                    var v = document.querySelector('video');
                    if (v) {
                        v.muted = false;
                        v.volume = 1.0;
                        v.play().catch(function(e) {
                            v.muted = true;
                            v.play().then(function() { v.muted = false; });
                        });
                    }
                })();
            """.trimIndent(), null)
        }
    }

    fun pause() {
        mainHandler.post {
            _isPlaying.value = false
            releaseWakeLock()
            webView?.evaluateJavascript("""
                (function() {
                    var v = document.querySelector('video');
                    if (v) v.pause();
                })();
            """.trimIndent(), null)
        }
    }

    fun seekTo(positionMs: Long) {
        mainHandler.post {
            _playbackPositionMs.value = positionMs
            val seconds = positionMs / 1000.0
            webView?.evaluateJavascript("""
                (function() {
                    var v = document.querySelector('video');
                    if (v) v.currentTime = $seconds;
                })();
            """.trimIndent(), null)
        }
    }

    fun stop() {
        mainHandler.post {
            _isPlaying.value = false
            releaseWakeLock()
            webView?.evaluateJavascript("""
                (function() {
                    var v = document.querySelector('video');
                    if (v) {
                        v.pause();
                        v.currentTime = 0;
                    }
                })();
            """.trimIndent(), null)
        }
    }

    fun release() {
        stop()
        mainHandler.post {
            try {
                webView?.destroy()
                webView = null
            } catch (_: Exception) {}
        }
    }
}
