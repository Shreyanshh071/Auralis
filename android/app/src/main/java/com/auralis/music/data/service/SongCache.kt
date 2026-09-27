package com.auralis.music.data.service

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import com.auralis.music.data.datastore.StorageDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.TreeSet

/**
 * The on-disk song cache behind Settings > Storage > "Song cache".
 *
 * The setting existed but nothing was ever cached (the player streamed straight from the network),
 * so the size always read 0 B. Played audio is now kept in `cacheDir/exoplayer`, bounded by
 * "Max song cache size", least recently played evicted first. Downloads (file://) never pass
 * through it.
 *
 * YouTube stream URLs are signed and change on every resolve, so entries are keyed by the song
 * and exact audio file ([keyFor]) instead of by URL; otherwise nothing would ever be reused.
 */
@UnstableApi
object SongCache {
    private const val DIR_NAME = "exoplayer"

    @Volatile var enabled: Boolean = true
        private set
    private val evictor = ResizableLruEvictor(1100L * 1024 * 1024)
    @Volatile private var cache: SimpleCache? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.cacheDir, DIR_NAME),
            evictor,
            StandaloneDatabaseProvider(context.applicationContext)
        ).also { cache = it }
    }

    /** Follows the Storage settings: the on/off switch and the size limit apply immediately. */
    fun observeSettings(context: Context) {
        scope.launch {
            StorageDataStore(context.applicationContext).settingsFlow.collect { s ->
                enabled = s.songCacheEnabled
                evictor.setMaxBytes(s.maxSongCacheSizeMb.toLong() * 1024 * 1024, get(context))
            }
        }
    }

    /**
     * Wraps the network source. While the cache is on, audio is read from disk when present and
     * written as it streams; while off, playback goes straight to the network.
     */
    fun dataSourceFactory(context: Context, upstream: DataSource.Factory): DataSource.Factory {
        val cached = CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory { if (enabled) cached.createDataSource() else upstream.createDataSource() }
    }

    /** Stable key for one song's audio file: the video, plus which format and exact file. */
    fun keyFor(videoId: String, url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val itag = uri?.getQueryParameter("itag")
        val clen = uri?.getQueryParameter("clen")
        return if (itag != null) "$videoId|itag=$itag|clen=${clen.orEmpty()}"
        else "$videoId|${uri?.host.orEmpty()}${uri?.path.orEmpty()}"
    }

    fun sizeBytes(context: Context): Long = runCatching { get(context).cacheSpace }.getOrDefault(0L)

    /** Removes every cached song. Returns the bytes freed. */
    fun clear(context: Context): Long {
        val c = get(context)
        val before = c.cacheSpace
        for (key in c.keys.toList()) runCatching { c.removeResource(key) }
        return before - c.cacheSpace
    }
}

/** Least-recently-used eviction whose limit can change while the cache is open. */
@UnstableApi
private class ResizableLruEvictor(@Volatile private var maxBytes: Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan> { a, b ->
        if (a.lastTouchTimestamp != b.lastTouchTimestamp) a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
        else a.compareTo(b)
    }
    private var currentBytes = 0L

    /** Takes the cache's lock first, the same order SimpleCache uses when it calls us. */
    fun setMaxBytes(bytes: Long, cache: Cache) {
        synchronized(cache) {
            synchronized(this) {
                maxBytes = bytes
                evict(cache, 0L)
            }
        }
    }

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != androidx.media3.common.C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    @Synchronized
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        currentBytes += span.length
        evict(cache, 0L)
    }

    @Synchronized
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        currentBytes -= span.length
    }

    @Synchronized
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    @Synchronized
    private fun evict(cache: Cache, requiredBytes: Long) {
        while (currentBytes + requiredBytes > maxBytes && spans.isNotEmpty()) {
            cache.removeSpan(spans.first())
        }
    }
}
