package com.auralis.music.windows

import com.auralis.music.ui.i18n.desktopText

import com.auralis.music.ui.components.IconButton

import androidx.compose.foundation.background
import com.auralis.music.ui.components.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.auralis.music.ui.components.secondaryClick

/** One row of the left panel: what it shows and what opening it does. */
internal data class LibraryEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val cover: String?,
    val liked: Boolean = false,
    /** Right-click: the playlist's options (the phone's playlist sheet). */
    val menu: (() -> Unit)? = null,
    val open: () -> Unit
)

/**
 * The left panel in place of the navigation sidebar (Spotify's Your Library): Liked Songs, then
 * every playlist and saved album. Pages are reached from the dock. Collapsed, only the covers.
 */
@Composable
internal fun LibrarySidebar(entries: List<LibraryEntry>, selectedKey: String?, collapsed: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.surface).padding(top = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = if (collapsed) 0.dp else 16.dp, end = if (collapsed) 0.dp else 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start) {
            if (!collapsed) {
                Icon(Icons.Outlined.LibraryMusic, null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(desktopText("Your Library"), Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.onSurface, maxLines = 1)
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                Icon(if (collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft,
                    if (collapsed) "Expand your library" else "Collapse your library", tint = colors.onSurfaceVariant)
            }
        }
        if (entries.isEmpty() && !collapsed) Text(desktopText("Playlists you make, import or save show up here."),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 13.sp, color = colors.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 90.dp)) {
            items(entries, key = { it.key }) { entry -> LibraryRow(entry, entry.key == selectedKey, collapsed) }
        }
    }
}

@Composable
private fun LibraryRow(entry: LibraryEntry, selected: Boolean, collapsed: Boolean) {
    val colors = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(when { selected -> colors.surfaceContainerHigh; hovered -> colors.surfaceContainer; else -> Color.Transparent })
        .hoverable(hover).secondaryClick(entry.menu).clickable(interactionSource = hover, indication = null, onClick = entry.open)
        .padding(if (collapsed) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.spacedBy(12.dp)) {
        val coverShape = RoundedCornerShape(6.dp)
        if (entry.liked) Box(Modifier.size(48.dp).clip(coverShape).background(Brush.linearGradient(
            listOf(Color(0xFF4A2FBD), Color(0xFF8E8EE5)))), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Favorite, null, Modifier.size(20.dp), tint = Color.White)
        } else Box(Modifier.size(48.dp).clip(coverShape).background(colors.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.LibraryMusic, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            if (!entry.cover.isNullOrBlank()) AsyncImage(entry.cover, entry.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (!collapsed) Column(Modifier.weight(1f)) {
            Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                color = if (selected) colors.primary else colors.onSurface)
            Text(entry.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
    }
}
