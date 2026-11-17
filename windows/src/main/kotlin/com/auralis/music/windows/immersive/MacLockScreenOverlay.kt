package com.auralis.music.windows.immersive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.auralis.music.data.datastore.LocalSettingsStores
import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.settings.DesktopDataStores
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.DesktopPlatformActions
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.i18n.desktopText
import com.auralis.music.ui.theme.ArtworkPalette
import com.auralis.music.ui.theme.AuralisDesktopTheme
import com.auralis.music.ui.theme.LocalAppearanceSettingsStore
import com.auralis.music.util.Log
import com.auralis.music.windows.LiquidPopupSurface
import com.auralis.music.windows.LocalPopupGlassSource
import com.auralis.music.windows.PopupGlassSource
import com.auralis.music.windows.recordPopupGlass
import com.auralis.music.windows.rememberPopupGlassSource
import com.auralis.music.windows.settings.DesktopPreferences
import com.auralis.music.windows.settings.DesktopThemeOverrides
import com.auralis.music.windows.settings.rememberArtworkPalette
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.KeyEvent.VK_C
import java.awt.event.KeyEvent.VK_ESCAPE
import java.awt.event.KeyEvent.VK_K
import java.awt.event.KeyEvent.VK_L
import java.awt.event.KeyEvent.VK_SPACE
import javax.swing.JComponent
import javax.swing.RootPaneContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal const val LOCK_SCREEN_WINDOW_TITLE = "Auralis Lock Screen"
internal const val UNLOCK_WITH_PASSWORD_BUTTON_TAG = "lock_screen_unlock_with_password"

internal data class LockScreenOverlayBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

internal data class FullScreenLockClockSpec(
    val topPaddingDp: Int,
    val clockSizeSp: Int,
    val dateSizeSp: Int
)

/**
 * Aligns the full-screen Lock Screen clock with the native macOS Lock Screen clock vertical band
 * (~9.6%..23.8% of display height) so toggling "Unlock with password" keeps the clock on the
 * same level as the macOS clock.
 */
internal fun computeFullScreenLockClockSpec(screenHeightDp: Int): FullScreenLockClockSpec {
    val safeH = screenHeightDp.coerceAtLeast(320)
    val topPaddingDp = (safeH * 0.091f).toInt().coerceAtLeast(56)
    val clockSizeSp = (safeH * 0.138f).toInt().coerceIn(72, 180)
    val dateSizeSp = (clockSizeSp / 6.5f).toInt().coerceIn(16, 26)
    return FullScreenLockClockSpec(
        topPaddingDp = topPaddingDp,
        clockSizeSp = clockSizeSp,
        dateSizeSp = dateSizeSp
    )
}

internal fun fullScreenClockTimeSpacing(clockSizeSp: Int): Dp = -(clockSizeSp * 0.13f).dp

/**
 * Places the collapsed Lock Screen overlay in the middle open band on macOS: clear of the top
 * lock clock and clear of the bottom user avatar / Touch ID / password field.
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
    val maxTop = screenY + (safeH * 0.79f).toInt() - height
    val preferredTop = screenY + (safeH * 0.46f).toInt()
    val y = minOf(preferredTop, maxTop).coerceAtLeast(screenY + (safeH * 0.30f).toInt())
    return LockScreenOverlayBounds(x = x, y = y, width = width, height = height)
}

internal fun computeLockScreenWindowBounds(
    displayX: Int,
    displayY: Int,
    displayWidth: Int,
    displayHeight: Int,
    backdropCollapsed: Boolean
): LockScreenOverlayBounds = if (backdropCollapsed) {
    computeLockScreenOverlayBounds(displayX, displayY, displayWidth, displayHeight)
} else {
    LockScreenOverlayBounds(
        x = displayX,
        y = displayY,
        width = displayWidth.coerceAtLeast(320),
        height = displayHeight.coerceAtLeast(320)
    )
}

/** Places the primary display (where macOS centers the password prompt) first, then the rest left to right. */
internal fun orderLockScreenDisplays(
    displays: List<ImmersiveDisplay>,
    primaryBounds: Rectangle?
): List<ImmersiveDisplay> {
    val leftToRight = displays.sortedBy { it.bounds.x }
    val primary = leftToRight.firstOrNull { primaryBounds != null && it.bounds == primaryBounds }
        ?: leftToRight.firstOrNull { it.bounds.x == 0 && it.bounds.y == 0 }
        ?: return leftToRight
    return listOf(primary) + (leftToRight - primary)
}

