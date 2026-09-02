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

/**
 * Optimizes thumbnail URLs to crisp, hardware-accelerated HD artwork (544x544 or 480x360),
 * avoiding memory bloat and maximizing scroll framerates without causing 404 error cascades.
 */
fun getHighResArtworkUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    var cleaned = url.trim()
    if (cleaned.startsWith("//")) cleaned = "https:$cleaned"

    // YouTube Music & Google User Content (1:1 square crisp 544x544 artwork):
    if (cleaned.contains("googleusercontent.com") || cleaned.contains("ggpht.com")) {
        return cleaned.replace(GOOGLE_W_REGEX, "=w544-h544-l90-rj")
            .replace(GOOGLE_S_REGEX, "=s544-c")
    }
    // YouTube video thumbnail (reliable 100% available 480x360 HD artwork):
    if (cleaned.contains("i.ytimg.com") || cleaned.contains("img.youtube.com") || cleaned.contains("youtu")) {
        val videoIdRegex = Regex("""(?:vi/|vi_webp/|v=|embed/|\.be/)([a-zA-Z0-9_-]{11})""")
        val match = videoIdRegex.find(cleaned)?.groupValues?.getOrNull(1)
        return if (!match.isNullOrBlank()) {
            "https://i.ytimg.com/vi/$match/hqdefault.jpg"
        } else {
            val noQuery = cleaned.substringBefore('?')
            noQuery.replace("default.jpg", "hqdefault.jpg")
                .replace("mqdefault.jpg", "hqdefault.jpg")
                .replace("hq720.jpg", "hqdefault.jpg")
        }
    }
    // iTunes / Apple Music artwork:
    if (cleaned.contains("mzstatic.com")) {
        return cleaned.replace(MZSTATIC_REGEX, "600x600bb")
    }
    // Spotify artwork:
    if (cleaned.contains("i.scdn.co/image/ab67616d00004851") || cleaned.contains("i.scdn.co/image/ab67616d00001e02")) {
        return cleaned.replace("ab67616d00004851", "ab67616d0000b273")
            .replace("ab67616d00001e02", "ab67616d0000b273")
    }
    return cleaned
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
    val context = LocalContext.current

    // Resolve the best primary URL instantly without network cascades
    val resolvedUrl = remember(url) {
        if (!url.isNullOrBlank()) getHighResArtworkUrl(url) ?: url else null
    }

    val fallbackUrl = remember(fallbackTrack?.id, fallbackTrack?.thumbnail) {
        when {
            fallbackTrack != null && !fallbackTrack.thumbnail.isNullOrBlank() -> getHighResArtworkUrl(fallbackTrack.thumbnail) ?: fallbackTrack.thumbnail
            fallbackTrack != null && !fallbackTrack.id.startsWith("sp_") && fallbackTrack.id.length in 8..15 -> "https://i.ytimg.com/vi/${fallbackTrack.id}/hqdefault.jpg"
            else -> null
        }
    }

    var isPrimaryError by remember(resolvedUrl) { mutableStateOf(false) }

    val activeUrl = remember(resolvedUrl, fallbackUrl, isPrimaryError) {
        if (!resolvedUrl.isNullOrBlank() && !isPrimaryError) {
            resolvedUrl
        } else {
            fallbackUrl
        }
    }

    val isYouTubeVideoThumb = remember(activeUrl) {
        activeUrl != null && (activeUrl.contains("i.ytimg.com") || activeUrl.contains("img.youtube.com"))
    }

    var isError by remember(activeUrl) { mutableStateOf(false) }

    val request = remember(activeUrl) {
        if (!activeUrl.isNullOrBlank()) {
            ImageRequest.Builder(context)
                .data(activeUrl)
                .allowHardware(true)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(100)
                .build()
        } else null
    }

    Box(
        modifier = modifier
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape) else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (request != null && !isError) {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) {
                        if (!isPrimaryError && !resolvedUrl.isNullOrBlank() && !fallbackUrl.isNullOrBlank() && resolvedUrl != fallbackUrl) {
                            isPrimaryError = true
                        } else {
                            isError = true
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (isYouTubeVideoThumb) Modifier.graphicsLayer { scaleX = 1.34f; scaleY = 1.34f } else Modifier)
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
