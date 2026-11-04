package com.auralis.music.windows.immersive

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.auralis.music.data.datastore.LocalSettingsStores
import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.settings.DesktopDataStores
import com.auralis.music.ui.theme.AuralisDesktopTheme
import com.auralis.music.ui.theme.LocalAppearanceSettingsStore
import com.auralis.music.windows.settings.DesktopThemeOverrides
import com.auralis.music.windows.settings.rememberArtworkPalette

/** Shares the main window's render surface instead of opening another GPU window. */
@Composable
internal fun EmbeddedImmersive(
    session: ImmersiveSession,
    playback: DesktopPlaybackController,
    lyrics: DesktopLyricsController,
    stores: DesktopDataStores,
    display: ImmersiveDisplay
) {
    val queue by playback.queue.collectAsState()
    val appearance by stores.appearance.settingsFlow.collectAsState(stores.appearance.getInitialSettings())
    val artwork = queue.current?.thumbnail
    val palette = rememberArtworkPalette(artwork)
    val preferences = remember { ImmersivePreferences() }
    val focus = remember { FocusRequester() }
    val actions = remember(session, playback, display) {
        object : ImmersiveKeyActions {
            override fun togglePlayPause() = playback.togglePlayPause()
            override fun next() = playback.next()
            override fun previous() = playback.previous()
            override fun seekBy(deltaMs: Long) = playback.seekBy(deltaMs)
            override fun toggleMute() = playback.toggleMute()
            override fun nextView() {
                val current = preferences.view(display, 0)
                preferences.setView(display, ImmersiveView.entries[(current.ordinal + 1) % ImmersiveView.entries.size])
            }
            override fun toggleDisplay(index: Int) = Unit
            override fun leaveToPlayer() = session.close()
            override fun leave() = session.close()
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    CompositionLocalProvider(
        LocalSettingsStores provides stores,
        LocalAppearanceSettingsStore provides stores.appearance
    ) {
        AuralisDesktopTheme(appearanceSettings = appearance, artworkPalette = palette,
            platformActions = remember { com.auralis.music.ui.components.DesktopPlatformActions {} }) {
            DesktopThemeOverrides {
                Box(Modifier.fillMaxSize().background(Color.Black)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { onImmersiveKey(it, actions) }
                    .focusable()) {
                    ImmersiveScreen(
                        view = preferences.view(display, 0),
                        onView = { preferences.setView(display, it) },
                        playback = playback,
                        lyrics = lyrics,
                        artwork = artwork,
                        palette = palette,
                        backgroundStyle = appearance.playerBackgroundStyle,
                        inlineLyric = true,
                        displays = listOf(display),
                        shownDisplays = listOf(display),
                        thisDisplay = display,
                        onToggleDisplay = {},
                        onLeave = session::close,
                        animateEntry = false
                    )
                }
            }
        }
    }
}
