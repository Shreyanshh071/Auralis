package com.auralis.music.ui.library

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.auralis.music.domain.model.SavedArtist

@Composable
internal fun SavedArtistCard(artist: SavedArtist, listMode: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        tween(180), label = "savedArtistHover")
    val modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(fill)
        .hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick).padding(12.dp)
    val portrait: @Composable () -> Unit = {
        Box((if (listMode) Modifier.size(56.dp) else Modifier.fillMaxWidth().aspectRatio(1f))
            .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.AccountCircle, null, Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!artist.thumbnail.isNullOrBlank()) AsyncImage(artist.thumbnail, artist.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
    val labels: @Composable () -> Unit = {
        Text(artist.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(artist.subscribers?.takeIf { it.isNotBlank() } ?: "Artist", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (listMode) Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        portrait(); Column(Modifier.weight(1f)) { labels() }
    } else Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        portrait(); Column(horizontalAlignment = Alignment.CenterHorizontally) { labels() }
    }
}
