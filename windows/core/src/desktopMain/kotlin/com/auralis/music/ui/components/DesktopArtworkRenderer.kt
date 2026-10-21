package com.auralis.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.auralis.music.domain.model.Track

/**
 * Artwork on desktop with Coil 3, drawn like the phone's ArtworkCard: the same URL sizing and the
 * music note when there's no artwork. Tracks without a URL fall back to their YouTube thumbnail.
 *
 * Every request has a fixed decode size: one sized to its layout waits for the first layout before
 * it can even look in the memory cache, and each screen's size made its own cache entry, so covers
 * already seen elsewhere loaded again. A large cover shows the thumbnail already in memory until
 * the full-size one arrives.
 */
object DesktopArtworkRenderer : ArtworkRenderer {
    /** Decode sizes in pixels: list rows, tiles and cards, then large views. */
    const val ROW_PX = 160
    const val TILE_PX = 400
    const val LARGE_PX = 1200

    internal fun resolve(url: String?, fallbackTrack: Track?, highRes: Boolean): String? {
        val raw = url?.takeIf { it.isNotBlank() } ?: fallbackTrack?.thumbnail?.takeIf { it.isNotBlank() }
        return when {
            raw != null -> if (highRes) getHighResArtworkUrl(raw) ?: raw else getOptimizedThumbnailUrl(raw) ?: raw
            fallbackTrack != null && fallbackTrack.id.length in 8..15 ->
                if (highRes) "https://i.ytimg.com/vi/${fallbackTrack.id}/hq720.jpg" else "https://i.ytimg.com/vi/${fallbackTrack.id}/hqdefault.jpg"
            else -> null
        }
    }

    fun request(context: PlatformContext, resolved: String, sizePx: Int, crossfade: Boolean, placeholderKey: String? = null) =
        ImageRequest.Builder(context).data(resolved).size(sizePx)
            .crossfade(crossfade)
            .apply { if (placeholderKey != null && placeholderKey != resolved) placeholderMemoryCacheKey(placeholderKey) }
            .build()

    @Composable
    override fun Draw(
        url: String?, modifier: Modifier, cornerRadius: Dp, elevation: Dp, contentDescription: String?,
        fallbackTrack: Track?, contentScale: ContentScale, highRes: Boolean, crossfade: Boolean, sizeToConstraints: Boolean
    ) {
        val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
        val context = LocalPlatformContext.current
        val request = remember(url, fallbackTrack?.id, fallbackTrack?.thumbnail, highRes, crossfade, sizeToConstraints) {
            resolve(url, fallbackTrack, highRes)?.let { resolved ->
                request(context, resolved, if (highRes) LARGE_PX else if (sizeToConstraints) ROW_PX else TILE_PX, crossfade,
                    placeholderKey = if (highRes) resolve(url, fallbackTrack, highRes = false) else null)
            }
        }
        var failed by remember(request) { mutableStateOf(false) }
        Box(
            modifier = modifier
                .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape) else Modifier)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (request == null || failed) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    onState = { failed = it is AsyncImagePainter.State.Error },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    @Composable
    override fun Image(url: String, contentDescription: String?, contentScale: ContentScale, crossfade: Boolean, modifier: Modifier) {
        val context = LocalPlatformContext.current
        AsyncImage(
            model = remember(url, crossfade) {
                ImageRequest.Builder(context).data(url).crossfade(crossfade).build()
            },
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier
        )
    }

    /** Recently prefetched URLs, so scrolling doesn't start a second download of one in flight. */
    private val prefetched = object : java.util.LinkedHashMap<String, Unit>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 1500
    }

    override fun prefetch(items: List<Pair<String?, Track?>>, sizeToConstraints: Boolean) {
        val context = PlatformContext.INSTANCE
        val loader = SingletonImageLoader.get(context)
        for ((url, track) in items) {
            val resolved = resolve(url, track, highRes = false) ?: continue
            if (synchronized(prefetched) { prefetched.put(resolved, Unit) } != null) continue
            loader.enqueue(request(context, resolved, if (sizeToConstraints) ROW_PX else TILE_PX, crossfade = false))
        }
    }
}
