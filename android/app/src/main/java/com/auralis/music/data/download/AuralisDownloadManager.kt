package com.auralis.music.data.download

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.auralis.music.data.network.AudioStreamResolver
import com.auralis.music.ui.components.AppPillManager
import com.auralis.music.domain.model.AudioQuality
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * High-performance Offline Download Manager for Auralis Music.
 * Handles audio downloading, file caching, persistent metadata storage, and instant offline playback.
 */
object AuralisDownloadManager {

    private const val TAG = "AuralisDownload"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var appContext: Context? = null

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

    private val jobsLock = Any()
    private val activeDownloadJobs = mutableMapOf<String, Deferred<TrackDownloadResult>>()
    private val playlistJobs = mutableMapOf<String, Deferred<PlaylistDownloadState>>()
    private val blockedIds = mutableSetOf<String>()
    private var clearingDownloads = false
    private val transferSlots = Semaphore(2)
    private val initialized = CompletableDeferred<Unit>()
    @Volatile private var store: DownloadStore? = null
    private val _playlistDownloads = MutableStateFlow<Map<String, PlaylistDownloadState>>(emptyMap())
    val playlistDownloads = _playlistDownloads.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            try {
                val loadedStore = DownloadStore(context.applicationContext.filesDir)
                synchronized(loadedStore) {
                    val tracks = loadedStore.load()
                    store = loadedStore
                    publishDownloads(tracks)
                }
                initialized.complete(Unit)
                Log.d(TAG, "Loaded ${_downloadedTracks.value.size} verified offline tracks")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading saved downloads: ${e.message}", e)
                initialized.completeExceptionally(e)
            }
        }
    }

    private fun getDownloadsDir(): File {
        val ctx = checkNotNull(appContext) { "AuralisDownloadManager not initialized" }
        val dir = File(ctx.filesDir, "audio_downloads")
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create audio download directory" }
        return dir
    }

    private fun publishDownloads(tracks: List<Track>) {
        _downloadedTracks.value = tracks
        _downloadedTrackIds.value = tracks.map { it.id }.toSet()
    }

    fun getDownloadedFile(trackId: String): File? = store?.downloadedFile(trackId)

    fun getDownloadedArtworkFile(trackId: String): File? =
        store?.artworkFile(trackId)?.takeIf { it.exists() && it.length() > 500 }

    fun isDownloaded(trackId: String): Boolean = getDownloadedFile(trackId) != null

    fun isDownloading(trackId: String): Boolean =
        synchronized(jobsLock) { activeDownloadJobs.containsKey(trackId) }

    suspend fun awaitInitialized(context: Context) {
        init(context.applicationContext)
        initialized.await()
    }

    suspend fun downloadTrackAwait(track: Track): TrackDownloadResult = requestTrack(track).await()

    fun cancelActiveDownload(trackId: String) {
        synchronized(jobsLock) { activeDownloadJobs[trackId] }?.cancel()
    }

    private fun requestTrack(track: Track): Deferred<TrackDownloadResult> = synchronized(jobsLock) {
        activeDownloadJobs[track.id]?.let { return@synchronized it }
        if (clearingDownloads || track.id in blockedIds) {
            return@synchronized CompletableDeferred(TrackDownloadResult(
                track.id, DownloadOutcome.SKIPPED, "queue", "Download removal in progress"
            ))
        }
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                initialized.await()
                transferSlots.withPermit {
                    if (isDownloaded(track.id)) TrackDownloadResult(track.id, DownloadOutcome.ALREADY_DOWNLOADED)
                    else performDownload(track)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for '${track.title}' (${track.id}) at initialization: ${e.message}", e)
                TrackDownloadResult(track.id, DownloadOutcome.FAILURE, "initialization", e.message ?: e.javaClass.simpleName)
            }
        }
        activeDownloadJobs[track.id] = job
        _activeDownloads.update { it + (track.id to 0f) }
        job.invokeOnCompletion {
            synchronized(jobsLock) {
                if (activeDownloadJobs[track.id] === job) {
                    activeDownloadJobs.remove(track.id)
                    _activeDownloads.update { it - track.id }
                }
            }
        }
        job.start()
        job
    }

    fun downloadTrack(track: Track, context: Context? = null) {
        if (appContext == null && context != null) {
            init(context)
        }
        if (isDownloaded(track.id)) {
            showToast("Download already completed")
            return
        }
        if (isDownloading(track.id)) {
            showToast("Download already in progress")
            return
        }

        showToast("Downloading '${track.title}'...")

        requestTrack(track)
    }

    private suspend fun performDownload(track: Track): TrackDownloadResult {
        var stage = "storage"
        var tempFile: File? = null
        try {
            val ctx = checkNotNull(appContext)
            val downloadStore = checkNotNull(store)
            getDownloadsDir()
            val temporary = downloadStore.audioFile(track.id, "tmp")
            tempFile = temporary
            val targetFile = downloadStore.audioFile(track.id)
            _activeDownloads.update { it + (track.id to 0.05f) }
            stage = "resolution"
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
                            context = ctx,
                            duration = track.duration
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
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
                            context = ctx,
                            duration = track.duration
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Strategy D direct NewPipe notice: ${e.message}")
                }
            }

            // Strategy E: InnerTube /player API (bypasses NewPipe cipher issues)
            if (streamUrl.isNullOrBlank() && !track.id.startsWith("sp_") && !track.id.startsWith("spotify:")) {
                try {
                    withTimeoutOrNull(10000L) {
                        streamUrl = com.auralis.music.data.network.InnerTubePlayerResolver.resolveStream(track.id)
                    }
                    if (!streamUrl.isNullOrBlank()) {
                        Log.d(TAG, "Strategy E resolved via InnerTubePlayerResolver for '${track.title}'")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Strategy E InnerTubePlayerResolver notice: ${e.message}")
                }
            }

            // Strategy F: Search-based alternative (finds same song under different video ID)
            if (streamUrl.isNullOrBlank() && track.title.isNotBlank()) {
                try {
                    withTimeoutOrNull(15000L) {
                        streamUrl = AudioStreamResolver.resolveNonRestrictedAlternative(
                            title = track.title,
                            artist = track.artist,
                            originalVideoId = track.id,
                            quality = AudioQuality.HIGH,
                            context = ctx,
                            duration = track.duration
                        )
                    }
                    if (!streamUrl.isNullOrBlank()) {
                        Log.d(TAG, "Strategy F resolved via alternative search for '${track.title}'")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Strategy F alternative search notice: ${e.message}")
                }
            }

            val initialUrl = streamUrl
            if (initialUrl.isNullOrBlank()) {
                throw IllegalStateException("Unable to resolve audio stream URL for '${track.title}'")
            }

            Log.d(TAG, "Resolved stream URL for '${track.title}': $initialUrl")
            _activeDownloads.update { it + (track.id to 0.15f) }

            currentCoroutineContext().ensureActive()
            stage = "transfer"
            // Download bytes to temp file with automatic retry and user-agent rotation
            var downloadSuccess = downloadStreamBytes(initialUrl, temporary, track.id)

            // If expired URL (403/410/fail), clear cache and re-resolve fresh URL once
            if (!downloadSuccess) {
                Log.w(TAG, "Initial download failed. Attempting fresh stream re-resolution...")
                AudioStreamResolver.clearCache()
                var freshStream = withTimeoutOrNull(15000L) {
                    AudioStreamResolver.resolveAudioStream(
                        videoId = track.id,
                        title = track.title,
                        artist = track.artist,
                        quality = AudioQuality.AUTO,
                        context = ctx,
                        duration = track.duration
                    )
                }

                // Retry fallback: InnerTubePlayerResolver
                if (freshStream.isNullOrBlank() && !track.id.startsWith("sp_") && !track.id.startsWith("spotify:")) {
                    try {
                        freshStream = withTimeoutOrNull(10000L) {
                            com.auralis.music.data.network.InnerTubePlayerResolver.resolveStream(track.id)
                        }
                    } catch (_: Exception) {}
                }

                // Retry fallback: search-based alternative
                if (freshStream.isNullOrBlank() && track.title.isNotBlank()) {
                    try {
                        freshStream = withTimeoutOrNull(15000L) {
                            AudioStreamResolver.resolveNonRestrictedAlternative(
                                title = track.title,
                                artist = track.artist,
                                originalVideoId = track.id,
                                quality = AudioQuality.HIGH,
                                context = ctx,
                                duration = track.duration
                            )
                        }
                    } catch (_: Exception) {}
                }

                if (!freshStream.isNullOrBlank()) {
                    downloadSuccess = downloadStreamBytes(freshStream, temporary, track.id)
                }
            }

            currentCoroutineContext().ensureActive()
            if (!downloadSuccess || temporary.length() < 1024) {
                throw IllegalStateException("Downloaded audio file is incomplete or empty")
            }

            stage = "persistence"
            currentCoroutineContext().ensureActive()

            // Download and save high-resolution artwork to local disk for 100% offline cover display
            var trackToStore = track
            try {
                val artFile = downloadStore.artworkFile(track.id)
                if (!artFile.exists() || artFile.length() < 500) {
                    val candidateArtUrl = when {
                        !track.thumbnail.isNullOrBlank() -> com.auralis.music.ui.components.getHighResArtworkUrl(track.thumbnail) ?: track.thumbnail
                        else -> {
                            val matched = AudioStreamResolver.getMatchedVideoId(track.id)
                            if (!matched.isNullOrBlank() && matched.length in 8..15) {
                                "https://i.ytimg.com/vi/$matched/hq720.jpg"
                            } else {
                                com.auralis.music.util.MasterArtworkResolver.resolveMasterArtworkUrl(track.title, track.artist, null)
                            }
                        }
                    }
                    if (!candidateArtUrl.isNullOrBlank()) {
                        val artDownloaded = downloadArtworkBytes(candidateArtUrl, artFile)
                        if (artDownloaded && artFile.exists() && artFile.length() > 500) {
                            trackToStore = track.copy(thumbnail = Uri.fromFile(artFile).toString())
                        } else if (track.thumbnail.isBlank()) {
                            trackToStore = track.copy(thumbnail = candidateArtUrl)
                        }
                    }
                } else {
                    trackToStore = track.copy(thumbnail = Uri.fromFile(artFile).toString())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Artwork save notice for '${track.title}': ${e.message}")
            }

            synchronized(downloadStore) {
                if (targetFile.exists()) targetFile.delete()
                if (!temporary.renameTo(targetFile)) {
                    temporary.copyTo(targetFile, overwrite = true)
                    temporary.delete()
                }

                // Publish success only after a safe metadata replacement.
                publishDownloads(downloadStore.add(trackToStore))
            }
            check(isDownloaded(track.id)) { "Persisted download could not be verified" }
            showToast("Downloaded '${track.title}'")
            Log.d(TAG, "Successfully downloaded track '${track.title}' (${track.id}, ${targetFile.length() / 1024} KB)")
            return TrackDownloadResult(track.id, DownloadOutcome.SUCCESS)
        } catch (e: CancellationException) {
            Log.i(TAG, "Download cancelled for '${track.title}' (${track.id}) at $stage")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for '${track.title}' (${track.id}) at $stage: ${e.message}", e)
            showToast("Download failed for '${track.title}'")
            return TrackDownloadResult(track.id, DownloadOutcome.FAILURE, stage, e.message ?: e.javaClass.simpleName)
        } finally {
            tempFile?.takeIf { it.exists() }?.delete()
        }
    }

    private suspend fun downloadStreamBytes(
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
            currentCoroutineContext().ensureActive()
            try {
                if (tempFile.exists()) tempFile.delete()

                val request = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", ua)
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=0-")
                    .build()

                httpClient.newCall(request).execute().use { response ->
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
                                    currentCoroutineContext().ensureActive()
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
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download attempt failed with UA '$ua': ${e.message}")
            }
        }
        return false
    }

    private suspend fun downloadArtworkBytes(artUrl: String, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(artUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            httpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful && res.body != null) {
                    val tempArt = File(targetFile.parentFile, ".${targetFile.name}.tmp")
                    FileOutputStream(tempArt).use { out ->
                        res.body!!.byteStream().copyTo(out)
                        out.flush()
                    }
                    if (tempArt.exists() && tempArt.length() > 500) {
                        if (targetFile.exists()) targetFile.delete()
                        return@withContext tempArt.renameTo(targetFile)
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun downloadPlaylist(
        tracks: List<Track>,
        playlistTitle: String = "Playlist",
        playlistId: String = playlistTitle,
        context: Context? = null
    ): Deferred<PlaylistDownloadState> {
        val ctx = context ?: appContext
        if (ctx != null) {
            init(ctx)
            PlaylistDownloadCoordinator.enqueue(ctx, playlistId, playlistTitle, tracks)
        }
        return synchronized(jobsLock) {
            playlistJobs[playlistId]?.let { return@synchronized it }
            val job = scope.async(start = CoroutineStart.LAZY) {
                if (ctx != null) {
                    var finalState: PlaylistDownloadState? = null
                    try {
                        PlaylistDownloadCoordinator.jobs.collect { jobs ->
                            val entity = jobs[playlistId]
                            if (entity != null) {
                                val state = PlaylistDownloadState(
                                    trackIds = PlaylistDownloadCoordinator.tracks(entity).map { it.id },
                                    results = PlaylistDownloadCoordinator.results(entity),
                                    status = runCatching { PlaylistDownloadStatus.valueOf(entity.status) }
                                        .getOrDefault(PlaylistDownloadStatus.DOWNLOADING)
                                )
                                _playlistDownloads.update { it + (playlistId to state) }
                                if (state.status in setOf(
                                        PlaylistDownloadStatus.COMPLETE,
                                        PlaylistDownloadStatus.ALREADY_DOWNLOADED,
                                        PlaylistDownloadStatus.PARTIAL_FAILURE,
                                        PlaylistDownloadStatus.FAILED,
                                        PlaylistDownloadStatus.CANCELLED,
                                        PlaylistDownloadStatus.EMPTY
                                    )) {
                                    finalState = state
                                    throw CancellationException("Playlist download job finished")
                                }
                            }
                        }
                    } catch (_: CancellationException) {
                        // Expected when terminal state is reached
                    }
                    finalState ?: PlaylistDownloadState(tracks.map { it.id }, status = PlaylistDownloadStatus.COMPLETE)
                } else {
                    runPlaylistDownload(tracks, ::isDownloaded, { track -> requestTrack(track).await() }) { state ->
                        _playlistDownloads.update { it + (playlistId to state) }
                    }
                }
            }
            playlistJobs[playlistId] = job
            job.invokeOnCompletion {
                synchronized(jobsLock) {
                    if (playlistJobs[playlistId] === job) playlistJobs.remove(playlistId)
                }
            }
            job.start()
            job
        }
    }

    fun removeDownload(trackId: String) {
        synchronized(jobsLock) {
            if (clearingDownloads || !blockedIds.add(trackId)) return
        }
        scope.launch {
            try {
                initialized.await()
                val job = synchronized(jobsLock) { activeDownloadJobs[trackId] }
                job?.cancel()
                job?.join()
                val downloadStore = checkNotNull(store)
                synchronized(downloadStore) {
                    val file = downloadStore.downloadedFile(trackId)
                    val artFile = downloadStore.artworkFile(trackId)
                    publishDownloads(downloadStore.remove(trackId))
                    file?.delete()
                    if (artFile.exists()) artFile.delete()
                }
                showToast("Removed download")
                Log.d(TAG, "Removed download: $trackId")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing download: ${e.message}", e)
            } finally {
                synchronized(jobsLock) { blockedIds.remove(trackId) }
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
        synchronized(jobsLock) {
            if (clearingDownloads) return
            clearingDownloads = true
        }
        scope.launch {
            try {
                initialized.await()
                val jobs = synchronized(jobsLock) { playlistJobs.values.toList() + activeDownloadJobs.values.toList() }
                jobs.forEach { it.cancel() }
                jobs.forEach { it.join() }
                val downloadStore = checkNotNull(store)
                synchronized(downloadStore) {
                    publishDownloads(downloadStore.clear())
                    getDownloadsDir().listFiles()?.forEach { it.delete() }
                    val ctx = appContext
                    if (ctx != null) {
                        File(ctx.filesDir, "artwork_downloads").listFiles()?.forEach { it.delete() }
                    }
                }
                _playlistDownloads.value = emptyMap()
                showToast("All downloads cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing all downloads: ${e.message}", e)
            } finally {
                synchronized(jobsLock) { clearingDownloads = false }
            }
        }
    }

    private fun showToast(message: String) {
        AppPillManager.showPill(message)
    }
}
