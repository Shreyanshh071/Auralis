package com.auralis.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.dynamicPrimary
import kotlin.math.roundToInt

/**
 * Every lyrics option on one page, under a live preview that plays demo lyrics with the
 * current settings, so a change shows the moment it's made. Text size and line spacing slide
 * right here; the preview follows the slider before the value is even saved.
 */
@Composable
internal fun LyricsSettingsScreen(
    settings: AppearanceSettings,
    onUpdate: (AppearanceSettings.() -> AppearanceSettings) -> Unit,
    onPickPosition: () -> Unit,
    onPickAnimation: () -> Unit,
    onBack: () -> Unit,
    /** An option sheet is open over the page: freeze the preview so the sheet animates smoothly. */
    paused: Boolean = false
) {
    val defaults = remember { AppearanceSettings() }
    var size by remember { mutableFloatStateOf(settings.lyricsTextSize) }
    var spacing by remember { mutableFloatStateOf(settings.lyricsLineSpacing) }
    // Follow changes made elsewhere (sync, reset) when not mid-drag.
    LaunchedEffect(settings.lyricsTextSize) { size = settings.lyricsTextSize }
    LaunchedEffect(settings.lyricsLineSpacing) { spacing = settings.lyricsLineSpacing }
    val preview = settings.copy(lyricsTextSize = size, lyricsLineSpacing = spacing)

    SettingsPage(title = str(R.string.lyrics), onBack = onBack) {
        item(key = "lyrics_preview") {
            Column {
                Text(
                    str(R.string.preview),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                )
                val nowPlaying = com.auralis.music.ui.lyrics.LocalNowPlayingLyrics.current
                // Only while a song is loaded; with the player closed the demo lines show.
                val liveLyrics = nowPlaying?.takeIf { it.title != null }?.lyrics?.takeIf {
                    it.syncType != com.auralis.music.domain.model.SyncType.PLAIN && it.lines.isNotEmpty()
                }
                // Keep the last song around so it can fade out after the player is closed.
                val lastNowPlaying = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(nowPlaying) }
                androidx.compose.runtime.SideEffect {
                    if (nowPlaying != null && liveLyrics != null) lastNowPlaying.value = nowPlaying
                }
                // Closing the song, opening one, or switching songs crossfades the preview.
                // Starting the live lyrics renderer while the page slides in made opening it stutter:
                // wait for the slide to finish, then fade the preview in.
                var previewReady by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(420)
                    previewReady = true
                }
                androidx.compose.animation.Crossfade(
                    targetState = when {
                        !previewReady -> PreviewSource.Pending
                        liveLyrics != null -> PreviewSource.Song(nowPlaying?.title.orEmpty(), liveLyrics)
                        else -> PreviewSource.Demo
                    },
                    animationSpec = androidx.compose.animation.core.tween(450),
                    label = "lyricsPreviewSource",
                    modifier = Modifier.fillMaxWidth().height(300.dp)
                ) { state ->
                    val source = nowPlaying?.takeIf { it.title != null } ?: lastNowPlaying.value
                    if (state is PreviewSource.Pending) {
                        // Same frame as the preview card, empty, while the page slides in.
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(Color(0xFF101016)))
                    } else if (state is PreviewSource.Song && source != null) {
                        CurrentSongLyricsPreview(
                            preview = preview,
                            nowPlaying = source,
                            lyrics = state.lyrics,
                            paused = paused,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Nothing playing, or no synced lyrics for it: demo lines with authored timing.
                        Box(Modifier.fillMaxSize()) {
                            com.auralis.music.ui.onboarding.TourLyricsPreview(
                                appearance = preview,
                                active = !paused,
                                modifier = Modifier.fillMaxSize()
                            )
                            PreviewTouchShield()
                        }
                    }
                }
            }
        }

        item(key = "lyrics_display") {
            SettingsGroup(str(R.string.lyrics_display)) {
                SliderRow(
                    icon = Icons.Default.AspectRatio,
                    title = str(R.string.lyrics_text_size),
                    valueLabel = str(R.string.x_sp, size.roundToInt()),
                    value = size,
                    range = 16f..36f,
                    steps = 19,
                    onChange = { size = it },
                    onDone = { onUpdate { copy(lyricsTextSize = size) } },
                    onReset = {
                        size = defaults.lyricsTextSize
                        onUpdate { copy(lyricsTextSize = defaults.lyricsTextSize) }
                    }
                )
                SliderRow(
                    icon = Icons.Default.LinearScale,
                    title = str(R.string.lyrics_line_spacing),
                    valueLabel = "${String.format(java.util.Locale.US, "%.1f", spacing)}x",
                    value = spacing,
                    range = 1f..4f,
                    steps = 29,
                    onChange = { spacing = it },
                    onDone = { onUpdate { copy(lyricsLineSpacing = spacing) } },
                    onReset = {
                        spacing = defaults.lyricsLineSpacing
                        onUpdate { copy(lyricsLineSpacing = defaults.lyricsLineSpacing) }
                    }
                )
                SettingsNavRow(
                    Icons.Default.GraphicEq,
                    str(R.string.lyrics_animation),
                    com.auralis.music.ui.i18n.UiLabels.of(
                        com.auralis.music.domain.model.LyricsAnimationMode.fromDisplayName(settings.lyricsAnimation).displayName
                    ),
                    onClick = onPickAnimation
                )
                SettingsNavRow(
                    Icons.Default.FormatAlignCenter,
                    str(R.string.lyrics_text_position),
                    com.auralis.music.ui.i18n.UiLabels.of(settings.lyricsTextPosition),
                    onClick = onPickPosition
                )
                SettingsSwitchRow(
                    Icons.Default.HideImage,
                    str(R.string.standard_lyrics_blur),
                    str(R.string.apply_soft_blur_focus_to_inactive_lyrics),
                    settings.standardLyricsBlur
                ) { enabled -> onUpdate { copy(standardLyricsBlur = enabled) } }
            }
        }

        item(key = "lyrics_behaviour") {
            SettingsGroup(str(R.string.lyrics_behaviour)) {
                SettingsSwitchRow(
                    Icons.Default.TouchApp,
                    str(R.string.change_lyrics_on_click),
                    str(R.string.seek_track_playback_to_the_clicked_lyric),
                    settings.changeLyricsOnTap
                ) { enabled -> onUpdate { copy(changeLyricsOnTap = enabled) } }
                SettingsSwitchRow(
                    Icons.Default.VerticalAlignBottom,
                    str(R.string.auto_scroll_lyrics),
                    str(R.string.automatically_keep_the_active_lyric_cent),
                    settings.autoScrollLyrics
                ) { enabled -> onUpdate { copy(autoScrollLyrics = enabled) } }
            }
        }
    }
}

