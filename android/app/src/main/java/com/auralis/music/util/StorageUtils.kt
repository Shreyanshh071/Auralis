package com.auralis.music.util

import android.content.Context
import coil.Coil
import com.auralis.music.data.download.AuralisDownloadManager
import com.auralis.music.data.network.AudioStreamResolver
import java.io.File
import java.text.DecimalFormat

object StorageUtils {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        val df = if (digitGroups == 0) DecimalFormat("#") else DecimalFormat("#.#")
        return "${df.format(value)} ${units[digitGroups]}"
    }

    fun getFolderSizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        try {
            dir.walkTopDown().forEach { file ->
                if (file.isFile) total += file.length()
            }
        } catch (_: Exception) {}
        return total
    }

    fun clearDirectory(dir: File): Long {
        if (!dir.exists()) return 0L
        val initialSize = getFolderSizeBytes(dir)
        try {
            dir.listFiles()?.forEach { file ->
                file.deleteRecursively()
            }
        } catch (_: Exception) {}
        return initialSize
    }

    fun getSongCacheSizeBytes(context: Context): Long {
        val cacheDirs = listOf(
            File(context.cacheDir, "exoplayer"),
            File(context.cacheDir, "media"),
            File(context.cacheDir, "newpipe"),
            File(context.cacheDir, "youtube_cache"),
            File(context.cacheDir, "http_cache"),
            File(context.cacheDir, "audio_cache")
        )
        return cacheDirs.sumOf { getFolderSizeBytes(it) }
    }

    fun clearSongCache(context: Context): Long {
        val cacheDirs = listOf(
            File(context.cacheDir, "exoplayer"),
            File(context.cacheDir, "media"),
            File(context.cacheDir, "newpipe"),
            File(context.cacheDir, "youtube_cache"),
            File(context.cacheDir, "http_cache"),
            File(context.cacheDir, "audio_cache")
        )
        val freed = cacheDirs.sumOf { clearDirectory(it) }
        AudioStreamResolver.clearCache()
        return freed
    }

    fun getImageCacheSizeBytes(context: Context): Long {
        var total = 0L
        val imageDirs = listOf(
            File(context.cacheDir, "image_cache"),
            File(context.cacheDir, "coil"),
            File(context.cacheDir, "image_manager_disk_cache")
        )
        total += imageDirs.sumOf { getFolderSizeBytes(it) }
        try {
            val diskCacheSize = Coil.imageLoader(context).diskCache?.size ?: 0L
            if (diskCacheSize > total) total = diskCacheSize
        } catch (_: Throwable) {}
        return total
    }

    fun clearImageCache(context: Context): Long {
        val imageDirs = listOf(
            File(context.cacheDir, "image_cache"),
            File(context.cacheDir, "coil"),
            File(context.cacheDir, "image_manager_disk_cache")
        )
        val freed = imageDirs.sumOf { clearDirectory(it) }
        try {
            Coil.imageLoader(context).diskCache?.clear()
            Coil.imageLoader(context).memoryCache?.clear()
        } catch (_: Throwable) {}
        return freed
    }

    fun getDownloadedSongsSizeBytes(): Long {
        return AuralisDownloadManager.getTotalDownloadSizeBytes()
    }
}
