package com.auralis.music.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.auralis.music.domain.model.Playlist

private class PlaylistSnapshot(var value: Playlist? = null)

/** Navigation may clear selection before AnimatedContent finishes drawing its outgoing page. */
@Composable
internal fun rememberPlaylistForExit(
    playlistId: String,
    selected: Playlist?,
    library: List<Playlist>
): Playlist? {
    val snapshot = remember(playlistId) { PlaylistSnapshot() }
    val current = selected?.takeIf { it.id == playlistId }
        ?: library.firstOrNull { it.id == playlistId }
        ?: snapshot.value
    // Retain the last committed version, including live download progress and track ordering.
    // The holder needs no observable state: selection/library changes already recompose this page.
    SideEffect { snapshot.value = current }
    return current
}
