package com.auralis.music.ui.player

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.SpotifyPlaylistImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/** Injectable transport/cache for testing provider responses without a phone or live Spotify requests. */
internal class SpotifyCanvasClient(
    private val http: OkHttpClient,
    private val folder: File,
    indexFile: File = File(folder, "index.properties"),
    private val clock: () -> Long = System::currentTimeMillis,
    /** The signed-in user's Spotify token when they allow Canvas lookups with it; null otherwise. */
    private val accountToken: (suspend () -> String?)? = null
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
    private val diskIndex by lazy { CanvasFileIndex(indexFile) }
    private val misses = mutableMapOf<String, Long>()
    private val failures = mutableMapOf<String, Long>()
    private val lock = Mutex()
    private var retryAt = 0L
    private val directHttp by lazy {
        http.newBuilder().followRedirects(false).callTimeout(5, TimeUnit.SECONDS).build()
    }
    private val segmentPool = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "canvas-segments").apply { isDaemon = true }
    }
    @Volatile private var partialsCleaned = false
    private val sharpScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val sharpJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String?>>()

    /**
     * What to play right away: the sharp copy if it is on disk, else the cached default MP4, else
     * the default MP4's own link to stream. The sharp copy then downloads in the background; wait
     * for it with [awaitSharp]. Nothing here downloads a whole video before returning.
     */
    suspend fun findFast(trackId: String?): String? = withContext(Dispatchers.IO) {
        if (trackId == null || !trackPattern.matches(trackId)) return@withContext null
        val listed: Pair<String, String> = lock.withLock {
            val known = indexedFileId(trackId)
            if (known != null) {
                File(folder, "$known-hq.ts").takeIf(::usable)?.let { return@withContext it.absolutePath }
                val url = diskIndex.getString("url.$trackId", "").orEmpty()
                return@withLock known to url
            }
            val now = clock()
            if (now < (misses[trackId] ?: 0L) || now < (failures[trackId] ?: 0L) || now < retryAt) return@withContext null
            var noneListed = false
            var item = try { listing(trackId, now) { noneListed = true } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    com.auralis.music.util.Log.w("SpotifyCanvas", "lookup $trackId failed: $error")
                    failures[trackId] = now + 60_000L
                    null
                }
            // SpicyLyrics has no Canvas: ask Spotify with the signed-in account, when the user allows it.
            if (item == null && noneListed) item = try { accountListing(trackId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    com.auralis.music.util.Log.w("SpotifyCanvas", "Spotify account Canvas lookup failed: ${error.message}")
                    null
                }
            if (item == null) { if (noneListed) misses[trackId] = now + MISS_RETRY_MS; return@withContext null }
            item
        }
        val (fileId, url) = listed
        val cachedSmall = File(folder, "$fileId-hq.mp4").takeIf(::usable)
        val sharp = if (cachedSmall == null || upgradeWorthTrying(fileId)) startSharp(trackId, fileId) else null
        cachedSmall?.absolutePath ?: url.takeIf(::streamable) ?: sharp?.await()
    }

    /** The sharp copy started by [findFast], once downloaded; null if it failed or none was started. */
    suspend fun awaitSharp(trackId: String): String? {
        val fileId = indexedFileId(trackId) ?: return null
        File(folder, "$fileId-hq.ts").takeIf(::usable)?.let { return it.absolutePath }
        return sharpJobs[fileId]?.await()
    }

    private fun startSharp(trackId: String, fileId: String): kotlinx.coroutines.Deferred<String?> =
        sharpJobs.getOrPut(fileId) {
            sharpScope.async {
                try { downloadViaManifest(trackId, fileId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    com.auralis.music.util.Log.w("SpotifyCanvas", "Sharp Canvas for $trackId failed: $error")
                    noteUpgradeFailed(fileId)
                    null
                } finally { sharpJobs.remove(fileId) }
            }
        }

    private fun streamable(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == "canvaz.scdn.co" && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) && uri.path?.endsWith(".cnvs.mp4", ignoreCase = true) == true
    }

    /**
     * Spotify's own Canvas service, which only answers a signed-in session, asked with the user's
     * token (the same request as the PC). Returns (fileId, streamable MP4 link) like [listing].
     * The token goes to Spotify and nowhere else.
     */
    private suspend fun accountListing(trackId: String): Pair<String, String>? {
        val token = accountToken?.invoke() ?: return null
        val uri = "spotify:track:$trackId".toByteArray()
        val entity = byteArrayOf(0x0a) + varint(uri.size) + uri
        val body = byteArrayOf(0x0a) + varint(entity.size) + entity
        val bytes = http.newCall(Request.Builder()
            .url("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/x-protobuf")
            .header("Accept", "application/protobuf")
            .post(body.toRequestBody("application/x-protobuf".toMediaType()))
            .build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null } ?: return null
        val canvas = parseAccountCanvas(bytes) ?: run {
            com.auralis.music.util.Log.i("SpotifyCanvas", "No Spotify Canvas for $trackId on the account either")
            return null
        }
        diskIndex.putStrings(mapOf(trackId to canvas.fileId, "url.$trackId" to canvas.url))
        return canvas.fileId to canvas.url
    }

    private class AccountCanvas(val fileId: String, val url: String)

    /** The first video canvas in Spotify's protobuf reply: its .cnvs.mp4 URL and file id. */
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
            val id = fileId ?: urlId.takeIf(filePattern::matches) ?: return null
            return AccountCanvas(id.lowercase(), url)
        }
        return null
    }

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

    /** SpicyLyrics' listing for [trackId] as (fileId, default MP4 link), recorded in the index. */
    private fun listing(trackId: String, now: Long, onNoneListed: () -> Unit): Pair<String, String>? {
        val request = Request.Builder()
            .url("https://regional.canvasstore.slnode.spicylyrics.org/provider/canvaslist/$trackId")
            .header("Accept", "application/json").build()
        val item = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val pause = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 1800) ?: 60L
                if (response.code == 429 || response.header("Retry-After") != null) retryAt = now + pause * 1000
                else failures[trackId] = now + 60_000L
                return null
            }
            val data = JSONObject(response.body?.string().orEmpty()).getJSONObject("data")
            check(data.has(trackId))
            data.optJSONObject(trackId)
        }
        check(item?.has("error") != true)
        if (item == null || item.optString("type") !in setOf("VIDEO", "VIDEO_LOOPING", "VIDEO_LOOPING_RANDOM")) {
            onNoneListed()
            return null
        }
        val fileId = item.getString("fileId")
        check(filePattern.matches(fileId))
        diskIndex.putStrings(mapOf(trackId to fileId, "url.$trackId" to item.optString("url")))
        return fileId to item.optString("url")
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

    suspend fun find(trackId: String?): String? = withContext(Dispatchers.IO) {
        if (trackId == null || !trackPattern.matches(trackId)) return@withContext null
        lock.withLock {
            // A download cut off by closing the app leaves a .partial file behind; clear old ones once.
            if (!partialsCleaned) {
                partialsCleaned = true
                val cutoff = clock() - 10 * 60_000L
                folder.listFiles { file -> file.name.endsWith(".partial") && file.lastModified() < cutoff }?.forEach { runCatching { it.delete() } }
            }
            val known = indexedFileId(trackId)
            // The manifest's best encode wins; a cached small default MP4 is only kept if the
            // upgrade cannot be fetched, so earlier low-quality loops improve on their next play.
            known?.let { id -> File(folder, "$id-hq.ts").takeIf(::usable)?.let { return@withLock it.absolutePath } }
            if (known != null) File(folder, "$known-hq.mp4").takeIf(::usable)?.let { small ->
                if (!upgradeWorthTrying(known)) return@withLock small.absolutePath
                return@withLock try { downloadViaManifest(trackId, known) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { noteUpgradeFailed(known); small.absolutePath }
            }
            val now = clock()
            if (now < (misses[trackId] ?: 0L) || now < (failures[trackId] ?: 0L)) return@withLock null
            // The video was cleared from disk but its id is still known: fetch it again directly.
            // SpicyLyrics can stop listing a canvas it once returned, so it is not asked first, and
            // a SpicyLyrics rate limit (retryAt) does not block this.
            if (known != null) {
                try {
                    bestQuality(trackId, known, diskIndex.getString("url.$trackId", "").orEmpty())?.let { return@withLock it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Fall through to SpicyLyrics, which may list a newer canvas for the track.
                }
            }
            if (now < retryAt) return@withLock null
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
            viaSpicy
                ?: run { if (noneListed) misses[trackId] = now + MISS_RETRY_MS; null }
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
                if (response.code == 429 || response.header("Retry-After") != null) retryAt = now + pause * 1000
                else failures[trackId] = now + 60_000L
                return null
            }
            val data = JSONObject(response.body?.string().orEmpty()).getJSONObject("data")
            check(data.has(trackId))
            data.optJSONObject(trackId)
        }
        check(item?.has("error") != true)
        if (item == null || item.optString("type") !in setOf("VIDEO", "VIDEO_LOOPING", "VIDEO_LOOPING_RANDOM")) {
            com.auralis.music.util.Log.i("SpotifyCanvas", "No video canvas for $trackId")
            onNoneListed()
            return null
        }
        val fileId = item.getString("fileId")
        check(filePattern.matches(fileId))
        // The direct URL is kept too, so a cleared video can be fetched again without SpicyLyrics.
        diskIndex.putStrings(mapOf(trackId to fileId, "url.$trackId" to item.optString("url")))
        return cachedFile(fileId)?.absolutePath ?: bestQuality(trackId, fileId, item.optString("url"))
    }

    /**
     * Spotify's manifest lists every encode and [downloadViaManifest] takes the sharpest (up to
     * 1080x1920). The direct .cnvs.mp4 is a small default encode, so it is only the fallback.
     */
    private fun bestQuality(trackId: String, fileId: String, directUrl: String): String? =
        try { downloadViaManifest(trackId, fileId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            com.auralis.music.util.Log.w("SpotifyCanvas", "HQ manifest for $trackId failed, using the default MP4: $error")
            noteUpgradeFailed(fileId)
            downloadDirect(directUrl, fileId)
        }

    // A manifest that failed is not asked again for a day, so a cached default MP4 plays at once.
    private fun upgradeWorthTrying(fileId: String): Boolean =
        (diskIndex.getString("hqfailed.$fileId", "")?.toLongOrNull() ?: 0L) + 86_400_000L < clock()

    private fun noteUpgradeFailed(fileId: String) {
        runCatching { diskIndex.putStrings(mapOf("hqfailed.$fileId" to clock().toString())) }
    }

    /** Reject the exact candidate used by the video player, then bypass the MP4 on the retry. */
    suspend fun fallback(trackId: String, failedPath: String): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            val fileId = indexedFileId(trackId) ?: return@withLock null
            val mp4 = File(folder, "$fileId-hq.mp4")
            val manifest = File(folder, "$fileId-hq.ts")
            val failed = listOf(mp4, manifest).firstOrNull { it.absolutePath == failedPath } ?: return@withLock null
            // A video decoder can still have the file open while it shuts down.
            runCatching { diskIndex.putStrings(mapOf("rejected.${failed.name}" to failed.lastModified().toString())) }
            runCatching { failed.delete() }
            if (failed == manifest) return@withLock downloadDirect(diskIndex.getString("url.$trackId", "").orEmpty(), fileId)
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

    private fun usable(file: File): Boolean {
        return file.isFile && file.length() in MIN_CACHE_BYTES..MAX_BYTES &&
            diskIndex.getString("rejected.${file.name}", "") != file.lastModified().toString()
    }

    private fun cachedFile(fileId: String): File? =
        listOf(File(folder, "$fileId-hq.ts"), File(folder, "$fileId-hq.mp4")).firstOrNull(::usable)

    private fun downloadDirect(url: String, fileId: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "canvaz.scdn.co" || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443) || uri.path?.endsWith(".cnvs.mp4", ignoreCase = true) != true) return null
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
        folder.mkdirs()
        check(temporary.renameTo(target)) { "Could not publish Canvas file" }
        // Keep the video cache bounded. The recording/index metadata lives outside Android's
        // disposable cache, so an evicted or cleared video can always be downloaded again.
        var bytes = folder.listFiles()?.filter { it.isFile && it.extension in setOf("mp4", "ts") }?.sumOf { it.length() } ?: 0L
        folder.listFiles()?.filter { it != target && it.isFile && it.extension in setOf("mp4", "ts") }
            ?.sortedBy { it.lastModified() }?.forEach { file ->
                if (bytes > 200L * 1024 * 1024) { val size = file.length(); if (file.delete()) bytes -= size }
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
            .filter { it.optString("file_type") == "ts" && it.optInt("video_width") in 1..1080 &&
                it.optInt("video_height") in 1..1920 }
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
                    val data = part.get(12, TimeUnit.SECONDS)
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

/** Small synchronous index; callers read and write on Dispatchers.IO. */
private class CanvasFileIndex(private val file: File) {
    private val values = java.util.Properties().apply {
        if (file.isFile) runCatching { file.inputStream().use { load(it) } }
    }
    @Synchronized fun getString(key: String, default: String): String? = values.getProperty(key, default)
    @Synchronized fun putStrings(entries: Map<String, String>) {
        entries.forEach { (key, value) -> values.setProperty(key, value) }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            temporary.outputStream().use { values.store(it, null) }
            // Android replaces the old file atomically; the copy fallback also supports JVM tests
            // on Windows, where renameTo refuses an existing destination.
            if (!temporary.renameTo(file)) temporary.copyTo(file, overwrite = true)
        } finally { temporary.delete() }
    }
}
