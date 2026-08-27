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
import com.auralis.music.ui.components.getHighResArtworkUrl
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

    init {
        com.auralis.music.data.network.AudioStreamResolver.init(appContext)
    }

    private var isUsingExoPlayer = false
    private var streamResolveJob: Job? = null

    val exoPlayer: ExoPlayer by lazy {
        val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            com.auralis.music.data.network.NetworkClientProvider.okHttpClient
        )
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*"
                )
            )

        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(httpDataSourceFactory)

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 400,
                /* bufferForPlaybackAfterRebufferMs = */ 1_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
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
                        if (playing && isUsingExoPlayer) {
                            activeTimingTracker?.let { tracker ->
                                if (tracker.tFirstAudioMs == 0L) {
                                    tracker.tFirstAudioMs = System.currentTimeMillis()
                                    tracker.streamEngine = "Native ExoPlayer"
                                    tracker.logSummary()
                                }
                            }
                        }
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
                            Player.STATE_BUFFERING -> {
                                _isBuffering.value = true
                                activeTimingTracker?.let { tracker ->
                                    if (tracker.tBufferingMs == 0L) tracker.tBufferingMs = System.currentTimeMillis()
                                }
                            }
                            Player.STATE_READY -> {
                                _isBuffering.value = false
                                if (duration > 0) _durationMs.value = duration
                                activeTimingTracker?.let { tracker ->
                                    if (tracker.tReadyMs == 0L) tracker.tReadyMs = System.currentTimeMillis()
                                }
                            }
                            Player.STATE_ENDED -> {
                                _isPlaying.value = false
                                _isBuffering.value = false
                                if (isUsingExoPlayer) {
                                    try {
                                        exoPlayer.stop()
                                        exoPlayer.clearMediaItems()
                                    } catch (_: Exception) {}
                                    dispatchTrackCompleted()
                                }
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
                            val savedPos = _playbackPositionMs.value
                            isUsingExoPlayer = false
                            _currentTrack.value?.let { track ->
                                Log.d("AuralisPlayback", "[Fallback] Switching to YouTube HTML5 engine after ExoPlayer error (seek=${savedPos}ms)")
                                youTubeEngine.loadVideo(track.id, savedPos)
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

    private val onTrackCompletedListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val lastCompletedSessionId = java.util.concurrent.atomic.AtomicLong(-1L)

    private fun dispatchTrackCompleted(completedSessionId: Long = currentSessionId.get()) {
        if (completedSessionId != currentSessionId.get()) {
            Log.d("AuralisPlayback", "[Stale Track Completed dropped] completedSessionId=$completedSessionId vs currentSession=${currentSessionId.get()}")
            return
        }
        if (lastCompletedSessionId.getAndSet(completedSessionId) != completedSessionId) {
            Log.d("AuralisPlayback", "[Track Completed #$completedSessionId] Dispatching completion to ${onTrackCompletedListeners.size} listeners")
            for (listener in onTrackCompletedListeners) {
                try {
                    listener.invoke()
                } catch (e: Exception) {
                    Log.e("AuralisPlayback", "Error in onTrackCompletedListener: ${e.message}")
                }
            }
        }
    }

    init {
        // Collect YouTube engine states
        scope.launch {
            youTubeEngine.isPlaying.collect { playing ->
                if (!isUsingExoPlayer) {
                    _isPlaying.value = playing
                    if (playing) {
                        activeTimingTracker?.let { tracker ->
                            if (tracker.tFirstAudioMs == 0L) {
                                tracker.tFirstAudioMs = System.currentTimeMillis()
                                tracker.streamEngine = "YouTube Web Engine"
                                tracker.logSummary()
                            }
                        }
                    }
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
                dispatchTrackCompleted()
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
        onTrackCompletedListeners.clear()
        onTrackCompletedListeners.add(callback)
    }

    fun addOnTrackCompletedListener(listener: () -> Unit) {
        if (!onTrackCompletedListeners.contains(listener)) {
            onTrackCompletedListeners.add(listener)
        }
    }

    fun removeOnTrackCompletedListener(listener: () -> Unit) {
        onTrackCompletedListeners.remove(listener)
    }

    private val currentSessionId = java.util.concurrent.atomic.AtomicLong(0L)
    private var activeTimingTracker: PlaybackTimingTracker? = null

    data class PlaybackTimingTracker(
        val requestId: Long,
        val trackTitle: String,
        val t0TapMs: Long = System.currentTimeMillis(),
        var tResolveStartMs: Long = 0L,
        var tStreamResolvedMs: Long = 0L,
        var tMediaItemPreparedMs: Long = 0L,
        var tBufferingMs: Long = 0L,
        var tReadyMs: Long = 0L,
        var tFirstAudioMs: Long = 0L,
        var streamEngine: String = "Unknown"
    ) {
        fun logSummary() {
            val totalMs = if (tFirstAudioMs > 0) tFirstAudioMs - t0TapMs else (System.currentTimeMillis() - t0TapMs)
            val resolveMs = if (tStreamResolvedMs > 0) tStreamResolvedMs - t0TapMs else -1
            val prepMs = if (tMediaItemPreparedMs > 0) tMediaItemPreparedMs - t0TapMs else -1
            val bufferMs = if (tBufferingMs > 0) tBufferingMs - t0TapMs else -1
            val readyMs = if (tReadyMs > 0) tReadyMs - t0TapMs else -1
            val audioMs = if (tFirstAudioMs > 0) tFirstAudioMs - t0TapMs else -1

            Log.i("AuralisPlaybackTiming", """
                ==================== PLAYBACK TIMING [#$requestId] ====================
                Track:             $trackTitle
                Engine:            $streamEngine
                Tap:               0ms
                Track Resolution:  ${if (resolveMs >= 0) "${resolveMs}ms" else "N/A"}
                MediaItem/Prepare: ${if (prepMs >= 0) "${prepMs}ms" else "N/A"}
                Buffering:         ${if (bufferMs >= 0) "${bufferMs}ms" else "N/A"}
                Ready:             ${if (readyMs >= 0) "${readyMs}ms" else "N/A"}
                First Audio:       ${if (audioMs >= 0) "${audioMs}ms" else "N/A"}
                Total Startup:     ${totalMs}ms
                ========================================================================
            """.trimIndent())
        }
    }

    fun play(
        track: Track,
        initialSeekMs: Long = 0L,
        requestId: Long = currentSessionId.incrementAndGet()
    ) {
        val tracker = PlaybackTimingTracker(
            requestId = requestId,
            trackTitle = track.title,
            t0TapMs = System.currentTimeMillis()
        )
        activeTimingTracker = tracker
        currentSessionId.set(requestId)
        streamResolveJob?.cancel()

        // 1. Immediately and synchronously stop & flush all previous playback
        try {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        } catch (_: Exception) {}
        youTubeEngine.stop()

        _currentTrack.value = track
        _playbackError.value = null
        _durationMs.value = track.duration * 1000L
        _playbackPositionMs.value = initialSeekMs
        _isBuffering.value = true
        _isPlaying.value = false

        Log.d("AuralisPlayback", "[Play Request #$requestId] id=${track.id}, title='${track.title}', artist='${track.artist}', duration=${track.duration}s, initialSeek=${initialSeekMs}ms")

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

        // Fast-path resolution for native ExoPlayer audio stream (stutter-free native AudioTrack)
        streamResolveJob = scope.launch {
            tracker.tResolveStartMs = System.currentTimeMillis()
            var directUrl: String? = null
            try {
                withTimeoutOrNull(12000L) {
                    directUrl = AudioStreamResolver.resolveAudioStream(track.id, track.title, track.artist)
                }
            } catch (e: Exception) {
                Log.w("AuralisPlayback", "[Resolver] Stream resolve notice: ${e.message}")
            }

            if (currentSessionId.get() != requestId) return@launch
            tracker.tStreamResolvedMs = System.currentTimeMillis()

            if (!directUrl.isNullOrBlank()) {
                Log.d("AuralisPlayback", "[Audio Engine] Direct native ExoPlayer stream resolved for '${track.title}' in ${tracker.tStreamResolvedMs - tracker.t0TapMs}ms ($directUrl)")
                try {
                    youTubeEngine.stop()
                    isUsingExoPlayer = true
                    tracker.streamEngine = "Native ExoPlayer"

                    val highResThumb = getHighResArtworkUrl(track.thumbnail) ?: track.thumbnail
                    val artworkUri = if (!highResThumb.isNullOrBlank()) Uri.parse(highResThumb) else null

                    val mediaItem = MediaItem.Builder()
                        .setUri(directUrl)
                        .setMediaId(track.id)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(track.title)
                                .setArtist(track.artist)
                                .setArtworkUri(artworkUri)
                                .build()
                        )
                        .build()

                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    tracker.tMediaItemPreparedMs = System.currentTimeMillis()
                    if (initialSeekMs > 0) {
                        exoPlayer.seekTo(initialSeekMs)
                    }
                    exoPlayer.play()
                    return@launch
                } catch (e: Exception) {
                    Log.e("AuralisPlayback", "[Audio Engine] ExoPlayer start failed, falling back to YouTube engine: ${e.message}")
                }
            }

            // Fallback to hardened YouTube web engine
            if (currentSessionId.get() != requestId) {
                Log.d("AuralisPlayback", "[Stale fallback dropped] reqId=$requestId vs active=${currentSessionId.get()}")
                return@launch
            }

            try {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            } catch (_: Exception) {}

            isUsingExoPlayer = false
            tracker.streamEngine = "YouTube Web Engine"
            Log.d("AuralisPlayback", "[Audio Engine] Routing to YouTube Web Engine for '${track.title}' (${track.id}) [reqId=$requestId, initialSeek=${initialSeekMs}ms]")
            youTubeEngine.loadVideo(track.id, initialSeekMs, requestId)
        }
    }

    private var prefetchJob: Job? = null

    fun prefetchTrack(track: Track?) {
        if (track == null) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            try {
                // Wait for the active track to finish stream resolution first
                streamResolveJob?.join()
                delay(1500)
                if (com.auralis.music.data.network.AudioStreamResolver.getCachedStream(track.id) == null) {
                    Log.d("AuralisPlayback", "[Prefetch] Pre-resolving stream for '${track.title}' (${track.id}) in background...")
                    com.auralis.music.data.network.AudioStreamResolver.resolveAudioStream(track.id, track.title, track.artist)
                }
            } catch (_: Exception) {}
        }
    }

    fun resume() {
        if (isUsingExoPlayer) {
            exoPlayer.play()
        } else {
            youTubeEngine.play()
        }
        _isPlaying.value = true
    }

    fun pause() {
        if (isUsingExoPlayer) {
            exoPlayer.pause()
        }
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
        if (isUsingExoPlayer) {
            exoPlayer.seekTo(positionMs)
        } else {
            youTubeEngine.seekTo(positionMs)
        }
    }

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    fun setIsFavorite(fav: Boolean) {
        _isFavorite.value = fav
    }

    private var onNextCallback: (() -> Unit)? = null
    private var onPreviousCallback: (() -> Unit)? = null
    private var onToggleFavoriteCallback: (() -> Unit)? = null
    private var onToggleRepeatCallback: (() -> Unit)? = null

    fun setNavigationCallbacks(
        onNext: () -> Unit,
        onPrevious: () -> Unit,
        onToggleFavorite: (() -> Unit)? = null,
        onToggleRepeat: (() -> Unit)? = null
    ) {
        this.onNextCallback = onNext
        this.onPreviousCallback = onPrevious
        this.onToggleFavoriteCallback = onToggleFavorite
        this.onToggleRepeatCallback = onToggleRepeat
    }

    fun next() {
        onNextCallback?.invoke()
    }

    fun previous() {
        onPreviousCallback?.invoke()
    }

    fun toggleFavorite() {
        onToggleFavoriteCallback?.invoke()
    }

    fun toggleRepeat() {
        onToggleRepeatCallback?.invoke()
    }

    fun seekForward(deltaMs: Long = 10000L) {
        val target = (_playbackPositionMs.value + deltaMs).coerceAtMost(_durationMs.value.coerceAtLeast(0))
        seekTo(target)
    }

    fun seekBackward(deltaMs: Long = 10000L) {
        val target = (_playbackPositionMs.value - deltaMs).coerceAtLeast(0)
        seekTo(target)
    }

    fun stop() {
        streamResolveJob?.cancel()
        _isPlaying.value = false
        try {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        } catch (_: Exception) {}
        youTubeEngine.stop()
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
