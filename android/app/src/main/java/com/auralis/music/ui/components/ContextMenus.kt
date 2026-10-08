package com.auralis.music.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.auralis.music.ui.glass.LocalLiquidGlass
import com.auralis.music.ui.player.ImmersiveColorField
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// Cover-tinted option sheets
// ─────────────────────────────────────────────────────────────────────────────

/** The sheet's floor under the colour field; also what shows before the field has loaded. */
internal val CoverSheetFloor = Color(0xFF121212)

/**
 * Behind a song's / playlist's option sheet: the immersive player's colour field, built from
 * the item's own cover, so every ⋮ menu takes on the colours of what it's about.
 */
@Composable
internal fun CoverSheetBackground(artworkUrl: String?) {
    ImmersiveColorField(artworkUrl = artworkUrl, seamPx = { 0f })
}

/** The colour field is always dark enough for white text, whatever the app theme is. */
@Composable
internal fun CoverSheetTheme(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = scheme.copy(
            surface = CoverSheetFloor,
            onSurface = Color.White,
            onSurfaceVariant = Color.White.copy(alpha = 0.7f),
            onBackground = Color.White
        ),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Liquid-glass long-press menu
// ─────────────────────────────────────────────────────────────────────────────

/** What a long-pressed item leaves behind for its menu: where it was and what it looked like. */
@Stable
class ContextMenuAnchor internal constructor() {
    internal var coordinates: LayoutCoordinates? = null
    internal var hostView: View? = null
    internal var onClick: (() -> Unit)? = null
    /** Bounds on screen, frozen when the press arms the anchor. */
    var boundsOnScreen by mutableStateOf(Rect.Zero)
        internal set
    var snapshot by mutableStateOf<ImageBitmap?>(null)
        internal set
    internal var capturing by mutableStateOf(false)
}

/**
 * The last item held down long enough to open a menu. The screen's own long-click handler opens
 * the option menu on the same tick; the menu then claims this anchor and, in liquid glass mode,
 * pops up beside the item instead of sliding a sheet up. A stale anchor (held without opening a
 * menu) expires so a later ⋮ tap never inherits it.
 */
internal object ContextMenuAnchors {
    private var armed: ContextMenuAnchor? = null
    private var armedAt = 0L

    fun arm(anchor: ContextMenuAnchor) {
        armed = anchor
        armedAt = SystemClock.uptimeMillis()
    }

    fun take(): ContextMenuAnchor? {
        val anchor = armed?.takeIf { SystemClock.uptimeMillis() - armedAt < ANCHOR_TTL_MS }
        armed = null
        return anchor
    }
}

private const val ANCHOR_TTL_MS = 600L

/** Claims the anchor of the long-press that opened this menu, in liquid glass mode only. */
@Composable
internal fun rememberContextMenuAnchor(): ContextMenuAnchor? {
    val glass = LocalLiquidGlass.current
    return remember { if (glass != null) ContextMenuAnchors.take() else null }
}

/**
 * Put on an item whose long-press opens an option menu (before its clickable). In liquid glass
 * mode a long hold records where the item is and snapshots it, so the menu can lift it out of
 * the blurred page. [onClick] is the item's normal tap action, reused by the lifted preview.
 * Outside glass mode it does nothing. The press is only watched, never consumed.
 */
fun Modifier.contextMenuAnchor(onClick: (() -> Unit)? = null): Modifier = composed {
    LocalLiquidGlass.current ?: return@composed this
    val view = LocalView.current
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val anchor = remember { ContextMenuAnchor() }
    anchor.hostView = view
    anchor.onClick = onClick
    this
        .onGloballyPositioned { anchor.coordinates = it }
        .drawWithContent {
            // Drawn through a layer only for the frame being captured, so scrolling pays nothing.
            if (anchor.capturing) {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            } else drawContent()
        }
        .pointerInput(anchor) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    }
                }
                if (released != null) return@awaitEachGesture
                val coords = anchor.coordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                val origin = IntArray(2).also { view.getLocationOnScreen(it) }
                anchor.boundsOnScreen = coords.boundsInWindow().translate(Offset(origin[0].toFloat(), origin[1].toFloat()))
                anchor.snapshot = null
                ContextMenuAnchors.arm(anchor)
                scope.launch {
                    anchor.capturing = true
                    // The next frame records the item into the layer; read it the frame after.
                    withFrameNanos { }
                    withFrameNanos { }
                    anchor.snapshot = runCatching { layer.toImageBitmap() }.getOrNull()
                    anchor.capturing = false
                }
            }
        }
}

/** One row of the glass menu. [dismisses] = false when the row opens something else instead. */
data class GlassMenuItem(
    val label: String,
    val icon: ImageVector,
    val tint: Color? = null,
    val enabled: Boolean = true,
    val dismisses: Boolean = true,
    val onClick: () -> Unit
)

/**
 * Liquid glass long-press menu: the page blurs and dims, the held item lifts out of it in place,
 * and a small glass menu pops from its edge (below it, or above when there isn't room).
 */
