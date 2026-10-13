package com.auralis.music.windows.player

import com.auralis.music.ui.components.IconButton

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.Dp
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
    panel: PlayerPanel,
    onPanelChange: (PlayerPanel) -> Unit,
    onClose: () -> Unit,
    palette: com.auralis.music.ui.theme.ArtworkPalette = com.auralis.music.ui.theme.DefaultArtworkPalette
) {
    val queue by playback.queue.collectAsState()
    val track = queue.current
    val appearance = LocalAppearanceSettings.current
    val artworkUrl = track?.thumbnail?.let { getHighResArtworkUrl(it) ?: it }

    // Opaque: the main window must never show through the player.
    Box(Modifier.fillMaxSize().background(Color(0xFF050505))) {
        PlayerBackdrop(appearance.playerBackgroundStyle, artworkUrl, palette)

        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 32.dp)) {
            val wide = maxWidth > 900.dp
            val leftWidth = if (wide) maxWidth * 0.44f else maxWidth
            // Spicy Lyrics' layout: the cover with room around it, the seek bar under it, then
            // the title block; playback controls and volume appear on the cover while hovered.
            val coverSize = minOf(leftWidth - 24.dp, maxHeight - 180.dp, 640.dp).coerceAtLeast(160.dp)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(if (wide) Modifier.width(leftWidth) else Modifier.weight(1f))
                ) {
                    CoverWithControls(playback, artworkUrl, track?.title, coverSize, ContentScale.Crop)
                    Spacer(Modifier.height(22.dp))
                    PlayerSeekBar(playback, Modifier.width(coverSize))
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = track?.title.orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(coverSize)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = track?.artist.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 22.sp,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(coverSize)
                    )
                }
                if (wide) {
                    Column(Modifier.weight(0.58f).fillMaxHeight()) {
                        PanelTabs(panel, onSelect = onPanelChange)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (panel) {
                                PlayerPanel.LYRICS -> LyricsPanel(playback, lyrics)
                                PlayerPanel.QUEUE -> QueueView(playback)
                            }
                        }
                    }
                }
            }
        }

        IconButton(onClick = onClose, modifier = Modifier.padding(12.dp).align(Alignment.TopStart)) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player", tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

/** What the player shows beside the artwork. */
enum class PlayerPanel(val label: String) { LYRICS("Lyrics"), QUEUE("Queue") }

