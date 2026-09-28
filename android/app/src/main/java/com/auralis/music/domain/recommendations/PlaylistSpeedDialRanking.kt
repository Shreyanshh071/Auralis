package com.auralis.music.domain.recommendations

import com.auralis.music.domain.model.Playlist

data class PlaylistListeningStats(
    val sessions: Int = 0,
    val listenedMs: Long = 0L
)

/** A playlist needs repeat visits and meaningful playback before it earns a Speed Dial slot. */
object PlaylistSpeedDialRanking {
    const val MIN_SESSIONS = 3
    const val MIN_LISTENED_MS = 20 * 60 * 1000L

    fun mostListened(
        playlists: List<Playlist>,
        stats: Map<String, PlaylistListeningStats>
    ): Playlist? = playlists.asSequence()
        .filter { it.tracks.isNotEmpty() && !it.id.startsWith("smart_") }
        .filter { playlist ->
            val activity = stats[playlist.id] ?: return@filter false
            activity.sessions >= MIN_SESSIONS && activity.listenedMs >= MIN_LISTENED_MS
        }
        .maxWithOrNull(compareBy<Playlist> { stats[it.id]?.listenedMs ?: 0L }
            .thenBy { stats[it.id]?.sessions ?: 0 }
            .thenBy { it.id })
}
