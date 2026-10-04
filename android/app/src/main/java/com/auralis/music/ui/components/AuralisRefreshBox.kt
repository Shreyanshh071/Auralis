package com.auralis.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.theme.dynamicPrimary
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pull down at the top of a page to reload it. The same gesture and indicator on every page.
 *
 * The page itself slides down with the finger and a morphing shape sits in the gap it leaves
 * (Material 3 expressive style); while loading the page stays lowered, and it slides back up when
 * the reload finishes.
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
    val gapPx = with(LocalDensity.current) { RefreshGap.toPx() }
    Box(
        modifier.pullToRefresh(
            isRefreshing = isRefreshing,
            state = state,
            threshold = RefreshGap,
            onRefresh = onRefresh
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, (state.distanceFraction * gapPx).roundToInt()) },
            content = content
        )
        MorphingRefreshIndicator(
            state = state,
            isRefreshing = isRefreshing,
            gapPx = gapPx,
            color = MaterialTheme.dynamicPrimary,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = indicatorTopInset)
        )
    }
}

/** Variant for pages without their own loading flag: the indicator stays up while [refresh] runs. */
@Composable
fun AuralisRefreshBox(
    refresh: suspend () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    indicatorTopInset: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit
) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AuralisRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                refreshing = true
                scope.launch {
                    try { refresh() } finally { refreshing = false }
                }
            }
        },
        modifier = modifier,
        indicatorTopInset = indicatorTopInset,
        content = content
    )
}

/** How far the page drops: the pull needed to trigger, and where it rests while loading. */
private val RefreshGap = PullToRefreshDefaults.PositionalThreshold + 16.dp
private val IndicatorSize = 40.dp

/**
 * Rounded shapes the indicator morphs through, as polar lobes: r(θ) = 1 + depth·cos(lobes·θ).
 * Cookie, pentagon, pill, sun, four-leaf, soft burst — the Material expressive loading set.
 */
private val Shapes = listOf(9 to 0.09f, 5 to 0.13f, 2 to 0.16f, 8 to 0.07f, 4 to 0.12f, 12 to 0.05f)
private const val MORPH_MS = 650f
private const val HOLD_MS = 150f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MorphingRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    gapPx: Float,
    color: Color,
    modifier: Modifier
) {
    // Milliseconds since loading started; drives the morph and spin. Only ticks while loading.
    var loadingMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { loadingMs = (it - start) / 1_000_000f }
    }
    val path = remember { Path() }
    val sizePx = with(LocalDensity.current) { IndicatorSize.toPx() }

    Canvas(
        modifier
            .size(IndicatorSize)
            .graphicsLayer {
                val f = state.distanceFraction
                // Centre the shape in the gap the page leaves; grow in as it opens.
                translationY = (f * gapPx - sizePx) / 2f
                val grow = ((f - 0.15f) / 0.85f).coerceIn(0f, 1f)
                scaleX = grow
                scaleY = grow
                alpha = grow
            }
    ) {
        val f = state.distanceFraction
        if (f <= 0.01f) return@Canvas

        val (from, to, t, spin) = if (isRefreshing) {
            val cycle = MORPH_MS + HOLD_MS
            val step = (loadingMs / cycle).toInt()
            val within = ((loadingMs - step * cycle) / MORPH_MS).coerceIn(0f, 1f)
            val eased = easeInOut(within)
            // Steady spin plus an extra quarter turn on every morph, like the M3 indicator.
            val spin = loadingMs * 0.24f + (step + eased) * 90f
            Morph(Shapes[step % Shapes.size], Shapes[(step + 1) % Shapes.size], eased, spin)
        } else {
            Morph(Shapes[0], Shapes[0], 0f, f * 180f)
        }

        val radius = size.minDimension / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val rot = spin / 180f * PI.toFloat()
        path.reset()
        val points = 96
        for (i in 0..points) {
            val theta = i / points.toFloat() * 2f * PI.toFloat()
            val r1 = (1f + from.second * cos(from.first * theta)) / (1f + from.second)
            val r2 = (1f + to.second * cos(to.first * theta)) / (1f + to.second)
            val r = radius * (r1 + (r2 - r1) * t)
            val x = cx + r * cos(theta + rot)
            val y = cy + r * sin(theta + rot)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
    }
}

private data class Morph(val from: Pair<Int, Float>, val to: Pair<Int, Float>, val t: Float, val spin: Float)

private fun easeInOut(x: Float): Float = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
