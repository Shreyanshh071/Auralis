package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SpotifyItemType
import com.auralis.music.data.network.SpotifyPlaylistImporter
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyArtworkAuthorityTest {
    private fun importer(vararg results: Track) = SpotifyPlaylistImporter(object : InnerTubeClient() {
        override suspend fun search(query: String, params: String?) = SearchResults(songs = results.toList())
    })
    private fun track(o: JSONObject) = Track(
        id = o.getString("id"), title = o.getString("title"), artist = o.getString("artist"),
        album = o.optString("album").takeUnless { it == "null" }, thumbnail = o.getString("thumbnail"),
        duration = o.getLong("duration")
    )
    private fun recordedCase(title: String) = runBlocking {
        val text = checkNotNull(javaClass.getResourceAsStream("/spotify-artwork-rematch-cases.json"))
            .bufferedReader().use { it.readText() }
        val cases = JSONArray(text)
        val case = (0 until cases.length()).map { cases.getJSONObject(it) }
            .first { it.getJSONObject("spotify").getString("title") == title }
        val original = track(case.getJSONObject("spotify"))
        val candidate = track(case.getJSONObject("youtube"))
        val actual = importer(candidate).enrichTracksWithYouTubeData(listOf(original)).single()
        assertEquals(candidate.id, actual.id)
        assertEquals(original.copy(id = candidate.id), actual)
        assertNotEquals(candidate.thumbnail, actual.thumbnail)
    }
    @Test fun mastMaganPreservesSpotifyReleaseArtwork() = recordedCase("Mast Magan")
    @Test fun channaMereyaPreservesSpotifyReleaseArtwork() = recordedCase("Channa Mereya")
    @Test fun raabtaPreservesSpotifyReleaseArtwork() = recordedCase("Raabta")

    private val missing = Track(id = "sp_recording", title = "Song", artist = "Singer", album = "Original Album", duration = 200)
    private val verified = missing.copy(id = "verifiedYt1", thumbnail = "https://catalog.example/original.jpg", duration = 201)

    @Test fun missingArtworkAcceptsVerifiedSameRecordingAndRelease() = runBlocking {
        val actual = importer(verified).enrichTracksWithYouTubeData(listOf(missing)).single()
        assertEquals(missing.copy(id = verified.id, thumbnail = verified.thumbnail), actual)
    }
    @Test fun missingArtworkRejectsDifferentReleaseButStillMatchesPlayback() = runBlocking {
        val compilation = verified.copy(album = "Singer Greatest Hits")
        val actual = importer(compilation).enrichTracksWithYouTubeData(listOf(missing)).single()
        assertEquals(compilation.id, actual.id)
        assertEquals("", actual.thumbnail)
        assertEquals(missing.album, actual.album)
    }
    @Test fun replacementRejectsUnknownDurationReleaseAndDifferentCreditedArtists() = runBlocking {
        for (candidate in listOf(verified.copy(album = null), verified.copy(duration = 0),
            verified.copy(artist = "Singer & Guest"), verified.copy(album = "Original Album (Deluxe)"))) {
            val actual = importer(candidate).enrichTracksWithYouTubeData(listOf(missing)).single()
            assertEquals("", actual.thumbnail)
        }
    }
    @Test fun replacementRejectsDifferentVersionAndWrongSong() = runBlocking {
        for (candidate in listOf(verified.copy(title = "Song (Live)"), verified.copy(title = "Other Song"),
            verified.copy(duration = 240))) {
            val actual = importer(candidate).enrichTracksWithYouTubeData(listOf(missing)).single()
            // Playback ranking is unchanged by this fix; artwork must still reject a loose match.
            assertEquals(missing, actual.copy(id = missing.id))
        }
    }
    @Test fun noMatchPreservesValidSpotifyCdnArtwork() = runBlocking {
        val original = missing.copy(thumbnail = "https://image-cdn-fa.spotifycdn.com/image/album")
        assertEquals(original, importer().enrichTracksWithYouTubeData(listOf(original)).single())
    }
    @Test fun playbackRematchPreservesAlbumIdAndAllReleaseFields() = runBlocking {
        val original = missing.copy(albumId = "retained-release", thumbnail = "https://i.scdn.co/image/original")
        val actual = importer(verified.copy(album = "Compilation")).enrichTracksWithYouTubeData(listOf(original)).single()
        assertEquals(original.copy(id = verified.id), actual)
    }
    @Test fun correctedMatchAlsoPreservesExistingReleaseArtwork() = runBlocking {
        val saved = missing.copy(id = "oldVideo001", thumbnail = "https://i.scdn.co/image/original")
        val wrongRecording = saved.copy(duration = 260, thumbnail = "https://catalog.example/other.jpg")
        val actual = importer(wrongRecording, verified.copy(album = "Compilation")).correctedMatch(saved)
        assertEquals(saved.copy(id = verified.id), actual)
    }
}
