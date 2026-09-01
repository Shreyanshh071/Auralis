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
import kotlinx.coroutines.flow.*

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val spatialAudioController = SpatialAudioController()

    val queueManager = com.auralis.music.domain.model.AudioQueueManager()
    private val _queueState = MutableStateFlow(queueManager.state)
    val queueState: StateFlow<com.auralis.music.domain.model.QueueState> = _queueState.asStateFlow()

    var isGaplessEnabled: Boolean = true
        private set
    var currentAudioQuality: com.auralis.music.domain.model.AudioQuality = com.auralis.music.domain.model.AudioQuality.AUTO
        private set
    private var enqueuedNextTrack: Track? = null
    private var onGaplessTransitionCallback: ((Track) -> Unit)? = null

    init {
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] Initialized singleton instance")
        com.auralis.music.data.network.AudioStreamResolver.init(appContext)

        // Broadcast real-time playback state to Discord Gateway Rich Presence
        scope.launch {
            combine(
                _currentTrack,
                _isPlaying,
                _playbackPositionMs,
                _durationMs
            ) { track, isPlaying, pos, duration ->
                com.auralis.music.data.network.discord.DiscordGatewayManager.getInstance(appContext)
                    .onPlaybackStateChanged(track, isPlaying, pos, duration)
            }.collect()
        }
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

        // DefaultDataSource.Factory seamlessly handles local file:// (offline downloads), content://, and network streams
        val defaultDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
            appContext,
            httpDataSourceFactory
        )

        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(defaultDataSourceFactory)

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

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem != null) {
                            val nextId = mediaItem.mediaId
                            Log.d("AuralisPlayback", "[Gapless Auto-Transition] Seamlessly crossed boundary into next track: $nextId (0ms delay)")
                            val nextTrack = enqueuedNextTrack
                            if (nextTrack != null && (nextTrack.id == nextId || AudioStreamResolver.getMatchedVideoId(nextTrack.id) == nextId)) {
                                _currentTrack.value = nextTrack
                                _durationMs.value = nextTrack.duration * 1000L
                                _playbackPositionMs.value = 0L
                                enqueuedNextTrack = null
                                onGaplessTransitionCallback?.invoke(nextTrack)
                            } else {
                                dispatchTrackCompleted()
                            }
                        }
                    }

                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        spatialAudioController.attachAudioSession(audioSessionId)
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
            Log.d("AuralisPlayback", "[Track Completed #$completedSessionId] Advancing queue directly from background audio player")
            val nextTrack = queueManager.advanceNext()
            if (nextTrack != null) {
                _queueState.value = queueManager.state
                play(nextTrack, initialSeekMs = 0L)
                val upcoming = queueManager.state.queue.getOrNull(queueManager.state.currentIndex + 1)
                prefetchTrack(upcoming)
            } else {
                if (queueManager.state.repeatMode == com.auralis.music.domain.model.RepeatMode.OFF) {
                    _isPlaying.value = false
                }
            }
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

        // High-frequency real-time ticker for ExoPlayer progress (16ms ~ 60fps for ultra-smooth seekbar & lyrics sync)
        scope.launch {
            while (isActive) {
                delay(16)
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

            // Check for offline downloaded track first for instant playback
            val isExplicitlyDownloaded = com.auralis.music.data.download.AuralisDownloadManager.isDownloaded(track.id)
            val localDownloadedFile = if (isExplicitlyDownloaded) com.auralis.music.data.download.AuralisDownloadManager.getDownloadedFile(track.id) else null
            if (localDownloadedFile != null && localDownloadedFile.exists()) {
                directUrl = Uri.fromFile(localDownloadedFile).toString()
                Log.d("AuralisPlayback", "[Offline Engine] Playing '${track.title}' from local storage: $directUrl")
            } else {
                try {
                    withTimeoutOrNull(3000L) {
                        directUrl = AudioStreamResolver.resolveAudioStream(
                            videoId = track.id,
                            title = track.title,
                            artist = track.artist,
                            quality = currentAudioQuality,
                            context = appContext
                        )
                    }
                } catch (e: Exception) {
                    Log.w("AuralisPlayback", "[Resolver] Stream resolve notice: ${e.message}")
                }
            }

            if (currentSessionId.get() != requestId) return@launch
            tracker.tStreamResolvedMs = System.currentTimeMillis()

            if (!directUrl.isNullOrBlank()) {
                Log.d("AuralisPlayback", "[Audio Engine] Direct native ExoPlayer stream resolved for '${track.title}' in ${tracker.tStreamResolvedMs - tracker.t0TapMs}ms ($directUrl)")
                try {
                    youTubeEngine.stop()
                    isUsingExoPlayer = true
                    tracker.streamEngine = "Native ExoPlayer"

                    val effectiveThumb = if (!track.thumbnail.isNullOrBlank()) {
                        track.thumbnail
                    } else {
                        com.auralis.music.data.network.ArtworkResolver.getArtwork(track) ?: track.thumbnail
                    }
                    val highResThumb = getHighResArtworkUrl(effectiveThumb) ?: effectiveThumb
                    val artworkUri = if (!highResThumb.isNullOrBlank()) Uri.parse(highResThumb) else null
                    val effectiveMediaId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(track.id) ?: track.id

                    val mediaItem = MediaItem.Builder()
                        .setUri(directUrl)
                        .setMediaId(effectiveMediaId)
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

            val effectiveId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(track.id) ?: track.id
            if (!effectiveId.startsWith("sp_") && !effectiveId.startsWith("spotify:")) {
                isUsingExoPlayer = false
                tracker.streamEngine = "YouTube Web Engine"
                Log.d("AuralisPlayback", "[Audio Engine] Routing to YouTube Web Engine for '${track.title}' ($effectiveId) [reqId=$requestId, initialSeek=${initialSeekMs}ms]")
                youTubeEngine.loadVideo(effectiveId, initialSeekMs, requestId)
            } else {
                Log.e("AuralisPlayback", "[Audio Engine] Failed to resolve playable YouTube stream for Spotify track '${track.title}' (${track.id})")
                _playbackError.value = "Unable to load stream for '${track.title}'"
                _isBuffering.value = false
            }
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
                delay(1200)
                Log.d("AuralisPlayback", "[Prefetch] Pre-resolving stream for '${track.title}' (${track.id}) [$currentAudioQuality] in background...")
                val localFile = com.auralis.music.data.download.AuralisDownloadManager.getDownloadedFile(track.id)
                val streamUrl = if (localFile != null && localFile.exists()) {
                    Uri.fromFile(localFile).toString()
                } else {
                    com.auralis.music.data.network.AudioStreamResolver.resolveAudioStream(
                        videoId = track.id,
                        title = track.title,
                        artist = track.artist,
                        quality = currentAudioQuality,
                        context = appContext
                    )
                }

                // 🚀 TRUE GAPLESS PLAYBACK PRE-BUFFERING
                if (!streamUrl.isNullOrBlank() && isGaplessEnabled && isUsingExoPlayer) {
                    withContext(Dispatchers.Main) {
                        if (exoPlayer.mediaItemCount == 1 && _isPlaying.value) {
                            val effectiveThumb = if (!track.thumbnail.isNullOrBlank()) {
                                track.thumbnail
                            } else {
                                com.auralis.music.data.network.ArtworkResolver.getArtwork(track) ?: track.thumbnail
                            }
                            val highResThumb = getHighResArtworkUrl(effectiveThumb) ?: effectiveThumb
                            val artworkUri = if (!highResThumb.isNullOrBlank()) Uri.parse(highResThumb) else null
                            val effectiveMediaId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(track.id) ?: track.id

                            val nextMediaItem = MediaItem.Builder()
                                .setUri(streamUrl)
                                .setMediaId(effectiveMediaId)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(track.title)
                                        .setArtist(track.artist)
                                        .setArtworkUri(artworkUri)
                                        .build()
                                )
                                .build()

                            enqueuedNextTrack = track
                            exoPlayer.addMediaItem(nextMediaItem)
                            Log.d("AuralisPlayback", "[Gapless Queue] Successfully queued next track '${track.title}' into ExoPlayer for 0ms transition")
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun setOnGaplessTransitionCallback(callback: (Track) -> Unit) {
        onGaplessTransitionCallback = callback
    }

    fun setAudioQuality(quality: com.auralis.music.domain.model.AudioQuality) {
        currentAudioQuality = quality
        Log.d("AuralisPlayback", "[Settings] Audio quality updated to: $quality")
    }

    fun setGaplessEnabled(enabled: Boolean) {
        isGaplessEnabled = enabled
        Log.d("AuralisPlayback", "[Settings] Gapless playback set to: $enabled")
        if (!enabled && isUsingExoPlayer) {
            if (exoPlayer.mediaItemCount > 1) {
                exoPlayer.removeMediaItem(1)
                enqueuedNextTrack = null
            }
        }
    }

    fun setSkipSilenceEnabled(enabled: Boolean) {
        if (isUsingExoPlayer) {
            exoPlayer.skipSilenceEnabled = enabled
            Log.d("AuralisPlayback", "[Settings] Skip silence set to: $enabled")
        }
    }

    fun setSpatialAudioEnabled(enabled: Boolean) {
        spatialAudioController.setSpatialAudioEnabled(enabled)
        Log.d("AuralisPlayback", "[Settings] Spatial Audio set to: $enabled")
    }

    fun isSpatialAudioEnabled(): Boolean = spatialAudioController.isSpatialAudioEnabled()

    fun prewarmTracks(tracks: List<Track>) {
        scope.launch(Dispatchers.IO) {
            for (t in tracks.take(3)) {
                try {
                    val isCached = com.auralis.music.data.network.AudioStreamResolver.getCachedStream(t.id) != null ||
                        com.auralis.music.data.network.AudioStreamResolver.getCachedStreamByFingerprint(
                            com.auralis.music.data.network.AudioStreamResolver.getSongFingerprintKey(t.title, t.artist)
                        ) != null
                    if (!isCached) {
                        Log.d("AuralisPlayback", "[Prewarm] Pre-resolving stream for '${t.title}' (${t.id}) in background...")
                        com.auralis.music.data.network.AudioStreamResolver.resolveAudioStream(t.id, t.title, t.artist)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun playTrack(
        track: Track,
        newQueue: List<Track> = emptyList(),
        startIndex: Int = 0,
        isUserQueue: Boolean = (newQueue.size > 1),
        initialPositionMs: Long = 0L
    ) {
        val isAutoQueue = !isUserQueue || newQueue.size <= 1
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] playTrack: title='${track.title}', artist='${track.artist}', queueSize=${newQueue.size}, initialPos=${initialPositionMs}ms")
        
        val qState = if (newQueue.isNotEmpty()) {
            val isSameQueue = queueManager.state.queue.isNotEmpty() &&
                              newQueue.map { it.id } == queueManager.state.queue.map { it.id }
            queueManager.setQueue(newQueue, startIndex, preserveOrderIfSame = isSameQueue, isUserQueue = !isAutoQueue)
        } else {
            queueManager.playTrack(track, isUserQueue = !isAutoQueue)
        }
        _queueState.value = qState

        play(track, initialSeekMs = initialPositionMs)

        val upcomingTrack = qState.queue.getOrNull(qState.currentIndex + 1)
        prefetchTrack(upcomingTrack)
    }

    fun resume() {
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] resume() called (isUsingExo=$isUsingExoPlayer, mediaItems=${exoPlayer.mediaItemCount}, track=${_currentTrack.value?.title})")
        val curTrack = _currentTrack.value
        if (isUsingExoPlayer) {
            if (exoPlayer.mediaItemCount > 0) {
                exoPlayer.play()
                _isPlaying.value = true
            } else if (curTrack != null) {
                Log.d("AuralisPlayback", "[AuralisAudioPlayer] resume() re-loading stream for '${curTrack.title}' (seek=${_playbackPositionMs.value}ms)")
                play(curTrack, initialSeekMs = _playbackPositionMs.value)
            }
        } else {
            if (curTrack != null) {
                youTubeEngine.play()
                _isPlaying.value = true
            }
        }
    }

    fun pause() {
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] pause() called (isUsingExo=$isUsingExoPlayer, track=${_currentTrack.value?.title})")
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
        val bounded = positionMs.coerceAtLeast(0L)
        _playbackPositionMs.value = bounded
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] seekTo(${bounded}ms)")
        if (isUsingExoPlayer) {
            val dur = exoPlayer.duration
            val target = if (dur > 0 && bounded >= dur) (dur - 500L).coerceAtLeast(0L) else bounded
            exoPlayer.seekTo(target)
        } else {
            youTubeEngine.seekTo(bounded)
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
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] next() triggered")
        if (queueManager.state.queue.isNotEmpty()) {
            val nextTrack = queueManager.advanceNext()
            if (nextTrack != null) {
                _queueState.value = queueManager.state
                play(nextTrack, initialSeekMs = 0L)
                val upcoming = queueManager.state.queue.getOrNull(queueManager.state.currentIndex + 1)
                prefetchTrack(upcoming)
            }
        }
        onNextCallback?.invoke()
    }

    fun previous() {
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] previous() triggered (pos=${_playbackPositionMs.value}ms)")
        if (_playbackPositionMs.value > 3000L) {
            seekTo(0L)
            return
        }
        if (queueManager.state.queue.isNotEmpty()) {
            val prevTrack = queueManager.advancePrevious()
            if (prevTrack != null) {
                _queueState.value = queueManager.state
                play(prevTrack, initialSeekMs = 0L)
            } else {
                seekTo(0L)
            }
        } else {
            seekTo(0L)
        }
        onPreviousCallback?.invoke()
    }

    fun toggleShuffle(): com.auralis.music.domain.model.QueueState {
        val qState = queueManager.toggleShuffle()
        _queueState.value = qState
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] toggleShuffle -> isShuffled=${qState.isShuffled}")
        return qState
    }

    fun toggleRepeat(): com.auralis.music.domain.model.RepeatMode {
        val nextMode = when (queueManager.state.repeatMode) {
            com.auralis.music.domain.model.RepeatMode.OFF -> com.auralis.music.domain.model.RepeatMode.ALL
            com.auralis.music.domain.model.RepeatMode.ALL -> com.auralis.music.domain.model.RepeatMode.ONE
            com.auralis.music.domain.model.RepeatMode.ONE -> com.auralis.music.domain.model.RepeatMode.OFF
        }
        val qState = queueManager.setRepeatMode(nextMode)
        _queueState.value = qState
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] toggleRepeat -> repeatMode=$nextMode")
        onToggleRepeatCallback?.invoke()
        return nextMode
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int): com.auralis.music.domain.model.QueueState {
        val qState = queueManager.moveItem(fromIndex, toIndex)
        _queueState.value = qState
        return qState
    }

    fun removeQueueItem(removeIndex: Int): com.auralis.music.domain.model.QueueState {
        val qState = queueManager.removeItem(removeIndex)
        _queueState.value = qState
        return qState
    }

    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val qState = queueManager.addToQueue(tracks)
        _queueState.value = qState
        if (_currentTrack.value == null && tracks.isNotEmpty()) {
            playTrack(tracks.first(), qState.queue, 0, isUserQueue = true)
        }
    }

    fun appendTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val qState = queueManager.appendTracks(tracks)
        _queueState.value = qState
    }

    fun playNext(track: Track) {
        val qState = queueManager.playNext(track)
        _queueState.value = qState
        if (_currentTrack.value == null) {
            playTrack(track, listOf(track), 0, isUserQueue = true)
        }
    }

    fun clearQueue() {
        val qState = queueManager.clearQueue()
        _queueState.value = qState
    }

    fun toggleFavorite() {
        onToggleFavoriteCallback?.invoke()
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
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] stop() called -> flushing streams")
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
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] release() called")
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
