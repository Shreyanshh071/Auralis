package com.auralis.music.windows.notch

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ImageComposeScene
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.ui.theme.AppearanceSettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.swing.Swing
import kotlin.test.*

class NotchAppearanceTest {
    @Test fun shellOutsideAppThemeUsesSavedStyleAndFollowsChanges() = runBlocking {
        val saved = MutableStateFlow(AppearanceSettings(lyricsAnimation = "Spicy Lyrics"))
        val store = object : AppearanceSettingsStore {
            override fun getInitialSettings() = saved.value
            override val settingsFlow = saved
            override suspend fun updateSettings(settings: AppearanceSettings) { saved.value = settings }
            override suspend fun hideMostPlayedPlaylist(playlistId: String) {}
        }
        withContext(Dispatchers.Swing) {
            var observed: String? = null
            // Deliberately no theme or settings composition-local provider: same placement as Main.
            val scene = ImageComposeScene(1, 1, coroutineContext = Dispatchers.Swing) {
                val settings = rememberNotchAppearance(store)
                SideEffect { observed = settings.lyricsAnimation }
            }
            try {
                repeat(4) { scene.render(System.nanoTime()).close(); delay(16) }
                assertEquals("Spicy Lyrics", observed)
                saved.value = saved.value.copy(lyricsAnimation = "Auralis (Fluid)")
                repeat(4) { scene.render(System.nanoTime()).close(); delay(16) }
                assertEquals("Auralis (Fluid)", observed)
            } finally { scene.close() }
        }
    }
}
