package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.auralis.music.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer

/** What the right-hand panel shows; the player bar's lyrics and queue buttons switch it. */
enum class SidePanel { NOW_PLAYING, LYRICS, QUEUE }

/** A titled right-hand panel with a close button, on the panel surface. */
@Composable
internal fun SidePanelSheet(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close $title") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** The synced lyrics, full height, as in the Now playing card. */
@Composable
internal fun SideLyricsPanel(playback: DesktopPlaybackController, lyrics: DesktopLyricsController) {
    val queue by playback.queue.collectAsState()
    val state by lyrics.state.collectAsState()
    val playerState by playback.state.collectAsState()
    val position = com.auralis.music.ui.lyrics.rememberLyricsClock(playback.clock,
        enabled = playerState == VlcAudioPlayer.State.PLAYING,
        fallbackPositionMs = playback.positionMs.collectAsState())
    key(queue.current?.id) {
        if (state.lyrics == null && state.isLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp))
        } else SidebarLyricsView(state.lyrics, position, playback::seekTo, queue.current?.duration?.times(1000L), lineGap = 10.dp)
    }
}
