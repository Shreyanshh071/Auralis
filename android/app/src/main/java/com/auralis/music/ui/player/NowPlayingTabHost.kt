package com.auralis.music.ui.player

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import com.auralis.music.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

/** Keeps prepared tabs composed. Hidden tabs sit below the viewport and skip drawing.
 * Every tab is placed in both layout passes so nested lookahead layouts stay valid.
 */
@Composable
internal fun NowPlayingTabHost(
    currentTab: NowPlayingTab,
    modifier: Modifier = Modifier,
    content: @Composable (NowPlayingTab) -> Unit
) {
    val reducedMotion = LocalReducedMotion.current
    val built = remember { mutableStateListOf(currentTab) }
    LaunchedEffect(Unit) {
        delay(900)
        for (tab in NowPlayingTab.entries) {
            if (tab !in built) built.add(tab)
            delay(350)
        }
    }
    var previous by remember { mutableStateOf(currentTab) }
    var direction by remember { mutableIntStateOf(1) }
    SideEffect {
        if (currentTab !in built) built.add(currentTab)
        if (previous != currentTab) {
            direction = if (currentTab.ordinal > previous.ordinal) 1 else -1
            previous = currentTab
        }
    }
    Box(modifier) {
        for (tab in NowPlayingTab.entries) {
            if (tab !in built && tab != currentTab) continue
            key(tab) {
                val selected = tab == currentTab
                val transition = updateTransition(selected, label = "playerTab")
                val onScreen = { selected || transition.currentState || transition.isRunning }
                val slide by transition.animateFloat(transitionSpec = {
                    when {
                        reducedMotion -> snap()
                        targetState -> tween(420, easing = CubicBezierEasing(.05f, .7f, .1f, 1f))
                        else -> tween(220, easing = FastOutLinearInEasing)
                    }
                }, label = "slide") { if (it) 1f else 0f }
                val opacity by transition.animateFloat(transitionSpec = {
                    when {
                        reducedMotion -> snap()
                        targetState -> tween(260, delayMillis = 60, easing = FastOutSlowInEasing)
                        else -> tween(160, easing = FastOutLinearInEasing)
                    }
                }, label = "fade") { if (it) 1f else 0f }
                val scale by transition.animateFloat(transitionSpec = {
                    if (reducedMotion) snap() else tween(420, easing = CubicBezierEasing(.05f, .7f, .1f, 1f))
                }, label = "scale") { if (it) 1f else .96f }
                Box(Modifier.fillMaxSize()
                    .zIndex(if (selected) 1f else 0f)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.placeRelative(0, if (onScreen()) 0 else HiddenLayerOffsetPx)
                        }
                    }
                    .drawWithContent { if (onScreen()) drawContent() }
                    .graphicsLayer {
                        alpha = opacity
                        translationX = if (selected) (1f - slide) * direction * size.width / 4f
                            else (1f - slide) * -direction * size.width / 8f
                        scaleX = if (selected) scale else 1f
                        scaleY = if (selected) scale else 1f
                    }
                ) { content(tab) }
            }
        }
    }
}

