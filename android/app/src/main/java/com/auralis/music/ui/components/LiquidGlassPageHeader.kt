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
import androidx.compose.animation.core.animateDpAsState
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

/** The page already clears the system status bar in AuralisApp. */
@Composable
internal fun LiquidGlassPageHeader(
    glass: LiquidGlassContext,
    onLogoClick: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenListenTogether: () -> Unit,
    onOpenStats: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val foreground = MaterialTheme.dynamicOnBackground
    val capsuleMotion = rememberFloatingIconMotion()
    val reducedMotion = LocalReducedMotion.current
    val statsWidth by animateDpAsState(
        targetValue = if (onOpenStats != null) 46.dp else 0.dp,
        animationSpec = if (reducedMotion) snap() else tween(300, easing = FastOutSlowInEasing),
        label = "headerStatsWidth"
    )
    fun openAction(index: Int, action: () -> Unit) {
        // Each 44dp target is separated by 2dp inside 4dp capsule padding.
        val capsuleWidth = 144f + statsWidth.value
        val start = if (index == -1) 4f else 4f + statsWidth.value + index * 46f
        val anchor = (start + 22f) / capsuleWidth
        capsuleMotion.onClick(anchor, action)
    }
    Row(
        modifier = modifier.fillMaxWidth().height(LiquidGlassHeaderHeight).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier.size(44.dp).liquidGlass(glass, CircleShape)
                .border(0.75.dp, foreground.copy(alpha = 0.18f), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
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
        Row(
            modifier = capsuleMotion.surfaceModifier.width(144.dp + statsWidth).height(52.dp).liquidGlass(glass, CircleShape)
                .border(0.75.dp, foreground.copy(alpha = 0.18f), CircleShape),
            verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            modifier = capsuleMotion.contentModifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
                modifier = Modifier.width(statsWidth).height(44.dp).clipToBounds()
                    .graphicsLayer { alpha = (statsWidth.value / 46f).coerceIn(0f, 1f) }
            ) {
                GlassHeaderAction(Icons.Default.Equalizer, str(R.string.stats), capsuleMotion) {
                    onOpenStats?.let { openAction(-1, it) }
                }
            }
            GlassHeaderAction(Icons.Default.History, str(R.string.history), capsuleMotion) { openAction(0, onOpenHistory) }
            Spacer(Modifier.width(2.dp))
            GlassHeaderAction(Icons.Default.Groups, str(R.string.listen_together), capsuleMotion) { openAction(1, onOpenListenTogether) }
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = { openAction(2, onOpenProfile) },
                interactionSource = capsuleMotion.interactionSource,
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
private fun GlassHeaderAction(icon: ImageVector, label: String, motion: FloatingIconMotion, onClick: () -> Unit) {
    val foreground = MaterialTheme.dynamicOnBackground
    IconButton(
        onClick = onClick,
        interactionSource = motion.interactionSource,
        modifier = Modifier.size(44.dp)
    ) {
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
