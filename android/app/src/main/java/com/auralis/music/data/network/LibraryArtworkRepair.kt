package com.auralis.music.data.network

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import com.auralis.music.domain.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class ArtworkCondition { TRUSTWORTHY, MISSING, SUSPICIOUS, CLEARLY_MISMATCHED }
data class ArtworkRepairOutcome(val corrected: Int, val unresolved: Int)

internal data class CatalogReleaseCandidate(
    val track: Track,
    val released: String,
    val trackCount: Int,
    val collectionArtist: String?
)

/** Conservative triage: scanning is local; only non-trustworthy rows trigger a lookup. */
object LibraryArtworkClassifier {
    private val editorialCollectionWords = Regex(
        "(?i)\\b(?:chill|vibes|playlist|mixtape|mix|non[ -]?stop|top songs|party anthems|love songs|long drive|classic pop)\\b"
    )

    @Suppress("UNUSED_PARAMETER")
    fun isLikelyEditorialCollection(album: String?, albumArtist: String?, trackArtist: String): Boolean {
        return !album.isNullOrBlank() && editorialCollectionWords.containsMatchIn(album)
    }

    /** Rows with catalog art cannot be trusted solely because their URL is nonblank. */
    fun needsSourceAudit(track: Track): Boolean = track.source == TrackSource.YOUTUBE &&
        track.id.length == 11 &&
        (track.thumbnail.contains("googleusercontent.com") || track.thumbnail.contains("mzstatic.com"))

    fun isPlaceholderAlbum(album: String?): Boolean = album?.trim()?.let {
        Regex("(?i)^([a-z0-9])\\1{3,}$").matches(it) ||
            it.equals("unknown", true) || it.equals("playlist", true)
            || it.equals("songs", true)
    } == true

    /** An explicit film credit in the song title outranks a different collection label. */
    fun hasCreditedReleaseConflict(track: Track): Boolean {
        val movie = ArtworkSourceEvidence.creditedMovie(track.title) ?: return false
        val release = ArtworkIdentity.normalized(track.album.orEmpty())
        val credited = ArtworkIdentity.normalized(movie)
        return release.isNotBlank() && credited.isNotBlank() && !release.contains(credited)
    }

    fun classify(track: Track, all: List<Track>, playlists: List<Playlist>): ArtworkCondition =
        classifyAll(all, playlists)[track.id] ?: ArtworkCondition.TRUSTWORTHY

    fun classifyAll(all: List<Track>, playlists: List<Playlist>): Map<String, ArtworkCondition> {
        val linked = mutableMapOf<String, MutableList<Playlist>>()
        for (playlist in playlists) for (track in playlist.tracks) {
            linked.getOrPut(track.id) { mutableListOf() }.add(playlist)
        }
        val byArt = all.asSequence().filter { it.thumbnail.isNotBlank() }.groupBy { it.thumbnail }
        val titlesPerArt = byArt.mapValues { (_, tracks) ->
            tracks.map { ArtworkIdentity.normalized(it.title) }.distinct().size
        }
        val conflictingAlbums = byArt.mapValues { (_, tracks) ->
            tracks.mapNotNull { it.album?.takeIf(String::isNotBlank)?.let(ArtworkIdentity::normalized) }
                .distinct().size > 1
        }
        val importedCollectionTitles = playlists.asSequence()
            .filter { it.description?.startsWith("Imported from ", ignoreCase = true) == true }
            .map { ArtworkIdentity.normalized(it.title) }
            .filter { it.isNotBlank() }
            .toSet()
        val authoritativeReleases = all.asSequence()
            .filter { it.id.startsWith("sp_") && it.thumbnail.contains("scdn.co") &&
                !it.album.isNullOrBlank() }
            .groupBy { ArtworkIdentity.normalized(it.title) to ArtworkIdentity.normalized(it.artist) }
        return all.associate { track ->
            track.id to classifyOne(track, linked[track.id].orEmpty(),
                titlesPerArt[track.thumbnail] ?: 0, conflictingAlbums[track.thumbnail] == true,
                importedCollectionTitles, authoritativeReleases)
        }
    }