/** Lyrics / Queue switch: white text on a translucent pill, like the player's other glass controls. */
@Composable
private fun PanelTabs(selected: PlayerPanel, onSelect: (PlayerPanel) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(4.dp)
    ) {
        PlayerPanel.entries.forEach { item ->
            val isSelected = item == selected
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelLarge,
                fontSize = 16.sp,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(item) }
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
internal fun rememberPlayerLyricsAppearance(): AppearanceSettings {
    val appearanceStore = com.auralis.music.ui.theme.LocalAppearanceSettingsStore.current
    val appearance = appearanceStore?.settingsFlow?.collectAsState(appearanceStore.getInitialSettings())?.value
        ?: LocalAppearanceSettings.current
    return appearance.copy(lyricsTextSize = appearance.lyricsTextSize * 1.35f)
}

@Composable
internal fun LyricsPanel(playback: DesktopPlaybackController, lyrics: DesktopLyricsController) {
    val appearance = rememberPlayerLyricsAppearance()
    val state by lyrics.state.collectAsState()
    val queue by playback.queue.collectAsState()
    val playerState by playback.state.collectAsState()
    val coarsePosition = playback.positionMs.collectAsState()
    // As on the phone: the view gets the position sampled every frame and interpolated between
    // libVLC's reports (rememberLyricsClock), not the raw reports (~4 a second), which made the
    // word sweep and the intro circle advance in steps.
    val positionState = com.auralis.music.ui.lyrics.rememberLyricsClock(
        source = playback.clock,
        enabled = playerState == VlcAudioPlayer.State.PLAYING,
        fallbackPositionMs = coarsePosition
    )
    val data = state.lyrics
    when {
        data == null && state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
        }
        else -> CompositionLocalProvider(
            LocalLyricsUi provides DesktopLyricsUi,
            // Larger desktop presentation; retain the user's chosen renderer and size preference.
            LocalAppearanceSettings provides appearance
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
private fun PlayerSeekBar(playback: DesktopPlaybackController, modifier: Modifier = Modifier) {
    val appearanceStore = com.auralis.music.data.datastore.LocalSettingsStores.current.appearance
    val appearance by appearanceStore.settingsFlow.collectAsState(appearanceStore.getInitialSettings())
    val length by playback.lengthMs.collectAsState()
    val state by playback.state.collectAsState()
    // The same per-frame clock as the lyrics, so the bar glides instead of jumping ~4 times a second.
    val position by com.auralis.music.ui.lyrics.rememberLyricsClock(
        source = playback.clock,
        enabled = state == VlcAudioPlayer.State.PLAYING,
        fallbackPositionMs = playback.positionMs.collectAsState()
    )
    val queue by playback.queue.collectAsState()
    val duration = length.takeIf { it > 0 } ?: ((queue.current?.duration ?: 0L) * 1000L)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shown = dragFraction?.let { (it * duration).toLong() } ?: position
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PlayerTime(shown)
        com.auralis.music.ui.components.AuralisPlayerSlider(
            value = fraction,
            onValueChange = { if (duration > 0) dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { playback.seekTo((it * duration).toLong()) }
                dragFraction = null
            },
            isPlaying = state == VlcAudioPlayer.State.PLAYING,
            currentPosMs = shown, totalDurationMs = duration,
            sliderStyle = appearance.playerSliderStyle, showTimestamps = false,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        )
        PlayerTime(duration)
    }
}

@Composable
private fun PlayerTime(ms: Long) {
    val seconds = (ms / 1000).coerceAtLeast(0)
    Text("%d:%02d".format(seconds / 60, seconds % 60), fontSize = 17.sp, color = Color.White.copy(alpha = .8f))
}

/** The cover; hovering shows shuffle/previous/play/next/repeat along its bottom and volume on its right. */
@Composable
private fun CoverWithControls(playback: DesktopPlaybackController, artworkUrl: String?, title: String?, size: Dp, scale: ContentScale) {
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val reveal by androidx.compose.animation.core.animateFloatAsState(if (hovered) 1f else 0f, tween(220), label = "coverControls")
    val state by playback.state.collectAsState()
    val queue by playback.queue.collectAsState()
    val repeat by playback.repeatMode.collectAsState()
    val roomActive by playback.roomActive.collectAsState()
    val volume by playback.volume.collectAsState()
    Box(Modifier.size(size).shadow(24.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).hoverable(hover)) {
        DesktopArtwork(highRes = true, url = artworkUrl, modifier = Modifier.fillMaxSize(), cornerRadius = 16.dp,
            contentDescription = title, contentScale = scale)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal }) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .15f), .6f to Color.Black.copy(alpha = .25f), 1f to Color.Black.copy(alpha = .6f))))
            if (reveal > 0f) {
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = playback::shuffleUpcoming, enabled = queue.hasNext && !roomActive) {
                        Icon(Icons.Rounded.Shuffle, "Shuffle upcoming songs", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    IconButton(onClick = playback::previous, modifier = Modifier.size(56.dp)) {
                        ClassicSkipGlyph(forward = false, color = Color.White, modifier = Modifier.size(width = 46.dp, height = 28.dp))
                    }
                    IconButton(onClick = playback::togglePlayPause, modifier = Modifier.size(64.dp)) {
                        if (state == VlcAudioPlayer.State.PLAYING || state == VlcAudioPlayer.State.BUFFERING)
                            ClassicPauseGlyph(color = Color.White, modifier = Modifier.size(width = 34.dp, height = 40.dp))
                        else ClassicPlayGlyph(color = Color.White, modifier = Modifier.size(width = 36.dp, height = 42.dp))
                    }
                    IconButton(onClick = playback::next, enabled = queue.hasNext, modifier = Modifier.size(56.dp)) {
                        ClassicSkipGlyph(forward = true, color = Color.White.copy(alpha = if (queue.hasNext) 1f else .4f),
                            modifier = Modifier.size(width = 46.dp, height = 28.dp))
                    }
                    IconButton(onClick = playback::cycleRepeatMode, enabled = !roomActive) {
                        Icon(if (repeat == DesktopPlaybackController.RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            "Repeat", tint = if (repeat == DesktopPlaybackController.RepeatMode.OFF) Color.White.copy(alpha = .7f) else Color.White,
                            modifier = Modifier.size(28.dp))
                    }
                }
                Column(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).height(size * .42f),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    HoverSlider(value = volume, onValueChange = playback::setVolume, vertical = true,
                        thin = 14.dp, thick = 20.dp, hitSize = 28.dp, modifier = Modifier.weight(1f))
                    IconButton(onClick = playback::toggleMute, modifier = Modifier.size(30.dp)) {
                        Icon(if (volume > 0f) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                            if (volume > 0f) "Mute" else "Unmute", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/**
 * The phone's "Blur" player background: the artwork blurred 120dp (drawn at half size and scaled
 * up, as the phone does), scaled 1.10, crossfading between songs, under a 30% black scrim.
 * Browsing overrides blur/brightness/scale to match Liquify without affecting the player defaults.
 */
@Composable
internal fun BlurredArtworkBackground(url: String?, blurRadius: Dp = 120.dp, brightness: Float = .70f, artworkScale: Float = 1.10f) {
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
                            scaleX = artworkScale * downscale
                            scaleY = artworkScale * downscale
                        }
                        .blur(blurRadius / downscale)
                )
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - brightness.coerceIn(0f, 1f))))
}
