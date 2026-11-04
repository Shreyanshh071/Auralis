package com.auralis.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.auralis.music.R
import com.auralis.music.data.network.ArtistPhotoProvider
import com.auralis.music.domain.model.Artist
import com.auralis.music.ui.glass.LocalLiquidGlass
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.player.LocalPlayerModalVisualState
import com.auralis.music.ui.theme.dynamicPrimary

/** A compact handoff from a song's credits to one artist's page. */
@Composable
internal fun ArtistPickerSheet(
    artists: List<Artist>,
    onPick: (Artist) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null
) {
    var closing by remember { mutableStateOf(false) }
    val glass = LocalLiquidGlass.current
    val modalVisual = LocalPlayerModalVisualState.current
    DisposableEffect(modalVisual, glass != null) {
        if (glass != null) modalVisual?.opened()
        onDispose { if (glass != null) modalVisual?.closed() }
    }
    val listHeight = LocalConfiguration.current.screenHeightDp.dp * .5f
    // Header slots run before the sheet supplies its close animation callback.
    var closeSheet by remember { mutableStateOf<((() -> Unit) -> Unit)?>(null) }
    fun finish(action: () -> Unit) {
        if (closing) return
        closing = true
        closeSheet?.invoke(action) ?: action()
    }
    AuralisOptionSheet(
        onDismiss = { if (!closing) onDismiss() },
        title = {
            val foreground = LocalContentColor.current
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(str(R.string.choose_artist), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subtitle ?: str(R.string.artist_picker_hint),
                        color = foreground.copy(alpha = .62f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { finish(onDismiss) },
                    enabled = !closing,
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(foreground.copy(alpha = .07f))
                ) {
                    Icon(Icons.Default.Close, str(R.string.close), modifier = Modifier.size(20.dp))
                }
            }
        }
    ) { close ->
        SideEffect { closeSheet = close }
        val foreground = LocalContentColor.current
        val accent = MaterialTheme.dynamicPrimary
        val rowShape = remember { RoundedCornerShape(20.dp) }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = listHeight),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(artists, key = { index, artist -> "${artist.id}:${artist.name}:$index" }) { _, artist ->
                val photo by produceState(
                    initialValue = artist.thumbnail?.takeIf { it.isNotBlank() }
                        ?: ArtistPhotoProvider.getCachedPhoto(artist.name),
                    key1 = artist.name,
                    key2 = artist.thumbnail
                ) {
                    value = artist.thumbnail?.takeIf { it.isNotBlank() }
                        ?: ArtistPhotoProvider.resolveArtistPhoto(artist.name)
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = 76.dp)
                        .clip(rowShape)
                        .background(foreground.copy(alpha = .045f))
                        .border(1.dp, foreground.copy(alpha = .07f), rowShape)
                        .clickable(enabled = !closing, role = Role.Button) { finish { onPick(artist) } }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(46.dp).clip(CircleShape)
                            .background(accent.copy(alpha = .14f)).clearAndSetSemantics { },
                        contentAlignment = Alignment.Center
                    ) {
                        // Keep the initial visible while loading, offline, or if the image fails.
                        Text(artist.name.trim().take(1).uppercase(), color = accent,
                            fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        if (!photo.isNullOrBlank()) {
                            AsyncImage(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(artist.name, modifier = Modifier.weight(1f), fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = foreground.copy(alpha = .45f),
                        modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
