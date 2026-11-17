package com.auralis.music.windows

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import com.auralis.music.util.KeyValueStore
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.util.desktopAppDataDir
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.awt.Toolkit
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Usable work-area bounds of a monitor in AWT logical coordinates.
 */
internal data class ScreenBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

/**
 * Last known desktop window placement alongside its most recent floating bounds.
 *
 * Floating bounds are preserved separately from [placement] so exiting `Maximized` or `Fullscreen`
 * (or closing while in either state) restores both the window mode and its prior custom shape.
 */
internal data class PersistedWindowState(
    val placement: WindowPlacement = WindowPlacement.Floating,
    val width: Float = DEFAULT_WINDOW_WIDTH,
    val height: Float = DEFAULT_WINDOW_HEIGHT,
    val x: Float? = null,
    val y: Float? = null
) {
    companion object {
        const val DEFAULT_WINDOW_WIDTH = 1280f
        const val DEFAULT_WINDOW_HEIGHT = 820f
        const val MIN_WINDOW_WIDTH = 900f
        const val MIN_WINDOW_HEIGHT = 600f
        const val MAC_MIN_WINDOW_WIDTH = 600f
        const val MAC_MIN_WINDOW_HEIGHT = 380f
    }
}

internal fun minWindowWidth(isMac: Boolean): Float =
    if (isMac) PersistedWindowState.MAC_MIN_WINDOW_WIDTH else PersistedWindowState.MIN_WINDOW_WIDTH

internal fun minWindowHeight(isMac: Boolean): Float =
    if (isMac) PersistedWindowState.MAC_MIN_WINDOW_HEIGHT else PersistedWindowState.MIN_WINDOW_HEIGHT

internal data class RestoredWindowState(
    val placement: WindowPlacement,
    val size: DpSize,
    val position: WindowPosition
)

internal object WindowStateCodec {
    private const val KEY_PLACEMENT = "placement"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    fun encode(state: PersistedWindowState): Map<String, String> = buildMap {
        put(
            KEY_PLACEMENT,
            when (state.placement) {
                WindowPlacement.Floating -> "floating"
                WindowPlacement.Maximized -> "maximized"
                WindowPlacement.Fullscreen -> "fullscreen"
            }
        )
        put(KEY_WIDTH, state.width.toString())
        put(KEY_HEIGHT, state.height.toString())
        if (state.x != null && state.y != null && state.x.isFinite() && state.y.isFinite()) {
            put(KEY_X, state.x.toString())
            put(KEY_Y, state.y.toString())
        }
    }

    fun decode(store: KeyValueStore): PersistedWindowState {
        val placement = when (store.getString(KEY_PLACEMENT, "floating")?.trim()?.lowercase()) {
            "maximized" -> WindowPlacement.Maximized
            "fullscreen" -> WindowPlacement.Fullscreen
            else -> WindowPlacement.Floating
        }
        val width = store.getString(KEY_WIDTH, "")
            ?.toFloatOrNull()
            ?.takeIf { it.isFinite() && it > 0f }
            ?: PersistedWindowState.DEFAULT_WINDOW_WIDTH
        val height = store.getString(KEY_HEIGHT, "")
            ?.toFloatOrNull()
            ?.takeIf { it.isFinite() && it > 0f }
            ?: PersistedWindowState.DEFAULT_WINDOW_HEIGHT
        val x = store.getString(KEY_X, "")?.toFloatOrNull()?.takeIf { it.isFinite() }
        val y = store.getString(KEY_Y, "")?.toFloatOrNull()?.takeIf { it.isFinite() }
        val hasCoords = x != null && y != null
        return PersistedWindowState(
            placement = placement,
            width = width,
            height = height,
            x = if (hasCoords) x else null,
            y = if (hasCoords) y else null
        )
    }
}

/**
 * Validates and clamps [saved] window geometry against currently attached [screens].
 *
 * When a saved position lies entirely off-screen (e.g., an external display was unplugged) or no
 * coordinates were saved yet, falls back to centering on the primary screen while keeping the
 * saved dimensions (clamped to screen bounds) and [PersistedWindowState.placement].
 */
internal fun resolveRestoredWindowState(
    saved: PersistedWindowState,
    screens: List<ScreenBounds>,
    minWidth: Float = PersistedWindowState.MIN_WINDOW_WIDTH,
    minHeight: Float = PersistedWindowState.MIN_WINDOW_HEIGHT
): RestoredWindowState {
    val rawWidth = saved.width.takeIf { it.isFinite() && it > 0f } ?: PersistedWindowState.DEFAULT_WINDOW_WIDTH
    val rawHeight = saved.height.takeIf { it.isFinite() && it > 0f } ?: PersistedWindowState.DEFAULT_WINDOW_HEIGHT

    if (screens.isEmpty()) {
        val w = rawWidth.coerceAtLeast(minWidth)
        val h = rawHeight.coerceAtLeast(minHeight)
        val position = if (saved.x != null && saved.y != null && saved.x.isFinite() && saved.y.isFinite()) {
            WindowPosition.Absolute(saved.x.dp, saved.y.dp)
        } else {
            WindowPosition.Aligned(Alignment.Center)
        }
        return RestoredWindowState(saved.placement, DpSize(w.dp, h.dp), position)
    }

    val savedX = saved.x?.takeIf { it.isFinite() }
    val savedY = saved.y?.takeIf { it.isFinite() }
    val matchingScreen = if (savedX != null && savedY != null) {
        val prelimW = rawWidth.coerceAtLeast(minWidth)
        val prelimH = rawHeight.coerceAtLeast(minHeight)
        screens
            .map { screen -> screen to intersectionArea(savedX, savedY, prelimW, prelimH, screen) }
            .filter { (_, area) -> area > 0f }
            .maxByOrNull { (_, area) -> area }
            ?.first
    } else {
        null
    }

    val targetScreen = matchingScreen ?: screens.first()
    val effectiveMinW = min(minWidth, targetScreen.width).coerceAtLeast(1f)
    val effectiveMinH = min(minHeight, targetScreen.height).coerceAtLeast(1f)
    val clampedW = rawWidth.coerceIn(effectiveMinW, targetScreen.width.coerceAtLeast(effectiveMinW))
    val clampedH = rawHeight.coerceIn(effectiveMinH, targetScreen.height.coerceAtLeast(effectiveMinH))

    val position = if (matchingScreen != null && savedX != null && savedY != null) {
        val maxX = max(matchingScreen.x, matchingScreen.x + matchingScreen.width - clampedW)
        val maxY = max(matchingScreen.y, matchingScreen.y + matchingScreen.height - clampedH)
        val clampedX = savedX.coerceIn(matchingScreen.x, maxX)
        val clampedY = savedY.coerceIn(matchingScreen.y, maxY)
        WindowPosition.Absolute(clampedX.dp, clampedY.dp)
    } else {
        WindowPosition.Aligned(Alignment.Center)
    }

    return RestoredWindowState(
        placement = saved.placement,
        size = DpSize(clampedW.dp, clampedH.dp),
        position = position
    )
}