internal fun connectedLockScreenDisplays(): List<ImmersiveDisplay> {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val primaryBounds = runCatching { Rectangle(env.defaultScreenDevice.defaultConfiguration.bounds) }.getOrNull()
    val all = env.screenDevices.map { device ->
        val b = device.defaultConfiguration.bounds
        ImmersiveDisplay("${b.x},${b.y}", Rectangle(b))
    }
    return orderLockScreenDisplays(all, primaryBounds)
}

internal fun isLockScreenPreviewRequested(
    args: Array<String>,
    sysProp: String? = System.getProperty("auralis.lockScreenPreview"),
    env: String? = System.getenv("AURALIS_LOCK_SCREEN_PREVIEW")
): Boolean {
    fun isTruthy(value: String?): Boolean =
        value?.trim()?.let { it.equals("true", ignoreCase = true) || it == "1" } == true

    return "--lock-screen-preview" in args || isTruthy(sysProp) || isTruthy(env)
}

internal fun shouldShowMacLockScreenOverlay(
    enabled: Boolean,
    locked: Boolean,
    previewActive: Boolean,
    hasTrack: Boolean
): Boolean = hasTrack && ((enabled && locked) || previewActive)

internal fun handleLockScreenPreviewKey(
    event: KeyEvent,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleCollapsed: () -> Unit
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val code = runCatching { event.key.nativeKeyCode }.getOrDefault(0)
    val cmdOrCtrl = if (isMacLockScreenHost()) event.isMetaPressed || event.isCtrlPressed else event.isCtrlPressed
    return when {
        code == VK_ESCAPE || event.key == Key.Escape -> {
            onDismiss()
            true
        }
        cmdOrCtrl && event.isShiftPressed && code == VK_L -> {
            onDismiss()
            true
        }
        code == VK_SPACE || code == VK_K -> {
            onTogglePlayPause()
            true
        }
        code == VK_C -> {
            onToggleCollapsed()
            true
        }
        else -> false
    }
}

/**
 * Application-scope macOS Lock Screen Now Playing & Live Synced Lyrics overlay.
 * Only activates on macOS hosts when enabled in Settings and a track is loaded while locked,
 * or when unlocked preview mode is active (`⌘⇧L` / `--lock-screen-preview`) for recording.
 * Opens as a full-screen song-synced screensaver on lock/wake and collapses to the middle-band
 * compact overlay when the user clicks "Unlock with password".
 */
