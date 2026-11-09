package com.auralis.music.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.ui.components.clickable

/**
 * The top of a playlist on Windows: cover, kind, title, length, and one row of actions.
 * Transparent, because the app paints one full-window artwork backdrop behind everything.
 * Search and sort live in the toolbar right above the song table, not up here.
 */
@Composable
internal fun DesktopPlaylistHero(
    playlist: Playlist, savedAlbums: List<SavedAlbum>, owner: String,
    onPlay: () -> Unit, onShuffle: () -> Unit, onOptions: () -> Unit
) {
    val isSpotify = playlist.id.startsWith("sp_") || playlist.id.startsWith("imported:spotify:")
    val isAlbum = isAlbumPlaylist(playlist, savedAlbums)
    val kind = when {
        isAlbum -> "Album"
        playlist.id.startsWith("smart_") -> "Collection"
        else -> "Playlist"
    }
    val count = playlist.tracks.size
    val totalSeconds = playlist.tracks.sumOf { it.duration.coerceAtLeast(0) }
    val length = when {
        totalSeconds >= 3600 -> "${totalSeconds / 3600} hr ${(totalSeconds % 3600) / 60} min"
        totalSeconds > 0 -> "${totalSeconds / 60} min ${totalSeconds % 60} sec"
        else -> null
    }
    val facts = listOfNotNull(
        owner.takeIf { it.isNotBlank() && !isAlbum },
        if (count == 1) "1 song" else "$count songs",
        length,
        "from Spotify".takeIf { isSpotify }
    )

    // 64dp on top clears the floating Back button.
    Row(
        Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 64.dp, bottom = 22.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Box(
            Modifier.size(212.dp)
                .shadow(28.dp, RoundedCornerShape(12.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(12.dp))
        ) { PlaylistCoverArt(playlist, savedAlbums, 12.dp) }

        Column(Modifier.weight(1f)) {
            Text(kind, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = .78f))
            Spacer(Modifier.height(6.dp))
            Text(
                playlist.title,
                fontSize = if (playlist.title.length <= 22) 54.sp else 38.sp,
                lineHeight = if (playlist.title.length <= 22) 58.sp else 42.sp,
                fontWeight = FontWeight.Black, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (!playlist.description.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(playlist.description!!, fontSize = 13.sp, color = Color.White.copy(alpha = .62f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Text(facts.joinToString("  •  "), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = .74f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PlayButton(enabled = count > 0, onClick = onPlay)
                HeroIcon(Icons.Default.Shuffle, "Shuffle", enabled = count > 0, onClick = onShuffle)
                HeroIcon(Icons.Default.MoreHoriz, "Playlist options", onClick = onOptions)
            }
        }
    }
}

@Composable
private fun PlayButton(enabled: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered && enabled) 1.06f else 1f, tween(140), label = "heroPlayScale")
    Box(
        Modifier.size(56.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.White.copy(alpha = .4f))
            .hoverable(hover)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.PlayArrow, "Play", tint = Color.Black, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun HeroIcon(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = if (hovered && enabled) .16f else .08f))
            .border(1.dp, Color.White.copy(alpha = .12f), CircleShape)
            .hoverable(hover)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = Color.White.copy(alpha = if (enabled) .92f else .4f), modifier = Modifier.size(22.dp))
    }
}
