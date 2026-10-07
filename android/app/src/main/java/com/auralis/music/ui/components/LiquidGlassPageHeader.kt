package com.auralis.music.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.BiasAlignment
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clipToBounds
import com.auralis.music.ui.theme.LocalReducedMotion
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.ui.glass.LiquidGlassContext
import com.auralis.music.ui.glass.LocalLiquidGlass
import com.auralis.music.ui.glass.liquidGlass
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.dynamicOnBackground
import com.kyant.backdrop.Backdrop

internal val LiquidGlassHeaderHeight = 64.dp

/** Page-owned scroll and backdrop, consumed by the persistent navigation header. */
class LiquidGlassHeaderPageState {
    var glass by mutableStateOf<LiquidGlassContext?>(null)
    var scrollToTop: () -> Unit = {}
}

/** Record only the scrolling content, so the header never samples its own glass. */
@Composable
internal fun rememberPageHeaderGlass(backdrop: Backdrop): LiquidGlassContext? {
    val appGlass = LocalLiquidGlass.current
    return remember(appGlass, backdrop) {
        appGlass?.let { LiquidGlassContext(backdrop, it.isDark, it.collapse) }
    }
}

/**
 * The page already clears the system status bar in AuralisApp.
 *
 * [collapsed] is true while a page opened from here (Profile, History, Listen Together, Stats)
 * covers the tab. The capsule then closes the way BitChord's action pill does: the logo and the
 * icons leave in the same frame and the empty glass springs shut, narrowing and thinning into its
 * right edge. On return it springs open from that edge with its icons already in place, revealed
 * as the glass grows. Driven by the page state rather than the tap, so a back gesture or a
 * programmatic close runs the same motion.
 */
