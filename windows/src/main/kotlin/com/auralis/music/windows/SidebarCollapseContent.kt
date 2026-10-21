package com.auralis.music.windows

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/** The viewport animates; its expanded and compact contents never reflow to intermediate widths. */
@Composable
internal fun SidebarCollapseContent(collapsed: Boolean, expandedWidth: Dp, compactWidth: Dp = 64.dp,
    content: @Composable (Boolean) -> Unit) {
    AnimatedContent(collapsed, modifier = Modifier.fillMaxSize().clipToBounds(),
        transitionSpec = {
            (fadeIn(tween(160, delayMillis = 40)) togetherWith fadeOut(tween(120))).using(null)
        }, label = "sidebarContent") { compact ->
        val contentWidth = if (compact) compactWidth else expandedWidth
        Box(Modifier.layout { measurable, constraints ->
            val width = contentWidth.roundToPx().coerceAtLeast(1)
            val child = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(constraints.constrainWidth(width), child.height) { child.placeRelative(0, 0) }
        }.fillMaxHeight()) { content(compact) }
    }
}
