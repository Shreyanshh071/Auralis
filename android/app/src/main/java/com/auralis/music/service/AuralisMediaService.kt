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
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_GET_TIMELINE)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_GET_TIMELINE -> true
                    else -> super.isCommandAvailable(command)
                }
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

            override fun getDuration(): Long {
                val d = audioPlayer.durationMs.value
                return if (d > 0) d else super.getDuration()
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

        // Observe track changes to update notification and system MediaSession dynamically
        serviceScope.launch {
            audioPlayer.currentTrack.collectLatest { track ->
                if (track != null) {
                    try {
                        val meta = androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setArtworkUri(if (!track.thumbnail.isNullOrBlank()) android.net.Uri.parse(track.thumbnail) else null)
                            .build()
                        val item = androidx.media3.common.MediaItem.Builder()
                            .setMediaId(track.id)
                            .setMediaMetadata(meta)
                            .build()
                        player.setMediaItem(item)
                    } catch (_: Exception) {}
                }
                updateNotification(track, audioPlayer.isPlaying.value, audioPlayer.isFavorite.value)
            }
        }

        // Observe playing state changes
        serviceScope.launch {
            audioPlayer.isPlaying.collectLatest { isPlaying ->
                try {
                    if (isPlaying && player.playbackState != Player.STATE_READY) {
                        player.play()
                    } else if (!isPlaying) {
                        player.pause()
                    }
                } catch (_: Exception) {}
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
                audioPlayer.pause()
                stopForeground(STOP_FOREGROUND_REMOVE)
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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(mediaStyle)
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

    private fun updateNotification(track: Track?, isPlaying: Boolean, isFavorite: Boolean = false) {
        val notifManager = NotificationManagerCompat.from(this)
        val notif = buildNotification(track, isPlaying, isFavorite, currentArtworkBitmap)
        try {
            notifManager.notify(NOTIFICATION_ID, notif)
        } catch (_: SecurityException) {}

        // Asynchronously fetch artwork if changed
        val thumbUrl = track?.thumbnail
        if (!thumbUrl.isNullOrBlank() && thumbUrl != lastArtworkUrl) {
            lastArtworkUrl = thumbUrl
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val loader = ImageLoader(applicationContext)
                    val req = ImageRequest.Builder(applicationContext)
                        .data(thumbUrl)
                        .allowHardware(false)
                        .build()
                    val result = loader.execute(req).drawable
                    if (result is BitmapDrawable) {
                        currentArtworkBitmap = result.bitmap
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

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
