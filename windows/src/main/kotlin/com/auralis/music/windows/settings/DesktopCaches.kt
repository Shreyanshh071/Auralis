package com.auralis.music.windows.settings

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import com.auralis.music.util.desktopAppDataDir
import okio.Path.Companion.toOkioPath
import java.io.File

object DesktopCaches {
    private var loader: ImageLoader? = null
    fun install(maxImageMb: Int = 512) {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context).diskCache {
                DiskCache.Builder().directory(File(desktopAppDataDir(), "artwork-cache").toOkioPath())
                    .maxSizeBytes(maxImageMb.coerceIn(128, 2048) * 1024L * 1024L).build()
            }.build().also { loader = it }
        }
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
