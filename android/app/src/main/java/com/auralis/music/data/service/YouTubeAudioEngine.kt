package com.auralis.music.data.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Universal Mobile YouTube Audio Engine for Auralis.
 * 
 * Bypasses embed restrictions (UMG, Sony Music, Vevo) by streaming directly from
 * m.youtube.com with continuous unmuted playback assertion and native audio focus.
 */
@SuppressLint("SetJavaScriptEnabled")
class YouTubeAudioEngine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var currentVideoId: String? = null
    private var isPolling = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private var onTrackCompletedCallback: (() -> Unit)? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (isPolling && webView != null) {
                pollMediaState()
                mainHandler.postDelayed(this, 250)
            }
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onStateChange(state: Int) {
            mainHandler.post {
                // 1 = PLAYING, 2 = PAUSED, 3 = BUFFERING, 0 = ENDED
                when (state) {
                    1 -> {
                        _isPlaying.value = true
                        _isBuffering.value = false
                        Log.d("AuralisPlayback", "[YouTube Engine State] PLAYING")
                        startPolling()
                    }
                    2 -> {
                        _isPlaying.value = false
                        _isBuffering.value = false
                        Log.d("AuralisPlayback", "[YouTube Engine State] PAUSED")
                    }
                    3 -> {
                        _isBuffering.value = true
                        Log.d("AuralisPlayback", "[YouTube Engine State] BUFFERING")
                    }
                    0 -> {
                        _isPlaying.value = false
                        _isBuffering.value = false
                        Log.d("AuralisPlayback", "[YouTube Engine State] ENDED")
                        onTrackCompletedCallback?.invoke()
                    }
                }
            }
        }

        @JavascriptInterface
        fun updateTime(current: Double, total: Double) {
            mainHandler.post {
                _playbackPositionMs.value = (current * 1000).toLong()
                if (total > 0 && !total.isNaN()) {
                    _durationMs.value = (total * 1000).toLong()
                }
            }
        }
    }

    init {
        mainHandler.post {
            getOrCreateWebView(context)
        }
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

                        // Injected media hook for full mobile playback
                        view?.evaluateJavascript("""
                            (function() {
                                function autoPlay() {
                                    // Dismiss cookies / app prompts
                                    var consent = document.querySelector('button[aria-label*="Agree"], button[aria-label*="Accept"], .yt-spec-button-shape-next--call-to-action');
                                    if (consent) consent.click();

                                    var v = document.querySelector('video');
                                    var playBtn = document.querySelector('.player-control-play-pause-icon, button.ytp-play-button, button[aria-label="Play"]');
                                    if (playBtn && v && v.paused) {
                                        playBtn.click();
                                    }
                                    if (v) {
                                        v.muted = false;
                                        v.volume = 1.0;
                                        if (v.paused) {
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
                if (v.paused) v.play().catch(function(e) {});
                
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
            webView?.evaluateJavascript("""
                (function() {
                    var v = document.querySelector('video');
                    if (v) {
                        v.muted = false;
                        v.volume = 1.0;
                        if (v.paused) v.play().catch(function(e) {});
                    }
                })();
            """.trimIndent(), null)
            _isPlaying.value = true
        }
    }

    fun pause() {
        mainHandler.post {
            webView?.evaluateJavascript("""
                (function() {
                    var v = document.querySelector('video');
                    if (v && !v.paused) v.pause();
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
            pause()
            webView?.destroy()
            webView = null
        }
    }
}
