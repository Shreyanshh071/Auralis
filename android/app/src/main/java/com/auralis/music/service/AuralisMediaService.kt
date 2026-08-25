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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Pure native AndroidX Media3 MediaSessionService providing:
 * - Uninterrupted foreground playback with persistent media notifications.
 * - Lock-screen media controls, Bluetooth AVRCP metadata sync, and headset media buttons.
 * - Zero stale notifications on rapid track switches.
 */
@OptIn(UnstableApi::class)
class AuralisMediaService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var customPlayer: AuralisMediaSessionPlayer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
        createNotificationChannel()

        val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
        val player = audioPlayer.exoPlayer

        // Wrap ExoPlayer + AudioPlayer in custom reactive MediaSession player
        customPlayer = AuralisMediaSessionPlayer(player, audioPlayer)

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
        mediaSession = MediaSession.Builder(this, customPlayer!!)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(AuralisSessionCallback())
            .build()

        // Start immediate foreground notification
        updateForegroundNotification(audioPlayer.currentTrack.value, audioPlayer.isPlaying.value)

        // Reactively update system notification when track or play state changes
        serviceScope.launch {
            audioPlayer.currentTrack.collectLatest { track ->
                updateForegroundNotification(track, audioPlayer.isPlaying.value)
            }
        }

        serviceScope.launch {
            audioPlayer.isPlaying.collectLatest { isPlaying ->
                updateForegroundNotification(audioPlayer.currentTrack.value, isPlaying)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auralis Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background audio playback and media notification"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
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
            ACTION_STOP -> {
                audioPlayer.pause()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        updateForegroundNotification(audioPlayer.currentTrack.value, audioPlayer.isPlaying.value)
        return START_STICKY
    }

    private fun createPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, AuralisMediaService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun updateForegroundNotification(track: Track?, isPlaying: Boolean) {
        try {
            createNotificationChannel()

            val title = track?.title ?: "Auralis Music"
            val artist = track?.artist ?: "Streaming"

            val contentPendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val prevPending = createPendingIntent(ACTION_PREVIOUS, 10)
            val togglePending = createPendingIntent(ACTION_TOGGLE, 20)
            val nextPending = createPendingIntent(ACTION_NEXT, 30)
            val stopPending = createPendingIntent(ACTION_STOP, 40)

            val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val playPauseText = if (isPlaying) "Pause" else "Play"

            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(artist)
                .setSubText("Auralis")
                .setContentIntent(contentPendingIntent)
                .setOngoing(isPlaying)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Previous", prevPending)
                .addAction(playPauseIcon, playPauseText, togglePending)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(0, 1, 2)
                )

            val notification = builder.build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            // Async Artwork Loader for Notification Large Icon
            if (track != null && track.thumbnail.isNotBlank()) {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val imageLoader = ImageLoader(applicationContext)
                        val req = ImageRequest.Builder(applicationContext)
                            .data(track.thumbnail)
                            .size(192, 192)
                            .allowHardware(false)
                            .build()
                        val result = imageLoader.execute(req)
                        val drawable = result.drawable
                        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
                            val activeTrack = AuralisAudioPlayer.getInstance(applicationContext).currentTrack.value
                            if (activeTrack?.id == track.id) {
                                builder.setLargeIcon(drawable.bitmap)
                                val updatedNotification = builder.build()
                                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                notificationManager.notify(NOTIFICATION_ID, updatedNotification)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w("AuralisPlayback", "updateForegroundNotification notice: ${e.message}")
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * Custom MediaSession Callback handling playback commands, speeds, and focus.
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
        customPlayer?.releaseCustomPlayer()
        customPlayer = null
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
