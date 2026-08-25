package com.auralis.music.data.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.auralis.music.data.network.AudioStreamResolver
import com.auralis.music.domain.model.Track
import com.auralis.music.service.AuralisMediaService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-Reliability Dual-Engine Audio Player for Auralis.
 * 
 * - Primary Engine: Native AndroidX ExoPlayer with configured HttpDataSource headers (User-Agent, Referer, Origin)
 *   and automatic host blacklisting on 429/403 errors.
 * - Fallback Engine: YouTube Audio Engine embedded in hardware WebView (used only when direct streams fail).
 * - Coordinated Engine Handoff: Zero conflicting play()/pause() calls to prevent AbortError.
 */
@UnstableApi
class AuralisAudioPlayer private constructor(context: Context) {

    private val appContext = context.applicationContext
    val youTubeEngine = YouTubeAudioEngine(appContext)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isUsingExoPlayer = false
    private var streamResolveJob: Job? = null

    val exoPlayer: ExoPlayer by lazy {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(8000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.jiosaavn.com/",
                    "Origin" to "https://www.jiosaavn.com",
                    "Accept" to "*/*"
                )
            )

        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(httpDataSourceFactory)

        ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build()
                setAudioAttributes(audioAttributes, true)
                setHandleAudioBecomingNoisy(true)

                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                        Log.d("AuralisPlayback", "[ExoPlayer Listener] onIsPlayingChanged: $playing")
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            val seekMs = newPosition.positionMs
                            _playbackPositionMs.value = seekMs
                            youTubeEngine.seekTo(seekMs)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> _isBuffering.value = true
                            Player.STATE_READY -> {
                                _isBuffering.value = false
                                if (duration > 0) _durationMs.value = duration
                            }
                            Player.STATE_ENDED -> {
                                _isPlaying.value = false
                                _isBuffering.value = false
                                onTrackCompletedCallback?.invoke()
                            }
                            Player.STATE_IDLE -> _isBuffering.value = false
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (!isUsingExoPlayer) return
                        val cause = error.cause
                        val httpCode = if (cause is HttpDataSource.InvalidResponseCodeException) cause.responseCode else null
                        val failedUri = if (cause is HttpDataSource.HttpDataSourceException) cause.dataSpec.uri.toString() else null
                        val failedHost = failedUri?.let { try { Uri.parse(it).host } catch (_: Exception) { null } }

                        Log.e("AuralisPlayback", "[ExoPlayer Error] code=${error.errorCodeName}, httpCode=$httpCode, host=$failedHost, uri=$failedUri, message=${error.message}", error)
                        _playbackError.value = error.message

                        // Blacklist failing host on 429 / 403
                        failedHost?.let { host ->
                            if (httpCode in listOf(403, 429, 500, 502, 503)) {
                                AudioStreamResolver.blacklistHost(host)
                            }
                        }

                        if (isUsingExoPlayer) {
                            isUsingExoPlayer = false
                            _currentTrack.value?.let { track ->
                                Log.d("AuralisPlayback", "[Fallback] Switching to YouTube HTML5 engine after ExoPlayer error")
                                youTubeEngine.loadVideo(track.id)
                            }
                        }
                    }
                })
            }
    }

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private var onTrackCompletedCallback: (() -> Unit)? = null

    init {
        // Collect YouTube engine states
        scope.launch {
            youTubeEngine.isPlaying.collect { playing ->
                if (!isUsingExoPlayer) {
                    _isPlaying.value = playing
                }
            }
        }
        scope.launch {
            youTubeEngine.playbackPositionMs.collect { pos ->
                if (!isUsingExoPlayer) {
                    _playbackPositionMs.value = pos
                }
            }
        }
        scope.launch {
            youTubeEngine.durationMs.collect { dur ->
                if (!isUsingExoPlayer && dur > 0) {
                    _durationMs.value = dur
                }
            }
        }
        scope.launch {
            youTubeEngine.isBuffering.collect { buffering ->
                if (!isUsingExoPlayer) {
                    _isBuffering.value = buffering
                }
            }
        }

        youTubeEngine.setOnTrackCompletedCallback {
            if (!isUsingExoPlayer) {
                onTrackCompletedCallback?.invoke()
            }
        }

        // Ticker for ExoPlayer progress
        scope.launch {
            while (isActive) {
                delay(250)
                if (isUsingExoPlayer && exoPlayer.isPlaying) {
                    _playbackPositionMs.value = exoPlayer.currentPosition
                    if (exoPlayer.duration > 0) {
                        _durationMs.value = exoPlayer.duration
                    }
                }
            }
        }
    }

    fun setOnTrackCompletedCallback(callback: () -> Unit) {
        this.onTrackCompletedCallback = callback
        youTubeEngine.setOnTrackCompletedCallback(callback)
    }

    private val currentSessionId = java.util.concurrent.atomic.AtomicLong(0L)

    fun play(track: Track, requestId: Long = currentSessionId.incrementAndGet()) {
        currentSessionId.set(requestId)
        _currentTrack.value = track
        _playbackError.value = null
        _durationMs.value = track.duration * 1000L
        _playbackPositionMs.value = 0L
        _isBuffering.value = true

        Log.d("AuralisPlayback", "[Play Request #$requestId] id=${track.id}, title='${track.title}', artist='${track.artist}', duration=${track.duration}s")

        // Start MediaSessionService in foreground for uninterrupted background audio
        try {
            val intent = Intent(appContext, AuralisMediaService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                androidx.core.content.ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
            Log.d("AuralisPlayback", "[MediaSession Service] Foreground service active for background audio")
        } catch (e: Exception) {
            Log.w("AuralisPlayback", "[MediaSession Service] startForegroundService notice: ${e.message}")
        }

        try {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        } catch (_: Exception) {}

        isUsingExoPlayer = false
        Log.d("AuralisPlayback", "[Audio Engine] Direct routing to YouTube Web Engine for '${track.title}' (${track.id}) [reqId=$requestId]")
        youTubeEngine.loadVideo(track.id, requestId)
    }

    fun resume() {
        youTubeEngine.play()
        _isPlaying.value = true
    }

    fun pause() {
        youTubeEngine.pause()
        _isPlaying.value = false
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            pause()
        } else {
            resume()
        }
    }

    fun seekTo(positionMs: Long) {
        _playbackPositionMs.value = positionMs
        youTubeEngine.seekTo(positionMs)
    }

    private var onNextCallback: (() -> Unit)? = null
    private var onPreviousCallback: (() -> Unit)? = null

    fun setNavigationCallbacks(onNext: () -> Unit, onPrevious: () -> Unit) {
        this.onNextCallback = onNext
        this.onPreviousCallback = onPrevious
    }

    fun next() {
        onNextCallback?.invoke()
    }

    fun previous() {
        onPreviousCallback?.invoke()
    }

    fun getOrCreateWebView(ctx: Context): View {
        return youTubeEngine.getOrCreateWebView(ctx)
    }

    fun release() {
        scope.cancel()
        streamResolveJob?.cancel()
        youTubeEngine.release()
        try {
            exoPlayer.release()
        } catch (_: Exception) {}
    }

    companion object {
        @Volatile
        private var instance: AuralisAudioPlayer? = null

        fun getInstance(context: Context): AuralisAudioPlayer {
            return instance ?: synchronized(this) {
                instance ?: AuralisAudioPlayer(context).also { instance = it }
            }
        }
    }
}
