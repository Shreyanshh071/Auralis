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

private val GOOGLE_W_REGEX = Regex("""=w\d+-h\d+.*""")
private val GOOGLE_S_REGEX = Regex("""=s\d+.*""")
private val MZSTATIC_REGEX = Regex("""\d+x\d+bb""")

private val YOUTUBE_VIDEO_ID_REGEX = Regex("""(?:vi/|vi_webp/|v=|embed/|\.be/)([a-zA-Z0-9_-]{11})""")

// High-performance LRU cache to eliminate redundant regex evaluation on thousands of track items during scrolling
private val artworkUrlCache = androidx.collection.LruCache<String, String>(500)

/**
 * Optimizes thumbnail URLs to uncompressed studio master HD artwork (1200x1200 or 720p),
 * providing razor-sharp, crystal-clear album covers.
 */
fun getHighResArtworkUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    artworkUrlCache[url]?.let { return it }

    var cleaned = url.trim()
    if (cleaned.startsWith("//")) cleaned = "https:$cleaned"

    val result = when {
        // YouTube Music & Google User Content: upgrade to studio master 1200x1200 uncompressed artwork:
        cleaned.contains("googleusercontent.com") || cleaned.contains("ggpht.com") -> {
            cleaned.replace(GOOGLE_W_REGEX, "=w1200-h1200-l90-rj")
                .replace(GOOGLE_S_REGEX, "=s1200-c")
        }
        // YouTube video thumbnail: upgrade to 1280x720 HD hq720
        cleaned.contains("i.ytimg.com") || cleaned.contains("img.youtube.com") || cleaned.contains("youtu") -> {
            val match = YOUTUBE_VIDEO_ID_REGEX.find(cleaned)?.groupValues?.getOrNull(1)
            if (!match.isNullOrBlank()) {
                "https://i.ytimg.com/vi/$match/hq720.jpg"
            } else {
                val noQuery = cleaned.substringBefore('?')
                noQuery.replace("hqdefault.jpg", "hq720.jpg")
                    .replace("mqdefault.jpg", "hq720.jpg")
                    .replace("default.jpg", "hq720.jpg")
            }
        }
        // iTunes / Apple Music artwork: 1200x1200bb uncompressed
        cleaned.contains("mzstatic.com") -> {
            cleaned.replace(MZSTATIC_REGEX, "1200x1200bb")
        }
        // Spotify artwork: 640x640 highest resolution
        cleaned.contains("i.scdn.co/image/ab67616d00004851") || cleaned.contains("i.scdn.co/image/ab67616d00001e02") -> {
            cleaned.replace("ab67616d00004851", "ab67616d0000b273")
                .replace("ab67616d00001e02", "ab67616d0000b273")
        }
        else -> cleaned
    }

    artworkUrlCache.put(url, result)
    return result
}

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

@Composable
fun ArtworkCard(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    elevation: Dp = 0.dp,
    contentDescription: String? = null,
    fallbackTrack: Track? = null,
    contentScale: ContentScale = ContentScale.Crop
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }

    // Resolve the best primary URL instantly with LRU cache lookup
    val resolvedUrl = remember(url) {
        if (!url.isNullOrBlank()) getHighResArtworkUrl(url) ?: url else null
    }

    val fallbackUrl = remember(fallbackTrack?.id, fallbackTrack?.thumbnail) {
        when {
            fallbackTrack != null && !fallbackTrack.thumbnail.isNullOrBlank() -> getHighResArtworkUrl(fallbackTrack.thumbnail) ?: fallbackTrack.thumbnail
            fallbackTrack != null && fallbackTrack.id.length in 8..15 -> "https://i.ytimg.com/vi/${fallbackTrack.id}/hqdefault.jpg"
            else -> null
        }
    }

    var isPrimaryError by remember(resolvedUrl) { mutableStateOf(false) }

    val activeUrl = if (!resolvedUrl.isNullOrBlank() && !isPrimaryError) {
        resolvedUrl
    } else {
        fallbackUrl
    }

    var isError by remember(activeUrl) { mutableStateOf(false) }

    val context = LocalContext.current
    val imageRequest = remember(activeUrl) {
        if (activeUrl.isNullOrBlank()) null else {
            ImageRequest.Builder(context)
                .data(activeUrl)
                .allowHardware(true)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .build()
        }
    }

    val onStateCallback = remember(resolvedUrl, fallbackUrl) {
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
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
