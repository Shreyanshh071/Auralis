package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.search.DesktopArtwork

/**
 * The queue, drawn like the phone's player queue: a History section (with Clear) above
 * "Continue Playing", 44dp artwork with a play/pause overlay on the current song, white title
 * (bold when playing) and "artist · length". Click a song to play it; its menu removes it.
 */
@Composable
fun QueueView(playback: DesktopPlaybackController, modifier: Modifier = Modifier) {
    val queue by playback.queue.collectAsState()
    val state by playback.state.collectAsState()
    QueueList(queue, state == VlcAudioPlayer.State.PLAYING, playback::playAt, playback::removeAt,
        playback::clearHistory, playback::togglePlayPause, modifier)
}

@Composable
internal fun QueueList(queue: DesktopPlaybackController.Queue, isPlaying: Boolean,
    onPlayAt: (Int) -> Unit, onRemoveAt: (Int) -> Unit, onClearHistory: () -> Unit, onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()) {
    val upcomingStart = queue.index.coerceAtLeast(0)
    val history = queue.history
    val historyCount = history.size
    var initiallyPositioned by remember { mutableStateOf(false) }

    // Open on the playing song, as the phone's queue does.
    LaunchedEffect(queue.current != null) {
        if (!initiallyPositioned && queue.current != null) {
            if (historyCount > 0) listState.scrollToItem(historyCount + 1)
            initiallyPositioned = true
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
    // Keep enough trailing room to open at Continue Playing even with only one song left.
    val trailing = (maxHeight - 56.dp * (queue.tracks.size - upcomingStart) - 24.dp).coerceAtLeast(24.dp)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = trailing)) {
        if (historyCount > 0) {
            item(key = "history_header") {
                QueueSectionHeader("History", action = {
                    Text(
                        text = "Clear",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onClearHistory)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                })
            }
            items(historyCount, key = { "h${history[it].index}:${history[it].value.id}" }) { offset ->
                val (i, track) = history[offset]
                QueueRow(track, isCurrent = false, isPlaying = false, onClick = { onPlayAt(i) }, onRemove = { onRemoveAt(i) })
            }
        }
        item(key = "continue_header") {
            QueueSectionHeader("Continue Playing", modifier = Modifier.padding(top = if (historyCount > 0) 10.dp else 0.dp))
        }
        items(queue.tracks.size - upcomingStart, key = { "u${it + upcomingStart}:${queue.tracks[it + upcomingStart].id}" }) { offset ->
            val i = offset + upcomingStart
            QueueRow(
                queue.tracks[i],
                isCurrent = i == queue.index,
                isPlaying = isPlaying,
                onClick = { if (i == queue.index) onToggle() else onPlayAt(i) },
                onRemove = if (i == queue.index) null else ({ onRemoveAt(i) })
            )
        }
    }
}
}

@Composable
private fun QueueSectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        action?.invoke()
    }
}

@Composable
private fun QueueRow(track: Track, isCurrent: Boolean, isPlaying: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            DesktopArtwork(track.thumbnail, Modifier.fillMaxSize(), 8.dp, track.title)
            if (isCurrent) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.30f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Currently playing",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (track.duration > 0L) "${track.artist} · ${formatQueueTime(track.duration)}" else track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onRemove != null) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Remove from queue") }, onClick = { menuOpen = false; onRemove() })
                }
            }
        } else {
            Spacer(Modifier.width(40.dp))
        }
    }
}

private fun formatQueueTime(seconds: Long): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
