package com.auralis.music.windows.player

import com.auralis.music.ui.i18n.desktopText

import com.auralis.music.ui.components.IconButton
import com.auralis.music.ui.components.TextButton
import com.auralis.music.ui.components.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.background
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material3.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.search.DesktopArtwork

@Composable
internal fun NowPlayingSidebar(playback: DesktopPlaybackController, lyrics: DesktopLyricsController, onExpand: () -> Unit, modifier: Modifier,
    palette: com.auralis.music.ui.theme.ArtworkPalette, onCollapse: () -> Unit,
    library: com.auralis.music.domain.repository.LibraryRepository? = null,
    onOpenQueue: () -> Unit = {}, onOpenArtist: (com.auralis.music.domain.model.Artist) -> Unit = {}, mainPlayerOpen: Boolean = false) {
    val queue by playback.queue.collectAsState()
    val track = queue.current ?: return
    var credits by remember(track.id) { mutableStateOf(SongCredits.cached(track.id)) }
    LaunchedEffect(track.id) { if (credits == null) credits = SongCredits.fetch(track.id) }
    val artists = remember(track.artist, credits) { sidebarArtists(track.artist, credits.orEmpty()) }
    NowPlayingSidebarContent(track, onExpand, modifier, palette, onCollapse, clock = playback.clock, playback = playback, onOpenArtist = onOpenArtist, artists = artists, mainPlayerOpen = mainPlayerOpen, extraCards = { cardModifier ->
        // Below lyrics: who made the song, then what's next (Spotify's Now Playing view, without tours).
        SidebarCreditsCard(track, library, onOpenArtist, cardModifier, credits)
        SidebarNextInQueueCard(playback, onOpenQueue, cardModifier)
    }) {
        val state by lyrics.state.collectAsState()
        val playerState by playback.state.collectAsState()
        val remote by playback.connectRemote.collectAsState()
        val position = com.auralis.music.ui.lyrics.rememberLyricsClock(playback.clock,
            enabled = remote?.playing ?: (playerState == VlcAudioPlayer.State.PLAYING),
            fallbackPositionMs = rememberShownPosition(playback, remote))
        key(track.id) {
            if (state.lyrics == null && state.isLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White.copy(alpha = .7f), modifier = Modifier.size(24.dp))
            } else SidebarLyricsView(state.lyrics, position, playback::seekTo, track.duration * 1000L,
                lineGap = 4.dp, followAnchorFraction = .50f, introAnchorFraction = .12f)
        }
    }
}

