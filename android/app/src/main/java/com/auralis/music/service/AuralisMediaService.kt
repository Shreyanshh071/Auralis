package com.auralis.music.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.auralis.music.MainActivity
import com.auralis.music.R
import com.auralis.music.data.service.AuralisAudioPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Pure native AndroidX Media3 MediaSessionService.
 * Automatically drives the Android 13/14/15 System Media Carousel on Lock Screen and Quick Settings.
 */
@OptIn(UnstableApi::class)
class AuralisMediaService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

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
    }

    override fun onCreate() {
        super.onCreate()

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

        // Configure Media Notification Provider using AndroidX Media3 Default Provider
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.app_name)
                .setChannelId(CHANNEL_ID)
                .build()
        )
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
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
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
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
