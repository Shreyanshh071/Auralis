package com.auralis.music.desktop.playback

import com.auralis.music.data.network.NewPipeDownloader
import com.auralis.music.data.network.RecordingMatches
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Imported metadata isn't a YouTube video. Resolve it before handing it to the extractor. */
object DesktopPlaybackMatch {
    fun isVideoId(id: String) = !id.startsWith("sp_") && !id.startsWith("spotify:") && Regex("[A-Za-z0-9_-]{11}").matches(id)

    suspend fun videoId(track: Track, search: (suspend (String) -> List<Track>)? = null): String? {
        val isCustomSearch = search != null
        val searchFn = search ?: ::searchVideos
        RecordingMatches.getMatchedVideoId(track.id)?.takeIf(::isVideoId)?.let { cachedId ->
            if (isCustomSearch || DesktopStreamResolver.resolve(cachedId) != null) {
                return cachedId
            }
            RecordingMatches.matchedVideoIds.remove(track.id)
            RecordingMatches.matchedDurations.remove(track.id)
        }
        if (isVideoId(track.id)) return track.id
        if (!track.id.startsWith("sp_") && !track.id.startsWith("spotify:")) return null

        val candidates = searchFn("${track.title} ${track.artist}").filter { isVideoId(it.id) }

        if (isCustomSearch) {
            val m = pickCandidate(track, candidates) ?: return null
            RecordingMatches.matchedVideoIds[track.id] = m.id
            RecordingMatches.matchedDurations[track.id] = m.duration.toLong()
            return m.id
        }

        val primaryMatches = pickCandidates(track, candidates)
        for (cand in primaryMatches) {
            if (DesktopStreamResolver.resolve(cand.id) != null) {
                RecordingMatches.matchedVideoIds[track.id] = cand.id
                RecordingMatches.matchedDurations[track.id] = cand.duration.toLong()
                com.auralis.music.util.Log.i("DesktopPlaybackMatch", "Matched '${track.title}' to ${cand.id}")
                return cand.id
            }
        }

        val cleanArtist = if (track.artist.equals("Spotify Artist", ignoreCase = true)) "" else track.artist
        val primaryArtist = cleanArtist.split(Regex("[,&/]|\\b(feat|ft|with)\\b", RegexOption.IGNORE_CASE)).firstOrNull()?.trim().orEmpty()
        val editQuery = if (primaryArtist.isNotBlank()) "${track.title} $primaryArtist" else track.title
        val videoCandidates = (searchVideoCandidates(editQuery) + if (editQuery != track.title) searchVideoCandidates(track.title) else emptyList())
            .filter { isVideoId(it.id) }

        val videoMatches = pickCandidates(track, videoCandidates)
        for (cand in videoMatches) {
            if (DesktopStreamResolver.resolve(cand.id) != null) {
                RecordingMatches.matchedVideoIds[track.id] = cand.id
                RecordingMatches.matchedDurations[track.id] = cand.duration.toLong()
                com.auralis.music.util.Log.i("DesktopPlaybackMatch", "Matched '${track.title}' to ${cand.id}")
                return cand.id
            }
        }

        return null
    }

    private fun pickCandidate(track: Track, candidates: List<Track>): Track? = pickCandidates(track, candidates).firstOrNull()