    private fun classifyOne(track: Track, linked: List<Playlist>, artTitles: Int,
        conflictingAlbums: Boolean, importedCollectionTitles: Set<String>,
        authoritativeReleases: Map<Pair<String, String>, List<Track>>): ArtworkCondition {
        if (track.source == TrackSource.LOCAL) return ArtworkCondition.TRUSTWORTHY
        val normalizedAlbum = ArtworkIdentity.normalized(track.album.orEmpty())
        if (normalizedAlbum.isNotBlank() && normalizedAlbum in importedCollectionTitles) {
            return ArtworkCondition.CLEARLY_MISMATCHED
        }
        if (linked.any { playlist ->
                val unrelated = playlist.tracks.filter { it.id != track.id &&
                    ArtworkIdentity.normalized(it.artist) != ArtworkIdentity.normalized(track.artist) }
                val reusedCover = track.thumbnail.isNotBlank() && playlist.coverUrl == track.thumbnail &&
                    unrelated.any { it.thumbnail == track.thumbnail }
                val reusedTitle = !track.album.isNullOrBlank() &&
                    ArtworkIdentity.normalized(playlist.title) == ArtworkIdentity.normalized(track.album) &&
                    unrelated.any { ArtworkIdentity.normalized(it.album.orEmpty()) == ArtworkIdentity.normalized(track.album) }
                reusedCover || reusedTitle
            }) return ArtworkCondition.CLEARLY_MISMATCHED
        if (track.thumbnail.isBlank()) return ArtworkCondition.MISSING
        if (hasCreditedReleaseConflict(track)) return ArtworkCondition.SUSPICIOUS
        if (track.id.startsWith("sp_") && !track.thumbnail.contains("scdn.co")) {
            return ArtworkCondition.SUSPICIOUS
        }
        val album = track.album?.takeIf { it.isNotBlank() }
        // Soundtracks and compilations legitimately share an album across many artists.
        // Flag only low-information placeholders, independent of a particular song name.
        if (isPlaceholderAlbum(album) || AlbumMetadataResolver.isCompilation(album) ||
            isLikelyEditorialCollection(album, null, track.artist)) {
            return ArtworkCondition.SUSPICIOUS
        }
        val sameSong = authoritativeReleases[
            ArtworkIdentity.normalized(track.title) to ArtworkIdentity.normalized(track.artist)
        ].orEmpty()
        if (!track.id.startsWith("sp_") && sameSong.any { verified ->
                ArtworkIdentity.normalized(verified.album.orEmpty()) != normalizedAlbum &&
                    track.thumbnail != verified.thumbnail
            }) return ArtworkCondition.SUSPICIOUS
        // Multiple songs on the same album normally share its cover. A repeated image is
        // suspicious only when those rows claim conflicting releases.
        if (artTitles >= 2 && conflictingAlbums) return ArtworkCondition.SUSPICIOUS
        return ArtworkCondition.TRUSTWORTHY
    }
}

internal object ArtworkSourceEvidence {
    private val fromReleaseSuffix = Regex("(?i)\\s*(?:[-–]\\s*from\\b|[\\(\\[]from\\b).*$")

    private fun recordingTitle(title: String): String =
        ArtworkIdentity.normalized(fromReleaseSuffix.replace(title, ""))

    fun sameRecordingTitle(a: String, b: String): Boolean =
        recordingTitle(a).isNotBlank() && recordingTitle(a) == recordingTitle(b)

    /** Catalog credits often omit a lyricist or shorten a featured artist's name. Require the
     * same recording title, length, primary performer and the next credited performer. */
    fun matchesCatalogRecording(requested: Track, candidate: Track): Boolean {
        val requestedTitle = recordingTitle(requested.title)
        val candidateTitle = recordingTitle(candidate.title)
        val creditedVersion = ArtworkIdentity.normalized(
            requested.artist.split(",", "&", "/").firstOrNull().orEmpty())
        val sameCreditedSingerVersion = requestedTitle.isNotBlank() &&
            creditedVersion.isNotBlank() &&
            candidateTitle == "$requestedTitle $creditedVersion version"
        if (!(sameRecordingTitle(requested.title, candidate.title) ||
                sameCreditedSingerVersion) || candidate.thumbnail.isBlank()) return false
        if (requested.duration > 0 && candidate.duration > 0 &&
            kotlin.math.abs(requested.duration - candidate.duration) > 12L) return false
        val separator = Regex("(?i)\\s*(?:,|&|/|\\band\\b|\\bfeat\\.?|\\bft\\.?)\\s*")
        val required = separator.split(requested.artist).map(ArtworkIdentity::normalized)
            .filter(String::isNotBlank).take(2)
        val credited = separator.split(candidate.artist).map(ArtworkIdentity::normalized)
            .filter(String::isNotBlank)
        if (required.isEmpty() || credited.isEmpty()) return false
        fun sameCredit(a: String, b: String): Boolean = a == b ||
            (a.length >= 5 && b.startsWith("$a ")) ||
            (b.length >= 5 && a.startsWith("$b "))
        return required.all { req -> credited.any { sameCredit(req, it) } }
    }

