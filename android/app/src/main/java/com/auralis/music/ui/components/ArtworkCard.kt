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

    // YouTube Music & Google User Content:
    if (cleaned.contains("googleusercontent.com") || cleaned.contains("ggpht.com")) {
        cleaned = cleaned.replace(GOOGLE_W_REGEX, "=w600-h600-l90-rj")
            .replace(GOOGLE_S_REGEX, "=s600-c")
    }
    // YouTube video thumbnail:
    if (cleaned.contains("i.ytimg.com") || cleaned.contains("img.youtube.com")) {
        val noQuery = cleaned.substringBefore('?')
        cleaned = noQuery.replace("hqdefault.jpg", "hq720.jpg")
            .replace("mqdefault.jpg", "hq720.jpg")
            .replace("sddefault.jpg", "hq720.jpg")
            .replace("default.jpg", "hq720.jpg")
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
    var isImageLoaded by remember(url) { mutableStateOf(false) }

    if (url.isNullOrBlank()) {
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

    val context = LocalContext.current
    val highRes = remember(url) { getHighResArtworkUrl(url) }

    val request = remember(highRes, url) {
        ImageRequest.Builder(context)
            .data(highRes ?: url)
            .crossfade(200)
            .build()
    }

    Box(
        modifier = modifier
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape) else Modifier)
            .clip(shape)
            .background(if (isImageLoaded) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant)
            .then(if (!isImageLoaded) Modifier.background(shimmerBrush) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = contentScale,
            onState = { state ->
                isImageLoaded = state is AsyncImagePainter.State.Success
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
