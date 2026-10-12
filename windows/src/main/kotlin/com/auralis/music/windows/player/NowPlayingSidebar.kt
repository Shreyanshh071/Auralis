package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.search.DesktopArtwork

@Composable
internal fun NowPlayingSidebar(playback: DesktopPlaybackController, lyrics: DesktopLyricsController, onExpand: () -> Unit, modifier: Modifier,
    palette: com.auralis.music.ui.theme.ArtworkPalette, onCollapse: () -> Unit) {
    val queue by playback.queue.collectAsState()
    val track = queue.current ?: return
    NowPlayingSidebarContent(track, onExpand, modifier, palette, onCollapse) {
        val state by lyrics.state.collectAsState()
        val playerState by playback.state.collectAsState()
        val position = com.auralis.music.ui.lyrics.rememberLyricsClock(playback.clock,
            enabled = playerState == VlcAudioPlayer.State.PLAYING,
            fallbackPositionMs = playback.positionMs.collectAsState())
        key(track.id) {
            if (state.lyrics == null && state.isLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White.copy(alpha = .7f), modifier = Modifier.size(24.dp))
            } else SidebarLyricsView(state.lyrics, position, playback::seekTo)
        }
    }
}

@Composable
internal fun NowPlayingSidebarContent(track: Track, onExpand: () -> Unit, modifier: Modifier,
    palette: com.auralis.music.ui.theme.ArtworkPalette = com.auralis.music.ui.theme.DefaultArtworkPalette,
    onCollapse: (() -> Unit)? = null,
    lyricContent: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
    var lyricsCollapsed by remember { mutableStateOf(false) }
    var lyricsWindow by remember { mutableStateOf(false) }
    if (lyricsWindow) androidx.compose.ui.window.Dialog(onDismissRequest = { lyricsWindow = false }) {
        Surface(shape = RoundedCornerShape(16.dp), color = palette.secondary.copy(alpha = .95f)) {
            Column(Modifier.width(360.dp).height(440.dp).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(track.title, Modifier.weight(1f), color = Color.White, maxLines = 1)
                    TextButton(onClick = { lyricsWindow = false }) { Text("Close", color = Color.White) }
                }
                Box(Modifier.weight(1f)) { lyricContent() }
            }
        }
    }
    val coverSize = minOf(maxWidth - 42.dp, maxHeight * .40f)
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) {
    ArtworkAura(palette, Modifier.fillMaxSize(), track.thumbnail)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Now playing", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = .75f))
            if (onCollapse != null) IconButton(onClick = onCollapse, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.ChevronRight, "Collapse now playing", tint = Color.White.copy(alpha = .75f))
            }
        }
        // Title, artist and lyrics card share the cover's edges, even when height shrinks the cover.
        DesktopArtwork(track.thumbnail, Modifier.size(coverSize), 12.dp, track.title, highRes = true)
        Text(track.title, Modifier.width(coverSize), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
        Text(track.artist, Modifier.width(coverSize), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .75f), maxLines = 2)
        Column(Modifier.weight(1f, fill = false).animateContentSize().heightIn(max = if (lyricsCollapsed) 48.dp else 320.dp)
            .width(coverSize).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .07f))) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Lyrics", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Color.White)
                LyricsCardButton({ lyricsWindow = true }, Icons.Outlined.PictureInPictureAlt, "Open lyrics window")
                LyricsCardButton(onExpand, Icons.Rounded.OpenInFull, "Expand lyrics")
                LyricsCardButton({ lyricsCollapsed = !lyricsCollapsed },
                    if (lyricsCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    if (lyricsCollapsed) "Show lyrics" else "Collapse lyrics")
            }
            androidx.compose.animation.AnimatedVisibility(!lyricsCollapsed, modifier = Modifier.weight(1f)) {
                Box(Modifier.fillMaxSize()) { lyricContent() }
            }
        }
    }
    }
    }
}

@Composable
private fun LyricsCardButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)
        .border(1.dp, Color.White.copy(alpha = .25f), androidx.compose.foundation.shape.CircleShape)) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(16.dp))
    }
    }
}