/**
 * The song that's playing, with its own lyrics in sync, drawn by the real lyrics view under the
 * settings being edited. Read-only: taps here don't seek the song.
 */
@Composable
private fun CurrentSongLyricsPreview(
    preview: AppearanceSettings,
    nowPlaying: com.auralis.music.ui.lyrics.NowPlayingLyricsPreview,
    lyrics: com.auralis.music.domain.model.LyricsData,
    paused: Boolean = false,
    modifier: Modifier = Modifier
) {
    androidx.compose.runtime.CompositionLocalProvider(
        com.auralis.music.ui.theme.LocalAppearanceSettings provides preview
    ) {
        Column(
            modifier
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF101016))
        ) {
            Text(
                listOfNotNull(nowPlaying.title, nowPlaying.artist).joinToString(" · ").ifBlank { preview.lyricsAnimation },
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = .55f),
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(18.dp)
            )
            // Frame-smooth position from the player's clock (the coarse position only ticks a few
            // times a second, which made the preview step and stutter).
            val position = com.auralis.music.ui.lyrics.rememberLyricsClock(
                source = nowPlaying.clockSource,
                enabled = nowPlaying.isPlaying && !paused,
                fallbackPositionMs = nowPlaying.position
            )
            // Frozen on the current moment while paused, so nothing redraws behind the sheet.
            val frozen = androidx.compose.runtime.remember(paused) {
                androidx.compose.runtime.mutableLongStateOf(position.value)
            }
            val shownPosition: androidx.compose.runtime.State<Long> = if (paused) frozen else position
            Box(Modifier.fillMaxWidth().weight(1f)) {
                androidx.compose.runtime.key(preview.lyricsAnimation) {
                    com.auralis.music.ui.lyrics.SyncedLyricsView(
                        lyrics = lyrics,
                        positionState = shownPosition,
                        onSeekTo = {},
                        isPlaying = nowPlaying.isPlaying && !paused,
                        lyricsMode = com.auralis.music.domain.model.LyricsMode.CINEMA,
                        standardLyricsBlur = preview.standardLyricsBlur,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                PreviewTouchShield()
            }
        }
    }
}

/** What the preview shows; any change crossfades it. */
private sealed interface PreviewSource {
    data object Pending : PreviewSource
    data object Demo : PreviewSource
    data class Song(val title: String, val lyrics: com.auralis.music.domain.model.LyricsData) : PreviewSource
}

/**
 * Sits over the preview so its lines can't be tapped, long-pressed or selected. It only eats
 * taps; a drag still goes to the settings page, so scrolling over the preview works.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PreviewTouchShield() {
    Box(
        Modifier
            .matchParentSize()
            .pointerInput(Unit) { detectTapGestures(onTap = {}, onLongPress = {}, onDoubleTap = {}) }
    )
}

/** A settings row with its value and a reset button, and a slider underneath. */
@Composable
private fun SliderRow(
    icon: ImageVector,
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    onReset: () -> Unit
) {
    val accent = MaterialTheme.dynamicPrimary
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsRowIcon(icon, false)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                Text(valueLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = accent)
            }
            IconButton(onClick = onReset) {
                Icon(Icons.Default.RestartAlt, str(R.string.reset), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = .10f)
            ),
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}
