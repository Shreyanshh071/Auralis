package com.auralis.music.desktop.library

import com.auralis.music.desktop.playback.DesktopPlaybackMatch
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
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Files are published only after complete transfer; the index survives restarts. */
class DesktopDownloads(
    root: File,
    private val scope: CoroutineScope,
    private val readTimeoutMs: Long = 60_000L,
    metadataFile: File = File(root, "index.json"),
    private val onRootChanged: (File) -> Unit = {},
    private val stream: suspend (Track) -> String = { track ->
        val id = DesktopPlaybackMatch.videoId(track) ?: error("Could not match this song to playable audio")
        val url = DesktopStreamResolver.resolve(id) ?: error("Could not resolve audio")
        LocalStreamRelay.register(url, refresh = { DesktopStreamResolver.refreshBlocking(id) })
    }
) {
    private val mutableRoot = MutableStateFlow(root)
    val rootDirectory = mutableRoot.asStateFlow()
    val root: File get() = mutableRoot.value

    data class Entry(val key: String, val groupId: String, val groupName: String, val folder: String,
        val track: Track, val index: Int, val status: String = "QUEUED", val file: String? = null,
        val bytes: Long = 0, val total: Long = 0, val error: String? = null, val coverUrl: String? = null, val directory: String? = null)
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
    private val transfers = Mutex()
    private val running = mutableMapOf<String, Job>()
    init {
        if (metadata.isFile) runCatching {
            val array = JSONArray(metadata.readText())
            mutable.value = List(array.length()) { i ->
                val o = array.getJSONObject(i)
                val e = Entry(o.getString("key"), o.getString("group"), o.getString("name"), o.getString("folder"),
                    TrackJson.decode(o.getJSONObject("track")), o.optInt("index"), o.getString("status"),
                    o.optString("file").ifBlank { null }, o.optLong("bytes"), o.optLong("total"), o.optString("error").ifBlank { null }, o.optString("cover").ifBlank { null }, o.optString("directory").ifBlank { null })
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
            .put("file", e.file.orEmpty()).put("bytes", e.bytes).put("total", e.total).put("error", e.error.orEmpty()).put("cover", e.coverUrl.orEmpty()).put("directory", e.directory.orEmpty())) } }
        metadata.parentFile.mkdirs()
        val tmp = File(metadata.parentFile, metadata.name + ".new")
        tmp.writeText(array.toString())
        Files.move(tmp.toPath(), metadata.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    @Synchronized fun enqueue(tracks: List<Track>, groupId: String = "individual", groupName: String = "Individual songs", coverUrl: String? = null) {
        require(groupId.isNotBlank())
        val existing = entries.value.firstOrNull { it.groupId == groupId }
        var folder = existing?.folder ?: sanitize(groupName)
        if (entries.value.any { it.folder.equals(folder, true) && it.groupId != groupId }) folder += " (${hash(groupId)})"
        tracks.distinctBy { it.id }.filter { it.id.isNotBlank() }.forEachIndexed { index, track ->
            val key = hash("$groupId|${track.id}")
            val old = entries.value.firstOrNull { it.key == key }
            if (old != null && (available(old) || running[key]?.isActive == true)) return@forEachIndexed
            val e = old?.copy(status = "QUEUED", error = null, directory = root.absolutePath) ?: Entry(key, groupId, groupName, folder, track, index, coverUrl = coverUrl, directory = root.absolutePath)
            update(e, false)
            running[key] = scope.launch(Dispatchers.IO) {
                transfers.withLock {
                    var target: File? = null
                    try {
                        val url = stream(track)
                        update(e.copy(status = "DOWNLOADING"))
                        val client = NetworkClientProvider.okHttpClient.newBuilder().readTimeout(readTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS).build()
                        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                            check(response.isSuccessful) { "Audio request failed (HTTP ${response.code})" }
                            val body = response.body ?: error("Empty audio response")
                            val mime = response.header("Content-Type").orEmpty()
                            require(!mime.contains("text/") && !mime.contains("json")) { "Server returned an error instead of audio" }
                            val extension = if (mime.contains("webm")) "webm" else if (mime.contains("ogg")) "ogg" else "m4a"
                            val name = "${sanitize(track.title, "Song")} (${hash(track.id)}).$extension"
                            val relative = "${e.folder}/" + if (groupId == "individual") name else "%02d - %s".format(e.index + 1, name)
                            val dest = safeFile(relative, e.directory?.let(::File) ?: root); dest.parentFile.mkdirs()
                            val partial = File(dest.parentFile, dest.name + ".partial"); target = partial
                            val total = body.contentLength()
                            var count = 0L; var lastUpdate = 0L
                            partial.outputStream().use { out -> body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val n = input.read(buffer); if (n < 0) break
                                    out.write(buffer, 0, n); count += n
                                    if (System.currentTimeMillis() - lastUpdate > 250) {
                                        update(e.copy(status = "DOWNLOADING", bytes = count, total = total), false)
                                        lastUpdate = System.currentTimeMillis()
                                    }
                                }
                            } }
                            check(count > 1024 && (total < 0 || count == total)) { "Audio download was incomplete" }
                            // Basic container signature check: never publish an HTML/error page as a song.
                            val header = partial.inputStream().use { it.readNBytes(16) }
                            check(header.size >= 8 && (String(header, 4, 4) == "ftyp" ||
                                header.take(4).map { it.toInt() and 255 } == listOf(0x1a, 0x45, 0xdf, 0xa3) || String(header, 0, 4) == "OggS")) { "Downloaded file is not a supported audio container" }
                            coroutineContext.ensureActive()
                            Files.move(partial.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            update(e.copy(status = "COMPLETED", file = relative, bytes = count, total = count))
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { update(e.copy(status = "FAILED", error = error.message ?: "Download failed")) }
                    finally {
                        target?.delete()
                        if (entries.value.none { it.key == e.key }) target?.parentFile?.let {
                            val base = e.directory?.let(::File) ?: root
                            pruneEmptyDownloadFolders(it, base, base.canonicalFile == File(System.getProperty("user.home"), "Downloads/Auralis").canonicalFile)
                        }
                    }
                }
            }
        }
        // Queue a large playlist in one disk write, rather than blocking the UI once per song.
        persist()
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
    @Synchronized fun changeDirectory(chosen: File) {
        val directory = chosen.canonicalFile
        require(directory.isDirectory || directory.mkdirs()) { "Couldn't create this folder." }
        val probe = File.createTempFile(".auralis-write-", ".tmp", directory)
        probe.delete()
        val previous = root.absolutePath
        if (directory == root.canonicalFile) return
        mutable.value = entries.value.map { if (it.directory == null) it.copy(directory = previous) else it }
        persist()
        onRootChanged(directory)
        mutableRoot.value = directory
    }

    companion object {
        val instance by lazy {
            val preferences = com.auralis.music.util.PropertiesFileKeyValueStore(File(desktopAppDataDir(), "download-location.properties"))
            val root = preferences.getString("directory", "")?.takeIf { it.isNotBlank() }?.let(::File)
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
