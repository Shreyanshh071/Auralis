package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.windows.search.DesktopArtwork

/** The bar along the bottom of the window: the current song, the controls and the seek bar. */
@Composable
fun NowPlayingBar(controller: DesktopPlaybackController?, onOpenPlayer: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .background(colors.surface)
            .padding(horizontal = 20.dp)
    ) {
        if (controller == null) {
            NoPlayer()
            return@Row
        }
        val queue by controller.queue.collectAsState()
        val state by controller.state.collectAsState()
        val resolving by controller.resolving.collectAsState()
        val track = queue.current

        // Current song (left third); clicking it opens the full player.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .clickable(enabled = track != null, onClick = onOpenPlayer)
                .padding(vertical = 6.dp)
        ) {
            DesktopArtwork(track?.thumbnail, Modifier.size(56.dp), 10.dp, null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    track?.title ?: "Nothing playing",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (track != null) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (track != null) {
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Controls and seek bar (centre)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1.4f).widthIn(max = 640.dp)
        ) {
            val enabled = track != null
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = controller::previous, enabled = enabled) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
                }
                val busy = resolving || state == VlcAudioPlayer.State.BUFFERING
                FilledIconButton(
                    onClick = controller::togglePlayPause,
                    enabled = enabled,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                    modifier = Modifier.size(42.dp)
                ) {
                    val showPause = state == VlcAudioPlayer.State.PLAYING || (busy && enabled)
                    Box(contentAlignment = Alignment.Center) {
                        if (busy && enabled) {
                            CircularProgressIndicator(Modifier.size(38.dp), color = colors.onPrimary.copy(alpha = 0.6f), strokeWidth = 2.dp)
                        }
                        Icon(
                            if (showPause) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (showPause) "Pause" else "Play"
                        )
                    }
                }
                IconButton(onClick = controller::next, enabled = queue.hasNext) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                }
            }
            SeekBar(controller, enabled)
        }

        // Volume (right)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.weight(1f)
        ) {
            val volume by controller.volume.collectAsState()
            var volumeBeforeMute by remember { mutableStateOf(1f) }
            IconButton(onClick = {
                if (volume > 0f) { volumeBeforeMute = volume; controller.setVolume(0f) }
                else controller.setVolume(volumeBeforeMute.takeIf { it > 0f } ?: 1f)
            }) {
                Icon(
                    if (volume > 0f) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                    contentDescription = if (volume > 0f) "Mute" else "Unmute",
                    tint = colors.onSurfaceVariant
                )
            }
            com.auralis.music.ui.components.AuralisSlider(
                value = volume,
                onValueChange = controller::setVolume,
                onValueChangeFinished = {},
                isPlaying = false,
                currentPosMs = 0L,
                totalDurationMs = 0L,
                sliderStyle = "Slim",
                activeTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                thumbColor = MaterialTheme.colorScheme.onSurface,
                showTimestamps = false,
                modifier = Modifier.width(120.dp)
            )
        }
    }
}

@Composable
private fun SeekBar(controller: DesktopPlaybackController, enabled: Boolean) {
    val position by controller.positionMs.collectAsState()
    val length by controller.lengthMs.collectAsState()
    val queue by controller.queue.collectAsState()
    // Before libVLC knows the length, use the song's length from search.
    val duration = length.takeIf { it > 0 } ?: ((queue.current?.duration ?: 0L) * 1000L)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownMs = dragFraction?.let { (it * duration).toLong() } ?: position

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TimeLabel(if (enabled) shownMs else 0L)
        val playing by controller.state.collectAsState()
        com.auralis.music.ui.components.AuralisSlider(
            value = fraction,
            onValueChange = { if (enabled && duration > 0) dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { controller.seekTo((it * duration).toLong()) }
                dragFraction = null
            },
            isPlaying = playing == VlcAudioPlayer.State.PLAYING,
            currentPosMs = shownMs,
            totalDurationMs = duration,
            sliderStyle = "Wavy",
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
            thumbColor = MaterialTheme.colorScheme.primary,
            showTimestamps = false,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
        )
        TimeLabel(if (enabled) duration else 0L)
    }
}

@Composable
private fun TimeLabel(ms: Long) {
    val totalSeconds = ms / 1000
    val text = if (totalSeconds >= 3600) {
        "%d:%02d:%02d".format(totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
    } else {
        "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 36.dp))
}

@Composable
private fun NoPlayer() {
    Column {
        Text("Playback needs VLC", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Auralis plays audio through libVLC. Install VLC (64-bit) and restart Auralis.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
