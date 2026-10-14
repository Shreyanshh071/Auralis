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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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

/** How far the card sits above the bar; the rest tucks under the bar's top edge. */
internal val UpNextPeekHeight = 34.dp

/**
 * The next song, peeking out above the mini player like a card stacked behind it: its cover,
 * title and artist. It slides to the new song when the queue moves on; clicking it plays it.
 * Hidden when nothing is queued. Draw it before the bar so the bar covers its lower edge.
 */
@Composable
internal fun UpNextPeek(controller: DesktopPlaybackController?, modifier: Modifier = Modifier) {
    if (controller == null) return
    val queue by controller.queue.collectAsState()
    val next = queue.tracks.getOrNull(queue.index + 1)?.takeIf { queue.current != null }
    AnimatedVisibility(next != null, modifier,
        enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it }) {
        val hover = remember { MutableInteractionSource() }
        val hovered by hover.collectIsHoveredAsState()
        val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        Box(Modifier.widthIn(min = 220.dp, max = 340.dp).height(UpNextPeekHeight + 12.dp)
            .clip(shape)
            .background(Color(0xFF1B1A1F).copy(alpha = if (hovered) 0.98f else 0.92f))
            .border(1.dp, Color.White.copy(alpha = if (hovered) 0.16f else 0.09f), shape)
            .hoverable(hover)
            .clickable(interactionSource = hover, indication = null) { controller.next() }) {
            AnimatedContent(next, transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 }) togetherWith
                    (fadeOut(tween(140)) + slideOutVertically(tween(200)) { -it / 2 })
            }, label = "upNext") { track ->
                if (track != null) Row(Modifier.height(UpNextPeekHeight).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesktopArtwork(track.thumbnail, Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)), 5.dp, null)
                    Text("Up next", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    Text(track.title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.92f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(track.artist, fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}