@Composable
internal fun MacLockScreenOverlay(
    playback: DesktopPlaybackController,
    lyrics: DesktopLyricsController,
    stores: DesktopDataStores,
    previewActive: Boolean = false,
    onDismissPreview: () -> Unit = {}
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
    val isPreview = previewActive && !locked

    LaunchedEffect(locked) {
        if (locked && previewActive) {
            onDismissPreview()
        }
    }

    var backdropCollapsed by remember { mutableStateOf(false) }
    var touchIdActive by remember { mutableStateOf(false) }
    LaunchedEffect(locked, wakeEpoch, isPreview) {
        if (locked || isPreview) {
            backdropCollapsed = false
            touchIdActive = withContext(Dispatchers.IO) {
                MacLockScreenBridge.isTouchIdAvailableForUnlock()
            }
        } else {
            touchIdActive = false
        }
    }

    if (!shouldShowMacLockScreenOverlay(enabled, locked, previewActive, track != null)) return
    val activeTrack = track ?: return

    val displays = remember(locked, wakeEpoch, isPreview) { connectedLockScreenDisplays() }
    val primaryDisplay = displays.firstOrNull() ?: return
    val primaryHeight = primaryDisplay.bounds.height
    val shownDisplays = if (backdropCollapsed) listOf(primaryDisplay) else displays

    val appearance by stores.appearance.settingsFlow.collectAsState(stores.appearance.getInitialSettings())
    val rawArtwork = activeTrack.thumbnail?.takeIf { it.isNotBlank() }
        ?: activeTrack.id.takeIf { it.length in 8..15 && !it.startsWith("local:") }
            ?.let { "https://i.ytimg.com/vi/$it/hq720.jpg" }
    val artwork = rawArtwork?.let { getHighResArtworkUrl(it) ?: it }
    val palette = rememberArtworkPalette(artwork)
    val platformActions = remember { DesktopPlatformActions { Log.i("MacLockScreenOverlay", it) } }

    shownDisplays.forEach { display ->
        key(display.key, locked) {
            val isPrimary = display == primaryDisplay
            val windowTitle = if (isPrimary) LOCK_SCREEN_WINDOW_TITLE else "$LOCK_SCREEN_WINDOW_TITLE [${display.key}]"
            val collapsedForDisplay = isPrimary && backdropCollapsed
            val bounds = remember(display, collapsedForDisplay) {
                computeLockScreenWindowBounds(
                    displayX = display.bounds.x,
                    displayY = display.bounds.y,
                    displayWidth = display.bounds.width,
                    displayHeight = display.bounds.height,
                    backdropCollapsed = collapsedForDisplay
                )
            }
            val cocoaFrame = remember(bounds, primaryHeight) {
                awtBoundsToCocoaFrame(bounds, primaryHeight)
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
                onCloseRequest = { if (!locked) onDismissPreview() },
                state = windowState,
                title = windowTitle,
                undecorated = true,
                transparent = true,
                resizable = false,
                focusable = !locked,
                alwaysOnTop = true,
                onPreviewKeyEvent = { event ->
                    if (!locked) {
                        handleLockScreenPreviewKey(
                            event = event,
                            onDismiss = onDismissPreview,
                            onTogglePlayPause = playback::togglePlayPause,
                            onToggleCollapsed = { backdropCollapsed = !backdropCollapsed }
                        )
                    } else {
                        false
                    }
                }
            ) {
                DisposableEffect(window, windowTitle) {
                    configureTransparentAwtWindow(window)
                    onDispose {
                        MacLockScreenBridge.clearActiveLockScreenWindow(windowTitle)
                    }
                }

                LaunchedEffect(window, windowTitle, locked, wakeEpoch, cocoaFrame, backdropCollapsed) {
                    configureTransparentAwtWindow(window)
                    if (locked) {
                        MacLockScreenBridge.promoteWindowToLockScreen(windowTitle, cocoaFrame)
                        delay(60L)
                        MacLockScreenBridge.promoteWindowToLockScreen(windowTitle, cocoaFrame)
                        delay(190L)
                        MacLockScreenBridge.promoteWindowToLockScreen(windowTitle, cocoaFrame)
                        delay(450L)
                        MacLockScreenBridge.promoteWindowToLockScreen(windowTitle, cocoaFrame)
                    } else {
                        MacLockScreenBridge.clearActiveLockScreenWindow(windowTitle)
                        MacLockScreenBridge.configurePreviewWindow(
                            windowTitle = windowTitle,
                            cocoaFrame = cocoaFrame,
                            fullScreen = !collapsedForDisplay
                        )
                        if (isPrimary) {
                            window.toFront()
                            window.requestFocus()
                        }
                        delay(60L)
                        MacLockScreenBridge.configurePreviewWindow(
                            windowTitle = windowTitle,
                            cocoaFrame = cocoaFrame,
                            fullScreen = !collapsedForDisplay
                        )
                    }
                }

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
                            when {
                                !isPrimary -> SecondaryLockScreenContent(
                                    artwork = artwork,
                                    palette = palette,
                                    backgroundStyle = appearance.playerBackgroundStyle,
                                    touchIdActive = touchIdActive,
                                    onRevealPassword = { backdropCollapsed = true }
                                )
                                !backdropCollapsed -> FullScreenLockScreenContent(
                                    track = activeTrack,
                                    artwork = artwork,
                                    palette = palette,
                                    backgroundStyle = appearance.playerBackgroundStyle,
                                    playback = playback,
                                    lyrics = lyrics,
                                    compactLyrics = bounds.height < 760,
                                    touchIdActive = touchIdActive,
                                    onRevealPassword = { backdropCollapsed = true }
                                )
                                else -> LockScreenOverlayContent(
                                    track = activeTrack,
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

/**
 * Fixed-height vertical layout for the Lock Screen overlay: always reserves the 3-row lyrics slot
 * above the player pill so the pill stays anchored in place when lyrics are unloaded or unsynced.
 */
@Composable
internal fun LockScreenOverlayLayout(
    compactLyrics: Boolean,
    modifier: Modifier = Modifier,
    lyricsSlot: @Composable (rowHeight: Dp) -> Unit,
    playerPill: @Composable () -> Unit
) {
    val lyricRowHeight = if (compactLyrics) 40.dp else 46.dp
    Box(
        modifier = modifier
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(lyricRowHeight * 3),
                contentAlignment = Alignment.Center
            ) {
                lyricsSlot(lyricRowHeight)
            }
            Spacer(Modifier.height(12.dp))
            playerPill()
        }
    }
}

@Composable
internal fun FullScreenLockScreenScaffold(
    compactLyrics: Boolean,
    background: @Composable BoxScope.(PopupGlassSource) -> Unit,
    clock: @Composable (clockSize: Int, dateSize: Int) -> Unit,
    lyricsSlot: @Composable (rowHeight: Dp) -> Unit,
    playerPill: @Composable () -> Unit,
    onRevealPassword: () -> Unit,
    modifier: Modifier = Modifier,
    touchIdActive: Boolean = false,
    unlockButtonModifier: Modifier = Modifier
) {
    val dockBackdrop = rememberPopupGlassSource()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val screenW = maxWidth.value.toInt().coerceAtLeast(320)
        val screenH = maxHeight.value.toInt().coerceAtLeast(320)
        val clockSpec = computeFullScreenLockClockSpec(screenH)
        val overlayBounds = computeLockScreenOverlayBounds(0, 0, screenW, screenH)
        Box(modifier = Modifier.fillMaxSize()) {
            background(dockBackdrop)
        }
        CompositionLocalProvider(LocalPopupGlassSource provides dockBackdrop) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(
                            top = clockSpec.topPaddingDp.dp,
                            start = 32.dp,
                            end = 32.dp
                        ),
                    contentAlignment = Alignment.TopCenter
                ) {
                    clock(clockSpec.clockSizeSp, clockSpec.dateSizeSp)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = overlayBounds.y.dp)
                        .fillMaxWidth()
                        .height(overlayBounds.height.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LockScreenOverlayLayout(
                        compactLyrics = compactLyrics,
                        lyricsSlot = lyricsSlot,
                        playerPill = playerPill
                    )
                }
                UnlockWithPasswordAction(
                    touchIdActive = touchIdActive,
                    onClick = onRevealPassword,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 32.dp, vertical = 28.dp),
                    buttonModifier = unlockButtonModifier
                )
            }
        }
    }
}