    fun creditedMovie(title: String): String? =
        Regex("(?i)\\bfrom\\s+[\"']([^\"']+)[\"']").find(title)
            ?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)

    fun matchesCreditedMovieRelease(requested: Track, candidate: Track, movie: String): Boolean {
        if (candidate.thumbnail.isBlank() || candidate.album.isNullOrBlank() ||
            !ArtworkIdentity.normalized(candidate.album.orEmpty()).contains(ArtworkIdentity.normalized(movie)) ||
            recordingTitle(requested.title) != recordingTitle(candidate.title)) return false
        val primary = ArtworkIdentity.normalized(requested.artist.split(",", "&", "/").first())
        if (primary.isBlank() ||
            !" ${ArtworkIdentity.normalized(candidate.artist)} ".contains(" $primary ")) return false
        return requested.duration <= 0 || candidate.duration <= 0 ||
            kotlin.math.abs(requested.duration - candidate.duration) <= 12L
    }

    fun sameArtwork(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        fun identity(url: String): String = when {
            url.contains("googleusercontent.com") -> url.substringBefore('=')
            url.contains("mzstatic.com") -> url.substringBeforeLast('/')
            else -> url.substringBefore('?')
        }
        return identity(a) == identity(b)
    }

    fun sameRelease(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        fun releaseName(value: String): String =
            ArtworkIdentity.normalized(value).removeSuffix(" single")
                .removeSuffix(" original motion picture soundtrack").trim()
        return releaseName(a) == releaseName(b)
    }

    /** Album browse headers can add a featured credit omitted by the exact video's album
     * label. Use this only after the two results have the same exact album browse ID. */
    fun sameSourceAlbumRelease(a: String?, b: String?): Boolean {
        if (sameRelease(a, b)) return true
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return sameRelease(TitleCleaner.cleanTitle(a), TitleCleaner.cleanTitle(b))
    }

    fun matchesProviderRelease(requested: Track, provider: Track): Boolean {
        if (!sameRelease(requested.album, provider.album) ||
            recordingTitle(requested.title) != recordingTitle(provider.title)) return false
        val primaryArtist = ArtworkIdentity.normalized(requested.artist.split(",", "&", "/").first())
        val providerArtist = " ${ArtworkIdentity.normalized(provider.artist)} "
        if (primaryArtist.isBlank() || !providerArtist.contains(" $primaryArtist ")) return false
        return requested.duration <= 0 || provider.duration <= 0 ||
            kotlin.math.abs(requested.duration - provider.duration) <= 12L
    }

    fun matchesExactVideo(requested: Track, exact: Track?): Boolean {
        if (exact == null || requested.id != exact.id || exact.thumbnail.isBlank()) return false
        // getQueue constructs its Track with TitleCleaner, so apply the same cleaning to
        // the saved title before comparing; otherwise songs with featured credits in their
        // display title fail their own exact-ID check.
        if (recordingTitle(TitleCleaner.cleanTitle(requested.title)) !=
            recordingTitle(TitleCleaner.cleanTitle(exact.title))) return false
        val primaryArtist = requested.artist.split(",", "&", "/").firstOrNull()?.trim().orEmpty()
        val artist = ArtworkIdentity.normalized(primaryArtist)
        val sourceArtist = " ${ArtworkIdentity.normalized(exact.artist.removeSuffix(" - Topic"))} "
        if (artist.isBlank() || !sourceArtist.contains(" $artist ")) return false
        if (requested.duration > 0 && exact.duration > 0 &&
            kotlin.math.abs(requested.duration - exact.duration) > 12L) return false
        return true
    }
}

