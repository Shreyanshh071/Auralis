package com.auralis.music.windows.player

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.SpotifyPlaylistImporter
import com.auralis.music.util.desktopAppDataDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Spotify's track video, downloaded as one short, silent video for the existing VLC renderer. */
internal object SpotifyCanvasLookup {
    private data class Entry(val path: String?, val expiresAt: Long)
    private val cache = mutableMapOf<String, Entry>()
    private val lock = Mutex()
    private var retryAt = 0L
    private val http get() = NetworkClientProvider.okHttpClient

    suspend fun find(trackId: String?): String? = withContext(Dispatchers.IO) {
        if (trackId == null || !Regex("[A-Za-z0-9]{22}").matches(trackId)) return@withContext null
        lock.withLock {
            val now = System.currentTimeMillis()
            cache[trackId]?.takeIf { now < it.expiresAt &&
                (it.path == null || File(it.path).isFile) }?.let { return@withLock it.path }
            if (now < retryAt) return@withLock null
            try {
                val request = Request.Builder()
                    .url("https://regional.canvasstore.slnode.spicylyrics.org/provider/canvaslist/$trackId")
                    .header("Accept", "application/json").build()
                val item = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val pause = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 1800) ?: 60L
                        retryAt = now + pause * 1000
                        return@withLock null
                    }
                    val data = JSONObject(response.body?.string().orEmpty()).getJSONObject("data")
                    check(data.has(trackId))
                    data.optJSONObject(trackId)
                }
                check(item?.has("error") != true)
                val path = if (item != null && item.optString("type").startsWith("VIDEO")) {
                    download(trackId, item.getString("fileId"))
                } else null
                cache[trackId] = Entry(path, now + 86_400_000L)
                path
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                retryAt = now + 60_000L
                null
            }
        }
    }

    private fun readJson(request: Request): JSONObject = http.newCall(request).execute().use { response ->
        check(response.isSuccessful)
        JSONObject(response.body?.string().orEmpty())
    }

    private fun download(trackId: String, fileId: String): String {
        check(Regex("[a-fA-F0-9]{32}").matches(fileId))
        val folder = File(desktopAppDataDir(), "cache/spotify-canvas").apply { mkdirs() }
        val target = File(folder, "$fileId.ts")
        if (target.isFile && target.length() > 0) return target.absolutePath
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
            .filter { it.optString("file_type") == "ts" && it.optInt("video_width") in 1..720 }
            .maxByOrNull { it.optInt("video_width") * it.optInt("video_height") }
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
                                check(bytes <= 16 * 1024 * 1024)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                check(bytes > 0)
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return target.absolutePath
        } finally {
            temporary.delete()
        }
    }
}
