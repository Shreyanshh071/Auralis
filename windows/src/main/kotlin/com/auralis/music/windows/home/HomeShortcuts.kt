package com.auralis.music.windows.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.auralis.music.domain.model.SpeedDialItem
import com.auralis.music.domain.model.SpeedDialType

@Composable
internal fun HomeShortcuts(pins: List<SpeedDialItem>, candidates: List<SpeedDialItem>, open: (SpeedDialItem) -> Unit,
    add: (SpeedDialItem) -> Unit, remove: (SpeedDialItem) -> Unit) {
    val freshPins = pins.map { pin -> candidates.firstOrNull { it.id == pin.id } ?: pin }
    var picking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.GridView, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Shortcuts", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = { query = ""; picking = true }) { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Text("Add") }
        }
        if (freshPins.isEmpty()) Text("Add playlists and artists you want to reach quickly.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(freshPins, key = { it.id }) { item ->
                Row(Modifier.width(264.dp).height(64.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer).clickable { open(item) }, verticalAlignment = Alignment.CenterVertically) {
                    ShortcutPhoto(item)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(item.album?.author ?: if (item.type == SpeedDialType.ARTIST) "Artist" else "Playlist",
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { remove(item) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Close, "Remove ${item.name} from shortcuts", Modifier.size(14.dp))
                    }
                }
            }
        }
    }
    if (picking) AlertDialog(onDismissRequest = { picking = false }, title = { Text("Add a shortcut") },
        confirmButton = { TextButton(onClick = { picking = false }) { Text("Done") } }, text = {
            Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Filter playlists and artists") })
                val matches = candidates.filter { it.name.contains(query, true) }.distinctBy { it.id }
                if (matches.isEmpty()) Text("No matching playlists or artists.")
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(matches, key = { it.id }) { item ->
                        val pinned = pins.any { it.id == item.id }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = !pinned) { add(item) }
                            .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ShortcutPhoto(item)
                            Text(item.name, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (pinned) "Added" else "Add", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        })
}

@Composable
private fun ShortcutPhoto(item: SpeedDialItem) {
    Box(Modifier.size(64.dp).then(if (item.type == SpeedDialType.ARTIST) Modifier.padding(8.dp).clip(CircleShape) else Modifier),
        contentAlignment = Alignment.Center) {
        if (item.image.isNullOrBlank()) Icon(Icons.Outlined.GridView, item.name, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else AsyncImage(item.image, item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
