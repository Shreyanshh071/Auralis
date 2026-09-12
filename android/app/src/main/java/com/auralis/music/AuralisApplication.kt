package com.auralis.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import kotlinx.coroutines.launch

/**
 * Custom Application class that configures a high-performance Coil ImageLoader
 * with hardware bitmap decoding, large RAM LRU cache (30% of app memory),
 * and 300MB disk cache to ensure 60-120fps ultra-smooth scrolling.
 */
class AuralisApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                com.auralis.music.data.network.AudioStreamResolver.init(this@AuralisApplication)
                com.auralis.music.data.download.AuralisDownloadManager.init(this@AuralisApplication)
                com.auralis.music.service.AuralisFirebaseMessagingService.subscribeToUpdateTopics()
                com.auralis.music.service.AppUpdateWorker.schedulePeriodicCheck(this@AuralisApplication)
            } catch (e: Exception) {
                android.util.Log.w("AuralisApp", "Background initialization error: ${e.message}")
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.30)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_image_cache"))
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .allowHardware(true)
            .networkCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }
}
