package com.auralis.music.ui.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

// ============================================================================
// PUSH / POP FOR PAGES OPENED INSIDE A SCREEN
// ============================================================================
//
// Profile -> Settings, Settings -> a section, Appearance -> Theme & colours.
// These pages are drawn over their parent rather than routed through a NavHost,
// so they used to swap in and out on a single frame. This applies VIVI's NavHost
// push/pop to them with the same tokens the Explore/Library detail stack uses:
// the page slides in from +1/8 width while the parent drifts to -1/8, both
// fading over 200ms; back reverses it.
// ============================================================================

private const val PushDurationMillis = 200

/** 0 -> 1 while a page is pushed over its parent. Read it only from [auralisPushParent]. */
@Composable
fun rememberPushProgress(pushed: Boolean): State<Float> = animateFloatAsState(
    targetValue = if (pushed) 1f else 0f,
    animationSpec = if (LocalReducedMotion.current) snap() else tween(PushDurationMillis, easing = FastOutSlowInEasing),
    label = "pushProgress"
)

/**
 * Parent side of the push: drifts left and fades out while a page covers it.
 * Draw-phase only, so it never recomposes the parent. Place it after the parent's
 * background so the backdrop stays opaque and nothing beneath shows through.
 */
fun Modifier.auralisPushParent(progress: State<Float>): Modifier = graphicsLayer {
    val p = progress.value
    translationX = -size.width / 8f * p
    alpha = 1f - p
}

/**
 * Page side: hosts the pushed page ([page] null = none) with VIVI's forward-enter
 * and backward-exit. Page -> page (About -> Updater) is a forward push.
 */
@Composable
fun <T : Any> AuralisPushedPage(
    page: T?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    val forwardEnter = auralisDetailForwardEnter()
    val forwardExit = auralisDetailForwardExit()
    val backwardExit = auralisDetailBackwardExit()
    AnimatedContent(
        targetState = page,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            when {
                initialState == null -> forwardEnter togetherWith ExitTransition.None
                targetState == null -> EnterTransition.None togetherWith backwardExit
                else -> forwardEnter togetherWith forwardExit
            }.using(null)
        },
        label = "pushedPage"
    ) { current ->
        if (current != null) {
            // Being hit-testable stops taps on the page's empty areas from reaching the
            // parent underneath; events are observed, never consumed, so the page's own
            // controls behave exactly as before.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent() }
                    }
            ) {
                content(current)
            }
        }
    }
}
