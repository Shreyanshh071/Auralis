package com.auralis.music.service

import android.app.Notification
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
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
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
    private var currentActiveMediaItem: androidx.media3.common.MediaItem? = null
    private var currentActiveMetadata: androidx.media3.common.MediaMetadata? = null

    companion object {
        const val ACTION_START = "com.auralis.music.ACTION_START"
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

    private var sessionActivityPendingIntent: PendingIntent? = null

    override fun onCreate() {
        super.onCreate()
        // 1. Create notification channel synchronously with low importance and public lockscreen visibility
        createNotificationChannel()

        // 2. Configure DefaultMediaNotificationProvider with authentic Auralis notification icon
        try {
            val provider = DefaultMediaNotificationProvider(this).apply {
                setSmallIcon(R.drawable.ic_notification_auralis)
            }
            setMediaNotificationProvider(provider)
        } catch (e: Exception) {
            Log.w("AuralisPlayback", "[AuralisMediaService] setMediaNotificationProvider notice: ${e.message}")
        }

        val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
        val player = audioPlayer.exoPlayer

        // Configure system activity launch intent for lockscreen / notification taps
        sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Wrap player in ForwardingPlayer so Android 13/14 system UI always exposes Previous/Next/Seek commands
        // and cleanly delegates to ExoPlayer without filtering or dropping Player.Listener callbacks.
        val forwardingPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_BACK)
                    .add(Player.COMMAND_SEEK_FORWARD)
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
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
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

            override fun stop() {
                audioPlayer.stop()
            }

            override fun getPlayWhenReady(): Boolean {
                return if (audioPlayer.isUsingExoPlayer) {
                    super.getPlayWhenReady()
                } else {
                    audioPlayer.isPlaying.value || audioPlayer.isBuffering.value
                }
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady) {
                    audioPlayer.resume()
                } else {
                    audioPlayer.pause()
                }
            }

            override fun getPlaybackState(): Int {
                return if (audioPlayer.isUsingExoPlayer) {
                    super.getPlaybackState()
                } else {
                    val track = audioPlayer.currentTrack.value
                    if (track != null) Player.STATE_READY else Player.STATE_IDLE
                }
            }

            override fun seekToPrevious() {
                audioPlayer.previous()
            }

            override fun seekToNext() {
                audioPlayer.next()
            }

            override fun seekToPreviousMediaItem() {
                audioPlayer.previous()
            }

            override fun seekToNextMediaItem() {
                audioPlayer.next()
            }

            override fun seekBack() {
                audioPlayer.seekBackward()
            }

            override fun seekForward() {
                audioPlayer.seekForward()
            }

            override fun seekTo(positionMs: Long) {
                audioPlayer.seekTo(positionMs)
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                audioPlayer.seekTo(positionMs)
            }

            override fun getCurrentTimeline(): androidx.media3.common.Timeline {
                if (audioPlayer.isUsingExoPlayer) {
                    val realTimeline = super.getCurrentTimeline()
                    if (!realTimeline.isEmpty) return realTimeline
                }
                val track = audioPlayer.currentTrack.value
                val durMs = audioPlayer.durationMs.value.takeIf { it > 0 } ?: ((track?.duration ?: 0L) * 1000L)
                val durationUs = durMs * 1000L
                val currentItem = currentMediaItem
                return if (track != null) SingleTrackTimeline(durationUs, currentItem) else super.getCurrentTimeline()
            }

            override fun getCurrentMediaItem(): androidx.media3.common.MediaItem? {
                val active = currentActiveMediaItem
                val track = audioPlayer.currentTrack.value
                if (active != null && track != null && active.mediaId == track.id) {
                    return active
                }
                return super.getCurrentMediaItem()
            }

            override fun getMediaMetadata(): androidx.media3.common.MediaMetadata {
                val active = currentActiveMetadata
                val track = audioPlayer.currentTrack.value
                if (active != null && track != null && (active.title?.toString().equals(track.title, ignoreCase = true))) {
                    return active
                }
                val isRedundant = com.auralis.music.data.network.AlbumMetadataResolver.isRedundantOrSingle(track?.album, track?.title ?: "")
                val displayAlbum = if (isRedundant) null else track?.album
                return androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(track?.title)
                    .setArtist(track?.artist)
                    .setAlbumTitle(displayAlbum)
                    .build()
            }

            override fun getPlaylistMetadata(): androidx.media3.common.MediaMetadata {
                val active = currentActiveMetadata
                val track = audioPlayer.currentTrack.value
                if (active != null && track != null && (active.title?.toString().equals(track.title, ignoreCase = true))) {
                    return active
                }
                return super.getPlaylistMetadata()
            }

            override fun isCurrentMediaItemSeekable(): Boolean = true
            override fun isCurrentMediaItemDynamic(): Boolean = false
            override fun isCurrentMediaItemLive(): Boolean = false
            override fun getCurrentMediaItemIndex(): Int = 0
            override fun getCurrentPeriodIndex(): Int = 0

            override fun getDuration(): Long {
                if (audioPlayer.isUsingExoPlayer) {
                    val realDur = super.getDuration()
                    if (realDur > 0) return realDur
                }
                val d = audioPlayer.durationMs.value
                val trackDur = (audioPlayer.currentTrack.value?.duration ?: 0L) * 1000L
                return if (d > 0) d else if (trackDur > 0) trackDur else super.getDuration()
            }

            override fun getCurrentPosition(): Long {
                return if (audioPlayer.isUsingExoPlayer) {
                    super.getCurrentPosition()
                } else {
                    audioPlayer.playbackPositionMs.value
                }
            }

            override fun isPlaying(): Boolean {
                return if (audioPlayer.isUsingExoPlayer) super.isPlaying() else audioPlayer.isPlaying.value
            }
        }

        // 2. Build Custom MediaSession with custom actions and command handling
        val session = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivityPendingIntent!!)
            .setCallback(AuralisSessionCallback())
            .setCustomLayout(buildCustomLayout(audioPlayer.isFavorite.value))
            .build()
        mediaSession = session
        addSession(session)

        // Keep MediaSession callback synced with current audio playback states
        serviceScope.launch {
            audioPlayer.isPlaying.collectLatest { isPlaying ->
                withContext(Dispatchers.Main) {
                    refreshNotification()
                }
                updateMediaSessionMetadata(audioPlayer.currentTrack.value, isPlaying, audioPlayer.isFavorite.value)
            }
        }

        serviceScope.launch {
            audioPlayer.isBuffering.collectLatest {
                withContext(Dispatchers.Main) {
                    refreshNotification()
                }
            }
        }

        serviceScope.launch {
            audioPlayer.isFavorite.collectLatest { isFav ->
                try {
                    mediaSession?.setCustomLayout(buildCustomLayout(isFav))
                } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    refreshNotification()
                }
                updateMediaSessionMetadata(audioPlayer.currentTrack.value, audioPlayer.isPlaying.value, isFav)
            }
        }

        // Observe track changes to update notification and system MediaSession dynamically
        serviceScope.launch {
            audioPlayer.currentTrack.collectLatest { track ->
                if (track != null) {
                    val localArtFile = com.auralis.music.data.download.AuralisDownloadManager.getDownloadedArtworkFile(track.id)
                    val rawUrl = getHighResArtworkUrl(track.thumbnail) ?: track.thumbnail
                    val cachedBitmap = if (!rawUrl.isNullOrBlank()) {
                        imageLoader.memoryCache?.get(coil.memory.MemoryCache.Key(rawUrl))?.bitmap
                            ?: imageLoader.memoryCache?.get(coil.memory.MemoryCache.Key(track.thumbnail ?: ""))?.bitmap
                    } else null

                    val effectiveBitmap = if (cachedBitmap != null) {
                        cachedBitmap
                    } else if (localArtFile != null && localArtFile.exists()) {
                        try {
                            android.graphics.BitmapFactory.decodeFile(localArtFile.absolutePath)
                        } catch (_: Exception) { null }
                    } else null

                    // ── PERF FIX #2: Move CPU-heavy bitmap processing off Main thread ──
                    // processForMediaNotification (crop+resize), saveMasterArtworkToCache (disk I/O),
                    // and toByteArray (JPEG encode) previously ran synchronously on Dispatchers.Main.
                    var localArtworkUri: android.net.Uri? = null
                    var artworkBytes: ByteArray? = null
                    if (effectiveBitmap != null) {
                        val (processed, artUri, bytes) = withContext(Dispatchers.IO) {
                            val p = ArtworkProcessor.processForMediaNotification(effectiveBitmap, targetSize = 600)
                            val uri = ArtworkProcessor.saveMasterArtworkToCache(applicationContext, p)
                            val b = ArtworkProcessor.toByteArray(p, quality = 92)
                            Triple(p, uri, b)
                        }
                        currentArtworkBitmap = processed
                        localArtworkUri = artUri
                        artworkBytes = bytes
                    } else {
                        currentArtworkBitmap = null
                    }

                    val isOfficialCdn = !rawUrl.isNullOrBlank() &&
                            (rawUrl.contains("googleusercontent.com") || rawUrl.contains("ggpht.com") ||
                             rawUrl.contains("mzstatic.com") || rawUrl.contains("scdn.co") ||
                             rawUrl.contains("jiosaavn.com") || rawUrl.contains("saavncdn.com") ||
                             rawUrl.contains("ytimg.com") || rawUrl.contains("youtube.com"))
                    val initialArtworkUri = localArtworkUri ?: if (isOfficialCdn) android.net.Uri.parse(rawUrl) else null

                    val isRedundant = com.auralis.music.data.network.AlbumMetadataResolver.isRedundantOrSingle(track.album, track.title)
                    val displayAlbum = if (isRedundant) null else track.album
                    val initialMetaBuilder = androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(displayAlbum)
                        .setArtworkUri(initialArtworkUri)

                    if (artworkBytes != null) {
                        initialMetaBuilder.setArtworkData(
                            artworkBytes,
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

                    // ForwardingPlayer dynamically supplies currentActiveMetadata and currentActiveMediaItem
                    // to Media3 MediaSession without injecting URI-less dummy items into ExoPlayer.

                    withContext(Dispatchers.Main) {
                        try {
                            audioPlayer.exoPlayer.playlistMetadata = initialMeta
                        } catch (_: Exception) {}
                        refreshNotification()
                    }
                }
                updateMediaSessionMetadata(track, audioPlayer.isPlaying.value, audioPlayer.isFavorite.value)
            }
        }

        // Synchronously publish foreground notification on service startup if a track is present
        if (audioPlayer.currentTrack.value != null) {
            refreshNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
        Log.d("AuralisPlayback", "[AuralisMediaService] onStartCommand received action=${intent?.action}, startId=$startId")

        // 1. Immediately and synchronously satisfy foreground service guarantee before processing intent
        refreshNotification()

        when (intent?.action) {
            ACTION_START, null -> {
                Log.d("AuralisPlayback", "[AuralisMediaService] ACTION_START -> foreground service confirmed active")
                refreshNotification()
            }
            ACTION_PLAY -> {
                Log.d("AuralisPlayback", "[AuralisMediaService] ACTION_PLAY -> audioPlayer.resume()")
                audioPlayer.resume()
                refreshNotification()
            }
            ACTION_PAUSE -> {
                Log.d("AuralisPlayback", "[AuralisMediaService] ACTION_PAUSE -> audioPlayer.pause()")
                audioPlayer.pause()
                refreshNotification()
            }
            ACTION_TOGGLE -> {
                Log.d("AuralisPlayback", "[AuralisMediaService] ACTION_TOGGLE -> audioPlayer.togglePlayPause()")
                audioPlayer.togglePlayPause()
                refreshNotification()
            }
            ACTION_NEXT -> {
                Log.d("AuralisPlayback", "[AuralisMediaService] ACTION_NEXT -> audioPlayer.next()")
                audioPlayer.next()
                refreshNotification()
            }
            ACTION_PREVIOUS -> {
                Log.d("AuralisPlayback", "[AuralisMediaService] ACTION_PREVIOUS -> audioPlayer.previous()")
                audioPlayer.previous()
                refreshNotification()
            }
            ACTION_SEEK_BACK -> {
                audioPlayer.seekBackward(10000L)
                refreshNotification()
            }
            ACTION_SEEK_FORWARD -> {
                audioPlayer.seekForward(10000L)
                refreshNotification()
            }
            ACTION_TOGGLE_FAVORITE -> {
                audioPlayer.toggleFavorite()
                refreshNotification()
            }
            ACTION_TOGGLE_REPEAT -> {
                audioPlayer.toggleRepeat()
                refreshNotification()
            }
            ACTION_STOP -> {
                Log.d("AuralisPlayback", "[AuralisMediaService] ACTION_STOP -> audioPlayer.stop() and stopSelf()")
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

    private fun createActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, AuralisMediaService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildMediaNotification(
        track: Track?,
        isPlaying: Boolean,
        isBuffering: Boolean,
        isFav: Boolean,
        artwork: Bitmap?
    ): Notification {
        val shouldShowPause = isPlaying || isBuffering

        val prevPending = createActionPendingIntent(ACTION_PREVIOUS, 1)
        val playPausePending = createActionPendingIntent(if (shouldShowPause) ACTION_PAUSE else ACTION_PLAY, 2)
        val nextPending = createActionPendingIntent(ACTION_NEXT, 3)
        val favPending = createActionPendingIntent(ACTION_TOGGLE_FAVORITE, 4)
        val stopPending = createActionPendingIntent(ACTION_STOP, 5)

        val prevAction = NotificationCompat.Action.Builder(
            R.drawable.ic_media_skip_previous,
            "Previous",
            prevPending
        ).build()

        val playPauseAction = NotificationCompat.Action.Builder(
            if (shouldShowPause) R.drawable.ic_media_pause_circle else R.drawable.ic_media_play_circle,
            if (shouldShowPause) "Pause" else "Play",
            playPausePending
        ).build()

        val nextAction = NotificationCompat.Action.Builder(
            R.drawable.ic_media_skip_next,
            "Next",
            nextPending
        ).build()

        val favAction = NotificationCompat.Action.Builder(
            if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline,
            if (isFav) "Favorited" else "Favorite",
            favPending
        ).build()

        val session = mediaSession
        val mediaStyle = if (session != null) {
            MediaStyleNotificationHelper.MediaStyle(session)
                .setShowActionsInCompactView(0, 1, 2)
        } else {
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
        }

        val title = track?.title?.ifBlank { "Auralis" } ?: "Auralis"
        val artist = track?.artist?.ifBlank { "Playing music" } ?: "Playing music"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setStyle(mediaStyle)
            .setSmallIcon(R.drawable.ic_notification_auralis)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(if (isBuffering) "Buffering..." else null)
            .setContentIntent(sessionActivityPendingIntent)
            .setDeleteIntent(stopPending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(isPlaying || isBuffering)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .addAction(favAction)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (artwork != null) {
            builder.setLargeIcon(artwork)
        }

        return builder.build()
    }

    fun refreshNotification() {
        val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
        val track = audioPlayer.currentTrack.value
        val isPlaying = audioPlayer.isPlaying.value
        val isBuffering = audioPlayer.isBuffering.value
        val isFav = audioPlayer.isFavorite.value

        if (track == null) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {}
            try {
                NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            } catch (_: Exception) {}
            return
        }

        val notification = buildMediaNotification(
            track = track,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            isFav = isFav,
            artwork = currentArtworkBitmap
        )

        val shouldRunForeground = isPlaying || isBuffering
        if (shouldRunForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                Log.w("AuralisPlayback", "[AuralisMediaService] startForeground notice: ${e.message}")
                try {
                    NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
                } catch (_: Exception) {}
            }
        } else {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(false)
                }
            } catch (_: Exception) {}
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.w("AuralisPlayback", "[AuralisMediaService] NotificationManager notify notice: ${e.message}")
            }
        }
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        refreshNotification()
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

    private val imageLoader by lazy {
        ImageLoader.Builder(applicationContext)
            .respectCacheHeaders(false)
            .build()
    }

    private fun updateMediaSessionMetadata(track: Track?, isPlaying: Boolean, isFavorite: Boolean = false) {
        if (track == null) {
            currentArtworkBitmap = null
            lastArtworkUrl = null
            return
        }

        val targetTrackId = track.id
        val targetTrackTitle = track.title
        val targetTrackArtist = track.artist
        val thumbUrl = track.thumbnail
        val cacheKey = "$targetTrackId - $targetTrackArtist - $targetTrackTitle - $thumbUrl"

        if (cacheKey != lastArtworkUrl) {
            lastArtworkUrl = cacheKey
            currentArtworkBitmap = null

            serviceScope.launch(Dispatchers.IO) {
                try {
                    val localArtFile = com.auralis.music.data.download.AuralisDownloadManager.getDownloadedArtworkFile(targetTrackId)
                    val localArtUri = if (localArtFile != null && localArtFile.exists()) android.net.Uri.fromFile(localArtFile).toString() else null
                    val matchedYtId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(targetTrackId)
                    val matchedYtUrl = if (!matchedYtId.isNullOrBlank() && matchedYtId.length in 8..15) "https://i.ytimg.com/vi/$matchedYtId/hq720.jpg" else null

                    val masterUrl = MasterArtworkResolver.resolveMasterArtworkUrl(targetTrackTitle, targetTrackArtist, thumbUrl)
                    val candidates = (listOfNotNull(localArtUri, masterUrl, matchedYtUrl) + ArtworkProcessor.getHighResArtworkCandidates(thumbUrl)).distinct()

                    var loadedBitmap: Bitmap? = null
                    var resolvedUrl = localArtUri ?: masterUrl ?: thumbUrl ?: ""

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

                    // Strict check: Only apply if track is STILL the currently active track
                    val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
                    val activeTrack = audioPlayer.currentTrack.value
                    if (loadedBitmap != null && activeTrack != null && activeTrack.id == targetTrackId) {
                        val processed = ArtworkProcessor.processForMediaNotification(loadedBitmap, targetSize = 600)
                        currentArtworkBitmap = processed
                        val artworkBytes = ArtworkProcessor.toByteArray(processed, quality = 92)
                        val localContentUri = ArtworkProcessor.saveMasterArtworkToCache(applicationContext, processed)

                        val isHighResCdn = resolvedUrl.isNotBlank() && !resolvedUrl.contains("hqdefault.jpg") && !resolvedUrl.contains("mqdefault.jpg")
                        val finalUri = localContentUri ?: if (isHighResCdn) android.net.Uri.parse(resolvedUrl) else null

                        val isRedundant = com.auralis.music.data.network.AlbumMetadataResolver.isRedundantOrSingle(activeTrack.album, activeTrack.title)
                        val displayAlbum = if (isRedundant) null else activeTrack.album
                        val updatedMeta = androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(activeTrack.title)
                            .setArtist(activeTrack.artist)
                            .setAlbumTitle(displayAlbum)
                            .setArtworkUri(finalUri)
                            .setArtworkData(artworkBytes, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                            .build()
                        val updatedItem = androidx.media3.common.MediaItem.Builder()
                            .setMediaId(activeTrack.id)
                            .setMediaMetadata(updatedMeta)
                            .build()

                        currentActiveMetadata = updatedMeta
                        currentActiveMediaItem = updatedItem

                        // Update MediaSession with artworkData byte array and URI for studio clarity in Android 13/14/15 Quick Settings & Lockscreen
                        withContext(Dispatchers.Main) {
                            try {
                                audioPlayer.exoPlayer.playlistMetadata = updatedMeta
                                refreshNotification()
                            } catch (_: Exception) {}
                        }
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

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: @Player.Command Int
        ): Int {
            val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
            if (audioPlayer.isGuestListenTogether.value) {
                when (playerCommand) {
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD -> {
                        Log.d("AuralisPlayback", "[AuralisMediaService] Blocked controller playerCommand $playerCommand because user is listener in Listen Together room")
                        return SessionResult.RESULT_ERROR_PERMISSION_DENIED
                    }
                }
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
            if (audioPlayer.isGuestListenTogether.value && customCommand.customAction == ACTION_TOGGLE_REPEAT) {
                Log.d("AuralisPlayback", "[AuralisMediaService] Blocked custom command repeat because user is listener in Listen Together room")
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            when (customCommand.customAction) {
                ACTION_TOGGLE_FAVORITE -> {
                    val nextFav = !audioPlayer.isFavorite.value
                    audioPlayer.toggleFavorite()
                    try {
                        session.setCustomLayout(buildCustomLayout(nextFav))
                        session.setCustomLayout(controller, buildCustomLayout(nextFav))
                    } catch (_: Exception) {}
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
        val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
        val isCurrentlyPlaying = audioPlayer.isPlaying.value
        Log.d("AuralisPlayback", "[AuralisMediaService] onTaskRemoved triggered (isPlaying=$isCurrentlyPlaying) -> performing graceful cleanup")

        try {
            com.auralis.music.data.sync.ListenTogetherManager.performTaskRemovedCleanup()
        } catch (_: Exception) {}

        try {
            audioPlayer.persistQueue()
            audioPlayer.stop()
        } catch (_: Exception) {}

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}

        try {
            val notifManager = NotificationManagerCompat.from(this)
            notifManager.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}

        try {
            mediaSession?.run {
                try {
                    removeSession(this)
                } catch (_: Exception) {}
                release()
                mediaSession = null
            }
        } catch (_: Exception) {}

        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d("AuralisPlayback", "[AuralisMediaService] onDestroy - cleaning up serviceScope and mediaSession")
        try {
            com.auralis.music.data.sync.ListenTogetherManager.performTaskRemovedCleanup()
        } catch (_: Exception) {}
        serviceScope.cancel()
        mediaSession?.run {
            try {
                removeSession(this)
            } catch (_: Exception) {}
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
