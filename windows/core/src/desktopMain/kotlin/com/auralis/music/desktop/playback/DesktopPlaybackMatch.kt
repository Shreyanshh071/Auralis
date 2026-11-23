package com.auralis.music.desktop.playback

import com.auralis.music.data.network.NewPipeDownloader
import com.auralis.music.data.network.RecordingMatches
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Imported metadata isn't a YouTube video. Resolve it before handing it to the extractor. */
object DesktopPlaybackMatch {
    fun isVideoId(id: String) = !id.startsWith("sp_") && !id.startsWith("spotify:") && Regex("[A-Za-z0-9_-]{11}").matches(id)

    suspend fun videoId(track: Track, search: (suspend (String) -> List<Track>)? = null): String? {
        val isCustomSearch = search != null
        val searchFn = search ?: ::searchVideos
        RecordingMatches.getMatchedVideoId(track.id)?.takeIf(::isVideoId)?.let { cachedId ->
            if (isCustomSearch) return cachedId
            val check = DesktopStreamResolver.resolveResult(cachedId)
            // A refused network or a spent allowance says nothing about the match: keep it.
            if (check.url != null || check !is ResolutionResult.Failed || !check.failure.category.alternateMayHelp) return cachedId
            RecordingMatches.matchedVideoIds.remove(track.id)
            RecordingMatches.matchedDurations.remove(track.id)
        }
        if (isVideoId(track.id)) return track.id
        if (!track.id.startsWith("sp_") && !track.id.startsWith("spotify:")) return null
        if (!isCustomSearch && stopTrying()) return null

        val candidates = searchFn("${track.title} ${track.artist}").filter { isVideoId(it.id) }

        if (isCustomSearch) {
            val m = pickCandidate(track, candidates) ?: return null
            RecordingMatches.matchedVideoIds[track.id] = m.id
            RecordingMatches.matchedDurations[track.id] = m.duration.toLong()
            return m.id
        }

        when (val probe = firstPlayable(track, pickCandidates(track, candidates))) {
            is Probe.Found -> return probe.id
            is Probe.Refused -> return probe.id
            Probe.Stop -> return null
            Probe.None -> Unit
        }

        val cleanArtist = if (track.artist.equals("Spotify Artist", ignoreCase = true)) "" else track.artist
        val primaryArtist = cleanArtist.split(Regex("[,&/]|\\b(feat|ft|with)\\b", RegexOption.IGNORE_CASE)).firstOrNull()?.trim().orEmpty()
        val editQuery = if (primaryArtist.isNotBlank()) "${track.title} $primaryArtist" else track.title
        val videoCandidates = (searchVideoCandidates(editQuery) + if (editQuery != track.title) searchVideoCandidates(track.title) else emptyList())
            .filter { isVideoId(it.id) }

        return when (val probe = firstPlayable(track, pickCandidates(track, videoCandidates))) {
            is Probe.Found -> probe.id
            is Probe.Refused -> probe.id
            Probe.Stop, Probe.None -> null
        }
    }

    private sealed interface Probe {
        /** A candidate that plays; remembered as the song's match. */
        class Found(val id: String) : Probe
        /** YouTube refused this network on a candidate: every other would fail alike. Not remembered. */
        class Refused(val id: String) : Probe
        /** Requests are paused or the allowance is spent. */
        object Stop : Probe
        object None : Probe
    }

    /**
     * Resolves [candidates] in turn until one plays. A network-wide refusal (verification, rate
     * limit, offline) stops at once and returns that candidate, so the lookup reports the refusal
     * and the song moves on to the other catalogs in about a second instead of after the whole
     * allowance.
     */
    private suspend fun firstPlayable(track: Track, candidates: List<Track>): Probe {
        for (cand in candidates) {
            if (stopTrying()) return Probe.Stop
            val result = DesktopStreamResolver.resolveResult(cand.id)
            if (result.url != null) {
                RecordingMatches.matchedVideoIds[track.id] = cand.id
                RecordingMatches.matchedDurations[track.id] = cand.duration.toLong()
                com.auralis.music.util.Log.i("DesktopPlaybackMatch", "Matched '${track.title}' to ${cand.id}")
                return Probe.Found(cand.id)
            }
            val refused = when (result) {
                is ResolutionResult.Deferred -> true
                is ResolutionResult.Failed -> result.failure.category.networkWide
                else -> false
            }
            if (refused) {
                com.auralis.music.util.Log.w("DesktopPlaybackMatch", "YouTube is refusing this network; trying no more uploads of '${track.title}'")
                return Probe.Refused(cand.id)
            }
        }
        return Probe.None
    }

