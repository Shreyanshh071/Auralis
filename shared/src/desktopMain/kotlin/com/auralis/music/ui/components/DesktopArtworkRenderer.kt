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
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.auralis.music.domain.model.Track

/**
 * Artwork on desktop with Coil 3, drawn like the phone's ArtworkCard: the same URL sizing, the
 * shimmer while loading, a fade-in only when the image wasn't already in memory, and the music
 * note when there's no artwork. Tracks without a URL fall back to their YouTube thumbnail.
 */
object DesktopArtworkRenderer : ArtworkRenderer {
    @Composable
    override fun Draw(
        url: String?, modifier: Modifier, cornerRadius: Dp, elevation: Dp, contentDescription: String?,
        fallbackTrack: Track?, contentScale: ContentScale, highRes: Boolean, crossfade: Boolean, sizeToConstraints: Boolean
    ) {
        val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
        val resolved = remember(url, fallbackTrack?.id, fallbackTrack?.thumbnail, highRes) {
            val raw = url?.takeIf { it.isNotBlank() } ?: fallbackTrack?.thumbnail?.takeIf { it.isNotBlank() }
            when {
                raw != null -> if (highRes) getHighResArtworkUrl(raw) ?: raw else getOptimizedThumbnailUrl(raw) ?: raw
                fallbackTrack != null && fallbackTrack.id.length in 8..15 ->
                    if (highRes) "https://i.ytimg.com/vi/${fallbackTrack.id}/hq720.jpg" else "https://i.ytimg.com/vi/${fallbackTrack.id}/hqdefault.jpg"
                else -> null
            }
        }
        var state by remember(resolved) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
        Box(
            modifier = modifier
                .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape) else Modifier)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (resolved == null || state is AsyncImagePainter.State.Error) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (resolved != null) {
                if (state is AsyncImagePainter.State.Loading) {
                    Box(Modifier.fillMaxSize().background(rememberShimmerBrush()))
                }
                val context = LocalPlatformContext.current
                AsyncImage(
                    model = remember(resolved, crossfade) {
                        ImageRequest.Builder(context).data(resolved).crossfade(crossfade).build()
                    },
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    onState = { state = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    @Composable
    override fun Image(url: String, contentDescription: String?, contentScale: ContentScale, crossfade: Boolean, modifier: Modifier) {
        val context = LocalPlatformContext.current
        AsyncImage(
            model = remember(url, crossfade) { ImageRequest.Builder(context).data(url).crossfade(crossfade).build() },
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier
        )
    }
}
