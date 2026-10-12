package com.auralis.music.windows.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auralis.music.desktop.library.DesktopDownloads
import com.auralis.music.desktop.playback.DesktopPlaybackController

@Composable
internal fun DesktopDownloadsPage(playback: DesktopPlaybackController?, back: () -> Unit,
    downloads: DesktopDownloads = DesktopDownloads.instance) {
    val entries by downloads.entries.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    val groups = entries.groupBy { it.groupId }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (selected == null) "Downloads" else groups[selected]?.firstOrNull()?.groupName ?: "Downloads", style = MaterialTheme.typography.headlineLarge)
            TextButton(onClick = { if (selected == null) back() else selected = null }) { Text("Back") }
        }
        Text("Saved on this computer · ${downloads.root.absolutePath}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (entries.isEmpty()) {
            Text("No downloads yet", style = MaterialTheme.typography.titleLarge)
            Text("Download a song from its menu, or download a playlist from Playlist options.")
        } else if (selected == null) LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(groups.keys.sortedBy { if (it == "individual") 0 else 1 }, key = { it }) { id ->
                val values = groups.getValue(id)
                Surface(onClick = { selected = id }, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(values.first().groupName, style = MaterialTheme.typography.titleLarge)
                            Text("${values.count(downloads::available)} saved · ${values.count { it.status in setOf("QUEUED", "DOWNLOADING") }} downloading · ${values.count { it.status == "FAILED" }} failed")
                        }
                        Text("Open")
                    }
                }
            }
        } else {
            val values = groups[selected].orEmpty().sortedBy { it.index }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = values.any(downloads::available), onClick = {
                    val tracks = values.filter(downloads::available).map { it.track }
                    tracks.firstOrNull()?.let { playback?.playFrom(it, tracks) }
                }) { Text("Play downloaded") }
                TextButton(onClick = { selected?.let(downloads::retry) }) { Text("Retry failed") }
                TextButton(onClick = { selected?.let(downloads::removeGroup); selected = null }) { Text("Remove folder downloads") }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(values, key = { it.key }) { e ->
                    val available = downloads.available(e)
                    Column(Modifier.fillMaxWidth().clickable(enabled = available) {
                        playback?.playFrom(e.track, values.filter(downloads::available).map { it.track })
                    }.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(e.track.title, style = MaterialTheme.typography.titleMedium)
                                Text(e.track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (available) "Saved · ${e.bytes / 1024} KB" else e.error ?: e.status.lowercase().replaceFirstChar { it.titlecase() })
                            }
                            TextButton(onClick = { downloads.remove(e.key) }) { Text("Remove") }
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
