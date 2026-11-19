package com.auralis.music.windows.player

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.SpotifyPlaylistImporter
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.util.desktopAppDataDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Spotify's track video, downloaded as one short, silent video for the existing VLC renderer. */
internal object SpotifyCanvasLookup {
    val accountAvailable: Boolean
        get() = CanvasSettings.prefs.value.spotifyAccount &&
            com.auralis.music.data.network.SpotifySession.signedIn.value

    private val client by lazy {
        SpotifyCanvasClient(NetworkClientProvider.okHttpClient, File(desktopAppDataDir(), "cache/spotify-canvas"),
            accountToken = {
                // Settings > Canvas > "Fetch Canvas with your Spotify account", signed in only.
                if (accountAvailable)
                    com.auralis.music.data.network.SpotifySession.getValidAccessToken() else null
            })
    }

    suspend fun find(trackId: String?): String? = client.find(trackId)
    fun cached(trackId: String): String? = client.cached(trackId)
    suspend fun sharp(trackId: String): String? = client.sharp(trackId)
    suspend fun fallback(trackId: String, failedPath: String): String? = client.fallback(trackId, failedPath)
    fun rememberSongId(songId: String, spotifyId: String) = client.rememberSongId(songId, spotifyId)
    fun songId(songId: String?): String? = client.songId(songId)
}

