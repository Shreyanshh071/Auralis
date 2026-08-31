package com.auralis.music.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import coil.ImageLoader
import coil.request.ImageRequest
import com.auralis.music.MainActivity
import com.auralis.music.R
import com.auralis.music.data.service.AuralisAudioPlayer
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.util.ArtworkProcessor
import com.auralis.music.util.MasterArtworkResolver
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pure native AndroidX Media3 MediaSessionService providing:
 * - Immediate synchronous startForeground() execution in onCreate() and onStartCommand().
 * - Full Android 13/14 Quick Settings & Lockscreen System Media Controls:
 *   App icon badge at top-left, interactive seekbar, previous/next, heart/favorite, repeat, and play/pause.
 */
@OptIn(UnstableApi::class)
class AuralisMediaService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastArtworkUrl: String? = null
    private var currentArtworkBitmap: Bitmap? = null
    private val sessionListeners = java.util.concurrent.CopyOnWriteArrayList<Player.Listener>()
    private var currentActiveMediaItem: androidx.media3.common.MediaItem? = null
    private var currentActiveMetadata: androidx.media3.common.MediaMetadata? = null

    companion object {
        const val ACTION_PLAY = "com.auralis.music.ACTION_PLAY"
        const val ACTION_PAUSE = "com.auralis.music.ACTION_PAUSE"
        const val ACTION_TOGGLE = "com.auralis.music.ACTION_TOGGLE"
        const val ACTION_NEXT = "com.auralis.music.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.auralis.music.ACTION_PREVIOUS"
        const val ACTION_SEEK_BACK = "com.auralis.music.ACTION_SEEK_BACK"
        const val ACTION_SEEK_FORWARD = "com.auralis.music.ACTION_SEEK_FORWARD"
        const val ACTION_TOGGLE_FAVORITE = "com.auralis.music.ACTION_TOGGLE_FAVORITE"
        const val ACTION_TOGGLE_REPEAT = "com.auralis.music.ACTION_TOGGLE_REPEAT"
        const val ACTION_STOP = "com.auralis.music.ACTION_STOP"

        const val CUSTOM_COMMAND_SET_SPEED = "com.auralis.music.SET_PLAYBACK_SPEED"
        const val EXTRA_SPEED_VALUE = "extra_speed_value"

        const val CHANNEL_ID = "auralis_media_playback_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        // 1. Create notification channel synchronously
        createNotificationChannel()

        val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
        val player = audioPlayer.exoPlayer

        // Configure system activity launch intent for lockscreen / notification taps
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Wrap player in ForwardingPlayer so Android 13/14 system UI always exposes Previous/Next/Seek commands
        val forwardingPlayer = object : ForwardingPlayer(player) {
            private val wrappedListeners = java.util.concurrent.ConcurrentHashMap<Player.Listener, Player.Listener>()

            override fun addListener(listener: Player.Listener) {
                sessionListeners.add(listener)
                val wrapped = object : Player.Listener {
                    override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                        // Prevent underlying stream changes from overwriting studio artwork with empty metadata
                        val active = currentActiveMetadata
                        val metaToDispatch = if (mediaMetadata.artworkData == null && mediaMetadata.artworkUri == null && active != null) {
                            active
                        } else {
                            active ?: mediaMetadata
                        }
                        listener.onMediaMetadataChanged(metaToDispatch)
                    }

                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        val itemToDispatch = currentActiveMediaItem ?: mediaItem
                        listener.onMediaItemTransition(itemToDispatch, reason)
                    }

                    override fun onPlaylistMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                        listener.onPlaylistMetadataChanged(currentActiveMetadata ?: mediaMetadata)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        listener.onIsPlayingChanged(isPlaying)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        listener.onPlaybackStateChanged(playbackState)
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        listener.onPlayWhenReadyChanged(playWhenReady, reason)
                    }

                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        listener.onPositionDiscontinuity(oldPosition, newPosition, reason)
                    }

                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        listener.onTimelineChanged(timeline, reason)
                    }
                }
                wrappedListeners[listener] = wrapped
                super.addListener(wrapped)
            }

            override fun removeListener(listener: Player.Listener) {
                sessionListeners.remove(listener)
                val wrapped = wrappedListeners.remove(listener) ?: listener
                super.removeListener(wrapped)
            }

            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_GET_METADATA)
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_STOP)
                    .add(Player.COMMAND_GET_TIMELINE)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_GET_TIMELINE -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun play() {
                audioPlayer.resume()
            }

            override fun pause() {
                audioPlayer.pause()
            }

            override fun getPlayWhenReady(): Boolean {
                return audioPlayer.isPlaying.value
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady) {
                    audioPlayer.resume()
                } else {
                    audioPlayer.pause()
                }
            }

            override fun getPlaybackState(): Int {
                return if (audioPlayer.currentTrack.value != null) Player.STATE_READY else Player.STATE_IDLE
            }

            override fun seekToPrevious() {
                audioPlayer.previous()
            }

            override fun seekToNext() {
                audioPlayer.next()
            }

            override fun seekTo(positionMs: Long) {
                audioPlayer.seekTo(positionMs)
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                audioPlayer.seekTo(positionMs)
            }

            override fun getCurrentTimeline(): androidx.media3.common.Timeline {
                val track = audioPlayer.currentTrack.value
                val durMs = audioPlayer.durationMs.value.takeIf { it > 0 } ?: ((track?.duration ?: 0L) * 1000L)
                val durationUs = durMs * 1000L
                val currentItem = currentMediaItem
                return if (track != null) SingleTrackTimeline(durationUs, currentItem) else androidx.media3.common.Timeline.EMPTY
            }

            override fun getCurrentMediaItem(): androidx.media3.common.MediaItem? {
                return currentActiveMediaItem ?: super.getCurrentMediaItem()
            }

            override fun getMediaMetadata(): androidx.media3.common.MediaMetadata {
                return currentActiveMetadata ?: super.getMediaMetadata()
            }

            override fun getPlaylistMetadata(): androidx.media3.common.MediaMetadata {
                return currentActiveMetadata ?: super.getPlaylistMetadata()
            }

            override fun isCurrentMediaItemSeekable(): Boolean = true
            override fun isCurrentMediaItemDynamic(): Boolean = false
            override fun isCurrentMediaItemLive(): Boolean = false
            override fun getCurrentMediaItemIndex(): Int = 0
            override fun getCurrentPeriodIndex(): Int = 0

            override fun getDuration(): Long {
                val d = audioPlayer.durationMs.value
                val trackDur = (audioPlayer.currentTrack.value?.duration ?: 0L) * 1000L
                return if (d > 0) d else if (trackDur > 0) trackDur else super.getDuration()
            }

            override fun getCurrentPosition(): Long {
                val p = audioPlayer.playbackPositionMs.value
                return if (p > 0) p else super.getCurrentPosition()
            }

            override fun isPlaying(): Boolean {
                return audioPlayer.isPlaying.value
            }
        }

        // 2. Build Custom MediaSession with custom actions and command handling
        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(AuralisSessionCallback())
            .setCustomLayout(buildCustomLayout(audioPlayer.isFavorite.value))
            .build()

        // 3. Start foreground with MediaSession token attached
        startForegroundSafely()

        fun dispatchPlaybackState(isPlaying: Boolean) {
            val state = if (audioPlayer.currentTrack.value != null) Player.STATE_READY else Player.STATE_IDLE
            for (listener in sessionListeners) {
                try {
                    listener.onIsPlayingChanged(isPlaying)
                    listener.onPlayWhenReadyChanged(isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    listener.onPlaybackStateChanged(state)
                } catch (_: Exception) {}
            }
        }

        // 3. Keep MediaSession callback synced with current audio playback states
        serviceScope.launch {
            audioPlayer.isPlaying.collectLatest { isPlaying ->
                val track = audioPlayer.currentTrack.value
                val isFav = audioPlayer.isFavorite.value
                updateNotification(track, isPlaying, isFav)
                dispatchPlaybackState(isPlaying)
            }
        }

        serviceScope.launch {
            audioPlayer.isFavorite.collectLatest { isFav ->
                val track = audioPlayer.currentTrack.value
                val isPlaying = audioPlayer.isPlaying.value
                updateNotification(track, isPlaying, isFav)
                mediaSession?.setCustomLayout(buildCustomLayout(isFav))
            }
        }

        // Observe track changes to update notification and system MediaSession dynamically
        serviceScope.launch {
            audioPlayer.currentTrack.collectLatest { track ->
                if (track != null) {
                    val rawUrl = getHighResArtworkUrl(track.thumbnail) ?: track.thumbnail
                    val cachedBitmap = if (!rawUrl.isNullOrBlank()) {
                        imageLoader.memoryCache?.get(coil.memory.MemoryCache.Key(rawUrl))?.bitmap
                            ?: imageLoader.memoryCache?.get(coil.memory.MemoryCache.Key(track.thumbnail ?: ""))?.bitmap
                    } else null

                    var localArtworkUri: android.net.Uri? = null
                    if (cachedBitmap != null) {
                        val processed = ArtworkProcessor.processForMediaNotification(cachedBitmap, targetSize = 600)
                        currentArtworkBitmap = processed
                        localArtworkUri = ArtworkProcessor.saveMasterArtworkToCache(applicationContext, processed)
                    } else {
                        currentArtworkBitmap = null
                    }

                    val isOfficialCdn = !rawUrl.isNullOrBlank() &&
                            (rawUrl.contains("googleusercontent.com") || rawUrl.contains("ggpht.com") ||
                             rawUrl.contains("mzstatic.com") || rawUrl.contains("scdn.co") ||
                             rawUrl.contains("jiosaavn.com") || rawUrl.contains("saavncdn.com") ||
                             rawUrl.contains("ytimg.com") || rawUrl.contains("youtube.com"))
                    val initialArtworkUri = localArtworkUri ?: if (isOfficialCdn) android.net.Uri.parse(rawUrl) else null

                    val initialMetaBuilder = androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setArtworkUri(initialArtworkUri)

                    if (currentArtworkBitmap != null) {
                        initialMetaBuilder.setArtworkData(
                            ArtworkProcessor.toByteArray(currentArtworkBitmap!!, quality = 92),
                            androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER
                        )
                    }

                    val initialMeta = initialMetaBuilder.build()
                    val initialItem = androidx.media3.common.MediaItem.Builder()
                        .setMediaId(track.id)
                        .setMediaMetadata(initialMeta)
                        .build()

                    currentActiveMetadata = initialMeta
                    currentActiveMediaItem = initialItem

                    try {
                        if (player.currentMediaItem?.mediaId != track.id && player.currentMediaItem?.localConfiguration == null) {
                            player.setMediaItem(initialItem)
                        }
                    } catch (_: Exception) {}

                    withContext(Dispatchers.Main) {
                        for (listener in sessionListeners) {
                            try {
                                listener.onMediaMetadataChanged(initialMeta)
                                listener.onPlaylistMetadataChanged(initialMeta)
                                listener.onMediaItemTransition(initialItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                            } catch (_: Exception) {}
                        }
                        dispatchPlaybackState(audioPlayer.isPlaying.value)
                    }
                }
                updateNotification(track, audioPlayer.isPlaying.value, audioPlayer.isFavorite.value)
            }
        }

        // Observe playing state changes
        serviceScope.launch {
            audioPlayer.isPlaying.collectLatest { isPlaying ->
                withContext(Dispatchers.Main) {
                    dispatchPlaybackState(isPlaying)
                }
                updateNotification(audioPlayer.currentTrack.value, isPlaying, audioPlayer.isFavorite.value)
            }
        }

        // Observe favorite changes to update custom layout in Android 13/14 System Media Card
        serviceScope.launch {
            audioPlayer.isFavorite.collectLatest { isFav ->
                try {
                    mediaSession?.setCustomLayout(buildCustomLayout(isFav))
                } catch (_: Exception) {}
                updateNotification(audioPlayer.currentTrack.value, audioPlayer.isPlaying.value, isFav)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
        when (intent?.action) {
            ACTION_PLAY -> audioPlayer.resume()
            ACTION_PAUSE -> audioPlayer.pause()
            ACTION_TOGGLE -> audioPlayer.togglePlayPause()
            ACTION_NEXT -> audioPlayer.next()
            ACTION_PREVIOUS -> audioPlayer.previous()
            ACTION_SEEK_BACK -> audioPlayer.seekBackward(10000L)
            ACTION_SEEK_FORWARD -> audioPlayer.seekForward(10000L)
            ACTION_TOGGLE_FAVORITE -> audioPlayer.toggleFavorite()
            ACTION_TOGGLE_REPEAT -> audioPlayer.toggleRepeat()
            ACTION_STOP -> {
                audioPlayer.stop()
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {}
                try {
                    val notifManager = NotificationManagerCompat.from(this)
                    notifManager.cancel(NOTIFICATION_ID)
                } catch (_: Exception) {}
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun buildCustomLayout(isFavorite: Boolean): List<CommandButton> {
        val favButton = CommandButton.Builder()
            .setDisplayName(if (isFavorite) "Favorited" else "Favorite")
            .setIconResId(if (isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
            .setEnabled(true)
            .build()

        val repeatButton = CommandButton.Builder()
            .setDisplayName("Repeat")
            .setIconResId(R.drawable.ic_repeat)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY))
            .setEnabled(true)
            .build()

        return listOf(favButton, repeatButton)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auralis Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback and lock-screen controls for Auralis"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundSafely() {
        val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
        val notif = buildNotification(audioPlayer.currentTrack.value, audioPlayer.isPlaying.value, audioPlayer.isFavorite.value, currentArtworkBitmap)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
            Log.d("AuralisPlayback", "[AuralisMediaService] startForeground() registered successfully")
        } catch (e: Exception) {
            Log.e("AuralisPlayback", "[AuralisMediaService] startForeground() error: ${e.message}", e)
        }
    }

    private fun buildNotification(track: Track?, isPlaying: Boolean, isFavorite: Boolean, artwork: Bitmap?): android.app.Notification {
        val title = track?.title ?: "Auralis Music"
        val artist = track?.artist ?: "Playing in Background"

        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY

        val favPendingIntent = PendingIntent.getService(
            this, 6, Intent(this, AuralisMediaService::class.java).apply { action = ACTION_TOGGLE_FAVORITE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val prevPendingIntent = PendingIntent.getService(
            this, 1, Intent(this, AuralisMediaService::class.java).apply { action = ACTION_PREVIOUS },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playPausePendingIntent = PendingIntent.getService(
            this, 2, Intent(this, AuralisMediaService::class.java).apply { action = playPauseAction },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextPendingIntent = PendingIntent.getService(
            this, 3, Intent(this, AuralisMediaService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val repeatPendingIntent = PendingIntent.getService(
            this, 7, Intent(this, AuralisMediaService::class.java).apply { action = ACTION_TOGGLE_REPEAT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(1, 2, 3)

        mediaSession?.sessionCompatToken?.let { token ->
            mediaStyle.setMediaSession(token)
        }

        val favIcon = if (isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline

        // Extract dominant/vibrant artwork color to dynamically tint media notification background on all OEM skins
        val dominantColorInt = artwork?.let { bmp ->
            try {
                val palette = androidx.palette.graphics.Palette.from(bmp).generate()
                palette.vibrantSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb
                    ?: palette.darkVibrantSwatch?.rgb
                    ?: palette.mutedSwatch?.rgb
            } catch (_: Exception) {
                null
            }
        } ?: android.graphics.Color.parseColor("#1E2430")

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(mediaStyle)
            .setColor(dominantColorInt)
            .setColorized(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(favIcon, "Favorite", favPendingIntent)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
            .addAction(R.drawable.ic_repeat, "Repeat", repeatPendingIntent)

        if (artwork != null) {
            builder.setLargeIcon(artwork)
        }

        return builder.build()
    }

    private val imageLoader by lazy {
        ImageLoader.Builder(applicationContext)
            .respectCacheHeaders(false)
            .build()
    }

    private fun updateNotification(track: Track?, isPlaying: Boolean, isFavorite: Boolean = false) {
        val notifManager = NotificationManagerCompat.from(this)
        val notif = buildNotification(track, isPlaying, isFavorite, currentArtworkBitmap)
        try {
            notifManager.notify(NOTIFICATION_ID, notif)
        } catch (_: SecurityException) {}

        // Asynchronously fetch highest-definition studio master artwork and process for edge-to-edge notification panel
        val thumbUrl = track?.thumbnail
        val trackTitle = track?.title
        val trackArtist = track?.artist
        val cacheKey = "${trackArtist.orEmpty()} - ${trackTitle.orEmpty()} - $thumbUrl"
        if (cacheKey != lastArtworkUrl) {
            lastArtworkUrl = cacheKey
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val masterUrl = MasterArtworkResolver.resolveMasterArtworkUrl(trackTitle, trackArtist, thumbUrl)
                    val candidates = (listOfNotNull(masterUrl) + ArtworkProcessor.getHighResArtworkCandidates(thumbUrl)).distinct()

                    var loadedBitmap: Bitmap? = null
                    var resolvedUrl = masterUrl ?: thumbUrl ?: ""

                    for (candidate in candidates) {
                        try {
                            val req = ImageRequest.Builder(applicationContext)
                                .data(candidate)
                                .allowHardware(false)
                                .build()
                            val drawable = imageLoader.execute(req).drawable
                            if (drawable is BitmapDrawable) {
                                val bmp = drawable.bitmap
                                // Reject YouTube's 120x90 dummy placeholder returned on missing maxresdefault
                                val isYouTubeDummy = bmp.width <= 120 && bmp.height <= 90
                                if (!isYouTubeDummy && bmp.width > 0 && bmp.height > 0) {
                                    loadedBitmap = bmp
                                    resolvedUrl = candidate
                                    break
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    if (loadedBitmap != null && track != null) {
                        val processed = ArtworkProcessor.processForMediaNotification(loadedBitmap, targetSize = 600)
                        currentArtworkBitmap = processed
                        val artworkBytes = ArtworkProcessor.toByteArray(processed, quality = 92)
                        val localContentUri = ArtworkProcessor.saveMasterArtworkToCache(applicationContext, processed)

                        val isHighResCdn = resolvedUrl.isNotBlank() && !resolvedUrl.contains("hqdefault.jpg") && !resolvedUrl.contains("mqdefault.jpg")
                        val finalUri = localContentUri ?: if (isHighResCdn) android.net.Uri.parse(resolvedUrl) else null

                        val updatedMeta = androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setArtworkUri(finalUri)
                            .setArtworkData(artworkBytes, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                            .build()
                        val updatedItem = androidx.media3.common.MediaItem.Builder()
                            .setMediaId(track.id)
                            .setMediaMetadata(updatedMeta)
                            .build()

                        currentActiveMetadata = updatedMeta
                        currentActiveMediaItem = updatedItem

                        // Update MediaSession with artworkData byte array and URI for studio clarity in Android 13/14/15 Quick Settings & Lockscreen
                        withContext(Dispatchers.Main) {
                            try {
                                for (listener in sessionListeners) {
                                    try {
                                        listener.onMediaMetadataChanged(updatedMeta)
                                        listener.onPlaylistMetadataChanged(updatedMeta)
                                    } catch (_: Exception) {}
                                }
                            } catch (_: Exception) {}
                        }

                        val updatedNotif = buildNotification(track, isPlaying, isFavorite, currentArtworkBitmap)
                        notifManager.notify(NOTIFICATION_ID, updatedNotif)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * Custom MediaSession Callback handling playback commands, speeds, favorites, and navigation.
     */
    private inner class AuralisSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_COMMAND_SET_SPEED, Bundle.EMPTY))
                .build()

            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_GET_TIMELINE)
                .build()

            val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(buildCustomLayout(audioPlayer.isFavorite.value))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
            when (customCommand.customAction) {
                ACTION_TOGGLE_FAVORITE -> {
                    audioPlayer.toggleFavorite()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_TOGGLE_REPEAT -> {
                    audioPlayer.toggleRepeat()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_COMMAND_SET_SPEED -> {
                    val speed = args.getFloat(EXTRA_SPEED_VALUE, 1.0f)
                    session.player.playbackParameters = PlaybackParameters(speed)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("AuralisPlayback", "[AuralisMediaService] onTaskRemoved triggered - stopping audio and dismissing media service")
        try {
            val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
            audioPlayer.stop()
        } catch (e: Exception) {
            Log.w("AuralisPlayback", "[AuralisMediaService] Error stopping audioPlayer on task removed: ${e.message}")
        }

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}

        try {
            val notifManager = NotificationManagerCompat.from(this)
            notifManager.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}

        mediaSession?.run {
            release()
            mediaSession = null
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d("AuralisPlayback", "[AuralisMediaService] onDestroy - releasing all resources")
        try {
            val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
            audioPlayer.stop()
        } catch (_: Exception) {}

        serviceScope.cancel()
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}

/**
 * Custom Media3 Timeline providing 1-window seekable timeline for the current active track,
 * allowing Android 13/14 Quick Settings & Lockscreen controls to render the interactive squiggly seekbar.
 */
private class SingleTrackTimeline(
    private val durationUs: Long,
    private val currentMediaItem: androidx.media3.common.MediaItem?
) : androidx.media3.common.Timeline() {
    override fun getWindowCount(): Int = 1
    override fun getPeriodCount(): Int = 1

    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long
    ): Window {
        window.set(
            Window.SINGLE_WINDOW_UID,
            currentMediaItem,
            null,
            0L,
            0L,
            0L,
            true, // isSeekable
            false, // isDynamic
            null, // liveConfiguration
            0L,
            if (durationUs > 0) durationUs else androidx.media3.common.C.TIME_UNSET,
            0,
            0,
            0L
        )
        return window
    }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        period.set(
            if (setIds) 0 else null,
            if (setIds) Window.SINGLE_WINDOW_UID else null,
            0,
            if (durationUs > 0) durationUs else androidx.media3.common.C.TIME_UNSET,
            0L
        )
        return period
    }

    override fun getIndexOfPeriod(uid: Any): Int = if (uid == 0 || uid == Window.SINGLE_WINDOW_UID) 0 else androidx.media3.common.C.INDEX_UNSET
    override fun getUidOfPeriod(periodIndex: Int): Any = 0
}
