package com.auralis.music.windows.home

import com.auralis.music.ui.components.DropdownMenuItem

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** An artist the listener plays, with their own play count and (once looked up) photo and channel. */
internal data class FamiliarArtist(val name: String, val plays: Int, val id: String = "", val photo: String? = null)

/**
 * What the poster's two buttons do, after Li Music's: subscribe (the YouTube account's own
 * subscription, so only when signed in) and the ⋯ menu (radio, shortcut, share, library, block).
 * `subscribed` is null while unknown (signed out, or the artist page hasn't answered yet).
 */
internal class FamiliarArtistActions(
    val subscribed: (FamiliarArtist) -> Boolean?,
    val subscribeBusy: (FamiliarArtist) -> Boolean,
    val toggleSubscribe: (FamiliarArtist) -> Unit,
    val startRadio: (FamiliarArtist) -> Unit,
    val isShortcut: (FamiliarArtist) -> Boolean,
    val toggleShortcut: (FamiliarArtist) -> Unit,
    val share: (FamiliarArtist) -> Unit,
    val inLibrary: (FamiliarArtist) -> Boolean,
    val toggleLibrary: (FamiliarArtist) -> Unit,
    val block: (FamiliarArtist) -> Unit,
)

/**
 * Familiar Artists, after Li Music's: the listener's top artists by their own play count, as
 * 4:5 posters in rank order with the rank ("01") and the count on the photo. Hovering shows the
 * subscribe and ⋯ buttons. Hidden with fewer than three artists.
 */
@Composable
internal fun FamiliarArtistsRow(artists: List<FamiliarArtist>, onOpen: (FamiliarArtist) -> Unit, actions: FamiliarArtistActions,
    /** Offscreen renders only: show the buttons without hovering. */
    showControls: Boolean = false) {
    if (artists.size < 3) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Person, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Familiar Artists", style = MaterialTheme.typography.titleLarge.copy(fontSize = 25.sp, lineHeight = 31.sp), fontWeight = FontWeight.Bold)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val list = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val cardWidth = ((maxWidth - 60.dp) / 6).coerceIn(180.dp, 252.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(state = list, modifier = Modifier.fillMaxWidth().mouseDragScroll(list), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(artists.take(10)) { index, artist ->
                        ArtistPoster(artist, index + 1, { onOpen(artist) }, actions, Modifier.width(cardWidth), showControls)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    com.auralis.music.ui.components.IconButton(onClick = {
                        scope.launch { list.animateScrollToItem((list.firstVisibleItemIndex - 3).coerceAtLeast(0)) }
                    }, enabled = list.canScrollBackward) {
                        Icon(Icons.Outlined.ChevronLeft, "Previous artists")
                    }
                    com.auralis.music.ui.components.IconButton(onClick = {
                        scope.launch { list.animateScrollToItem((list.firstVisibleItemIndex + 3).coerceAtMost(artists.take(10).lastIndex)) }
                    }, enabled = list.canScrollForward) {
                        Icon(Icons.Outlined.ChevronRight, "More artists")
                    }
                }
            }
        }
    }
}

private val PosterPhotoAlignment = BiasAlignment(0f, -0.56f)
private val GlassButtonSize = 32.dp
private val GlassInset = 8.dp
private val GlassGap = 4.dp

