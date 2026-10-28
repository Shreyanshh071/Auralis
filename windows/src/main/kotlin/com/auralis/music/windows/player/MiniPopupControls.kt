package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.search.DesktopArtwork
import com.auralis.music.ui.components.clickable

@Composable
internal fun MiniPopupControls(track: Track, scale: Float, playing: Boolean?, onOpenPlayer: () -> Unit,
    onPrevious: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit,
    onPlaylistMenu: (Boolean) -> Unit = {}, seekBar: @Composable () -> Unit = {}) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f))))
        .padding(horizontal = (12 * scale).dp, vertical = (9 * scale).dp)) {
        val stacked = maxWidth < 520.dp
        val availableWidth = maxWidth
        val artwork = (42 * scale).dp.coerceAtLeast(40.dp)
        val playSize = (40 * scale).dp
        @Composable fun SongInfo(modifier: Modifier) {
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(artwork).clip(RoundedCornerShape(5.dp)).clickable(onClick = onOpenPlayer).coverExpandCue()) {
                    DesktopArtwork(track.thumbnail, Modifier.fillMaxSize(), 5.dp, "Open player")
                }
                Spacer(Modifier.width((8 * scale).dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(track.title, Modifier.weight(1f, fill = false), fontSize = (15 * scale).sp,
                            lineHeight = (18 * scale).sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        PlayerPlaylistButton(track, onMenuVisibilityChanged = onPlaylistMenu)
                    }
                    Text(track.artist, fontSize = (11 * scale).sp, lineHeight = (14 * scale).sp, color = Color.White.copy(alpha = .7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        @Composable fun PlaybackButtons(modifier: Modifier) {
            Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((6 * scale).dp)) {
                IconButton(onPrevious, Modifier.size((32 * scale).dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size((21 * scale).dp)) }
                IconButton(onToggle, Modifier.size(playSize)) { Icon(if (playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    "Play or pause", tint = Color.White, modifier = Modifier.size((28 * scale).dp)) }
                IconButton(onNext, Modifier.size((32 * scale).dp)) { Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size((21 * scale).dp)) }
            }
        }
        Column(Modifier.fillMaxWidth()) {
            if (stacked || playing == null) {
                SongInfo(Modifier.fillMaxWidth())
                if (playing != null) { Spacer(Modifier.height((4 * scale).dp)); PlaybackButtons(Modifier.align(Alignment.CenterHorizontally)) }
            } else {
                Box(Modifier.fillMaxWidth().height(playSize.coerceAtLeast(artwork))) {
                    SongInfo(Modifier.align(Alignment.CenterStart).widthIn(max = availableWidth / 2 - (75 * scale).dp))
                    PlaybackButtons(Modifier.align(Alignment.Center))
                }
            }
            seekBar()
        }
    }
    }
}
