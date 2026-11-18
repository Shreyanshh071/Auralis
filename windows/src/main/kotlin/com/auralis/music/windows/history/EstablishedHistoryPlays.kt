package com.auralis.music.windows.history

import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.*

/** A new session is not a play until the stream for its current song is established. */
internal fun establishedHistoryPlays(
    queue: Flow<DesktopPlaybackController.Queue>, state: Flow<VlcAudioPlayer.State>,
    session: Flow<Long>, established: Flow<String?>
): Flow<Pair<Long, Track>> = combine(queue, state, session, established) { q, s, id, sounding ->
    q.current?.takeIf { s == VlcAudioPlayer.State.PLAYING && sounding == it.id }?.let { id to it }
}.filterNotNull().distinctUntilChangedBy { it.first to it.second.id }
