package com.auralis.music.data.network

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher

/** Decides which songs of an already imported Spotify list are new to Auralis. */
internal object SpotifyIncrementalImport {
    /**
     * [seenIds] are the Spotify songs earlier imports brought in, so one the user removed here stays
     * removed. Songs imported by link may already carry their YouTube id, hence [matchedId] and the
     * title check.
     */
    fun newTracks(
        remote: List<Track>,
        local: List<Track>,
        seenIds: Set<String>,
        matchedId: (String) -> String? = { null }
    ): List<Track> {
        val localIds = local.mapTo(HashSet()) { it.id }
        val localSongs = local.mapTo(HashSet(), ::songKey)
        return remote.distinctBy { it.id }.filter { track ->
            track.id !in seenIds && track.id !in localIds &&
                matchedId(track.id) !in localIds && songKey(track) !in localSongs
        }
    }

    /**
     * Whether a playlist already in Auralis is this imported one: same name, same number of songs
     * and the same songs. A copy brought in by link can carry YouTube's own titles and credits
     * ("Kesariya (From "Brahmastra")"), so songs compare by id or base title, and up to 1 in 10
     * may differ in spelling.
     */
    fun isSamePlaylist(
        title: String,
        tracks: List<Track>,
        local: com.auralis.music.domain.model.Playlist,
        matchedId: (String) -> String? = { null }
    ): Boolean {
        if (SearchQueryMatcher.normalize(title) != SearchQueryMatcher.normalize(local.title)) return false
        val songs = tracks.distinctBy { it.id }
        val localSongs = local.tracks.distinctBy { it.id }
        if (songs.isEmpty() || songs.size != localSongs.size) return false
        val ids = localSongs.mapTo(HashSet()) { it.id }
        val titles = localSongs.mapTo(HashSet()) { baseTitle(it) }
        val same = songs.count { it.id in ids || matchedId(it.id) in ids || baseTitle(it) in titles }
        return same * 10 >= songs.size * 9
    }

    private fun baseTitle(track: Track): String =
        SearchQueryMatcher.normalize(track.title.substringBefore(" - ").replace(Regex("""[\(\[].*"""), ""))

    private fun songKey(track: Track): String {
        val artist = track.artist.split(",").first()
        return "${SearchQueryMatcher.normalize(track.title)}|${SearchQueryMatcher.normalize(artist)}"
    }
}
