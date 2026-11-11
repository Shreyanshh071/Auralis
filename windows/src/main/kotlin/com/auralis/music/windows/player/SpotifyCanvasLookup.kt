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
import okhttp3.Request
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.nio.file.StandardCopyOption

/** Spotify's track video, downloaded as one short, silent video for the existing VLC renderer. */
internal object SpotifyCanvasLookup {
    private val client by lazy {
        SpotifyCanvasClient(NetworkClientProvider.okHttpClient, File(desktopAppDataDir(), "cache/spotify-canvas"))
    }

    fun cached(trackId: String?): String? = client.cached(trackId)
    suspend fun find(trackId: String?): String? = client.find(trackId)
    suspend fun fallback(trackId: String, failedPath: String): String? = client.fallback(trackId, failedPath)
}

/** Injectable transport/cache for exercising real provider responses without Spotify or VLC. */
internal class SpotifyCanvasClient(
    private val http: OkHttpClient,
    private val folder: File,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private companion object {
        private const val MAX_BYTES = 64L * 1024 * 1024
        private const val MIN_CACHE_BYTES = 64L * 1024
        private const val DAY_MS = 86_400_000L
    }
    private val trackPattern = Regex("[A-Za-z0-9]{22}")
    private val filePattern = Regex("[a-fA-F0-9]{32}")
    private val diskIndex by lazy { PropertiesFileKeyValueStore(File(folder, "index.properties"), synchronous = true) }
    private val misses = mutableMapOf<String, Long>()
    private val failures = mutableMapOf<String, Long>()
    private val lock = Mutex()
    private var retryAt = 0L
    private val directHttp by lazy {
        http.newBuilder().followRedirects(false).callTimeout(5, TimeUnit.SECONDS).build()
    }

    /** Cache hits must not wait behind an unrelated provider request/download. */
    fun cached(trackId: String?): String? {
        if (trackId == null || !trackPattern.matches(trackId)) return null
        return indexedFileId(trackId)?.let(::cachedFile)?.absolutePath
    }

    suspend fun find(trackId: String?): String? = withContext(Dispatchers.IO) {
        cached(trackId)?.let { return@withContext it }
        if (trackId == null || !trackPattern.matches(trackId)) return@withContext null
        lock.withLock {
            indexedFileId(trackId)?.let { cachedFile(it)?.let { file -> return@withLock file.absolutePath } }
            val now = clock()
            if (now < retryAt || now < (misses[trackId] ?: 0L) || now < (failures[trackId] ?: 0L)) return@withLock null
            try {
                val request = Request.Builder()
                    .url("https://regional.canvasstore.slnode.spicylyrics.org/provider/canvaslist/$trackId")
                    .header("Accept", "application/json").build()
                val item = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        com.auralis.music.util.Log.w("SpotifyCanvas", "lookup $trackId: HTTP ${response.code}")
                        val pause = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 1800) ?: 60L
                        if (response.code == 429 || response.header("Retry-After") != null) retryAt = now + pause * 1000
                        else failures[trackId] = now + 60_000L
                        return@withLock null
                    }
                    val data = JSONObject(response.body?.string().orEmpty()).getJSONObject("data")
                    check(data.has(trackId))
                    data.optJSONObject(trackId)
                }
                check(item?.has("error") != true)
                if (item == null || item.optString("type") !in setOf("VIDEO", "VIDEO_LOOPING", "VIDEO_LOOPING_RANDOM")) {
                    com.auralis.music.util.Log.i("SpotifyCanvas", "No video canvas for $trackId")
                    misses[trackId] = now + DAY_MS
                    return@withLock null
                }
                val fileId = item.getString("fileId")
                check(filePattern.matches(fileId))
                diskIndex.putStrings(mapOf(trackId to fileId))
                cachedFile(fileId)?.absolutePath
                    ?: downloadDirect(item.optString("url"), fileId)
                    ?: downloadViaManifest(trackId, fileId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                com.auralis.music.util.Log.w("SpotifyCanvas", "lookup $trackId failed: $error")
                failures[trackId] = now + 60_000L
                null
            }
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
        return file.isFile && file.length() in MIN_CACHE_BYTES..MAX_BYTES &&
            diskIndex.getString("rejected.${file.name}", "") != file.lastModified().toString()
    }

    private fun cachedFile(fileId: String): File? =
        listOf(File(folder, "$fileId-hq.mp4"), File(folder, "$fileId-hq.ts")).firstOrNull(::usable)

    private fun downloadDirect(url: String, fileId: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "canvaz.scdn.co" || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443) || uri.path?.endsWith(".cnvs.mp4", ignoreCase = true) != true) return null
        val target = File(folder, "$fileId-hq.mp4")
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
        val urls = (start until end step step).map { time ->
            base + segment.replace("{{segment_timestamp}}", (time / 1000).toString())
        }
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
