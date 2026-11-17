package com.auralis.music.windows.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.auralis.music.util.Log
import kotlinx.coroutines.launch

/** Material's emphasized curve: a quick start that settles gently into place. */
private val ExpandEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val EXPAND_MS = 480
private const val COLLAPSE_MS = 380
private const val CLOSE_FADE_MS = 120
private const val TAG = "PlayerExpand"

/**
 * Warm-up ends once no frame has taken [WARM_SLOW_NANOS] or more for [WARM_CALM_NANOS] (and at
 * least [WARM_MIN_FRAMES] frames have drawn), or after [WARM_MAX_MS].
 */
private const val WARM_SLOW_NANOS = 25_000_000L
private const val WARM_CALM_NANOS = 90_000_000L
private const val WARM_MIN_FRAMES = 4
private const val WARM_MAX_MS = 800L
/** How long to wait for the window to reach fullscreen size before building the player anyway. */
private const val RESIZE_WAIT_MS = 300L

/**
 * Drawn at this opacity during warm-up: invisible on screen, but every part of the player is
 * really rendered, so the GPU's first-use work happens before the animation starts.
 */
private const val WARM_ALPHA = 2f / 255f

/** With `trace-frames` in the data folder, each open and close logs its frame times. */
private val traceFrames by lazy {
    runCatching { java.io.File(com.auralis.music.util.desktopAppDataDir(), "trace-frames").isFile }.getOrDefault(false)
}

