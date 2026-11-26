package com.auralis.music.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.components.tactileBounce
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Motion for the playlist page: the cover drifts and softens as the page scrolls, a compact
 * title chip takes over the top bar, rows ripple in when the page opens (or after a jump back
 * to the top), and long playlists get a slim scroller that can be grabbed.
 */

/** Key of the playlist page's header item. */
internal const val PLAYLIST_HEADER_KEY = "playlist_header"

/**
 * How far the header has scrolled up, in px. When the header has left the list entirely it
 * reports [Float.MAX_VALUE] (fully scrolled away).
 */
internal fun LazyListState.playlistHeaderScrollPx(): Float {
    val header = layoutInfo.visibleItemsInfo.firstOrNull { it.key == PLAYLIST_HEADER_KEY }
    return when {
        header != null -> (-header.offset).toFloat().coerceAtLeast(0f)
        firstVisibleItemIndex > 1 -> Float.MAX_VALUE
        else -> 0f
    }
}

/** The hero cover's scroll look: it trails the page (parallax), swells a touch, dims and blurs. */
internal fun Modifier.playlistHeroDrift(state: LazyListState, coverHeightPx: () -> Float): Modifier =
    graphicsLayer {
        val height = coverHeightPx().coerceAtLeast(1f)
        val scrolled = state.playlistHeaderScrollPx().coerceAtMost(height)
        val f = scrolled / height
        translationY = scrolled * 0.42f
        val swell = 1f + 0.06f * f
        scaleX = swell
        scaleY = swell
        alpha = 1f - 0.55f * f
        val blur = 26f * f
        renderEffect = if (blur > 0.5f) BlurEffect(blur, blur) else null
    }

/**
 * A ripple of rows sliding into place, top to bottom. [play] restarts it; rows ask for their
 * look with [rowLayer], passing their rank on screen (0 = first row shown).
 */
@Stable
internal class PlaylistCascade(private val scope: CoroutineScope) {
    private val clock = Animatable(1f)
    /** First list index of the ripple: rows above it are already in place. */
    var baseIndex by mutableStateOf(0)
        private set

    fun play(fromIndex: Int = 0) {
        baseIndex = fromIndex
        scope.launch {
            clock.snapTo(0f)
            clock.animateTo(1f, tween(TOTAL_MS, easing = LinearEasing))
        }
    }

    /** 0 = not shown yet, 1 = settled, for the row [rank] places below the ripple's start. */
    fun progress(rank: Int): Float {
        val c = clock.value
        if (c >= 1f || rank < 0) return 1f
        val r = rank.coerceAtMost(MAX_RANK)
        val t = (c * TOTAL_MS - r * STAGGER_MS) / ROW_MS
        return FastOutSlowInEasing.transform(t.coerceIn(0f, 1f))
    }

    companion object {
        private const val STAGGER_MS = 34f
        private const val ROW_MS = 380f
        private const val MAX_RANK = 14
        private const val TOTAL_MS = (MAX_RANK * STAGGER_MS + ROW_MS).toInt()
    }
}

@androidx.compose.runtime.Composable
internal fun rememberPlaylistCascade(): PlaylistCascade {
    val scope = rememberCoroutineScope()
    return remember(scope) { PlaylistCascade(scope) }
}

/** A row's part in the ripple: it rises a little, fades in and comes up to full size. */
internal fun Modifier.playlistCascadeLayer(cascade: PlaylistCascade, rank: () -> Int, risePx: Float): Modifier =
    graphicsLayer {
        val p = cascade.progress(rank())
        if (p >= 1f) return@graphicsLayer
        alpha = p
        translationY = (1f - p) * risePx
        val s = 0.97f + 0.03f * p
        scaleX = s
        scaleY = s
    }

/**
 * The chip that takes over the top bar once the header has scrolled under it: the cover in
 * miniature beside the playlist's name. Tapping it goes back to the top.
 */
@Composable
internal fun PlaylistCollapsedTitle(
    visible: Boolean,
    title: String,
    chipModifier: Modifier,
    textColor: Color,
    onClick: () -> Unit,
    cover: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    // 0 = tucked behind the back button, 1 = fully out. It swells out of the button's right side
    // on a soft spring (a touch of overshoot) and shrinks back into it.
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible) spring(dampingRatio = 0.68f, stiffness = 340f)
            else tween(230, easing = FastOutSlowInEasing),
        label = "titleChipReveal"
    )
    if (reveal <= 0.001f && !visible) return
    val shape = RoundedCornerShape(23.dp)
    val density = LocalDensity.current
    // Starts as a small bead hidden under the button, then stretches out to the right.
    val tuckPx = with(density) { 26.dp.toPx() }
    val beadPx = with(density) { 30.dp.toPx() }
    Box(
        modifier = modifier
            .offset { IntOffset((-tuckPx * (1f - reveal.coerceIn(0f, 1f))).roundToInt(), 0) }
            .graphicsLayer {
                // Swells slightly taller as it bursts out, settling to its own height.
                val swell = 0.72f + 0.28f * reveal
                scaleY = swell
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                alpha = (reveal * 3f).coerceIn(0f, 1f)
            }
    ) {
        Row(
            modifier = Modifier
                .height(46.dp)
                .then(chipModifier)
                .clip(shape)
                .tactileBounce(scaleDown = 0.94f, onClick = onClick)
                // The pill is as wide as the reveal allows; its content stays full size and is
                // uncovered from the left as the pill stretches.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0))
                    val natural = placeable.width.toFloat()
                    val w = (beadPx + (natural - beadPx) * reveal).roundToInt()
                        .coerceIn(0, constraints.maxWidth)
                    layout(w, placeable.height) { placeable.place(0, 0) }
                }
                .widthIn(max = 230.dp)
                .padding(start = 6.dp, end = 16.dp)
                .graphicsLayer { alpha = ((reveal - 0.3f) / 0.7f).coerceIn(0f, 1f) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))) { cover() }
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                color = textColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
