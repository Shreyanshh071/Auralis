package com.auralis.music.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auralis.music.domain.model.Track

/** How a platform draws artwork (the phone with Coil 2, desktop with Coil 3). */
interface ArtworkRenderer {
    @Composable
    fun Draw(
        url: String?, modifier: Modifier, cornerRadius: Dp, elevation: Dp, contentDescription: String?,
        fallbackTrack: Track?, contentScale: ContentScale, highRes: Boolean, crossfade: Boolean, sizeToConstraints: Boolean
    )

    /** Just the image at [url] (no corners, placeholder or URL sizing), like a bare AsyncImage. */
    @Composable
    fun Image(url: String, contentDescription: String?, contentScale: ContentScale, crossfade: Boolean, modifier: Modifier,
              alignment: Alignment = Alignment.Center)

    /** Starts loading artwork (url, or the track's thumbnail) that is about to scroll into view. */
    fun prefetch(items: List<Pair<String?, Track?>>, sizeToConstraints: Boolean) {}
}

/** Until a platform installs its renderer: the empty-artwork look (note on a dark tile). */
object PlaceholderArtworkRenderer : ArtworkRenderer {
    @Composable
    override fun Draw(
        url: String?, modifier: Modifier, cornerRadius: Dp, elevation: Dp, contentDescription: String?,
        fallbackTrack: Track?, contentScale: ContentScale, highRes: Boolean, crossfade: Boolean, sizeToConstraints: Boolean
    ) {
        Box(modifier.clip(RoundedCornerShape(cornerRadius)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    override fun Image(url: String, contentDescription: String?, contentScale: ContentScale, crossfade: Boolean, modifier: Modifier, alignment: Alignment) {}
}

val LocalArtworkRenderer = staticCompositionLocalOf<ArtworkRenderer> { PlaceholderArtworkRenderer }

/** Album or track artwork, rounded, with a placeholder while it loads; drawn by the platform's renderer. */
@Composable
fun ArtworkCard(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    elevation: Dp = 0.dp,
    contentDescription: String? = null,
    fallbackTrack: Track? = null,
    contentScale: ContentScale = ContentScale.Crop,
    highRes: Boolean = false,
    // Fades in only when loaded from disk or network; a memory-cache hit still shows instantly.
    crossfade: Boolean = true,
    // Opt in only for bounded browsing thumbnails; player and large artwork keep explicit sizes.
    sizeToConstraints: Boolean = false
) = LocalArtworkRenderer.current.Draw(url, modifier, cornerRadius, elevation, contentDescription, fallbackTrack, contentScale, highRes, crossfade, sizeToConstraints)

@Composable
fun rememberShimmerBrush(
    targetValue: Float = 1400f
): androidx.compose.ui.graphics.Brush {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmerTransition")
    val translateAnimation = transition.animateFloat(
        initialValue = -500f,
        targetValue = targetValue,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(durationMillis = 600, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val shimmerColors = listOf(
        Color(0xFF16151B),
        Color(0xFF262532),
        Color(0xFF3D3C4E),
        Color(0xFF262532),
        Color(0xFF16151B)
    )

    return androidx.compose.ui.graphics.Brush.linearGradient(
        colors = shimmerColors,
        start = androidx.compose.ui.geometry.Offset(translateAnimation.value, 0f),
        end = androidx.compose.ui.geometry.Offset(translateAnimation.value + 400f, 0f)
    )
}

/**
 * Loads the artwork of the [ahead] items past the last visible one, and a few before the first,
 * whenever the visible range changes, so covers are ready before their rows scroll in. [artworkAt]
 * gives an item's artwork by its index in the list (null for items without one).
 */
@Composable
fun PrefetchListArtwork(
    state: androidx.compose.foundation.lazy.LazyListState,
    sizeToConstraints: Boolean = true,
    ahead: Int = 24,
    artworkAt: (Int) -> Pair<String?, Track?>?
) {
    val renderer = LocalArtworkRenderer.current
    val lookup = androidx.compose.runtime.rememberUpdatedState(artworkAt)
    androidx.compose.runtime.LaunchedEffect(state, renderer, sizeToConstraints) {
        androidx.compose.runtime.snapshotFlow {
            val visible = state.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) null else visible.first().index..visible.last().index
        }.collect { range ->
            if (range == null) return@collect
            val total = state.layoutInfo.totalItemsCount
            val indices = (range.last + 1..minOf(range.last + ahead, total - 1)) + (maxOf(range.first - 6, 0) until range.first)
            renderer.prefetch(indices.mapNotNull { lookup.value(it) }, sizeToConstraints)
        }
    }
}
