package com.auralis.music.data.connect

import android.content.Context
import com.auralis.music.data.service.AuralisAudioPlayer
import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What [AuralisAudioPlayer] asks Connect before acting on a user control. */
interface ConnectHook {
    /** True when the control went to the account's output on another device. */
    fun intercept(intent: ConnectIntent): Boolean
    /** The session this phone shows while another device plays it; null when this phone is the output. */
    fun remote(): ConnectRemoteView?
}

/**
 * Connect's view of this phone's player. Every call runs through [AuralisAudioPlayer.fromConnect]
 * so it acts here and is never sent back out as a remote control. Main thread only.
 */
class AndroidConnectPlayer(private val context: Context, private val player: AuralisAudioPlayer) : ConnectPlayer {
    /**
     * A Listen Together guest plays the room host's music: Connect stands aside (it neither
     * silences that audio nor makes this phone the account's output because of it).
     */
    private val inRoom: Boolean get() = player.isGuestListenTogether.value

    override fun snapshot(): LocalPlayback {
        val track = player.currentTrack.value
        val q = player.queueState.value
        val duration = player.durationMs.value.takeIf { it > 0L } ?: ((track?.duration ?: 0L) * 1000L)
        return LocalPlayback(
            track = track,
            queue = q.queue,
            index = q.currentIndex,
            playing = track != null && !inRoom && player.intendsToPlay(),
            buffering = player.isBuffering.value,
            positionMs = player.rawPositionMs(),
            durationMs = duration,
            shuffle = q.isShuffled,
            repeat = q.repeatMode,
            context = player.connectContext,
            error = player.playbackError.value
        )
    }

    override fun load(tracks: List<Track>, index: Int, positionMs: Long, play: Boolean, shuffle: Boolean, repeat: RepeatMode, context: ConnectContext?) {
        if (index !in tracks.indices || inRoom) return
        player.fromConnect {
            player.connectContext = context
            if (play) {
                player.playTrack(tracks[index], tracks, index, isUserQueue = true, initialPositionMs = positionMs, preserveQueueSource = true)
                player.applyConnectQueueFlags(shuffle, repeat)
            } else {
                player.loadSilently(tracks, index, positionMs, shuffle, repeat)
            }
        }
    }

    override fun silence() {
        if (!inRoom && (player.intendsToPlay() || player.isPlaying.value)) player.fromConnect { player.pause() }
    }

    override suspend fun prepare(track: Track): Boolean {
        val downloaded = com.auralis.music.data.download.AuralisDownloadManager.getDownloadedFile(track.id)?.exists() == true
        if (downloaded) return true
        // A song from another device's own files can't be played here.
        if (track.source == TrackSource.LOCAL) return false
        return withContext(Dispatchers.IO) {
            val videoId = com.auralis.music.data.network.AudioStreamResolver.resolvePlaybackVideoId(track) ?: return@withContext false
            // Warms the stream cache so starting after the handoff is quick; a miss here isn't fatal.
            runCatching {
                com.auralis.music.data.network.AudioStreamResolver.resolveAudioStream(
                    videoId = videoId, title = track.title, artist = track.artist,
                    quality = player.currentAudioQuality, context = context, duration = track.duration
                )
            }
            true
        }
    }

    override fun play() = player.fromConnect { player.resume() }
    override fun pause() = player.fromConnect { player.pause() }
    override fun seekTo(positionMs: Long) = player.fromConnect { player.seekTo(positionMs) }
    override fun next() { player.fromConnect { player.next() } }
    override fun previous() = player.fromConnect { player.previous() }

    override fun playIndex(index: Int) {
        val q = player.queueState.value
        val track = q.queue.getOrNull(index) ?: return
        player.fromConnect { player.playTrack(track, q.queue, index, isUserQueue = q.isUserQueue, preserveQueueSource = true) }
    }

    override fun setShuffle(on: Boolean) {
        if (player.queueState.value.isShuffled != on) player.fromConnect { player.toggleShuffle() }
    }

    override fun setRepeat(mode: RepeatMode) = player.fromConnect { player.setRepeatMode(mode) }
    override fun add(track: Track) = player.fromConnect { player.addToQueue(listOf(track)) }
    override fun playNext(track: Track) = player.fromConnect { player.playNext(track) }
    override fun move(from: Int, to: Int) { player.fromConnect { player.moveQueueItem(from, to) } }
    override fun remove(index: Int) { player.fromConnect { player.removeQueueItem(index) } }
}
