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

    private fun songKey(track: Track): String {
        val artist = track.artist.split(",").first()
        return "${SearchQueryMatcher.normalize(track.title)}|${SearchQueryMatcher.normalize(artist)}"
    }
}
