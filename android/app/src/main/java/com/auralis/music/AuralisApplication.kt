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
        com.auralis.music.data.datastore.OnboardingStore.init(this,
            try { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.isAnonymous == false } catch (_: Exception) { false })
        // Before any screen: Home, Library and Explore read the network state from here.
        com.auralis.music.data.network.ConnectivityMonitor.start(this)
        // Shared network code (hl/gl, proxy) reads these; the same calls it made when it lived here.
        com.auralis.music.data.network.ContentSettingsSource.current = { com.auralis.music.data.datastore.ContentSettingsStore.value }
        com.auralis.music.data.network.ContentLocale.appLanguageTag = { com.auralis.music.ui.i18n.AppLanguage.currentTagOrBlank() }
        // First: network code reads hl/gl, result filters and the proxy from here on every request.
        com.auralis.music.data.datastore.ContentSettingsStore.init(this)
        com.auralis.music.data.network.ContentProxy.install()
        // Before any playback or download worker: age-restricted songs need the YouTube sign-in.
        com.auralis.music.data.network.YouTubeSession.init(
            com.auralis.music.util.SharedPreferencesKeyValueStore(
                applicationContext.getSharedPreferences(com.auralis.music.data.network.YouTubeSession.PREFS_NAME, MODE_PRIVATE)
            )
        )
        com.auralis.music.data.network.SpotifySession.init(this)
        com.auralis.music.data.datastore.CatalogSettingsStore.init(this)
        // Album lookups and release labels from earlier searches, so a song's album or Single card
        // appears with the first results (its own small file; the database is untouched).
        val searchLookups = com.auralis.music.util.SharedPreferencesKeyValueStore(getSharedPreferences("auralis_album_lookups", MODE_PRIVATE))
        com.auralis.music.data.network.AlbumMetadataResolver.persistTo(searchLookups)
        com.auralis.music.data.network.InnerTubeClient.persistReleaseTypesTo(searchLookups)
        com.auralis.music.data.sync.LocalDataOwner.init(this)
        com.auralis.music.data.network.provider.AmllLyricsSource.indexCacheDir = cacheDir
        // zemer-cipher logs through Timber; keep its lines in logcat for diagnosing downloads.
        if (timber.log.Timber.treeCount == 0) timber.log.Timber.plant(timber.log.Timber.DebugTree())
        com.zemer.cipher.ZemerCipher.initialize(this)
        // Auralis Connect follows the signed-in account (it registers this phone; it never plays).
        com.auralis.music.data.connect.AuralisConnect.init(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(1000L)
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
            .okHttpClient {
                okhttp3.OkHttpClient.Builder()
                    // Wikimedia answers 403 to the default client name, which left artist photos blank.
                    .addInterceptor { chain ->
                        val request = chain.request()
                        chain.proceed(
                            if (request.url.host.endsWith("wikimedia.org")) {
                                request.newBuilder().header("User-Agent", "AuralisMusicApp/1.0 (contact@auralis.app)").build()
                            } else request
                        )
                    }
                    .build()
            }
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
