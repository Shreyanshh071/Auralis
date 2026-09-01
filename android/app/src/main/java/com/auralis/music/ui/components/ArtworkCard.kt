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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.theme.LocalReducedMotion

private val GOOGLE_W_REGEX = Regex("""=w\d+-h\d+.*""")
private val GOOGLE_S_REGEX = Regex("""=s\d+.*""")
private val MZSTATIC_REGEX = Regex("""\d+x\d+bb""")

/**
 * Optimizes thumbnail URLs to crisp, hardware-accelerated 544x544/720p HD artwork,
 * avoiding memory bloat and maximizing scroll framerates.
 */
fun getHighResArtworkUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    var cleaned = url.trim()
    if (cleaned.startsWith("//")) cleaned = "https:$cleaned"

    // YouTube Music & Google User Content (1:1 square crisp 544x544 artwork):
    if (cleaned.contains("googleusercontent.com") || cleaned.contains("ggpht.com")) {
        cleaned = cleaned.replace(GOOGLE_W_REGEX, "=w544-h544-l90-rj")
            .replace(GOOGLE_S_REGEX, "=s544-c")
    }
    // YouTube video thumbnail (true 16:9 HD without 4:3 letterbox bars):
    if (cleaned.contains("i.ytimg.com") || cleaned.contains("img.youtube.com") || cleaned.contains("youtu")) {
        val videoIdRegex = Regex("""(?:vi/|vi_webp/|v=|embed/|\.be/)([a-zA-Z0-9_-]{11})""")
        val match = videoIdRegex.find(cleaned)?.groupValues?.getOrNull(1)
        cleaned = if (!match.isNullOrBlank()) {
            "https://i.ytimg.com/vi/$match/hq720.jpg"
        } else {
            val noQuery = cleaned.substringBefore('?')
            noQuery.replace("hqdefault.jpg", "hq720.jpg")
                .replace("default.jpg", "mqdefault.jpg")
        }
    }
    // iTunes / Apple Music artwork:
    if (cleaned.contains("mzstatic.com")) {
        cleaned = cleaned.replace(MZSTATIC_REGEX, "600x600bb")
    }
    // Spotify artwork:
    if (cleaned.contains("i.scdn.co/image/ab67616d00004851") || cleaned.contains("i.scdn.co/image/ab67616d00001e02")) {
        cleaned = cleaned.replace("ab67616d00004851", "ab67616d0000b273")
            .replace("ab67616d00001e02", "ab67616d0000b273")
    }
    return cleaned
}

@Composable
fun rememberShimmerBrush(
    targetValue: Float = 1400f
): Brush {
    val transition = rememberInfiniteTransition(label = "shimmerTransition")
    val translateAnimation = transition.animateFloat(
        initialValue = -500f,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shimmerColors = if (isDark) {
        listOf(
            Color(0xFF16151B),
            Color(0xFF262532),
            Color(0xFF3D3C4E),
            Color(0xFF262532),
            Color(0xFF16151B)
        )
    } else {
        listOf(
            Color(0xFFE2E4E9),
            Color(0xFFF1F2F6),
            Color(0xFFFFFFFF),
            Color(0xFFF1F2F6),
            Color(0xFFE2E4E9)
        )
    }

    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnimation.value, 0f),
        end = Offset(translateAnimation.value + 400f, 0f)
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
    val shimmerBrush = rememberShimmerBrush()
    val context = LocalContext.current

    var resolvedFallbackUrl by remember(fallbackTrack?.id, fallbackTrack?.title) {
        mutableStateOf(fallbackTrack?.let { com.auralis.music.data.network.ArtworkResolver.getArtwork(it) })
    }

    LaunchedEffect(url, fallbackTrack?.id, fallbackTrack?.title) {
        if ((url.isNullOrBlank() || url.contains("default.jpg")) && fallbackTrack != null && resolvedFallbackUrl.isNullOrBlank()) {
            val resolved = com.auralis.music.data.network.ArtworkResolver.resolveArtwork(fallbackTrack)
            if (!resolved.isNullOrBlank()) {
                resolvedFallbackUrl = resolved
            }
        }
    }

    // Build the ordered list of candidate URLs to try
    val candidateUrls = remember(url, resolvedFallbackUrl, fallbackTrack?.id) {
        val list = mutableListOf<String>()

        // 1. Fallback YouTube direct video thumbnail candidates (Guaranteed individual song artwork)
        if (fallbackTrack != null && !fallbackTrack.id.startsWith("sp_") && fallbackTrack.id.length in 8..15) {
            val vid = fallbackTrack.id
            list.add("https://i.ytimg.com/vi/$vid/hq720.jpg")
            list.add("https://i.ytimg.com/vi/$vid/maxresdefault.jpg")
            list.add("https://i.ytimg.com/vi/$vid/mqdefault.jpg")
            list.add("https://i.ytimg.com/vi/$vid/hqdefault.jpg")
        }

        // 2. High-res optimized version of primary URL
        if (!url.isNullOrBlank()) {
            getHighResArtworkUrl(url)?.let { list.add(it) }
            if (url !in list) list.add(url)
        }

        // 3. If resolved from ArtworkResolver
        if (!resolvedFallbackUrl.isNullOrBlank()) {
            getHighResArtworkUrl(resolvedFallbackUrl)?.let { list.add(it) }
            if (resolvedFallbackUrl !in list) list.add(resolvedFallbackUrl!!)
        }

        // 4. Fallback track's existing thumbnail
        if (fallbackTrack != null && !fallbackTrack.thumbnail.isNullOrBlank() && fallbackTrack.thumbnail !in list) {
            list.add(fallbackTrack.thumbnail)
        }

        list.distinct()
    }

    var currentCandidateIndex by remember(candidateUrls) { mutableIntStateOf(0) }
    var isImageLoaded by remember(candidateUrls) { mutableStateOf(false) }
    var isError by remember(candidateUrls) { mutableStateOf(false) }

    val activeUrl = candidateUrls.getOrNull(currentCandidateIndex)

    if (activeUrl.isNullOrBlank() && (isError || candidateUrls.isEmpty())) {
        Box(
            modifier = modifier
                .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape) else Modifier)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
        return
    }

    val request = remember(activeUrl) {
        ImageRequest.Builder(context)
            .data(activeUrl)
            .crossfade(200)
            .build()
    }

    Box(
        modifier = modifier
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape) else Modifier)
            .clip(shape)
            .background(if (isImageLoaded) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant)
            .then(if (!isImageLoaded && !isError) Modifier.background(shimmerBrush) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (!activeUrl.isNullOrBlank()) {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Success -> {
                            isImageLoaded = true
                            isError = false
                        }
                        is AsyncImagePainter.State.Error -> {
                            if (currentCandidateIndex < candidateUrls.size - 1) {
                                currentCandidateIndex++
                            } else {
                                isError = true
                                isImageLoaded = false
                            }
                        }
                        is AsyncImagePainter.State.Loading -> {
                            isImageLoaded = false
                            isError = false
                        }
                        else -> Unit
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isError && !isImageLoaded) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}