/**
 * Pure transition function for updating [PersistedWindowState] from live window state.
 *
 * Ignores transient states (minimized, hidden to tray/dock, initial macOS fullscreen restoration,
 * Windows borderless Now Playing/immersive fullscreen, and macOS Now Playing automatic maximize)
 * while still honoring an explicit user switch into native macOS Fullscreen during Now Playing.
 */
internal fun nextPersistedWindowState(
    current: PersistedWindowState,
    placement: WindowPlacement,
    size: DpSize,
    position: WindowPosition,
    isMinimized: Boolean,
    visible: Boolean,
    playerFullscreen: Boolean,
    embeddedImmersive: Boolean,
    isMac: Boolean,
    macPrePlayerPlacement: WindowPlacement? = null,
    restoringInitialFullscreen: Boolean = false,
    commitFloatingBounds: Boolean = true
): PersistedWindowState {
    if (!visible || isMinimized || restoringInitialFullscreen) {
        return current
    }
    if (playerFullscreen || embeddedImmersive) {
        return if (isMac) {
            if (placement == WindowPlacement.Fullscreen) {
                current.copy(placement = WindowPlacement.Fullscreen)
            } else {
                current.copy(placement = macPrePlayerPlacement ?: current.placement)
            }
        } else {
            current
        }
    }
    if (placement != WindowPlacement.Floating) {
        return current.copy(placement = placement)
    }
    if (!commitFloatingBounds) {
        return current.copy(placement = WindowPlacement.Floating)
    }
    val width = size.width.value
    val height = size.height.value
    val minWidth = minWindowWidth(isMac)
    val minHeight = minWindowHeight(isMac)
    if (!width.isFinite() || !height.isFinite() ||
        width < minWidth ||
        height < minHeight
    ) {
        return current.copy(placement = WindowPlacement.Floating)
    }
    val absPos = position as? WindowPosition.Absolute
    val nextX = absPos?.x?.value?.takeIf { it.isFinite() } ?: current.x
    val nextY = absPos?.y?.value?.takeIf { it.isFinite() } ?: current.y
    return PersistedWindowState(
        placement = WindowPlacement.Floating,
        width = width,
        height = height,
        x = nextX,
        y = nextY
    )
}

internal fun currentUsableScreenBounds(): List<ScreenBounds> {
    return try {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val toolkit = runCatching { Toolkit.getDefaultToolkit() }.getOrNull()
        val defaultDevice = runCatching { ge.defaultScreenDevice }.getOrNull()
        val orderedDevices = buildList {
            if (defaultDevice != null) add(defaultDevice)
            ge.screenDevices.forEach { if (it != defaultDevice) add(it) }
        }
        orderedDevices.mapNotNull { device ->
            val gc = device.defaultConfiguration ?: return@mapNotNull null
            val bounds = gc.bounds
            val insets = runCatching { toolkit?.getScreenInsets(gc) }.getOrNull()
            val left = insets?.left ?: 0
            val top = insets?.top ?: 0
            val right = insets?.right ?: 0
            val bottom = insets?.bottom ?: 0
            val w = (bounds.width - left - right).coerceAtLeast(1)
            val h = (bounds.height - top - bottom).coerceAtLeast(1)
            ScreenBounds(
                x = (bounds.x + left).toFloat(),
                y = (bounds.y + top).toFloat(),
                width = w.toFloat(),
                height = h.toFloat()
            )
        }
    } catch (_: HeadlessException) {
        emptyList()
    }
}

private fun intersectionArea(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    screen: ScreenBounds
): Float {
    val overlapX = max(0f, min(x + w, screen.x + screen.width) - max(x, screen.x))
    val overlapY = max(0f, min(y + h, screen.y + screen.height) - max(y, screen.y))
    return overlapX * overlapY
}

internal object WindowStatePreferences {
    private val store: KeyValueStore by lazy {
        PropertiesFileKeyValueStore(
            File(desktopAppDataDir(), "desktop-window.properties"),
            synchronous = true
        )
    }

    fun loadRaw(): PersistedWindowState = WindowStateCodec.decode(store)

    fun load(screens: List<ScreenBounds> = currentUsableScreenBounds()): RestoredWindowState =
        resolveRestoredWindowState(loadRaw(), screens)

    fun save(state: PersistedWindowState) {
        store.clear()
        store.putStrings(WindowStateCodec.encode(state))
    }
}
