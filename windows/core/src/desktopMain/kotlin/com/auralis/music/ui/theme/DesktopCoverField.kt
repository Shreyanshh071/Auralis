package com.auralis.music.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.components.LocalArtworkRenderer
import com.auralis.music.ui.components.getHighResArtworkUrl

/** Desktop's cover-tinted backdrop for option sheets: the cover blurred under a dark floor. */
@Composable
internal fun DesktopCoverField(artworkUrl: String?) {
    Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
        if (!artworkUrl.isNullOrBlank()) {
            LocalArtworkRenderer.current.Image(
                url = getHighResArtworkUrl(artworkUrl) ?: artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                crossfade = true,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }.blur(80.dp)
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
    }
}