@Composable
private fun ArtistPoster(artist: FamiliarArtist, rank: Int, onOpen: () -> Unit, actions: FamiliarArtistActions, modifier: Modifier,
    showControls: Boolean = false) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }
    // On hover only (or while the menu is open): six posters each carrying two discs would cover the faces.
    val controls by animateFloatAsState(if (hovered || menuOpen || showControls) 1f else 0f, tween(150), label = "posterControls")
    val photo = artist.photo?.let { com.auralis.music.ui.components.getHighResArtworkUrl(it) ?: it }
    BoxWithConstraints(modifier.aspectRatio(4f / 5f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .hoverable(hover).clickable(onClick = onOpen)) {
        val posterWidth = maxWidth; val posterHeight = maxHeight
        if (photo != null) {
            AsyncImage(photo, artist.name, contentScale = ContentScale.Crop, alignment = PosterPhotoAlignment, modifier = Modifier.fillMaxSize())
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
        if (controls > 0.01f) Row(Modifier.align(Alignment.TopEnd).padding(top = GlassInset, end = GlassInset).alpha(controls),
            horizontalArrangement = Arrangement.spacedBy(GlassGap)) {
            val subscribed = actions.subscribed(artist)
            val busy = actions.subscribeBusy(artist)
            // Pulses while the subscription is being written, as Li Music's button does.
            val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "subscribeBusy").animateFloat(1f, .45f,
                androidx.compose.animation.core.infiniteRepeatable(tween(600), androidx.compose.animation.core.RepeatMode.Reverse),
                label = "subscribeBusyAlpha")
            // Each button's left edge on the poster, so its frosted glass shows the photo right behind it.
            val subscribeLeft = posterWidth - GlassInset - GlassButtonSize * 2 - GlassGap
            val menuLeft = posterWidth - GlassInset - GlassButtonSize
            GlassButton(photo, posterWidth, posterHeight, subscribeLeft, GlassInset,
                if (subscribed == true) "Unsubscribe from ${artist.name}" else "Subscribe to ${artist.name}",
                if (subscribed == true) Icons.Outlined.HowToReg else Icons.Outlined.PersonAddAlt,
                tint = if (subscribed == true) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .8f),
                modifier = Modifier.alpha(if (busy) pulse else 1f)) { if (!busy) actions.toggleSubscribe(artist) }
            Box {
                GlassButton(photo, posterWidth, posterHeight, menuLeft, GlassInset, "More options for ${artist.name}",
                    Icons.Outlined.MoreVert, tint = Color.White.copy(alpha = .8f)) { menuOpen = true }
                ArtistMenu(artist, menuOpen, { menuOpen = false }, actions)
            }
        }
    }
}

/**
 * A round, frosted button over the poster (Li Music's `bg-black/40 backdrop-blur-sm`): the same
 * photo, blurred and placed so it lines up with the poster underneath, under a 40% black tint.
 */
@Composable
private fun GlassButton(photo: String?, posterWidth: Dp, posterHeight: Dp, left: Dp, top: Dp, label: String,
    icon: ImageVector, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(modifier.size(GlassButtonSize).clip(CircleShape).hoverable(hover).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        if (photo != null) AsyncImage(photo, null, contentScale = ContentScale.Crop, alignment = PosterPhotoAlignment,
            modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(posterWidth, posterHeight).offset(-left, -top).blur(6.dp))
        Box(Modifier.matchParentSize().background(com.auralis.music.ui.components.smoothHoverColor(Color.Black.copy(alpha = if (hovered) .6f else .4f)))
            .border(0.5.dp, Color.White.copy(alpha = .08f), CircleShape))
        Icon(icon, label, Modifier.size(18.dp), tint = com.auralis.music.ui.components.smoothHoverColor(if (hovered && tint != Color.White) tint.copy(alpha = 1f) else tint))
    }
}

/** The ⋯ menu: Start radio, Add to shortcuts, Share, Save to library, Block artist (Li Music's artist menu). */
@Composable
internal fun ArtistMenu(artist: FamiliarArtist, expanded: Boolean, dismiss: () -> Unit, actions: FamiliarArtistActions) {
    DropdownMenu(expanded, onDismissRequest = dismiss, shape = RoundedCornerShape(10.dp),
        containerColor = Color(0xF0151217), shadowElevation = 12.dp, tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .08f))) {
        @Composable
        fun item(text: String, icon: ImageVector, action: () -> Unit) = DropdownMenuItem(
            text = { Text(text, fontSize = 14.sp, color = Color(0xFFE8E4EB)) },
            leadingIcon = { Icon(icon, null, Modifier.size(18.dp), tint = Color(0xFFBDB7C5)) },
            onClick = { dismiss(); action() },
            modifier = Modifier.height(38.dp))
        item("Start radio", Icons.Outlined.Radio) { actions.startRadio(artist) }
        if (actions.isShortcut(artist)) item("Remove from shortcuts", Icons.Outlined.DashboardCustomize) { actions.toggleShortcut(artist) }
        else item("Add to shortcuts", Icons.Outlined.Dashboard) { actions.toggleShortcut(artist) }
        item("Share", Icons.Outlined.Share) { actions.share(artist) }
        if (actions.inLibrary(artist)) item("Remove from library", Icons.Outlined.BookmarkRemove) { actions.toggleLibrary(artist) }
        else item("Save to library", Icons.Outlined.LibraryAdd) { actions.toggleLibrary(artist) }
        item("Block artist", Icons.Outlined.PersonOff) { actions.block(artist) }
    }
}
