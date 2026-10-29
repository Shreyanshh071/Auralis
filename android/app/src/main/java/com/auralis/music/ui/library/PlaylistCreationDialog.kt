package com.auralis.music.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.auralis.music.data.playlistsync.*
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.LibraryRepository

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

data class PlaylistCreationRequest(val tracks: List<Track> = emptyList(), val onCreated: ((Playlist) -> Unit)? = null)
val LocalPlaylistCreation = staticCompositionLocalOf<((PlaylistCreationRequest) -> Unit)?> { null }
class ImportSyncChoice {
    var enabled by mutableStateOf(false)
    var changed: (Boolean) -> Unit = {}
}
val LocalImportSyncChoice = staticCompositionLocalOf<ImportSyncChoice?> { null }

@Composable
fun PlaylistImportSyncToggle() {
    val choice = LocalImportSyncChoice.current ?: return
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Keep imported playlists in sync")
            val descriptionStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp)
            Text("Two-way sync with YouTube Music playlists.", style = descriptionStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Currently works with YouTube Music only. Spotify playlists can be imported, but changes won’t sync.",
                style = descriptionStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(choice.enabled, { choice.enabled = it; choice.changed(it) })
    }
}

@Composable
fun PlaylistCreationDialog(library: LibraryRepository, engine: PlaylistSyncEngine, request: PlaylistCreationRequest,
    onCreated: (Playlist) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var service by remember { mutableStateOf<SyncService?>(null) }
    var localId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    PlaylistCreationDialogLayout(name = name, onNameChange = { name = it },
        youtubeSync = service == SyncService.YOUTUBE,
        onYoutubeSyncChange = { service = if (it) SyncService.YOUTUBE else null },
        busy = busy, saved = localId != null, trackCount = request.tracks.size, error = error,
        onDismiss = onDismiss, onCreate = {
            busy = true; error = null
            scope.launch {
                try {
                    val id = localId ?: withContext(Dispatchers.IO) { library.createPlaylist(name.trim()).id }.also { localId = it }
                    val created = withContext(Dispatchers.IO) {
                        request.tracks.forEach { library.addTrackToPlaylist(id, it) }
                        service?.let { engine.connectCreated(id, it) }
                        library.getPlaylist(id).first() ?: error("The playlist no longer exists.")
                    }
                    request.onCreated?.invoke(created); onCreated(created)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = (if (localId != null) "Saved in Auralis. " else "") + (e.message ?: "Could not finish creating the playlist.") }
                finally { busy = false }
            }
        })
}