/**
 * Hosts the full player. Opened from the right sidebar's expand button, the player grows out of
 * the sidebar's bounds (rounded corners squaring off as it fills the window) and shrinks back into
 * it on close; opened any other way it fades.
 *
 * Every frame of the animation redraws only a mask (and, opening, a fading panel) over the player's
 * cached layer: nothing is re-measured or recomposed while it runs. The player is laid out once at
 * full size and the mask reveals it, so its content never reflows mid-animation.
 *
 * Before the clock starts, the player is composed and drawn invisibly until frames come back
 * quickly. Its first frames are the expensive ones (composition, then the GPU's first pass over
 * its shaders and high-resolution artwork, ~100ms frames on a first open), and they used to land
 * mid-animation as visible stalls.
 *
 * [onFullscreen] asks for borderless fullscreen before the growth starts and for the windowed size
 * after the shrink ends, so the window resize never lands in the animation: the caller holds the
 * page beneath at its windowed size, and the resize itself happens during the invisible warm-up.
 * [onCovered] reports when the player hides the page completely (true) and when it starts leaving.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class) // LocalWindowInfo.containerSize
@Composable
internal fun PlayerExpandOverlay(
    visible: Boolean,
    fromSidebar: Boolean,
    sidebarBounds: State<Rect?>,
    palette: com.auralis.music.ui.theme.ArtworkPalette,
    /** Whether the window is already borderless fullscreen (then there is no resize to wait for). */
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    /** Holds (true) or releases the page beneath at its windowed size around the window resize. */
    onHoldPage: (Boolean) -> Unit,
    /** True from the first animation frame of an open until a close begins: the sidebar is hidden. */
    onCovering: (Boolean) -> Unit,
    onCovered: (Boolean) -> Unit,
    onHidden: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val currentFullscreen by rememberUpdatedState(fullscreen)
    val progress = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(visible) }
    var reveal by remember { mutableStateOf(false) }
    var covered by remember { mutableStateOf(false) }
    var warming by remember { mutableStateOf(false) }
    // Closing keeps the player's content in view as it shrinks, then crossfades into the sidebar.
    var closing by remember { mutableStateOf(false) }
    val closeFade = remember { Animatable(1f) }
    val currentFromSidebar by rememberUpdatedState(fromSidebar)
    val currentOnFullscreen by rememberUpdatedState(onFullscreen)
    val currentOnHoldPage by rememberUpdatedState(onHoldPage)
    val currentOnCovering by rememberUpdatedState(onCovering)
    val currentOnCovered by rememberUpdatedState(onCovered)
    val currentOnHidden by rememberUpdatedState(onHidden)
    LaunchedEffect(visible) {
        val t0 = System.nanoTime()
        if (visible) {
            closing = false
            closeFade.snapTo(1f)
            if (progress.value == 0f) reveal = currentFromSidebar && sidebarBounds.value != null
            var warmFrames = 0
            // The page beneath is held at its windowed size before the window goes borderless,
            // so the resize moves nothing on screen.
            currentOnHoldPage(true)
            if (!shown) {
                // The player is built only once the resize has landed: built at the windowed
                // size, its effect buffers were made twice, and the second set's GPU stalls
                // spilled into the animation.
                val windowed = windowInfo.containerSize
                val wasFullscreen = currentFullscreen
                currentOnFullscreen(true)
                if (!wasFullscreen) {
                    while (windowInfo.containerSize == windowed &&
                        (System.nanoTime() - t0) / 1_000_000 < RESIZE_WAIT_MS) withFrameNanos { }
                    withFrameNanos { }
                }
                shown = true
                warming = true
                try {
                    // Warm until frames have stayed quick for a stretch: a first open's GPU work
                    // comes as 50-100ms stalls a few quick frames apart.
                    val warmStart = withFrameNanos { it }
                    var last = warmStart
                    var calmSince = warmStart
                    while (true) {
                        val now = withFrameNanos { it }
                        warmFrames++
                        if (now - last >= WARM_SLOW_NANOS) calmSince = now
                        last = now
                        if (warmFrames >= WARM_MIN_FRAMES && now - calmSince >= WARM_CALM_NANOS) break
                        if ((now - warmStart) / 1_000_000 >= WARM_MAX_MS) break
                    }
                } finally { warming = false }
            } else currentOnFullscreen(true)
            // From the first animation frame the sidebar is under the player (the growth starts
            // at its bounds), so what the sidebar swaps now is never seen.
            currentOnCovering(true)
            val animStart = System.nanoTime()
            progress.animateTo(1f, if (reveal) tween(EXPAND_MS, easing = ExpandEasing) else tween(180))
            val animEnd = System.nanoTime()
            covered = true
            currentOnCovered(true)
            if (traceFrames) {
                kotlinx.coroutines.delay(900)
                Log.i(TAG, "[open ${if (reveal) "grow" else "fade"}] warm-up $warmFrames frames, animation " +
                    "${(animStart - t0) / 1_000_000}..${(animEnd - t0) / 1_000_000}ms, then covered. " +
                    "Root draws: ${com.auralis.music.windows.dev.FrameLog.since(t0)}")
            }
        } else {
            covered = false
            currentOnCovered(false)
            // The shrink covers the sidebar until its last moment, hiding the swap back.
            currentOnCovering(false)
            val wasShown = shown
            if (shown) {
                reveal = reveal && sidebarBounds.value != null
                val animStart = System.nanoTime()
                if (reveal) {
                    closing = true
                    kotlinx.coroutines.coroutineScope {
                        // The last moments crossfade into the sidebar beneath. By then the player
                        // is sidebar-sized, so fading its layer costs next to nothing.
                        launch {
                            kotlinx.coroutines.delay((COLLAPSE_MS - CLOSE_FADE_MS).toLong())
                            closeFade.animateTo(0f, tween(CLOSE_FADE_MS))
                        }
                        progress.animateTo(0f, tween(COLLAPSE_MS, easing = ExpandEasing))
                    }
                } else progress.animateTo(0f, tween(160))
                if (traceFrames) Log.i(TAG, "[close ${if (reveal) "shrink" else "fade"}] animation " +
                    "${(animStart - t0) / 1_000_000}..${(System.nanoTime() - t0) / 1_000_000}ms. " +
                    "Root draws: ${com.auralis.music.windows.dev.FrameLog.since(t0)}")
            }
            // The shrink ran at fullscreen size; the window restores once the player is gone. The
            // page stays held until the window has actually shrunk back: released together with
            // the request, it was laid out once at the still-fullscreen height (a one-frame jump).
            val fullscreenSize = windowInfo.containerSize
            val restoreStart = System.nanoTime()
            currentOnFullscreen(false)
            if (wasShown) {
                while (windowInfo.containerSize == fullscreenSize &&
                    (System.nanoTime() - restoreStart) / 1_000_000 < RESIZE_WAIT_MS) withFrameNanos { }
                withFrameNanos { }
            }
            currentOnHoldPage(false)
            shown = false
            currentOnHidden()
        }
    }
    if (!shown) return
    val origin = remember { mutableStateOf(Offset.Zero) }
    val cover = remember(palette) {
        Brush.verticalGradient(listOf(palette.primary, palette.secondary, palette.tertiary).map { it.copy(alpha = 1f) })
    }
    val outside = remember { Path().apply { fillType = PathFillType.EvenOdd } }
    // While growing or shrinking, the reveal is a mask, not a clip. The player's blur and glass
    // effects render through offscreen buffers sized to the clip they're drawn in: a clip that grew
    // every frame made each of them reallocate a bigger buffer every few frames (~10 GPU stalls of
    // 50-100ms on a launch's first open). Drawn into one full-size offscreen layer and masked
    // afterwards, every buffer keeps one size for the whole animation.
    // The warm-up draws the mask and panel too (invisibly): their first GPU setup was a 245ms
    // stall on the first animation frame when it happened there.
    fun masking() = reveal && progress.value < 1f
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin.value = it.positionInRoot() }
            .graphicsLayer {
                clip = false
                // Offscreen for the whole time a grown player is up, not just while masking:
                // switching back to direct drawing as the growth landed rebuilt every effect buffer
                // once more (a 60ms frame), and again when the shrink began.
                compositingStrategy = if (reveal) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                alpha = when {
                    warming -> WARM_ALPHA
                    !reveal -> progress.value
                    closing -> closeFade.value
                    else -> 1f
                }
            }
            .drawWithContent {
                drawContent()
                if (!masking()) return@drawWithContent
                val p = progress.value
                // An opaque panel in the artwork's colours over the player at the start of the
                // growth, thinning out as it opens: the sidebar reads as lifting and opening up.
                // Never on close, where the player's own content stays in view.
                if (!closing) {
                    val coverAlpha = 1f - (p / .35f).coerceIn(0f, 1f)
                    if (coverAlpha > 0f) drawRect(cover, alpha = coverAlpha)
                }
                val full = Rect(Offset.Zero, size)
                val start = sidebarBounds.value?.translate(-origin.value.x, -origin.value.y) ?: full
                outside.rewind()
                outside.fillType = PathFillType.EvenOdd
                outside.addRect(full)
                outside.addRoundRect(RoundRect(lerp(start, full, p), CornerRadius(12.dp.toPx() * (1f - p))))
                drawPath(outside, Color.Black, blendMode = BlendMode.Clear)
            }
    ) {
        // Its own layer: the mask and cover above redraw each frame without re-recording the player.
        Box(Modifier.fillMaxSize().graphicsLayer { }) { content() }
    }
}
