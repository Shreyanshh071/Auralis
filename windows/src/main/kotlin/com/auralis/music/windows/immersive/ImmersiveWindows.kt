package com.auralis.music.windows.immersive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.auralis.music.data.datastore.LocalSettingsStores
import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.desktop.settings.DesktopDataStores
import com.auralis.music.ui.theme.AuralisDesktopTheme
import com.auralis.music.ui.theme.LocalAppearanceSettingsStore
import com.auralis.music.util.Log
import com.auralis.music.windows.settings.DesktopThemeOverrides
import com.auralis.music.windows.settings.rememberArtworkPalette
import kotlinx.coroutines.delay

private const val TAG = "Immersive"
private const val DISPLAY_CHECK_MS = 2_000L
private const val KEEP_AWAKE_MS = 20_000L

private fun DesktopPlaybackController.isPlayingNow(): Boolean =
    connectRemote.value?.playing ?: (state.value == VlcAudioPlayer.State.PLAYING)

/** Starts immersive mode after the chosen idle time, ends an idle session on any input, and keeps the screen on. */
@Composable
internal fun ImmersiveIdleWatcher(session: ImmersiveSession, playback: DesktopPlaybackController) {
    LaunchedEffect(session, playback) {
        val preferences = ImmersivePreferences()
        var lastKeptAwake = 0L
        while (true) {
            val now = System.currentTimeMillis()
            val idle = WindowsPresence.idleMillis()
            val trigger = session.trigger
            if (trigger != null && playback.isPlayingNow() && now - lastKeptAwake >= KEEP_AWAKE_MS) {
                WindowsPresence.keepDisplayOn()
                lastKeptAwake = now
            }
            when (trigger) {
                ImmersiveTrigger.IDLE -> {
                    if (shouldLeaveIdle(now - session.openedAtMs, idle)) session.close()
                    delay(200)
                }
                ImmersiveTrigger.MANUAL -> delay(1_000)
                null -> {
                    val minutes = preferences.idleMinutes
                    // The notification state is only asked for once the rest says go.
                    if (shouldStartIdle(minutes, playback.isPlayingNow(), idle, true) &&
                        WindowsPresence.acceptsInterruptions()) session.open(ImmersiveTrigger.IDLE)
                    delay(2_000)
                }
            }
        }
    }
}

