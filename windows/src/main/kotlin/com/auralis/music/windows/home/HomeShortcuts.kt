package com.auralis.music.windows.home

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
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.LibraryMusic
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
    playlistCovers: Map<String, com.auralis.music.domain.model.Playlist> = emptyMap(),
    sidebar: Boolean = false, collapsed: Boolean = false) {
    val freshPins = pins.map { pin ->
        candidates.firstOrNull { cand ->
            val candAlbum = cand.album
            val pinAlbum = pin.album
            cand.id == pin.id ||
            (candAlbum != null && pinAlbum != null && (candAlbum.id == pinAlbum.id || candAlbum.id.removePrefix("local:") == pinAlbum.id.removePrefix("local:"))) ||
            (cand.type == SpeedDialType.ARTIST && pin.type == SpeedDialType.ARTIST && (cand.id == pin.id || cand.name.equals(pin.name, true)))
        } ?: pin
    }
    val row = androidx.compose.foundation.lazy.rememberLazyListState()
    var picking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(if (sidebar) 8.dp else 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!collapsed) {
                Icon(Icons.Outlined.GridView, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Shortcuts", Modifier.weight(1f).padding(start = 8.dp), style = if (sidebar) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { query = ""; picking = true }) { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); if (!collapsed) Text("Add") }
        }
        if (freshPins.isEmpty() && !collapsed) Text("Add playlists and artists you want to reach quickly.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
          val cards: @Composable (SpeedDialItem) -> Unit = { item ->
                var artistMenu by remember { mutableStateOf(false) }
                val artist = FamiliarArtist(item.name, 0, item.id, item.image)
                val menu = { if (item.type == SpeedDialType.ARTIST && artistActions != null) artistMenu = true else onMenu(item) }
                Box((if (sidebar) Modifier.fillMaxWidth() else Modifier.width(292.dp)).height(if (sidebar) 56.dp else 64.dp).clip(RoundedCornerShape(12.dp))
                    .secondaryClick(menu).clickable { open(item) }) {
                    ArtworkAura(com.auralis.music.ui.theme.DefaultArtworkPalette, Modifier.matchParentSize(),
                        item.image ?: item.track?.thumbnail, AuraPresentation.SHORTCUT)
                    val photoSize = if (sidebar) 44.dp else 48.dp
                    val startPad = if (collapsed) 0.dp else (if (sidebar) 6.dp else 8.dp)
                    Row(
                        modifier = Modifier.fillMaxSize().padding(start = startPad, end = if (collapsed) 0.dp else 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start
                    ) {
                        val playlist = playlistCovers[item.album?.id ?: item.id.removePrefix("playlist-")]
                            ?: playlistCovers["local:${item.id.removePrefix("album-local:").removePrefix("album-").removePrefix("local:")}"]
                            ?: playlistCovers[item.id.removePrefix("album-local:").removePrefix("album-").removePrefix("local:")]
                        if (playlist != null) Box(Modifier.size(photoSize).clip(RoundedCornerShape(8.dp))) {
                            com.auralis.music.ui.library.PlaylistCoverArt(playlist, emptyList(), 8.dp)
                        } else ShortcutPhoto(item, photoSize.value.toInt())
                        if (!collapsed) Column(Modifier.weight(1f).padding(horizontal = if (sidebar) 8.dp else 12.dp)) {
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color.White)
                            Text(item.album?.author ?: when (item.type) { SpeedDialType.ARTIST -> "Artist"; SpeedDialType.TRACK -> item.track?.artist ?: "Song"; else -> "Playlist" },
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .72f))
                        }
                        if (!collapsed) Box {
                            IconButton(onClick = menu, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Outlined.MoreVert, "Options for ${item.name}", Modifier.size(18.dp), tint = androidx.compose.ui.graphics.Color.White)
                            }
                            if (artistActions != null) ArtistMenu(artist, artistMenu, { artistMenu = false }, artistActions)
                        }
                        if (!collapsed && !sidebar) IconButton(onClick = { remove(item) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Outlined.Close, "Remove ${item.name} from shortcuts", Modifier.size(14.dp), tint = androidx.compose.ui.graphics.Color.White.copy(alpha = .7f))
                        }
                    }
                }
          }
          if (sidebar) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              freshPins.forEach { item -> key(item.id) { cards(item) } }
          } else LazyRow(state = row, modifier = Modifier.mouseDragScroll(row), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              items(freshPins, key = { it.id }) { item -> cards(item) }
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
                        val isPinned = pins.any { pin ->
                            val pinAlbum = pin.album
                            val itemAlbum = item.album
                            pin.id == item.id ||
                            (pinAlbum != null && itemAlbum != null && (pinAlbum.id == itemAlbum.id || pinAlbum.id.removePrefix("local:") == itemAlbum.id.removePrefix("local:"))) ||
                            (pin.type == SpeedDialType.ARTIST && item.type == SpeedDialType.ARTIST && (pin.id == item.id || pin.name.equals(item.name, true)))
                        }
                        ShortcutCandidateRow(item, isPinned, playlistCovers, onRemove = { remove(item) }, onAdd = { add(item) })
                    }
                }
            }
        })
}

