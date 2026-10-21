package com.auralis.music.windows.home

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.domain.model.PlaylistResult

/** History can contain an imported playlist's title in the album field. Prefer catalogue identity. */
internal fun historyAlbumIdentity(album: PlaylistResult,
    lookup: (String) -> Pair<String, String>? = InnerTubeClient::cachedQueueAlbum): PlaylistResult {
    if (!album.id.startsWith("album:")) return album
    val known = lookup(album.id.removePrefix("album:")) ?: return album
    if (known.first.isBlank() || !known.second.startsWith("MPRE")) return album
    return album.copy(id = known.second, title = known.first)
}
