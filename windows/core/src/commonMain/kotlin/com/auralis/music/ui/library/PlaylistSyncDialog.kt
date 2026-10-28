package com.auralis.music.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auralis.music.data.playlistsync.*
import com.auralis.music.domain.model.Playlist
import com.auralis.music.ui.components.GlassAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

val LocalPlaylistSync = staticCompositionLocalOf<((Playlist) -> Unit)?> { null }

@Composable
fun PlaylistSyncDialog(playlist: Playlist, engine: PlaylistSyncEngine, onDismiss: () -> Unit) {
    val links by engine.store.links.collectAsState()
    val origin = engine.source(playlist.id)
    if (origin == null) {
        // A locally created playlist has no import source to infer.
        PlaylistSyncSetupDialog(playlist, engine, onDismiss)
        return
    }
    val link = links.firstOrNull { it.localId == playlist.id && it.enabled }
    val enabled = link != null
    val status by engine.status.collectAsState()
    var busy by remember(playlist.id) { mutableStateOf(false) }
    var pendingToggle by remember(playlist.id) { mutableStateOf<Boolean?>(null) }
    var error by remember(playlist.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    GlassAlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Playlist sync") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(playlist.title, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Sync with ${origin.first.label}", modifier = Modifier.weight(1f))
                Switch(checked = pendingToggle ?: enabled, enabled = !busy, onCheckedChange = { turnOn ->
                    pendingToggle = turnOn; busy = true; error = null
                    scope.launch {
                        try {
                            if (turnOn) engine.enable(playlist.id)
                            else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { engine.disable(playlist.id) }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Could not connect playlist sync. Your songs are kept." }
                        finally { busy = false; pendingToggle = null }
                    }
                })
            }
            Text("Add a song here and it is added to the same ${origin.first.label} playlist. Songs added there appear here automatically too.",
                style = MaterialTheme.typography.bodySmall)
            Text("${origin.first.label} changes are checked about every five seconds while Auralis is running. Turning sync off keeps both playlists.",
                style = MaterialTheme.typography.bodySmall)
            if (busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Connecting playlist sync…", style = MaterialTheme.typography.bodySmall)
            } else if (enabled) Text(status[playlist.id] ?: if (link?.interrupted == true) "Sync pending${link.failure?.let { ": $it" }.orEmpty()}" else "Sync is on", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Done") } })
}

@Composable
private fun PlaylistSyncSetupDialog(playlist: Playlist, engine: PlaylistSyncEngine, onDismiss: () -> Unit) {
    val links by engine.store.links.collectAsState()
    val link = links.firstOrNull { it.localId == playlist.id }
    val status by engine.status.collectAsState()
    val imported = remember(playlist.id) { PlaylistSyncStore.imported(playlist.id) }
    var service by remember { mutableStateOf(link?.service ?: imported?.first ?: SyncService.YOUTUBE) }
    var remoteId by remember { mutableStateOf(link?.remoteId ?: imported?.second.orEmpty()) }
    var createNew by remember { mutableStateOf(remoteId.isBlank()) }
    var preview by remember { mutableStateOf<PlaylistSyncPreview?>(null) }
    var choice by remember { mutableStateOf<SyncChoice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun perform(action: suspend () -> Unit) {
        busy = true; error = null
        scope.launch { try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not sync. Your playlist is kept." }
            finally { busy = false } }
    }
    GlassAlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Playlist sync") },
        text = { Column(Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(playlist.title, style = MaterialTheme.typography.titleMedium)
            Text("Two-way sync keeps songs, order, title and description updated in both apps. Your connected account must be able to edit the playlist.",
                style = MaterialTheme.typography.bodySmall)
            if (link != null) {
                Text("Linked to ${link.service.label} · ${status[playlist.id] ?: if (link.interrupted) "Review needed" else "Sync enabled"}")
                if (link.syncedAt > 0) Text("Last synced: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(link.syncedAt))}",
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncService.entries.forEach { item -> FilterChip(selected = service == item, enabled = !busy && link == null,
                    onClick = { service = item; preview = null; choice = null; remoteId = ""; createNew = true }, label = { Text(item.label) }) }
            }
            if (link == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = createNew, enabled = !busy, onClick = { createNew = true; preview = null; choice = null }, label = { Text("Create private playlist") })
                    FilterChip(selected = !createNew, enabled = !busy, onClick = { createNew = false; preview = null; choice = null }, label = { Text("Link existing") })
                }
            }
            if (!createNew || link != null) OutlinedTextField(remoteId, { remoteId = it; preview = null; choice = null },
                label = { Text("${service.label} playlist link or ID") }, singleLine = true, enabled = !busy && link == null,
                modifier = Modifier.fillMaxWidth())
            Text("Turning sync off keeps both copies. Deleting a local playlist disconnects it; it does not delete the service playlist.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy && (createNew || remoteId.isNotBlank()), onClick = { perform {
                preview = engine.preview(playlist, service, if (createNew && link == null) null else remoteId.trim())
                choice = if (preview?.remote == null) SyncChoice.AURALIS else if (preview?.merged != null) SyncChoice.MERGE else null
            } }) { Text(if (busy) "Checking…" else "Preview changes / Sync now") }
            preview?.let { p ->
                HorizontalDivider()
                Text("Review before syncing", style = MaterialTheme.typography.titleSmall)
                Text("Auralis: ${p.localSnapshot.title} · ${p.localSnapshot.ids.size} songs")
                Text(p.remote?.let { "${service.label}: ${it.snapshot.title} · ${it.snapshot.ids.size} songs" }
                    ?: "A new private playlist will be created on ${service.label}.")
                if (p.remote != null) {
                    p.merged?.let { merge -> FilterChip(choice == SyncChoice.MERGE,
                        onClick = { choice = SyncChoice.MERGE }, enabled = !busy,
                        label = { Text("Merge independent changes · ${merge.ids.size} songs") }) }
                    FilterChip(choice == SyncChoice.AURALIS, onClick = { choice = SyncChoice.AURALIS }, enabled = !busy,
                        label = { Text("Use Auralis version on both sides") })
                    FilterChip(choice == SyncChoice.REMOTE, onClick = { choice = SyncChoice.REMOTE }, enabled = !busy,
                        label = { Text("Use ${service.label} version on both sides") })
                    if (p.merged == null) Text("Changes conflict. Choose which version to keep; nothing changes until you confirm.", style = MaterialTheme.typography.bodySmall)
                }
                val target = when (choice) { SyncChoice.AURALIS -> p.localSnapshot; SyncChoice.REMOTE -> p.remote?.snapshot
                    SyncChoice.MERGE -> p.merged; null -> null }
                target?.let { desired ->
                    val old = p.remote?.snapshot?.ids.orEmpty()
                    val oldCounts = old.groupingBy { it }.eachCount()
                    val newCounts = desired.ids.groupingBy { it }.eachCount()
                    val added = newCounts.entries.sumOf { (id, count) -> (count - (oldCounts[id] ?: 0)).coerceAtLeast(0) }
                    val removed = oldCounts.entries.sumOf { (id, count) -> (count - (newCounts[id] ?: 0)).coerceAtLeast(0) }
                    Text("Result: ${desired.title} · ${desired.ids.size} songs. $added additions, $removed removals on ${service.label}.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Description: ${desired.description.ifBlank { "None" }}", style = MaterialTheme.typography.bodySmall)
                    val names = p.mapping.mapNotNull { (localId, remoteId) -> p.local.tracks.find { it.id == localId }?.let { remoteId to it.title } }.toMap() +
                        p.remote?.tracks.orEmpty().zip(p.remote?.snapshot?.ids.orEmpty()).associate { (track, id) -> id to track.title }
                    Text("Song order", style = MaterialTheme.typography.labelLarge)
                    desired.ids.forEachIndexed { index, id ->
                        Text("${index + 1}. ${names[id] ?: id}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { Button(enabled = preview != null && choice != null && !busy, onClick = { perform {
            engine.apply(preview!!, choice!!); onDismiss()
        } }) { Text(if (busy) "Syncing…" else if (link == null) "Enable two-way sync" else "Apply sync") } },
        dismissButton = { Row {
            if (link != null) TextButton(enabled = !busy, onClick = { engine.disable(playlist.id); onDismiss() }) { Text("Turn sync off") }
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") }
        } })
}
