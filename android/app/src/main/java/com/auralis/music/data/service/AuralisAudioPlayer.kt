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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val settingsDataStore = com.auralis.music.data.datastore.SettingsDataStore(appContext)
    private var settingsLoaded = false
    private var runtimeSettings = com.auralis.music.domain.model.PlayerSettings()
    private val queuePersistenceMutex = Mutex()
    private var queueRestoreComplete = false
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private val connectedAudioDevices = mutableSetOf<Int>()
    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out android.media.AudioDeviceInfo>) {
            val newBluetoothOutput = devices.any { device ->
                device.id !in connectedAudioDevices && device.isSink && device.type in setOf(
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER
                )
            }
            connectedAudioDevices.addAll(devices.map { it.id })
            if (newBluetoothOutput && runtimeSettings.resumeOnBluetoothConnect &&
                !_isSpeakerForced.value && !_isPlaying.value && !_isBuffering.value &&
                _currentTrack.value != null && !isGuestListenTogether.value && !isMediaMuted()) {
                resume()
            }
        }

        override fun onAudioDevicesRemoved(devices: Array<out android.media.AudioDeviceInfo>) {
            connectedAudioDevices.removeAll(devices.map { it.id }.toSet())
        }
    }
    private val mediaVolumeObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { pauseIfMediaMuted() }
    }

    private fun isMediaMuted(): Boolean = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0 ||
        audioManager.isStreamMute(android.media.AudioManager.STREAM_MUSIC)

    private fun pauseIfMediaMuted() {
        if (runtimeSettings.pauseOnMediaMute && _isPlaying.value && isMediaMuted()) pause()
    }

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
                if (!settingsDataStore.settingsFlow.first().persistentQueue) {
                    queuePersistenceMutex.withLock { queueDataStore.clearPersistedQueue() }
                    return@launch
                }
                val persisted = queueDataStore.persistedQueueFlow.first()
                if (persisted.tracks.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (settingsDataStore.settingsFlow.first().persistentQueue && queueManager.state.queue.isEmpty()) {
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
                            // Shuffle was saved but never restored, so it silently turned off
                            // every time the app restarted.
                            queueManager.restoreShuffleFlag(persisted.isShuffled)
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
            } finally {
                withContext(Dispatchers.Main) {
                    queueRestoreComplete = true
                    persistQueue()
                }
            }
        }

        // Broadcast real-time playback state to Discord Gateway Rich Presence
        scope.launch {
            combine(
                _currentTrack,
                _isPlaying,
                _isBuffering,
                _playbackPositionMs,
                _durationMs
            ) { track, isPlaying, isBuffering, pos, duration ->
                com.auralis.music.data.network.discord.DiscordGatewayManager.getInstance(appContext)
                    .onPlaybackStateChanged(track, isPlaying, isBuffering, pos, duration)
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
    private var nativeStreamPreparation: Deferred<String?>? = null

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
        // Network audio goes through the on-disk song cache (Settings > Storage). DefaultDataSource
        // only uses this factory for http(s), so downloaded files never get copied into the cache.
        SongCache.observeSettings(appContext)
        val defaultDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
            appContext,
            SongCache.dataSourceFactory(appContext, httpDataSourceFactory)
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
                        if (!isUsingExoPlayer) return
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
                        if (!isUsingExoPlayer) return
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            val seekMs = newPosition.positionMs
                            _playbackPositionMs.value = seekMs
                            youTubeEngine.seekTo(seekMs)
                            _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (!isUsingExoPlayer) return
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
                        if (!isUsingExoPlayer) return
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem != null) {
                            if (isGuestListenTogether.value) {
                                syncPause()
                                return
                            }
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
                                publishDiscordPresenceNow()
                                enqueuedNextTrack = null
                                syncUpcomingGaplessTrack()
                                persistQueue()
                                onGaplessTransitionCallback?.invoke(advancedTrack)
                                if (runtimeSettings.autoLoadMore && !queueManager.state.isUserQueue && queueManager.isNearEnd(4)) {
                                    requestAutoQueueExtension()
                                }
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
            if (isGuestListenTogether.value) {
                // A Listen Together guest waits at the end for the host's next song instead of
                // starting its own next queue item (which put guests on a different song).
                Log.d("AuralisPlayback", "[Track Completed #$completedSessionId] Guest: waiting for the host's next song")
                _isPlaying.value = false
                return
            }
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
                if (runtimeSettings.autoLoadMore && !queueManager.state.isUserQueue && queueManager.isNearEnd(4)) {
                    requestAutoQueueExtension()
                }
            } else {
                if (runtimeSettings.autoLoadMore && !queueManager.state.isUserQueue) {
                    _isBuffering.value = true
                    persistQueue()
                    requestAutoQueueExtension(advanceWhenLoaded = true)
                } else {
                    syncWantsPlay = false
                    if (queueManager.state.repeatMode == com.auralis.music.domain.model.RepeatMode.OFF) {
                        _isPlaying.value = false
                    }
                    persistQueue()
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
        // Observe preferences for the lifetime of the existing player, including background playback.
        connectedAudioDevices.addAll(audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).map { it.id })
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, android.os.Handler(android.os.Looper.getMainLooper()))
        appContext.contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, mediaVolumeObserver)
        scope.launch {
            settingsDataStore.settingsFlow.collect { settings ->
                val persistenceChanged = runtimeSettings.persistentQueue != settings.persistentQueue
                val autoLoadEnabled = (!settingsLoaded || !runtimeSettings.autoLoadMore) && settings.autoLoadMore
                settingsLoaded = true
                runtimeSettings = settings
                if (!settings.autoLoadMore) {
                    autoQueueJob?.cancel()
                    advanceAfterAutoLoad = false
                } else if (autoLoadEnabled && _isPlaying.value && queueManager.isNearEnd(4)) {
                    requestAutoQueueExtension()
                }
                if (persistenceChanged) persistQueue()
                pauseIfMediaMuted()
            }
        }
        scope.launch { _isPlaying.collect { if (it) pauseIfMediaMuted() } }
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
                pauseIfMediaMuted()
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

    fun applyVerifiedTrackMetadata(track: Track) {
        if (isGuestListenTogether.value) return
        if (queueManager.applyVerifiedMetadata(track)) {
            _queueState.value = queueManager.state
            persistQueue()
        }
        val active = _currentTrack.value
        if (active?.id == track.id) {
            _currentTrack.value = active.copy(album = track.album, thumbnail = track.thumbnail)
        }
    }

    fun play(
        track: Track,
        initialSeekMs: Long = 0L,
        requestId: Long = currentSessionId.incrementAndGet()
    ) {
        syncWantsPlay = true
        roomHoldActive = false
        seekWhileLoadingMs = null
        val tracker = PlaybackTimingTracker(
            requestId = requestId,
            trackTitle = track.title,
            t0TapMs = System.currentTimeMillis()
        )
        activeTimingTracker = tracker
        currentSessionId.set(requestId)
        autoQueueJob?.cancel()
        advanceAfterAutoLoad = false
        streamResolveJob?.cancel()
        nativeStreamPreparation?.cancel()
        nativeStreamPreparation = null

        // 1. Immediately and synchronously stop & flush all previous playback
        try {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        } catch (_: Exception) {}
        youTubeEngine.stop()

        audioLeadingSilenceProcessor.resetDetection()
        _audioLeadingSilenceMs.value = null

        val initialTrack = if (track.thumbnail.isBlank()) {
            val localArt = com.auralis.music.data.download.AuralisDownloadManager.getDownloadedArtworkFile(track.id)
            if (localArt != null && localArt.exists()) {
                track.copy(thumbnail = Uri.fromFile(localArt).toString())
            } else {
                track
            }
        } else {
            track
        }

        _currentTrack.value = initialTrack
        _playbackError.value = null
        _durationMs.value = track.duration * 1000L
        _playbackPositionMs.value = initialSeekMs
        _isBuffering.value = true
        _isPlaying.value = false
        publishDiscordPresenceNow()

        Log.d("AuralisPlayback", "[Play Request #$requestId] id=${track.id}, title='${track.title}', artist='${track.artist}', duration=${track.duration}s, initialSeek=${initialSeekMs}ms")

        // Start MediaSessionService synchronously for uninterrupted background audio and immediate foreground notification
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
                    val recordingId = AudioStreamResolver.resolvePlaybackVideoId(track)
                    if (recordingId != null) {
                        // Start both engines without waiting for a slow extraction.
                        // A ready native stream can still win while the web page loads.
                        val preparation = scope.async(Dispatchers.IO) {
                            AudioStreamResolver.resolveAudioStream(
                                videoId = track.id,
                                title = track.title,
                                artist = track.artist,
                                quality = currentAudioQuality,
                                context = appContext,
                                duration = track.duration
                            )
                        }
                        nativeStreamPreparation = preparation
                        directUrl = withTimeoutOrNull(250L) { preparation.await() }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("AuralisPlayback", "[Resolver] Stream resolve notice: ${e.message}")
                }
            }

            if (!isActive || currentSessionId.get() != requestId) {
                Log.d("AuralisPlayback", "[Resolver] Dropping resolved stream - job cancelled or stale requestId=$requestId vs ${currentSessionId.get()}")
                return@launch
            }
            tracker.tStreamResolvedMs = System.currentTimeMillis()

            fun startNativePlayback(url: String, startPositionMs: Long = initialSeekMs): Boolean {
                return try {
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
                        .setUri(url)
                        .setMediaId(effectiveMediaId)
                        .setCustomCacheKey(SongCache.keyFor(effectiveMediaId, url))
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(track.title)
                                .setArtist(track.artist)
                                .setAlbumTitle(displayAlbum)
                                .setArtworkUri(artworkUri)
                                .build()
                        )
                        .build()

                    if (!isActive || currentSessionId.get() != requestId) {
                        Log.d("AuralisPlayback", "[Resolver] Dropping ExoPlayer start - cancelled or stale requestId=$requestId vs ${currentSessionId.get()}")
                        return false
                    }

                    exoPlayer.setMediaItem(mediaItem)
                    // The saved preference can arrive before native playback begins, when
                    // setSkipSilenceEnabled has no active ExoPlayer to update yet.
                    exoPlayer.skipSilenceEnabled = runtimeSettings.skipSilence
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && _isSpeakerForced.value && preferredAudioDevice != null) {
                        exoPlayer.setPreferredAudioDevice(preferredAudioDevice)
                    }
                    exoPlayer.prepare()
                    tracker.tMediaItemPreparedMs = System.currentTimeMillis()
                    // A jump made while the song was loading wins over where it was asked to start.
                    val startAtMs = seekWhileLoadingMs ?: startPositionMs
                    seekWhileLoadingMs = null
                    if (startAtMs > 0) {
                        exoPlayer.seekTo(startAtMs)
                    }
                    if (syncWantsPlay) {
                        exoPlayer.play()
                    } else {
                        // A Listen Together host paused while this song was loading: stay paused.
                        exoPlayer.pause()
                        _isPlaying.value = false
                    }
                    true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("AuralisPlayback", "[Audio Engine] ExoPlayer start failed, falling back to YouTube engine: ${e.message}")
                    false
                }
            }

            if (!directUrl.isNullOrBlank() && startNativePlayback(directUrl)) return@launch

            // Fallback to hardened YouTube web engine
            if (!isActive || currentSessionId.get() != requestId) {
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
                val startAtMs = seekWhileLoadingMs ?: initialSeekMs
                seekWhileLoadingMs = null
                youTubeEngine.loadVideo(effectiveId, startAtMs, requestId)
                if (!syncWantsPlay) youTubeEngine.pause()
                nativeStreamPreparation?.let { preparation ->
                    val readyStream = awaitNativeDuringWebStartup(preparation, youTubeEngine.isPlaying)
                    if (!readyStream.isNullOrBlank() && isActive &&
                        currentSessionId.get() == requestId && !youTubeEngine.isPlaying.value) {
                        val pendingPosition = seekWhileLoadingMs ?: youTubeEngine.playbackPositionMs.value
                        tracker.tStreamResolvedMs = System.currentTimeMillis()
                        Log.d("AuralisPlayback", "[Startup Race] Native stream ready before web audio for '${track.title}' [reqId=$requestId]")
                        if (!startNativePlayback(readyStream, pendingPosition)) {
                            isUsingExoPlayer = false
                            tracker.streamEngine = "YouTube Web Engine"
                            youTubeEngine.loadVideo(effectiveId, pendingPosition, requestId)
                            if (!syncWantsPlay) youTubeEngine.pause()
                        }
                    }
                }
            } else {
                Log.e("AuralisPlayback", "[Audio Engine] Failed to resolve playable YouTube stream for Spotify track '${track.title}' (${track.id})")
                _playbackError.value = "No playable match found for '${track.title}' by ${track.artist} on YouTube Music"
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
                                .setCustomCacheKey(SongCache.keyFor(effectiveMediaId, streamUrl.toString()))
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
        // Guests never preload the next song: they only ever play what the host plays.
        if (stopAtEndOfTrack || isGuestListenTogether.value) {
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

    private val queueGenerationId = java.util.concurrent.atomic.AtomicLong(0L)
    private var autoQueueJob: Job? = null
    private var advanceAfterAutoLoad = false
    private val radioHistory by lazy {
        com.auralis.music.data.repository.HistoryRepositoryImpl(
            database.trackDao(), database.historyDao(), database.playCountDao(), database.playbackEventDao()
        )
    }
    private val radioClient by lazy { com.auralis.music.data.network.InnerTubeClient() }

    // Queue extension belongs to the existing player so it survives Activity/task removal.
    fun requestAutoQueueExtension(advanceWhenLoaded: Boolean = false) {
        if (!runtimeSettings.autoLoadMore || queueManager.state.isUserQueue || isGuestListenTogether.value) return
        val seed = queueManager.state.currentTrack ?: return
        advanceAfterAutoLoad = advanceAfterAutoLoad || advanceWhenLoaded
        if (autoQueueJob?.isActive == true) return
        val genId = queueGenerationId.get()
        autoQueueJob = scope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(5000L) { com.auralis.music.data.network.LocalizedContent.run { radioClient.getRadioTracks(seed.id, seed.artist, seed.title) } } ?: emptyList()
                }
                ensureActive()
                if (!runtimeSettings.autoLoadMore || queueGenerationId.get() != genId ||
                    queueManager.state.isUserQueue || isGuestListenTogether.value) return@launch

                val existingIds = queueManager.state.queue.map { it.id }.toSet()
                var uniqueTracks = tracks.filter { it.id !in existingIds && it.id != seed.id }

                if (uniqueTracks.size < 14) {
                    val fallbackCandidates = withContext(Dispatchers.IO) {
                        try {
                            val candidates = radioHistory.getRecentHeavyRotation() +
                                radioHistory.getHistory().first().map { it.track } + radioHistory.getLikedSeeds()
                            val fromHistory = candidates.filter { it.id != seed.id && it.id !in existingIds && it.id !in uniqueTracks.map { u -> u.id } }
                            if (fromHistory.isNotEmpty()) {
                                fromHistory.shuffled().take(14 - uniqueTracks.size)
                            } else {
                                withTimeoutOrNull(2500L) {
                                    radioClient.search("${seed.artist} songs", com.auralis.music.data.network.InnerTubeClient.FILTER_SONGS)
                                        .songs.filter { it.id != seed.id && it.id !in existingIds && it.id !in uniqueTracks.map { u -> u.id } }
                                        .take(14 - uniqueTracks.size)
                                } ?: emptyList()
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    uniqueTracks = uniqueTracks + fallbackCandidates
                }

                val batch = uniqueTracks.take(14)
                if (batch.isNotEmpty()) {
                    appendTracks(batch)
                    Log.d("AuralisPlayback", "[AutoRadio] Appended ${batch.size} tracks to queue. New queueSize=${queueManager.state.queue.size}")
                }

                if (advanceAfterAutoLoad) {
                    advanceAfterAutoLoad = false
                    val nextTrack = queueManager.advanceNext()
                    if (nextTrack != null) {
                        _queueState.value = queueManager.state
                        play(nextTrack, initialSeekMs = 0L)
                        syncUpcomingGaplessTrack()
                        persistQueue()
                    } else {
                        pause()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AuralisPlayback", "[AutoRadio] Queue extension failed: ${e.message}")
                if (advanceAfterAutoLoad) pause()
            } finally {
                if (coroutineContext[Job] == autoQueueJob) advanceAfterAutoLoad = false
            }
        }
    }

    private var persistJob: Job? = null

    fun persistQueue() {
        if (!queueRestoreComplete) return
        val qState = queueManager.state
        val pos = _playbackPositionMs.value
        persistJob?.cancel()
        persistJob = scope.launch(Dispatchers.IO) {
            try {
                // Serialize save/clear operations and re-read the preference inside the lock so
                // an older save can never resurrect a queue after persistence is disabled.
                queuePersistenceMutex.withLock {
                    if (settingsDataStore.settingsFlow.first().persistentQueue) {
                        queueDataStore.saveQueue(
                            tracks = qState.queue,
                            currentIndex = qState.currentIndex,
                            positionMs = pos,
                            isUserQueue = qState.isUserQueue,
                            isShuffled = qState.isShuffled,
                            repeatMode = qState.repeatMode.name
                        )
                    } else {
                        queueDataStore.clearPersistedQueue()
                    }
                }
            } catch (e: CancellationException) {
                throw e
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
        val wasEnabled = isGaplessEnabled
        isGaplessEnabled = enabled
        Log.d("AuralisPlayback", "[Settings] Gapless playback set to: $enabled")
        if (!enabled && isUsingExoPlayer) {
            if (exoPlayer.mediaItemCount > 1) {
                exoPlayer.removeMediaItem(1)
                enqueuedNextTrack = null
            }
        }
        if (enabled && !wasEnabled && isUsingExoPlayer) {
            syncUpcomingGaplessTrack()
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

    /**
     * Whether playback should start once the song now loading is ready. False after a Listen
     * Together host pauses mid-load, so a guest doesn't start playing on its own when it finishes.
     */
    /**
     * A jump made while the song is still being fetched. Seeking a player that hasn't loaded the
     * song yet was thrown away when loading finished (it starts at the requested start, usually
     * 0:00), so a listener played the start while the host had already jumped to the middle.
     */
    @Volatile private var seekWhileLoadingMs: Long? = null

    /** True if the song is still loading and [positionMs] was saved to apply once it's ready. */
    private fun rememberSeekWhileLoading(positionMs: Long): Boolean {
        if (streamResolveJob?.isActive != true) return false
        seekWhileLoadingMs = positionMs
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] Song still loading: will start at ${positionMs}ms")
        return true
    }

    private val _syncWantsPlay = MutableStateFlow(false)
    /** User playback intent survives a temporary audio-focus pause. */
    val playbackRequested: StateFlow<Boolean> = _syncWantsPlay.asStateFlow()
    private var syncWantsPlay: Boolean
        get() = _syncWantsPlay.value
        set(value) { _syncWantsPlay.value = value }

    /** A Listen Together host is holding this song at the start until the guests have it loaded. */
    private val _roomHoldActive = MutableStateFlow(false)
    private var roomHoldActive: Boolean
        get() = _roomHoldActive.value
        set(value) { _roomHoldActive.value = value }

    /**
     * Changes to what the user means playback to do that [isPlaying] alone doesn't show (a hold
     * released, a pause while a song loads). The Listen Together host re-broadcasts on these;
     * missing them left guests paused while the host played.
     */
    val playIntentChanges: kotlinx.coroutines.flow.Flow<Pair<Boolean, Boolean>> =
        kotlinx.coroutines.flow.combine(_syncWantsPlay, _roomHoldActive) { wants, held -> wants to held }

    /**
     * Holds the song that just started at 0:00 instead of playing it, so guests can finish loading
     * it and everyone starts from the very beginning together. [releaseHeldStart] starts it.
     */
    fun holdStartForRoom() {
        roomHoldActive = true
        syncWantsPlay = false
        if (streamResolveJob?.isActive == true) return // it will load paused
        // Loaded already (a cached song starts at once): stop it at 0:00 even if it hasn't
        // reported playing yet, or the host plays on while telling the room it's held.
        if (isUsingExoPlayer) {
            exoPlayer.pause()
            exoPlayer.seekTo(0L)
        } else {
            youTubeEngine.pause()
            youTubeEngine.seekTo(0L)
        }
        _playbackPositionMs.value = 0L
        _isPlaying.value = false
    }

    /**
     * Play/pause from the notification, lock screen or a headset. For a Listen Together guest it
     * goes to the room (when the host allows it) like the in-app button; the system's own pauses
     * (headphones unplugged, a call) don't come through here, so they stay on this phone.
     */
    fun playFromControls(play: Boolean) {
        if (!isGuestListenTogether.value) {
            if (play) resume() else pause()
            return
        }
        forwardGuestControl(if (play) com.auralis.music.data.sync.GuestCommand.PLAY else com.auralis.music.data.sync.GuestCommand.PAUSE)
    }

    /** The held song is loaded and can start the instant it's released (not still fetching or buffering). */
    fun isReadyToStart(): Boolean {
        if (streamResolveJob?.isActive == true || _isBuffering.value) return false
        return if (isUsingExoPlayer) exoPlayer.playbackState == Player.STATE_READY else youTubeEngine.hasActiveStream()
    }

    /** Starts a song held by [holdStartForRoom]; does nothing if the host paused or moved on meanwhile. */
    fun releaseHeldStart() {
        if (!roomHoldActive) return
        roomHoldActive = false
        syncResume()
    }

    /**
     * Set while this user is a guest in a Listen Together room whose host lets guests control
     * playback: a guest's own taps (play/pause, next, previous, seek) go to the host instead of
     * being ignored. System pauses (headphones out, a call) never reach it, so they stay local.
     */
    @Volatile var guestControlForwarder: ((com.auralis.music.data.sync.GuestCommand) -> Unit)? = null

    /** A guest's "add to queue" / "play next": offered to the room instead of this phone's queue copy. */
    @Volatile var guestSongSuggester: ((Track) -> Unit)? = null

    /**
     * Whether the user means playback to be running, even while it briefly rebuffers after a seek.
     * The room is told this rather than [isPlaying], so a host's rebuffer isn't broadcast as a pause.
     */
    fun intendsToPlay(): Boolean = when {
        roomHoldActive -> false
        streamResolveJob?.isActive == true -> syncWantsPlay
        isUsingExoPlayer -> exoPlayer.playWhenReady && exoPlayer.playbackState != Player.STATE_ENDED
        else -> _isPlaying.value || (_isBuffering.value && syncWantsPlay)
    }

    private fun forwardGuestControl(type: String, positionMs: Long = 0L) {
        guestControlForwarder?.invoke(
            com.auralis.music.data.sync.GuestCommand(type = type, positionMs = positionMs, seq = System.currentTimeMillis())
        )
    }

    /** Sends user actions to Discord immediately instead of waiting for the state collector. */
    private fun publishDiscordPresenceNow() {
        com.auralis.music.data.network.discord.DiscordGatewayManager.getInstance(appContext)
            .onPlaybackStateChanged(
                track = _currentTrack.value,
                isPlaying = _isPlaying.value,
                isBuffering = _isBuffering.value,
                positionMs = _playbackPositionMs.value,
                durationMs = _durationMs.value
            )
    }

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

    /**
     * The host's queue changed (a song added, played next, reordered) without a song change:
     * update this guest's queue in place, keeping the song that's playing untouched.
     */
    fun syncQueue(newQueue: List<Track>, currentIndex: Int) {
        if (newQueue.isEmpty()) return
        val cur = _currentTrack.value
        val q = newQueue.toMutableList()
        // Keep the exact copy playing here (it may be the host's matched video id).
        if (cur != null && currentIndex in q.indices) q[currentIndex] = cur
        _queueState.value = queueManager.setQueue(q, currentIndex.coerceIn(0, q.lastIndex), preserveOrderIfSame = false, isUserQueue = true)
    }

    fun syncResume() {
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] syncResume from host")
        syncWantsPlay = true
        // Still fetching the song: it starts by itself when ready. Calling play() here would
        // restart the fetch and put this guest further behind.
        if (streamResolveJob?.isActive == true) return
        val curTrack = _currentTrack.value ?: return
        if (isUsingExoPlayer && exoPlayer.mediaItemCount > 0) {
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                exoPlayer.prepare()
            }
            exoPlayer.play()
            _isPlaying.value = true
            publishDiscordPresenceNow()
        } else if (!isUsingExoPlayer && youTubeEngine.hasActiveStream()) {
            youTubeEngine.play()
            _isPlaying.value = true
            publishDiscordPresenceNow()
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
        syncWantsPlay = false
        if (isUsingExoPlayer) {
            exoPlayer.pause()
        }
        youTubeEngine.pause()
        _isPlaying.value = false
        publishDiscordPresenceNow()
    }

    fun syncSeek(positionMs: Long) {
        val bounded = positionMs.coerceAtLeast(0L)
        _playbackPositionMs.value = bounded
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] syncSeek from host: ${bounded}ms")
        if (rememberSeekWhileLoading(bounded)) return
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
        isUserQueue: Boolean = false,
        initialPositionMs: Long = 0L,
        preserveQueueSource: Boolean = false
    ) {
        if (isGuestListenTogether.value) {
            Log.d("AuralisPlayback", "[AuralisAudioPlayer] playTrack blocked - user is listener in Listen Together room")
            guestControlForwarder?.invoke(
                com.auralis.music.data.sync.GuestCommand(
                    type = com.auralis.music.data.sync.GuestCommand.PLAY_TRACK,
                    seq = System.currentTimeMillis(),
                    track = track
                )
            )
            return
        }
        val isSingleSongSelection = !preserveQueueSource && !isUserQueue && (newQueue.isEmpty() || newQueue.size == 1)
        if (isSingleSongSelection) {
            queueGenerationId.incrementAndGet()
            autoQueueJob?.cancel()
            advanceAfterAutoLoad = false
        }
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] playTrack: title='${track.title}', artist='${track.artist}', queueSize=${newQueue.size}, isSingleSong=$isSingleSongSelection, initialPos=${initialPositionMs}ms")
        
        val targetQueue = if (isSingleSongSelection) {
            listOf(track)
        } else if (newQueue.isNotEmpty()) {
            newQueue
        } else {
            queueManager.state.queue
        }
        val targetIndex = if (isSingleSongSelection) {
            0
        } else if (startIndex in targetQueue.indices && targetQueue[startIndex].id == track.id) {
            startIndex
        } else {
            targetQueue.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: startIndex.coerceIn(0, (targetQueue.size - 1).coerceAtLeast(0))
        }

        val isSameQueue = queueManager.state.queue.isNotEmpty() &&
                          targetQueue.map { it.id } == queueManager.state.queue.map { it.id }
        val qState = queueManager.setQueue(targetQueue, targetIndex, preserveOrderIfSame = isSameQueue, isUserQueue = isUserQueue)
        _queueState.value = qState

        play(track, initialSeekMs = initialPositionMs)
        syncUpcomingGaplessTrack()
        persistQueue()
        if (isSingleSongSelection && runtimeSettings.autoLoadMore && !queueManager.state.isUserQueue) {
            requestAutoQueueExtension()
        }
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
        // The user pressed play: that overrides a room hold, and a song still loading starts when ready.
        roomHoldActive = false
        syncWantsPlay = true
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] resume() called (isUsingExo=$isUsingExoPlayer, mediaItems=${exoPlayer.mediaItemCount}, track=${curTrack.title}, seek=${_playbackPositionMs.value}ms)")

        startMediaService(AuralisMediaService.ACTION_START)

        if (isUsingExoPlayer && exoPlayer.mediaItemCount > 0) {
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                exoPlayer.prepare()
            }
            exoPlayer.play()
            _isPlaying.value = true
            publishDiscordPresenceNow()
        } else if (!isUsingExoPlayer && youTubeEngine.hasActiveStream()) {
            youTubeEngine.play()
            _isPlaying.value = true
            publishDiscordPresenceNow()
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
        advanceAfterAutoLoad = false
        if (isGuestListenTogether.value) {
            Log.d("AuralisPlayback", "[AuralisAudioPlayer] pause() blocked - user is listener in Listen Together room")
            return
        }
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] pause() called (isUsingExo=$isUsingExoPlayer, track=${_currentTrack.value?.title})")
        roomHoldActive = false
        syncWantsPlay = false
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
        publishDiscordPresenceNow()
        // The running service observes isPlaying; pausing must not start a new foreground service.
        persistQueue()
    }

    fun togglePlayPause() {
        if (isGuestListenTogether.value) {
            Log.d("AuralisPlayback", "[AuralisAudioPlayer] togglePlayPause() blocked - user is listener in Listen Together room")
            forwardGuestControl(
                if (intendsToPlay()) com.auralis.music.data.sync.GuestCommand.PAUSE else com.auralis.music.data.sync.GuestCommand.PLAY
            )
            return
        }
        if (_isPlaying.value) {
            pause()
        } else {
            resume()
        }
    }

    /** Seeks the user made on this device (not ones applied from a Listen Together room). */
    private val _userSeekEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val userSeekEvents: SharedFlow<Long> = _userSeekEvents.asSharedFlow()

    fun seekTo(positionMs: Long) {
        if (isGuestListenTogether.value) {
            Log.d("AuralisPlayback", "[AuralisAudioPlayer] seekTo() blocked - user is listener in Listen Together room")
            forwardGuestControl(com.auralis.music.data.sync.GuestCommand.SEEK, positionMs)
            return
        }
        val bounded = positionMs.coerceAtLeast(0L)
        _userSeekEvents.tryEmit(bounded)
        _playbackPositionMs.value = bounded
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] seekTo(${bounded}ms)")
        if (rememberSeekWhileLoading(bounded)) {
            persistQueue()
            return
        }
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
            forwardGuestControl(com.auralis.music.data.sync.GuestCommand.NEXT)
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
                if (runtimeSettings.autoLoadMore && !queueManager.state.isUserQueue && queueManager.isNearEnd(4)) {
                    requestAutoQueueExtension()
                }
                return true
            }
        }
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] next() reached the end of the queue")
        if (runtimeSettings.autoLoadMore && !queueManager.state.isUserQueue) {
            requestAutoQueueExtension(advanceWhenLoaded = true)
        } else {
            pause()
        }
        return false
    }

    fun previous() {
        if (isGuestListenTogether.value) {
            Log.d("AuralisPlayback", "[AuralisAudioPlayer] previous() blocked - user is listener in Listen Together room")
            forwardGuestControl(com.auralis.music.data.sync.GuestCommand.PREVIOUS)
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
        if (isGuestListenTogether.value) {
            tracks.forEach { guestSongSuggester?.invoke(it) }
            return
        }
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
        if (isGuestListenTogether.value) {
            tracks.forEach { guestSongSuggester?.invoke(it) }
            return
        }
        val qState = queueManager.playNext(tracks)
        _queueState.value = qState
        syncUpcomingGaplessTrack()
        persistQueue()
        if (_currentTrack.value == null) {
            playTrack(tracks.first(), tracks, 0, isUserQueue = true)
        }
    }

    fun clearQueue() {
        syncWantsPlay = false
        val qState = queueManager.clearQueue()
        _queueState.value = qState
        _currentTrack.value = null
        syncUpcomingGaplessTrack()
        persistQueue()
    }

    fun clearCurrentTrack() {
        syncWantsPlay = false
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
            if (existing == null) trackDao.upsertTrack(track.toEntity())
            trackDao.setFavorite(track.id, nextFav, if (nextFav) System.currentTimeMillis() else null)
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
        syncWantsPlay = false
        autoQueueJob?.cancel()
        advanceAfterAutoLoad = false
        Log.d("AuralisPlayback", "[AuralisAudioPlayer] stop() called -> flushing streams")
        cancelSleepTimer()
        currentSessionId.incrementAndGet()
        streamResolveJob?.cancel()
        streamResolveJob = null
        _isPlaying.value = false
        _isBuffering.value = false
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
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        appContext.contentResolver.unregisterContentObserver(mediaVolumeObserver)
        scope.cancel()
        currentSessionId.incrementAndGet()
        streamResolveJob?.cancel()
        streamResolveJob = null
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
