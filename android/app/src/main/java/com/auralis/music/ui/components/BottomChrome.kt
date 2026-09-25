package com.auralis.music.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// ============================================================================
// BOTTOM CHROME INSET (dock + mini player)
// ============================================================================
//
// Scrolling pages used to guess how much room the dock and mini player take
// (`if (track != null) 240.dp else 140.dp` and similar), which left large empty
// gaps and jumped when the mini player appeared or closed. AuralisApp now
// publishes the real height, with the mini player's share gliding between
// 0 and its height, and pages clear exactly that plus a small gap.
// ============================================================================

/** Height the floating chrome covers above the system navigation bar. */
@Stable
class BottomChrome(private val aboveNavigationBar: () -> Dp) {
    /** Snapshot read: evaluate during layout (see [bottomChromePadding]), not composition. */
    val height: Dp get() = aboveNavigationBar()
}

/** Null outside AuralisApp (previews): pages then only clear the navigation bar. */
val LocalBottomChrome = staticCompositionLocalOf<BottomChrome?> { null }

/**
 * Content padding whose bottom clears the dock and mini player by [gap].
 *
 * The chrome height is read inside [PaddingValues.calculateBottomPadding], i.e. during
 * measure, so the mini-player glide only re-lays out the list instead of recomposing
 * the whole page each frame.
 *
 * Pass [includeNavigationBar] = false when the page already sits inside a
 * `navigationBarsPadding()` container.
 */
@Composable
fun bottomChromePadding(
    start: Dp = 0.dp,
    top: Dp = 0.dp,
    end: Dp = 0.dp,
    gap: Dp = 16.dp,
    includeNavigationBar: Boolean = true
): PaddingValues {
    val chrome = LocalBottomChrome.current
    val navigationBars = WindowInsets.navigationBars
    val density = LocalDensity.current
    return remember(chrome, navigationBars, density, start, top, end, gap, includeNavigationBar) {
        ChromePaddingValues(start, top, end, gap, chrome, if (includeNavigationBar) navigationBars else null, density)
    }
}

internal class ChromePaddingValues(
    private val start: Dp,
    private val top: Dp,
    private val end: Dp,
    private val gap: Dp,
    private val chrome: BottomChrome?,
    private val navigationBars: WindowInsets?,
    private val density: Density
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) start else end

    override fun calculateTopPadding(): Dp = top

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) end else start

    override fun calculateBottomPadding(): Dp {
        val navigationBar = navigationBars?.let { with(density) { it.getBottom(this).toDp() } } ?: 0.dp
        return navigationBar + (chrome?.height ?: 0.dp) + gap
    }
}
