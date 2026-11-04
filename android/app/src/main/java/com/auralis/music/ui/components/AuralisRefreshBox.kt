package com.auralis.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.glass.glassBarTint
import com.auralis.music.ui.glass.liquidGlass
import com.auralis.music.ui.theme.dynamicPrimary
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pull down at the top of a page to reload it. The same gesture and indicator on every page.
 *
 * The page stays where it is: a round button drops in over it, glass on liquid glass and solid
 * otherwise, matching the page's floating buttons. A ring fills with the pull and spins while
 * loading, then the button rises away when the reload finishes.
 *
 * Pass [isRefreshing] when the screen tracks its own loading state, or use the suspending
 * overload, which shows the indicator until [onRefresh] returns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuralisRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    indicatorTopInset: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()
    // Only records the page while the button is out: it's a whole extra layer otherwise.
    val showing by remember { derivedStateOf { isRefreshingOrPulled(state, isRefreshing) } }
    val pageBackdrop = rememberLayerBackdrop()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state) {
        snapshotFlow { state.distanceFraction >= 1f }.collect { armed ->
            if (armed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    Box(
        modifier.pullToRefresh(
            isRefreshing = isRefreshing,
            state = state,
            threshold = RefreshPull,
            onRefresh = onRefresh
        )
    ) {
        Box(
            Modifier.fillMaxSize().then(if (showing) Modifier.layerBackdrop(pageBackdrop) else Modifier),
            content = content
        )
        if (showing) RefreshButton(
            state = state,
            isRefreshing = isRefreshing,
            backdrop = pageBackdrop,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().offset(y = indicatorTopInset + 12.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun isRefreshingOrPulled(state: PullToRefreshState, isRefreshing: Boolean) =
    isRefreshing || state.distanceFraction > 0f

/** Variant for pages without their own loading flag: the indicator stays up while [refresh] runs. */
@Composable
fun AuralisRefreshBox(
    refresh: suspend () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    indicatorTopInset: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit
) {
    var refreshing by remember { mutableStateOf(false) }
    // Start only after Compose has applied isRefreshing=true to the pull modifier.
    // A no-op (e.g. search history) or cache hit can otherwise set true and false in
    // the same frame, leaving Material's release animation parked at the threshold.
    LaunchedEffect(refreshing) {
        if (refreshing) {
            try { refresh() } finally { refreshing = false }
        }
    }
    AuralisRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                refreshing = true
            }
        },
        modifier = modifier,
        indicatorTopInset = indicatorTopInset,
        content = content
    )
}

/** The pull that triggers a reload. */
private val RefreshPull = PullToRefreshDefaults.PositionalThreshold + 16.dp
private val ButtonSize = 46.dp
private val RingSize = 22.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshButton(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    backdrop: Backdrop,
    modifier: Modifier
) {
    // Milliseconds since loading started; drives the spin. Only ticks while loading.
    var loadingMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { loadingMs = (it - start) / 1_000_000f }
    }
    val glass = rememberPageHeaderGlass(backdrop)
    val accent = MaterialTheme.dynamicPrimary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = .14f)
    val travelPx = with(LocalDensity.current) { (ButtonSize + 28.dp).toPx() }
    val surface = if (glass != null) {
        Modifier.liquidGlass(glass, CircleShape, tint = glassBarTint(glass.isDark), blurRadius = 14.dp)
    } else {
        Modifier.shadow(8.dp, CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
    }
    Box(
        modifier
            // Moved in placement, not a layer, so the glass samples what's really behind it.
            .offset { IntOffset(0, (-(1f - state.distanceFraction.coerceAtMost(1f)) * travelPx).roundToInt()) }
            .graphicsLayer {
                val shown = state.distanceFraction.coerceIn(0f, 1f)
                alpha = (shown * 2.5f).coerceAtMost(1f)
                scaleX = .7f + .3f * shown
                scaleY = .7f + .3f * shown
            }
            .size(ButtonSize)
            .then(surface),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(RingSize)) {
            val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(track, style = Stroke(width = 2.5.dp.toPx()))
            val pull = state.distanceFraction.coerceIn(0f, 1f)
            val (start, sweep) = if (isRefreshing) {
                loadingMs * .36f to 90f + 170f * abs(sin(loadingMs / 520f))
            } else {
                -90f + pull * 120f to pull * 300f
            }
            drawArc(accent, start, sweep, useCenter = false, style = stroke)
        }
    }
}
