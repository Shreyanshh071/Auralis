package com.auralis.music.windows.settings

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.request.crossfadeMillis
import com.auralis.music.util.desktopAppDataDir
import okio.Path.Companion.toOkioPath
import java.io.File

object DesktopCaches {
    private var loader: ImageLoader? = null
    fun install(maxImageMb: Int = 512) {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                // A quarter of the heap: a long playlist's covers stay decoded when scrolling back.
                .memoryCache { MemoryCache.Builder().maxSizePercent(context, .25).build() }
                .diskCache {
                    DiskCache.Builder().directory(File(desktopAppDataDir(), "artwork-cache").toOkioPath())
                        .maxSizeBytes(maxImageMb.coerceIn(128, 2048) * 1024L * 1024L).build()
                }
                // Coil's own OkHttp client downloads at most 5 images per server at once, and
                // almost every cover comes from one or two Google servers: a screenful of covers
                // queued behind each other and sat empty for seconds.
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageClient })); add(FadeOnlyDownloads) }
                .build().also { loader = it }
        }
    }

    /**
     * Only a cover downloaded just now fades in. One read back from the disk cache appears at once:
     * fading every one in from the empty tile flashed the tiles dark on each scroll back.
     */
    private object FadeOnlyDownloads : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val result = chain.proceed()
            return if (result is SuccessResult && result.dataSource == DataSource.DISK && result.request.crossfadeMillis > 0)
                result.copy(request = result.request.newBuilder().crossfade(false).build())
            else result
        }
    }

    private val imageClient by lazy {
        com.auralis.music.data.network.NetworkClientProvider.okHttpClient.newBuilder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 24 })
            .build()
    }
    fun imageBytes(): Long = loader?.diskCache?.size ?: 0
    fun clearImages() { loader?.memoryCache?.clear(); loader?.diskCache?.clear() }
    fun lyricsBytes(): Long = File(desktopAppDataDir(), "lyrics").listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
    fun clearLyrics() {
        val appDir = desktopAppDataDir().canonicalFile
        val target = File(appDir, "lyrics").canonicalFile
        require(target.parentFile == appDir)
        // Only generated lyric JSON files, never settings, history, library or account files.
        target.listFiles()?.filter { it.isFile && it.extension == "json" && it.canonicalFile.parentFile == target }?.forEach { java.nio.file.Files.delete(it.toPath()) }
    }
}
