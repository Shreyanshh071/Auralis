package com.auralis.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface

// Library pieces the playlist sheet and Home share with the phone's Library screen:
// cover art, album/import detection and the sheet's dialogs.

val CARD_DARK_BG: Color @Composable get() = MaterialTheme.dynamicSurface

val LIME_TEXT: Color @Composable get() = MaterialTheme.dynamicPrimary

fun isRemovableMostPlayedPlaylist(id: String) =
    id == "smart_top_50" || id == "smart_weekly_most" || id == "smart_monthly_most"

/**
 * A playlist's cover exactly as its page shows it: the album art for an album, the chosen photo,
 * the 4-cover collage, or the first song's art. The edit dialog previews through this too, so
 * what it shows is what saving gives.
 */
@Composable
fun PlaylistCoverArt(
    playlist: Playlist,
    savedAlbums: List<SavedAlbum>,
    cornerRadius: Dp
) {
    val detailValidTracks = remember(playlist.tracks) {
        playlist.tracks.filter { !it.thumbnail.isNullOrBlank() || it.id.isNotBlank() }
    }
    val detailDistinctTracks = remember(playlist.tracks) {
        getDistinctArtworkTracks(playlist.tracks)
    }

    val isAlbum = remember(playlist.id, playlist.title, playlist.description, playlist.tracks, savedAlbums) {
        isAlbumPlaylist(playlist, savedAlbums)
    }

    if (isAlbum) {
        val albumCoverUrl = remember(playlist.coverUrl, playlist.tracks, savedAlbums) {
            resolveAlbumArtworkUrl(playlist, savedAlbums)
        }
        ArtworkCard(
            url = albumCoverUrl,
            fallbackTrack = detailValidTracks.firstOrNull() ?: playlist.tracks.firstOrNull(),
            modifier = Modifier.fillMaxSize(),
            cornerRadius = cornerRadius,
            contentDescription = playlist.title
        )
    } else if (!playlist.coverUrl.isNullOrBlank() && !usesTrackCollage(playlist, detailDistinctTracks.size)) {
        ArtworkCard(
            url = playlist.coverUrl,
            fallbackTrack = detailValidTracks.firstOrNull(),
            modifier = Modifier.fillMaxSize(),
            cornerRadius = cornerRadius,
            contentDescription = playlist.title
        )
    } else if (detailDistinctTracks.size >= 4) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ArtworkCard(
                    url = detailDistinctTracks[0].thumbnail,
                    fallbackTrack = detailDistinctTracks[0],
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    cornerRadius = 0.dp,
                    contentDescription = null
                )
                ArtworkCard(
                    url = detailDistinctTracks[1].thumbnail,
                    fallbackTrack = detailDistinctTracks[1],
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    cornerRadius = 0.dp,
                    contentDescription = null
                )
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ArtworkCard(
                    url = detailDistinctTracks[2].thumbnail,
                    fallbackTrack = detailDistinctTracks[2],
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    cornerRadius = 0.dp,
                    contentDescription = null
                )
                ArtworkCard(
                    url = detailDistinctTracks[3].thumbnail,
                    fallbackTrack = detailDistinctTracks[3],
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    cornerRadius = 0.dp,
                    contentDescription = null
                )
            }
        }
    } else if (detailValidTracks.isNotEmpty()) {
        ArtworkCard(
            url = detailValidTracks.first().thumbnail,
            fallbackTrack = detailValidTracks.first(),
            modifier = Modifier.fillMaxSize(),
            cornerRadius = cornerRadius,
            contentDescription = playlist.title
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = LIME_TEXT,
                modifier = Modifier.size(64.dp)
            )
        }
    }
}

/**
 * Deduplicates tracks to find distinct album/artwork representations.
 * If all tracks share the same album or thumbnail (e.g. an added album),
 * this returns a list of size 1 so that a single full-size album artwork
 * is displayed rather than a 4-quadrant collage of identical images.
 */
fun getDistinctArtworkTracks(tracks: List<Track>): List<Track> {
    val seenArtworkKeys = mutableSetOf<String>()
    val result = mutableListOf<Track>()
    for (track in tracks) {
        if (track.thumbnail.isBlank() && track.id.isBlank()) continue
        val cleanThumb = track.thumbnail.substringBefore("=").substringBefore("?").trim()
        val albumKey = when {
            !track.albumId.isNullOrBlank() -> "albumId:${track.albumId}"
            !track.album.isNullOrBlank() && !track.album.equals("Single", ignoreCase = true) -> "album:${track.album!!.trim().lowercase()}"
            else -> null
        }
        val thumbKey = if (cleanThumb.isNotBlank()) "thumb:$cleanThumb" else null
        val idKey = "track:${track.id}"

        // A shared album label does not make two different track covers identical.
        val hasMatch = if (thumbKey != null) thumbKey in seenArtworkKeys
            else albumKey != null && albumKey in seenArtworkKeys

        if (!hasMatch) {
            albumKey?.let { seenArtworkKeys.add(it) }
            thumbKey?.let { seenArtworkKeys.add(it) }
            seenArtworkKeys.add(idKey)
            result.add(track)
        }
    }
    return result
}

fun usesTrackCollage(playlist: Playlist, distinctCoverCount: Int): Boolean {
    if (distinctCoverCount < 4) return false
    val cover = playlist.coverUrl.orEmpty()
    if (cover.isBlank()) return true
    // Spotify account imports have a playlist cover chosen by the owner. Show that
    // artwork instead of replacing it with a collage of the playlist's songs.
    if (isSpotifyImport(playlist)) return false
    // The editor stores chosen photos as data/content/file URIs. Network covers come from
    // imports or automatic artwork and should not hide a mixed playlist's four song covers.
    return !cover.startsWith("data:") && !cover.startsWith("content:") && !cover.startsWith("file:")
}