@Composable
fun GlassContextMenu(
    anchor: ContextMenuAnchor,
    items: List<GlassMenuItem>,
    onDismiss: () -> Unit
) {
    val glass = LocalLiquidGlass.current
    val isDark = glass?.isDark ?: true
    val pageView = LocalView.current.rootView
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun close(after: () -> Unit = {}) {
        if (closing) return
        closing = true
        scope.launch {
            progress.animateTo(0f, tween(170))
            after()
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))
    }

    // Blur the page itself (the activity window) behind the menu's window.
    if (blurSupported) {
        val maxBlurPx = with(density) { 26.dp.toPx() }
        LaunchedEffect(pageView) {
            snapshotFlow { progress.value.coerceIn(0f, 1f) }.collect { p ->
                val radius = maxBlurPx * p
                pageView.setRenderEffect(
                    if (radius < 0.5f) null
                    else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                )
            }
        }
        DisposableEffect(pageView) {
            onDispose { pageView.setRenderEffect(null) }
        }
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setWindowAnimations(0)
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
            }
            onDispose { }
        }

        var rootOnScreen by remember { mutableStateOf<Offset?>(null) }
        // Where the menu grows from: the side touching the lifted item. Set during layout.
        var menuOrigin by remember { mutableStateOf(TransformOrigin(0f, 0f)) }
        // Animation is read only in layer/draw lambdas, so opening never recomposes the menu.
        val p = { progress.value.coerceIn(0f, 1.2f) }
        val scrimAlpha = if (blurSupported) 0.32f else 0.6f

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    val origin = IntArray(2).also { dialogView.getLocationOnScreen(it) }
                    rootOnScreen = coords.positionInWindow() + Offset(origin[0].toFloat(), origin[1].toFloat())
                }
                .drawBehind { drawRect(Color.Black.copy(alpha = scrimAlpha * p().coerceIn(0f, 1f))) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { close() }
        ) {
            val root = rootOnScreen ?: return@Box
            val itemRect = anchor.boundsOnScreen.translate(-root)
            val marginPx = with(density) { 12.dp.toPx() }
            val gapPx = with(density) { 10.dp.toPx() }
            val cardPadPx = with(density) { 6.dp.toPx() }
            val menuShape = RoundedCornerShape(22.dp)

            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    // The lifted item: its own snapshot on a soft glass card.
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                val lift = 1f + 0.035f * p()
                                scaleX = lift
                                scaleY = lift
                            }
                            .clip(RoundedCornerShape(18.dp))
                            .drawBehind {
                                val a = p().coerceIn(0f, 1f)
                                drawRect(if (isDark) Color(0xFF1C1C1E).copy(alpha = 0.55f * a) else Color.White.copy(alpha = 0.6f * a))
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { close(after = { anchor.onClick?.invoke() }) }
                    ) {
                        val shot = anchor.snapshot
                        Canvas(Modifier.fillMaxSize()) {
                            if (shot != null) {
                                drawImage(
                                    image = shot,
                                    dstOffset = androidx.compose.ui.unit.IntOffset(cardPadPx.roundToInt(), cardPadPx.roundToInt()),
                                    dstSize = androidx.compose.ui.unit.IntSize(
                                        itemRect.width.roundToInt().coerceAtLeast(1),
                                        itemRect.height.roundToInt().coerceAtLeast(1)
                                    )
                                )
                            }
                        }
                    }
                    // The menu itself.
                    Column(
                        modifier = Modifier
                            .width(236.dp)
                            .graphicsLayer {
                                val s = 0.82f + 0.18f * p()
                                scaleX = s
                                scaleY = s
                                alpha = p().coerceIn(0f, 1f)
                                transformOrigin = menuOrigin
                            }
                            .clip(menuShape)
                            .background(
                                if (isDark) Color(0xFF1E1E21).copy(alpha = if (blurSupported) 0.74f else 0.95f)
                                else Color(0xFFF7F7F9).copy(alpha = if (blurSupported) 0.80f else 0.96f)
                            )
                            .border(
                                0.75.dp,
                                Brush.verticalGradient(
                                    if (isDark) listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.05f))
                                    else listOf(Color.White.copy(alpha = 0.95f), Color.Black.copy(alpha = 0.06f))
                                ),
                                menuShape
                            )
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.Top
                    ) {
                        val textColor = if (isDark) Color.White else Color(0xFF111111)
                        items.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (item.enabled) 1f else 0.45f)
                                    .clickable(enabled = item.enabled && !closing) {
                                        if (item.dismisses) close(after = item.onClick) else item.onClick()
                                    }
                                    .padding(horizontal = 18.dp, vertical = 12.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.label,
                                    color = item.tint ?: textColor,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    tint = item.tint ?: textColor,
                                    modifier = Modifier.padding(start = 12.dp).size(20.dp)
                                )
                            }
                        }
                    }
                }
            ) { measurables, constraints ->
                val cardW = (itemRect.width + cardPadPx * 2).roundToInt().coerceAtLeast(1)
                val cardH = (itemRect.height + cardPadPx * 2).roundToInt().coerceAtLeast(1)
                val card = measurables[0].measure(Constraints.fixed(cardW, cardH))
                val below = constraints.maxHeight - (itemRect.bottom + cardPadPx + gapPx) - marginPx * 3
                val above = itemRect.top - cardPadPx - gapPx - marginPx * 3
                val placeBelow = below >= above
                val room = (if (placeBelow) below else above).roundToInt().coerceAtLeast(0)
                val menu = measurables[1].measure(
                    constraints.copy(minWidth = 0, minHeight = 0, maxHeight = room.coerceAtLeast(1))
                )
                val menuX = itemRect.left.coerceIn(marginPx, (constraints.maxWidth - menu.width - marginPx).coerceAtLeast(marginPx))
                val menuY = if (placeBelow) itemRect.bottom + cardPadPx + gapPx
                    else itemRect.top - cardPadPx - gapPx - menu.height
                menuOrigin = TransformOrigin(
                    pivotFractionX = ((itemRect.left + itemRect.width / 2f - menuX) / menu.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                    pivotFractionY = if (placeBelow) 0f else 1f
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    card.place((itemRect.left - cardPadPx).roundToInt(), (itemRect.top - cardPadPx).roundToInt())
                    menu.place(menuX.roundToInt(), menuY.roundToInt())
                }
            }
        }
    }
}
