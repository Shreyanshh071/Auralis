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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
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
 * - Immediate synchronous startForeground() execution in onCreate() and onStartCommand() to prevent ForegroundServiceDidNotStartInTimeException.
 * - Persistent notification with MediaStyle, playback controls, and background keepalive.
 * - Android 13/14 lock-screen and Quick Settings media controls.
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

        // 2. Immediately call startForeground() synchronously before any other work
        startForegroundSafely()

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

        // Build Custom MediaSession with custom actions and command handling
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(AuralisSessionCallback())
            .build()

        // Observe player changes to update notification dynamically
        serviceScope.launch {
            audioPlayer.currentTrack.collectLatest { track ->
                updateNotification(track, audioPlayer.isPlaying.value)
            }
        }

        serviceScope.launch {
            audioPlayer.isPlaying.collectLatest { isPlaying ->
                updateNotification(audioPlayer.currentTrack.value, isPlaying)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Guarantee startForeground is active on every startCommand entry
        startForegroundSafely()

        val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
        when (intent?.action) {
            ACTION_PLAY -> audioPlayer.resume()
            ACTION_PAUSE -> audioPlayer.pause()
            ACTION_TOGGLE -> audioPlayer.togglePlayPause()
            ACTION_NEXT -> audioPlayer.next()
            ACTION_PREVIOUS -> audioPlayer.previous()
            ACTION_STOP -> {
                audioPlayer.pause()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
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
        val notif = buildNotification(audioPlayer.currentTrack.value, audioPlayer.isPlaying.value, currentArtworkBitmap)
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

    private fun buildNotification(track: Track?, isPlaying: Boolean, artwork: Bitmap?): android.app.Notification {
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

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setStyle(mediaStyle)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)

        if (artwork != null) {
            builder.setLargeIcon(artwork)
        }

        return builder.build()
    }

    private fun updateNotification(track: Track?, isPlaying: Boolean) {
        val notifManager = NotificationManagerCompat.from(this)
        val notif = buildNotification(track, isPlaying, currentArtworkBitmap)
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
                        val updatedNotif = buildNotification(track, isPlaying, currentArtworkBitmap)
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
     * Custom MediaSession Callback handling playback commands, speeds, and navigation.
     */
    private inner class AuralisSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CUSTOM_COMMAND_SET_SPEED, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CUSTOM_COMMAND_SET_SPEED) {
                val speed = args.getFloat(EXTRA_SPEED_VALUE, 1.0f)
                session.player.playbackParameters = PlaybackParameters(speed)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
