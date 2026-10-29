package com.auralis.music.windows

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import com.auralis.music.ui.components.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class TitleBarActions(
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val back: () -> Unit = {},
    val forward: () -> Unit = {},
    val refresh: () -> Unit = {},
    val accountLabel: String = "",
    val openAccount: () -> Unit = {},
    val openTogether: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val home: () -> Unit = {},
    val canRefresh: Boolean = true,
    val accountAvatar: String? = null,
    val accountMenu: (@Composable () -> Unit)? = null,
    val openImport: () -> Unit = {},
    val openDiscord: () -> Unit = {},
    val openStats: () -> Unit = {},
    /** The search field centred in the bar; null hides it. */
    val search: (@Composable () -> Unit)? = null,
)

/** The window's title bar height; tall enough to grab and drag to the top edge to maximize. */
val TITLE_BAR_HEIGHT = 44.dp

/** Compact desktop chrome. Report frame hit regions in physical client pixels to Windows. */
@Composable
fun WindowTitleBar(
    actions: TitleBarActions, title: String, icon: Painter?, maximized: Boolean,
    minimize: () -> Unit, maximize: () -> Unit, close: () -> Unit,
    captionBounds: (Rect) -> Unit = {}, maximizeBounds: (Rect) -> Unit = {},
    captionHover: CaptionHover = CaptionHover(), searchBounds: (Rect) -> Unit = {}, brandBounds: (Rect) -> Unit = {}, systemDecorated: Boolean = false,
    minimizeBounds: (Rect) -> Unit = {}, closeBounds: (Rect) -> Unit = {}
) {
    // Where the left controls end and the right ones start, so the centred search never covers them.
    val isMac = remember { System.getProperty("os.name").orEmpty().lowercase().contains("mac") }
    var leftEnd by remember { mutableStateOf(0f) }
    var rightStart by remember { mutableStateOf(Float.MAX_VALUE) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(TITLE_BAR_HEIGHT).background(Color(0xFF0D0B0E))) {
        val barWidth = with(density) { maxWidth.toPx() }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.fillMaxHeight().onGloballyPositioned { brandBounds(it.boundsInWindow()) }.padding(start = if (isMac) 78.dp else 10.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The Auralis glyph (the header logo), tinted to the bar's text colour.
                Image(com.auralis.music.ui.components.LocalBrandImages.current.headerLogo(), null, Modifier.size(22.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFFE8E4EB)))
                Text(title, color = Color(0xFFE8E4EB), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            ChromeButton(Icons.Outlined.ChevronLeft, "Back", actions.back, actions.canGoBack, 34)
            ChromeButton(Icons.Outlined.ChevronRight, "Forward", actions.forward, actions.canGoForward, 34)
            ChromeButton(Icons.Outlined.Refresh, "Refresh", actions.refresh, enabled = actions.canRefresh, width = 34,
                modifier = Modifier.onGloballyPositioned { leftEnd = it.boundsInParent().right })
            Spacer(Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { captionBounds(it.boundsInWindow()) })
            Box(Modifier.onGloballyPositioned { rightStart = it.boundsInParent().left }) {
            Row(Modifier.fillMaxHeight().clickable(onClick = actions.openAccount).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.auralis.music.windows.profile.YouTubeAccountAvatar(actions.accountAvatar, Modifier.size(26.dp))
                Text(actions.accountLabel, modifier = Modifier.widthIn(max = 180.dp),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFFE8E4EB))
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = Color(0xFFA6A0AD))
            }
            actions.accountMenu?.invoke()
            }
            ChromeButton(Icons.Outlined.BarChart, "Stats", actions.openStats, width = 34)
            ChromeButton(com.auralis.music.ui.components.LocalBrandImages.current.discord(), "Discord", actions.openDiscord, width = 34)
            Box(Modifier.width(1.dp).height(16.dp).background(Color(0xFF302B34)))
            ChromeButton(Icons.Outlined.Groups, "Listen Together", actions.openTogether, width = 34)
            ChromeButton(Icons.Outlined.Link, "Import playlists", actions.openImport, width = 34)
            ChromeButton(Icons.Outlined.Settings, "Settings", actions.openSettings, width = 34)
            if (!isMac) {
                Box(Modifier.width(1.dp).height(16.dp).background(Color(0xFF302B34)))
                if (!systemDecorated) {
                    Spacer(Modifier.width(6.dp))
                    CaptionButton(CaptionGlyph.Minimize, minimize, captionHover, 8,
                        Modifier.onGloballyPositioned { minimizeBounds(it.boundsInWindow()) })
                    CaptionButton(if (maximized) CaptionGlyph.Restore else CaptionGlyph.Maximize, maximize, captionHover, 9,
                        Modifier.onGloballyPositioned { maximizeBounds(it.boundsInWindow()) })
                    CaptionButton(CaptionGlyph.Close, close, captionHover, 20,
                        Modifier.onGloballyPositioned { closeBounds(it.boundsInWindow()) })
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
        // Centred and up to 620dp when that clears both sides by 12dp; in a narrow window, the free
        // gap between them instead, so it never covers the account or the buttons.
        val desired = minOf(maxWidth * 0.42f, 620.dp).coerceAtLeast(320.dp)
        val centredRoom = with(density) { (2f * minOf(barWidth / 2f - leftEnd, rightStart - barWidth / 2f) - 24.dp.toPx()).toDp() }
        val gapStart = with(density) { (leftEnd + 12.dp.toPx()).toDp() }
        val gap = with(density) { (rightStart - leftEnd - 24.dp.toPx()).toDp() }
        val centred = centredRoom >= minOf(desired, 360.dp)
        val searchWidth = if (centred) minOf(desired, centredRoom) else minOf(desired, gap)
        if (actions.search != null && searchWidth >= 140.dp) Box((if (centred) Modifier.align(Alignment.Center)
            else Modifier.align(Alignment.CenterStart).offset(x = gapStart)).width(searchWidth).height(34.dp)
            .onGloballyPositioned { searchBounds(it.boundsInWindow()) }) { actions.search.invoke() }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(Color(0xFF242026)))
    }
}