@Composable
private fun FullScreenLockScreenContent(
    track: Track,
    artwork: String?,
    palette: ArtworkPalette,
    backgroundStyle: String,
    playback: DesktopPlaybackController,
    lyrics: DesktopLyricsController,
    compactLyrics: Boolean,
    touchIdActive: Boolean,
    onRevealPassword: () -> Unit
) {
    FullScreenLockScreenScaffold(
        compactLyrics = compactLyrics,
        background = { dockBackdrop ->
            ImmersiveBackdropLayer(
                backgroundStyle = backgroundStyle,
                artwork = artwork,
                palette = palette,
                modifier = Modifier
                    .fillMaxSize()
                    .recordPopupGlass(dockBackdrop)
            )
        },
        clock = { clockSize, dateSize ->
            BigClock(
                fontSize = clockSize,
                dateFontSize = dateSize,
                timeSpacing = fullScreenClockTimeSpacing(clockSize),
                liquidGlass = true
            )
        },
        lyricsSlot = { lyricRowHeight ->
            ClockLyrics(
                playback = playback,
                lyrics = lyrics,
                modifier = Modifier.fillMaxWidth(),
                rowHeight = lyricRowHeight
            )
        },
        playerPill = {
            LiquidPopupSurface(frosted = false) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    NowPlayingRow(
                        track = track,
                        artwork = artwork,
                        playback = playback
                    )
                }
            }
        },
        onRevealPassword = onRevealPassword,
        touchIdActive = touchIdActive
    )
}

