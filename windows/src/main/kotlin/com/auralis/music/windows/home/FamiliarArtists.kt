package com.auralis.music.windows.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/** An artist the listener plays, with their own play count and (once looked up) photo and channel. */
internal data class FamiliarArtist(val name: String, val plays: Int, val id: String = "", val photo: String? = null)

/**
 * Familiar Artists, after Li Music's: the listener's top artists by their own play count, as
 * 4:5 posters in rank order with the rank ("01") and the count on the photo. Hovering shows a
 * follow button. Hidden with fewer than three artists.
 */
@Composable
internal fun FamiliarArtistsRow(artists: List<FamiliarArtist>, followed: Set<String>, onOpen: (FamiliarArtist) -> Unit,
    onToggleFollow: (FamiliarArtist) -> Unit) {
    if (artists.size < 3) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Person, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Familiar Artists", style = MaterialTheme.typography.titleLarge.copy(fontSize = 25.sp, lineHeight = 31.sp), fontWeight = FontWeight.Bold)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Six across at full width, then three, then two (whole rows only).
            val columns = when { maxWidth >= 1000.dp -> 6; maxWidth >= 560.dp -> 3; else -> 2 }
            val shown = artists.take(6)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                shown.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { artist ->
                            val rank = artists.indexOf(artist) + 1
                            ArtistPoster(artist, rank, artist.id.isNotBlank() && artist.id in followed, { onOpen(artist) }, { onToggleFollow(artist) },
                                Modifier.weight(1f))
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistPoster(artist: FamiliarArtist, rank: Int, followed: Boolean, onOpen: () -> Unit, onToggleFollow: () -> Unit, modifier: Modifier) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val controls by animateFloatAsState(if (hovered) 1f else 0f, tween(150), label = "posterControls")
    Box(modifier.aspectRatio(4f / 5f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .hoverable(hover).clickable(onClick = onOpen)) {
        if (artist.photo != null) {
            AsyncImage(com.auralis.music.ui.components.getHighResArtworkUrl(artist.photo) ?: artist.photo, artist.name,
                contentScale = ContentScale.Crop, alignment = androidx.compose.ui.BiasAlignment(0f, -0.56f), modifier = Modifier.fillMaxSize())
        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Person, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .55f to Color.Black.copy(alpha = .2f), 1f to Color.Black.copy(alpha = .85f))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(rank.toString().padStart(2, '0'), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            Text(artist.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp))
            Text(if (artist.plays == 1) "1 play" else "${artist.plays} plays", color = Color.White.copy(alpha = .65f), fontSize = 12.sp,
                maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        // On hover only: six posters each carrying a button at rest would cover the faces.
        if (artist.id.isNotBlank()) IconButton(onClick = onToggleFollow, enabled = controls > 0.5f,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(32.dp).alpha(controls).clip(CircleShape)
                .background(Color.Black.copy(alpha = .45f))) {
            Icon(if (followed) Icons.Outlined.HowToReg else Icons.Outlined.PersonAddAlt,
                if (followed) "Unfollow ${artist.name}" else "Follow ${artist.name}", Modifier.size(18.dp),
                tint = if (followed) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .85f))
        }
    }
}
