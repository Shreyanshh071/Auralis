package com.auralis.music.ui.library

import com.auralis.music.ui.components.IconButton
import com.auralis.music.ui.components.Button
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum

/** Desktop banner; typography comes from Auralis's existing theme. */
@Composable
internal fun DesktopPlaylistHero(playlist: Playlist, savedAlbums: List<SavedAlbum>, subtitle: String,
    query: String, onQuery: (String) -> Unit, onPlay: () -> Unit,
    onShuffle: () -> Unit, onOptions: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(0.dp))) {
        // The app owns one full-window artwork backdrop. Keeping this banner transparent
        // avoids a second cover crop and lets it follow the playing song with the track list.
        // Back floats over the page (PlaylistDetailView) so it stays reachable while scrolled.
        OutlinedTextField(query, onQuery, singleLine = true,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).width(252.dp),
            placeholder = { Text("Search this playlist", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
            shape = RoundedCornerShape(24.dp), textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedPlaceholderColor = Color.White.copy(alpha = .6f), unfocusedPlaceholderColor = Color.White.copy(alpha = .6f),
                focusedLeadingIconColor = Color.White.copy(alpha = .7f), unfocusedLeadingIconColor = Color.White.copy(alpha = .7f),
                unfocusedBorderColor = Color.White.copy(alpha = .16f),
                focusedBorderColor = Color.White.copy(alpha = .45f),
                unfocusedContainerColor = Color.Black.copy(alpha = .24f), focusedContainerColor = Color.Black.copy(alpha = .24f)))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.size(184.dp).clip(RoundedCornerShape(10.dp))) { PlaylistCoverArt(playlist, savedAlbums, 10.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("PLAYLIST", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .66f))
                    if (playlist.id.startsWith("sp_") || playlist.id.startsWith("imported:spotify:"))
                        Text("FROM SPOTIFY", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .66f))
                }
                Text(playlist.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, fontSize = 32.sp,
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .68f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onPlay, enabled = playlist.tracks.isNotEmpty(), contentPadding = PaddingValues(horizontal = 16.dp)) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Play")
                    }
                    IconButton(onOptions) { Icon(Icons.Default.MoreVert, "Playlist options", tint = Color.White) }
                    IconButton(onShuffle, enabled = playlist.tracks.isNotEmpty()) { Icon(Icons.Default.Shuffle, "Shuffle", tint = Color.White) }
                }
            }
        }
    }
}