class VerifiedReleaseLookup(
    private val spotify: SpotifyPlaylistImporter = SpotifyPlaylistImporter(),
    private val innerTube: InnerTubeClient = InnerTubeClient(),
    private val appleArtworkEvidence: (Track) -> Boolean? = AppleArtworkEvidence::belongsToTrack
) {
    internal fun selectPreferredCatalogRelease(
        requested: Track, candidates: List<CatalogReleaseCandidate>
    ): Track? {
        val matching = candidates.filter { item ->
            ArtworkSourceEvidence.matchesCatalogRecording(requested, item.track) &&
                !item.track.album.isNullOrBlank() &&
                !LibraryArtworkClassifier.isPlaceholderAlbum(item.track.album) &&
                !AlbumMetadataResolver.isCompilation(item.track.album) &&
                !LibraryArtworkClassifier.isLikelyEditorialCollection(
                    item.track.album, item.collectionArtist, requested.artist)
        }
        if (matching.isEmpty()) return null
        val existingAlbumIsReliable = !requested.album.isNullOrBlank() &&
            !LibraryArtworkClassifier.isPlaceholderAlbum(requested.album) &&
            !AlbumMetadataResolver.isCompilation(requested.album) &&
            !LibraryArtworkClassifier.isLikelyEditorialCollection(
                requested.album, null, requested.artist)
        if (existingAlbumIsReliable) {
            return matching.firstOrNull {
                ArtworkSourceEvidence.sameRelease(requested.album, it.track.album)
            }?.track
        }
        // For imports carrying a collection title, choose the earliest official album or
        // single that matches this recording. A random search order is not release evidence.
        val soundtrackTag = AlbumMetadataResolver.extractSoundtrackTag(requested.title)
        return matching.sortedWith(
            compareByDescending<CatalogReleaseCandidate> {
                soundtrackTag != null && ArtworkIdentity.normalized(it.track.album.orEmpty())
                    .contains(ArtworkIdentity.normalized(soundtrackTag))
            }.thenBy { it.released.ifBlank { "9999" } }
                .thenByDescending {
                    it.track.album.orEmpty().contains("Original Motion Picture Soundtrack", true)
                }.thenByDescending { it.trackCount > 3 }
        ).first().track
    }

    /** The queue video can carry a music-video or compilation image. Its album browse ID must
     * resolve to an album before that album artwork is used to repair a library row. */
    internal suspend fun verifiedSourceAlbum(track: Track): Track? {
        if (track.id.startsWith("sp_") || track.id.length != 11) return null
        val exact = try { innerTube.getQueue(listOf(track.id)).firstOrNull() }
            catch (_: Exception) { null } ?: return null
        if (!ArtworkSourceEvidence.matchesExactVideo(track, exact) ||
            exact.albumId.isNullOrBlank() ||
            LibraryArtworkClassifier.isPlaceholderAlbum(exact.album) ||
            AlbumMetadataResolver.isCompilation(exact.album) ||
            LibraryArtworkClassifier.hasCreditedReleaseConflict(
                track.copy(album = exact.album))) return null
        fun validAlbum(album: com.auralis.music.domain.model.PlaylistResult): Boolean =
            album.id == exact.albumId && album.thumbnail?.isNotBlank() == true &&
                ArtworkSourceEvidence.sameSourceAlbumRelease(album.title, exact.album) &&
                !LibraryArtworkClassifier.isLikelyEditorialCollection(
                    album.title, album.author, track.artist)
        val direct = try { innerTube.getAlbumById(exact.albumId.orEmpty()) }
            catch (_: Exception) { null }
        val album = direct?.takeIf(::validAlbum) ?: try {
            val queries = listOf(exact.album.orEmpty(),
                "${exact.artist} ${exact.album.orEmpty()}").distinct()
            queries.firstNotNullOfOrNull { query ->
                innerTube.search(query, InnerTubeClient.FILTER_ALBUMS).albums
                    .firstOrNull(::validAlbum)
            }
        } catch (_: Exception) { null } ?: return null
        return track.copy(album = album.title, albumId = album.id,
            thumbnail = album.thumbnail.orEmpty())
    }

    /**
     * Verify an already populated artwork URL against the exact YouTube recording. This only
     * returns a replacement when the provider association is contradicted and the source video
     * supplies a matching song. An unavailable provider or ambiguous source leaves Room alone.
     */
    suspend fun auditExisting(track: Track): Track? = withContext(Dispatchers.IO) {
        if (!LibraryArtworkClassifier.needsSourceAudit(track)) return@withContext null
        val exact = try { innerTube.getQueue(listOf(track.id)).firstOrNull() }
            catch (_: Exception) { null }
        if (!ArtworkSourceEvidence.matchesExactVideo(track, exact)) return@withContext null
        exact ?: return@withContext null
        val appleMatches = if (track.thumbnail.contains("mzstatic.com")) {
            appleArtworkEvidence(track)
        } else null
        // A verified catalog compilation is still a valid release for a song credited to a
        // film. The film title in the song name does not invalidate that compilation cover.
        if (appleMatches == true) return@withContext track
        val creditedMovie = ArtworkSourceEvidence.creditedMovie(track.title)
        if (creditedMovie != null &&
            (!track.thumbnail.contains("mzstatic.com") || appleMatches == false) &&
            !ArtworkIdentity.normalized(track.album.orEmpty())
                .contains(ArtworkIdentity.normalized(creditedMovie)) &&
            !ArtworkIdentity.normalized(exact.album.orEmpty())
                .contains(ArtworkIdentity.normalized(creditedMovie))) {
            val movieRelease = try {
                innerTube.search("${track.title} ${track.artist}", InnerTubeClient.FILTER_SONGS)
                    .songs.firstOrNull {
                        ArtworkSourceEvidence.matchesCreditedMovieRelease(track, it, creditedMovie)
                    }
            } catch (_: Exception) { null }
            if (movieRelease != null) {
                val verified = verifiedSourceAlbum(movieRelease)
                if (verified != null &&
                    ArtworkSourceEvidence.sameRelease(movieRelease.album, verified.album)) {
                    return@withContext track.copy(album = verified.album,
                        thumbnail = verified.thumbnail)
                }
            }
        }
        if (ArtworkSourceEvidence.sameArtwork(track.thumbnail, exact.thumbnail)) return@withContext track

        if (track.thumbnail.contains("mzstatic.com")) {
            // Network failures and region-restricted responses are not evidence of a mismatch.
            if (appleMatches == null) return@withContext null
            val sourceMatchesKnownRelease = ArtworkSourceEvidence.sameRelease(track.album, exact.album)
            val sourceMatchesCreditedMovie = creditedMovie != null &&
                ArtworkIdentity.normalized(exact.album.orEmpty()).contains(
                    ArtworkIdentity.normalized(creditedMovie))
            return@withContext verifiedSourceAlbum(track)?.takeIf {
                !LibraryArtworkClassifier.isPlaceholderAlbum(it.album) &&
                (sourceMatchesKnownRelease || sourceMatchesCreditedMovie) }
        }

        if (!ArtworkSourceEvidence.sameRelease(track.album, exact.album)) return@withContext null
        if (!exact.thumbnail.contains("googleusercontent.com")) return@withContext null
        // A search song with the same title, credited artist, duration and release can legitimately
        // use a different image from the exact video's thumbnail. Keep it when confirmed.
        val songWithCurrentImage = try {
            innerTube.search("${track.title} ${track.artist}", InnerTubeClient.FILTER_SONGS).songs
                .firstOrNull { ArtworkSourceEvidence.sameArtwork(track.thumbnail, it.thumbnail) }
        } catch (_: Exception) { return@withContext null }
        if (songWithCurrentImage != null) {
            if (ArtworkSourceEvidence.matchesProviderRelease(track, songWithCurrentImage)) {
                return@withContext track
            }
            // A catalog song with the same recording title may be a different edition or
            // contain imperfect credits. That does not prove this image belongs elsewhere.
            if (ArtworkSourceEvidence.sameRecordingTitle(track.title, songWithCurrentImage.title)) {
                return@withContext null
            }
            return@withContext verifiedSourceAlbum(track)
        }
        // Google image URLs contain no browse ID. Resolve the URL back to a catalog album
        // before replacing it; merely differing from the exact video's thumbnail is not enough.
        val albumQueries = listOf(track.album.orEmpty(),
            AlbumMetadataResolver.cleanAlbumTitle(track.album.orEmpty())).filter(String::isNotBlank).distinct()
        for (query in albumQueries) {
            val owner = try {
                innerTube.search(query, InnerTubeClient.FILTER_ALBUMS).albums.firstOrNull {
                    ArtworkSourceEvidence.sameArtwork(track.thumbnail, it.thumbnail.orEmpty())
                }
            } catch (_: Exception) { null }
            if (owner != null) {
                return@withContext if (ArtworkSourceEvidence.sameRelease(track.album, owner.title))
                    track else verifiedSourceAlbum(track)
            }
        }
        // A self-titled single has a precise release ID from the exact video. When the current
        // Google image is absent from both matching songs and album results, prefer that source.
        if (ArtworkIdentity.normalized(track.album.orEmpty()) ==
                ArtworkIdentity.normalized(track.title) &&
            !exact.albumId.isNullOrBlank()) return@withContext verifiedSourceAlbum(track)
        null
    }

    suspend fun lookup(track: Track): Track? = withContext(Dispatchers.IO) {
        if (track.id.startsWith("sp_")) {
            try {
                val token = spotify.getAccessToken()
                if (token != null) {
                    val exact = spotify.fetchTrackFromApi(track.id.removePrefix("sp_"), token)
                        ?.tracks?.firstOrNull()
                    if (exact != null && ArtworkIdentity.matches(track, exact) &&
                        exact.id == track.id && !exact.album.isNullOrBlank()) return@withContext exact
                }
            } catch (_: Exception) {}
        }

        // Placeholder album names came from a collection or a failed import. Restore the
        // release linked by the exact video only after its album browse ID confirms the cover.
        if (LibraryArtworkClassifier.isPlaceholderAlbum(track.album)) {
            verifiedSourceAlbum(track)?.let { return@withContext it }
        }

        // Independent catalog confirmation for a suspicious YouTube upload or unavailable Spotify API.
        try {
            val query = URLEncoder.encode("${track.title} ${track.artist}", "UTF-8")
            val connection = URL("https://itunes.apple.com/search?term=$query&entity=song&limit=30")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            try {
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val results = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        .optJSONArray("results")
                    val catalog = mutableListOf<CatalogReleaseCandidate>()
                    if (results != null) for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val art = item.optString("artworkUrl100")
                            .replace("100x100bb", "1400x1400bb")
                        val candidate = Track(
                            title = item.optString("trackName"), artist = item.optString("artistName"),
                            album = item.optString("collectionName").takeIf { it.isNotBlank() },
                            duration = item.optLong("trackTimeMillis") / 1000L,
                            thumbnail = art
                        )
                        catalog.add(CatalogReleaseCandidate(candidate,
                            item.optString("releaseDate"), item.optInt("trackCount"),
                            item.optString("collectionArtistName").takeIf { it.isNotBlank() }))
                    }
                    selectPreferredCatalogRelease(track, catalog)?.let { return@withContext it }
                }
            } finally { connection.disconnect() }
        } catch (_: Exception) {}

        // Never copy a video thumbnail merely because its ID and song metadata match: some
        // exact videos are filed under an editorial collection with its own cover.
        verifiedSourceAlbum(track)?.let { return@withContext it }
        // If the source upload has collection metadata, accept a catalog alternative only
        // when all identity checks pass and the verified results agree on one release.
        try {
            val matches = innerTube.search("${track.title} ${track.artist}", InnerTubeClient.FILTER_SONGS)
                .songs.filter { candidate ->
                    ArtworkIdentity.matches(track, candidate) &&
                        !candidate.album.isNullOrBlank() &&
                        !LibraryArtworkClassifier.isPlaceholderAlbum(candidate.album)
                }
            val releases = matches.map { ArtworkIdentity.normalized(it.album.orEmpty()) }.distinct()
            if (releases.size == 1) {
                for (match in matches) {
                    val verifiedAlbum = verifiedSourceAlbum(match) ?: continue
                    if (ArtworkSourceEvidence.sameRelease(match.album, verifiedAlbum.album)) {
                        return@withContext verifiedAlbum
                    }
                }
            }
        } catch (_: Exception) {}
        null
    }
}

