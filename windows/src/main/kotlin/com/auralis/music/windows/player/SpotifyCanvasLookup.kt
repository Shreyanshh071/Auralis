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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.nio.file.StandardCopyOption

/** Spotify's track video, downloaded as one short, silent video for the existing VLC renderer. */
internal object SpotifyCanvasLookup {
    private const val MAX_BYTES = 64L * 1024 * 1024
    // Small direct MP4s are typically mobile previews. Prefer the manifest for those clips.
    private const val MIN_DIRECT_BYTES = 1_500_000L
    private const val MIN_CACHE_BYTES = 64L * 1024
    // A song SpicyLyrics has no video for is asked again after a few minutes, not a day: its
    // catalogue fills in over time, and a clean install must not stay locked out.
    private const val MISS_RETRY_MS = 300_000L
    private val trackPattern = Regex("[A-Za-z0-9]{22}")
    private val filePattern = Regex("[a-fA-F0-9]{32}")
    private val folder by lazy { File(desktopAppDataDir(), "cache/spotify-canvas") }
    private val diskIndex by lazy { PropertiesFileKeyValueStore(File(folder, "index.properties"), synchronous = true) }
    private val misses = mutableMapOf<String, Long>()
    private val lock = Mutex()
    private var retryAt = 0L
    private val http get() = NetworkClientProvider.okHttpClient
    private val directHttp by lazy {
        http.newBuilder().followRedirects(false).callTimeout(5, TimeUnit.SECONDS).build()
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
            val known = indexedFileId(trackId)
            known?.let { cachedFile(it)?.let { file -> return@withLock file.absolutePath } }
            val now = System.currentTimeMillis()
            if (now < (misses[trackId] ?: 0L)) return@withLock null
            // The video was cleared from disk but its id is still known: fetch it again directly.
            // SpicyLyrics can stop listing a canvas it once returned, so it is not asked first, and
            // a SpicyLyrics rate limit (retryAt) does not block this.
            if (known != null) {
                try {
                    (downloadDirect(diskIndex.getString("url.$trackId", "").orEmpty(), known)
                        ?: downloadViaManifest(trackId, known)).let { return@withLock it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Fall through to SpicyLyrics, which may list a newer canvas for the track.
                }
            }
            // Only SpicyLyrics saying it has no canvas blocks the track for a while; a failed
            // download (no network, a deleted folder) is simply tried again next time.
            var noneListed = false
            val viaSpicy: String? = if (now < retryAt) null else try {
                fromSpicyLyrics(trackId, now) { noneListed = true }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                retryAt = now + 60_000L
                null
            }
            viaSpicy
                ?: fromAccount(trackId)
                ?: run { if (noneListed) misses[trackId] = now + MISS_RETRY_MS; null }
        }
    }

    /** SpicyLyrics' public canvas list; null when it has none (or is rate-limiting). */
    private fun fromSpicyLyrics(trackId: String, now: Long, onNoneListed: () -> Unit): String? {
        val request = Request.Builder()
            .url("https://regional.canvasstore.slnode.spicylyrics.org/provider/canvaslist/$trackId")
            .header("Accept", "application/json").build()
        val item = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val pause = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 1800) ?: 60L
                retryAt = now + pause * 1000
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
        // The direct URL is kept too, so a cleared video can be fetched again without SpicyLyrics.
        diskIndex.putStrings(mapOf(trackId to fileId, "url.$trackId" to item.optString("url")))
        return cachedFile(fileId)?.absolutePath
            ?: downloadDirect(item.optString("url"), fileId)
            ?: downloadViaManifest(trackId, fileId)
    }

    /**
     * Settings > Canvas > "Fetch Canvas with your Spotify account": Spotify's own Canvas lookup,
     * which only answers a signed-in session, asked with the user's web-player token. Used only
     * when SpicyLyrics has no canvas for the track. The token goes to Spotify and nowhere else.
     */
    private suspend fun fromAccount(trackId: String): String? {
        if (!CanvasSettings.prefs.value.spotifyAccount || !com.auralis.music.data.network.SpotifySession.signedIn.value) return null
        val token = com.auralis.music.data.network.SpotifySession.getValidAccessToken() ?: return null
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
                com.auralis.music.util.Log.i("AnimatedCovers", "No Spotify Canvas for $trackId on the account either")
                return null
            }
            val fileId = canvas.fileId
            diskIndex.putStrings(buildMap {
                put(trackId, fileId)
                put("url.$trackId", canvas.url)
            })
            cachedFile(fileId)?.absolutePath
                ?: downloadDirect(canvas.url, fileId)
                ?: if (canvas.realFileId) downloadViaManifest(trackId, fileId)
                   else downloadDirect(canvas.url, fileId, minimumBytes = MIN_CACHE_BYTES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            com.auralis.music.util.Log.w("AnimatedCovers", "Spotify account Canvas lookup failed: ${e.message}")
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
        lock.withLock {
            val fileId = indexedFileId(trackId) ?: return@withLock null
            val mp4 = File(folder, "$fileId-hq.mp4")
            val manifest = File(folder, "$fileId-hq.ts")
            val failed = listOf(mp4, manifest).firstOrNull { it.absolutePath == failedPath } ?: return@withLock null
            // A Windows decoder can still have the file open while it shuts down.
            runCatching { diskIndex.putStrings(mapOf("rejected.${failed.name}" to failed.lastModified().toString())) }
            runCatching { failed.delete() }
            if (failed == manifest) return@withLock null
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
        val minimum = if (file.extension == "mp4") MIN_DIRECT_BYTES else MIN_CACHE_BYTES
        return file.isFile && file.length() in minimum..MAX_BYTES &&
            diskIndex.getString("rejected.${file.name}", "") != file.lastModified().toString()
    }

    private fun cachedFile(fileId: String): File? =
        listOf(File(folder, "$fileId-hq.mp4"), File(folder, "$fileId-hq.ts")).firstOrNull(::usable)

    private fun downloadDirect(url: String, fileId: String, minimumBytes: Long = MIN_DIRECT_BYTES): String? {
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
                if (length >= 0 && length !in minimumBytes..MAX_BYTES) return null
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
                if (bytes < minimumBytes || length >= 0 && bytes != length) return null
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
        val urls = (start until end step step).map { time ->
            base + segment.replace("{{segment_timestamp}}", (time / 1000).toString())
        }
        folder.mkdirs()
        val temporary = File.createTempFile(fileId, ".partial", folder)
        try {
            temporary.outputStream().use { output ->
                var bytes = 0L
                for (template in urls) {
                    val url = template.replace("{{profile_id}}", profile.getInt("id").toString())
                        .replace("{{file_type}}", "ts")
                    val uri = URI(url)
                    check(uri.scheme == "https" && (uri.host.endsWith(".scdn.co") ||
                        uri.host.endsWith(".spotifycdn.com")))
                    http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        check(response.isSuccessful)
                        checkNotNull(response.body).byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                check(bytes <= MAX_BYTES)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
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
