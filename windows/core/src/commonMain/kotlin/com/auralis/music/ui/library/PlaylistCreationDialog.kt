package com.auralis.music.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
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
        // The desktop glass host also scrolls its dialog. Bound this inner form before
        // its scroll modifier so the outer scroller cannot give it infinite height.
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, singleLine = true, enabled = !busy && localId == null,
                label = { Text("Playlist name") }, modifier = Modifier.fillMaxWidth())
            // Spotify playlists import but don't sync, so YouTube Music is the only sync choice.
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Save to", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PlaylistDestination("Auralis only", "Save in your Auralis library", Icons.Default.MusicNote,
                    selected = service == null, enabled = !busy && localId == null) { service = null }
                PlaylistDestination("YouTube Music", "Private playlist with two-way sync", Icons.Default.Sync,
                    selected = service == SyncService.YOUTUBE, enabled = !busy && localId == null) { service = SyncService.YOUTUBE }
            }
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

@Composable
private fun PlaylistDestination(title: String, subtitle: String, icon: ImageVector, selected: Boolean,
    enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Surface(shape = shape,
        color = if (selected) colors.primary.copy(alpha = .12f) else colors.onSurface.copy(alpha = .035f),
        border = BorderStroke(1.dp, if (selected) colors.primary.copy(alpha = .6f) else colors.outlineVariant.copy(alpha = .5f)),
        modifier = Modifier.fillMaxWidth().clip(shape).selectable(selected, enabled = enabled,
            role = Role.RadioButton, onClick = onClick)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = if (selected) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Default.Check, null, tint = colors.primary, modifier = Modifier.size(18.dp))
            else Spacer(Modifier.size(18.dp))
        }
    }
}
