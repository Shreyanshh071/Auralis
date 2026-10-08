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
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.auralis.music.ui.i18n.AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        com.auralis.music.ui.i18n.AppLanguage.init(this)
        // Shared network code (hl/gl, proxy) reads these; the same calls it made when it lived here.
        com.auralis.music.data.network.ContentSettingsSource.current = { com.auralis.music.data.datastore.ContentSettingsStore.value }
        com.auralis.music.data.network.ContentLocale.appLanguageTag = { com.auralis.music.ui.i18n.AppLanguage.currentTagOrBlank() }
        // First: network code reads hl/gl, result filters and the proxy from here on every request.
        com.auralis.music.data.datastore.ContentSettingsStore.init(this)
        com.auralis.music.data.network.ContentProxy.install()
        // Before any playback or download worker: age-restricted songs need the YouTube sign-in.
        com.auralis.music.data.network.YouTubeSession.init(this)
        com.auralis.music.data.network.SpotifySession.init(this)
        com.auralis.music.data.sync.LocalDataOwner.init(this)
        com.auralis.music.data.network.provider.AmllLyricsSource.indexCacheDir = cacheDir
        // zemer-cipher logs through Timber; keep its lines in logcat for diagnosing downloads.
        if (timber.log.Timber.treeCount == 0) timber.log.Timber.plant(timber.log.Timber.DebugTree())
        com.zemer.cipher.ZemerCipher.initialize(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                com.auralis.music.data.network.AudioStreamResolver.init(this@AuralisApplication)
                com.auralis.music.data.download.AuralisDownloadManager.init(this@AuralisApplication)
                com.auralis.music.data.download.PlaylistDownloadCoordinator.init(this@AuralisApplication)
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
