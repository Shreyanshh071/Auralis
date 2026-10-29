package com.auralis.music.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.playlistsync.*
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.ui.components.GlassAlertDialog
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
    GlassAlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Create playlist") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, singleLine = true, enabled = !busy && localId == null,
                label = { Text("Playlist name") }, modifier = Modifier.fillMaxWidth())
            Text("Sync this playlist with", style = MaterialTheme.typography.titleSmall)
            Column {
                FilterChip(service == null, { service = null }, enabled = !busy && localId == null, label = { Text("Auralis only") })
                SyncService.entries.forEach { item -> FilterChip(service == item, { service = item }, enabled = !busy && localId == null,
                    label = { Text(item.label) }) }
            }
            Text(service?.let { "Creates a private playlist in your ${it.label} account and keeps additions and edits synced automatically in both directions." }
                ?: "Saved in Auralis without connecting a service.", style = MaterialTheme.typography.bodySmall)
            if (request.tracks.isNotEmpty()) Text("${request.tracks.size} song(s) will be added.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { Button(enabled = name.isNotBlank() && !busy, onClick = {
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
        }) { Text(if (busy) "Creating…" else "Create playlist") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}
