package com.auralis.music.windows.library

import com.auralis.music.domain.model.Track
import com.auralis.music.ui.library.getDistinctArtworkTracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.security.MessageDigest
import okhttp3.Request

/** Reusable local artwork for cards/backgrounds that need one URL rather than a composable. */
internal suspend fun playlistCollage(tracks: List<Track>): String? = withContext(Dispatchers.IO) {
    val urls = getDistinctArtworkTracks(tracks).mapNotNull { it.thumbnail.takeIf(String::isNotBlank) }.distinct().take(4)
    if (urls.size < 4) return@withContext urls.firstOrNull()
    val key = MessageDigest.getInstance("SHA-256").digest(urls.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }
    val target = File(com.auralis.music.util.desktopAppDataDir(), "artwork-cache/playlist-collages/$key.png")
    if (target.isFile) return@withContext target.absolutePath
    val images = urls.map { url ->
        runCatching {
            when {
                File(url).isFile -> ImageIO.read(File(url))
                url.startsWith("file:") -> ImageIO.read(File(java.net.URI(url)))
                else -> com.auralis.music.data.network.NetworkClientProvider.okHttpClient.newBuilder()
                    .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build().newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (response.isSuccessful) response.body?.byteStream()?.use(ImageIO::read) else null
                    }
            }
        }.getOrNull()
    }
    if (images.any { it == null }) return@withContext null
    val image = BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
        setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        images.forEachIndexed { i, cover ->
            val size = minOf(cover!!.width, cover.height)
            val x = (cover.width - size) / 2; val y = (cover.height - size) / 2
            drawImage(cover, (i % 2) * 300, (i / 2) * 300, (i % 2 + 1) * 300, (i / 2 + 1) * 300, x, y, x + size, y + size, null)
        }
        dispose()
    }
    target.parentFile.mkdirs()
    // Concurrent Home/library requests can render the same collage; publish atomically.
    val temp = File.createTempFile("collage-", ".png", target.parentFile)
    try {
        ImageIO.write(image, "png", temp)
        java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally { temp.delete() }
    target.absolutePath
}