/** Injectable transport/cache for exercising real provider responses without Spotify or VLC. */
internal class SpotifyCanvasClient(
    private val http: OkHttpClient,
    private val folder: File,
    /** The signed-in user's Spotify web-player token when they allow Canvas lookups with it; null otherwise. */
    private val accountToken: (suspend () -> String?)? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private companion object {
        private const val MAX_BYTES = 64L * 1024 * 1024
        private const val MIN_CACHE_BYTES = 64L * 1024
        // A song SpicyLyrics has no video for is asked again after a few minutes, not a day: its
        // catalogue fills in over time, and a clean install must not stay locked out.
        private const val MISS_RETRY_MS = 300_000L
    }
    private val trackPattern = Regex("[A-Za-z0-9]{22}")
    private val filePattern = Regex("[a-fA-F0-9]{32}")
    private val diskIndex by lazy { PropertiesFileKeyValueStore(File(folder, "index.properties"), synchronous = true) }
    private val misses = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, Long>()
    private val accountRetry = ConcurrentHashMap<String, Long>()
    private val qualityRetries = ConcurrentHashMap<String, Long>()
    // Coalesce requests for the same track without queueing playback behind other songs' downloads.
    private val trackLocks = Array(64) { Mutex() }
    private fun trackLock(trackId: String): Mutex = trackLocks[trackId.hashCode().ushr(1) % trackLocks.size]
    private val retryAt = AtomicLong()
    private val directHttp by lazy {
        http.newBuilder().followRedirects(false).callTimeout(5, TimeUnit.SECONDS).build()
    }
    private val segmentPool = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "canvas-segments").apply { isDaemon = true }
    }
    private val cleanPartials by lazy {
        val cutoff = clock() - 10 * 60_000L
        folder.listFiles { file -> file.name.endsWith(".partial") && file.lastModified() < cutoff }
            ?.forEach { runCatching { it.delete() } }
    }

    /**
     * A song's Spotify track id, once any lyrics source has named it. Kept on disk so the Canvas
     * stays when the lyrics source changes (only some sources carry the id) and after a restart.
     */
    fun rememberSongId(songId: String, spotifyId: String) {
        if (songId.isBlank() || !trackPattern.matches(spotifyId)) return
        if (diskIndex.getString("song.$songId", "") != spotifyId) runCatching { diskIndex.putStrings(mapOf("song.$songId" to spotifyId)) }
    }

    fun songId(songId: String?): String? =
        songId?.takeIf { it.isNotBlank() }?.let { diskIndex.getString("song.$it", "") }?.takeIf(trackPattern::matches)

    fun cached(trackId: String): String? = indexedFileId(trackId)?.let(::cachedFile)?.absolutePath

    suspend fun find(trackId: String?): String? = withContext(Dispatchers.IO) {
        if (trackId == null || !trackPattern.matches(trackId)) return@withContext null
        cached(trackId)?.let { return@withContext it }
        cleanPartials
        trackLock(trackId).withLock {
            val known = indexedFileId(trackId)
            known?.let { cachedFile(it)?.let { file -> return@withLock file.absolutePath } }
            val now = clock()
            if (now < (misses[trackId] ?: 0L) || now < (failures[trackId] ?: 0L)) {
                return@withLock fromAccount(trackId, now)
            }
            // The video was cleared from disk but its id is still known: fetch it again directly.
            // SpicyLyrics can stop listing a canvas it once returned, so it is not asked first, and
            // a SpicyLyrics rate limit (retryAt) does not block this.
            if (known != null) {
                try {
                    (downloadDirect(diskIndex.getString("url.$trackId", "").orEmpty(), known)
                        ?: if (hasManifest(trackId)) downloadViaManifest(trackId, known) else null)
                        ?.let { return@withLock it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Fall through to SpicyLyrics, which may list a newer canvas for the track.
                }
            }
            if (now < retryAt.get()) return@withLock fromAccount(trackId, now)
            // Only SpicyLyrics saying it has no canvas blocks the track for a while; a failed
            // download (no network, a deleted folder) is retried after a minute.
            var noneListed = false
            val viaSpicy: String? = try {
                fromSpicyLyrics(trackId, now) { noneListed = true }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                com.auralis.music.util.Log.w("SpotifyCanvas", "lookup $trackId failed: $error")
                failures[trackId] = now + 60_000L
                null
            }
            if (noneListed) misses[trackId] = now + MISS_RETRY_MS
            viaSpicy ?: fromAccount(trackId, now)
        }
    }

    /** SpicyLyrics' public canvas list; null when it has none, is rate-limiting, or failed. */
    private fun fromSpicyLyrics(trackId: String, now: Long, onNoneListed: () -> Unit): String? {
        val request = Request.Builder()
            .url("https://regional.canvasstore.slnode.spicylyrics.org/provider/canvaslist/$trackId")
            .header("Accept", "application/json").build()
        val item = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                com.auralis.music.util.Log.w("SpotifyCanvas", "lookup $trackId: HTTP ${response.code}")
                val pause = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 1800) ?: 60L
                if (response.code == 429 || response.header("Retry-After") != null)
                    retryAt.updateAndGet { previous -> maxOf(previous, now + pause * 1000) }
                else failures[trackId] = now + 60_000L
                return null
            }
            val data = JSONObject(response.body?.string().orEmpty()).getJSONObject("data")
            check(data.has(trackId))
            data.optJSONObject(trackId)
        }
        check(item?.has("error") != true)
        if (item == null || item.optString("type").let { it.isNotBlank() && it !in setOf("VIDEO", "VIDEO_LOOPING", "VIDEO_LOOPING_RANDOM") }) {
            com.auralis.music.util.Log.i("SpotifyCanvas", "No video canvas for $trackId")
            onNoneListed()
            return null
        }
        val url = item.optString("url")
        val manifestId = item.optString("fileId").takeIf(filePattern::matches)?.lowercase()
        // The public provider can return just url/type/uri. The MP4 URL's hash is a cache
        // identity, not necessarily Spotify's manifest file ID; never require or conflate them.
        val fileId = manifestId ?: directCacheId(url)
        checkNotNull(fileId) { "Canvas response has neither a valid file ID nor a playable URL" }
        // The direct URL is kept too, so a cleared video can be fetched again without SpicyLyrics.
        diskIndex.putStrings(mapOf(trackId to fileId, "url.$trackId" to url,
            "manifest.$trackId" to (manifestId != null).toString()))
        return cachedFile(fileId)?.absolutePath
            ?: downloadDirect(url, fileId)
            ?: if (manifestId != null) downloadViaManifest(trackId, manifestId) else null
    }

    /**
     * Spotify's own Canvas lookup, which only answers a signed-in session, asked with the user's
     * web-player token. Used only when SpicyLyrics has no canvas for the track. The token goes to
     * Spotify and nowhere else.
     */
    private suspend fun fromAccount(trackId: String, now: Long): String? {
        if (now < (accountRetry[trackId] ?: 0L)) return null
        val token = accountToken?.invoke() ?: return null
        accountRetry[trackId] = now + 60_000L
        return try {
            val uri = "spotify:track:$trackId".toByteArray()
            val entity = byteArrayOf(0x0a) + varint(uri.size) + uri
            val body = byteArrayOf(0x0a) + varint(entity.size) + entity
            val response = http.newCall(Request.Builder()
                .url("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/x-protobuf")
                .header("Accept", "application/protobuf")
                .post(body.toRequestBody("application/x-protobuf".toMediaType()))
                .build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null } ?: return null
            val canvas = parseAccountCanvas(response) ?: run {
                com.auralis.music.util.Log.i("SpotifyCanvas", "No Spotify Canvas for $trackId on the account either")
                return null
            }
            diskIndex.putStrings(mapOf(trackId to canvas.fileId, "url.$trackId" to canvas.url,
                "manifest.$trackId" to canvas.realFileId.toString()))
            cachedFile(canvas.fileId)?.absolutePath
                ?: downloadDirect(canvas.url, canvas.fileId)
                ?: if (canvas.realFileId) downloadViaManifest(trackId, canvas.fileId) else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            com.auralis.music.util.Log.w("SpotifyCanvas", "Spotify account Canvas lookup failed: ${e.message}")
            null
        }
    }

    private class AccountCanvas(val fileId: String, val url: String, val realFileId: Boolean)

    /** Reads the first video canvas from Spotify's protobuf reply: its .cnvs.mp4 URL and file id. */
    private fun parseAccountCanvas(bytes: ByteArray): AccountCanvas? {
        for (canvas in protoFields(bytes).filter { it.first == 1 }.mapNotNull { it.second as? ByteArray }) {
            val strings = protoFields(canvas).mapNotNull { (field, value) ->
                (value as? ByteArray)?.let { field to String(it, Charsets.UTF_8) }
            }
            val url = strings.map { it.second }.firstOrNull {
                it.startsWith("https://canvaz.scdn.co/") && it.endsWith(".cnvs.mp4", ignoreCase = true)
            } ?: continue
            val urlId = url.substringAfterLast('/').substringBefore('.')
            val fileId = strings.firstOrNull { (field, value) -> field != 1 && filePattern.matches(value) && value != urlId }?.second
            return when {
                fileId != null -> AccountCanvas(fileId.lowercase(), url, realFileId = true)
                filePattern.matches(urlId) -> AccountCanvas(urlId.lowercase(), url, realFileId = false)
                else -> null
            }
        }
        return null
    }

    /** Top-level protobuf fields: varints as Long, length-delimited as ByteArray; others skipped. */
    private fun protoFields(bytes: ByteArray): List<Pair<Int, Any>> {
        val out = mutableListOf<Pair<Int, Any>>()
        var i = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (i < bytes.size) {
                val b = bytes[i++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) break
            }
            return result
        }
        while (i < bytes.size) {
            val key = readVarint()
            val field = (key shr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out += field to readVarint()
                1 -> i += 8
                2 -> {
                    val length = readVarint().toInt()
                    if (length < 0 || i + length > bytes.size) return out
                    out += field to bytes.copyOfRange(i, i + length)
                    i += length
                }
                5 -> i += 4
                else -> return out
            }
        }
        return out
    }

    private fun varint(value: Int): ByteArray {
        var n = value
        val out = mutableListOf<Byte>()
        while (true) {
            val b = n and 0x7f
            n = n ushr 7
            if (n != 0) out += (b or 0x80).toByte() else { out += b.toByte(); return out.toByteArray() }
        }
    }

    /** Reject the exact candidate used by VLC, then bypass the MP4 on the retry. */
    suspend fun fallback(trackId: String, failedPath: String): String? = withContext(Dispatchers.IO) {
        trackLock(trackId).withLock {
            val fileId = indexedFileId(trackId) ?: return@withLock null
            val mp4 = File(folder, "$fileId-hq.mp4")
            val manifest = File(folder, "$fileId-hq.ts")
            val failed = listOf(mp4, manifest).firstOrNull { it.absolutePath == failedPath } ?: return@withLock null
            // A Windows decoder can still have the file open while it shuts down.
            runCatching { diskIndex.putStrings(mapOf("rejected.${failed.name}" to failed.lastModified().toString())) }
            runCatching { failed.delete() }
            if (failed == manifest) return@withLock mp4.takeIf(::usable)?.absolutePath
            if (!hasManifest(trackId)) return@withLock null
            try {
                downloadViaManifest(trackId, fileId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun indexedFileId(trackId: String): String? =
        diskIndex.getString(trackId, "")?.takeIf(filePattern::matches)

    // Existing indexes stored real manifest IDs before URL-only discovery was supported.
    private fun hasManifest(trackId: String): Boolean = diskIndex.getString("manifest.$trackId", "true") != "false"

    private fun directUri(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
        it.scheme == "https" && it.host == "canvaz.scdn.co" && it.userInfo == null &&
            (it.port == -1 || it.port == 443) && it.path?.endsWith(".cnvs.mp4", ignoreCase = true) == true
    }

    private fun directCacheId(url: String): String? {
        val uri = directUri(url) ?: return null
        val name = uri.path.substringAfterLast('/').substringBefore('.')
        return name.takeIf(filePattern::matches)?.lowercase()
            ?: java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it) }
    }

    private fun usable(file: File): Boolean {
        return file.isFile && file.length() in MIN_CACHE_BYTES..MAX_BYTES &&
            diskIndex.getString("rejected.${file.name}", "") != file.lastModified().toString()
    }

    private fun cachedFile(fileId: String): File? =
        // The MP4 is the provider's default encode; the TS is the highest manifest profile.
        listOf(File(folder, "$fileId-hq.ts"), File(folder, "$fileId-hq.mp4")).firstOrNull(::usable)

    /** Upgrade after the default loop starts, retaining it if the manifest is unavailable. */
    suspend fun sharp(trackId: String): String? = withContext(Dispatchers.IO) {
        if (!trackPattern.matches(trackId)) return@withContext null
        trackLock(trackId).withLock {
            val fileId = indexedFileId(trackId) ?: return@withLock null
            if (!hasManifest(trackId)) return@withLock null
            File(folder, "$fileId-hq.ts").takeIf(::usable)?.let { return@withLock it.absolutePath }
            val now = clock()
            if (now < (qualityRetries[fileId] ?: 0L)) return@withLock null
            try {
                downloadViaManifest(trackId, fileId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Reopening the player or changing its layout must not hammer a failed endpoint.
                qualityRetries[fileId] = now + 60 * 60_000L
                com.auralis.music.util.Log.w("SpotifyCanvas", "Quality upgrade unavailable for $trackId: ${error.message}")
                null
            }
        }
    }

    private fun downloadDirect(url: String, fileId: String): String? {
        if (directUri(url) == null) return null
        val target = File(folder, "$fileId-hq.mp4")
        // The folder may have been deleted while the app runs (clearing the cache by hand).
        folder.mkdirs()
        val temporary = File.createTempFile(fileId, ".mp4.partial", folder)
        try {
            directHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code != 200) return null
                val body = response.body ?: return null
                val length = body.contentLength()
                // Encoded size is not resolution: a legitimate short HD loop can be under 1.5 MB.
                if (length >= 0 && length !in MIN_CACHE_BYTES..MAX_BYTES) return null
                var bytes = 0L
                temporary.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > MAX_BYTES) return null
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (bytes < MIN_CACHE_BYTES || length >= 0 && bytes != length) return null
            }
            publish(temporary, target)
            return target.absolutePath
        } catch (_: java.io.IOException) {
            return null
        } finally {
            temporary.delete()
        }
    }

    private fun publish(temporary: File, target: File) {
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readJson(request: Request): JSONObject = http.newCall(request).execute().use { response ->
        check(response.isSuccessful)
        JSONObject(response.body?.string().orEmpty())
    }

    private fun downloadViaManifest(trackId: String, fileId: String): String {
        val target = File(folder, "$fileId-hq.ts")
        if (usable(target)) return target.absolutePath
        // A short-lived anonymous embed token is sent only to Spotify's manifest endpoint.
        val embed = http.newCall(Request.Builder()
            .url("https://open.spotify.com/embed/track/$trackId").build()).execute().use { response ->
            check(response.isSuccessful)
            response.body?.string().orEmpty()
        }
        val token = checkNotNull(SpotifyPlaylistImporter.extractSessionTokenFromEmbed(embed))
        val manifest = readJson(Request.Builder()
            .url("https://spclient.wg.spotify.com/manifests/v9/json/sources/$fileId/options/supports_drm")
            .header("Authorization", "Bearer $token").build())
        val content = manifest.getJSONArray("contents").getJSONObject(0)
        check(content.optJSONArray("encryption_infos")?.length() == 0)
        val profiles = content.getJSONArray("profiles")
        val profile = (0 until profiles.length()).map { profiles.getJSONObject(it) }
            .filter { it.optString("file_type") == "ts" && it.optInt("video_width") in 1..2160 &&
                it.optInt("video_height") in 1..3840 }
            .maxWithOrNull(compareBy<JSONObject> { it.optLong("video_width") * it.optLong("video_height") }
                .thenBy { it.optLong("video_bitrate") })
        checkNotNull(profile)
        val base = manifest.getJSONArray("base_urls").getString(0)
        val start = content.getLong("start_time_millis")
        val end = content.getLong("end_time_millis")
        val step = content.getLong("segment_length") * 1000
        check(start >= 0 && end - start in 1..15_000 && step in 1..15_000)
        val segment = manifest.getString("segment_template")
        val segmentUrls = (start until end step step).map { time ->
            (base + segment.replace("{{segment_timestamp}}", (time / 1000).toString()))
                .replace("{{profile_id}}", profile.getInt("id").toString()).replace("{{file_type}}", "ts")
                .also { url ->
                    val uri = URI(url)
                    check(uri.scheme == "https" && (uri.host.endsWith(".scdn.co") || uri.host.endsWith(".spotifycdn.com")))
                }
        }
        // Segments are fetched side by side: each request waits ~0.5s on its own, so one after
        // another made a cleared Canvas take several seconds to come back.
        val segments = segmentUrls.map { url ->
            segmentPool.submit<ByteArray> {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    check(response.isSuccessful)
                    val bytes = checkNotNull(response.body).bytes()
                    check(bytes.size <= MAX_BYTES)
                    bytes
                }
            }
        }
        folder.mkdirs()
        val temporary = File.createTempFile(fileId, ".partial", folder)
        try {
            temporary.outputStream().use { output ->
                var bytes = 0L
                for (part in segments) {
                    val data = part.get(30, TimeUnit.SECONDS)
                    bytes += data.size
                    check(bytes <= MAX_BYTES)
                    output.write(data)
                }
                check(bytes >= MIN_CACHE_BYTES)
            }
            publish(temporary, target)
            return target.absolutePath
        } finally {
            temporary.delete()
        }
    }
}