    /**
     * Another upload of the same recording when [failedId] can't be played: an age-restricted video
     * ("cannot be watched anonymously") or one with no stream. YouTube Music's own audio track of
     * the song is usually open (Li Music plays those), so search for it, keep only the same
     * recording (title, artist, length, no other version), and remember it for next time.
     */
    suspend fun alternative(track: Track, failedId: String, resolve: suspend (String) -> String?): Pair<String, String>? {
        if (stopTrying()) return null
        val query = "${track.title} ${track.artist}".trim()
        val candidates = (runCatching { searchVideos(query) }.getOrDefault(emptyList()) +
            runCatching { searchVideoCandidates(query) }.getOrDefault(emptyList()))
            .filter { isVideoId(it.id) && it.id != failedId }.distinctBy { it.id }
        for (candidate in pickCandidates(track, candidates).take(4)) {
            if (stopTrying()) break
            val url = resolve(candidate.id) ?: continue
            RecordingMatches.matchedVideoIds[failedId] = candidate.id
            if (track.id != failedId) RecordingMatches.matchedVideoIds[track.id] = candidate.id
            com.auralis.music.util.Log.i("DesktopPlaybackMatch", "'${track.title}' ($failedId) can't be played; using ${candidate.id} (${candidate.title} by ${candidate.artist})")
            return candidate.id to url
        }
        com.auralis.music.util.Log.w("DesktopPlaybackMatch", "No playable upload of '${track.title}' besides $failedId")
        return null
    }

    /**
     * Trying more candidates is pointless while YouTube requests are paused (each would be refused
     * alike) or once the intent's request allowance is spent.
     */
    private suspend fun stopTrying(): Boolean = com.auralis.music.data.network.YouTubeBackoff.active ||
        kotlinx.coroutines.currentCoroutineContext()[com.auralis.music.data.network.PlaybackIntentContext]?.budget?.exhausted == true

    private suspend fun <T> meteredSearch(block: () -> T): T {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val budget = kotlinx.coroutines.currentCoroutineContext()[com.auralis.music.data.network.PlaybackIntentContext]?.budget
            ?: com.auralis.music.data.network.RequestBudget(com.auralis.music.data.network.RequestBudget.newId("m"), "match")
        val ticket = DesktopStreamResolver.gate.newTicket(RequestPriority.FOREGROUND)
        return DesktopStreamResolver.gate.withPermit(ticket) {
            if (stopTrying() || !com.auralis.music.data.network.YouTubeBackoff.permit())
                throw com.auralis.music.data.network.BudgetExhaustedException("Matching paused or allowance exhausted")
            NewPipeDownloader.cancellable(minOf(12_000L, budget.remainingMs()).coerceAtLeast(1L),
                com.auralis.music.data.network.YouTubeTraffic.Tag(budget.correlationId, budget.reason, budget), block)
        }
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
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {}
        if (list.isEmpty() && !stopTrying()) try {
            val itc = com.auralis.music.data.network.InnerTubeClient()
            val videos = itc.search(query, com.auralis.music.data.network.InnerTubeClient.FILTER_VIDEOS).songs
            list.addAll(videos)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {}
        if (list.isEmpty() && !stopTrying()) try {
            DesktopStreamResolver.initializeExtractor()
            meteredSearch {
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
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {}
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
        if (stopTrying()) return@withContext emptyList()
        DesktopStreamResolver.initializeExtractor()
        meteredSearch {
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
