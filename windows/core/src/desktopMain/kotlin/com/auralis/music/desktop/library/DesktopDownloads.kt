package com.auralis.music.desktop.library

import com.auralis.music.desktop.playback.DesktopJioSaavnAudio
import com.auralis.music.desktop.playback.DesktopPlaybackMatch
import com.auralis.music.desktop.playback.DesktopSoundCloudAudio
import com.auralis.music.desktop.playback.DesktopStreamResolver
import com.auralis.music.desktop.playback.LocalStreamRelay
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.PlaylistDownloadJob
import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.util.desktopAppDataDir
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Files are published only after complete transfer; the index survives restarts. Audio comes from
 * YouTube ([stream]) and, when YouTube can't supply it, from the first of [alternates] with the
 * song's exact recording.
 */
class DesktopDownloads(
    root: File,
    private val scope: CoroutineScope,
    private val readTimeoutMs: Long = 60_000L,
    metadataFile: File = File(root, "index.json"),
    private val onRootChanged: (File) -> Unit = {},
    /** Null: JioSaavn and SoundCloud behind the real YouTube resolver, nothing behind a test's. */
    alternates: List<Source>? = null,
    private val stream: suspend (Track) -> String = YOUTUBE,
) {
    /** A catalog that may hold the song's exact recording: [find] returns a file or HLS playlist URL, or null. */
    class Source(val name: String, val find: suspend (Track) -> String?)

    private val alternates: List<Source> = alternates ?: if (stream === YOUTUBE) CATALOGS else emptyList()
    private val mutableRoot = MutableStateFlow(root)
    val rootDirectory = mutableRoot.asStateFlow()
    val root: File get() = mutableRoot.value

    data class Entry(val key: String, val groupId: String, val groupName: String, val folder: String,
        val track: Track, val index: Int, val status: String = "QUEUED", val file: String? = null,
        val bytes: Long = 0, val total: Long = 0, val error: String? = null, val coverUrl: String? = null, val directory: String? = null,
        /** Where the audio came from: "YouTube", or the catalog that stood in for it. */
        val source: String? = null)
    private val metadata = metadataFile
    private val mutable = MutableStateFlow<List<Entry>>(emptyList())
    val entries = mutable.asStateFlow()
    val tracks = entries.map { list -> list.filter(::available).map { it.track }.distinctBy { it.id } }
    val jobs = entries.map { list -> list.filter { it.groupId != "individual" }.groupBy { it.groupId }.map { (id, values) ->
        PlaylistDownloadJob(id, id, values.first().groupName,
            if (values.any { it.status in setOf("QUEUED", "DOWNLOADING") }) "DOWNLOADING"
            else if (values.all(::available)) "COMPLETED" else "PARTIAL_FAILURE",
            values.count(::available), values.count { it.status == "FAILED" }, 0)
    } }
    private val transferSlots = Semaphore(3)
    private val running = mutableMapOf<String, Job>()
    init {
        root.mkdirs()
        File(root, "Individual songs").mkdirs()
        File(root, "Playlists").mkdirs()
        if (metadata.isFile) runCatching {
            val array = JSONArray(metadata.readText())
            mutable.value = List(array.length()) { i ->
                val o = array.getJSONObject(i)
                val e = Entry(o.getString("key"), o.getString("group"), o.getString("name"), o.getString("folder"),
                    TrackJson.decode(o.getJSONObject("track")), o.optInt("index"), o.getString("status"),
                    o.optString("file").ifBlank { null }, o.optLong("bytes"), o.optLong("total"), o.optString("error").ifBlank { null }, o.optString("cover").ifBlank { null }, o.optString("directory").ifBlank { null },
                    o.optString("source").ifBlank { null })
                if (e.status != "COMPLETED" || !available(e)) e.copy(status = "FAILED", error = "Download interrupted or file missing. Retry.") else e
            }
        }
    }
    private fun safeFile(relative: String, directory: File = root): File {
        val target = File(directory, relative).canonicalFile
        require(target.toPath().startsWith(directory.canonicalFile.toPath()) && target != directory.canonicalFile) { "Invalid download path" }
        return target
    }
    fun available(e: Entry): Boolean = e.status == "COMPLETED" && e.file?.let { runCatching {
        safeFile(it, e.directory?.let(::File) ?: root).let { file -> file.isFile && file.length() == e.bytes && e.bytes > 1024 }
    }.getOrDefault(false) } == true
    fun localFile(id: String): File? = entries.value.firstOrNull { it.track.id == id && available(it) }?.let { e -> e.file?.let { safeFile(it, e.directory?.let(::File) ?: root) } }
    fun isDownloading(id: String) = entries.value.any { it.track.id == id && it.status in setOf("QUEUED", "DOWNLOADING") }
    fun groupTracks(id: String, complete: Boolean = false) = entries.value.filter { it.groupId == id && (!complete || available(it)) }.sortedBy { it.index }.map { it.track }

    @Synchronized private fun update(entry: Entry, save: Boolean = true) {
        mutable.value = entries.value.filterNot { it.key == entry.key } + entry
        if (save) persist()
    }
    @Synchronized private fun persist() {
        root.mkdirs()
        val array = JSONArray().apply { entries.value.forEach { e -> put(JSONObject()
            .put("key", e.key).put("group", e.groupId).put("name", e.groupName).put("folder", e.folder)
            .put("track", TrackJson.encode(e.track)).put("index", e.index).put("status", e.status)
            .put("file", e.file.orEmpty()).put("bytes", e.bytes).put("total", e.total).put("error", e.error.orEmpty()).put("cover", e.coverUrl.orEmpty()).put("directory", e.directory.orEmpty()).put("source", e.source.orEmpty())) } }
        metadata.parentFile.mkdirs()
        val tmp = File(metadata.parentFile, metadata.name + ".new")
        tmp.writeText(array.toString())
        Files.move(tmp.toPath(), metadata.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    @Synchronized fun enqueue(tracks: List<Track>, groupId: String = "individual", groupName: String = "Individual songs", coverUrl: String? = null) {
        require(groupId.isNotBlank())
        val existing = entries.value.firstOrNull { it.groupId == groupId }
        var folder = existing?.folder ?: if (groupId == "individual") "Individual songs" else "Playlists/${sanitize(groupName)}"
        if (entries.value.any { it.folder.equals(folder, true) && it.groupId != groupId }) folder += " (${hash(groupId)})"
        tracks.distinctBy { it.id }.filter { it.id.isNotBlank() }.forEachIndexed { index, track ->
            val key = hash("$groupId|${track.id}")
            val old = entries.value.firstOrNull { it.key == key }
            if (old != null && (available(old) || running[key]?.isActive == true)) return@forEachIndexed
            if (old != null && old.status == "FAILED") {
                DesktopStreamResolver.invalidate(track.id)
                com.auralis.music.data.network.RecordingMatches.matchedVideoIds.remove(track.id)
                com.auralis.music.data.network.RecordingMatches.matchedDurations.remove(track.id)
            }
            val e = old?.copy(status = "QUEUED", error = null, directory = root.absolutePath) ?: Entry(key, groupId, groupName, folder, track, index, coverUrl = coverUrl, directory = root.absolutePath)
            update(e, false)
            running[key] = scope.launch(Dispatchers.IO) {
                transferSlots.withPermit {
                    var lastError: Throwable? = null
                    val maxDownloadAttempts = 2
                    for (attempt in 1..maxDownloadAttempts) {
                        try {
                            if (attempt > 1) {
                                DesktopStreamResolver.invalidate(track.id)
                                com.auralis.music.data.network.RecordingMatches.matchedVideoIds.remove(track.id)
                                com.auralis.music.data.network.RecordingMatches.matchedDurations.remove(track.id)
                                delay(600L * attempt)
                            }
                            download(e, track)
                            lastError = null
                            break
                        } catch (err: Throwable) {
                            // Only canceling the download stops it; a lookup's own timeout is a failed attempt.
                            if (err is CancellationException) currentCoroutineContext().ensureActive()
                            lastError = err
                        }
                    }
                    if (lastError != null) {
                        update(e.copy(status = "FAILED", error = lastError.message ?: "Download failed"))
                    }
                }
            }
        }
        // Queue a large playlist in one disk write, rather than blocking the UI once per song.
        persist()
    }

    /**
     * Saves [track] from YouTube or, when YouTube can't supply it (requests paused or refused, no
     * matching upload, or its transfer failed), from the first alternate catalog that has the exact
     * recording and transfers completely. Throws, saying why, when no source could.
     */
    private suspend fun download(e: Entry, track: Track) {
        val youtubeError = try {
            save(e, track, stream(track), YOUTUBE_SOURCE)
            return
        } catch (err: Throwable) {
            if (err is CancellationException) currentCoroutineContext().ensureActive()
            if (alternates.isEmpty()) throw err
            err
        }
        var alternateError: Throwable? = null
        for (source in alternates) {
            val url = try {
                source.find(track)
            } catch (err: Throwable) {
                if (err is CancellationException) currentCoroutineContext().ensureActive()
                com.auralis.music.util.Log.w(TAG, "${source.name} lookup failed for ${track.id}: ${err.javaClass.simpleName}")
                null
            } ?: continue
            try {
                save(e, track, url, source.name)
                com.auralis.music.util.Log.i(TAG, "Saved '${track.title}' (${track.id}) from ${source.name}: ${youtubeError.message}")
                return
            } catch (err: Throwable) {
                if (err is CancellationException) currentCoroutineContext().ensureActive()
                com.auralis.music.util.Log.w(TAG, "${source.name} download failed for ${track.id}: ${err.message}")
                alternateError = err
            }
        }
        val why = youtubeError.message ?: "YouTube couldn't supply this song"
        error(alternateError?.let { "$why; ${it.message}" }
            ?: "$why; no exact copy on ${alternates.joinToString(" or ") { it.name }}")
    }

    /** Transfers [url] (a file, or another catalog's HLS playlist) and publishes it as [e]'s file. */
    private suspend fun save(e: Entry, track: Track, url: String, source: String) {
        update(e.copy(status = "DOWNLOADING", source = source))
        val client = NetworkClientProvider.okHttpClient.newBuilder()
            .followRedirects(true)
            .readTimeout(readTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .connectTimeout(30_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val base = e.directory?.let(::File) ?: root
        val name = "${sanitize(track.title, "Song")} (${hash(track.id)})"
        val stem = "${e.folder}/" + if (e.groupId == "individual") name else "%02d - %s".format(e.index + 1, name)
        val partial = safeFile("$stem.partial", base); partial.parentFile.mkdirs()
        var lastUpdate = 0L
        val progress = { bytes: Long, total: Long ->
            if (System.currentTimeMillis() - lastUpdate > 250) {
                update(e.copy(status = "DOWNLOADING", bytes = bytes, total = total, source = source), false)
                lastUpdate = System.currentTimeMillis()
            }
        }
        try {
            val playlist = source != YOUTUBE_SOURCE && HlsPlaylist.isPlaylist(url)
            val count = if (playlist) fetchPlaylist(client, url, partial, progress)
                else fetchFile(client, url, partial, youtube = source == YOUTUBE_SOURCE, progress)
            // Never publish an HTML/error page as a song; the extension is what the bytes are, not what the server said.
            val container = checkNotNull(AudioContainer.of(partial.inputStream().use { it.readNBytes(16) })) {
                "Downloaded file is not a supported audio container"
            }
            // Joined fragments would otherwise have no length, so the player could neither show one nor seek.
            if (playlist && container == AudioContainer.MP4) runCatching { FragmentedMp4.fillInDuration(partial) }
                .onFailure { com.auralis.music.util.Log.w(TAG, "Couldn't write the length of ${track.id}'s file: ${it.javaClass.simpleName}") }
            val relative = "$stem.${container.extension}"
            val dest = safeFile(relative, base)
            currentCoroutineContext().ensureActive()
            Files.move(partial.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            update(e.copy(status = "COMPLETED", file = relative, bytes = count, total = count, source = source))
        } finally {
            partial.delete()
        }
    }

    /** One progressive file, fetched in 2 MB ranges where the server allows (bypasses Google Video throttling). */
    private suspend fun fetchFile(client: okhttp3.OkHttpClient, url: String, partial: File, youtube: Boolean,
        progress: (Long, Long) -> Unit): Long {
        val chunkSize = 2 * 1024 * 1024L
        var count = 0L
        client.newCall(audioRequest(url, 0L until chunkSize)).execute().use { response ->
            if (response.code == 403 && youtube) DesktopStreamResolver.markHostFailed(url)
            check(response.isSuccessful || response.code == 206) { "Audio request failed (HTTP ${response.code})" }
            val mime = response.header("Content-Type").orEmpty()
            require(!mime.contains("text/") && !mime.contains("json")) { "Server returned an error instead of audio" }
            partial.outputStream().use { out ->
                if (response.code == 206) {
                    val totalLength = response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
                    response.body?.byteStream()?.use { input -> count += copy(input, out) { progress(count + it, totalLength) } }
                    var start = count
                    while (start < totalLength) {
                        currentCoroutineContext().ensureActive()
                        val end = minOf(start + chunkSize - 1, totalLength - 1)
                        client.newCall(audioRequest(url, start..end)).execute().use { chunk ->
                            if (chunk.code == 403 && youtube) DesktopStreamResolver.markHostFailed(url)
                            check(chunk.code == 206 || chunk.isSuccessful) { "Chunk transfer failed (HTTP ${chunk.code})" }
                            chunk.body?.byteStream()?.use { input -> count += copy(input, out) { progress(count + it, totalLength) } }
                        }
                        start = end + 1
                    }
                    check(count > 1024 && (totalLength <= 0 || count == totalLength)) { "Audio download was incomplete" }
                } else {
                    // Servers that ignore Range send the whole file at once.
                    val body = response.body ?: error("Empty audio response")
                    val total = body.contentLength()
                    body.byteStream().use { input -> count += copy(input, out) { progress(count + it, total) } }
                    check(count > 1024 && (total < 0 || count == total)) { "Audio download was incomplete" }
                }
            }
        }
        return count
    }

    /**
     * An HLS recording (SoundCloud serves many uploads only this way): the best variant's init
     * segment and every media segment, appended in order, which is one playable file.
     */
    private suspend fun fetchPlaylist(client: okhttp3.OkHttpClient, url: String, partial: File, progress: (Long, Long) -> Unit): Long {
        var parsed = HlsPlaylist.parse(fetchText(client, url), url)
        if (parsed is HlsPlaylist.Parsed.Variants) {
            val variant = parsed.urls.first()
            parsed = HlsPlaylist.parse(fetchText(client, variant), variant)
        }
        val media = parsed as? HlsPlaylist.Parsed.Media ?: error("HLS playlist has no media segments")
        val parts = listOfNotNull(media.init) + media.segments
        var count = 0L
        partial.outputStream().use { out ->
            parts.forEachIndexed { index, part ->
                currentCoroutineContext().ensureActive()
                // The whole size is known only at the end; until then it is extrapolated from the parts so far.
                val estimate = { bytes: Long -> bytes * parts.size / (index + 1) }
                client.newCall(audioRequest(part.url, part.range)).execute().use { response ->
                    check(response.isSuccessful) { "Audio segment request failed (HTTP ${response.code})" }
                    check(part.range == null || response.code == 206) { "Server ignored an HLS byte range" }
                    val body = response.body ?: error("Empty audio segment")
                    val expected = part.range?.let { it.last - it.first + 1 } ?: body.contentLength()
                    val written = body.byteStream().use { input -> copy(input, out) { progress(count + it, estimate(count + it)) } }
                    check(written > 0 && (expected < 0 || written == expected)) { "Audio segment was incomplete" }
                    count += written
                }
            }
        }
        check(count > 1024) { "Audio download was incomplete" }
        return count
    }

    private fun fetchText(client: okhttp3.OkHttpClient, url: String): String =
        client.newCall(audioRequest(url, null)).execute().use { response ->
            check(response.isSuccessful) { "Playlist request failed (HTTP ${response.code})" }
            val body = response.body ?: error("Empty playlist")
            check(body.contentLength() <= MAX_PLAYLIST_BYTES) { "Playlist is too large" }
            String(body.byteStream().use { it.readNBytes(MAX_PLAYLIST_BYTES.toInt() + 1) }, Charsets.UTF_8)
                .also { check(it.length <= MAX_PLAYLIST_BYTES) { "Playlist is too large" } }
        }

    private fun audioRequest(url: String, range: LongRange?) = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "*/*")
        .apply { if (range != null) header("Range", "bytes=${range.first}-${range.last}") }
        .build()

    /** Copies [input] to [out], reporting the bytes copied so far; returns the total. */
    private suspend fun copy(input: java.io.InputStream, out: java.io.OutputStream, copied: (Long) -> Unit): Long {
        val buffer = ByteArray(128 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer); if (n < 0) break
            out.write(buffer, 0, n); total += n
            copied(total)
        }
        return total
    }
    @Synchronized fun remove(key: String) {
        running.remove(key)?.cancel()
        val e = entries.value.firstOrNull { it.key == key } ?: return
        val base = e.directory?.let(::File) ?: root
        val file = e.file?.let { safeFile(it, base) }
        check(file == null || !file.exists() || file.delete()) { "Couldn't remove ${e.track.title}. Stop playing it and try again." }
        mutable.value = entries.value.filterNot { it.key == key }; persist()
        val folder = file?.parentFile ?: safeFile(e.folder, base)
        pruneEmptyDownloadFolders(folder, base,
            base.canonicalFile == File(System.getProperty("user.home"), "Downloads/Auralis").canonicalFile)
    }
    fun removeTrack(id: String) = entries.value.filter { it.track.id == id }.forEach { remove(it.key) }
    fun removeGroup(id: String) = entries.value.filter { it.groupId == id }.forEach { remove(it.key) }
    fun clear() = entries.value.toList().forEach { remove(it.key) }
    fun retry(id: String) {
        val group = entries.value.filter { it.groupId == id }
        if (group.isNotEmpty()) enqueue(group.map { it.track }, id, group.first().groupName, group.first().coverUrl)
    }
    /** Existing files keep their recorded directory; the chosen location applies to new downloads. */
    @Synchronized fun changeDirectory(chosen: File, transferExisting: Boolean = false): Int {
        val directory = chosen.canonicalFile
        require(directory.isDirectory || directory.mkdirs()) { "Couldn't create this folder." }
        File(directory, "Individual songs").mkdirs()
        File(directory, "Playlists").mkdirs()
        val probe = File.createTempFile(".auralis-write-", ".tmp", directory)
        probe.delete()
        val previous = root.absolutePath
        if (directory == root.canonicalFile) return 0
        mutable.value = entries.value.map { if (it.directory == null) it.copy(directory = previous) else it }
        persist()
        onRootChanged(directory)
        mutableRoot.value = directory
        return if (transferExisting) moveExistingDownloads() else 0
    }

    fun isOutsideCurrentRoot(e: Entry): Boolean {
        val rel = e.file?.takeIf { it.isNotBlank() } ?: return false
        val base = e.directory?.takeIf { it.isNotBlank() }?.let(::File)?.canonicalFile ?: return false
        val current = root.canonicalFile
        if (base == current) return false
        val sourceFile = runCatching { safeFile(rel, base) }.getOrNull() ?: return false
        return sourceFile.exists()
    }

    fun countFilesOutsideCurrentRoot(): Int = entries.value.count(::isOutsideCurrentRoot)

    fun countGroupFilesOutsideCurrentRoot(groupId: String): Int = entries.value.count { it.groupId == groupId && isOutsideCurrentRoot(it) }

    @Synchronized fun moveExistingDownloads(predicate: (Entry) -> Boolean = { true }): Int {
        val targetRoot = root.canonicalFile
        targetRoot.mkdirs()
        File(targetRoot, "Individual songs").mkdirs()
        File(targetRoot, "Playlists").mkdirs()
        val toMove = entries.value.filter { predicate(it) && isOutsideCurrentRoot(it) }
        if (toMove.isEmpty()) return 0

        var movedCount = 0
        val updatedEntries = entries.value.toMutableList()
        val defaultHome = File(System.getProperty("user.home"), "Downloads/Auralis").canonicalFile

        for (e in toMove) {
            val rel = e.file ?: continue
            val sourceBase = (e.directory?.takeIf { it.isNotBlank() }?.let(::File) ?: targetRoot).canonicalFile
            if (sourceBase == targetRoot) continue

            val targetFile = runCatching { safeFile(rel, targetRoot) }.getOrNull() ?: continue
            var sourceFile = runCatching { safeFile(rel, sourceBase) }.getOrNull()
            if (sourceFile == null || !sourceFile.exists()) {
                val parentFallback = runCatching { safeFile(rel, targetRoot.parentFile ?: targetRoot) }.getOrNull()
                if (parentFallback != null && parentFallback.exists() && parentFallback.canonicalFile != targetFile.canonicalFile) {
                    sourceFile = parentFallback
                }
            }
            if (sourceFile == null || !sourceFile.exists()) continue
            if (sourceFile.canonicalFile == targetFile.canonicalFile) continue

            targetFile.parentFile.mkdirs()
            try {
                Files.move(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.delete(sourceFile.toPath())
            }
            runCatching {
                val removeRoot = (sourceBase == defaultHome || sourceBase.name.equals("Auralis", ignoreCase = true))
                pruneEmptyDownloadFolders(sourceFile.parentFile, sourceBase, removeRoot)
            }

            val index = updatedEntries.indexOfFirst { it.key == e.key }
            if (index >= 0) {
                updatedEntries[index] = e.copy(directory = targetRoot.absolutePath)
            }
            movedCount++
        }

        val distinctSources = toMove.mapNotNull { it.directory?.takeIf { d -> d.isNotBlank() }?.let(::File)?.canonicalFile }.distinct()
        for (sourceBase in distinctSources) {
            if (sourceBase.exists() && sourceBase != targetRoot) {
                val removeRoot = (sourceBase == defaultHome || sourceBase.name.equals("Auralis", ignoreCase = true))
                runCatching { pruneEmptyDownloadFolders(sourceBase, sourceBase, removeRoot) }
            }
        }

        mutable.value = updatedEntries
        persist()
        return movedCount
    }

    fun moveExistingDownloadsToCurrentRoot(): Int = moveExistingDownloads()

    fun moveGroupToCurrentRoot(groupId: String): Int = moveExistingDownloads { it.groupId == groupId }

    companion object {
        private const val TAG = "DesktopDownloads"
        private const val YOUTUBE_SOURCE = "YouTube"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024

        /** The matched YouTube upload's audio stream; throws saying why when there is none. */
        private val YOUTUBE: suspend (Track) -> String = { track ->
            // Downloads wait behind playback (background priority) under their own allowance, and
            // fail at once while YouTube requests are paused instead of adding to the refusals.
            val budget = com.auralis.music.data.network.RequestBudget(com.auralis.music.data.network.RequestBudget.newId("d"), "download",
                deadlineMs = 45_000L)
            kotlinx.coroutines.withContext(com.auralis.music.data.network.PlaybackIntentContext(budget)) {
                val id = DesktopPlaybackMatch.videoId(track) ?: error("Could not match this song to playable audio")
                when (val result = DesktopStreamResolver.resolveResult(id, com.auralis.music.desktop.playback.RequestPriority.BACKGROUND)) {
                    is com.auralis.music.desktop.playback.ResolutionResult.Resolved -> result.resource.url
                    is com.auralis.music.desktop.playback.ResolutionResult.Deferred ->
                        error("YouTube requests are paused (${result.failure.category}); try again in ${(result.retryInMs + 59_999) / 60_000} min")
                    is com.auralis.music.desktop.playback.ResolutionResult.Failed -> error("Could not resolve audio (${result.failure.category})")
                    else -> error("Could not resolve audio")
                }
            }
        }

        /**
         * The catalogs asked after YouTube, in order: JioSaavn's file (320 kbps when it has one),
         * then SoundCloud. Both return only the song's exact recording (RecordingMatch), never a
         * remix, live take or other cut.
         */
        val CATALOGS: List<Source> = listOf(
            Source("JioSaavn", DesktopJioSaavnAudio::find320),
            Source("SoundCloud", DesktopSoundCloudAudio::find),
        )

        fun resolveDownloadDirectory(chosen: File): File {
            val canonical = chosen.canonicalFile
            return if (canonical.name.equals("Auralis", ignoreCase = true)) {
                canonical
            } else {
                File(canonical, "Auralis").canonicalFile
            }
        }

        val instance by lazy {
            val preferences = com.auralis.music.util.PropertiesFileKeyValueStore(File(desktopAppDataDir(), "download-location.properties"))
            val configured = preferences.getString("directory", "")?.takeIf { it.isNotBlank() }?.let(::File)
            val root = configured?.let(::resolveDownloadDirectory)
                ?: File(System.getProperty("user.home"), "Downloads/Auralis")
            DesktopDownloads(root, CoroutineScope(SupervisorJob() + Dispatchers.IO),
                metadataFile = File(desktopAppDataDir(), "download-index.json"),
                onRootChanged = { preferences.putStrings(mapOf("directory" to it.absolutePath)) })
        }
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        fun sanitize(value: String, fallback: String = "Playlist"): String {
            val clean = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\.{2,}"), " ").trim().trimEnd('.', ' ').take(80).ifBlank { fallback }
            return if (Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?").matches(clean)) "_$clean" else clean
        }
    }
}
