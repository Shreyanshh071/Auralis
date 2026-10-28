package com.auralis.music.ui.viewmodel

import com.auralis.music.data.connect.ConnectRemoteView
import com.auralis.music.data.service.PlaybackClockSource
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * While another device of the account is the Connect output, the player screens show that
 * device's session (song, queue, play state, moving position) and this phone stays silent.
 */
internal fun PlayerUiState.withRemote(remote: ConnectRemoteView, nowMs: Long): PlayerUiState = copy(
    currentTrack = remote.track,
    isPlaying = remote.playing,
    isBuffering = remote.buffering,
    playbackPositionMs = remote.positionAt(nowMs),
    durationMs = remote.durationMs,
    queue = remote.queue,
    // Remote sessions do not carry per-occurrence listening history.
    listenedQueueIndices = emptySet(),
    currentIndex = remote.index,
    isShuffled = remote.shuffle,
    repeatMode = remote.repeat,
    queueSourceTitle = remote.context?.title?.takeIf { it.isNotBlank() } ?: queueSourceTitle.takeIf { currentTrack?.id == remote.track.id },
    isFavorite = isFavorite && currentTrack?.id == remote.track.id,
    lyrics = lyrics.takeIf { currentTrack?.id == remote.track.id },
    isLoadingLyrics = false,
    errorMessage = null,
    audioLeadingSilenceMs = null
)

/** [local], or [local] with the remote session laid over it; the value is computed when read. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class ConnectOverlayState(
    private val local: StateFlow<PlayerUiState>,
    private val remote: StateFlow<ConnectRemoteView?>,
    private val remoteLyrics: StateFlow<PlayerUiState> = local,
    private val now: () -> Long = System::currentTimeMillis
) : StateFlow<PlayerUiState> {
    override val value: PlayerUiState get() = remote.value?.let {
        val lyrics = remoteLyrics.value
        local.value.withRemote(it, now()).copy(
            lyrics = lyrics.lyrics.takeIf { _ -> lyrics.currentTrack?.id == it.track.id },
            isLoadingLyrics = lyrics.isLoadingLyrics && lyrics.currentTrack?.id == it.track.id
        )
    } ?: local.value
    override val replayCache: List<PlayerUiState> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<PlayerUiState>): Nothing {
        combine(local, remote, remoteLyrics) { _, _, _ -> value }.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}

/** The local position, or the remote one advancing a few times a second while it plays. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ConnectOverlayPosition(
    private val local: StateFlow<Long>,
    private val remote: StateFlow<ConnectRemoteView?>,
    private val now: () -> Long = System::currentTimeMillis
) : StateFlow<Long> {
    override val value: Long get() = remote.value?.positionAt(now()) ?: local.value
    override val replayCache: List<Long> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<Long>): Nothing {
        val ticks = remote.flatMapLatest { r ->
            if (r != null && r.playing && !r.buffering) flow { while (true) { emit(Unit); delay(250L) } } else flowOf(Unit)
        }
        combine(local, ticks) { _, _ -> value }.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}

/** The lyrics clock: the remote session's while it is shown, this phone's player otherwise. */
internal class ConnectOverlayClock(
    private val local: PlaybackClockSource,
    private val remote: StateFlow<ConnectRemoteView?>,
    private val now: () -> Long = System::currentTimeMillis
) : PlaybackClockSource {
    override fun rawPositionMs(): Long = remote.value?.positionAt(now()) ?: local.rawPositionMs()
    override fun isPlaying(): Boolean = remote.value?.let { it.playing && !it.buffering } ?: local.isPlaying()
    override fun speed(): Float = if (remote.value != null) 1f else local.speed()
    override fun isBuffering(): Boolean = remote.value?.buffering ?: local.isBuffering()
}
