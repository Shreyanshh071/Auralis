package com.auralis.music.windows.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import com.auralis.music.desktop.library.DesktopDownloads
import com.auralis.music.desktop.playback.DesktopPlaybackController

@Composable
internal fun DesktopDownloadsPage(playback: DesktopPlaybackController?, back: () -> Unit,
    downloads: DesktopDownloads = DesktopDownloads.instance, playlists: List<com.auralis.music.domain.model.Playlist> = emptyList()) {
    val entries by downloads.entries.collectAsState()
    val directory by downloads.rootDirectory.collectAsState()
    val scope = rememberCoroutineScope()
    var choosingDirectory by remember { mutableStateOf(false) }
    var directoryMessage by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    val groups = entries.groupBy { it.groupId }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { if (selected == null) back() else selected = null }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
            }
            Text(if (selected == null) "Downloads" else groups[selected]?.firstOrNull()?.groupName ?: "Downloads",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Save location · ${directory.absolutePath}", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = !choosingDirectory, onClick = {
                choosingDirectory = true
                scope.launch {
                    try {
                        val picked = withContext(Dispatchers.IO) {
                            var result: File? = null
                            SwingUtilities.invokeAndWait {
                                val chooser = JFileChooser(directory).apply {
                                    dialogTitle = "Choose download folder"
                                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                    isAcceptAllFileFilterUsed = false
                                }
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) result = chooser.selectedFile
                            }
                            result
                        }
                        if (picked != null) {
                            withContext(Dispatchers.IO) { downloads.changeDirectory(picked) }
                            directoryMessage = "New downloads will use this folder. Existing downloads remain available in their original folders."
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { directoryMessage = e.message ?: "Couldn't change the download folder." }
                    finally { choosingDirectory = false }
                }
            }) { Text("Change folder") }
        }
        directoryMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (entries.isEmpty()) {
            Text("No downloads yet", style = MaterialTheme.typography.titleLarge)
            Text("Download a song from its menu, or download a playlist from Playlist options.")
        } else if (selected == null) {
            Text("Downloaded Folders (${groups.size})", style = MaterialTheme.typography.titleLarge)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(groups.keys.sortedBy { if (it == "individual") 0 else 1 }, key = { it }) { id ->
                val values = groups.getValue(id)
                val playlistCover = values.firstNotNullOfOrNull { it.coverUrl } ?: playlists.firstOrNull { it.id == id }?.coverUrl
                var menu by remember { mutableStateOf(false) }
                Surface(onClick = { selected = id }, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f), RoundedCornerShape(18.dp)),
                    color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(80.dp)) {
                            DownloadFolderArtwork(values, if (id == "individual") null else playlistCover)
                            Box(Modifier.align(Alignment.BottomEnd).size(26.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(values.first().groupName, style = MaterialTheme.typography.titleLarge)
                            val saved = values.count(downloads::available)
                            val busy = values.count { it.status in setOf("QUEUED", "DOWNLOADING") }
                            val failed = values.count { it.status == "FAILED" }
                            Text(buildString {
                                append("$saved songs · Offline")
                                if (busy > 0) append(" · $busy downloading")
                                if (failed > 0) append(" · $failed failed")
                            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Folder options") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Open folder") }, onClick = { menu = false; selected = id })
                                DropdownMenuItem(text = { Text("Remove downloads") }, onClick = { menu = false; runCatching { downloads.removeGroup(id) }.onFailure { directoryMessage = it.message } })
                            }
                        }
                    }
                }
            }
            }
        } else {
            val values = groups[selected].orEmpty().sortedBy { it.index }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = values.any(downloads::available), onClick = {
                    val tracks = values.filter(downloads::available).map { it.track }
                    tracks.firstOrNull()?.let { playback?.playFrom(it, tracks, automatic = false) }
                }) { Text("Play downloaded") }
                TextButton(onClick = { runCatching { selected?.let(downloads::removeGroup); selected = null }.onFailure { directoryMessage = it.message } }) { Text("Remove folder downloads") }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(values, key = { it.key }) { e ->
                    val available = downloads.available(e)
                    Column(Modifier.fillMaxWidth().clickable(enabled = available) {
                        playback?.playFrom(e.track, values.filter(downloads::available).map { it.track }, automatic = false)
                    }.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            com.auralis.music.ui.components.ArtworkCard(
                                url = e.track.thumbnail.takeIf { it.isNotBlank() } ?: e.coverUrl,
                                fallbackTrack = e.track, modifier = Modifier.size(56.dp),
                                cornerRadius = 8.dp, contentDescription = "Cover for ${e.track.title}")
                            Column(Modifier.weight(1f)) {
                                Text(e.track.title, style = MaterialTheme.typography.titleMedium)
                                Text(e.track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (available) "Saved · ${e.bytes / 1024} KB" else e.error ?: e.status.lowercase().replaceFirstChar { it.titlecase() })
                            }
                            TextButton(onClick = { runCatching { downloads.remove(e.key) }.onFailure { directoryMessage = it.message } }) { Text("Remove") }
                        }
                        if (e.status == "DOWNLOADING") {
                            if (e.total > 0) LinearProgressIndicator(progress = { (e.bytes.toFloat() / e.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DownloadFolderArtwork(entries: List<DesktopDownloads.Entry>, cover: String?) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) {
        if (!cover.isNullOrBlank()) com.auralis.music.ui.components.ArtworkCard(
            url = cover, fallbackTrack = entries.firstOrNull()?.track, modifier = Modifier.fillMaxSize(),
            cornerRadius = 12.dp, contentDescription = "Playlist cover")
        else com.auralis.music.ui.library.PlaylistCoverArt(
            com.auralis.music.domain.model.Playlist("download-collage", "Downloaded songs", tracks = entries.sortedBy { it.index }.map { it.track }),
            emptyList(), 12.dp)
    }
}
