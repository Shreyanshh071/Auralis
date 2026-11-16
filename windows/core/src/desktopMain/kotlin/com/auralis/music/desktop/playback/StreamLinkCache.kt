package com.auralis.music.desktop.playback

import com.auralis.music.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolved stream links, in memory and on disk, scoped by video, profile (quality and enabled
 * clients) and route (proxy and local network). A link from another quality, client set or
 * network is never handed out. Disk entries are versioned, bounded, kept only while comfortably
 * before their expiry, written atomically off the caller's thread, and contain only the signed
 * media URL and its format metadata: never cookies, visitor data or tokens.
 */
class StreamLinkCache(
    private val file: () -> File?,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 200,
    private val writer: Executor = defaultWriter
) {
    private data class Key(val videoId: String, val profile: String, val route: String) {
        fun encode() = "$videoId|$profile|$route"
        companion object {
            fun decode(text: String): Key? = text.split('|').takeIf { it.size == 3 && it[0].isNotEmpty() }?.let { Key(it[0], it[1], it[2]) }
        }
    }

    private val entries = ConcurrentHashMap<Key, PlaybackResource>()
    @Volatile private var loaded = false
    private val saveQueued = AtomicBoolean(false)

    private fun usable(resource: PlaybackResource, margin: Long) = now() < resource.expiresAtWallMs - margin

    fun get(videoId: String, profile: String, route: String): PlaybackResource? {
        ensureLoaded()
        val key = Key(videoId, profile, route)
        val entry = entries[key] ?: return null
        val margin = if (entry.fromDisk) DISK_MARGIN_MS else MEMORY_MARGIN_MS
        if (!usable(entry, margin)) { entries.remove(key, entry); return null }
        return entry
    }

    fun put(resource: PlaybackResource) {
        ensureLoaded()
        // Links without an expiry are trusted for an hour at most.
        val bounded = resource.copy(expiresAtWallMs = minOf(resource.expiresAtWallMs, now() + MAX_LIFETIME_MS), fromDisk = false)
        entries[Key(resource.videoId, resource.profile, resource.route)] = bounded
        scheduleSave()
    }

    /** Forgets [videoId] under every profile and route (a refused link must not be reloaded). */
    fun remove(videoId: String): Boolean {
        ensureLoaded()
        val removed = entries.keys.filter { it.videoId == videoId }.count { entries.remove(it) != null }
        if (removed > 0) scheduleSave()
        return removed > 0
    }

    /** Forgets [url] only if it is still the entry for [videoId] (a newer link stays). */
    fun removeIfUrl(videoId: String, url: String): Boolean {
        ensureLoaded()
        val removed = entries.entries.filter { it.key.videoId == videoId && it.value.url == url }.count { entries.remove(it.key, it.value) }
        if (removed > 0) scheduleSave()
        return removed > 0
    }

    fun clear() {
        loaded = true
        entries.clear()
        scheduleSave()
    }

    val size: Int get() { ensureLoaded(); return entries.size }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            load()
        }
    }

    private fun load() {
        val f = runCatching { file() }.getOrNull()?.takeIf { it.isFile } ?: return
        val props = runCatching { java.util.Properties().apply { f.inputStream().use { load(it) } } }.getOrElse {
            Log.w(TAG, "Discarding unreadable stream-link cache: ${it.message}")
            runCatching { f.delete() }
            return
        }
        if (props.getProperty(VERSION_KEY) != VERSION.toString()) {
            // An older format (keyed by video alone, no profile or network): not safe to reuse.
            Log.i(TAG, "Discarding stream-link cache from an older format")
            runCatching { f.delete() }
            return
        }
        var kept = 0
        for (name in props.stringPropertyNames()) {
            if (name == VERSION_KEY) continue
            val key = Key.decode(name) ?: continue
            val parts = props.getProperty(name).split('|', limit = 6)
            if (parts.size != 6) continue
            val expires = parts[0].toLongOrNull() ?: continue
            val url = parts[5].takeIf { it.startsWith("https://") } ?: continue
            val resource = PlaybackResource(key.videoId, url, expires, parts[1].toIntOrNull(), parts[2].ifEmpty { null },
                parts[3].toLongOrNull(), parts[4].ifEmpty { null }, key.profile, key.route, fromDisk = true)
            if (usable(resource, DISK_MARGIN_MS)) { entries.putIfAbsent(key, resource); kept++ }
        }
        Log.d(TAG, "Loaded $kept saved stream links")
    }

    private fun scheduleSave() {
        if (runCatching { file() }.getOrNull() == null) return
        if (!saveQueued.compareAndSet(false, true)) return
        writer.execute {
            saveQueued.set(false)
            save()
        }
    }

    /** Writes the cache now (tests, and the writer thread). */
    fun save() {
        val f = runCatching { file() }.getOrNull() ?: return
        synchronized(this) {
            runCatching {
                val time = now()
                val props = java.util.Properties()
                props.setProperty(VERSION_KEY, VERSION.toString())
                entries.entries.filter { time < it.value.expiresAtWallMs - DISK_MARGIN_MS }
                    .sortedByDescending { it.value.expiresAtWallMs }.take(maxEntries)
                    .forEach { (key, r) ->
                        props.setProperty(key.encode(), listOf(r.expiresAtWallMs, r.itag ?: "", r.mimeType.orEmpty(),
                            r.contentLength ?: "", r.client.orEmpty(), r.url).joinToString("|"))
                    }
                f.parentFile?.mkdirs()
                val temp = File(f.parentFile, f.name + ".tmp")
                temp.outputStream().use { props.store(it, null) }
                java.nio.file.Files.move(temp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }.onFailure { Log.w(TAG, "Couldn't save stream links: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "StreamLinkCache"
        const val VERSION = 2
        private const val VERSION_KEY = "__version"
        const val MEMORY_MARGIN_MS = 60_000L
        /** Links from an earlier run must have a comfortable lifetime left. */
        const val DISK_MARGIN_MS = 5 * 60_000L
        const val MAX_LIFETIME_MS = 6 * 3600_000L
        private val defaultWriter: Executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "stream-link-cache").apply { isDaemon = true }
        }
    }
}

/**
 * A fingerprint of how this PC reaches YouTube: the configured proxy (never its credentials) and
 * the local network addresses. Stream links are bound to the address they were issued to, so a
 * change here (another Wi-Fi, a VPN, a proxy toggle) makes cached links from before unusable. It
 * can't see a public IP change behind the same local network; links then fail their first read
 * and are refreshed once.
 */
object RouteScope {
    @Volatile private var cached: Pair<Long, String>? = null
    private const val TTL_MS = 10_000L

    fun current(): String {
        val now = System.currentTimeMillis()
        cached?.let { (at, value) -> if (now - at < TTL_MS) return value }
        val value = compute()
        cached = now to value
        return value
    }

    /** Forget the remembered fingerprint (after a known network or proxy change). */
    fun invalidate() { cached = null }

    private fun compute(): String {
        val proxy = runCatching {
            com.auralis.music.data.network.ContentProxy.configuredProxy(com.auralis.music.data.network.ContentSettingsSource.current())
                ?.let { "${it.type()}:${it.address()}" }
        }.getOrNull() ?: "direct"
        val addresses = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
                .flatMap { nic -> nic.inetAddresses.toList().filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }.map { it.hostAddress } }
                .sorted()
        }.getOrDefault(emptyList())
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("$proxy|${addresses.joinToString(",")}".toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
