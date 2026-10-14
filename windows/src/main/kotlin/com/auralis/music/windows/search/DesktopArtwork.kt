package com.auralis.music.windows.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage

/**
 * Artwork for the shared search UI on Windows: the phone's look (rounded, placeholder note).
 * [highRes] loads the full-size cover for large views; row-sized thumbnails blur when stretched.
 */
@Composable
fun DesktopArtwork(url: String?, modifier: Modifier, cornerRadius: Dp, contentDescription: String?,
    contentScale: ContentScale = ContentScale.Crop, highRes: Boolean = false) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (url.isNullOrBlank()) {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val raw = url.trim().replace(Regex("""(?:hq)+default\.jpg"""), "hqdefault.jpg")
            val source = (if (highRes) com.auralis.music.ui.components.getHighResArtworkUrl(raw)
                else com.auralis.music.ui.components.getOptimizedThumbnailUrl(raw)) ?: raw
            // A fixed decode size: sizing to the first layout decoded covers that start tiny (the
            // Now playing panel animating open) at a few pixels, and they stayed pixelated.
            val context = coil3.compose.LocalPlatformContext.current
            val request = androidx.compose.runtime.remember(source, highRes) {
                coil3.request.ImageRequest.Builder(context).data(source).size(if (highRes) 1200 else 400).build()
            }
            AsyncImage(
                // Sized like the phone's rows: 400x400 album art, 16:9 frames for video thumbnails.
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
