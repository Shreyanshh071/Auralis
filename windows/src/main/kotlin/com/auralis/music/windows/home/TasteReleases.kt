package com.auralis.music.windows.home

import com.auralis.music.data.network.YouTubeBackoff
import com.auralis.music.desktop.library.DesktopListeningStats
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * "Albums & singles" from the listener's own taste, recomputed as they listen: artists are
 * weighted by measured listening time (halving every week of age), and the shelf interleaves
 * releases from the top artists in that order. Each artist's releases are looked up once
 * (YouTube Music album search) and cached to disk, two at a time, never while YouTube is blocking.
 *
 * Seeded immediately on Frame 0 from the listener's own listening history albums so it is
 * always personalized without waiting on network lookups or failing when offline.
 */
internal class TasteReleases(
    private val stats: DesktopListeningStats,
    private val search: SearchRepository,
    private val scope: CoroutineScope
) {
    private val mutable = MutableStateFlow<DesktopHomeShelf?>(null)
    val shelf: StateFlow<DesktopHomeShelf?> = mutable.asStateFlow()
    private val releasesByArtist = ConcurrentHashMap<String, List<PlaylistResult>>()
    private val lookups = Semaphore(2)

    private val cacheFile by lazy {
        File(com.auralis.music.util.desktopAppDataDir(), "cache/taste-releases.json")
    }

    init {
        loadDiskCache()
    }

    private fun loadDiskCache() {
        runCatching {
            if (!cacheFile.exists()) return@runCatching
            val text = cacheFile.readText()
            if (text.isBlank()) return@runCatching
            val obj = JSONObject(text)
            obj.keys().forEach { key ->
                val arr = obj.optJSONArray(key) ?: return@forEach
                val list = mutableListOf<PlaylistResult>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val title = item.optString("title")
                    val thumb = item.optString("thumbnail").takeIf(String::isNotBlank)
                    val author = item.optString("author").takeIf(String::isNotBlank)
                    if (id.isNotBlank() && title.isNotBlank()) {
                        list += PlaylistResult(id, title, thumb, author)
                    }
                }
                if (list.isNotEmpty()) {
                    releasesByArtist[key.lowercase()] = list
                }
            }
        }
    }

    private fun saveDiskCache() {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val obj = JSONObject()
            releasesByArtist.forEach { (artistKey, list) ->
                val arr = JSONArray()
                list.forEach { item ->
                    val o = JSONObject()
                    o.put("id", item.id)
                    o.put("title", item.title)
                    o.put("thumbnail", item.thumbnail.orEmpty())
                    o.put("author", item.author.orEmpty())
                    arr.put(o)
                }
                obj.put(artistKey, arr)
            }
            val temp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            temp.writeText(obj.toString())
            temp.renameTo(cacheFile)
        }
    }

    fun start() {
        // Immediate Frame 0 build from existing listening stats + disk cache
        scope.launch {
            val initialListens = stats.listens.value
            if (initialListens.isNotEmpty()) {
                val artists = topArtists(initialListens)
                rebuild(artists, initialListens)
            }
        }

        // Live updates as the listener plays more songs
        scope.launch {
            stats.listens
                .map { listens -> topArtists(listens) to listens.count { it.countsAsPlay } }
                .distinctUntilChanged()
                .collect { (artists, _) ->
                    rebuild(artists, stats.listens.value)
                }
        }
    }

    private suspend fun rebuild(artists: List<String>, listens: List<DesktopListeningStats.Listen>) {
        if (artists.isEmpty()) {
            mutable.value = null
            return
        }

        // Step 1: Ensure every top artist has at least their locally listened albums
        var updatedLocal = false
        artists.forEach { name ->
            val key = name.lowercase()
            if (!releasesByArtist.containsKey(key)) {
                val local = localAlbumsForArtist(listens, name)
                if (local.isNotEmpty()) {
                    releasesByArtist[key] = local
                    updatedLocal = true
                }
            }
        }

        // Emit current best shelf immediately (zero network latency)
        updateShelf(artists)
        if (updatedLocal) {
            saveDiskCache()
        }

        // Step 2: In the background, enrich any artists who haven't had full online discography searches
        val missingOnline = artists.filter { name ->
            val key = name.lowercase()
            val existing = releasesByArtist[key]
            existing.isNullOrEmpty() || existing.none { it.id.startsWith("MPRE") || it.id.startsWith("OLAK") || it.id.startsWith("VL") || it.id.startsWith("PL") }
        }

        if (missingOnline.isNotEmpty()) {
            scope.launch {
                if (YouTubeBackoff.active) {
                    YouTubeBackoff.awaitClear()
                }

                var anyNew = false
                missingOnline.forEach { name ->
                    lookups.withPermit {
                        if (YouTubeBackoff.active) {
                            YouTubeBackoff.awaitClear()
                        }
                        val found = runCatching { search.searchAlbums(name) }.getOrNull().orEmpty()
                        val valid = found
                            .filter { SearchQueryMatcher.isAuthorMatch(it.author, name) && !it.id.startsWith("UC") }
                            .distinctBy { it.id }
                            .take(6)

                        if (valid.isNotEmpty()) {
                            val local = localAlbumsForArtist(listens, name)
                            val merged = (valid + local).distinctBy { it.title.trim().lowercase() }.take(6)
                            releasesByArtist[name.lowercase()] = merged
                            anyNew = true
                        }
                    }
                }

                if (anyNew) {
                    saveDiskCache()
                    updateShelf(artists)
                }
            }
        }
    }

    private fun updateShelf(artists: List<String>) {
        val perArtist = artists.map { releasesByArtist[it.lowercase()].orEmpty() }
        val mixed = buildList {
            for (round in 0 until 6) {
                perArtist.forEach { list ->
                    list.getOrNull(round)?.let(::add)
                }
            }
        }.distinctBy { it.id }.take(24)

        if (mixed.isNotEmpty()) {
            mutable.value = DesktopHomeShelf("taste-releases", "Albums & singles", albums = mixed)
        }
    }

    companion object {
        private const val HALF_LIFE_MS = 7L * 24 * 60 * 60 * 1000

        /** Extracts albums and singles from listens for a given artist name. */
        fun localAlbumsForArtist(listens: List<DesktopListeningStats.Listen>, artistName: String): List<PlaylistResult> {
            val normTarget = SearchQueryMatcher.normalize(artistName)
            val artistListens = listens.filter { listen ->
                val artist = listen.track.artist
                SearchQueryMatcher.isAuthorMatch(artist, artistName) ||
                    SearchQueryMatcher.normalize(artist).contains(normTarget) ||
                    normTarget.contains(SearchQueryMatcher.normalize(artist))
            }
            if (artistListens.isEmpty()) return emptyList()

            val albums = mutableListOf<PlaylistResult>()
            val seen = mutableSetOf<String>()

            // 1. Group by album title if available
            val withAlbum = artistListens.filter { !it.track.album.isNullOrBlank() }
            val groupedAlbums = withAlbum.groupBy { it.track.album!!.trim().lowercase() }
            for ((_, albumListens) in groupedAlbums) {
                val first = albumListens.first()
                val albumTitle = first.track.album!!.trim()
                val key = albumTitle.lowercase()
                if (key !in seen) {
                    seen += key
                    val albumId = first.track.albumId?.takeIf { it.isNotBlank() } ?: "album:${first.track.id}"
                    val thumb = albumListens.firstNotNullOfOrNull { it.track.thumbnail.takeIf(String::isNotBlank) }.orEmpty()
                    albums += PlaylistResult(albumId, albumTitle, thumb, first.track.artist)
                }
            }

            // 2. Add singles/standalones if fewer than 4 albums
            if (albums.size < 4) {
                val standalones = artistListens.filter { it.track.album.isNullOrBlank() }.sortedByDescending { it.timeMs }
                for (single in standalones) {
                    val key = single.track.title.trim().lowercase()
                    if (key !in seen) {
                        seen += key
                        albums += PlaylistResult(
                            id = "album:${single.track.id}",
                            title = single.track.title,
                            thumbnail = single.track.thumbnail,
                            author = "${single.track.artist} · Single"
                        )
                        if (albums.size >= 4) break
                    }
                }
            }

            return albums.take(6)
        }

        /** The listener's top artists by recency-weighted listening time (first credited artist). */
        fun topArtists(listens: List<DesktopListeningStats.Listen>, limit: Int = 10, now: Long = System.currentTimeMillis()): List<String> {
            val weights = HashMap<String, Pair<String, Double>>()
            for (listen in listens) {
                val name = listen.track.artist
                    .split(Regex("""\s*(?:,|&| feat\.? | ft\.? | x )\s*""", RegexOption.IGNORE_CASE))
                    .firstOrNull()?.trim()?.removeSuffix(" - Topic")?.takeIf { it.isNotBlank() && it != "Unknown Artist" } ?: continue
                val age = (now - listen.startedAt).coerceAtLeast(0L)
                val weight = listen.timeMs * Math.pow(0.5, age.toDouble() / HALF_LIFE_MS)
                val key = name.lowercase()
                val (shown, total) = weights[key] ?: (name to 0.0)
                weights[key] = shown to total + weight
            }
            return weights.values.sortedByDescending { it.second }.take(limit).map { it.first }
        }
    }
}
