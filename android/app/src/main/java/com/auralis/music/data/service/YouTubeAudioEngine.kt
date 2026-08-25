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
 * Ultra-High-Speed Pre-warmed YouTube HTML5 Audio Engine.
 * 
 * Features:
 * - Pre-warmed IFrame Engine: Zero page reloads on song changes. Track switches execute in < 200ms via `loadVideoById`.
 * - Request-ID Synchronization: Stale callbacks or time updates from skipped songs are discarded instantly.
 * - Native Background Audio: Partial WakeLock and hardware audio priority keep playback seamless when screen is locked.
 * - Automated Ad Annihilation: Injected CSS, JS bypass, and network filter strip all YouTube ads.
 */
class YouTubeAudioEngine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var isEngineReady = false
    private var pendingVideoId: String? = null
    private var pendingRequestId: Long = 0L
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

    private val playerHtml = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                * { margin:0; padding:0; overflow:hidden; background:#000; }
                html, body, #player { width:100%; height:100%; }
                .ad-showing, .ad-interrupting, .video-ads, .ytp-ad-module,
                .ytp-ad-overlay-container, #player-ads, .ytp-ad-text {
                    display: none !important;
                    visibility: hidden !important;
                }
            </style>
        </head>
        <body>
            <div id="player"></div>
            <script>
                var player = null;
                var isReady = false;
                var currentReq = 0;

                // 1. Spoof visibility state so YouTube never pauses when backgrounded
                try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; } });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; } });
                    Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; } });
                } catch(e) {}

                window.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                document.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                window.addEventListener('pagehide', function(e) { e.stopImmediatePropagation(); }, true);
                window.addEventListener('blur', function(e) { e.stopImmediatePropagation(); }, true);

                // 2. Load YouTube IFrame API
                var tag = document.createElement('script');
                tag.src = "https://www.youtube.com/iframe_api";
                var firstScriptTag = document.getElementsByTagName('script')[0];
                firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

                function onYouTubeIframeAPIReady() {
                    player = new YT.Player('player', {
                        height: '100%',
                        width: '100%',
                        playerVars: {
                            'autoplay': 1,
                            'playsinline': 1,
                            'controls': 0,
                            'disablekb': 1,
                            'fs': 0,
                            'rel': 0,
                            'modestbranding': 1,
                            'origin': 'https://www.youtube.com'
                        },
                        events: {
                            'onReady': onPlayerReady,
                            'onStateChange': onPlayerStateChange,
                            'onError': onPlayerError
                        }
                    });
                }

                function onPlayerReady(event) {
                    isReady = true;
                    if (window.AuralisBridge) {
                        window.AuralisBridge.onEngineReady();
                    }
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
                                v.muted = true;
                                v.playbackRate = 16.0;
                                if (!isNaN(v.duration) && v.duration > 0) {
                                    v.currentTime = v.duration + 1;
                                }
                            } catch(e) {}
                        } else {
                            if (v.muted) v.muted = false;
                            if (v.playbackRate !== 1.0) v.playbackRate = 1.0;
                        }
                    }
                }

                function loadVideo(videoId, reqId) {
                    currentReq = reqId;
                    window._auralisRequestId = reqId;
                    if (!isReady || !player) return;
                    try {
                        player.loadVideoById({
                            videoId: videoId,
                            suggestedQuality: 'small'
                        });
                        player.playVideo();
                    } catch(e) {}
                }

                function onPlayerStateChange(event) {
                    // 1 = PLAYING, 2 = PAUSED, 3 = BUFFERING, 0 = ENDED
                    var state = event.data;
                    annihilateAds();
                    if (window.AuralisBridge) {
                        window.AuralisBridge.onStateChange(state, currentReq);
                    }
                }

                function onPlayerError(event) {
                    if (window.AuralisBridge) {
                        window.AuralisBridge.onStateChange(2, currentReq);
                    }
                }

                function playAudio() {
                    if (player && player.playVideo) player.playVideo();
                }

                function pauseAudio() {
                    if (player && player.pauseVideo) player.pauseVideo();
                }

                function seekAudio(seconds) {
                    if (player && player.seekTo) player.seekTo(seconds, true);
                }

                setInterval(function() {
                    annihilateAds();
                    if (player && isReady && player.getCurrentTime) {
                        try {
                            var curr = player.getCurrentTime() || 0;
                            var dur = player.getDuration() || 0;
                            if (window.AuralisBridge) {
                                window.AuralisBridge.updateTime(curr, dur, currentReq);
                            }
                        } catch(e) {}
                    }
                }, 250);
            </script>
        </body>
        </html>
    """.trimIndent()

    private inner class WebAppInterface {
        @JavascriptInterface
        fun onEngineReady() {
            mainHandler.post {
                isEngineReady = true
                Log.d("AuralisPlayback", "[YouTube Engine] HTML5 IFrame Engine Pre-warmed & Ready")
                val pVid = pendingVideoId
                val pReq = pendingRequestId
                if (!pVid.isNullOrBlank()) {
                    pendingVideoId = null
                    loadVideo(pVid, pReq)
                }
            }
        }

        @JavascriptInterface
        fun onStateChange(state: Int, reqId: Long) {
            mainHandler.post {
                val activeReq = currentRequestId.get()
                if (reqId != activeReq && reqId != 0L) {
                    Log.d("AuralisPlayback", "[Stale onStateChange dropped] reqId=$reqId != activeReq=$activeReq")
                    return@post
                }

                when (state) {
                    0 -> { // Ended
                        _isPlaying.value = false
                        _isBuffering.value = false
                        releaseWakeLock()
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
        fun onStateChange(state: Int) {
            onStateChange(state, currentRequestId.get())
        }

        @JavascriptInterface
        fun updateTime(currentSec: Double, durationSec: Double, reqId: Long) {
            mainHandler.post {
                val activeReq = currentRequestId.get()
                if (reqId != activeReq && reqId != 0L) {
                    return@post
                }

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
            Log.d("AuralisPlayback", "[Pre-warming] Initializing YouTube IFrame Engine")
            webView?.loadDataWithBaseURL("https://www.youtube.com", playerHtml, "text/html", "UTF-8", null)
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
                        Log.e("AuralisPlayback", "[WebView Error] ${error?.description} for ${request?.url}")
                    }
                }
            }
        }
        return webView!!
    }

    fun setOnTrackCompletedCallback(callback: () -> Unit) {
        this.onTrackCompletedCallback = callback
    }

    /**
     * Loads a video with a monotonically increasing session request ID.
     * Instant playback switch via pre-warmed IFrame player in < 200ms without page reloads.
     */
    fun loadVideo(videoId: String, requestId: Long = currentRequestId.incrementAndGet()) {
        currentRequestId.set(requestId)
        currentVideoId = videoId

        mainHandler.post {
            requestAudioFocus()
            acquireWakeLock()
            _playbackPositionMs.value = 0L
            _isBuffering.value = true
            _isPlaying.value = true

            getOrCreateWebView(context)

            if (isEngineReady) {
                Log.d("AuralisPlayback", "[Instant Track Switch] loadVideo('$videoId', reqId=$requestId)")
                webView?.evaluateJavascript("loadVideo('$videoId', $requestId);", null)
            } else {
                pendingVideoId = videoId
                pendingRequestId = requestId
                Log.d("AuralisPlayback", "[Pre-warm Init] Loading YouTube IFrame Engine for '$videoId'")
                webView?.loadDataWithBaseURL("https://www.youtube.com", playerHtml, "text/html", "UTF-8", null)
            }
        }
    }

    fun play() {
        mainHandler.post {
            requestAudioFocus()
            acquireWakeLock()
            _isPlaying.value = true
            webView?.evaluateJavascript("playAudio();", null)
        }
    }

    fun pause() {
        mainHandler.post {
            _isPlaying.value = false
            releaseWakeLock()
            webView?.evaluateJavascript("pauseAudio();", null)
        }
    }

    fun seekTo(positionMs: Long) {
        mainHandler.post {
            _playbackPositionMs.value = positionMs
            val seconds = positionMs / 1000.0
            webView?.evaluateJavascript("seekAudio($seconds);", null)
        }
    }

    fun stop() {
        mainHandler.post {
            _isPlaying.value = false
            releaseWakeLock()
            webView?.evaluateJavascript("pauseAudio();", null)
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