internal object AppleArtworkEvidence {
    private val lookupLock = Any()
    private val lookupResponses = mutableMapOf<String, String>()
    private var lastLookupAt = 0L

    private fun lookupByUpc(upc: String): String? = synchronized(lookupLock) {
        lookupResponses[upc]?.let { return@synchronized it }
        // Apple Search has a small public request budget. The scan runs in the background;
        // pace distinct releases and reuse the response for every song on that album.
        val waitMs = 3100L - (System.currentTimeMillis() - lastLookupAt)
        if (waitMs > 0) Thread.sleep(waitMs)
        lastLookupAt = System.currentTimeMillis()
        val connection = URL("https://itunes.apple.com/lookup?upc=$upc&entity=song&limit=200")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@synchronized null
            connection.inputStream.bufferedReader().use { it.readText() }
                .also { lookupResponses[upc] = it }
        } finally { connection.disconnect() }
    }

    /** true = verified, false = contradicted, null = insufficient provider evidence. */
    fun belongsToTrack(track: Track): Boolean? {
        val upc = Regex("/([0-9]{10,14})\\.jpg/").find(track.thumbnail)?.groupValues?.get(1)
            ?: return null
        return try {
                val results = JSONObject(lookupByUpc(upc) ?: return null)
                    .optJSONArray("results") ?: return null
                if (results.length() == 0) return null
                var sawCatalogTrack = false
                var sawSameSongTitle = false
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    if (item.optString("wrapperType") != "track") continue
                    sawCatalogTrack = true
                    val candidate = Track(title = item.optString("trackName"),
                        artist = item.optString("artistName"),
                        album = item.optString("collectionName"),
                        duration = item.optLong("trackTimeMillis") / 1000L,
                        thumbnail = track.thumbnail)
                    if (ArtworkSourceEvidence.matchesProviderRelease(track, candidate)) return true
                    if (ArtworkSourceEvidence.sameRecordingTitle(track.title, candidate.title)) {
                        sawSameSongTitle = true
                    }
                }
                // A title match with imperfect release/artist metadata is inconclusive,
                // especially for imports with lossy text encoding or alternate editions.
                if (sawSameSongTitle) null else if (sawCatalogTrack) false else null
        } catch (_: Exception) { null }
    }


}

