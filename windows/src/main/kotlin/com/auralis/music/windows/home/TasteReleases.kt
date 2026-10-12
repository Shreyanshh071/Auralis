package com.auralis.music.windows.home

import com.auralis.music.data.network.YouTubeBackoff
import com.auralis.music.desktop.library.DesktopListeningStats
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * "Albums & singles" from the listener's own taste, recomputed as they listen: artists are
 * weighted by measured listening time (halving every week of age), and the shelf interleaves
 * releases from the top artists in that order. Each artist's releases are looked up once
 * (YouTube Music album search) and cached, two at a time, never while YouTube is blocking.
 */
internal class TasteReleases(private val stats: DesktopListeningStats, private val search: SearchRepository, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow<DesktopHomeShelf?>(null)
    val shelf: StateFlow<DesktopHomeShelf?> = mutable.asStateFlow()
    private val releasesByArtist = ConcurrentHashMap<String, List<PlaylistResult>>()
    private val lookups = Semaphore(2)

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            // A new completed play (or a shift in the top artists) recomputes; mid-song flushes don't.
            stats.listens
                .map { listens -> topArtists(listens) to listens.count { it.countsAsPlay } }
                .distinctUntilChanged()
                .debounce(3_000)
                .collect { (artists, _) -> rebuild(artists) }
        }
    }

    private suspend fun rebuild(artists: List<String>) {
        if (artists.isEmpty()) { mutable.value = null; return }
        val missing = artists.filter { !releasesByArtist.containsKey(it.lowercase()) }
        if (missing.isNotEmpty() && !YouTubeBackoff.active) {
            kotlinx.coroutines.coroutineScope {
                missing.map { name ->
                    async {
                        lookups.withPermit {
                            val found = runCatching { search.searchAlbums(name) }.getOrNull() ?: return@withPermit
                            releasesByArtist[name.lowercase()] = found
                                .filter { SearchQueryMatcher.isAuthorMatch(it.author, name) && !it.id.startsWith("UC") }
                                .distinctBy { it.id }
                                .take(6)
                        }
                    }
                }.awaitAll()
            }
        }
        // Round-robin by rank: each artist's best release, then each one's second, and so on.
        val perArtist = artists.map { releasesByArtist[it.lowercase()].orEmpty() }
        val mixed = buildList {
            for (round in 0 until 6) perArtist.forEach { list -> list.getOrNull(round)?.let(::add) }
        }.distinctBy { it.id }.take(24)
        mutable.value = if (mixed.size >= 4) DesktopHomeShelf("taste-releases", "Albums & singles", albums = mixed) else null
    }

    companion object {
        private const val HALF_LIFE_MS = 7L * 24 * 60 * 60 * 1000

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
