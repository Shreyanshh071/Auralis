package com.auralis.music.data.connect

import android.content.Context
import com.auralis.music.data.service.AuralisAudioPlayer
import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
            playing = track != null && !inRoom && (pendingLoad != null || player.intendsToPlay()),
            buffering = pendingLoad != null || player.isBuffering.value,
            positionMs = pendingLoadPosition ?: player.rawPositionMs(),
            durationMs = duration,
            shuffle = q.isShuffled,
            repeat = q.repeatMode,
            context = player.connectContext,
            error = player.playbackError.value
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The stream warm-up started by [prepare], by track id. */
    private var warming: Pair<String, Job>? = null
    private var pendingLoad: Job? = null
    private var pendingLoadPosition: Long? = null

    override fun load(tracks: List<Track>, index: Int, positionMs: Long, play: Boolean, shuffle: Boolean, repeat: RepeatMode, context: ConnectContext?) {
        if (index !in tracks.indices || inRoom) return
        pendingLoad?.cancel()
        pendingLoad = null
        pendingLoadPosition = null
        val warm = warming?.takeIf { it.first == tracks[index].id && it.second.isActive }?.second
        if (play && warm != null) {
            // The handoff finished before the warm-up: join it rather than resolving the stream
            // twice. Bounded under the engine's settle window, so a slow lookup just starts cold.
            player.fromConnect { player.loadSilently(tracks, index, positionMs, shuffle, repeat) }
            pendingLoadPosition = positionMs
            pendingLoad = scope.launch {
                withTimeoutOrNull(3_500L) { warm.join() }
                val resumeAt = pendingLoadPosition ?: positionMs
                pendingLoad = null
                pendingLoadPosition = null
                startLoaded(tracks, index, resumeAt, play, shuffle, repeat, context)
            }
            return
        }
        startLoaded(tracks, index, positionMs, play, shuffle, repeat, context)
    }

    private fun startLoaded(tracks: List<Track>, index: Int, positionMs: Long, play: Boolean, shuffle: Boolean, repeat: RepeatMode, context: ConnectContext?) {
        if (inRoom) return
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
        pendingLoad?.cancel()
        pendingLoad = null
        pendingLoadPosition = null
        if (!inRoom && (player.intendsToPlay() || player.isPlaying.value)) player.fromConnect { player.pause() }
    }

    override suspend fun prepare(track: Track): Boolean {
        val downloaded = com.auralis.music.data.download.AuralisDownloadManager.getDownloadedFile(track.id)?.exists() == true
        if (downloaded) return true
        // A song from another device's own files can't be played here.
        if (track.source == TrackSource.LOCAL) return false
        if (track.id.startsWith("unavailable:")) return false
        // Warm the stream cache while the handoff completes rather than before acknowledging it:
        // the old output stops and commits meanwhile. A miss isn't fatal; a song that won't
        // start is handed back by the engine.
        warming?.second?.cancel()
        val quality = player.currentAudioQuality
        warming = track.id to scope.launch(Dispatchers.IO) {
            runCatching {
                val videoId = com.auralis.music.data.network.AudioStreamResolver.resolvePlaybackVideoId(track)
                    ?: return@runCatching
                com.auralis.music.data.network.AudioStreamResolver.resolveAudioStream(
                    videoId = videoId, title = track.title, artist = track.artist,
                    quality = quality, context = context, duration = track.duration
                )
            }
        }
        return true
    }

    override fun play() = player.fromConnect { player.resume() }
    override fun pause() = silence()
    override fun seekTo(positionMs: Long) {
        if (pendingLoad != null) pendingLoadPosition = positionMs
        player.fromConnect { player.seekTo(positionMs) }
    }
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
