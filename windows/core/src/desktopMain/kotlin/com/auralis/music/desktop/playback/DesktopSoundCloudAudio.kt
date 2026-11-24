package com.auralis.music.desktop.playback

import com.auralis.music.data.network.NewPipeDownloader
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.domain.model.Track
import com.auralis.music.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import kotlin.math.abs

/**
 * The last automatic source: the same recording on SoundCloud, through NewPipe's SoundCloud
 * service. Its requests go to SoundCloud's hosts, which YouTube's gate, meter and cooldown never
 * see, and never take the YouTube extraction gate. Go+ previews and geo-blocked uploads are skipped.
 */
object DesktopSoundCloudAudio {
    private const val TAG = "SoundCloudAudio"
    private const val LOOKUP_TIMEOUT_MS = 10_000L
    private const val SEARCH_TIMEOUT_MS = 5_000L
    private const val STREAM_TIMEOUT_MS = 4_000L
    private const val MAX_TRIES = 3
    private val DASH = Regex("""\s[-–—]\s""")

    internal data class Upload(val url: String, val name: String, val uploader: String, val durationSec: Long)

    private sealed interface Lookup {
        class Found(val url: String, val upload: Upload) : Lookup
        class Missed(val why: String) : Lookup
    }

    /**
     * A stream of [track]'s exact recording on SoundCloud (a progressive MP3 or an HLS playlist),
     * or null. Never throws except when the caller is canceled.
     */
    suspend fun find(track: Track): String? {
        if (track.id.startsWith("unavailable:") || track.title.isBlank()) return null
        val started = System.currentTimeMillis()
        val result: Lookup = try {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { lookUp(track) } ?: Lookup.Missed("timed out")
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            Lookup.Missed("timed out")
        } catch (e: Throwable) {
            // Throwable: a missing class in a trimmed build must not take playback down.
            Lookup.Missed("failed (${e.javaClass.simpleName})")
        }
        val elapsed = System.currentTimeMillis() - started
        when (result) {
            is Lookup.Found -> Log.i(TAG, "Matched '${track.title}' by '${track.artist}' (${track.duration}s) to '${result.upload.name}' " +
                "by '${result.upload.uploader}' (${result.upload.durationSec}s) in ${elapsed}ms")
            is Lookup.Missed -> Log.i(TAG, "No SoundCloud stream for '${track.title}' by '${track.artist}' (${track.duration}s): ${result.why} after ${elapsed}ms")
        }
        return (result as? Lookup.Found)?.url
    }

    private suspend fun lookUp(track: Track): Lookup {
        val variants = DesktopJioSaavnAudio.lookupVariants(track)
            .filter { it.artist.isNotBlank() && !it.artist.equals("Spotify Artist", ignoreCase = true) }
        if (variants.isEmpty()) return Lookup.Missed("no artist to search with")
        DesktopStreamResolver.initializeExtractor()
        var seen = 0
        for (variant in variants) {
            val query = "${TitleCleaner.cleanArtist(variant.artist)} ${TitleCleaner.cleanTitle(variant.title)}".trim()
            val uploads = NewPipeDownloader.cancellable(SEARCH_TIMEOUT_MS) { search(query) }
            seen += uploads.size
            val matches = pick(variant, uploads)
            for (upload in matches.take(MAX_TRIES)) {
                val url = try {
                    NewPipeDownloader.cancellable(STREAM_TIMEOUT_MS) { stream(upload, variant) }
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    null
                } catch (e: Throwable) {
                    // Go+ snippets and geo blocks throw here; the next matching upload may play.
                    Log.d(TAG, "Skipping '${upload.name}' by '${upload.uploader}': ${e.javaClass.simpleName}")
                    null
                } ?: continue
                return Lookup.Found(url, upload)
            }
            if (matches.isNotEmpty()) return Lookup.Missed("${matches.size} matching uploads, none playable")
        }
        return Lookup.Missed("no exact recording among $seen results")
    }

    private fun search(query: String): List<Upload> {
        val extractor = ServiceList.SoundCloud.getSearchExtractor(query, listOf(SoundcloudSearchQueryHandlerFactory.TRACKS), "")
        try {
            extractor.fetchPage()
        } catch (_: org.schabi.newpipe.extractor.search.SearchExtractor.NothingFoundException) {
            // NewPipe's way of saying "no results"; the next search variant may still find it.
            return emptyList()
        }
        return extractor.initialPage.items.filterIsInstance<StreamInfoItem>()
            .mapNotNull { item -> item.url?.let { Upload(it, item.name.orEmpty(), item.uploaderName.orEmpty(), item.duration) } }
    }

    /** The upload's best stream, or null when what SoundCloud serves isn't the whole song. */
    private fun stream(upload: Upload, target: Track): String? {
        val extractor = ServiceList.SoundCloud.getStreamExtractor(upload.url)
        extractor.fetchPage()
        if (!fullLength(extractor.length, upload, target)) {
            Log.d(TAG, "'${upload.name}' serves ${extractor.length}s, not the whole song")
            return null
        }
        return extractor.audioStreams
            .filter { it.isUrl && !it.content.isNullOrBlank() }
            .sortedWith(compareByDescending<AudioStream> { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.thenByDescending(::kbps))
            .firstOrNull()?.content
    }

    /** A 30 s preview (or any other cut) served in place of the listed upload is refused. */
    internal fun fullLength(servedSec: Long, upload: Upload, target: Track): Boolean {
        if (servedSec <= 0) return true
        if (upload.durationSec > 0 && abs(servedSec - upload.durationSec) > RecordingMatch.LENGTH_TOLERANCE_SEC) return false
        val known = target.duration > 0 && target.duration !in RecordingMatch.PLACEHOLDER_LENGTHS
        return !known || abs(servedSec - target.duration) <= RecordingMatch.LENGTH_TOLERANCE_SEC
    }

    /** The uploads that are [target]'s recording, the artist's own account first. */
    internal fun pick(target: Track, uploads: List<Upload>): List<Upload> {
        val artists = RecordingMatch.artistNames(target)
        val matches = RecordingMatch.matches(target, uploads) { asTrack(target, it) }
        return matches.sortedByDescending { upload ->
            RecordingMatch.artistNames(Track(id = upload.url, title = "", artist = upload.uploader)).any { it in artists }
        }
    }

    /**
     * The upload as the matcher sees it. Labels and fans title uploads "TV Girl - Loving Machine",
     * so when the part before the dash names the song's artist, that is the artist and the rest is
     * the title; otherwise the uploader is the artist.
     */
    internal fun asTrack(target: Track, upload: Upload): Track {
        val duration = upload.durationSec.coerceAtLeast(0L)
        val parts = upload.name.split(DASH, limit = 2).map(String::trim)
        if (parts.size == 2 && parts.all(String::isNotBlank)) {
            val prefix = RecordingMatch.artistNames(Track(id = upload.url, title = "", artist = parts[0]))
            if (prefix.isNotEmpty() && prefix.any { it in RecordingMatch.artistNames(target) }) {
                return Track(id = upload.url, title = parts[1], artist = TitleCleaner.cleanArtist(parts[0]), duration = duration)
            }
        }
        return Track(id = upload.url, title = upload.name, artist = TitleCleaner.cleanArtist(upload.uploader), duration = duration)
    }

    private fun kbps(stream: AudioStream): Int = stream.averageBitrate.takeIf { it > 0 } ?: (stream.bitrate / 1000)
}
