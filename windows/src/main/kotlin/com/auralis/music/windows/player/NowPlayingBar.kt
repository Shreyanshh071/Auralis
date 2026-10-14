package com.auralis.music.windows.player

import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.QueueMusic

import androidx.compose.foundation.background
import com.auralis.music.ui.components.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.outlined.Textsms
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import com.auralis.music.ui.components.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import com.auralis.music.ui.components.FilledIconButton
import androidx.compose.material3.Icon
import com.auralis.music.ui.components.IconButton
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
fun NowPlayingBar(controller: DesktopPlaybackController?, onOpenPlayer: () -> Unit = {},
    onOpenQueue: () -> Unit = onOpenPlayer, onOpenLyrics: () -> Unit = onOpenPlayer) {
    NowPlayingBarContent(controller, onOpenPlayer, onOpenQueue, onOpenLyrics)
}

@Composable
private fun NowPlayingBarContent(controller: DesktopPlaybackController?, onOpenPlayer: () -> Unit,
    onOpenQueue: () -> Unit, onOpenLyrics: () -> Unit) {
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
        val repeatMode by controller.repeatMode.collectAsState()
        val roomActive by controller.roomActive.collectAsState()
        val track = queue.current

        val whiteButtons = IconButtonDefaults.iconButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = .35f))
        // Only the cover is interactive; the song-info region has no hover highlight.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .padding(vertical = 6.dp)
        ) {
            DesktopArtwork(track?.thumbnail, Modifier.size(56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .clickable(enabled = track != null, onClick = onOpenPlayer), 10.dp, "Open player")
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
            // A little below centre: the controls and the seek bar's touch area are taller than the
            // bar, so centred the buttons touched (and were cut by) its top edge.
            modifier = Modifier.weight(1.4f).widthIn(max = 640.dp).offset(y = 7.dp)
        ) {
            val enabled = track != null
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = controller::shuffleUpcoming, enabled = queue.hasNext && !roomActive,
                    colors = whiteButtons, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle upcoming songs", Modifier.size(20.dp))
                }
                IconButton(onClick = controller::previous, enabled = enabled, colors = whiteButtons, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(22.dp))
                }
                val busy = resolving || state == VlcAudioPlayer.State.BUFFERING
                FilledIconButton(
                    onClick = controller::togglePlayPause,
                    enabled = enabled,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black,
                        disabledContainerColor = Color.White.copy(alpha = .35f), disabledContentColor = Color.Black.copy(alpha = .6f)),
                    modifier = Modifier.size(32.dp)
                ) {
                    val showPause = state == VlcAudioPlayer.State.PLAYING || (busy && enabled)
                    Box(contentAlignment = Alignment.Center) {
                        if (busy && enabled) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = Color.Black.copy(alpha = 0.6f), strokeWidth = 2.dp)
                        }
                        Icon(
                            if (showPause) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (showPause) "Pause" else "Play"
                        )
                    }
                }
                IconButton(onClick = controller::next, enabled = queue.hasNext || (enabled && !roomActive && repeatMode == DesktopPlaybackController.RepeatMode.ALL), colors = whiteButtons, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = controller::cycleRepeatMode, enabled = enabled && !roomActive,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = if (repeatMode == DesktopPlaybackController.RepeatMode.OFF)
                        colors.onSurfaceVariant else colors.primary), modifier = Modifier.size(34.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (repeatMode == DesktopPlaybackController.RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            when (repeatMode) { DesktopPlaybackController.RepeatMode.OFF -> "Repeat off"; DesktopPlaybackController.RepeatMode.ALL -> "Repeat all"; DesktopPlaybackController.RepeatMode.ONE -> "Repeat one" }, Modifier.size(20.dp))
                        if (repeatMode != DesktopPlaybackController.RepeatMode.OFF) Box(Modifier.size(3.dp).background(colors.primary, CircleShape))
                    }
                }
            }
            SeekBar(controller, enabled)
        }

        // Right: volume (mute + slider), sound output, lyrics, queue.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.weight(1f)
        ) {
            val volume by controller.volume.collectAsState()
            val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
            IconButton(onClick = controller::toggleMute, modifier = Modifier.size(34.dp)) {
                Icon(if (volume > 0f) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                    contentDescription = if (volume > 0f) "Mute" else "Unmute", tint = iconTint, modifier = Modifier.size(20.dp))
            }
            HoverSlider(value = volume, onValueChange = controller::setVolume, modifier = Modifier.width(110.dp),
                activeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
            Spacer(Modifier.width(10.dp))
            Box {
                var devices by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
                IconButton(onClick = { devices = controller.outputDevices() }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Headphones, "Sound output", tint = iconTint, modifier = Modifier.size(20.dp))
                }
                com.auralis.music.windows.GlassDropdownMenu(expanded = devices != null, onDismissRequest = { devices = null }) {
                    com.auralis.music.windows.GlassDropdownMenuHeader("Sound output")
                    val chosen = controller.outputDevice
                    // libVLC lists Windows' default as an empty id; show it as "Windows default".
                    devices.orEmpty().forEach { (id, name) ->
                        val selected = if (chosen == null) id.isEmpty() else id == chosen
                        com.auralis.music.windows.GlassDropdownMenuItem(
                            text = { Text(if (id.isEmpty()) "Windows default" else name, maxLines = 1) },
                            leadingIcon = {
                                val isHeadphones = name.contains("headphone", ignoreCase = true) || name.contains("buds", ignoreCase = true) || name.contains("airpod", ignoreCase = true)
                                Icon(
                                    if (isHeadphones) Icons.Rounded.Headphones else Icons.AutoMirrored.Rounded.VolumeUp,
                                    null,
                                    Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = { if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) },
                            selected = selected,
                            onClick = { controller.setOutputDevice(id.ifEmpty { null }); devices = null }
                        )
                    }
                    if (devices.isNullOrEmpty()) com.auralis.music.windows.GlassDropdownMenuItem(text = { Text("No outputs found") }, onClick = { devices = null }, enabled = false)
                }
            }
            IconButton(onClick = onOpenLyrics, enabled = track != null, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Outlined.Textsms, "Lyrics", tint = iconTint, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onOpenQueue, enabled = track != null, modifier = Modifier.size(34.dp)) {
                Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, "Queue", tint = iconTint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun SeekBar(controller: DesktopPlaybackController, enabled: Boolean) {
    val appearanceStore = com.auralis.music.data.datastore.LocalSettingsStores.current.appearance
    val appearance by appearanceStore.settingsFlow.collectAsState(appearanceStore.getInitialSettings())
    val state by controller.state.collectAsState()
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
        com.auralis.music.ui.components.AuralisPlayerSlider(
            value = fraction,
            onValueChange = { if (enabled && duration > 0) dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { controller.seekTo((it * duration).toLong()) }
                dragFraction = null
            },
            isPlaying = state == VlcAudioPlayer.State.PLAYING,
            currentPosMs = shownMs, totalDurationMs = duration,
            sliderStyle = appearance.playerSliderStyle, showTimestamps = false,
            activeTrackColor = MaterialTheme.colorScheme.onSurface,
            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
            // The mini player's bar is slim; it still thickens a little on hover.
            slimThicknessRest = 6.dp, slimThicknessHover = 10.dp,
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
