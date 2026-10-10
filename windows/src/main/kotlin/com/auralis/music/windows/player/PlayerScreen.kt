package com.auralis.music.windows.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.ui.components.AuralisSlider
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.lyrics.ExperimentalLyricsView
import com.auralis.music.ui.lyrics.LocalLyricsUi
import com.auralis.music.ui.player.ClassicPauseGlyph
import com.auralis.music.ui.player.ClassicPlayGlyph
import com.auralis.music.ui.player.ClassicSkipGlyph
import com.auralis.music.ui.theme.LocalAppearanceSettings
import com.auralis.music.windows.search.DesktopArtwork

/**
 * The full player: the phone's Blur background, artwork and controls on the left, and the
 * phone's MetroLyrics view (its default lyrics style) on the right.
 */
@Composable
fun PlayerScreen(
    playback: DesktopPlaybackController,
    lyrics: DesktopLyricsController,
    onClose: () -> Unit
) {
    val queue by playback.queue.collectAsState()
    val track = queue.current
    val artworkUrl = track?.thumbnail?.let { getHighResArtworkUrl(it) ?: it }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BlurredArtworkBackground(artworkUrl)

        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp)) {
            val wide = maxWidth > 900.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(if (wide) Modifier.weight(0.42f).widthIn(max = 520.dp) else Modifier.weight(1f))
                ) {
                    DesktopArtwork(
                        url = artworkUrl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .weight(1f, fill = false)
                            .shadow(24.dp, RoundedCornerShape(16.dp)),
                        cornerRadius = 16.dp,
                        contentDescription = track?.title
                    )
                    Spacer(Modifier.height(28.dp))
                    // The phone player's title block: bold title, artist at 75% white.
                    Text(
                        text = track?.title.orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = track?.artist.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 17.sp,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(12.dp))
                    PlayerSeekBar(playback)
                    Transport(playback)
                }
                if (wide) {
                    Box(Modifier.weight(0.58f).fillMaxHeight()) {
                        LyricsPanel(playback, lyrics)
                    }
                }
            }
        }

        IconButton(onClick = onClose, modifier = Modifier.padding(12.dp).align(Alignment.TopStart)) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player", tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun LyricsPanel(playback: DesktopPlaybackController, lyrics: DesktopLyricsController) {
    val state by lyrics.state.collectAsState()
    val queue by playback.queue.collectAsState()
    val playerState by playback.state.collectAsState()
    val positionState = playback.positionMs.collectAsState()
    val data = state.lyrics
    when {
        data == null && state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
        }
        else -> CompositionLocalProvider(
            LocalLyricsUi provides DesktopLyricsUi,
            // The phone's defaults: MetroLyrics, centred, standard blur.
            LocalAppearanceSettings provides remember { AppearanceSettings() }
        ) {
            ExperimentalLyricsView(
                lyrics = data,
                positionState = positionState,
                onSeekTo = playback::seekTo,
                isPlaying = playerState == VlcAudioPlayer.State.PLAYING,
                isBuffering = playerState == VlcAudioPlayer.State.BUFFERING,
                lyricsClockSource = playback.clock,
                track = queue.current,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun PlayerSeekBar(playback: DesktopPlaybackController) {
    val position by playback.positionMs.collectAsState()
    val length by playback.lengthMs.collectAsState()
    val state by playback.state.collectAsState()
    val queue by playback.queue.collectAsState()
    val duration = length.takeIf { it > 0 } ?: ((queue.current?.duration ?: 0L) * 1000L)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    // The phone player's slider and colours (white on the artwork background).
    AuralisSlider(
        value = fraction,
        onValueChange = { if (duration > 0) dragFraction = it },
        onValueChangeFinished = {
            dragFraction?.let { playback.seekTo((it * duration).toLong()) }
            dragFraction = null
        },
        isPlaying = state == VlcAudioPlayer.State.PLAYING,
        currentPosMs = dragFraction?.let { (it * duration).toLong() } ?: position,
        totalDurationMs = duration,
        sliderStyle = "Wavy",
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Transport(playback: DesktopPlaybackController) {
    val state by playback.state.collectAsState()
    val queue by playback.queue.collectAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        // Sizes from the phone's classic player: 64dp skip targets, 76dp play/pause.
        IconButton(onClick = playback::previous, modifier = Modifier.size(64.dp)) {
            ClassicSkipGlyph(forward = false, color = Color.White, modifier = Modifier.size(width = 46.dp, height = 28.dp))
        }
        Spacer(Modifier.width(20.dp))
        IconButton(onClick = playback::togglePlayPause, modifier = Modifier.size(76.dp)) {
            if (state == VlcAudioPlayer.State.PLAYING || state == VlcAudioPlayer.State.BUFFERING) {
                ClassicPauseGlyph(color = Color.White, modifier = Modifier.size(width = 34.dp, height = 36.dp))
            } else {
                ClassicPlayGlyph(color = Color.White, modifier = Modifier.size(width = 36.dp, height = 38.dp))
            }
        }
        Spacer(Modifier.width(20.dp))
        IconButton(onClick = playback::next, enabled = queue.hasNext, modifier = Modifier.size(64.dp)) {
            ClassicSkipGlyph(
                forward = true,
                color = Color.White.copy(alpha = if (queue.hasNext) 1f else 0.4f),
                modifier = Modifier.size(width = 46.dp, height = 28.dp)
            )
        }
    }
}

/**
 * The phone's "Blur" player background: the artwork blurred 120dp (drawn at half size and scaled
 * up, as the phone does), scaled 1.10, crossfading between songs, under a 30% black scrim.
 */
@Composable
private fun BlurredArtworkBackground(url: String?) {
    Crossfade(targetState = url, animationSpec = tween(700), label = "playerBackground") { artwork ->
        Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
            if (artwork != null) {
                val downscale = 2f
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize(1f / downscale)
                        .graphicsLayer {
                            scaleX = 1.10f * downscale
                            scaleY = 1.10f * downscale
                        }
                        .blur(120.dp / downscale)
                )
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
}
