package com.auralis.music.windows.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.auralis.music.ui.components.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.windows.search.DesktopArtwork

/** The card's height; it floats this far plus [UpNextPeekGap] above the bar. */
internal val UpNextPeekHeight = 56.dp
/** The panels' own 8dp gap above the bar, so the card's bottom sits on their bottom edge. */
internal val UpNextPeekGap = 8.dp

/**
 * The next song in its own small card just above the mini player (Spotify's): its cover, then
 * the title over the artist. Translucent, with the song's own cover blurred behind (Windows can't
 * blur the page under it). It slides to the new song when the queue moves on; clicking it plays
 * it. Hidden when nothing is queued.
 */
@Composable
internal fun UpNextPeek(controller: DesktopPlaybackController?, modifier: Modifier = Modifier) {
    if (controller == null) return
    val queue by controller.queue.collectAsState()
    val next = queue.tracks.getOrNull(queue.index + 1)?.takeIf { queue.current != null }
    AnimatedVisibility(next != null, modifier,
        enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 }) {
        UpNextCard(next) { controller.next() }
    }
}

/** The card itself: [next]'s cover, title and artist on frosted glass. */
@Composable
internal fun UpNextCard(next: com.auralis.music.domain.model.Track?, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.height(UpNextPeekHeight).widthIn(min = 150.dp, max = 280.dp).width(IntrinsicSize.Max)
        .clip(shape)
        .border(1.dp, Color.White.copy(alpha = if (hovered) 0.18f else 0.10f), shape)
        .hoverable(hover)
        .clickable(interactionSource = hover, indication = null, onClick = onClick)) {
        // Frosted: the next song's cover, blurred, under a see-through dark tint.
        Box(Modifier.matchParentSize()) { BlurredArtworkBackground(next?.thumbnail, blurRadius = 30.dp, brightness = .75f) }
        Box(Modifier.matchParentSize().background(Color(0xFF141318).copy(alpha = if (hovered) 0.42f else 0.55f)))
        AnimatedContent(next, transitionSpec = {
            (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 }) togetherWith
                (fadeOut(tween(140)) + slideOutVertically(tween(200)) { -it / 2 })
        }, label = "upNext") { track ->
            if (track != null) Row(Modifier.fillMaxHeight().padding(start = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DesktopArtwork(track.thumbnail, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), 6.dp, null)
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(track.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
