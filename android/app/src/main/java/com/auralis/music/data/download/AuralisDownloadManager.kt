package com.auralis.music.data.download

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.auralis.music.data.network.AudioStreamResolver
import com.auralis.music.domain.model.AudioQuality
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * High-performance Offline Download Manager for Auralis Music.
 * Handles audio downloading, file caching, persistent metadata storage, and instant offline playback.
 */
object AuralisDownloadManager {

    private const val TAG = "AuralisDownload"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val _downloadedTracks = MutableStateFlow<List<Track>>(emptyList())
    val downloadedTracks: StateFlow<List<Track>> = _downloadedTracks.asStateFlow()

    private val _downloadedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedTrackIds: StateFlow<Set<String>> = _downloadedTrackIds.asStateFlow()

    private val _activeDownloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, Float>> = _activeDownloads.asStateFlow()

    private val activeDownloadJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadSavedDownloads()
    }

    private fun getDownloadsDir(): File {
        val ctx = appContext ?: throw IllegalStateException("AuralisDownloadManager not initialized")
        val dir = File(ctx.filesDir, "audio_downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getMetadataFile(): File {
        val ctx = appContext ?: throw IllegalStateException("AuralisDownloadManager not initialized")
        return File(ctx.filesDir, "downloaded_tracks_v1.json")
    }

    fun getDownloadedFile(trackId: String): File? {
        val ctx = appContext ?: return null
        if (trackId.startsWith("sp_") || trackId.startsWith("spotify:")) return null
        val dir = File(ctx.filesDir, "audio_downloads")
        val file = File(dir, "${trackId}.m4a")
        if (file.exists() && file.length() > 1024 && _downloadedTrackIds.value.contains(trackId)) return file
        return null
    }

    fun isDownloaded(trackId: String): Boolean {
        if (trackId.startsWith("sp_") || trackId.startsWith("spotify:")) return false
        val mappedId = AudioStreamResolver.getMatchedVideoId(trackId)
        return _downloadedTrackIds.value.contains(trackId) ||
                (!mappedId.isNullOrBlank() && _downloadedTrackIds.value.contains(mappedId))
    }

    fun isDownloading(trackId: String): Boolean {
        val mappedId = AudioStreamResolver.getMatchedVideoId(trackId)
        return _activeDownloads.value.containsKey(trackId) ||
                (!mappedId.isNullOrBlank() && _activeDownloads.value.containsKey(mappedId))
    }

    private fun loadSavedDownloads() {
        scope.launch {
            try {
                val metaFile = getMetadataFile()
                val dir = getDownloadsDir()
                if (!metaFile.exists()) {
                    _downloadedTracks.value = emptyList()
                    _downloadedTrackIds.value = emptySet()
                    return@launch
                }

                val jsonStr = metaFile.readText()
                val jsonArr = JSONArray(jsonStr)
                val validTracks = mutableListOf<Track>()
                val validIds = mutableSetOf<String>()

                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    val id = obj.getString("id")
                    // Reject raw unmapped Spotify IDs from offline cache
                    if (id.startsWith("sp_") || id.startsWith("spotify:")) continue
                    val audioFile = File(dir, "${id}.m4a")
                    if (audioFile.exists() && audioFile.length() > 1024) {
                        val track = Track(
                            id = id,
                            title = obj.optString("title", "Unknown Title"),
                            artist = obj.optString("artist", "Unknown Artist"),
                            duration = obj.optLong("duration", 0L),
                            thumbnail = obj.optString("thumbnail", ""),
                            album = obj.optString("album").takeIf { !it.isNullOrBlank() }
                        )
                        validTracks.add(track)
                        validIds.add(id)
                    }
                }

                _downloadedTracks.value = validTracks
                _downloadedTrackIds.value = validIds
                Log.d(TAG, "Loaded ${validTracks.size} verified offline tracks")

                // Clean up any orphaned/legacy audio files (including any raw sp_*.m4a files)
                val existingFiles = dir.listFiles() ?: emptyArray()
                for (file in existingFiles) {
                    val nameWithoutExt = file.nameWithoutExtension
                    if (file.name.startsWith("sp_") || file.extension.equals("tmp", ignoreCase = true) || !validIds.contains(nameWithoutExt)) {
                        Log.d(TAG, "Purging legacy/orphaned audio file: ${file.name}")
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading saved downloads: ${e.message}")
            }
        }
    }

    private fun persistDownloads() {
        try {
            val metaFile = getMetadataFile()
            val jsonArr = JSONArray()
            for (track in _downloadedTracks.value) {
                val obj = JSONObject().apply {
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("duration", track.duration)
                    put("thumbnail", track.thumbnail)
                    put("album", track.album ?: "")
                }
                jsonArr.put(obj)
            }
            metaFile.writeText(jsonArr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving downloads metadata: ${e.message}")
        }
    }

    fun downloadTrack(track: Track, context: Context? = null) {
        if (appContext == null && context != null) {
            init(context)
        }
        val ctx = appContext ?: return

        if (isDownloaded(track.id)) {
            showToast("Download already completed")
            return
        }
        if (isDownloading(track.id)) {
            showToast("Download already in progress")
            return
        }

        showToast("Downloading '${track.title}'...")

        val job = scope.launch {
            _activeDownloads.update { it + (track.id to 0.05f) }
            val dir = getDownloadsDir()
            val tempFile = File(dir, "${track.id}.tmp")
            val targetFile = File(dir, "${track.id}.m4a")

            try {
                Log.d(TAG, "Starting download for '${track.title}' (${track.id})")

                var streamUrl: String? = null

                // Strategy A: Memory Cache
                streamUrl = AudioStreamResolver.getCachedStream(track.id)
                    ?: AudioStreamResolver.getCachedStream("${track.id}_HIGH")
                    ?: AudioStreamResolver.getCachedStream("${track.id}_AUTO")

                val mappedId = AudioStreamResolver.getMatchedVideoId(track.id)
                if (streamUrl.isNullOrBlank() && !mappedId.isNullOrBlank()) {
                    streamUrl = AudioStreamResolver.getCachedStream(mappedId)
                        ?: AudioStreamResolver.getCachedStream("${mappedId}_HIGH")
                        ?: AudioStreamResolver.getCachedStream("${mappedId}_AUTO")
                }

                // Strategy B: Resolve stream via AudioStreamResolver with HIGH quality
                if (streamUrl.isNullOrBlank()) {
                    try {
                        withTimeoutOrNull(20000L) {
                            streamUrl = AudioStreamResolver.resolveAudioStream(
                                videoId = track.id,
                                title = track.title,
                                artist = track.artist,
                                quality = AudioQuality.HIGH,
                                context = ctx
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Strategy B resolve notice: ${e.message}")
                    }
                }

                // Strategy C: Resolve stream with AUTO quality
                if (streamUrl.isNullOrBlank()) {
                    try {
                        withTimeoutOrNull(15000L) {
                            streamUrl = AudioStreamResolver.resolveAudioStream(
                                videoId = track.id,
                                title = track.title,
                                artist = track.artist,
                                quality = AudioQuality.AUTO,
                                context = ctx
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Strategy C resolve notice: ${e.message}")
                    }
                }

                // Strategy D: Direct NewPipe Extractor for YouTube IDs
                if (streamUrl.isNullOrBlank() && !track.id.startsWith("sp_") && !track.id.startsWith("spotify:")) {
                    try {
                        AudioStreamResolver.ensureNewPipeInitialized()
                        val extractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=${track.id}")
                        extractor.fetchPage()
                        val audioStreams = extractor.audioStreams
                        if (!audioStreams.isNullOrEmpty()) {
                            streamUrl = audioStreams.maxByOrNull { it.averageBitrate }?.content ?: audioStreams.firstOrNull()?.content
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Strategy D direct NewPipe notice: ${e.message}")
                    }
                }

                val initialUrl = streamUrl
                if (initialUrl.isNullOrBlank()) {
                    throw IllegalStateException("Unable to resolve audio stream URL for '${track.title}'")
                }

                Log.d(TAG, "Resolved stream URL for '${track.title}': $initialUrl")
                _activeDownloads.update { it + (track.id to 0.15f) }

                // Download bytes to temp file with automatic retry and user-agent rotation
                var downloadSuccess = downloadStreamBytes(initialUrl, tempFile, track.id)

                // If expired URL (403/410/fail), clear cache and re-resolve fresh URL once
                if (!downloadSuccess) {
                    Log.w(TAG, "Initial download failed. Attempting fresh stream re-resolution...")
                    AudioStreamResolver.clearCache()
                    val freshStream = withTimeoutOrNull(15000L) {
                        AudioStreamResolver.resolveAudioStream(
                            videoId = track.id,
                            title = track.title,
                            artist = track.artist,
                            quality = AudioQuality.AUTO,
                            context = ctx
                        )
                    }
                    if (!freshStream.isNullOrBlank()) {
                        downloadSuccess = downloadStreamBytes(freshStream, tempFile, track.id)
                    }
                }

                if (!downloadSuccess || tempFile.length() < 1024) {
                    throw IllegalStateException("Downloaded audio file is incomplete or empty")
                }

                // 3. Commit downloaded file
                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }

                // 4. Update memory & persistent store
                _downloadedTracks.update { list ->
                    if (list.none { it.id == track.id }) list + track else list
                }
                _downloadedTrackIds.update { set ->
                    val newSet = set + track.id
                    val effectiveMapped = AudioStreamResolver.getMatchedVideoId(track.id)
                    if (!effectiveMapped.isNullOrBlank()) newSet + effectiveMapped else newSet
                }
                persistDownloads()

                _activeDownloads.update { it - track.id }
                showToast("Downloaded '${track.title}' for offline playback")
                Log.d(TAG, "Successfully downloaded track '${track.title}' (${targetFile.length() / 1024} KB)")

            } catch (e: Exception) {
                Log.e(TAG, "Download failed for '${track.title}': ${e.message}", e)
                if (tempFile.exists()) tempFile.delete()
                _activeDownloads.update { it - track.id }
                showToast("Download failed for '${track.title}'")
            } finally {
                activeDownloadJobs.remove(track.id)
            }
        }

        activeDownloadJobs[track.id] = job
    }

    private fun downloadStreamBytes(
        streamUrl: String,
        tempFile: File,
        trackId: String
    ): Boolean {
        val userAgents = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Auralis/2.1.0 (Android; ExoPlayer)"
        )

        for (ua in userAgents) {
            try {
                if (tempFile.exists()) tempFile.delete()

                val request = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", ua)
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .header("Referer", "https://www.youtube.com/")
                    .header("Origin", "https://www.youtube.com")
                    .header("Range", "bytes=0-")
                    .build()

                val response = httpClient.newCall(request).execute()
                if ((response.isSuccessful || response.code == 206) && response.body != null) {
                    val body = response.body!!
                    val contentLength = body.contentLength().coerceAtLeast(1L)
                    var bytesReadTotal = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            var lastProgressUpdate = System.currentTimeMillis()

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                bytesReadTotal += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastProgressUpdate > 200) {
                                    val progress = 0.15f + (0.80f * (bytesReadTotal.toFloat() / contentLength)).coerceIn(0f, 0.80f)
                                    _activeDownloads.update { it + (trackId to progress) }
                                    lastProgressUpdate = now
                                }
                            }
                            output.flush()
                        }
                    }

                    if (tempFile.exists() && tempFile.length() > 5000) {
                        return true
                    }
                } else {
                    Log.w(TAG, "Download attempt with UA '$ua' returned HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Download attempt failed with UA '$ua': ${e.message}")
            }
        }
        return false
    }

    fun downloadPlaylist(tracks: List<Track>, playlistTitle: String = "Playlist") {
        if (tracks.isEmpty()) return
        val unDownloaded = tracks.filter { !isDownloaded(it.id) && !isDownloading(it.id) }
        if (unDownloaded.isEmpty()) {
            showToast("All songs in '$playlistTitle' are already downloaded")
            return
        }

        showToast("Queued ${unDownloaded.size} songs from '$playlistTitle' for download")
        scope.launch {
            for (track in unDownloaded) {
                downloadTrack(track)
                // Small spacing to prevent network congestion
                kotlinx.coroutines.delay(600)
            }
        }
    }

    fun removeDownload(trackId: String) {
        scope.launch {
            try {
                activeDownloadJobs[trackId]?.cancel()
                activeDownloadJobs.remove(trackId)
                _activeDownloads.update { it - trackId }

                val file = getDownloadedFile(trackId)
                file?.delete()

                val trackTitle = _downloadedTracks.value.firstOrNull { it.id == trackId }?.title

                _downloadedTracks.update { list -> list.filter { it.id != trackId } }
                _downloadedTrackIds.update { set ->
                    val mapped = AudioStreamResolver.getMatchedVideoId(trackId)
                    set.filter { it != trackId && it != mapped }.toSet()
                }
                persistDownloads()

                showToast("Removed ${trackTitle?.let { "'$it'" } ?: "download"}")
                Log.d(TAG, "Removed download: $trackId")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing download: ${e.message}")
            }
        }
    }

    fun getTotalDownloadSizeBytes(): Long {
        val ctx = appContext ?: return 0L
        val dir = File(ctx.filesDir, "audio_downloads")
        if (!dir.exists()) return 0L
        var total = 0L
        try {
            dir.listFiles()?.forEach { file ->
                if (file.isFile) total += file.length()
            }
        } catch (_: Exception) {}
        return total
    }

    fun clearAllDownloads() {
        scope.launch {
            try {
                activeDownloadJobs.values.forEach { it.cancel() }
                activeDownloadJobs.clear()
                _activeDownloads.value = emptyMap()

                val ctx = appContext
                if (ctx != null) {
                    val dir = File(ctx.filesDir, "audio_downloads")
                    if (dir.exists()) {
                        dir.listFiles()?.forEach { it.delete() }
                    }
                    val metaFile = getMetadataFile()
                    if (metaFile.exists()) metaFile.delete()
                }

                _downloadedTracks.value = emptyList()
                _downloadedTrackIds.value = emptySet()
                showToast("All downloads cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing all downloads: ${e.message}")
            }
        }
    }

    private fun showToast(message: String) {
        val ctx = appContext ?: return
        mainHandler.post {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }
}