@Composable
internal fun LiquidGlassPageHeader(
    glass: LiquidGlassContext,
    onLogoClick: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenListenTogether: () -> Unit,
    onOpenStats: (() -> Unit)? = null,
    collapsed: Boolean = false,
    modifier: Modifier = Modifier
) {
    val foreground = MaterialTheme.dynamicOnBackground
    val reducedMotion = LocalReducedMotion.current
    val statsWidth by animateDpAsState(
        targetValue = if (onOpenStats != null) 46.dp else 0.dp,
        animationSpec = if (reducedMotion) snap() else tween(300, easing = FastOutSlowInEasing),
        label = "headerStatsWidth"
    )

    // A tap closes the capsule first and opens the page once it is nearly shut. The page covers
    // the header, so opening it at once hid the whole close behind it and only an empty pill
    // flashed before the page appeared.
    var closingForTap by remember { mutableStateOf(false) }
    // Where the capsule closes to (and later grows back from), as fractions of its own box:
    // the tapped button's side. Profile closes into the top-right corner, Stats into the
    // top-left, History and Listen Together into their own icon, both ends arriving together.
    // (The capsule's centre sits between those two icons, so closing to it read as closing
    // into whichever was nearer.)
    var anchorX by remember { mutableFloatStateOf(0.5f) }
    var anchorY by remember { mutableFloatStateOf(0.5f) }
    val shut = collapsed || closingForTap
    val open = remember { Animatable(if (shut) 0f else 1f) }
    LaunchedEffect(shut, reducedMotion) {
        val target = if (shut) 0f else 1f
        if (reducedMotion) open.snapTo(target)
        else open.animateTo(
            target,
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = if (shut) Spring.StiffnessMedium else Spring.StiffnessMediumLow
            )
        )
    }
    val isCollapsed by rememberUpdatedState(shut)
    val scope = rememberCoroutineScope()
    // Centre of the icon at [slot] (0 History, 1 Listen Together) as a fraction of the capsule:
    // 4dp padding, the stats slot, then 44dp buttons 2dp apart.
    fun iconCentre(slot: Int): Float {
        val stats = statsWidth.value
        return (4f + stats + slot * 46f + 22f) / (144f + stats)
    }
    fun openPage(towardX: Float, towardY: Float, action: () -> Unit) {
        if (shut) return
        if (reducedMotion) { action(); return }
        anchorX = towardX
        anchorY = towardY
        closingForTap = true
        scope.launch {
            try {
                // Bounded wait: the page must open even if the frame clock stalls.
                withTimeoutOrNull(400L) { snapshotFlow { open.value }.first { it <= 0.15f } }
                action()
            } finally {
                closingForTap = false
            }
        }
    }

    val pressSource = remember { MutableInteractionSource() }
    val pressed by pressSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reducedMotion) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 900f),
        label = "headerCapsulePress"
    )

    Row(
        modifier = modifier.fillMaxWidth().height(LiquidGlassHeaderHeight).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier
                // Swapped, not faded: the page that opens brings its own title to this corner.
                .graphicsLayer { alpha = if (isCollapsed) 0f else 1f }
                .size(44.dp).liquidGlass(glass, CircleShape)
                .border(0.75.dp, foreground.copy(alpha = 0.18f), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    enabled = !shut,
                    onClick = onLogoClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_auralis_header_logo),
                contentDescription = str(R.string.auralis_logo),
                colorFilter = ColorFilter.tint(foreground),
                modifier = Modifier.size(26.dp)
            )
        }
        Box(
            modifier = Modifier
                // Size follows the spring at layout time, so the glass stays a true capsule while it
                // narrows and thins. The slot keeps its full size; the glass sits at the anchor
                // within it, so with a centred anchor both ends close in at the same rate.
                .layout { measurable, constraints ->
                    val f = open.value
                    val fullPx = (144.dp + statsWidth).roundToPx()
                    val fullH = 52.dp.roundToPx()
                    val w = (fullPx * f).roundToInt().coerceIn(1, fullPx)
                    val h = (fullH * (0.25f + 0.75f * f)).roundToInt().coerceIn(1, fullH)
                    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h))
                    layout(fullPx, fullH) {
                        placeable.place(((fullPx - w) * anchorX).roundToInt(), ((fullH - h) * anchorY).roundToInt())
                    }
                }
                .graphicsLayer {
                    // The last sliver fades rather than ending as a dot.
                    alpha = (open.value * 4f).coerceIn(0f, 1f)
                    scaleX = pressScale
                    scaleY = pressScale
                    transformOrigin = TransformOrigin(anchorX, anchorY)
                }
                .liquidGlass(glass, CircleShape)
                .border(0.75.dp, foreground.copy(alpha = 0.18f), CircleShape)
                .clip(CircleShape),
            contentAlignment = Alignment.CenterEnd
        ) {
          Row(
            modifier = Modifier
                // Icons stay pinned to the anchor side, so the growing glass reveals them from there.
                .wrapContentSize(BiasAlignment(anchorX * 2f - 1f, anchorY * 2f - 1f), unbounded = true)
                .graphicsLayer { alpha = if (isCollapsed) 0f else 1f }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
                modifier = Modifier.width(statsWidth).height(44.dp).clipToBounds()
                    .graphicsLayer { alpha = (statsWidth.value / 46f).coerceIn(0f, 1f) }
            ) {
                GlassHeaderAction(Icons.Default.Equalizer, str(R.string.stats), pressSource, !shut) {
                    onOpenStats?.let { openPage(0f, 0f, it) }
                }
            }
            GlassHeaderAction(Icons.Default.History, str(R.string.history), pressSource, !shut) { openPage(iconCentre(0), 0.5f, onOpenHistory) }
            Spacer(Modifier.width(2.dp))
            GlassHeaderAction(Icons.Default.Groups, str(R.string.listen_together), pressSource, !shut) { openPage(iconCentre(1), 0.5f, onOpenListenTogether) }
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = { openPage(1f, 0f, onOpenProfile) },
                enabled = !shut,
                interactionSource = pressSource,
                modifier = Modifier.size(44.dp)
            ) {
                Box(
                    modifier = Modifier.size(28.dp)
                        .background(if (glass.isDark) Color(0xFF25272A) else Color(0xFFE1E3E6), CircleShape)
                        .border(0.75.dp, foreground.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Person, str(R.string.profile), tint = foreground.copy(alpha = 0.65f), modifier = Modifier.size(20.dp))
                }
            }
          }
        }
    }
}

@Composable
private fun GlassHeaderAction(
    icon: ImageVector,
    label: String,
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val foreground = MaterialTheme.dynamicOnBackground
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = Modifier.size(44.dp)
    ) {
        // Explicit tint: a disabled IconButton would otherwise grey the glyph while it fades out.
        Icon(icon, label, tint = foreground.copy(alpha = 0.90f), modifier = Modifier.size(23.dp))
    }
}

@Composable
internal fun LiquidGlassPageTitle(
    title: String,
    modifier: Modifier = Modifier,
    scrollOffsetPx: () -> Int = { 0 }
) {
    Text(
        text = title,
        color = MaterialTheme.dynamicOnBackground,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.8).sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = modifier.fillMaxWidth().padding(top = 72.dp, bottom = 20.dp)
            .graphicsLayer {
                val offset = scrollOffsetPx().toFloat().coerceAtLeast(0f)
                // Move with the list; fade before the title reaches the floating controls.
                val fadeStart = 8.dp.toPx()
                val fadeDistance = 32.dp.toPx()
                alpha = (1f - (offset - fadeStart).coerceAtLeast(0f) / fadeDistance).coerceIn(0f, 1f)
            }
            .semantics { heading() }
    )
}
