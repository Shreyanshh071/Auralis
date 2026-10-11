package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.filled.ChevronRight
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
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.search.DesktopArtwork

@Composable
internal fun NowPlayingSidebar(playback: DesktopPlaybackController, lyrics: DesktopLyricsController, onExpand: () -> Unit, modifier: Modifier,
    palette: com.auralis.music.ui.theme.ArtworkPalette, onCollapse: () -> Unit) {
    val queue by playback.queue.collectAsState()
    val track = queue.current ?: return
    NowPlayingSidebarContent(track, onExpand, modifier, palette, onCollapse) {
        val appearance = com.auralis.music.ui.theme.LocalAppearanceSettings.current
        CompositionLocalProvider(com.auralis.music.ui.theme.LocalAppearanceSettings provides
            appearance.copy(lyricsTextSize = 20f, lyricsTextPosition = "Left")) {
            LyricsPanel(playback, lyrics)
        }
    }
}

@Composable
internal fun NowPlayingSidebarContent(track: Track, onExpand: () -> Unit, modifier: Modifier,
    palette: com.auralis.music.ui.theme.ArtworkPalette = com.auralis.music.ui.theme.DefaultArtworkPalette,
    onCollapse: (() -> Unit)? = null,
    lyricContent: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
    val coverSize = minOf(maxWidth - 42.dp, maxHeight * .40f)
    Box(Modifier.fillMaxSize().padding(start = 2.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).clip(RoundedCornerShape(16.dp))) {
    ArtworkAura(palette, Modifier.fillMaxSize(), track.thumbnail)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Now playing", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = .75f))
            if (onCollapse != null) IconButton(onClick = onCollapse, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.ChevronRight, "Collapse now playing", tint = Color.White.copy(alpha = .75f))
            }
        }
        DesktopArtwork(track.thumbnail, Modifier.size(coverSize).align(Alignment.CenterHorizontally), 12.dp, track.title)
        Text(track.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
        Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .75f), maxLines = 2)
        Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .10f))) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Lyrics", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Color.White)
                IconButton(onClick = onExpand) { Icon(Icons.Rounded.OpenInFull, "Expand lyrics", tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { lyricContent() }
        }
    }
    }
    }
}
