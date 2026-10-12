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
    fun isVideoId(id: String) = Regex("[A-Za-z0-9_-]{11}").matches(id)

    suspend fun videoId(track: Track, search: suspend (String) -> List<Track> = ::searchVideos): String? {
        RecordingMatches.getMatchedVideoId(track.id)?.takeIf(::isVideoId)?.let { return it }
        if (isVideoId(track.id)) return track.id
        if (!track.id.startsWith("sp_") && !track.id.startsWith("spotify:")) return null
        val candidates = search("${track.title} ${track.artist}")
            .filter { isVideoId(it.id) }
        val match = SearchQueryMatcher.findBestCandidateForTrack(track, candidates)
            ?: candidates.firstOrNull { SearchQueryMatcher.isRoundingOnlyLengthGap(track, it) }
            ?: return null
        RecordingMatches.matchedVideoIds[track.id] = match.id
        RecordingMatches.matchedDurations[track.id] = match.duration.toLong()
        com.auralis.music.util.Log.i("DesktopPlaybackMatch", "Matched '${track.title}' to ${match.id}")
        return match.id
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