@Composable
internal fun ShortcutCandidateRow(
    item: SpeedDialItem,
    pinned: Boolean,
    playlistCovers: Map<String, com.auralis.music.domain.model.Playlist> = emptyMap(),
    onRemove: () -> Unit = {},
    onAdd: () -> Unit = {}
) {
    val interaction = remember(item.id) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlight by androidx.compose.animation.animateColorAsState(
        androidx.compose.ui.graphics.Color.White.copy(alpha = if (hovered) .12f else 0f),
        androidx.compose.animation.core.tween(180), label = "shortcutRowHighlight")

    val actionInteraction = remember(item.id, pinned) { MutableInteractionSource() }
    val actionHovered by actionInteraction.collectIsHoveredAsState()
    val actionColor = if (pinned) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val actionBackground by androidx.compose.animation.animateColorAsState(
        if (actionHovered) actionColor.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent,
        androidx.compose.animation.core.tween(150), label = "shortcutActionBg")

    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(highlight)
        .hoverable(interaction)
        .clickable(interactionSource = interaction, indication = null, onClick = if (pinned) onRemove else onAdd)
        .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val playlist = playlistCovers[item.album?.id ?: item.id.removePrefix("playlist-")]
            ?: playlistCovers["local:${item.id.removePrefix("album-local:").removePrefix("album-").removePrefix("local:")}"]
            ?: playlistCovers[item.id.removePrefix("album-local:").removePrefix("album-").removePrefix("local:")]
        if (playlist != null) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))) {
                com.auralis.music.ui.library.PlaylistCoverArt(playlist, emptyList(), 8.dp)
            }
        } else {
            ShortcutPhoto(item, side = 48)
        }
        Text(item.name, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(actionBackground)
                .hoverable(actionInteraction)
                .clickable(
                    interactionSource = actionInteraction,
                    indication = null,
                    onClick = if (pinned) onRemove else onAdd
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (pinned) "Remove" else "Add",
                color = if (actionHovered) actionColor else actionColor.copy(alpha = 0.95f),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ShortcutPhoto(item: SpeedDialItem, side: Int = 48) {
    val isArtist = item.type == SpeedDialType.ARTIST
    val shape = if (isArtist) CircleShape else RoundedCornerShape(8.dp)
    val rawUrl = item.image?.takeIf { it.isNotBlank() } ?: item.track?.thumbnail?.takeIf { it.isNotBlank() }
    val imageUrl = remember(rawUrl) {
        if (!rawUrl.isNullOrBlank()) com.auralis.music.ui.components.getOptimizedThumbnailUrl(rawUrl) ?: rawUrl else null
    }
    val context = coil3.compose.LocalPlatformContext.current
    val request = remember(imageUrl) {
        if (!imageUrl.isNullOrBlank()) {
            coil3.request.ImageRequest.Builder(context)
                .data(imageUrl)
                .size(400)
                .build()
        } else null
    }
    Box(Modifier.size(side.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        if (request != null) {
            AsyncImage(request, item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val icon = when (item.type) {
                SpeedDialType.ARTIST -> Icons.Outlined.Person
                SpeedDialType.ALBUM, SpeedDialType.PLAYLIST -> Icons.Outlined.LibraryMusic
                else -> Icons.Outlined.GridView
            }
            Icon(icon, item.name, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size((side / 2).dp))
        }
    }
}
