package com.auralis.music.service

import android.net.Uri
import android.os.Looper
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.auralis.music.data.service.AuralisAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Custom AndroidX Media3 Player implementation bridging AuralisAudioPlayer with MediaSession.
 * 
 * Provides:
 * - Real-time state synchronization with MediaSession / Lock screen / Bluetooth AVRCP.
 * - Accurate track metadata (title, artist, album art Uri).
 * - Full media command routing (Play, Pause, Next, Previous, Seek).
 */
@UnstableApi
class AuralisMediaSessionPlayer(
    basePlayer: Player,
    private val audioPlayer: AuralisAudioPlayer
) : ForwardingPlayer(basePlayer) {

    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val customListeners = CopyOnWriteArrayList<Player.Listener>()

    init {
        // Observe Current Track metadata changes
        playerScope.launch {
            audioPlayer.currentTrack.collectLatest { track ->
                val mediaItem = currentMediaItem
                val metadata = mediaMetadata
                val flags = FlagSet.Builder()
                    .add(Player.EVENT_MEDIA_ITEM_TRANSITION)
                    .add(Player.EVENT_MEDIA_METADATA_CHANGED)
                    .add(Player.EVENT_TRACKS_CHANGED)
                    .build()
                val events = Player.Events(flags)
                customListeners.forEach { listener ->
                    try {
                        listener.onMediaItemTransition(mediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                        listener.onMediaMetadataChanged(metadata)
                        listener.onEvents(this@AuralisMediaSessionPlayer, events)
                    } catch (_: Exception) {}
                }
            }
        }

        // Observe IsPlaying changes
        playerScope.launch {
            audioPlayer.isPlaying.collectLatest { playing ->
                val state = playbackState
                val flags = FlagSet.Builder()
                    .add(Player.EVENT_IS_PLAYING_CHANGED)
                    .add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                    .add(Player.EVENT_PLAYBACK_STATE_CHANGED)
                    .build()
                val events = Player.Events(flags)
                customListeners.forEach { listener ->
                    try {
                        listener.onIsPlayingChanged(playing)
                        listener.onPlayWhenReadyChanged(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                        listener.onPlaybackStateChanged(state)
                        listener.onEvents(this@AuralisMediaSessionPlayer, events)
                    } catch (_: Exception) {}
                }
            }
        }

        // Observe Buffering changes
        playerScope.launch {
            audioPlayer.isBuffering.collectLatest { buffering ->
                val state = playbackState
                val flags = FlagSet.Builder()
                    .add(Player.EVENT_PLAYBACK_STATE_CHANGED)
                    .build()
                val events = Player.Events(flags)
                customListeners.forEach { listener ->
                    try {
                        listener.onPlaybackStateChanged(state)
                        listener.onEvents(this@AuralisMediaSessionPlayer, events)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        customListeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        customListeners.remove(listener)
    }

    override fun getApplicationLooper(): Looper = Looper.getMainLooper()

    override fun isPlaying(): Boolean = audioPlayer.isPlaying.value

    override fun getPlayWhenReady(): Boolean = audioPlayer.isPlaying.value

    override fun getPlaybackState(): Int {
        return when {
            audioPlayer.isBuffering.value -> Player.STATE_BUFFERING
            audioPlayer.currentTrack.value != null -> Player.STATE_READY
            else -> Player.STATE_IDLE
        }
    }

    override fun getCurrentPosition(): Long = audioPlayer.playbackPositionMs.value

    override fun getDuration(): Long = audioPlayer.durationMs.value

    override fun getCurrentMediaItem(): MediaItem? {
        val track = audioPlayer.currentTrack.value ?: return null
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.artist)
                    .setArtworkUri(if (track.thumbnail.isNotBlank()) Uri.parse(track.thumbnail) else null)
                    .build()
            )
            .build()
    }

    override fun getMediaMetadata(): MediaMetadata {
        val track = audioPlayer.currentTrack.value ?: return MediaMetadata.EMPTY
        return MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.artist)
            .setArtworkUri(if (track.thumbnail.isNotBlank()) Uri.parse(track.thumbnail) else null)
            .build()
    }

    override fun play() {
        audioPlayer.resume()
    }

    override fun pause() {
        audioPlayer.pause()
    }

    override fun seekTo(positionMs: Long) {
        audioPlayer.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        audioPlayer.seekTo(positionMs)
    }

    override fun seekToNext() {
        audioPlayer.next()
    }

    override fun seekToNextMediaItem() {
        audioPlayer.next()
    }

    override fun seekToPrevious() {
        audioPlayer.previous()
    }

    override fun seekToPreviousMediaItem() {
        audioPlayer.previous()
    }

    override fun stop() {
        audioPlayer.pause()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        return when (command) {
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_STOP -> true
            else -> super.isCommandAvailable(command)
        }
    }

    override fun getAvailableCommands(): Player.Commands {
        return Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_STOP
            )
            .build()
    }

    fun releaseCustomPlayer() {
        playerScope.cancel()
        customListeners.clear()
    }
}
