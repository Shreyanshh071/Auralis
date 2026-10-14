package com.auralis.music.windows

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*

/** Anchored dropdown with the same live page blur as the app's other glass popups. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun GlassDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val margin = with(density) { 8.dp.roundToPx() }
    val provider = remember(margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                glassDropdownPosition(anchorBounds, windowSize, popupContentSize, margin, layoutDirection)
        }
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)) {
        LiquidPopupSurface(Modifier.width(with(density) { (window.width - margin * 2).coerceAtLeast(1).toDp() }.coerceAtMost(280.dp))
            .heightIn(max = with(density) { (window.height - margin * 2).coerceAtLeast(1).toDp() })) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = Color.White, onSurfaceVariant = Color.White.copy(alpha = .72f))) {
                    ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp)) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = content)
                    }
                }
            }
        }
    }
}

/** Seamless option item resting directly on the liquid glass background. */
@Composable
internal fun GlassDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)

    val bgAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0f
            selected && hovered -> 0.16f
            selected -> 0.12f
            hovered -> 0.08f
            else -> 0f
        },
        animationSpec = tween(120),
        label = "itemBgAlpha"
    )

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = Color.White.copy(alpha = bgAlpha),
        border = null,
        interactionSource = interactionSource,
        modifier = modifier
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .fillMaxWidth()
            .heightIn(min = 36.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (leadingIcon != null) {
                CompositionLocalProvider(
                    LocalContentColor provides if (enabled) Color.White.copy(alpha = .9f) else Color.White.copy(alpha = .38f)
                ) {
                    leadingIcon()
                }
            }
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(
                    LocalContentColor provides if (enabled) Color.White else Color.White.copy(alpha = .38f)
                ) {
                    ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp)) {
                        text()
                    }
                }
            }
            if (trailingIcon != null) {
                CompositionLocalProvider(
                    LocalContentColor provides if (enabled) (if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .85f)) else Color.White.copy(alpha = .38f)
                ) {
                    trailingIcon()
                }
            }
        }
    }
}

/** Section header inside [GlassDropdownMenu]. */
@Composable
internal fun GlassDropdownMenuHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = 0.65f),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

internal fun glassDropdownPosition(anchor: IntRect, window: IntSize, popup: IntSize, margin: Int,
    direction: LayoutDirection = LayoutDirection.Ltr): IntOffset {
    val x = if (direction == LayoutDirection.Ltr) anchor.right - popup.width else anchor.left
    val y = if (anchor.bottom + popup.height + margin <= window.height) anchor.bottom else anchor.top - popup.height
    return IntOffset(x.coerceIn(margin, (window.width - popup.width - margin).coerceAtLeast(margin)),
        y.coerceIn(margin, (window.height - popup.height - margin).coerceAtLeast(margin)))
}