@Composable
internal fun NowPlayingSidebarContent(track: Track, onExpand: () -> Unit, modifier: Modifier,
    palette: com.auralis.music.ui.theme.ArtworkPalette = com.auralis.music.ui.theme.DefaultArtworkPalette,
    onCollapse: (() -> Unit)? = null,
    extraCards: @Composable ColumnScope.(Modifier) -> Unit = {},
    clock: com.auralis.music.data.service.PlaybackClockSource? = null,
    playback: DesktopPlaybackController? = null,
    onOpenArtist: (com.auralis.music.domain.model.Artist) -> Unit = {},
    artists: List<com.auralis.music.domain.model.Artist> = sidebarArtists(track.artist, emptyList()),
    mainPlayerOpen: Boolean = false,
    lyricContent: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
    var lyricsCollapsed by remember { mutableStateOf(false) }
    var lyricsWindow by remember { mutableStateOf(false) }
    LaunchedEffect(mainPlayerOpen) { if (mainPlayerOpen) lyricsWindow = false }
    val expandPlayer = { lyricsWindow = false; onExpand() }
    if (lyricsWindow && !mainPlayerOpen) LyricsMiniPopup(track, palette, { lyricsWindow = false }, expandPlayer, playback, lyricContent)
    val coverSize = (maxWidth - 32.dp).coerceAtLeast(1.dp)
    val motionAura = rememberMotionAura(track.id)
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) {
    if (com.auralis.music.ui.theme.LocalAppearanceSettings.current.dynamicTheme) {
        ArtworkAura(palette, Modifier.fillMaxSize().then(com.auralis.music.windows.dev.perfSkip("aura")), track.thumbnail, motionAura = motionAura)
    } else {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
    }
    // Scrolls: the lyrics, credits and next-in-queue cards run below the cover.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(desktopText("Now playing"), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = .75f))
            if (onCollapse != null) IconButton(onClick = onCollapse, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.ChevronRight, desktopText("Collapse now playing"), tint = Color.White.copy(alpha = .75f))
            }
        }
        // Title, artist and lyrics card share the cover's edges, even when height shrinks the cover.
        if (mainPlayerOpen) {
            // The fullscreen cover owns animation playback while it covers this sidebar.
            DesktopArtwork(track.thumbnail, Modifier.size(coverSize), 12.dp, track.title, highRes = true)
        } else SidebarMusicVideo(track, clock, Modifier.size(coverSize).clip(RoundedCornerShape(12.dp)).then(com.auralis.music.windows.dev.perfSkip("cover")), motionAura) {
        AnimatedCover(track, Modifier.fillMaxSize(), motionAura) {
            DesktopArtwork(track.thumbnail, Modifier.fillMaxSize(), 12.dp, track.title, highRes = true)
        }
        }
        Text(track.title, Modifier.width(coverSize), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
        SidebarArtistNames(artists, onOpenArtist, Modifier.width(coverSize))
        androidx.compose.animation.AnimatedVisibility(!lyricsWindow,
            enter = androidx.compose.animation.fadeIn(tween(220)) + androidx.compose.animation.expandVertically(tween(300)),
            exit = androidx.compose.animation.fadeOut(tween(160)) + androidx.compose.animation.shrinkVertically(tween(260))) {
        SidebarLyricsCard(
            collapsed = lyricsCollapsed,
            onToggle = { lyricsCollapsed = !lyricsCollapsed },
            onOpenWindow = { lyricsWindow = true },
            onExpand = expandPlayer,
            // No fixed height: the card animates its own (open and closed); a fixed one made closing snap.
            modifier = Modifier.width(coverSize),
            lyricContent = lyricContent
        )
        }
        extraCards(Modifier.width(coverSize))
    }
    }
    }
}


@Composable
internal fun SidebarLyricsCard(
    collapsed: Boolean,
    onToggle: () -> Unit,
    onOpenWindow: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    lyricContent: @Composable () -> Unit
) {
    BoxWithConstraints(modifier) {
        val expandedHeight = if (maxHeight.isSpecified && maxHeight > 48.dp) minOf(320.dp, maxHeight) else 320.dp
        val targetHeight = if (collapsed) 48.dp else expandedHeight
        val height by animateDpAsState(
            targetValue = targetHeight,
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            label = "lyricsCardHeight"
        )
        val opacity by animateFloatAsState(
            targetValue = if (collapsed) 0f else 1f,
            animationSpec = tween(if (collapsed) 200 else 240, easing = FastOutSlowInEasing),
            label = "lyricsCardOpacity"
        )
        val arrowRotation by animateFloatAsState(
            targetValue = if (collapsed) 180f else 0f,
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            label = "lyricsCardArrow"
        )
        val contentHeight = maxOf(0.dp, expandedHeight - 48.dp)
        Column(Modifier.width(maxWidth).height(height).clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = .07f))) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(desktopText("Lyrics"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Color.White)
                LyricsCardButton(onOpenWindow, Icons.Outlined.PictureInPictureAlt, "Open lyrics mini-player")
                LyricsCardButton(onExpand, Icons.Rounded.OpenInFull, "Expand lyrics")
                LyricsCardButton(onToggle, Icons.Default.KeyboardArrowUp,
                    if (collapsed) "Show lyrics" else "Collapse lyrics", arrowRotation)
            }
            if (!collapsed || height > 48.dp) {
                Box(Modifier.fillMaxWidth().height(contentHeight).clipToBounds().then(com.auralis.music.windows.dev.perfSkip("lyrics")).graphicsLayer { alpha = opacity }) {
                    lyricContent()
                }
            }
        }
    }
}

@Composable
private fun LyricsCardButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, rotation: Float = 0f) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)
        .border(1.dp, Color.White.copy(alpha = .25f), androidx.compose.foundation.shape.CircleShape)) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation })
    }
    }
}
