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

    const val MAX_PLAYLISTS = 3

    fun mostListened(
        playlists: List<Playlist>,
        stats: Map<String, PlaylistListeningStats>
    ): Playlist? = mostListened(playlists, stats, limit = 1).firstOrNull()

    /** Qualifying playlists, most listened first. */
    fun mostListened(
        playlists: List<Playlist>,
        stats: Map<String, PlaylistListeningStats>,
        limit: Int
    ): List<Playlist> = playlists.asSequence()
        .filter { it.tracks.isNotEmpty() && !it.id.startsWith("smart_") }
        .filter { playlist ->
            val activity = stats[playlist.id] ?: return@filter false
            activity.sessions >= MIN_SESSIONS && activity.listenedMs >= MIN_LISTENED_MS
        }
        .sortedWith(compareByDescending<Playlist> { stats[it.id]?.listenedMs ?: 0L }
            .thenByDescending { stats[it.id]?.sessions ?: 0 }
            .thenBy { it.id })
        .take(limit)
        .toList()

    /**
     * Places playlists among songs by measured listening time: each playlist goes ahead of the
     * first song listened to less than it, so a more-listened song keeps its place in front.
     * [items] keep their order; [playlists] must be sorted by [playlistMs], highest first.
     */
    fun <T> interleave(
        items: List<T>,
        itemMs: (T) -> Long,
        playlists: List<T>,
        playlistMs: (T) -> Long
    ): List<T> {
        val result = ArrayList<T>(items.size + playlists.size)
        var next = 0
        for (item in items) {
            while (next < playlists.size && playlistMs(playlists[next]) > itemMs(item)) {
                result.add(playlists[next++])
            }
            result.add(item)
        }
        while (next < playlists.size) result.add(playlists[next++])
        return result
    }
}
