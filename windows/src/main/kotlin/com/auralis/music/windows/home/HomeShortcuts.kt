package com.auralis.music.windows.home

import com.auralis.music.ui.components.IconButton
import com.auralis.music.ui.components.TextButton

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material.icons.outlined.MoreVert
import com.auralis.music.ui.components.secondaryClick
import com.auralis.music.windows.player.ArtworkAura
import com.auralis.music.windows.player.AuraPresentation
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
    add: (SpeedDialItem) -> Unit, remove: (SpeedDialItem) -> Unit,
    onMenu: (SpeedDialItem) -> Unit = {}, artistActions: FamiliarArtistActions? = null,
    playlistCovers: Map<String, com.auralis.music.domain.model.Playlist> = emptyMap()) {
    val freshPins = pins.map { pin -> candidates.firstOrNull { it.id == pin.id } ?: pin }
    val row = androidx.compose.foundation.lazy.rememberLazyListState()
    var picking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.GridView, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Shortcuts", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = { query = ""; picking = true }) { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Text("Add") }
        }
        if (freshPins.isEmpty()) Text("Add playlists and artists you want to reach quickly.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyRow(state = row, modifier = Modifier.mouseDragScroll(row), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(freshPins, key = { it.id }) { item ->
                var artistMenu by remember { mutableStateOf(false) }
                val artist = FamiliarArtist(item.name, 0, item.id, item.image)
                val menu = { if (item.type == SpeedDialType.ARTIST && artistActions != null) artistMenu = true else onMenu(item) }
                Box(Modifier.width(292.dp).height(64.dp).clip(RoundedCornerShape(12.dp))
                    .secondaryClick(menu).clickable { open(item) }) {
                    ArtworkAura(com.auralis.music.ui.theme.DefaultArtworkPalette, Modifier.matchParentSize(),
                        item.image ?: item.track?.thumbnail, AuraPresentation.SHORTCUT)
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        val playlist = playlistCovers[item.album?.id ?: item.id.removePrefix("playlist-")]
                        if (playlist != null) Box(Modifier.size(64.dp)) {
                            com.auralis.music.ui.library.PlaylistCoverArt(playlist, emptyList(), 0.dp)
                        } else ShortcutPhoto(item)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color.White)
                            Text(item.album?.author ?: if (item.type == SpeedDialType.ARTIST) "Artist" else "Playlist",
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .72f))
                        }
                        Box {
                            IconButton(onClick = menu, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Outlined.MoreVert, "Options for ${item.name}", Modifier.size(18.dp), tint = androidx.compose.ui.graphics.Color.White)
                            }
                            if (artistActions != null) ArtistMenu(artist, artistMenu, { artistMenu = false }, artistActions)
                        }
                        IconButton(onClick = { remove(item) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Outlined.Close, "Remove ${item.name} from shortcuts", Modifier.size(14.dp), tint = androidx.compose.ui.graphics.Color.White.copy(alpha = .7f))
                        }
                    }
                }
            }
        }
    }
    if (picking) com.auralis.music.ui.components.GlassAlertDialog(onDismissRequest = { picking = false }, title = { Text("Add a shortcut") },
        confirmButton = { TextButton(onClick = { picking = false }) { Text("Done") } }, text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Filter playlists and artists") })
                val matches = candidates.filter { it.name.contains(query, true) }.distinctBy { it.id }
                if (matches.isEmpty()) Text("No matching playlists or artists.")
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(matches, key = { it.id }) { item ->
                        ShortcutCandidateRow(item, pins.any { it.id == item.id }) { add(item) }
                    }
                }
            }
        })
}

@Composable
internal fun ShortcutCandidateRow(item: SpeedDialItem, pinned: Boolean, onAdd: () -> Unit) {
    val interaction = remember(item.id) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(com.auralis.music.ui.components.smoothHoverColor(androidx.compose.ui.graphics.Color.White.copy(alpha = if (hovered && !pinned) .12f else 0f)))
        .hoverable(interaction)
        .clickable(interactionSource = interaction, indication = null, enabled = !pinned, onClick = onAdd)
        .padding(start = 8.dp, end = 20.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        ShortcutPhoto(item)
        Text(item.name, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (pinned) "Added" else "Add", color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ShortcutPhoto(item: SpeedDialItem) {
    Box(Modifier.size(64.dp).then(if (item.type == SpeedDialType.ARTIST) Modifier.padding(8.dp).clip(CircleShape) else Modifier),
        contentAlignment = Alignment.Center) {
        if (item.image.isNullOrBlank()) Icon(Icons.Outlined.GridView, item.name, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else AsyncImage(item.image, item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