    private fun pickCandidates(track: Track, candidates: List<Track>): List<Track> {
        val list = mutableListOf<Track>()
        SearchQueryMatcher.findBestCandidateForTrack(track, candidates)?.let { list.add(it) }
        SearchQueryMatcher.findReuploadOfEdit(track, candidates)?.let { if (!list.any { x -> x.id == it.id }) list.add(it) }
        candidates.filter { SearchQueryMatcher.isRoundingOnlyLengthGap(track, it) }.forEach { if (!list.any { x -> x.id == it.id }) list.add(it) }
        candidates.filter { candidate ->
            val candBase = SearchQueryMatcher.baseTitle(candidate.title)
            val targetBase = SearchQueryMatcher.baseTitle(track.title)
            val sameBase = candBase.isNotBlank() && (candBase == targetBase || candBase.replace(" ", "") == targetBase.replace(" ", ""))
            val candNormArtist = SearchQueryMatcher.normalize(candidate.artist)
            val targetPrimaryArtist = SearchQueryMatcher.normalize(
                (if (track.artist.equals("Spotify Artist", ignoreCase = true)) "" else track.artist)
                    .split(Regex("[,&/]|\\b(feat|ft|with)\\b")).first().trim()
            )
            val sameArtist = targetPrimaryArtist.isNotBlank() && (candNormArtist.contains(targetPrimaryArtist) || targetPrimaryArtist.contains(candNormArtist))
            val lengthDiff = kotlin.math.abs(candidate.duration - track.duration)
            val lengthOk = track.duration <= 0L || candidate.duration <= 0L || lengthDiff <= SearchQueryMatcher.MAX_RECORDING_DELTA_SEC
            val noOtherFeature = SearchQueryMatcher.featuredArtists(candidate).isEmpty() ||
                SearchQueryMatcher.featuredArtists(candidate).all { f -> SearchQueryMatcher.normalize("${track.artist} ${track.title}").contains(SearchQueryMatcher.normalize(f)) }
            sameBase && sameArtist && lengthOk && noOtherFeature
        }.forEach { if (!list.any { x -> x.id == it.id }) list.add(it) }
        return list
    }

    private suspend fun searchVideoCandidates(query: String): List<Track> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Track>()
        try {
            val itc = com.auralis.music.data.network.InnerTubeClient()
            val videos = itc.searchForPlayback(query, com.auralis.music.data.network.InnerTubeClient.FILTER_VIDEOS)
            list.addAll(videos)
        } catch (_: Exception) {}
        if (list.isEmpty()) try {
            val itc = com.auralis.music.data.network.InnerTubeClient()
            val videos = itc.search(query, com.auralis.music.data.network.InnerTubeClient.FILTER_VIDEOS).songs
            list.addAll(videos)
        } catch (_: Exception) {}
        if (list.isEmpty()) try {
            DesktopStreamResolver.initializeExtractor()
            NewPipeDownloader.withRequestBudget(12_000L) {
                val search = ServiceList.YouTube.getSearchExtractor(query)
                search.fetchPage()
                val items = search.initialPage.items.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
                    val id = runCatching { ServiceList.YouTube.streamLHFactory.getId(item.url) }.getOrNull()
                        ?.takeIf(::isVideoId) ?: return@mapNotNull null
                    Track(id = id, title = item.name, artist = item.uploaderName.orEmpty(),
                        duration = item.duration, thumbnail = item.thumbnails.firstOrNull()?.url.orEmpty())
                }
                list.addAll(items)
            }
        } catch (_: Exception) {}
        list.distinctBy { it.id }
    }

    // Foreground playback uses YouTube's video search, independently of the background
    // YouTube Music import queue and its search cooldown. Never wait five minutes behind it.
    private suspend fun searchVideos(query: String): List<Track> = withContext(Dispatchers.IO) {
        try {
            val music = com.auralis.music.data.network.InnerTubeClient().searchForPlayback(query)
            if (music.isNotEmpty()) return@withContext music
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* Try the video endpoint once when Music isn't available. */ }
        DesktopStreamResolver.initializeExtractor()
        NewPipeDownloader.withRequestBudget(12_000L) {
            val search = ServiceList.YouTube.getSearchExtractor(query)
            search.fetchPage()
            search.initialPage.items.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
                val id = runCatching { ServiceList.YouTube.streamLHFactory.getId(item.url) }.getOrNull()
                    ?.takeIf(::isVideoId) ?: return@mapNotNull null
                Track(id = id, title = item.name, artist = item.uploaderName.orEmpty(),
                    duration = item.duration, thumbnail = item.thumbnails.firstOrNull()?.url.orEmpty())
            }
        }
    }
}
