package com.auralis.music.windows.library

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.ui.library.LibrarySourceFilter
import com.auralis.music.ui.library.isSpotifyImport
import com.auralis.music.ui.library.isYouTubeMusicImport

private const val SavedAlbumPrefix = "album:desktop-saved:"

/** Older desktop imports have random IDs but retain the importer's explicit source label. */
internal fun desktopPlaylistSource(playlist: Playlist): LibrarySourceFilter? {
    val description = playlist.description?.lineSequence()?.firstOrNull()?.trim()
    return when {
        isSpotifyImport(playlist) || description.equals("Imported from Spotify", ignoreCase = true) -> LibrarySourceFilter.SPOTIFY
        isYouTubeMusicImport(playlist) || description.equals("Imported from YouTube Music", ignoreCase = true) ||
            description.equals("Imported from YouTube", ignoreCase = true) -> LibrarySourceFilter.YOUTUBE
        else -> null
    }
}

internal fun savedAlbumEntryId(album: SavedAlbum) = SavedAlbumPrefix + album.id

/** Saved albums are a separate store, while the shared Library grid displays playlist entries. */
internal fun desktopLibraryItems(playlists: List<Playlist>, albums: List<SavedAlbum>): List<Playlist> =
    playlists + albums.filterNot { album ->
        playlists.any { it.id.removePrefix("album-").removePrefix("VL") == album.id.removePrefix("album-").removePrefix("VL") }
    }.map { album ->
        Playlist(savedAlbumEntryId(album), album.title, "Album by ${album.artist.orEmpty()}",
            album.thumbnail, createdAt = album.savedAt, isCustom = false)
    }

/** A saved album as the album page opens it (for callers outside this file's receiver scope). */
internal fun libraryAlbumAsBrowse(album: SavedAlbum) = album.asBrowseAlbum()

internal fun SavedAlbum.asBrowseAlbum() = PlaylistResult(
    id = id, title = title, thumbnail = thumbnail, author = artist, trackCount = trackCount, releaseType = "album"
)

/** Preserve saved album identity; local playlist shortcuts open the local playlist page. */
internal fun playlistShortcutTarget(playlist: Playlist): PlaylistResult = PlaylistResult(
    id = if (playlist.id.startsWith(SavedAlbumPrefix)) playlist.id.removePrefix(SavedAlbumPrefix) else "local:${playlist.id}",
    title = playlist.title, thumbnail = playlist.coverUrl ?: playlist.tracks.firstOrNull()?.thumbnail,
    author = if (playlist.id.startsWith(SavedAlbumPrefix)) playlist.description?.removePrefix("Album by ") else "Playlist · ${playlist.tracks.size} songs"
)
