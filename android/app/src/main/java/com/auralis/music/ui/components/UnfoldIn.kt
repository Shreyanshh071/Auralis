package com.auralis.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.auralis.music.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

/** Only content that appears this soon after a screen opens plays the entrance (data loads async). */
const val UNFOLD_WINDOW_MS = 2_500L
private const val UNFOLD_STAGGER_MS = 55L

/**
 * Screen entrance used by Home and Stats: each block grows down from its top edge, one after
 * another, pushing everything below it down the page, with a soft spring settle and a slight
 * downward drift. Runs once, only during the first moments after the screen opened
 * ([openedAtMs], from `SystemClock.uptimeMillis()`), never when content scrolls into view later.
 * [canPlay] is read once, when the block first appears: a list passes "not scrolled yet", so
 * rows that scroll into view inside the window stay put instead of dropping in.
 */
@Composable
fun UnfoldIn(order: Int, openedAtMs: Long, canPlay: () -> Boolean = { true }, content: @Composable () -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    val play = remember {
        !reducedMotion && android.os.SystemClock.uptimeMillis() - openedAtMs < UNFOLD_WINDOW_MS && canPlay()
    }
    val visible = remember { MutableTransitionState(!play) }
    LaunchedEffect(Unit) {
        if (!visible.targetState) {
            delay(order.coerceIn(0, 12) * UNFOLD_STAGGER_MS)
            visible.targetState = true
        }
    }
    AnimatedVisibility(
        visibleState = visible,
        enter = expandVertically(
            expandFrom = Alignment.Top,
            animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold)
        ) + slideInVertically(
            initialOffsetY = { -it / 10 },
            animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
        ) + fadeIn(tween(260)),
        exit = ExitTransition.None
    ) {
        // AnimatedVisibility stacks its children like a Box. A Home section is a heading plus its
        // content emitted side by side, so without this Column they drew on top of each other.
        Column { content() }
    }
}