/** One borderless window per chosen display. Lives at application scope, so it opens with the main window hidden. */
@Composable
internal fun ImmersiveWindows(
    session: ImmersiveSession,
    playback: DesktopPlaybackController,
    lyrics: DesktopLyricsController,
    stores: DesktopDataStores,
    appBounds: () -> java.awt.Rectangle?,
    icon: Painter?,
    onLeaveToPlayer: () -> Unit
) {
    val trigger = session.trigger ?: return
    val appearance by stores.appearance.settingsFlow.collectAsState(stores.appearance.getInitialSettings())
    val queue by playback.queue.collectAsState()
    val current = queue.current
    val artwork = current?.thumbnail?.takeIf { it.isNotBlank() }
        ?: current?.id?.takeIf { it.length in 8..15 && !it.startsWith("local:") }?.let { "https://i.ytimg.com/vi/$it/hq720.jpg" }
    val palette = rememberArtworkPalette(artwork)
    val platformActions = remember { com.auralis.music.ui.components.DesktopPlatformActions { Log.i(TAG, it) } }
    val preferences = remember { ImmersivePreferences() }
    var displays by remember { mutableStateOf(connectedDisplays(appBounds())) }
    // A display plugged in or out mid-session: its window comes or goes.
    LaunchedEffect(Unit) {
        while (true) {
            delay(DISPLAY_CHECK_MS)
            val connected = connectedDisplays(appBounds())
            if (connected != displays) displays = connected
        }
    }
    val shown = preferences.shown(displays)
    val firstShown = shown.firstOrNull()
    shown.forEach { display ->
        key(display.key) {
            val state = remember {
                WindowState(WindowPlacement.Floating, false, WindowPosition.Absolute(display.bounds.x.dp, display.bounds.y.dp),
                    DpSize(display.bounds.width.dp, display.bounds.height.dp))
            }
            val index = displays.indexOf(display)
            val view = preferences.view(display, index)
            val actions = remember(playback, display) {
                object : ImmersiveKeyActions {
                    override fun togglePlayPause() = playback.togglePlayPause()
                    override fun next() = playback.next()
                    override fun previous() = playback.previous()
                    override fun seekBy(deltaMs: Long) = playback.seekBy(deltaMs)
                    override fun toggleMute() = playback.toggleMute()
                    override fun nextView() = preferences.setView(display,
                        ImmersiveView.entries[(preferences.view(display, displays.indexOf(display)).ordinal + 1) % ImmersiveView.entries.size])
                    override fun toggleDisplay(index: Int) {
                        val target = displays.getOrNull(index) ?: return
                        val on = target in preferences.shown(displays)
                        // The last display stays on; leaving is what ends it.
                        if (on && preferences.shown(displays).size <= 1) return
                        preferences.setOff(target, on)
                    }
                    override fun leaveToPlayer() = onLeaveToPlayer()
                    override fun leave() = session.close()
                }
            }
            val currentActions by rememberUpdatedState(actions)
            Window(
                onCloseRequest = session::close,
                state = state,
                // Not "Auralis": a second launch finds the main window by that title.
                title = "Auralis Immersive",
                icon = icon,
                undecorated = true,
                transparent = false,
                resizable = false,
                focusable = trigger == ImmersiveTrigger.MANUAL,
                alwaysOnTop = true,
                onPreviewKeyEvent = { event -> onImmersiveKey(event, currentActions) }
            ) {
                DisposableEffect(window) {
                    java.awt.Color.BLACK.let { window.background = it; window.contentPane.background = it }
                    val inputRestore = com.auralis.music.windows.installWindowInputRestore(window)
                    onDispose {
                        inputRestore.close()
                    }
                }
                LaunchedEffect(trigger, firstShown) {
                    if (trigger == ImmersiveTrigger.MANUAL && display == firstShown) {
                        window.toFront()
                        window.requestFocus()
                    }
                }
                LaunchedEffect(stores) {
                    stores.privacy.settingsFlow.collect { privacy ->
                        runCatching {
                            val affinity = com.sun.jna.NativeLibrary.getInstance("user32").getFunction("SetWindowDisplayAffinity")
                            affinity.invokeInt(arrayOf(com.sun.jna.Pointer(window.windowHandle), if (privacy.disableScreenshot) 0x11 else 0))
                        }.onFailure { Log.w(TAG, "Couldn't apply screenshot privacy: ${it.javaClass.simpleName}") }
                    }
                }
                CompositionLocalProvider(
                    LocalSettingsStores provides stores,
                    LocalAppearanceSettingsStore provides stores.appearance
                ) {
                    AuralisDesktopTheme(appearanceSettings = appearance, artworkPalette = palette, platformActions = platformActions) {
                        DesktopThemeOverrides {
                            ImmersiveScreen(
                                view = view,
                                onView = { preferences.setView(display, it) },
                                playback = playback,
                                lyrics = lyrics,
                                artwork = artwork,
                                palette = palette,
                                backgroundStyle = appearance.playerBackgroundStyle,
                                inlineLyric = shown.size == 1,
                                displays = displays,
                                shownDisplays = shown,
                                thisDisplay = display,
                                onToggleDisplay = actions::toggleDisplay,
                                onLeave = session::close
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun onImmersiveKey(event: KeyEvent, actions: ImmersiveKeyActions): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val code = runCatching { event.key.nativeKeyCode }.getOrDefault(0)
    return handleImmersiveKey(code, event.isCtrlPressed, event.isShiftPressed, actions)
}
