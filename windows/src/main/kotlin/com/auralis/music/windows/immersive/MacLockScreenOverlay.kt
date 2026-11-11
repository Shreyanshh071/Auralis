package com.auralis.music.windows.immersive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.auralis.music.data.datastore.LocalSettingsStores
import com.auralis.music.domain.model.Track
import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.settings.DesktopDataStores
import com.auralis.music.ui.components.DesktopPlatformActions
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.theme.AuralisDesktopTheme
import com.auralis.music.ui.theme.LocalAppearanceSettingsStore
import com.auralis.music.util.Log
import com.auralis.music.windows.settings.DesktopPreferences
import com.auralis.music.windows.settings.DesktopThemeOverrides
import com.auralis.music.windows.settings.rememberArtworkPalette
import javax.swing.JComponent
import javax.swing.RootPaneContainer
import kotlinx.coroutines.delay

internal const val LOCK_SCREEN_WINDOW_TITLE = "Auralis Lock Screen"

internal data class LockScreenOverlayBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/**
 * Places the Lock Screen overlay in the middle open band on macOS: clear of the top lock clock
 * and clear of the bottom user avatar / Touch ID / password field.
 */
internal fun computeLockScreenOverlayBounds(
    screenX: Int,
    screenY: Int,
    screenWidth: Int,
    screenHeight: Int
): LockScreenOverlayBounds {
    val safeW = screenWidth.coerceAtLeast(320)
    val safeH = screenHeight.coerceAtLeast(320)
    val width = 580.coerceAtMost((safeW - 32).coerceAtLeast(320))
    val height = minOf(340, (safeH * 0.38f).toInt().coerceAtLeast(260)).coerceAtMost(safeH)
    val x = screenX + (safeW - width) / 2
    val maxTop = screenY + (safeH * 0.76f).toInt() - height
    val preferredTop = screenY + (safeH * 0.41f).toInt()
    val y = minOf(preferredTop, maxTop).coerceAtLeast(screenY + (safeH * 0.30f).toInt())
    return LockScreenOverlayBounds(x = x, y = y, width = width, height = height)
}

/**
 * Application-scope macOS Lock Screen Now Playing & Live Synced Lyrics overlay.
 * Only activates on macOS hosts when enabled in Settings and a track is loaded while locked.
 */
@Composable
internal fun MacLockScreenOverlay(
    playback: DesktopPlaybackController,
    lyrics: DesktopLyricsController,
    stores: DesktopDataStores
) {
    if (!isMacLockScreenHost()) return

    LaunchedEffect(Unit) {
        MacLockScreenBridge.startObserving()
    }

    val revision by DesktopPreferences.store.changes.collectAsState()
    val enabled = remember(revision) { ImmersivePreferences().macLockScreenOverlay }
    val locked by MacLockScreenBridge.isLocked.collectAsState()
    val wakeEpoch by MacLockScreenBridge.wakeEpoch.collectAsState()
    val queue by playback.queue.collectAsState()
    val track = queue.current

    if (!enabled || !locked || track == null) return

    val display = remember(locked, wakeEpoch) { connectedDisplays(null).firstOrNull() } ?: return
    val bounds = remember(display) {
        computeLockScreenOverlayBounds(
            screenX = display.bounds.x,
            screenY = display.bounds.y,
            screenWidth = display.bounds.width,
            screenHeight = display.bounds.height
        )
    }
    val windowState = rememberWindowState(
        position = WindowPosition.Absolute(bounds.x.dp, bounds.y.dp),
        size = DpSize(bounds.width.dp, bounds.height.dp)
    )
    LaunchedEffect(bounds) {
        windowState.position = WindowPosition.Absolute(bounds.x.dp, bounds.y.dp)
        windowState.size = DpSize(bounds.width.dp, bounds.height.dp)
    }

    Window(
        onCloseRequest = {},
        state = windowState,
        title = LOCK_SCREEN_WINDOW_TITLE,
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = false,
        alwaysOnTop = true
    ) {
        DisposableEffect(window) {
            configureTransparentAwtWindow(window)
            onDispose {
                MacLockScreenBridge.clearActiveLockScreenWindow()
            }
        }

        LaunchedEffect(window, locked, wakeEpoch) {
            configureTransparentAwtWindow(window)
            MacLockScreenBridge.promoteWindowToLockScreen(LOCK_SCREEN_WINDOW_TITLE)
            delay(60L)
            MacLockScreenBridge.promoteWindowToLockScreen(LOCK_SCREEN_WINDOW_TITLE)
            delay(190L)
            MacLockScreenBridge.promoteWindowToLockScreen(LOCK_SCREEN_WINDOW_TITLE)
            delay(450L)
            MacLockScreenBridge.promoteWindowToLockScreen(LOCK_SCREEN_WINDOW_TITLE)
        }

        val appearance by stores.appearance.settingsFlow.collectAsState(stores.appearance.getInitialSettings())
        val rawArtwork = track.thumbnail
        val artwork = rawArtwork?.let { getHighResArtworkUrl(it) ?: it }
        val palette = rememberArtworkPalette(artwork)
        val platformActions = remember { DesktopPlatformActions { Log.i("MacLockScreenOverlay", it) } }

        CompositionLocalProvider(
            LocalSettingsStores provides stores,
            LocalAppearanceSettingsStore provides stores.appearance
        ) {
            AuralisDesktopTheme(
                appearanceSettings = appearance,
                artworkPalette = palette,
                platformActions = platformActions
            ) {
                DesktopThemeOverrides {
                    LockScreenOverlayContent(
                        track = track,
                        artwork = artwork,
                        playback = playback,
                        lyrics = lyrics,
                        compactLyrics = bounds.height < 300
                    )
                }
            }
        }
    }
}

private fun configureTransparentAwtWindow(window: java.awt.Window) {
    val clear = java.awt.Color(0, 0, 0, 0)
    window.background = clear
    (window as? RootPaneContainer)?.apply {
        contentPane?.background = clear
        (contentPane as? JComponent)?.isOpaque = false
        rootPane?.apply {
            isOpaque = false
            background = clear
            putClientProperty("Window.shadow", java.lang.Boolean.FALSE)
        }
    }
}

@Composable
private fun LockScreenOverlayContent(
    track: Track,
    artwork: String?,
    playback: DesktopPlaybackController,
    lyrics: DesktopLyricsController,
    compactLyrics: Boolean
) {
    val cardShape = RoundedCornerShape(22.dp)
    val lyricRowHeight = if (compactLyrics) 40.dp else 46.dp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ClockLyrics(
                playback = playback,
                lyrics = lyrics,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                rowHeight = lyricRowHeight
            )
            Box(
                modifier = Modifier
                    .clip(cardShape)
                    .background(Color(0xFF0C0D12).copy(alpha = 0.78f))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                Color.Transparent
                            )
                        )
                    )
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.10f),
                                Color.Transparent
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.24f),
                                Color.White.copy(alpha = 0.08f)
                            )
                        ),
                        shape = cardShape
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                NowPlayingRow(
                    track = track,
                    artwork = artwork,
                    playback = playback
                )
            }
        }
    }
}