@Composable
internal fun SecondaryLockScreenScaffold(
    background: @Composable BoxScope.() -> Unit,
    clock: @Composable (clockSize: Int, dateSize: Int) -> Unit,
    touchIdActive: Boolean,
    onRevealPassword: () -> Unit,
    modifier: Modifier = Modifier,
    unlockButtonModifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val clockSize = (maxHeight.value * 0.14f).toInt().coerceIn(72, 132)
        val dateSize = (clockSize / 5).coerceIn(16, 26)
        background()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
            Box(Modifier.align(Alignment.Center)) {
                clock(clockSize, dateSize)
            }
            UnlockWithPasswordAction(
                touchIdActive = touchIdActive,
                onClick = onRevealPassword,
                modifier = Modifier.align(Alignment.BottomCenter),
                buttonModifier = unlockButtonModifier
            )
        }
    }
}

@Composable
private fun SecondaryLockScreenContent(
    artwork: String?,
    palette: ArtworkPalette,
    backgroundStyle: String,
    touchIdActive: Boolean,
    onRevealPassword: () -> Unit
) {
    val clockBackdrop = rememberPopupGlassSource()
    CompositionLocalProvider(LocalPopupGlassSource provides clockBackdrop) {
        SecondaryLockScreenScaffold(
            background = {
                ImmersiveBackdropLayer(
                    backgroundStyle = backgroundStyle,
                    artwork = artwork,
                    palette = palette,
                    modifier = Modifier.fillMaxSize().recordPopupGlass(clockBackdrop)
                )
            },
            clock = { clockSize, dateSize ->
                BigClock(
                    fontSize = clockSize,
                    dateFontSize = dateSize,
                    liquidGlass = true
                )
            },
            touchIdActive = touchIdActive,
            onRevealPassword = onRevealPassword
        )
    }
}

internal fun lockScreenTouchIdLabel(touchIdActive: Boolean): String? =
    if (touchIdActive) "Touch ID active" else null

@Composable
internal fun UnlockWithPasswordAction(
    touchIdActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val touchIdLabel = lockScreenTouchIdLabel(touchIdActive)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = buttonModifier
                .testTag(UNLOCK_WITH_PASSWORD_BUTTON_TAG)
                .clip(shape)
                .background(Color.White.copy(alpha = if (hovered) 0.22f else 0.14f))
                .border(1.dp, Color.White.copy(alpha = if (hovered) 0.34f else 0.22f), shape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.LockOpen,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = desktopText("Unlock with password"),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        if (touchIdLabel != null) {
            Text(
                text = desktopText(touchIdLabel),
                color = Color.White.copy(alpha = 0.56f),
                fontSize = 11.sp
            )
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
    Box(Modifier.fillMaxSize()) {
        LockScreenOverlayLayout(
            compactLyrics = compactLyrics,
            lyricsSlot = { lyricRowHeight ->
                ClockLyrics(
                    playback = playback,
                    lyrics = lyrics,
                    modifier = Modifier.fillMaxWidth(),
                    rowHeight = lyricRowHeight
                )
            },
            playerPill = {
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
        )
    }
}