/**
 * Determines whether a playlist item represents an album rather than a user collection/playlist.
 */
fun isAlbumPlaylist(
    playlist: Playlist,
    savedAlbums: List<SavedAlbum> = emptyList()
): Boolean {
    val id = playlist.id
    // 1. YouTube Music album browse ID or Auralis synthetic album ID
    if (id.startsWith("album-") || id.startsWith("album:") || id.startsWith("MPREb_") || id.startsWith("OLAK5uy_") || id.startsWith("VLOLAK5uy_")) {
        return true
    }
    if (com.auralis.music.domain.recommendations.SpeedDialIdHelper.isAlbumId(id) && !id.startsWith("VLPL") && !id.startsWith("PL")) {
        return true
    }

    // 2. Description explicitly created by Auralis album-addition or album import
    val desc = playlist.description?.trim()
    if (desc != null && (desc.startsWith("Album by ", ignoreCase = true) ||
            desc.startsWith("Album •", ignoreCase = true) ||
            desc.equals("Album", ignoreCase = true) ||
            desc.startsWith("EP by ", ignoreCase = true) ||
            desc.startsWith("Single by ", ignoreCase = true))) {
        return true
    }

    // A playlist imported from an account keeps its playlist identity even when its title
    // matches a saved album or all of its tracks happen to come from one album.
    if (id.startsWith("imported:spotify:") || id.startsWith("imported:youtube_music:") || id.startsWith("sp_")) {
        return false
    }

    // 3. Matched against user's saved albums
    if (savedAlbums.isNotEmpty()) {
        val cleanTitle = playlist.title.trim()
        if (savedAlbums.any { it.id == id || it.title.equals(cleanTitle, ignoreCase = true) }) {
            return true
        }
    }

    // 4. Track album metadata consistency
    if (playlist.tracks.isNotEmpty()) {
        val cleanPlaylistTitle = playlist.title.trim()

        // Check if all tracks with albumId share the same albumId
        val albumIds = playlist.tracks.mapNotNull { it.albumId?.trim()?.takeIf { aId -> aId.isNotBlank() } }
        if (albumIds.isNotEmpty() && albumIds.all { it == albumIds.first() }) {
            return true
        }

        // Check if all tracks with album name belong to the same album
        val meaningfulAlbums = playlist.tracks.mapNotNull {
            it.album?.trim()?.takeIf { a -> a.isNotBlank() && !a.equals("Single", ignoreCase = true) }
        }
        if (meaningfulAlbums.isNotEmpty() &&
            meaningfulAlbums.all { it.equals(meaningfulAlbums.first(), ignoreCase = true) } &&
            meaningfulAlbums.size >= (playlist.tracks.size / 2).coerceAtLeast(1)) {
            return true
        }

        // Check if the playlist title matches the album of the tracks
        if (playlist.tracks.any { it.album?.trim()?.equals(cleanPlaylistTitle, ignoreCase = true) == true }) {
            return true
        }
    }

    return false
}

/**
 * Resolves the canonical single album artwork URL using canonical Auralis metadata sources.
 */
fun resolveAlbumArtworkUrl(
    playlist: Playlist,
    savedAlbums: List<SavedAlbum> = emptyList()
): String? {
    // 1. Direct album cover if set
    if (!playlist.coverUrl.isNullOrBlank()) {
        return playlist.coverUrl
    }

    // 2. SavedAlbum thumbnail if present
    if (savedAlbums.isNotEmpty()) {
        val cleanTitle = playlist.title.trim()
        val matchingSaved = savedAlbums.firstOrNull { it.id == playlist.id || it.title.equals(cleanTitle, ignoreCase = true) }
        if (!matchingSaved?.thumbnail.isNullOrBlank()) {
            return matchingSaved?.thumbnail
        }
    }

    // 3. Track's thumbnail if tracks are present (album tracks inherently carry the official album thumbnail)
    val trackThumb = playlist.tracks.firstOrNull { !it.thumbnail.isNullOrBlank() }?.thumbnail
    if (!trackThumb.isNullOrBlank()) {
        return trackThumb
    }

    // 4. Cached album artwork in AlbumMetadataResolver across tracks ONLY IF the cached album title matches the playlist title!
    val cleanPlaylistTitle = playlist.title.trim()
    for (track in playlist.tracks) {
                val cached = com.auralis.music.data.network.AlbumMetadataResolver.getCached(track.title, track.artist, track.album)
        if (!cached?.albumArt.isNullOrBlank() &&
            !cached?.albumTitle.isNullOrBlank() &&
            (cached.albumTitle.equals(cleanPlaylistTitle, ignoreCase = true) ||
             cleanPlaylistTitle.contains(cached.albumTitle, ignoreCase = true) ||
             cached.albumTitle.contains(cleanPlaylistTitle, ignoreCase = true))) {
            return cached.albumArt
        }
    }

    // 5. Cached artwork in ArtworkResolver across tracks
    for (track in playlist.tracks) {
        val art = com.auralis.music.data.network.ArtworkResolver.getArtwork(track)
        if (!art.isNullOrBlank()) {
            return art
        }
    }

    return null
}

enum class PlaylistDialogType {
    EDIT, EXPORT, DELETE, DELETE_ALL_DOWNLOADS
}

/** Imported playlists carry their source in their ID; older Spotify imports used an "sp_" prefix. */
fun isSpotifyImport(playlist: Playlist): Boolean =
    playlist.id.startsWith("imported:spotify:") || playlist.id.startsWith("sp_")

fun isYouTubeMusicImport(playlist: Playlist): Boolean =
    playlist.id.startsWith("imported:youtube_music:")