class LibraryArtworkRepair(
    private val repository: LibraryRepository,
    private val auditLookup: suspend (Track) -> Track? = VerifiedReleaseLookup()::auditExisting,
    private val lookup: suspend (Track) -> Track? = VerifiedReleaseLookup()::lookup
) {
    suspend fun run(onCorrected: suspend (Track) -> Unit = {}): Int =
        runDetailed(onCorrected).corrected

    suspend fun runDetailed(onCorrected: suspend (Track) -> Unit = {}): ArtworkRepairOutcome {
        val tracks = repository.getAllTracks()
        val playlists = repository.getPlaylists().first()
        val classification = LibraryArtworkClassifier.classifyAll(tracks, playlists)
        val importedTrackIds = playlists.asSequence()
            .filter { it.id.startsWith("imported:spotify:") || it.id.startsWith("imported:youtube_music:") }
            .flatMap { it.tracks.asSequence().map(Track::id) }.toSet()
        var corrected = 0
        var unresolved = 0
        val correctedIds = mutableSetOf<String>()
        val candidatesToCheck = tracks.filter { track ->
            val condition = classification[track.id]
            val importedSourceArt = track.id in importedTrackIds &&
                (track.thumbnail.contains("scdn.co") || track.thumbnail.contains("googleusercontent.com") ||
                    track.thumbnail.contains("ggpht.com") || track.thumbnail.contains("ytimg.com"))
            val needsRepair = condition != ArtworkCondition.TRUSTWORTHY &&
                !(importedSourceArt && condition != ArtworkCondition.CLEARLY_MISMATCHED &&
                    !LibraryArtworkClassifier.hasCreditedReleaseConflict(track))
            needsRepair || LibraryArtworkClassifier.needsSourceAudit(track)
        }.sortedBy {
            when (classification[it.id]) {
                ArtworkCondition.CLEARLY_MISMATCHED -> 0
                ArtworkCondition.MISSING -> 1
                ArtworkCondition.SUSPICIOUS -> 2
                else -> 3
            }
        }
        val permits = Semaphore(4)
        for (batch in candidatesToCheck.chunked(20)) {
            val lookedUp = coroutineScope {
                batch.map { track ->
                    async { permits.withPermit {
                        runCatching {
                            val condition = classification[track.id]
                            val sourceArtwork = track.thumbnail.contains("scdn.co") ||
                                track.thumbnail.contains("googleusercontent.com") ||
                                track.thumbnail.contains("ggpht.com") ||
                                track.thumbnail.contains("ytimg.com")
                            val preserveImportedSource = track.id in importedTrackIds && sourceArtwork &&
                                track.thumbnail.isNotBlank() &&
                                condition != ArtworkCondition.CLEARLY_MISMATCHED &&
                                !LibraryArtworkClassifier.hasCreditedReleaseConflict(track)
                            val preservePopulatedRelease = condition == ArtworkCondition.SUSPICIOUS &&
                                track.thumbnail.isNotBlank() &&
                                !LibraryArtworkClassifier.hasCreditedReleaseConflict(track) &&
                                !LibraryArtworkClassifier.isPlaceholderAlbum(track.album) &&
                                (!AlbumMetadataResolver.isCompilation(track.album) || preserveImportedSource) &&
                                !LibraryArtworkClassifier.isLikelyEditorialCollection(
                                    track.album, null, track.artist) &&
                                !track.id.startsWith("sp_")
                            if (condition == ArtworkCondition.TRUSTWORTHY || preservePopulatedRelease || preserveImportedSource) {
                                auditLookup(track)
                            } else {
                                // A collection title is not a release hint. Do not constrain
                                // catalog matching to that contaminated album field.
                                val lookupTrack = if ((condition == ArtworkCondition.CLEARLY_MISMATCHED &&
                                    !LibraryArtworkClassifier.isPlaceholderAlbum(track.album)) ||
                                    LibraryArtworkClassifier.hasCreditedReleaseConflict(track)) {
                                    track.copy(album = null)
                                } else track
                                lookup(lookupTrack)
                            }
                        }.getOrNull()
                    } }
                }.awaitAll()
            }
            for ((track, candidate) in batch.zip(lookedUp)) {
                val condition = classification[track.id] ?: ArtworkCondition.TRUSTWORTHY
                val verifiedIdentity = if (condition == ArtworkCondition.TRUSTWORTHY) {
                    ArtworkSourceEvidence.matchesExactVideo(track, candidate)
                } else candidate != null && if (candidate.id.isBlank() &&
                    candidate.thumbnail.contains("mzstatic.com")) {
                    ArtworkSourceEvidence.matchesCatalogRecording(track, candidate)
                } else ArtworkIdentity.matches(track, candidate)
                if (candidate == null || !verifiedIdentity) {
                    unresolved++
                    continue
                }
                val album = candidate.album?.takeIf { it.isNotBlank() } ?: track.album
                val art = candidate.thumbnail.takeIf { it.isNotBlank() }
                if (art == null) {
                    unresolved++
                    continue
                }
                // A provider can label a track with the title or cover of an imported
                // collection. Reject that metadata even when title/artist/duration match.
                val imported = playlists.filter {
                    it.description?.startsWith("Imported from ", ignoreCase = true) == true
                }
                val collectionAlbum = imported.any {
                    ArtworkIdentity.normalized(it.title) == ArtworkIdentity.normalized(album.orEmpty())
                }
                val collectionCover = imported.any { playlist ->
                    !playlist.coverUrl.isNullOrBlank() &&
                        ArtworkSourceEvidence.sameArtwork(playlist.coverUrl.orEmpty(), art) &&
                        playlist.tracks.any { it.id == track.id }
                }
                if (collectionAlbum || collectionCover) {
                    unresolved++
                    continue
                }
                if (condition == ArtworkCondition.TRUSTWORTHY &&
                    album == track.album && art == track.thumbnail) continue
                if ((condition == ArtworkCondition.CLEARLY_MISMATCHED && album == track.album) ||
                    (LibraryArtworkClassifier.isPlaceholderAlbum(track.album) &&
                        LibraryArtworkClassifier.isPlaceholderAlbum(album)) ||
                    (album == track.album && art == track.thumbnail)) {
                    unresolved++
                    continue
                }
                if (!repository.updateVerifiedTrackRelease(track.id, track.album, track.thumbnail, album, art)) {
                    unresolved++
                    continue
                }
                onCorrected(track.copy(album = album, thumbnail = art))
                correctedIds.add(track.id)
                corrected++
            }
        }
        // An imported collection name is provably not this song's release. If no catalog
        // replacement passed validation, remove only that false claim. Keep an independent
        // song image; discard the image only when it is the imported playlist cover itself.
        val imported = playlists.filter {
            it.description?.startsWith("Imported from ", ignoreCase = true) == true
        }
        val importedTitles = imported.map { ArtworkIdentity.normalized(it.title) }.toSet()
        for (track in tracks) {
            if (track.id in correctedIds ||
                classification[track.id] != ArtworkCondition.CLEARLY_MISMATCHED ||
                ArtworkIdentity.normalized(track.album.orEmpty()) !in importedTitles) continue
            val playlistCover = imported.any {
                ArtworkIdentity.normalized(it.title) == ArtworkIdentity.normalized(track.album.orEmpty()) &&
                    ArtworkSourceEvidence.sameArtwork(it.coverUrl.orEmpty(), track.thumbnail)
            }
            val retainedArt = if (playlistCover) "" else track.thumbnail
            if (repository.updateVerifiedTrackRelease(track.id, track.album, track.thumbnail,
                    null, retainedArt)) {
                onCorrected(track.copy(album = null, thumbnail = retainedArt))
                corrected++
            }
        }
        return ArtworkRepairOutcome(corrected, unresolved)
    }
}
