package com.auralis.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auralis.music.ui.theme.LocalReducedMotion

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.auralis.music.data.network.ArtworkResolver
import com.auralis.music.domain.model.Track
import com.auralis.music.util.MasterArtworkResolver

/**
 * Upgrades thumbnail URLs to uncompressed studio master 1200x1200 HD / 720p artwork,
 * eliminating letterbox bars and low-res artifacts completely.
 */
fun getHighResArtworkUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    var cleaned = url.trim()
    if (cleaned.startsWith("//")) cleaned = "https:$cleaned"

    // YouTube Music & Google User Content (yt3.googleusercontent.com, lh3.googleusercontent.com, ggpht.com):
    // Upgrade from low-res thumbnail dimensions (=w60, =w120, =w544, =s120) to full studio 1200x1200 uncompressed album art
    if (cleaned.contains("googleusercontent.com") || cleaned.contains("ggpht.com")) {
        cleaned = cleaned.replace(Regex("""=w\d+-h\d+.*"""), "=w1200-h1200-l90-rj")
            .replace(Regex("""=s\d+.*"""), "=s1200-c")
    }
    // YouTube video thumbnail: upgrade 480x360 hqdefault to 1280x720 HD hq720
    if (cleaned.contains("i.ytimg.com") || cleaned.contains("img.youtube.com")) {
        cleaned = cleaned.replace("hqdefault.jpg", "hq720.jpg")
            .replace("mqdefault.jpg", "hq720.jpg")
            .replace("sddefault.jpg", "hq720.jpg")
            .replace("default.jpg", "hq720.jpg")
    }
    // iTunes / Apple Music artwork: 100x100bb -> 1400x1400bb
    if (cleaned.contains("mzstatic.com")) {
        cleaned = cleaned.replace(Regex("""\d+x\d+bb"""), "1400x1400bb")
    }
    // Spotify artwork:
    if (cleaned.contains("i.scdn.co/image/ab67616d00004851") || cleaned.contains("i.scdn.co/image/ab67616d00001e02")) {
        cleaned = cleaned.replace("ab67616d00004851", "ab67616d0000b273")
            .replace("ab67616d00001e02", "ab67616d0000b273")
    }
    return cleaned
}

@Composable
fun ArtworkCard(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    elevation: Dp = 4.dp,
    contentDescription: String? = null,
    fallbackTrack: Track? = null,
    contentScale: ContentScale = ContentScale.Crop
) {
    var effectiveUrl by remember(url) { mutableStateOf(url) }

    // Auto-resolve missing or video-still artwork on the fly
    LaunchedEffect(url, fallbackTrack?.id) {
        val targetTrack = fallbackTrack
        if (targetTrack != null) {
            val cached = ArtworkResolver.getArtwork(targetTrack)
            if (!cached.isNullOrBlank()) {
                effectiveUrl = cached
            } else if (url.isNullOrBlank() || url.contains("i.ytimg.com") || url.contains("img.youtube.com")) {
                val master = MasterArtworkResolver.resolveMasterArtworkUrl(
                    targetTrack.title,
                    targetTrack.artist,
                    url
                )
                if (!master.isNullOrBlank()) {
                    effectiveUrl = master
                    ArtworkResolver.cacheArtwork(targetTrack, master)
                } else {
                    effectiveUrl = url
                }
            } else {
                effectiveUrl = url
            }
        } else {
            effectiveUrl = url
        }
    }

    if (effectiveUrl.isNullOrBlank()) {
        Box(
            modifier = modifier
                .shadow(elevation, RoundedCornerShape(cornerRadius))
                .clip(RoundedCornerShape(cornerRadius))
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
    val reducedMotion = LocalReducedMotion.current

    val highRes = getHighResArtworkUrl(effectiveUrl)
    var imageModel by remember(effectiveUrl) { mutableStateOf<Any?>(highRes ?: effectiveUrl) }

    val request = remember(imageModel, reducedMotion) {
        ImageRequest.Builder(context)
            .data(imageModel)
            .listener(
                onError = { _, _ ->
                    // Graceful failover: if upgraded high-res 404s, revert to original URL
                    if (imageModel == highRes && highRes != effectiveUrl && !effectiveUrl.isNullOrBlank()) {
                        imageModel = effectiveUrl
                    }
                }
            )
            .crossfade(!reducedMotion)
            .build()
    }

    Box(
        modifier = modifier
            .shadow(elevation, RoundedCornerShape(cornerRadius))
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize()
        )
    }
}