private enum class CaptionGlyph { Minimize, Maximize, Restore, Close }

/**
 * Minimize, maximize and close drawn like Windows 11's own caption buttons: 46dp wide, full-height
 * fills (a faint white wash, or #C42B1C with a white glyph for Close) and 1px Segoe Fluent-style glyphs
 * snapped to device pixels. Hover and press come from the native hit test ([hitCode] is the button's
 * HT code), which also gives the screen-corner pixel to Close; Compose hover covers the fallback frame.
 */
@Composable
private fun CaptionButton(glyph: CaptionGlyph, click: () -> Unit, native: CaptionHover, hitCode: Int,
    modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val active = hovered || native.button == hitCode
    val down = (pressed && hovered) || (native.button == hitCode && native.pressed)
    val close = glyph == CaptionGlyph.Close
    val fill by animateColorAsState(when {
        !active -> if (close) Color(0x00C42B1C) else Color(0x00FFFFFF)
        close -> if (down) Color(0xE6C42B1C) else Color(0xFFC42B1C)
        else -> if (down) Color(0x0BFFFFFF) else Color(0x10FFFFFF)
    }, animationSpec = tween(if (active) 60 else 160))
    val ink = when {
        close && active -> Color.White
        down -> Color(0xC8E8E4EB)
        else -> Color(0xFFE8E4EB)
    }
    Box(modifier.width(46.dp).fillMaxHeight().background(fill).hoverable(interactions)
        .clickable(interactionSource = interactions, indication = null, onClick = click)
        .drawBehind { drawCaptionGlyph(glyph, ink) })
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCaptionGlyph(glyph: CaptionGlyph, ink: Color) {
    val stroke = kotlin.math.round(density).coerceAtLeast(1f)
    // Odd stroke widths sit on pixel centres, even ones on pixel edges: crisp either way.
    val half = if (stroke.toInt() % 2 == 1) 0.5f else 0f
    val unit = kotlin.math.round(10.dp.toPx() / 2f) // half the 10dp glyph box, whole pixels
    val cx = kotlin.math.floor(size.width / 2f) + half
    val cy = kotlin.math.floor(size.height / 2f) + half
    val l = cx - unit; val t = cy - unit; val r = cx + unit; val b = cy + unit
    val style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
    val radius = androidx.compose.ui.geometry.CornerRadius(kotlin.math.round(1.dp.toPx()))
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(ink, androidx.compose.ui.geometry.Offset(x1, y1), androidx.compose.ui.geometry.Offset(x2, y2), stroke)
    when (glyph) {
        CaptionGlyph.Minimize -> line(l, cy, r, cy)
        CaptionGlyph.Maximize -> drawRoundRect(ink, androidx.compose.ui.geometry.Offset(l, t),
            androidx.compose.ui.geometry.Size(r - l, b - t), radius, style)
        CaptionGlyph.Restore -> {
            val step = kotlin.math.round(2.dp.toPx())
            drawRoundRect(ink, androidx.compose.ui.geometry.Offset(l, t + step),
                androidx.compose.ui.geometry.Size(r - l - step, b - t - step), radius, style)
            line(l + step, t, r, t)
            line(r, t, r, b - step)
        }
        CaptionGlyph.Close -> {
            line(l, t, r, b)
            line(r, t, l, b)
        }
    }
}

@Composable
private fun ChromeButton(icon: ImageVector, label: String, click: () -> Unit, enabled: Boolean = true,
    width: Int = 46, closeButton: Boolean = false, modifier: Modifier = Modifier) =
    ChromeButton(androidx.compose.ui.graphics.vector.rememberVectorPainter(icon), label, click, enabled, width, closeButton, modifier)

@Composable
private fun ChromeButton(icon: Painter, label: String, click: () -> Unit, enabled: Boolean = true,
    width: Int = 46, closeButton: Boolean = false, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val fill by animateColorAsState(
        if (!hovered || !enabled) Color.Transparent else if (closeButton) Color(0xFFC42B38) else Color(0xFF29252E),
        animationSpec = tween(150))
    Box(modifier.width(width.dp).fillMaxHeight().background(fill).hoverable(interactions)
        .clickable(interactionSource = interactions, indication = null, enabled = enabled, onClick = click),
        contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(if (width == 46) 14.dp else 18.dp),
            tint = if (enabled) Color(0xFFBDB7C5) else Color(0xFF514C56))
    }
}
