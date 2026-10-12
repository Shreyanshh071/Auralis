package com.auralis.music.windows.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.ui.theme.AppearanceSettingsStore
import com.auralis.music.ui.theme.LocalAppearanceSettings
import com.auralis.music.ui.theme.LocalAppearanceSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class FullscreenLyricsSettingsTest {
    @Test fun singleLineLyricsAlsoChangeHeightWithLineSpacing() {
        val spacing = mutableFloatStateOf(1.1f)
        var measured = 0
        val scene = ImageComposeScene(500, 220, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                CompositionLocalProvider(com.auralis.music.ui.lyrics.LocalLyricsUi provides com.auralis.music.ui.lyrics.LyricsUiPlatform(
                    strings = com.auralis.music.ui.lyrics.DefaultLyricsUi.strings, roundedMouseHover = true)) {
                    com.auralis.music.ui.lyrics.ExperimentalLyricsLine(0, com.auralis.music.domain.model.LyricLine(0, "Short lyric"),
                        true, true, false, false, false, remember { mutableLongStateOf(1000L) },
                        46f, spacing.floatValue, androidx.compose.ui.graphics.Color.White, "Left", true, 0,
                        com.auralis.music.domain.model.SyncType.LINE_SYNC, standardBlur = false,
                        onSizeChanged = { measured = it }, onClick = {}, onLongClick = {})
                }
            }
        }
        try {
            var frame = 0L
            repeat(10) { scene.render(frame++ * 16_666_667L).close() }
            val compact = measured
            spacing.floatValue = 1.6f
            repeat(10) { scene.render(frame++ * 16_666_667L).close() }
            assertTrue(measured > compact + 15, "Spacing must change gaps between short lyric rows as well as wrapped text")
        } finally { scene.close() }
    }

    @Test fun fullscreenReadsLiveSettingsBeyondTheOldSizeClamp() {
        val settings = MutableStateFlow(AppearanceSettings())
        val store = object : AppearanceSettingsStore {
            override val settingsFlow = settings
            override fun getInitialSettings() = settings.value
            override suspend fun updateSettings(value: AppearanceSettings) { settings.value = value }
            override suspend fun hideMostPlayedPlaylist(playlistId: String) {}
        }
        var observed = AppearanceSettings()
        val scene = ImageComposeScene(500, 500, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { CompositionLocalProvider(LocalAppearanceSettingsStore provides store,
                LocalAppearanceSettings provides AppearanceSettings()) {
                val value = rememberPlayerLyricsAppearance()
                SideEffect { observed = value }
            } }
        }
        try {
            var frame = 0L
            for ((size, spacing) in listOf(14f to 1.1f, 22f to 1.3f, 36f to 1.6f)) {
                settings.value = settings.value.copy(lyricsTextSize = size, lyricsLineSpacing = spacing)
                repeat(5) { scene.render(frame++ * 16_666_667L).close() }
                assertEquals(size * 1.35f, observed.lyricsTextSize, .001f)
                assertEquals(spacing, observed.lyricsLineSpacing)
            }
        } finally { scene.close() }
    }
}
