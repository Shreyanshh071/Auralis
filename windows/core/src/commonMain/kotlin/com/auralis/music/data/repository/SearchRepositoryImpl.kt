package com.auralis.music.data.repository

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.remote.InvidiousApi
import com.auralis.music.data.remote.PipedApi
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.SearchTopResult
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.domain.search.SearchQueryMatcher
import com.auralis.music.data.network.AlbumMetadataResolver
import com.auralis.music.data.network.NetworkClientProvider
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Dedicated YouTube Music search repository powered by InnerTube WEB_REMIX API
 * and live autocomplete suggestions.
 */
class SearchRepositoryImpl(
    private val innerTubeClient: InnerTubeClient,
    private val suggestionsClient: SearchSuggestionsClient,
    private val searchHistory: SearchHistoryStore
) : SearchRepository {

    override suspend fun search(query: String): SearchResults = search(query) {}

    // Finished searches: searching the same thing again shows its results at once while they refresh.
    private data class FinishedSearch(val timeMs: Long, val results: SearchResults)
    private val finishedSearches = java.util.concurrent.ConcurrentHashMap<String, FinishedSearch>()

    override fun cachedSearch(query: String): SearchResults? =
        finishedSearches[query.trim().lowercase()]?.takeIf { System.nanoTime() / 1_000_000 - it.timeMs < FINISHED_SEARCH_TTL_MS }?.results

    private fun rememberFinished(query: String, results: SearchResults) {
        if (finishedSearches.size >= 32) finishedSearches.entries.minByOrNull { it.value.timeMs }?.let { finishedSearches.remove(it.key, it.value) }
        finishedSearches[query.lowercase()] = FinishedSearch(System.nanoTime() / 1_000_000, results)
    }

    /** The release-card lookups started early for the song most likely to become the top result. */
    private class EarlyReleaseLookup(
        val trackId: String,
        val albumId: String?,
        /** The album as soon as iTunes named it (no YouTube Music page yet). */
        val named: java.util.concurrent.atomic.AtomicReference<com.auralis.music.data.network.ResolvedAlbum?>,
        val resolver: kotlinx.coroutines.Deferred<ResolverAnswer?>,
        val releaseType: kotlinx.coroutines.Deferred<String?>?
    )

    private fun <T> kotlinx.coroutines.Deferred<T>.getCompletedOrNull(): T? =
        if (isCompleted && !isCancelled) @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) getCompleted() else null

    /** A finished album lookup: [album] is null when it found nothing. */
    private class ResolverAnswer(val album: com.auralis.music.data.network.ResolvedAlbum?)

    private companion object {
        /** How long a stuck core request may hold back ranking once another has answered. */
        const val CORE_WAIT_MS = 2_500L
        /** Extra wait for albums/artists once songs and general results are in. */
        const val CORE_GRACE_MS = 800L
        /** How long the ranked result may wait for its release card's lookups (album / Single). */
        const val RELEASE_CARD_WAIT_MS = 400L
        /** Partial results are shown only if the ranked result is not ready this long after the first answer. */
        const val PREVIEW_AFTER_MS = 600L
        const val FINISHED_SEARCH_TTL_MS = 30 * 60_000L
    }

    override suspend fun search(query: String, onResults: (SearchResults) -> Unit): SearchResults = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext SearchResults()
        // A recent search shows straight away and refreshes quietly: no previews or half-built
        // results replace it, only the refreshed result at the end.
        val cached = cachedSearch(trimmed)
        if (cached != null) onResults(cached)
        val emit: (SearchResults) -> Unit = if (cached != null) { _ -> } else onResults
        // One summary line per search (tag AuralisSearch): when each request answered and what
        // the supplementary lookups cost, so slow or failing searches can be read from logcat.
        val startedNs = System.nanoTime()
        val timings = java.util.concurrent.ConcurrentHashMap<String, String>()

        val fresh = coroutineScope {
            fun elapsedMs() = (System.nanoTime() - startedNs) / 1_000_000
            suspend fun <T> timed(label: String, block: suspend () -> T): T {
                val from = System.nanoTime()
                try { return block() } finally { timings[label] = "${(System.nanoTime() - from) / 1_000_000}ms" }
            }
            val responses = kotlinx.coroutines.channels.Channel<SearchResults>(4)
            suspend fun fetch(params: String?): SearchResults {
                val result = try { innerTubeClient.search(trimmed, params) }
                catch (e: Exception) { kotlin.coroutines.coroutineContext.ensureActive(); SearchResults() }
                timings[InnerTubeClient.searchFilterName(params)] = "${elapsedMs()}ms" + if (result.requestFailed) " FAILED" else ""
                responses.send(result)
                return result
            }
            val coreParams = listOf(InnerTubeClient.FILTER_SONGS, InnerTubeClient.FILTER_ALBUMS, InnerTubeClient.FILTER_ARTISTS, null)
            val coreRequests = coreParams.map { params -> async { fetch(params) } }

            // Spelling suggestions are supplementary and cannot hold the submitted search open.
            val topSuggestionDeferred = async {
                withTimeoutOrNull(3_000L) {
                    if (trimmed.length < 2) emptyList() else {
                        try {
                            val best = suggestionsClient.getSuggestions(trimmed).firstOrNull {
                                it.isNotBlank() && !it.equals(trimmed, ignoreCase = true)
                            }
                            if (best != null) innerTubeClient.search(best, InnerTubeClient.FILTER_SONGS).songs else emptyList()
                        } catch (e: Exception) { kotlin.coroutines.coroutineContext.ensureActive(); emptyList() }
                    }
                } ?: emptyList()
            }
            // Rank as soon as the four core requests are in, but once something has arrived never wait
            // more than CORE_WAIT_MS for the rest: on some networks YouTube Music refuses a request
            // (HTTP 403) only after 6-10 s, and the whole search used to wait for that. A late
            // request is folded in when it lands (below).
            // The release card's lookups (album lookup, release label) take about a second. They start as
            // soon as the songs response names the likely top song, alongside the rest of the search,
            // instead of after ranking; ranking reuses them when it picks the same song.
            var earlyRelease: EarlyReleaseLookup? = null
            fun startEarlyReleaseLookup() {
                if (earlyRelease != null || !coreRequests[0].isCompleted) return
                val songsResponse = coreRequests[0].let { if (it.isCompleted) it.getCompletedOrNull() else null } ?: return
                val likelyTop = SearchQueryMatcher.partitionResults(songsResponse.songs, trimmed).first.firstOrNull() ?: return
                if (!AlbumMetadataResolver.needsResolving(likelyTop.album, likelyTop.title) && !likelyTop.albumId.isNullOrBlank()) return
                val named = java.util.concurrent.atomic.AtomicReference<com.auralis.music.data.network.ResolvedAlbum?>()
                earlyRelease = EarlyReleaseLookup(
                    trackId = likelyTop.id,
                    albumId = likelyTop.albumId,
                    named = named,
                    resolver = async { timed("albumResolverEarly") {
                        try {
                            withTimeoutOrNull(4_000L) {
                                ResolverAnswer(AlbumMetadataResolver.resolveAlbum(likelyTop.title, likelyTop.artist, innerTubeClient,
                                    onAlbumNamed = { named.set(it) }))
                            }
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    } },
                    releaseType = likelyTop.albumId?.takeIf { it.startsWith("MPRE") }?.let { id ->
                        async {
                            try { withTimeoutOrNull(2_500L) { innerTubeClient.getReleaseType(id) } }
                            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                        }
                    }
                )
            }

            val received = mutableListOf<SearchResults>()
            var coreDeadlineNs = startedNs + CORE_WAIT_MS * 1_000_000
            var mainAnswered = false
            // Partial results only appear when ranking is slow: shown at once, they were replaced and
            // reordered a moment later by the ranked result.
            var firstUsefulNs = 0L
            var previewsShown = false
            fun previewDueMs() = if (previewsShown || firstUsefulNs == 0L) Long.MAX_VALUE
                else (firstUsefulNs - System.nanoTime()) / 1_000_000 + PREVIEW_AFTER_MS
            while (received.size < coreRequests.size) {
                // Once songs and general results are in (they decide the top result and the song list),
                // or any three have answered, the rest get a short grace period, not the full wait.
                val answered = coreRequests.filter { it.isCompleted && !it.await().requestFailed }
                if (!mainAnswered && (answered.size >= 3 || (coreRequests[0] in answered && coreRequests[3] in answered))) {
                    mainAnswered = true
                    coreDeadlineNs = minOf(coreDeadlineNs, System.nanoTime() + CORE_GRACE_MS * 1_000_000)
                }
                val waitMs = (coreDeadlineNs - System.nanoTime()) / 1_000_000
                val next = when {
                    received.none { it.isNotEmpty() } -> responses.receive()
                    waitMs <= 0 -> null
                    else -> withTimeoutOrNull(minOf(waitMs, previewDueMs().coerceAtLeast(1L))) { responses.receive() }
                }
                if (next == null) {
                    if (waitMs > 0 && previewDueMs() <= 0) {
                        previewsShown = true
                        searchPreview(trimmed, received).takeIf { it.isNotEmpty() }?.let(emit)
                        continue
                    }
                    break
                }
                received += next
                startEarlyReleaseLookup()
                if (firstUsefulNs == 0L && next.isNotEmpty()) firstUsefulNs = System.nanoTime()
                if (previewsShown) searchPreview(trimmed, received).takeIf { it.isNotEmpty() }?.let(emit)
            }
            if (received.size == coreRequests.size && received.all { it.isEmpty() } && received.any { it.requestFailed }) {
                throw IOException("Search could not reach the music service")
            }

            // Ranks one set of core responses: emits the ranked result before the optional lookups
            // (artist photo, album card, album names, Single labels) and returns it with them filled in.
            suspend fun rank(core: List<SearchResults>, suggestionSongs: List<Track>, requestFailed: Boolean,
                             emitRanked: Boolean): SearchResults = coroutineScope {
            val officialSongs = core[0].songs
            val officialAlbums = core[1].let { if (it.albums.isNotEmpty()) it.albums else it.playlists }
            val officialArtists = core[2].artists
            val generalResults = core[3]

            val cardTrack = (generalResults.topResult as? SearchTopResult.SongResult)?.track
            val allSongs: List<Track> = (officialSongs + generalResults.songs + suggestionSongs + listOfNotNull(cardTrack)).distinctBy { it.id }
            val allAlbums: List<PlaylistResult> = (officialAlbums + generalResults.albums + generalResults.playlists.filter { it.id.startsWith("MPRE") }).distinctBy { it.id }

            // 1. Partition matched songs and supplementary recommendations
            val (matchedSongs, recommendations) = com.auralis.music.domain.search.SearchQueryMatcher.partitionResults(
                candidates = allSongs,
                query = trimmed,
                maxRecommendations = 3
            )

            // 2. Rank albums strictly by relevance and popularity
            val rankedAlbums = com.auralis.music.domain.search.SearchQueryMatcher.rankAlbums(
                candidates = allAlbums,
                query = trimmed
            )

            // 3. Automatically extract artists and resolve real artist photos from YouTube Music
            val songArtists: List<String> = matchedSongs
                .map { it.artist }
                .flatMap { it.split(",", "&", "feat.", "ft.", "/").map { a -> a.trim() } }
                .filter { it.isNotBlank() && it.length > 1 && !it.equals("Spotify Artist", ignoreCase = true) && !it.equals("Various Artists", ignoreCase = true) }
                .distinctBy { it.lowercase() }

            val allFoundArtists = (officialArtists + generalResults.artists).distinctBy { it.name.lowercase() }
            val existingArtistNames: Set<String> = allFoundArtists.map { it.name.lowercase() }.toSet()
            val missingArtists = mutableListOf<Artist>()

            for (artName in songArtists) {
                if (!existingArtistNames.contains(artName.lowercase())) {
                    val matchingOfficial = officialArtists.find { it.name.equals(artName, ignoreCase = true) }
                    val artistThumb = matchingOfficial?.thumbnail
                    val artistId = matchingOfficial?.id ?: "yt:$artName"
                    missingArtists.add(
                        Artist(
                            id = artistId,
                            name = matchingOfficial?.name ?: artName,
                            thumbnail = artistThumb,
                            query = "$artName top songs"
                        )
                    )
                }
            }

            val enrichedArtists: List<Artist> = (allFoundArtists + missingArtists).distinctBy { it.name.lowercase() }

            val officialThumbsById = officialSongs
                .filter { !it.thumbnail.isNullOrBlank() && !it.thumbnail.contains("i.ytimg.com/vi/") }
                .associateBy { it.id }

            fun upgradeTrackThumb(t: Track): Track {
                if (t.thumbnail.contains("i.ytimg.com/vi/") || t.thumbnail.isBlank()) {
                    val matchById = officialThumbsById[t.id]
                    if (matchById != null && !matchById.thumbnail.isNullOrBlank()) {
                        val bestViews = listOfNotNull(t.views, matchById.views).maxByOrNull { SearchQueryMatcher.parsePlayCount(it) } ?: t.views
                        return t.copy(
                            thumbnail = matchById.thumbnail,
                            album = if (t.album.isNullOrBlank()) matchById.album else t.album,
                            views = bestViews
                        )
                    }

                    // Only match by title if the artist ALSO matches!
                    val matchByTitleAndArtist = officialSongs
                        .filter { cand ->
                            !cand.thumbnail.isNullOrBlank() &&
                            !cand.thumbnail.contains("i.ytimg.com/vi/") &&
                            cand.title.equals(t.title, ignoreCase = true) &&
                            SearchQueryMatcher.isAuthorMatch(cand.artist, t.artist)
                        }
                        .maxByOrNull { SearchQueryMatcher.parsePlayCount(it.views) }

                    if (matchByTitleAndArtist != null && !matchByTitleAndArtist.thumbnail.isNullOrBlank()) {
                        val bestViews = listOfNotNull(t.views, matchByTitleAndArtist.views).maxByOrNull { SearchQueryMatcher.parsePlayCount(it) } ?: t.views
                        return t.copy(
                            thumbnail = matchByTitleAndArtist.thumbnail,
                            album = if (t.album.isNullOrBlank()) matchByTitleAndArtist.album else t.album,
                            views = bestViews
                        )
                    }
                }
                return t
            }

            val finalMatchedSongs = matchedSongs.map { upgradeTrackThumb(it) }
            val finalRecommendations = recommendations.map { upgradeTrackThumb(it) }

            val exactArtistMatch = officialArtists.find { it.name.equals(trimmed, ignoreCase = true) }
                ?: enrichedArtists.find { it.name.equals(trimmed, ignoreCase = true) }

            val exactAlbumMatch = rankedAlbums.find { it.title.equals(trimmed, ignoreCase = true) }
                ?: officialAlbums.find { it.title.equals(trimmed, ignoreCase = true) }

            val topMatchedSong = finalMatchedSongs.firstOrNull()
            val topSongViews = SearchQueryMatcher.parsePlayCount(topMatchedSong?.views)

            val normQuery = SearchQueryMatcher.normalize(trimmed)
            val normQueryStem = normQuery.removeSuffix("s")
            val topSongNormTitle = topMatchedSong?.let { SearchQueryMatcher.normalize(it.title) } ?: ""
            val topSongCleanTitle = topMatchedSong?.let { SearchQueryMatcher.normalize(it.title.replace(Regex("\\(.*\\)|\\[.*\\]"), "")) } ?: ""
            val topSongNormStem = topSongNormTitle.removeSuffix("s")
            val topSongCleanStem = topSongCleanTitle.removeSuffix("s")
            val topSongIsExactTitle = topMatchedSong != null && (
                topSongNormTitle == normQuery ||
                topSongCleanTitle == normQuery ||
                (normQueryStem.length >= 3 && (topSongNormStem == normQueryStem || topSongCleanStem == normQueryStem))
            )

            val topSongWins = topMatchedSong != null && topSongIsExactTitle && (
                topSongViews >= 1_000_000L ||
                (exactArtistMatch == null && exactAlbumMatch == null)
            )

            val ytmAlbumResult = generalResults.topResult as? SearchTopResult.AlbumResult
            val isYtmAlbumValidMatch = ytmAlbumResult != null &&
                !ytmAlbumResult.album.title.startsWith("Radio •", ignoreCase = true) &&
                !ytmAlbumResult.album.title.endsWith(" Radio", ignoreCase = true) &&
                !ytmAlbumResult.album.id.startsWith("VLRD") &&
                !ytmAlbumResult.album.id.startsWith("pl:") &&
                (SearchQueryMatcher.normalize(ytmAlbumResult.album.title) == normQuery ||
                 ytmAlbumResult.album.id == exactAlbumMatch?.id ||
                 ytmAlbumResult.album.title.equals(trimmed, ignoreCase = true))

            val ytmArtistResult = generalResults.topResult as? SearchTopResult.ArtistResult
            val isYtmArtistValidMatch = ytmArtistResult != null &&
                (SearchQueryMatcher.normalize(ytmArtistResult.artist.name) == normQuery ||
                 ytmArtistResult.artist.id == exactArtistMatch?.id ||
                 ytmArtistResult.artist.name.equals(trimmed, ignoreCase = true))

            // Resolve Top Result with highest fidelity:
            // 1. High-popularity exact title song match takes absolute precedence (e.g. TV Girl - Lovers Rock 303M plays, The Weeknd - Starboy 3.5B plays)
            // 2. YouTube Music verified Artist card matching query (e.g. "Radiohead", "Taylor Swift")
            // 3. YouTube Music verified Album card matching query (e.g. "OK Computer", "French Exit")
            // 4. Exact Artist match (when query is an artist name)
            // 5. Exact Album match (when query is an album name)
            // 6. Top matched song
            // 7. YouTube Music general song card
            // 8. Fallback exact matches
            // A hugely popular song that genuinely matches on its title (e.g. "chogada tara" ->
            // Chogada from Loveyatri, 1.3B plays, where "tara" is its lyric) must not lose the Top
            // result to an obscure album that happens to be titled exactly like the query.
            val topSongTier = topMatchedSong?.let { SearchQueryMatcher.evaluateMatch(it, trimmed)?.tier }
            val popularTitleSongWins = topMatchedSong != null && !topSongIsExactTitle &&
                topSongViews >= 10_000_000L &&
                topSongTier in setOf(
                    SearchQueryMatcher.MatchTier.EXACT_TITLE,
                    SearchQueryMatcher.MatchTier.PREFIX_TITLE,
                    SearchQueryMatcher.MatchTier.CLOSE_TITLE
                )

            // An album named exactly like the query against the top song: the more listened one wins.
            // An album's plays are the plays of its songs in these results (the title track counts
            // for it too, so "after hours", "currents" and "starboy" open their albums when other
            // songs of theirs show up). An album no result song belongs to has no proven listeners
            // and never takes the spot from a song.
            // Same play counts the top song is judged by (after merging in the official upload's
            // count), so an album is never short-changed against its own title track.
            val songsForPlays = (finalMatchedSongs + allSongs.map { upgradeTrackThumb(it) })
                .groupBy { it.id }
                .map { (_, copies) -> copies.maxByOrNull { SearchQueryMatcher.parsePlayCount(it.views) }!! }
            fun albumPlays(album: PlaylistResult): Long {
                val normAlbum = SearchQueryMatcher.normalize(album.title)
                val byAlbumArtist = { song: Track -> SearchQueryMatcher.isAuthorMatch(song.artist, album.author.orEmpty()) }
                return songsForPlays.filter { song ->
                    (album.id.isNotBlank() && song.albumId == album.id) ||
                        (song.album?.let { SearchQueryMatcher.normalize(it) } == normAlbum && byAlbumArtist(song)) ||
                        // The title track, when this result came without its album (YouTube Music's
                        // general results often leave it out and the songs-only search can fail).
                        (song.album.isNullOrBlank() && SearchQueryMatcher.normalize(song.title) == normAlbum && byAlbumArtist(song))
                }
                    // One entry per song: a single's remix / instrumental / live cuts of the title
                    // track are still one song, so they can't outvote the song itself.
                    .groupBy { SearchQueryMatcher.normalize(AlbumMetadataResolver.cleanTrackTitle(it.title)) }
                    .values.sumOf { versions -> versions.maxOf { SearchQueryMatcher.parsePlayCount(it.views) } }
            }
            // Several albums can share the name ("Currents"); the most listened one is meant.
            // The album search sometimes misses the album everyone means, but its songs still name
            // it ("After Hours" / MPREb_TH6Wut5eTMQ), so those count as candidates too.
            val albumsFromSongs = songsForPlays
                .filter { !it.albumId.isNullOrBlank() && it.album?.let { a -> SearchQueryMatcher.normalize(a) } == normQuery }
                .map { PlaylistResult(id = it.albumId!!, title = it.album!!, thumbnail = it.thumbnail.ifBlank { null }, author = it.artist) }
            val sameNameAlbums = (listOfNotNull(ytmAlbumResult?.album?.takeIf { isYtmAlbumValidMatch }) + allAlbums + albumsFromSongs)
                .filter { SearchQueryMatcher.normalize(it.title) == normQuery }
                .distinctBy { it.id }
            // Search results say nothing about an album's popularity, and often contain none of its
            // songs ("graduation": only same-titled songs by others, none from Kanye's album). So the
            // leading candidates' own pages are read for their real per-track plays.
            // Capped so a slow album page can't hold up the whole search; without it the album is
            // judged by its songs in the results alone.
            val pageInfo: Map<String, Pair<Long, Int>> = timed("albumPages") { sameNameAlbums.take(2).map { album ->
                async {
                    album.id to (kotlinx.coroutines.withTimeoutOrNull(1_500L) {
                        innerTubeClient.getAlbumPlays(album.id)
                    } ?: (0L to 0))
                }
            }.associate { it.await() } }
            // A release of up to four tracks by the song's own artist, named like the song, is that
            // song's single ("creep", "blinding lights"): it never outranks the song itself.
            fun isSongsOwnSingle(album: PlaylistResult): Boolean {
                val song = topMatchedSong ?: return false
                if (!topSongIsExactTitle || !SearchQueryMatcher.isAuthorMatch(song.artist, album.author.orEmpty())) return false
                val tracks = pageInfo[album.id]?.second ?: 0
                val plays = maxOf(albumPlays(album), pageInfo[album.id]?.first ?: 0L)
                return tracks in 1..4 || (tracks == 0 && plays <= topSongViews)
            }
            fun totalAlbumPlays(album: PlaylistResult): Long =
                if (isSongsOwnSingle(album)) minOf(maxOf(albumPlays(album), pageInfo[album.id]?.first ?: 0L), topSongViews)
                else maxOf(albumPlays(album), pageInfo[album.id]?.first ?: 0L)
            val queriedAlbum = sameNameAlbums.maxByOrNull { totalAlbumPlays(it) }
                ?: exactAlbumMatch ?: ytmAlbumResult?.album?.takeIf { isYtmAlbumValidMatch }
            val queriedAlbumPlays = queriedAlbum?.let { totalAlbumPlays(it) } ?: 0L
            // A same-named artist only keeps the spot when their songs here outplay the album: a small
            // band called "Currents" must not hide Tame Impala's Currents; "radiohead" stays the artist.
            val queriedArtistPlays = ytmArtistResult?.takeIf { isYtmArtistValidMatch }?.artist?.let { artist ->
                songsForPlays.filter { SearchQueryMatcher.isAuthorMatch(it.artist, artist.name) }
                    .sumOf { SearchQueryMatcher.parsePlayCount(it.views) }
            } ?: 0L
            val albumBeatsSong = queriedAlbum != null &&
                // Strictly more: a single's only song is the song itself, so a tie means a single
                // ("blinding lights", "creep") and the song keeps the spot.
                queriedAlbumPlays > topSongViews &&
                // Same artist, same name: the album must bring real listening beyond the song
                // itself (After Hours 8.9B vs 644M), not just a remix on top of it ("creep": 2.302B
                // single vs the 2.3B song).
                !(topMatchedSong != null && topSongIsExactTitle &&
                    SearchQueryMatcher.isAuthorMatch(topMatchedSong.artist, queriedAlbum.author.orEmpty()) &&
                    queriedAlbumPlays < topSongViews + topSongViews / 10) &&
                (!isYtmArtistValidMatch || queriedAlbumPlays > queriedArtistPlays)

            var resolvedTopResult: SearchTopResult? = when {
                albumBeatsSong -> SearchTopResult.AlbumResult(queriedAlbum!!)
                topSongWins -> {
                    SearchTopResult.SongResult(upgradeTrackThumb(topMatchedSong!!))
                }
                popularTitleSongWins && !isYtmArtistValidMatch -> {
                    SearchTopResult.SongResult(upgradeTrackThumb(topMatchedSong!!))
                }
                isYtmArtistValidMatch -> {
                    ytmArtistResult
                }
                isYtmAlbumValidMatch -> {
                    ytmAlbumResult
                }
                exactArtistMatch != null && (topMatchedSong == null || !topSongIsExactTitle) -> {
                    SearchTopResult.ArtistResult(exactArtistMatch)
                }
                exactAlbumMatch != null && (topMatchedSong == null || !topSongIsExactTitle) -> {
                    SearchTopResult.AlbumResult(exactAlbumMatch)
                }
                topMatchedSong != null -> {
                    // Check if YouTube Music returned an official song card that is an exact query match and has substantially more views than topMatchedSong
                    val ytmTopTrack = (generalResults.topResult as? SearchTopResult.SongResult)?.track
                    val ytmTrackMatch = ytmTopTrack?.let { SearchQueryMatcher.evaluateMatch(it, trimmed) }
                    val ytmTrackViews = SearchQueryMatcher.parsePlayCount(ytmTopTrack?.views)

                    val authoritativeSong = if (
                        ytmTopTrack != null &&
                        ytmTrackMatch != null &&
                        ytmTrackMatch.tier == com.auralis.music.domain.search.SearchQueryMatcher.MatchTier.EXACT_TITLE &&
                        ytmTrackViews > (topSongViews.coerceAtLeast(1L) * 2L)
                    ) {
                        ytmTopTrack
                    } else {
                        topMatchedSong
                    }
                    SearchTopResult.SongResult(upgradeTrackThumb(authoritativeSong))
                }
                generalResults.topResult is SearchTopResult.SongResult -> {
                    SearchTopResult.SongResult(upgradeTrackThumb((generalResults.topResult as SearchTopResult.SongResult).track))
                }
                exactArtistMatch != null -> {
                    SearchTopResult.ArtistResult(exactArtistMatch)
                }
                exactAlbumMatch != null -> {
                    SearchTopResult.AlbumResult(exactAlbumMatch)
                }
                else -> null
            }

            // Both featured slots come from the same relevance/popularity ordering. They can
            // be two songs, two albums, or one of each; a single is not a second copy of its song.
            val albumCounts = sameNameAlbums.associate { it.id to totalAlbumPlays(it) }
            val featuredRanking = rankMixedSearchResults(trimmed, finalMatchedSongs,
                sameNameAlbums.filterNot { isSongsOwnSingle(it) }, albumCounts).let { ranked ->
                // The same artist's same-named release needs more than 10% over the song itself
                // (see albumBeatsSong): "creep" is Radiohead's song, with the Creep EP below it.
                val first = ranked.firstOrNull() as? SearchTopResult.AlbumResult
                val song = topMatchedSong
                if (first != null && song != null && topSongIsExactTitle &&
                    SearchQueryMatcher.isAuthorMatch(song.artist, first.album.author.orEmpty()) &&
                    (albumCounts[first.album.id] ?: 0L) < topSongViews + topSongViews / 10) {
                    val songEntry = ranked.firstOrNull { it is SearchTopResult.SongResult && it.track.id == song.id }
                    if (songEntry != null) listOf(songEntry) + ranked.filterNot { it === songEntry } else ranked
                } else ranked
            }
            if (resolvedTopResult !is SearchTopResult.ArtistResult && featuredRanking.isNotEmpty()) {
                resolvedTopResult = featuredRanking.first()
            }
            // A query that names an artist ("daft punk") means the artist, unless an upload titled
            // like them is actually more listened than all their songs here together.
            val namedArtist = ytmArtistResult?.artist?.takeIf { isYtmArtistValidMatch }
                ?: exactArtistMatch?.takeIf { it.id.startsWith("UC") }
            if (namedArtist != null && resolvedTopResult !is SearchTopResult.ArtistResult) {
                val norm = SearchQueryMatcher.normalize(namedArtist.name)
                val artistPlays = songsForPlays.filter { song ->
                    com.auralis.music.domain.recommendations.SimilarSeedPlanner.splitArtistCredit(song.artist)
                        .any { SearchQueryMatcher.normalize(it) == norm }
                }.sumOf { SearchQueryMatcher.parsePlayCount(it.views) }
                val topPlays = when (val top = resolvedTopResult) {
                    is SearchTopResult.SongResult -> SearchQueryMatcher.parsePlayCount(top.track.views)
                    is SearchTopResult.AlbumResult -> albumCounts[top.album.id] ?: 0L
                    else -> 0L
                }
                if (artistPlays > topPlays) resolvedTopResult = SearchTopResult.ArtistResult(namedArtist)
            }
            val runnerUp = selectAlsoMatching(trimmed, resolvedTopResult, featuredRanking, albumCounts)
            val runnerUpAlbumPlays = (runnerUp as? SearchTopResult.AlbumResult)?.let { albumCounts[it.album.id] }
                ?: (resolvedTopResult as? SearchTopResult.AlbumResult)?.let { albumCounts[it.album.id] } ?: 0L

            // Resolve Primary Artist (e.g. Radiohead for "OK Computer", Kanye West for "Graduation", Elley Duhé for "MIDDLE OF THE NIGHT")
            var primaryArtist: Artist? = when {
                resolvedTopResult is SearchTopResult.ArtistResult -> resolvedTopResult.artist
                resolvedTopResult is SearchTopResult.AlbumResult -> {
                    val albumAuthor = resolvedTopResult.album.author ?: ""
                    enrichedArtists.find { it.name.equals(albumAuthor, ignoreCase = true) || albumAuthor.contains(it.name, ignoreCase = true) }
                        ?: officialArtists.find { it.name.equals(albumAuthor, ignoreCase = true) }
                        ?: if (albumAuthor.isNotBlank()) {
                            Artist(
                                id = "yt:$albumAuthor",
                                name = albumAuthor,
                                thumbnail = null,
                                query = "$albumAuthor top songs"
                            )
                        } else null
                }
                resolvedTopResult is SearchTopResult.SongResult -> {
                    // The first credited artist is the song's artist ("Darshan Raval, Asees Kaur & ..."),
                    // not whichever co-credited name happens to come first in the artist results.
                    // A duo credited as one act ("Simon & Garfunkel") keeps its whole name.
                    val fullCredit = resolvedTopResult.track.artist
                    val wholeAct = (enrichedArtists + officialArtists).find { it.name.equals(fullCredit, ignoreCase = true) }
                    val songArtist = if (wholeAct != null) fullCredit else com.auralis.music.domain.recommendations.SimilarSeedPlanner
                        .splitArtistCredit(fullCredit).firstOrNull() ?: fullCredit
                    if (songArtist.isNotBlank() && !songArtist.equals("Unknown Artist", ignoreCase = true) && !songArtist.equals("YouTube Artist", ignoreCase = true)) {
                        enrichedArtists.find { it.name.equals(songArtist, ignoreCase = true) }
                            ?: enrichedArtists.find { songArtist.contains(it.name, ignoreCase = true) }
                            ?: officialArtists.find { it.name.equals(songArtist, ignoreCase = true) }
                            ?: Artist(
                                id = "yt:$songArtist",
                                name = songArtist,
                                thumbnail = null,
                                query = "$songArtist top songs"
                            )
                    } else null
                }
                exactAlbumMatch != null -> {
                    val albumAuthor = exactAlbumMatch.author ?: ""
                    enrichedArtists.find { it.name.equals(albumAuthor, ignoreCase = true) || albumAuthor.contains(it.name, ignoreCase = true) }
                        ?: officialArtists.find { it.name.equals(albumAuthor, ignoreCase = true) }
                        ?: if (albumAuthor.isNotBlank()) {
                            Artist(
                                id = "yt:$albumAuthor",
                                name = albumAuthor,
                                thumbnail = null,
                                query = "$albumAuthor top songs"
                            )
                        } else null
                }
                else -> exactArtistMatch ?: enrichedArtists.firstOrNull()
            }

            // The remaining lookups are independent network calls: run them together so the full
            // results cost the slowest one, not their sum.
            val topTrackForAlbum = (resolvedTopResult as? SearchTopResult.SongResult)?.track
            val artistNeedingPhoto = primaryArtist?.takeIf { it.thumbnail.isNullOrBlank() || it.thumbnail!!.contains("i.ytimg.com/vi/") }
            val artistPhotoDeferred = async { timed("artistPhoto") {
                val artistName = artistNeedingPhoto?.name ?: return@timed null
                officialArtists.find { it.name.equals(artistName, ignoreCase = true) }
                    ?: generalResults.artists.find { it.name.equals(artistName, ignoreCase = true) }
                    ?: try {
                        val artistSearch = withTimeoutOrNull(3_000L) { innerTubeClient.search(artistName, InnerTubeClient.FILTER_ARTISTS).artists } ?: emptyList()
                        val norm = SearchQueryMatcher.normalize(artistName)
                        artistSearch.firstOrNull { it.id.startsWith("UC") && SearchQueryMatcher.normalize(it.name) == norm }
                            ?: artistSearch.firstOrNull { it.name.contains(artistName, ignoreCase = true) }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            } }
            // Songs that arrived without their album (general results leave it out) get it from
            // YouTube Music's own record of each upload, in one batched request.
            val albumLookupIds = (listOfNotNull(topTrackForAlbum?.id) +
                finalMatchedSongs.filter { it.album.isNullOrBlank() }.map { it.id }).distinct().take(25)
            val queueDeferred = async { timed("queue") {
                if (albumLookupIds.isEmpty()) emptyMap() else try {
                    (withTimeoutOrNull(2_500L) { innerTubeClient.getQueue(albumLookupIds) } ?: emptyList())
                        .associateBy { it.id }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap<String, Track>() }
            } }
            // A song whose release is named like the song may be a single or an album's title
            // track: its release page says which ("Single • 2024" / "Album • 2020").
            val ambiguousReleaseIds = finalMatchedSongs.take(12)
                .filter { !it.albumId.isNullOrBlank() && AlbumMetadataResolver.isRedundantOrSingle(it.album, it.title) }
                .mapNotNull { it.albumId }.distinct()
                .filter { id -> allAlbums.none { it.id == id && it.releaseType != null } }
            val early = earlyRelease
            val releaseTypeDeferreds = ambiguousReleaseIds.map { id ->
                if (early?.releaseType != null && early.albumId == id) early.releaseType.let { d -> async { id to d.await() } }
                else async {
                    id to try { withTimeoutOrNull(2_500L) { innerTubeClient.getReleaseType(id) } }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                }
            }
            val topNeedsResolving = topTrackForAlbum != null &&
                (AlbumMetadataResolver.needsResolving(topTrackForAlbum.album, topTrackForAlbum.title) ||
                    topTrackForAlbum.albumId.isNullOrBlank() || topTrackForAlbum.album.isNullOrBlank())
            // A finished lookup (its answer, possibly "nothing found"), or null if it timed out or failed.
            val resolverDeferred: kotlinx.coroutines.Deferred<ResolverAnswer?> =
                if (topNeedsResolving && early != null && early.trackId == topTrackForAlbum?.id) early.resolver
                else async { timed("albumResolver") {
                if (!topNeedsResolving) null else try {
                    withTimeoutOrNull(4_000L) {
                        ResolverAnswer(AlbumMetadataResolver.resolveAlbum(topTrackForAlbum!!.title, primaryArtist?.name ?: topTrackForAlbum.artist, innerTubeClient))
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            } }
            // The same lookup answered earlier (any search), so the first result can use it at once.
            val cachedResolverAnswer = if (!topNeedsResolving) null else
                AlbumMetadataResolver.getCached(topTrackForAlbum!!.title, primaryArtist?.name ?: topTrackForAlbum.artist)?.let { ResolverAnswer(it) }

            // Another upload of the top song in the results already received that sits on a real album
            // ("Blinding Lights" on After Hours next to its single): that album is known at once.
            fun albumUploadOfSameSong(track: Track): Track? = songsForPlays.firstOrNull { other ->
                other.id != track.id && other.albumId?.startsWith("MPRE") == true && !other.album.isNullOrBlank() &&
                    !AlbumMetadataResolver.needsResolving(other.album, other.title) &&
                    SearchQueryMatcher.isSameSong(other, track) &&
                    SearchQueryMatcher.unrequestedVersionTags(other, trimmed).isEmpty()
            }

            // Everything above decides what is shown and in what order; what follows only fills in
            // details, so the result can be shown before the optional lookups answer.
            val rankedPrimaryArtist = primaryArtist
            val rankedTopResult = resolvedTopResult
            fun assemble(realArtist: Artist?, queueTracks: Map<String, Track>, resolverAnswer: ResolverAnswer?,
                         lateReleaseTypes: Map<String, String>): SearchResults {
            val resolved = resolverAnswer?.album
            var primaryArtist = rankedPrimaryArtist
            var resolvedTopResult = rankedTopResult
            if (primaryArtist != null && realArtist != null && !realArtist.thumbnail.isNullOrBlank() && artistNeedingPhoto != null) {
                primaryArtist = primaryArtist.copy(
                    id = if (realArtist.id.startsWith("UC")) realArtist.id else primaryArtist.id,
                    thumbnail = realArtist.thumbnail
                )
            }

            // Set when the album service confirmed a full album, e.g. "After Hours" by The Weeknd:
            // named like its title track, but 14 songs, not a single.
            var albumConfirmedNotSingle = false

            // Resolve Primary Album (InnerTube get_queue / Apple Music specification)
            var primaryAlbum: PlaylistResult? = when {
                resolvedTopResult is SearchTopResult.AlbumResult -> resolvedTopResult.album
                resolvedTopResult is SearchTopResult.SongResult -> {
                    val track = resolvedTopResult.track
                    val targetArtist = primaryArtist?.name ?: track.artist
                    val isRedundant = AlbumMetadataResolver.needsResolving(track.album, track.title)

                    // 1. If the track already has verified authentic studio album metadata
                    if (!isRedundant && !track.albumId.isNullOrBlank() && !track.album.isNullOrBlank()) {
                        PlaylistResult(
                            id = track.albumId!!,
                            title = track.album!!,
                            thumbnail = track.thumbnail.ifBlank { null },
                            author = targetArtist
                        )
                    } else albumUploadOfSameSong(track)?.let { onAlbum ->
                        // 2. The same song's album upload among the results already received.
                        albumConfirmedNotSingle = true
                        resolvedTopResult = SearchTopResult.SongResult(track.copy(album = onAlbum.album, albumId = onAlbum.albumId))
                        PlaylistResult(
                            id = onAlbum.albumId!!,
                            title = onAlbum.album!!,
                            thumbnail = onAlbum.thumbnail.ifBlank { null } ?: track.thumbnail.ifBlank { null },
                            author = targetArtist
                        )
                    } ?: run {
                        // 3. Resolve authentic studio album via AlbumMetadataResolver (Apple Music / iTunes + YTM)

                        if (resolved != null && !resolved.isSingle && resolved.albumTitle.isNotBlank() &&
                            !AlbumMetadataResolver.isCompilation(resolved.albumTitle)) {
                            albumConfirmedNotSingle = true
                            val updatedTrack = track.copy(
                                album = resolved.albumTitle,
                                albumId = resolved.albumId ?: track.albumId
                            )
                            resolvedTopResult = SearchTopResult.SongResult(updatedTrack)
                            PlaylistResult(
                                id = resolved.albumId ?: "pl:${resolved.albumTitle}",
                                title = resolved.albumTitle,
                                thumbnail = resolved.albumArt ?: track.thumbnail.ifBlank { null },
                                author = targetArtist
                            )
                        } else {
                            // 3. Fetch authentic album metadata via getSongDetails (InnerTube get_queue)
                            val detailedTrack = queueTracks[track.id]

                            if (detailedTrack != null && !detailedTrack.albumId.isNullOrBlank() && !detailedTrack.album.isNullOrBlank() && !AlbumMetadataResolver.needsResolving(detailedTrack.album, track.title)) {
                                resolvedTopResult = SearchTopResult.SongResult(
                                    track.copy(
                                        album = detailedTrack.album,
                                        albumId = detailedTrack.albumId
                                    )
                                )
                                PlaylistResult(
                                    id = detailedTrack.albumId!!,
                                    title = detailedTrack.album!!,
                                    thumbnail = track.thumbnail.ifBlank { null },
                                    author = targetArtist
                                )
                            } else {
                                // 4. Match against rankedAlbums if an album by this artist matches and is not redundant
                                val matchingAlbum = rankedAlbums.firstOrNull { album ->
                                    SearchQueryMatcher.isAuthorMatch(album.author, targetArtist) &&
                                    !AlbumMetadataResolver.isRedundantOrSingle(album.title, track.title) &&
                                    (album.title.contains(track.title, ignoreCase = true) ||
                                     track.title.contains(album.title, ignoreCase = true))
                                }
                                matchingAlbum
                            }
                        }
                    }
                }
                exactAlbumMatch != null -> exactAlbumMatch
                else -> null
            }

            // Suppress redundant album card if it just mirrors the song title as a single
            if (primaryAlbum != null && !albumConfirmedNotSingle) {
                val topSongTitle = (resolvedTopResult as? SearchTopResult.SongResult)?.track?.title ?: ""
                if (AlbumMetadataResolver.isRedundantOrSingle(primaryAlbum.title, topSongTitle)) {
                    primaryAlbum = null
                }
            }

            // Release labels known so far: from album results, the release pages read for this search,
            // and release pages read by earlier searches.
            val knownReleaseTypes = allAlbums.mapNotNull { a -> a.releaseType?.let { a.id to it } }.toMap() + lateReleaseTypes
            fun releaseTypeOf(id: String) = knownReleaseTypes[id] ?: InnerTubeClient.cachedReleaseType(id)

            // A song that is only a single gets a release card of its own: its artwork labelled
            // "Single", not clickable. Only when two sources agree: YouTube Music labels its release a
            // single, and the album lookup found it on a single and on no album. "Nothing found" is not
            // enough: Radiohead's Creep is on Pablo Honey, which iTunes doesn't list at all.
            val topTrackNow = (resolvedTopResult as? SearchTopResult.SongResult)?.track
            var singleCard = false
            if (primaryAlbum == null && topTrackNow != null && !topTrackNow.albumId.isNullOrBlank() &&
                releaseTypeOf(topTrackNow.albumId!!) == "single" && resolved?.isSingle == true) {
                primaryAlbum = PlaylistResult(
                    id = topTrackNow.albumId!!,
                    title = topTrackNow.album?.takeIf { it.isNotBlank() } ?: topTrackNow.title,
                    thumbnail = topTrackNow.thumbnail.ifBlank { null },
                    author = primaryArtist?.name ?: topTrackNow.artist,
                    releaseType = "single"
                )
                singleCard = true
            }

            // Ensure prioritized ordering
            val finalArtists = if (primaryArtist != null) {
                listOf(primaryArtist) + enrichedArtists.filterNot { it.name.equals(primaryArtist.name, ignoreCase = true) }
            } else {
                enrichedArtists
            }

            val listedAlbum = primaryAlbum?.takeIf { !singleCard }
            val finalAlbums = if (listedAlbum != null) {
                listOf(listedAlbum) + rankedAlbums.filterNot { it.id == listedAlbum.id || it.title.equals(listedAlbum.title, ignoreCase = true) }
            } else {
                rankedAlbums
            }

            // Propagate resolved authentic album to matching songs
            val resolvedTopTrack = (resolvedTopResult as? SearchTopResult.SongResult)?.track
            val resolvedCleanTopTitle = resolvedTopTrack?.let { AlbumMetadataResolver.cleanTrackTitle(it.title).lowercase() }
            val finalSongsWithAlbums = finalMatchedSongs.map { song ->
                val known = queueTracks[song.id]
                if (song.album.isNullOrBlank() && known != null && !known.album.isNullOrBlank()) {
                    song.copy(album = known.album, albumId = known.albumId ?: song.albumId)
                } else song
            }.map { s ->
                if (resolvedTopTrack != null && !resolvedTopTrack.album.isNullOrBlank()) {
                    val sCleanTitle = AlbumMetadataResolver.cleanTrackTitle(s.title).lowercase()
                    val isArtistMatch = SearchQueryMatcher.isAuthorMatch(s.artist, resolvedTopTrack.artist)
                    if (s.id == resolvedTopTrack.id || (isArtistMatch && sCleanTitle == resolvedCleanTopTitle && AlbumMetadataResolver.isRedundantOrSingle(s.album, s.title))) {
                        s.copy(album = resolvedTopTrack.album, albumId = resolvedTopTrack.albumId)
                    } else {
                        s
                    }
                } else {
                    s
                }
            }

            // Searching an artist ("radiohead") means their songs, most played first, not uploads
            // that only mention the name in their title ("Radiohead - Creep // Español + Lyrics").
            val searchedArtist = (resolvedTopResult as? SearchTopResult.ArtistResult)?.artist
            val orderedSongs = if (searchedArtist == null) finalSongsWithAlbums else {
                val norm = SearchQueryMatcher.normalize(searchedArtist.name)
                val byArtist = { t: Track ->
                    com.auralis.music.domain.recommendations.SimilarSeedPlanner.splitArtistCredit(t.artist)
                        .any { SearchQueryMatcher.normalize(it) == norm }
                }
                val ownSongs = (finalSongsWithAlbums + allSongs.map { upgradeTrackThumb(it) })
                    .filter(byArtist).distinctBy { it.id }
                    .sortedWith(compareByDescending<Track> { SearchQueryMatcher.parsePlayCount(it.views) }
                        .thenBy { AlbumMetadataResolver.isRedundantOrSingle(it.album, it.title) })
                    // The single and the album upload of one song are one entry: the most played, and
                    // the album's copy when they tie.
                    .distinctBy { SearchQueryMatcher.normalize(AlbumMetadataResolver.cleanTrackTitle(it.title)) }
                ownSongs + finalSongsWithAlbums.filterNot { song -> ownSongs.any { it.id == song.id } }
            }

            return SearchResults(
                topResult = resolvedTopResult,
                recommendations = finalRecommendations.filterNot { rec -> orderedSongs.any { it.id == rec.id } },
                songs = orderedSongs,
                albums = finalAlbums,
                artists = finalArtists,
                playlists = generalResults.playlists,
                primaryArtist = primaryArtist,
                primaryAlbum = primaryAlbum,
                primarySingle = singleCard,
                runnerUp = runnerUp,
                albumPlays = runnerUpAlbumPlays,
                albumPlayCounts = albumCounts,
                requestFailed = requestFailed,
                releaseTypes = knownReleaseTypes
            )
            }

            // Albums get_queue reported for these uploads in earlier searches.
            val cachedQueueTracks = albumLookupIds.mapNotNull { id ->
                InnerTubeClient.cachedQueueAlbum(id)?.let { (album, albumId) -> id to Track(id, "", "", album = album, albumId = albumId) }
            }.toMap()
            if (emitRanked) {
                // The release card shows with the songs when its lookups finish within a short wait;
                // otherwise the songs go first and the card fills in when they answer.
                val topReleaseType = topTrackForAlbum?.albumId
                    ?.let { id -> ambiguousReleaseIds.indexOf(id).takeIf { it >= 0 } }?.let { releaseTypeDeferreds[it] }
                // The album is shown as soon as iTunes names it; its YouTube Music page follows.
                val namedEarly = early?.takeIf { it.resolver === resolverDeferred }?.named
                if (topNeedsResolving && cachedResolverAnswer == null) {
                    withTimeoutOrNull(RELEASE_CARD_WAIT_MS) {
                        while (!resolverDeferred.isCompleted && namedEarly?.get() == null) kotlinx.coroutines.delay(20)
                        topReleaseType?.await()
                    }
                }
                val resolvedSoFar = resolverDeferred.getCompletedOrNull() ?: cachedResolverAnswer
                    ?: namedEarly?.get()?.let { ResolverAnswer(it) }
                val typesSoFar = releaseTypeDeferreds.mapNotNull { it.getCompletedOrNull()?.let { (id, type) -> type?.let { id to it } } }.toMap()
                emit(assemble(null, cachedQueueTracks, resolvedSoFar, typesSoFar))
            }
            assemble(
                realArtist = artistPhotoDeferred.await(),
                queueTracks = cachedQueueTracks + queueDeferred.await(),
                resolverAnswer = resolverDeferred.await() ?: cachedResolverAnswer,
                lateReleaseTypes = releaseTypeDeferreds.mapNotNull { d -> d.await().let { (id, type) -> type?.let { id to it } } }.toMap()
            )
            }

            // Missing responses count as empty until they land. The spelling-suggestion songs get the
            // same deadline as the core requests; if they are still not back they are left out rather
            // than reshuffling the songs later.
            val core = coreRequests.map { if (it.isCompleted) it.await() else SearchResults() }
            val suggestionWaitMs = ((coreDeadlineNs - System.nanoTime()) / 1_000_000).coerceAtLeast(0L)
            val suggestionSongs = withTimeoutOrNull(suggestionWaitMs) { topSuggestionDeferred.await() } ?: emptyList()
            val first = rank(core, suggestionSongs, core.any { it.requestFailed }, emitRanked = true)
            if (coreRequests.all { it.isCompleted && !it.await().requestFailed }) return@coroutineScope first

            // A late or failed core request: show the first result with its details, then wait for
            // the straggler (retrying a failed request once) and rank again with everything.
            emit(first)
            val settled = coreRequests.mapIndexed { i, request ->
                async {
                    val answer = request.await()
                    if (!answer.requestFailed) answer
                    else timed("retry-${InnerTubeClient.searchFilterName(coreParams[i])}") { innerTubeClient.search(trimmed, coreParams[i]) }
                }
            }.map { it.await() }
            val failed = settled.any { it.requestFailed }
            if (settled == core) first.copy(requestFailed = failed)
            else rank(settled, suggestionSongs, failed, emitRanked = false)
        }
        com.auralis.music.util.Log.i(InnerTubeClient.SEARCH_LOG_TAG, "search '$trimmed' done in ${(System.nanoTime() - startedNs) / 1_000_000}ms" +
            (if (cached != null) " (cached result shown first)" else "") + "; failed=${fresh.requestFailed}; " +
            timings.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" })
        if (!fresh.requestFailed) rememberFinished(trimmed, fresh)
        // A refresh that lost a request never replaces a complete earlier result.
        if (fresh.requestFailed && cached != null) cached else fresh
    }

    override suspend fun searchLiveSongs(query: String, onResults: (List<Track>) -> Unit) = coroutineScope {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@coroutineScope
        val responses = kotlinx.coroutines.channels.Channel<Pair<Boolean, List<Track>>>(2)
        listOf(true, false).forEach { filtered ->
            launch(Dispatchers.IO) {
                val songs = innerTubeClient.searchLive(trimmed, if (filtered) InnerTubeClient.FILTER_SONGS else null).songs
                responses.send(filtered to songs)
            }
        }
        var filteredSongs = emptyList<Track>()
        var generalSongs = emptyList<Track>()
        repeat(2) {
            val (filtered, songs) = responses.receive()
            if (filtered) filteredSongs = songs else generalSongs = songs
            val candidates = (filteredSongs + generalSongs).distinctBy { it.id }
            val (matched, remaining) = SearchQueryMatcher.partitionResults(candidates, trimmed)
            if (candidates.isNotEmpty()) onResults(if (matched.isNotEmpty()) matched else remaining.take(10))
        }
    }

    override suspend fun searchSongs(query: String): List<Track> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        // Fetch filtered and general songs concurrently in parallel for fast response
        val filteredDef = async {
            try {
                innerTubeClient.search(trimmed, InnerTubeClient.FILTER_SONGS).songs
            } catch (_: Exception) { emptyList() }
        }
        val generalDef = async {
            try {
                innerTubeClient.search(trimmed).songs
            } catch (_: Exception) { emptyList() }
        }

        val filtered = filteredDef.await()
        val general = generalDef.await()
        val candidates = (filtered + general).distinctBy { it.id }

        val (matchedSongs, remaining) = com.auralis.music.domain.search.SearchQueryMatcher.partitionResults(candidates, trimmed)
        if (matchedSongs.isNotEmpty()) matchedSongs else remaining.take(10)
    }

    override suspend fun searchAlbums(query: String): List<PlaylistResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        val filteredRes = innerTubeClient.search(trimmed, InnerTubeClient.FILTER_ALBUMS)
        val filtered = if (filteredRes.albums.isNotEmpty()) filteredRes.albums else filteredRes.playlists
        val general = innerTubeClient.search(trimmed).albums
        val candidates = (filtered + general).distinctBy { it.id }

        com.auralis.music.domain.search.SearchQueryMatcher.rankAlbums(candidates, trimmed)
    }

    override suspend fun searchArtists(query: String): List<Artist> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        // 1. Try YouTube Music filtered artist search
        val filtered = innerTubeClient.search(trimmed, InnerTubeClient.FILTER_ARTISTS).artists
        if (filtered.isNotEmpty()) return@withContext filtered

        // 2. Fall back to general search artists enriched with song artists
        val general = search(trimmed)
        general.artists
    }

    override suspend fun searchPlaylists(query: String): List<PlaylistResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        // 1. Try YouTube Music filtered playlist search
        val filtered = innerTubeClient.search(trimmed, InnerTubeClient.FILTER_PLAYLISTS).playlists
        if (filtered.isNotEmpty()) return@withContext filtered

        // 2. Fall back to general YouTube Music search playlists
        innerTubeClient.search(trimmed).playlists
    }

    override suspend fun getSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        suggestionsClient.getSuggestions(trimmed)
    }

    override suspend fun getArtistPage(artist: Artist): ArtistPage? = withContext(Dispatchers.IO) {
        innerTubeClient.getArtistPage(artist)
    }

    private val youtubePlaylistImporter = com.auralis.music.data.network.YouTubePlaylistImporter()

    private fun filterOfficialAlbumTracks(album: PlaylistResult, tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return tracks

        val isExplicitLiveAlbum = album.title.contains("Live", ignoreCase = true)
        val isExplicitInstrumentalAlbum = album.title.contains("Instrumental", ignoreCase = true)
        val isExplicitAcousticAlbum = album.title.contains("Acoustic", ignoreCase = true)
        val isExplicitRemixAlbum = album.title.contains("Remix", ignoreCase = true)

        val cleanTracks = tracks.filter { track ->
            val title = track.title
            val isInstrumental = !isExplicitInstrumentalAlbum && (
                title.contains("(Instrumental", ignoreCase = true) ||
                title.contains("[Instrumental", ignoreCase = true) ||
                title.endsWith(" - Instrumental", ignoreCase = true) ||
                title.endsWith(" (Instrumental)", ignoreCase = true)
            )

            val isLiveCut = !isExplicitLiveAlbum && (
                title.contains("(Live From", ignoreCase = true) ||
                title.contains("(Live at", ignoreCase = true) ||
                title.contains("[Live at", ignoreCase = true) ||
                title.contains("(Live in", ignoreCase = true) ||
                title.contains("(Live /", ignoreCase = true) ||
                title.contains(" - Live", ignoreCase = true)
            )

            val isCommentaryOrDemo = title.contains("(Commentary", ignoreCase = true) ||
                title.contains("(Demo", ignoreCase = true) ||
                title.contains("[Demo", ignoreCase = true) ||
                title.contains("(Acapella", ignoreCase = true)

            !isInstrumental && !isLiveCut && !isCommentaryOrDemo
        }.let { if (isExplicitRemixAlbum) it else withoutRemixesOfAlbumSongs(it) }

        // Outro / Closing track detection for albums with expanded bonus tracks appended at end
        val outroIndex = cleanTracks.indexOfFirst {
            it.title.equals("Curtains Close", ignoreCase = true) ||
            (it.title.equals("Outro", ignoreCase = true) && cleanTracks.size > 20) ||
            (it.title.equals("Still Don't Give A Fuck", ignoreCase = true) && cleanTracks.size > 20) ||
            (it.title.equals("Criminal", ignoreCase = true) && cleanTracks.size > 18)
        }

        val isExpandedOrDeluxeAlbumTitle = album.title.contains("Expanded", ignoreCase = true) ||
            album.title.contains("Deluxe", ignoreCase = true) ||
            album.title.contains("Anniversary", ignoreCase = true) ||
            album.title.contains("Bonus", ignoreCase = true)

        val finalTracks = if (outroIndex in 10..25 && cleanTracks.size > outroIndex + 1 && !isExpandedOrDeluxeAlbumTitle) {
            cleanTracks.take(outroIndex + 1)
        } else {
            cleanTracks
        }

        return finalTracks
    }

    override suspend fun getAlbumTracks(album: PlaylistResult): List<Track> = withContext(Dispatchers.IO) {
        try {
            // 0. If this is an artist Top Songs collection
            if (album.id.startsWith("artist_top_songs:")) {
                val artistName = album.author?.ifBlank { null }
                    ?: album.title.substringBefore(" - Top songs").substringBefore(" - Top Songs").trim()
                val searchHits = innerTubeClient.search(artistName, InnerTubeClient.FILTER_SONGS).songs
                val targetName = artistName.lowercase().trim()
                val filtered = searchHits.filter { trk ->
                    val trkArtist = trk.artist.lowercase()
                    (trkArtist.contains(targetName) || targetName.contains(trkArtist)) &&
                    !trk.title.contains("cover", ignoreCase = true) &&
                    !trk.title.contains("karaoke", ignoreCase = true) &&
                    !trk.title.contains("remake", ignoreCase = true)
                }.distinctBy { it.id }
                val results = if (filtered.isNotEmpty()) filtered else searchHits
                return@withContext results.map {
                    it.copy(
                        artist = if (it.artist.isBlank() || it.artist == "Artist") artistName else it.artist
                    )
                }
            }

            // 1. If album ID is already an official YouTube browse ID (MPRE, VL, PL, OLAK)
            if (album.id.startsWith("MPRE") || album.id.startsWith("VL") || album.id.startsWith("PL") || album.id.startsWith("OLAK")) {
                val imported = youtubePlaylistImporter.importPlaylistById(album.id)
                if (imported != null && imported.tracks.isNotEmpty()) {
                    val tracksWithAlbumMeta = imported.tracks.map {
                        it.copy(
                            album = it.album?.takeIf(String::isNotBlank)
                                ?: album.title.takeIf { _ -> album.id.startsWith("MPRE") || album.id.startsWith("OLAK") },
                            artist = if (it.artist.isBlank() || it.artist == "Artist" || it.artist == "YouTube Music") {
                                album.author ?: it.artist
                            } else it.artist,
                            thumbnail = it.thumbnail
                        )
                    }
                    return@withContext filterOfficialAlbumTracks(album, tracksWithAlbumMeta)
                }
            }

            // 2. Lookup the official album via YouTube Music InnerTube FILTER_ALBUMS
            val cleanTitle = AlbumMetadataResolver.cleanAlbumTitle(album.title)
            val primaryArtist = album.author
                ?.split(",", "&", "feat.", "ft.", "/", "•")
                ?.firstOrNull()?.trim()
                ?.takeIf { it.isNotBlank() && it != "Artist" && it != "Various Artists" }

            val queriesToTry = listOfNotNull(
                cleanTitle.takeIf { it.isNotBlank() },
                primaryArtist?.let { "$it $cleanTitle" },
                album.title.takeIf { it != cleanTitle }
            ).distinct()

            var matchedAlbum: PlaylistResult? = null
            for (query in queriesToTry) {
                val albumsResult = innerTubeClient.search(query, InnerTubeClient.FILTER_ALBUMS).albums
                val match = albumsResult.firstOrNull { cand ->
                    val candClean = AlbumMetadataResolver.cleanAlbumTitle(cand.title)
                    val authorMatches = primaryArtist != null && cand.author?.let {
                        SearchQueryMatcher.isAuthorMatch(it, primaryArtist)
                    } == true
                    authorMatches && (candClean.equals(cleanTitle, ignoreCase = true) ||
                    cand.title.equals(album.title, ignoreCase = true) ||
                    (cleanTitle.length > 3 && candClean.contains(cleanTitle, ignoreCase = true)) ||
                    (candClean.length > 3 && cleanTitle.contains(candClean, ignoreCase = true)))
                }
                if (match != null) {
                    matchedAlbum = match
                    break
                }
            }

            if (matchedAlbum != null && (matchedAlbum.id.startsWith("MPRE") || matchedAlbum.id.startsWith("OLAK") || matchedAlbum.id.startsWith("VL") || matchedAlbum.id.startsWith("PL"))) {
                val imported = youtubePlaylistImporter.importPlaylistById(matchedAlbum.id)
                if (imported != null && imported.tracks.isNotEmpty()) {
                    val tracksWithAlbumMeta = imported.tracks.map {
                        it.copy(
                            album = it.album?.takeIf(String::isNotBlank)
                                ?: album.title.takeIf { _ -> matchedAlbum.id.startsWith("MPRE") || matchedAlbum.id.startsWith("OLAK") },
                            artist = if (it.artist.isBlank() || it.artist == "Artist" || it.artist == "YouTube Music") {
                                album.author ?: matchedAlbum.author ?: it.artist
                            } else it.artist,
                            thumbnail = it.thumbnail
                        )
                    }
                    return@withContext filterOfficialAlbumTracks(album, tracksWithAlbumMeta)
                }
            }

            // 3. Fallback: Search songs where album metadata matches cleanTitle or album.title
            val songQueries = listOfNotNull(
                primaryArtist?.let { "$it $cleanTitle" },
                cleanTitle,
                album.title.takeIf { it != cleanTitle }
            ).distinct()

            for (sQuery in songQueries) {
                val albumSongs = innerTubeClient.search(sQuery, InnerTubeClient.FILTER_SONGS).songs
                val matchingSongs = albumSongs.filter {
                    val songAlbum = it.album ?: ""
                    val cleanSongAlbum = AlbumMetadataResolver.cleanAlbumTitle(songAlbum)
                    songAlbum.equals(album.title, ignoreCase = true) ||
                    cleanSongAlbum.equals(cleanTitle, ignoreCase = true) ||
                    (cleanTitle.length > 4 && cleanSongAlbum.contains(cleanTitle, ignoreCase = true)) ||
                    (cleanSongAlbum.length > 4 && cleanTitle.contains(cleanSongAlbum, ignoreCase = true))
                }
                if (matchingSongs.isNotEmpty()) {
                    return@withContext filterOfficialAlbumTracks(album, matchingSongs.distinctBy { it.id })
                }
            }

            return@withContext emptyList()
        } catch (_: Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            emptyList()
        }
    }

    override fun getRecentSearchQueries(): Flow<List<String>> {
        return searchHistory.recentQueries()
    }

    override suspend fun recordSearchQuery(query: String) = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isNotBlank()) {
            searchHistory.add(trimmed, System.currentTimeMillis())
        }
    }

    override suspend fun removeSearchQuery(query: String) = withContext(Dispatchers.IO) {
        searchHistory.remove(query)
    }

    override suspend fun clearSearchHistory() = withContext(Dispatchers.IO) {
        searchHistory.clear()
    }
}
