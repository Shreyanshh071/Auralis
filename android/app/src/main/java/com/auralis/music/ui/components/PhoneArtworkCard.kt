package com.auralis.music.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.launch

/** The phone's artwork (Coil 2), drawn for shared [ArtworkCard] through [PhoneArtworkRenderer]. */
@Composable
fun PhoneArtworkCard(
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
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }

    // Resolve the best primary URL instantly with LRU cache lookup
    val resolvedUrl = remember(url, highRes) {
        if (!url.isNullOrBlank()) {
            if (highRes) getHighResArtworkUrl(url) ?: url else getOptimizedThumbnailUrl(url) ?: url
        } else null
    }

    val fallbackUrl = remember(fallbackTrack?.id, fallbackTrack?.thumbnail, highRes) {
        val fallbackRaw = fallbackTrack?.thumbnail
        val localArt = fallbackTrack?.id?.let {
            com.auralis.music.data.download.AuralisDownloadManager.getDownloadedArtworkFile(it)
        }
        val matchedYtId = fallbackTrack?.id?.let {
            com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(it)
        }
        val resolvedArt = fallbackTrack?.let {
            com.auralis.music.data.network.ArtworkResolver.getArtwork(it)
        }
        when {
            localArt != null && localArt.exists() && localArt.length() > 500 -> {
                android.net.Uri.fromFile(localArt).toString()
            }
            fallbackTrack != null && !fallbackRaw.isNullOrBlank() -> {
                if (highRes) getHighResArtworkUrl(fallbackRaw) ?: fallbackRaw else getOptimizedThumbnailUrl(fallbackRaw) ?: fallbackRaw
            }
            !resolvedArt.isNullOrBlank() -> {
                if (highRes) getHighResArtworkUrl(resolvedArt) ?: resolvedArt else getOptimizedThumbnailUrl(resolvedArt) ?: resolvedArt
            }
            !matchedYtId.isNullOrBlank() && matchedYtId.length in 8..15 -> {
                if (highRes) "https://i.ytimg.com/vi/$matchedYtId/hq720.jpg" else "https://i.ytimg.com/vi/$matchedYtId/hqdefault.jpg"
            }
            fallbackTrack != null && fallbackTrack.id.length in 8..15 -> {
                if (highRes) "https://i.ytimg.com/vi/${fallbackTrack.id}/hq720.jpg" else "https://i.ytimg.com/vi/${fallbackTrack.id}/hqdefault.jpg"
            }
            else -> null
        }
    }

    var isPrimaryError by remember(resolvedUrl) { mutableStateOf(false) }
    var asyncResolvedUrl by remember(fallbackTrack?.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(fallbackTrack?.id, resolvedUrl, fallbackUrl) {
        if (resolvedUrl.isNullOrBlank() && fallbackUrl.isNullOrBlank() && fallbackTrack != null) {
            val art = com.auralis.music.data.network.ArtworkResolver.resolveArtwork(fallbackTrack)
            if (!art.isNullOrBlank()) {
                asyncResolvedUrl = art
            }
        }
    }

    val activeUrl = if (!resolvedUrl.isNullOrBlank() && !isPrimaryError) {
        resolvedUrl
    } else if (!fallbackUrl.isNullOrBlank()) {
        fallbackUrl
    } else {
        asyncResolvedUrl
    }

    var isError by remember(activeUrl) { mutableStateOf(false) }

    val context = LocalContext.current
    val imageRequest = remember(context, activeUrl, highRes, crossfade, sizeToConstraints) {
        if (activeUrl.isNullOrBlank()) null else {
            val isCachedInMemory = try {
                coil.Coil.imageLoader(context).memoryCache?.get(coil.memory.MemoryCache.Key(activeUrl)) != null
            } catch (_: Throwable) {
                false
            }
            val dataModel: Any = when {
                activeUrl.startsWith("data:image/") -> {
                    try {
                        val base64 = activeUrl.substringAfter("base64,")
                        android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    } catch (_: Throwable) {
                        activeUrl
                    }
                }
                activeUrl.startsWith("file://") -> java.io.File(activeUrl.removePrefix("file://"))
                activeUrl.startsWith("/") -> java.io.File(activeUrl)
                else -> activeUrl
            }
            ImageRequest.Builder(context)
                .data(dataModel)
                .apply {
                    if (highRes) size(600, 600)
                    else if (!sizeToConstraints) size(384, 384)
                    // Otherwise AsyncImage supplies its remembered ConstraintsSizeResolver.
                }
                .allowHardware(true)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(crossfade && !isCachedInMemory)
                .build()
        }
    }

    val onStateCallback = remember(resolvedUrl, fallbackUrl, activeUrl) {
        { state: AsyncImagePainter.State ->
            if (state is AsyncImagePainter.State.Error) {
                if (!isPrimaryError && !resolvedUrl.isNullOrBlank() && !fallbackUrl.isNullOrBlank() && resolvedUrl != fallbackUrl) {
                    isPrimaryError = true
                } else {
                    isError = true
                }
            }
        }
    }

    Box(
        modifier = modifier
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape) else Modifier)
            .clip(shape)
            .background(Color(0xFF141414)),
        contentAlignment = Alignment.Center
    ) {
        if (!activeUrl.isNullOrBlank() && !isError) {
            AsyncImage(
                model = imageRequest ?: activeUrl,
                contentDescription = contentDescription,
                contentScale = contentScale,
                onState = onStateCallback,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/** Installed by AuralisTheme as [LocalArtworkRenderer]. */
object PhoneArtworkRenderer : ArtworkRenderer {
    @Composable
    override fun Draw(
        url: String?, modifier: Modifier, cornerRadius: Dp, elevation: Dp, contentDescription: String?,
        fallbackTrack: Track?, contentScale: ContentScale, highRes: Boolean, crossfade: Boolean, sizeToConstraints: Boolean
    ) = PhoneArtworkCard(url, modifier, cornerRadius, elevation, contentDescription, fallbackTrack, contentScale, highRes, crossfade, sizeToConstraints)

    @Composable
    override fun Image(url: String, contentDescription: String?, contentScale: ContentScale, crossfade: Boolean, modifier: Modifier) {
        val context = LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(context).data(url).crossfade(crossfade).build(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier
        )
    }
}
