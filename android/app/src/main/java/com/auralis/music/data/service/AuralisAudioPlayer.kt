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
import com.auralis.music.data.local.AuralisDatabase
import com.auralis.music.data.local.mapper.toEntity
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
class AuralisAudioPlayer private constructor(context: Context) : PlaybackClockSource {

    private val appContext = context.applicationContext
    val youTubeEngine = YouTubeAudioEngine(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val spatialAudioController = SpatialAudioController()
    private val queueDataStore = com.auralis.music.data.datastore.QueueDataStore(appContext)
    private val database by lazy { AuralisDatabase.getInstance(appContext) }
    private val trackDao by lazy { database.trackDao() }

    val queueManager = com.auralis.music.domain.model.AudioQueueManager()
    private val _queueState = MutableStateFlow(queueManager.state)
    val queueState: StateFlow<com.auralis.music.domain.model.QueueState> = _queueState.asStateFlow()

    var isGaplessEnabled: Boolean = true
        private set
    var currentAudioQuality: com.auralis.music.domain.model.AudioQuality = com.auralis.music.domain.model.AudioQuality.AUTO
        private set
    private var enqueuedNextTrack: Track? = null
    private var onGaplessTransitionCallback: ((Track) -> Unit)? = null

    private val sleepTimerManager = com.auralis.music.domain.model.SleepTimerManager()
    private var sleepTimerJob: Job? = null
    private val _sleepTimerSeconds = MutableStateFlow(0L)
    val sleepTimerSeconds: StateFlow<Long> = _sleepTimerSeconds.asStateFlow()

    private val _isSleepTimerEndOfSong = MutableStateFlow(false)
    val isSleepTimerEndOfSong: StateFlow<Boolean> = _isSleepTimerEndOfSong.asStateFlow()

    @Volatile
    var stopAtEndOfTrack: Boolean = false
        private set

    private val _needsWebView = MutableStateFlow(false)
    val needsWebView: StateFlow<Boolean> = _needsWebView.asStateFlow()

    init {
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] Initialized singleton instance")
        com.auralis.music.data.network.AudioStreamResolver.init(appContext)

        // Restore persisted queue asynchronously if player is freshly initialized
        scope.launch(Dispatchers.IO) {
            try {
                val persisted = queueDataStore.persistedQueueFlow.first()
                if (persisted.tracks.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (queueManager.state.queue.isEmpty()) {
                            val repeatMode = try {
                                com.auralis.music.domain.model.RepeatMode.valueOf(persisted.repeatMode)
                            } catch (_: Exception) {
                                com.auralis.music.domain.model.RepeatMode.OFF
                            }
                            queueManager.setQueue(
                                tracks = persisted.tracks,
                                startIndex = persisted.currentIndex,
                                preserveOrderIfSame = false,
                                isUserQueue = persisted.isUserQueue
                            )
                            queueManager.setRepeatMode(repeatMode)
                            _queueState.value = queueManager.state
                            val cur = queueManager.state.currentTrack
                            if (cur != null && _currentTrack.value == null) {
                                _currentTrack.value = cur
                                _playbackPositionMs.value = persisted.lastPositionMs
                                _durationMs.value = cur.duration * 1000L
                            }
                            Log.d("AuralisPlayback", "[Persistence] Restored queue from disk: ${persisted.tracks.size} tracks, index=${persisted.currentIndex}, isUserQueue=${persisted.isUserQueue}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("AuralisPlayback", "[Persistence] Failed to restore queue on init: ${e.message}")
            }
        }

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

        // Observe favorite state directly from Room database for currently playing track
        scope.launch {
            _currentTrack.map { it?.id }.distinctUntilChanged().collectLatest { trackId ->
                if (trackId != null) {
                    trackDao.isFavoriteFlow(trackId).collect { fav ->
                        _isFavorite.value = (fav == true)
                    }
                } else {
                    _isFavorite.value = false
                }
            }
        }
    }

    var isUsingExoPlayer: Boolean = false
        private set
    private var nativeRetryCount = 0
    private var streamResolveJob: Job? = null

    private val _isSpeakerForced = MutableStateFlow(false)
    val isSpeakerForced: StateFlow<Boolean> = _isSpeakerForced.asStateFlow()
    private var preferredAudioDevice: android.media.AudioDeviceInfo? = null

    fun routeAudio(toSpeaker: Boolean) {
        _isSpeakerForced.value = toSpeaker
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        try {
            val outputs = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            val speakerDevice = outputs.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val btDevice = outputs.firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }

            preferredAudioDevice = if (toSpeaker) speakerDevice else btDevice

            // Direct ExoPlayer AudioTrack routing
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                exoPlayer.setPreferredAudioDevice(preferredAudioDevice)
            }

            // Always maintain MODE_NORMAL so media streams are never degraded to telephony voice codecs
            audioManager.mode = android.media.AudioManager.MODE_NORMAL

            Log.d("AuralisPlayback", "[AudioRoute] Switched audio route: toSpeaker=$toSpeaker (device=${preferredAudioDevice?.productName ?: "Default"})")
        } catch (e: Throwable) {
            Log.e("AuralisPlayback", "[AudioRoute] Failed to switch audio route: ${e.message}", e)
        }
    }

    val audioLeadingSilenceProcessor = AudioLeadingSilenceProcessor().apply {
        onLeadingSilenceDetected = { silenceMs ->
            Log.d("AuralisPlayback", "[AudioLeadingSilence] Detected stream leading silence: ${silenceMs}ms for '${_currentTrack.value?.title}'")
            _audioLeadingSilenceMs.value = silenceMs
        }
    }
    private val _audioLeadingSilenceMs = MutableStateFlow<Long?>(null)
    val audioLeadingSilenceMs: StateFlow<Long?> = _audioLeadingSilenceMs.asStateFlow()

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
                /* bufferForPlaybackMs = */ 200,
                /* bufferForPlaybackAfterRebufferMs = */ 250
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink? {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(audioLeadingSilenceProcessor))
                    .build()
            }
        }

        ExoPlayer.Builder(appContext, renderersFactory)
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
                            nativeRetryCount = 0
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
                            _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
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
                                if (isUsingExoPlayer) {
                                    val curPos = try { exoPlayer.currentPosition } catch (_: Exception) { _playbackPositionMs.value }
                                    val dur = try { exoPlayer.duration } catch (_: Exception) { _durationMs.value }
                                    val effectiveDur = if (dur > 0) dur else (_currentTrack.value?.duration ?: 0L) * 1000L

                                    // Natural track end validation:
                                    // Only advance if track duration is unknown/short or playback genuinely reached within 4s of track end.
                                    // Prevents premature stream drop / socket close from skipping the song or resetting to 0.
                                    val reachedNaturalEnd = effectiveDur <= 3000L || curPos >= (effectiveDur - 4000L)
                                    if (reachedNaturalEnd) {
                                        _isPlaying.value = false
                                        _isBuffering.value = false
                                        try {
                                            exoPlayer.stop()
                                            exoPlayer.clearMediaItems()
                                        } catch (_: Exception) {}
                                        dispatchTrackCompleted()
                                    } else {
                                        // Premature stream EOF / socket close: reconnect without falsely publishing not-playing.
                                        // Mark as buffering so the UI shows the rebuffering state rather than a paused flash.
                                        _isBuffering.value = true
                                        Log.w("AuralisPlayback", "[Premature Stream EOF] pos=${curPos}ms vs dur=${effectiveDur}ms. Reconnecting from ${curPos}ms instead of skipping track!")
                                        _currentTrack.value?.let { cur ->
                                            play(cur, initialSeekMs = curPos)
                                        }
                                    }
                                } else {
                                    _isPlaying.value = false
                                    _isBuffering.value = false
                                }
                            }
                            Player.STATE_IDLE -> _isBuffering.value = false
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem != null) {
                            if (stopAtEndOfTrack) {
                                Log.d("AuralisPlayback", "[SleepTimer] Stop at end of track triggered at gapless boundary. Pausing playback.")
                                cancelSleepTimer()
                                pause()
                                return
                            }
                            val nextId = mediaItem.mediaId
                            Log.d("AuralisPlayback", "[Gapless Auto-Transition] Seamlessly crossed boundary into next track: $nextId (0ms delay)")
                            val nextTrack = enqueuedNextTrack
                            if (nextTrack != null && (nextTrack.id == nextId || AudioStreamResolver.getMatchedVideoId(nextTrack.id) == nextId)) {
                                val advancedTrack = queueManager.advanceNext() ?: nextTrack
                                _queueState.value = queueManager.state
                                _currentTrack.value = advancedTrack
                                _durationMs.value = advancedTrack.duration * 1000L
                                _playbackPositionMs.value = 0L
                                enqueuedNextTrack = null
                                syncUpcomingGaplessTrack()
                                persistQueue()
                                onGaplessTransitionCallback?.invoke(advancedTrack)
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
                            val curTrack = _currentTrack.value
                            if (curTrack != null && nativeRetryCount < 2) {
                                nativeRetryCount++
                                Log.w("AuralisPlayback", "[ExoPlayer Auto-Recovery #$nativeRetryCount] Re-resolving direct audio stream for '${curTrack.title}' at ${savedPos}ms (error=${error.errorCodeName})...")
                                AudioStreamResolver.invalidateStream(curTrack.id)
                                play(curTrack, initialSeekMs = savedPos)
                            } else {
                                nativeRetryCount = 0
                                isUsingExoPlayer = false
                                curTrack?.let { track ->
                                    _needsWebView.value = true
                                    Log.d("AuralisPlayback", "[Fallback] Switching to YouTube HTML5 engine after ExoPlayer error (seek=${savedPos}ms)")
                                    youTubeEngine.loadVideo(track.id, savedPos)
                                }
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

    // ── PlaybackClockSource ──────────────────────────────────────────────────
    // Sampled once per displayed frame by the lyrics renderer, so these three
    // must stay allocation-free and never throw. ExoPlayer is not thread-safe:
    // touch it only from the thread that owns it, and otherwise hand back the
    // coarse mirror the 16 ms ticker maintains. `exoPlayer` is `by lazy`, but
    // the `isUsingExoPlayer` guard means the clock can never be what creates it.

    override fun rawPositionMs(): Long {
        if (!isUsingExoPlayer) return _playbackPositionMs.value
        return try {
            if (exoPlayer.applicationLooper == android.os.Looper.myLooper()) {
                exoPlayer.currentPosition
            } else {
                _playbackPositionMs.value
            }
        } catch (_: Exception) {
            _playbackPositionMs.value
        }
    }

    /**
     * Engine-agnostic: covers the WebView fallback path too. Returns true only
     * when the track is unpaused AND not stalled on a buffer refill, so the
     * lyrics highlight freezes during rebuffer rather than sprinting ahead.
     */
    override fun isPlaying(): Boolean = _isPlaying.value && !_isBuffering.value

    override fun isBuffering(): Boolean = _isBuffering.value

    override fun speed(): Float {
        if (!isUsingExoPlayer) return 1.0f
        return try {
            if (exoPlayer.applicationLooper == android.os.Looper.myLooper()) {
                exoPlayer.playbackParameters.speed
            } else {
                1.0f
            }
        } catch (_: Exception) {
            1.0f
        }
    }

    private val onTrackCompletedListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val lastCompletedSessionId = java.util.concurrent.atomic.AtomicLong(-1L)

    private fun dispatchTrackCompleted(completedSessionId: Long = currentSessionId.get()) {
        if (completedSessionId != currentSessionId.get()) {
            Log.d("AuralisPlayback", "[Stale Track Completed dropped] completedSessionId=$completedSessionId vs currentSession=${currentSessionId.get()}")
            return
        }
        if (lastCompletedSessionId.getAndSet(completedSessionId) != completedSessionId) {
            if (stopAtEndOfTrack) {
                Log.d("AuralisPlayback", "[SleepTimer] Stop at end of track triggered for #$completedSessionId in dispatchTrackCompleted. Pausing playback.")
                cancelSleepTimer()
                pause()
                return
            }
            Log.d("AuralisPlayback", "[Track Completed #$completedSessionId] Advancing queue directly from background audio player")
            val nextTrack = queueManager.advanceNext()
            if (nextTrack != null) {
                _queueState.value = queueManager.state
                play(nextTrack, initialSeekMs = 0L)
                syncUpcomingGaplessTrack()
                persistQueue()
            } else {
                if (queueManager.state.repeatMode == com.auralis.music.domain.model.RepeatMode.OFF) {
                    _isPlaying.value = false
                }
                persistQueue()
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
        var lastYtPersistTickMs = System.currentTimeMillis()
        scope.launch {
            youTubeEngine.playbackPositionMs.collect { pos ->
                if (!isUsingExoPlayer) {
                    _playbackPositionMs.value = pos
                    val now = System.currentTimeMillis()
                    if (now - lastYtPersistTickMs >= 3000L && _isPlaying.value) {
                        lastYtPersistTickMs = now
                        persistQueue()
                    }
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

        // Real-time progress ticker for ExoPlayer UI seekbars & time indicators (100ms is completely smooth
        // for seekbars and stops main-thread flooding. Lyrics has its own dedicated 60fps withFrameMillis clock).
        scope.launch {
            var lastPersistTickMs = System.currentTimeMillis()
            while (isActive) {
                delay(100)
                if (isUsingExoPlayer && (exoPlayer.isPlaying || _isPlaying.value)) {
                    val pos = exoPlayer.currentPosition
                    if (pos >= 0L && pos != _playbackPositionMs.value) {
                        _playbackPositionMs.value = pos
                    }
                    val dur = exoPlayer.duration
                    if (dur > 0 && dur != _durationMs.value) {
                        _durationMs.value = dur
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastPersistTickMs >= 3000L) {
                        lastPersistTickMs = now
                        persistQueue()
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

    fun updateCurrentTrackArtwork(artworkUrl: String) {
        if (artworkUrl.isBlank()) return
        val current = _currentTrack.value ?: return
        if (current.thumbnail.isBlank() || current.thumbnail != artworkUrl) {
            _currentTrack.value = current.copy(thumbnail = artworkUrl)
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

        audioLeadingSilenceProcessor.resetDetection()
        _audioLeadingSilenceMs.value = null

        val localArt = com.auralis.music.data.download.AuralisDownloadManager.getDownloadedArtworkFile(track.id)
        val initialTrack = if (track.thumbnail.isBlank() && localArt != null && localArt.exists()) {
            track.copy(thumbnail = Uri.fromFile(localArt).toString())
        } else {
            track
        }

        _currentTrack.value = initialTrack
        _playbackError.value = null
        _durationMs.value = track.duration * 1000L
        _playbackPositionMs.value = initialSeekMs
        _isBuffering.value = true
        _isPlaying.value = false

        Log.d("AuralisPlayback", "[Play Request #$requestId] id=${track.id}, title='${track.title}', artist='${track.artist}', duration=${track.duration}s, initialSeek=${initialSeekMs}ms")

        // Start MediaSessionService for uninterrupted background audio and immediate foreground notification
        startMediaService(AuralisMediaService.ACTION_START)

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
                    withTimeoutOrNull(6500L) {
                        directUrl = AudioStreamResolver.resolveAudioStream(
                            videoId = track.id,
                            title = track.title,
                            artist = track.artist,
                            quality = currentAudioQuality,
                            context = appContext,
                            duration = track.duration
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

                    val localArtFile = com.auralis.music.data.download.AuralisDownloadManager.getDownloadedArtworkFile(track.id)
                    val effectiveThumb = when {
                        localArtFile != null && localArtFile.exists() && localArtFile.length() > 500 -> {
                            Uri.fromFile(localArtFile).toString()
                        }
                        !track.thumbnail.isNullOrBlank() -> {
                            track.thumbnail
                        }
                        else -> {
                            com.auralis.music.data.network.ArtworkResolver.getArtwork(track) ?: track.thumbnail
                        }
                    }
                    val highResThumb = getHighResArtworkUrl(effectiveThumb) ?: effectiveThumb
                    val artworkUri = if (!highResThumb.isNullOrBlank()) Uri.parse(highResThumb) else null
                    val effectiveMediaId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(track.id) ?: track.id
                    val isRedundantAlbum = com.auralis.music.data.network.AlbumMetadataResolver.isRedundantOrSingle(track.album, track.title)
                    val displayAlbum = if (isRedundantAlbum) null else track.album

                    val mediaItem = MediaItem.Builder()
                        .setUri(directUrl)
                        .setMediaId(effectiveMediaId)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(track.title)
                                .setArtist(track.artist)
                                .setAlbumTitle(displayAlbum)
                                .setArtworkUri(artworkUri)
                                .build()
                        )
                        .build()

                    exoPlayer.setMediaItem(mediaItem)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && _isSpeakerForced.value && preferredAudioDevice != null) {
                        exoPlayer.setPreferredAudioDevice(preferredAudioDevice)
                    }
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
                _needsWebView.value = true
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
                        context = appContext,
                        duration = track.duration
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

                            val isRedundantNext = com.auralis.music.data.network.AlbumMetadataResolver.isRedundantOrSingle(track.album, track.title)
                            val displayNextAlbum = if (isRedundantNext) null else track.album

                            val nextMediaItem = MediaItem.Builder()
                                .setUri(streamUrl)
                                .setMediaId(effectiveMediaId)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(track.title)
                                        .setArtist(track.artist)
                                        .setAlbumTitle(displayNextAlbum)
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

    fun syncUpcomingGaplessTrack() {
        if (stopAtEndOfTrack) {
            prefetchJob?.cancel()
            if (isUsingExoPlayer && exoPlayer.mediaItemCount > 1) {
                try {
                    exoPlayer.removeMediaItem(1)
                } catch (_: Exception) {}
            }
            enqueuedNextTrack = null
            return
        }
        val nextUpcoming = queueManager.state.queue.getOrNull(queueManager.state.currentIndex + 1)
        if (nextUpcoming?.id != enqueuedNextTrack?.id) {
            prefetchJob?.cancel()
            if (isUsingExoPlayer && exoPlayer.mediaItemCount > 1) {
                try {
                    exoPlayer.removeMediaItem(1)
                } catch (_: Exception) {}
            }
            enqueuedNextTrack = null
            if (nextUpcoming != null) {
                prefetchTrack(nextUpcoming)
            }
        }
    }

    fun persistQueue() {
        val qState = queueManager.state
        val pos = _playbackPositionMs.value
        scope.launch(Dispatchers.IO) {
            try {
                queueDataStore.saveQueue(
                    tracks = qState.queue,
                    currentIndex = qState.currentIndex,
                    positionMs = pos,
                    isUserQueue = qState.isUserQueue,
                    isShuffled = qState.isShuffled,
                    repeatMode = qState.repeatMode.name
                )
            } catch (e: Exception) {
                Log.w("AuralisPlayback", "[Persistence] Failed to persist queue: ${e.message}")
            }
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
                    val isCached = com.auralis.music.data.network.AudioStreamResolver.getCachedStream(t.id) != null
                    if (!isCached) {
                        Log.d("AuralisPlayback", "[Prewarm] Pre-resolving stream for '${t.title}' (${t.id}) in background...")
                        com.auralis.music.data.network.AudioStreamResolver.resolveAudioStream(t.id, t.title, t.artist, duration = t.duration)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val isGuestListenTogether = MutableStateFlow(false)

    fun setGuestListenTogether(isGuest: Boolean) {
        isGuestListenTogether.value = isGuest
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] setGuestListenTogether: $isGuest")
    }

    fun syncPlayTrack(
        track: Track,
        newQueue: List<Track> = emptyList(),
        startIndex: Int = 0,
        initialPositionMs: Long = 0L
    ) {
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] syncPlayTrack from host: '${track.title}', pos=${initialPositionMs}ms")
        val qState = if (newQueue.isNotEmpty()) {
            queueManager.setQueue(newQueue, startIndex, preserveOrderIfSame = false, isUserQueue = true)
        } else {
            queueManager.playTrack(track, isUserQueue = true)
        }
        _queueState.value = qState
        play(track, initialSeekMs = initialPositionMs)
    }

    fun syncResume() {
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] syncResume from host")
        val curTrack = _currentTrack.value ?: return
        if (isUsingExoPlayer && exoPlayer.mediaItemCount > 0) {
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                exoPlayer.prepare()
            }
            exoPlayer.play()
            _isPlaying.value = true
        } else if (!isUsingExoPlayer && youTubeEngine.hasActiveStream()) {
            youTubeEngine.play()
            _isPlaying.value = true
        } else {
            val durMs = (curTrack.duration * 1000L).takeIf { it > 0 } ?: _durationMs.value
            val safeSeekMs = if (durMs > 1000L && _playbackPositionMs.value >= durMs - 500L) {
                0L
            } else {
                _playbackPositionMs.value.coerceAtLeast(0L)
            }
            play(curTrack, initialSeekMs = safeSeekMs)
        }
    }

    fun syncPause() {
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] syncPause from host")
        if (isUsingExoPlayer) {
            exoPlayer.pause()
        }
        youTubeEngine.pause()
        _isPlaying.value = false
    }

    fun syncSeek(positionMs: Long) {
        val bounded = positionMs.coerceAtLeast(0L)
        _playbackPositionMs.value = bounded
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] syncSeek from host: ${bounded}ms")
        if (isUsingExoPlayer) {
            val dur = exoPlayer.duration
            val target = if (dur > 0 && bounded >= dur) (dur - 500L).coerceAtLeast(0L) else bounded
            exoPlayer.seekTo(target)
        } else {
            youTubeEngine.seekTo(bounded)
        }
    }

    fun playTrack(
        track: Track,
        newQueue: List<Track> = emptyList(),
        startIndex: Int = 0,
        isUserQueue: Boolean = (newQueue.size > 1),
        initialPositionMs: Long = 0L
    ) {
        if (isGuestListenTogether.value) {
            Log.d("AuralisPlayback", "[AuralisAudioPlayer] playTrack blocked - user is listener in Listen Together room")
            return
        }
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
        syncUpcomingGaplessTrack()
        persistQueue()
    }

    fun startMediaService(action: String = AuralisMediaService.ACTION_START) {
        try {
            val intent = Intent(appContext, AuralisMediaService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                androidx.core.content.ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
            Log.d("AuralisPlayback", "[MediaSession Service] Service started with action=$action")
        } catch (e: Exception) {
            Log.w("AuralisPlayback", "[MediaSession Service] Service start notice: ${e.message}")
        }
    }

    fun resume() {
        if (isGuestListenTogether.value) {
            Log.d("AuralisPlayback", "[AuralisAudioPlayer] resume() blocked - user is listener in Listen Together room")
            return
        }
        val curTrack = _currentTrack.value ?: return
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] resume() called (isUsingExo=$isUsingExoPlayer, mediaItems=${exoPlayer.mediaItemCount}, track=${curTrack.title}, seek=${_playbackPositionMs.value}ms)")

        startMediaService(AuralisMediaService.ACTION_START)

        if (isUsingExoPlayer && exoPlayer.mediaItemCount > 0) {
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                exoPlayer.prepare()
            }
            exoPlayer.play()
            _isPlaying.value = true
        } else if (!isUsingExoPlayer && youTubeEngine.hasActiveStream()) {
            youTubeEngine.play()
            _isPlaying.value = true
        } else if (streamResolveJob?.isActive == true) {
            Log.d("AuralisPlayback", "[AuralisAudioPlayer] resume() called while stream resolution is in-flight for '${curTrack.title}', preserving active resolution")
        } else {
            // Cold start, stream unloaded, or cleared: re-resolve stream and play from saved position
            val durMs = (curTrack.duration * 1000L).takeIf { it > 0 } ?: _durationMs.value
            val safeSeekMs = if (durMs > 1000L && _playbackPositionMs.value >= durMs - 500L) {
                0L
            } else {
                _playbackPositionMs.value.coerceAtLeast(0L)
            }
            Log.d("AuralisPlayback", "[AuralisAudioPlayer] resume() cold-start/stream-reload for '${curTrack.title}' (seek=${safeSeekMs}ms)")
            play(curTrack, initialSeekMs = safeSeekMs)
        }
    }

    fun pause() {
        if (isGuestListenTogether.value) {
            Log.d("AuralisPlayback", "[AuralisAudioPlayer] pause() blocked - user is listener in Listen Together room")
            return
        }
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] pause() called (isUsingExo=$isUsingExoPlayer, track=${_currentTrack.value?.title})")
        if (isUsingExoPlayer) {
            try {
                val currentPos = exoPlayer.currentPosition
                if (currentPos >= 0L) {
                    _playbackPositionMs.value = currentPos
                }
            } catch (_: Exception) {}
            exoPlayer.pause()
        }
        youTubeEngine.pause()
        _isPlaying.value = false
        startMediaService(AuralisMediaService.ACTION_START)
        persistQueue()
    }

    fun togglePlayPause() {
        if (isGuestListenTogether.value) {
            Log.d("AuralisPlayback", "[AuralisAudioPlayer] togglePlayPause() blocked - user is listener in Listen Together room")
            return
        }
        if (_isPlaying.value) {
            pause()
        } else {
            resume()
        }
    }

    fun seekTo(positionMs: Long) {
        if (isGuestListenTogether.value) {
            Log.d("AuralisPlayback", "[AuralisAudioPlayer] seekTo() blocked - user is listener in Listen Together room")
            return
        }
        val bounded = positionMs.coerceAtLeast(0L)
        _playbackPositionMs.value = bounded
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] seekTo(${bounded}ms)")
        if (isUsingExoPlayer) {
            val dur = exoPlayer.duration
            val target = if (dur > 0 && bounded >= dur) (dur - 500L).coerceAtLeast(0L) else bounded
            exoPlayer.seekTo(target)
            _isBuffering.value = (exoPlayer.playbackState == Player.STATE_BUFFERING)
        } else {
            youTubeEngine.seekTo(bounded)
            _isBuffering.value = youTubeEngine.isBuffering.value
        }
        persistQueue()
    }

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    fun setIsFavorite(fav: Boolean) {
        _isFavorite.value = fav
    }

    private var onNextCallback: (() -> Unit)? = null
    private var onPreviousCallback: (() -> Unit)? = null
    private var onToggleFavoriteCallback: (() -> Unit)? = null

    // ── SLEEP TIMER ──

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            cancelSleepTimer()
            return
        }
        stopAtEndOfTrack = false
        _isSleepTimerEndOfSong.value = false
        sleepTimerManager.setTimer(minutes)
        val initialSec = sleepTimerManager.getRemainingSeconds()
        _sleepTimerSeconds.value = initialSec
        Log.d("AuralisPlayback", "[SleepTimer] Set sleep timer for $minutes minutes (${initialSec}s)")
        startSleepTimerTicker()
    }

    fun setSleepTimerEndOfTrack() {
        sleepTimerJob?.cancel()
        stopAtEndOfTrack = true
        _isSleepTimerEndOfSong.value = true

        val dur = _durationMs.value.takeIf { it > 0L } ?: ((_currentTrack.value?.duration ?: 0L) * 1000L)
        val pos = rawPositionMs().takeIf { it > 0L } ?: _playbackPositionMs.value
        val remainingSec = maxOf(1L, (dur - pos) / 1000L)
        sleepTimerManager.setTimerSeconds(remainingSec)
        _sleepTimerSeconds.value = remainingSec
        Log.d("AuralisPlayback", "[SleepTimer] Set sleep timer for End of Track (estimated ${remainingSec}s)")
        syncUpcomingGaplessTrack()
        startSleepTimerTicker()
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerManager.cancel()
        val wasEndOfTrack = stopAtEndOfTrack
        stopAtEndOfTrack = false
        _isSleepTimerEndOfSong.value = false
        _sleepTimerSeconds.value = 0L
        Log.d("AuralisPlayback", "[SleepTimer] Cancelled sleep timer")
        if (wasEndOfTrack) {
            syncUpcomingGaplessTrack()
        }
    }

    private fun startSleepTimerTicker() {
        sleepTimerJob?.cancel()
        sleepTimerJob = scope.launch {
            Log.d("AuralisPlayback", "[SleepTimer] Ticker started (stopAtEndOfTrack=$stopAtEndOfTrack, deadline=${sleepTimerManager.deadlineEpochMs})")
            while (sleepTimerManager.isSet) {
                val remaining = if (stopAtEndOfTrack) {
                    val dur = _durationMs.value.takeIf { it > 0L } ?: ((_currentTrack.value?.duration ?: 0L) * 1000L)
                    val pos = rawPositionMs().takeIf { it > 0L } ?: _playbackPositionMs.value
                    maxOf(0L, (dur - pos) / 1000L)
                } else {
                    sleepTimerManager.getRemainingSeconds()
                }

                _sleepTimerSeconds.value = remaining

                if (stopAtEndOfTrack) {
                    if (remaining <= 0L) {
                        Log.d("AuralisPlayback", "[SleepTimer] End of track reached by countdown! Stopping playback now.")
                        cancelSleepTimer()
                        pause()
                        break
                    }
                } else {
                    if (remaining <= 0L || sleepTimerManager.isExpired()) {
                        Log.d("AuralisPlayback", "[SleepTimer] Sleep timer reached target! Stopping playback now.")
                        cancelSleepTimer()
                        pause()
                        break
                    }
                }

                delay(1000L)
            }
        }
    }
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

    private var lastPreviousTapMs = 0L

    fun next(): Boolean {
        if (isGuestListenTogether.value) {
            Log.d("AuralisPlayback", "[AuralisAudioPlayer] next() blocked - user is listener in Listen Together room")
            return false
        }
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] next() triggered (queueSize=${queueManager.state.queue.size}, currentIndex=${queueManager.state.currentIndex})")
        if (queueManager.state.queue.isNotEmpty()) {
            val nextTrack = queueManager.advanceNext(isUserSkip = true)
            if (nextTrack != null) {
                _queueState.value = queueManager.state
                play(nextTrack, initialSeekMs = 0L)
                syncUpcomingGaplessTrack()
                persistQueue()
                return true
            }
        }
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] next() cannot advance queue (end of queue or single track). Notifying onNextCallback.")
        onNextCallback?.invoke()
        return false
    }

    fun previous() {
        if (isGuestListenTogether.value) {
            Log.d("AuralisPlayback", "[AuralisAudioPlayer] previous() blocked - user is listener in Listen Together room")
            return
        }
        val now = System.currentTimeMillis()
        val isDoubleTap = (now - lastPreviousTapMs) <= 2000L
        lastPreviousTapMs = now

        Log.d("AuralisPlayback", "[AuralisAudioPlayer] previous() triggered (pos=${_playbackPositionMs.value}ms, isDoubleTap=$isDoubleTap)")
        if (_playbackPositionMs.value > 3000L && !isDoubleTap) {
            seekTo(0L)
            return
        }
        if (queueManager.state.queue.isNotEmpty()) {
            val prevTrack = queueManager.advancePrevious(isUserSkip = true)
            if (prevTrack != null) {
                _queueState.value = queueManager.state
                play(prevTrack, initialSeekMs = 0L)
                syncUpcomingGaplessTrack()
                persistQueue()
            } else {
                seekTo(0L)
            }
        } else {
            seekTo(0L)
        }
    }

    fun toggleShuffle(): com.auralis.music.domain.model.QueueState {
        val qState = queueManager.toggleShuffle()
        _queueState.value = qState
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] toggleShuffle -> isShuffled=${qState.isShuffled}")
        syncUpcomingGaplessTrack()
        persistQueue()
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
        syncUpcomingGaplessTrack()
        persistQueue()
        onToggleRepeatCallback?.invoke()
        return nextMode
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int): com.auralis.music.domain.model.QueueState {
        val qState = queueManager.moveItem(fromIndex, toIndex)
        _queueState.value = qState
        syncUpcomingGaplessTrack()
        persistQueue()
        return qState
    }

    fun removeQueueItem(removeIndex: Int): com.auralis.music.domain.model.QueueState {
        val qState = queueManager.removeItem(removeIndex)
        _queueState.value = qState
        syncUpcomingGaplessTrack()
        persistQueue()
        return qState
    }

    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val qState = queueManager.addToQueue(tracks)
        _queueState.value = qState
        syncUpcomingGaplessTrack()
        persistQueue()
        if (_currentTrack.value == null && tracks.isNotEmpty()) {
            playTrack(tracks.first(), qState.queue, 0, isUserQueue = true)
        }
    }

    fun appendTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val qState = queueManager.appendTracks(tracks)
        _queueState.value = qState
        syncUpcomingGaplessTrack()
        persistQueue()
    }

    fun playNext(track: Track) {
        playNext(listOf(track))
    }

    fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val qState = queueManager.playNext(tracks)
        _queueState.value = qState
        syncUpcomingGaplessTrack()
        persistQueue()
        if (_currentTrack.value == null) {
            playTrack(tracks.first(), tracks, 0, isUserQueue = true)
        }
    }

    fun clearQueue() {
        val qState = queueManager.clearQueue()
        _queueState.value = qState
        _currentTrack.value = null
        syncUpcomingGaplessTrack()
        scope.launch(Dispatchers.IO) {
            try {
                queueDataStore.clearPersistedQueue()
            } catch (_: Exception) {}
        }
    }

    fun clearCurrentTrack() {
        _currentTrack.value = null
        _isPlaying.value = false
        _playbackPositionMs.value = 0L
        _durationMs.value = 0L
    }

    fun toggleFavorite(targetTrack: Track? = _currentTrack.value) {
        val track = targetTrack ?: return
        if (track.id == _currentTrack.value?.id) {
            _isFavorite.value = !_isFavorite.value
        }
        scope.launch(Dispatchers.IO) {
            val existing = trackDao.getTrackById(track.id)
            val nextFav = !(existing?.isFavorite ?: false)
            trackDao.upsertTrack(
                track.toEntity(
                    isFavorite = nextFav,
                    favoriteAddedAt = if (nextFav) System.currentTimeMillis() else null
                )
            )
            withContext(Dispatchers.Main) {
                if (track.id == _currentTrack.value?.id) {
                    _isFavorite.value = nextFav
                }
                onToggleFavoriteCallback?.invoke()
            }
        }
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
        cancelSleepTimer()
        streamResolveJob?.cancel()
        _isPlaying.value = false
        try {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        } catch (_: Exception) {}
        youTubeEngine.stop()
    }

    fun getOrCreateWebView(ctx: Context): View {
        _needsWebView.value = true
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
