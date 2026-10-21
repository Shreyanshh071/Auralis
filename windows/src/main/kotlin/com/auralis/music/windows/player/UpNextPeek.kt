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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.ui.components.clickable
import com.auralis.music.windows.search.DesktopArtwork

/** The card's height; it floats this far plus [UpNextPeekGap] above the bar. */
internal val UpNextPeekHeight = 56.dp
/** The panels' own 8dp gap above the bar, so the card's bottom sits on their bottom edge. */
internal val UpNextPeekGap = 8.dp

/**
 * The next song in its own card just above the mini player, as wide as its cover and text need
 * (at most the left panel's [width]). [onSize] reports its size, zero while hidden, so the left
 * panel can tile around it.
 */
@Composable
internal fun UpNextPeek(
    controller: DesktopPlaybackController?,
    modifier: Modifier = Modifier,
    width: Dp = Dp.Unspecified,
    collapsed: Boolean = false,
    onSize: (androidx.compose.ui.unit.DpSize) -> Unit = {}
) {
    if (controller == null) { onSize(androidx.compose.ui.unit.DpSize.Zero); return }
    val queue by controller.queue.collectAsState()
    val next = queue.tracks.getOrNull(queue.index + 1)?.takeIf { queue.current != null }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val showing = next != null
    // Reported straight from layout on every size change (each frame of a resize animation), so
    // the panel tiles with the card as it moves; zero as soon as there is no next song.
    val report by androidx.compose.runtime.rememberUpdatedState(onSize)
    androidx.compose.runtime.LaunchedEffect(showing) { if (!showing) report(androidx.compose.ui.unit.DpSize.Zero) }
    AnimatedVisibility(
        visible = next != null,
        modifier = modifier,
        enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 }
    ) {
        UpNextCard(next, width = width, collapsed = collapsed,
            modifier = Modifier.onSizeChanged { if (showing) report(with(density) { androidx.compose.ui.unit.DpSize(it.width.toDp(), it.height.toDp()) }) }) { controller.next() }
    }
}

/** The card itself: [next]'s cover, title and artist, sized to them and at most [width] wide. */
@Composable
internal fun UpNextCard(
    next: com.auralis.music.domain.model.Track?,
    width: Dp = Dp.Unspecified,
    collapsed: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val shape = RoundedCornerShape(12.dp)

    val cardModifier = if (collapsed) {
        Modifier.height(UpNextPeekHeight).width(if (width != Dp.Unspecified && width > 0.dp) width else 64.dp)
    } else if (width != Dp.Unspecified && width > 0.dp) {
        Modifier.height(UpNextPeekHeight).widthIn(max = width)
    } else {
        Modifier.height(UpNextPeekHeight).widthIn(min = 180.dp, max = 320.dp).fillMaxWidth()
    }

    Box(
        modifier.then(cardModifier)
            .clip(shape)
            .hoverable(hover)
            .clickable(interactionSource = hover, indication = null, onClick = onClick)
    ) {
        // The same artwork aura as the sidebar's shortcut cards, from the next song's cover.
        ArtworkAura(com.auralis.music.ui.theme.DefaultArtworkPalette, Modifier.matchParentSize(),
            next?.thumbnail, AuraPresentation.SHORTCUT)
        if (hovered) Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.06f)))

        AnimatedContent(
            targetState = next,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 }) togetherWith
                    (fadeOut(tween(140)) + slideOutVertically(tween(200)) { -it / 2 })
            },
            label = "upNext"
        ) { track ->
            if (track != null) {
                Row(
                    modifier = (if (collapsed) Modifier.fillMaxSize() else Modifier.fillMaxHeight())
                        .padding(start = if (collapsed) 12.dp else 8.dp, end = if (collapsed) 12.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.spacedBy(10.dp)
                ) {
                    DesktopArtwork(track.thumbnail, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), 6.dp, null)
                    if (!collapsed) {
                        Column(
                            modifier = Modifier.padding(end = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                text = track.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
