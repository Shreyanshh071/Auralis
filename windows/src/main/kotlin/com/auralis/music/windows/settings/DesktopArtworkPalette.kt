package com.auralis.music.windows.settings

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.auralis.music.ui.theme.ArtworkPalette
import com.auralis.music.ui.theme.DefaultArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import okhttp3.Request

private val paletteFlow = MutableStateFlow(DefaultArtworkPalette)

@Composable
fun rememberArtworkPalette(url: String?): ArtworkPalette {
    var palette by remember { mutableStateOf(DefaultArtworkPalette) }
    LaunchedEffect(url) {
        com.auralis.music.ui.theme.ArtworkPaletteSource.current = paletteFlow
        palette = DefaultArtworkPalette
        paletteFlow.value = palette
        if (!url.isNullOrBlank()) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val thumbnail = com.auralis.music.ui.components.getOptimizedThumbnailUrl(url) ?: url
                    com.auralis.music.data.network.NetworkClientProvider.okHttpClient
                        .newCall(Request.Builder().url(thumbnail).build()).execute().use { response ->
                            check(response.isSuccessful)
                            val image = response.body?.byteStream()?.use(ImageIO::read)
                            image?.let(::extractArtworkPalette) ?: DefaultArtworkPalette
                        }
                }.getOrDefault(DefaultArtworkPalette)
            }
            palette = result
            paletteFlow.value = result
        }
    }
    return palette
}

/** Sample and quantize the cover; ignore transparent pixels and near-white/black borders. */
internal fun extractArtworkPalette(image: BufferedImage): ArtworkPalette {
    val counts = mutableMapOf<Int, Int>()
    for (y in 0 until image.height step (image.height / 64).coerceAtLeast(1)) {
        for (x in 0 until image.width step (image.width / 64).coerceAtLeast(1)) {
            val argb = image.getRGB(x, y)
            if ((argb ushr 24) < 128) continue
            val r = (argb ushr 16) and 255; val g = (argb ushr 8) and 255; val b = argb and 255
            val hsv = java.awt.Color.RGBtoHSB(r, g, b, null)
            if (hsv[2] < 0.08f || hsv[2] > 0.96f && hsv[1] < 0.08f) continue
            val bucket = ((r / 16) shl 8) or ((g / 16) shl 4) or (b / 16)
            counts[bucket] = (counts[bucket] ?: 0) + 1
        }
    }
    if (counts.isEmpty()) return DefaultArtworkPalette
    val colors = counts.entries.sortedByDescending { (bucket, count) ->
        val hsv = java.awt.Color.RGBtoHSB(((bucket ushr 8) and 15) * 16, ((bucket ushr 4) and 15) * 16, (bucket and 15) * 16, null)
        count * (0.25f + hsv[1])
    }.take(6).map { (bucket, _) ->
        Color(0xFF000000.toInt() or ((((bucket ushr 8) and 15) * 16 + 8) shl 16) or ((((bucket ushr 4) and 15) * 16 + 8) shl 8) or ((bucket and 15) * 16 + 8))
    }
    val primary = colors.first()
    return ArtworkPalette(primary, colors.getOrElse(1) { primary }, colors.getOrElse(2) { primary }, glowColors = colors)
}
