package com.auralis.music.windows.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.ui.components.GlassAlertDialog
import com.auralis.music.ui.components.LocalPlatformActions
import com.auralis.music.windows.library.OptionsMenuPopup
import com.auralis.music.ui.components.clickable
import kotlinx.coroutines.*

internal val LocalPlayerPlaylists = staticCompositionLocalOf<LibraryRepository?> { null }

/** Direct playlist picker, shared by the bottom player and its floating popup. */
@Composable
internal fun PlayerPlaylistButton(track: Track, modifier: Modifier = Modifier,
    onMenuVisibilityChanged: (Boolean) -> Unit = {}) {
    val library = LocalPlayerPlaylists.current ?: return
    val platform = LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    val menuDensity = LocalDensity.current
    val creator = com.auralis.music.ui.library.LocalPlaylistCreation.current
    var anchor by remember { mutableStateOf<IntOffset?>(null) }
    var menu by remember(track.id) { mutableStateOf(false) }
    var create by remember(track.id) { mutableStateOf(false) }
    var name by remember(track.id) { mutableStateOf("") }
    var createdId by remember(track.id) { mutableStateOf<String?>(null) }
    var busy by remember(track.id) { mutableStateOf(false) }
    var error by remember(track.id) { mutableStateOf<String?>(null) }
    val visibility by rememberUpdatedState(onMenuVisibilityChanged)
    LaunchedEffect(menu, create, busy) { visibility(menu || create || busy) }
    DisposableEffect(track.id) { onDispose { visibility(false) } }
    fun perform(action: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { withContext(Dispatchers.IO) { action() }; menu = false; create = false }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not add this song. Please try again." }
            finally { busy = false }
        }
    }
    Box(Modifier.onGloballyPositioned { val p = it.positionInWindow(); anchor = IntOffset(p.x.toInt(), (p.y + it.size.height).toInt()) }) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        IconButton(onClick = { menu = true }, modifier = modifier.size(28.dp), enabled = !busy) {
            Box(Modifier.size(18.dp).border(1.1.dp, Color.White.copy(alpha = .9f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, "Add to playlist", Modifier.size(12.dp), tint = Color.White)
            }
        }
        }
        if (menu) {
            val playlists by remember(library) { library.getPlaylists() }.collectAsState(emptyList())
            OptionsMenuPopup(onDismiss = { if (!busy) menu = false }, anchorPosition = anchor) {
                // The general options menu uses compact button labels. This picker
                // instead keeps the same density and title/body fonts as Library.
                CompositionLocalProvider(LocalDensity provides menuDensity) {
                    PlayerPlaylistPickerContent(track, playlists, busy, error,
                        onCreate = { menu = false; if (creator != null) creator(com.auralis.music.ui.library.PlaylistCreationRequest(listOf(track))) else create = true },
                        onAdd = { playlist -> perform {
                                val added = library.addTrackToPlaylist(playlist.id, track)
                                withContext(Dispatchers.Main) { platform.showPill(if (added) "Added to ${playlist.title}" else "Already in ${playlist.title}") }
                        } })
                }
            }
        }
        if (create) GlassAlertDialog(onDismissRequest = { if (!busy) create = false },
            title = { Text("Create playlist") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, enabled = !busy && createdId == null,
                        singleLine = true, label = { Text("Playlist name") })
                    Text("This song will be added to the new playlist.", style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }, confirmButton = { Button(enabled = name.isNotBlank() && !busy, onClick = { perform {
                val id = createdId ?: library.createPlaylist(name.trim()).id.also { newId ->
                    withContext(Dispatchers.Main) { createdId = newId }
                }
                library.addTrackToPlaylist(id, track)
                withContext(Dispatchers.Main) { platform.showPill("Created ${name.trim()} and added the song") }
            } }) { Text(if (busy) "Adding…" else "Create and add") } },
            dismissButton = { TextButton(onClick = { create = false }, enabled = !busy) { Text("Cancel") } })
    }
}

@Composable
internal fun PlayerPlaylistPickerContent(track: Track, playlists: List<Playlist>, busy: Boolean,
    error: String?, onCreate: () -> Unit, onAdd: (Playlist) -> Unit) {
    val available = playlists.filter { it.isCustom }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text("Add to playlist", Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            com.auralis.music.windows.search.DesktopArtwork(track.thumbnail, Modifier.size(40.dp), 6.dp, track.title)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp, color = Color.White.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = !busy, onClick = onCreate)
            .padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = .09f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(23.dp).border(1.3.dp, Color.White.copy(alpha = .85f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(17.dp), tint = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text("Create playlist", style = MaterialTheme.typography.titleMedium, fontSize = 16.sp)
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color.White.copy(alpha = .12f))
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 360.dp)) {
            items(available, key = { it.id }) { playlist ->
                val added = playlist.tracks.any { it.id == track.id }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = !busy && !added, onClick = { onAdd(playlist) })
                    .padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))) {
                        com.auralis.music.ui.library.PlaylistCoverArt(playlist, emptyList(), 6.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playlist.title, style = MaterialTheme.typography.titleMedium, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (added) "Already added" else "${playlist.tracks.size} songs", style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp, color = Color.White.copy(alpha = .65f))
                    }
                    if (added) Icon(Icons.Rounded.Check, "Already added", Modifier.size(19.dp), tint = Color(0xFF75DFA8))
                }
            }
        }
        if (available.isEmpty()) Text("Create your first playlist above.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
    }
}
