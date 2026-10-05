package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.AudioStreamResolver
import com.auralis.music.data.network.SpotifyPlaylistImporter
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyPlaybackMatchRegressionTest {
    @Test fun `multi artist release uses full credits when primary artist search misses`() = runBlocking {
        val source = Track(id = "sp_multicredit", title = "A Song", artist = "Composer, Singer",
            duration = 299)
        val recording = source.copy(id = "multicredit", artist = "Singer", duration = 300)
        val client = mockk<InnerTubeClient>()
        coEvery { client.search(any(), any()) } returns SearchResults()
        coEvery { client.search("A Song Composer, Singer", InnerTubeClient.FILTER_SONGS) } returns
            SearchResults(songs = listOf(recording))
        assertEquals(recording.id, SpotifyPlaylistImporter(innerTubeClient = client).matchToYouTube(source)?.id)
    }
    @Test fun `prepared recording bypasses catalog requests and retains source cover`() = runBlocking {
        val spotify = Track(id = "sp_prepared_playback", title = "A Song", artist = "Artist",
            duration = 210, thumbnail = "https://i.scdn.co/image/source")
        AudioStreamResolver.rememberMatchedVideoId(spotify.id, "abcdefghijk", 212L)
        val client = mockk<InnerTubeClient>()
        val saved = SpotifyPlaylistImporter(innerTubeClient = client)
            .enrichTracksWithYouTubeData(listOf(spotify)).single()
        assertEquals("abcdefghijk", saved.id)
        assertEquals(spotify.thumbnail, saved.thumbnail)
        assertEquals(212L, saved.duration)
        assertEquals(212L, AudioStreamResolver.getEffectiveDurationSec(spotify.id, spotify.duration))
        coVerify(exactly = 0) { client.search(any(), any()) }
    }

    @Test fun `expired stream does not discard the prepared Spotify recording`() {
        val id = "sp_expired_playback"
        AudioStreamResolver.rememberMatchedVideoId(id, "lmnopqrstuv")
        AudioStreamResolver.cacheStream("lmnopqrstuv_AUTO", "https://example.com/audio")
        AudioStreamResolver.invalidateStream(id)
        assertEquals("lmnopqrstuv", AudioStreamResolver.getMatchedVideoId(id))
        assertNull(AudioStreamResolver.getCachedStream("lmnopqrstuv_AUTO"))
        AudioStreamResolver.clearCache()
        assertEquals("lmnopqrstuv", AudioStreamResolver.getMatchedVideoId(id))
    }
    @Test fun `recent failed extraction falls back without repeating network work`() = runBlocking {
        val id = "sp_failed_native_replay"
        AudioStreamResolver.rememberMatchedVideoId(id, "failedyt123")
        AudioStreamResolver.rememberNativeExtractionFailure("failedyt123")
        assertNull(AudioStreamResolver.resolveAudioStream(id, "A Song", "Artist"))
        assertEquals("failedyt123", AudioStreamResolver.getMatchedVideoId(id))
    }
    @Test fun `Attention import stores the actual YouTube recording and duration`() = runBlocking {
        val spotify = Track(id = "sp_attention", title = "Attention", artist = "Charlie Puth", duration = 208)
        val youtube = Track(id = "vxUBYHz_q1I", title = "Attention", artist = "Charlie Puth", duration = 212)
        val client = mockk<InnerTubeClient>()
        coEvery { client.search(any(), any()) } returns SearchResults(songs = listOf(youtube))
        val importer = SpotifyPlaylistImporter(innerTubeClient = client)
        assertEquals(youtube.id, importer.matchToYouTube(spotify)?.id)
        val saved = importer.enrichTracksWithYouTubeData(listOf(spotify)).single()
        assertEquals(youtube.id, saved.id)
        assertEquals(212L, saved.duration)
        assertEquals(youtube.id, AudioStreamResolver.getMatchedVideoId(spotify.id))
    }

    @Test fun `short unavailable edit never maps to the ABBA album master`() = runBlocking {
        val edit = Track(id = "sp_edit", title = "Lay All Your Love On Me", artist = "Cwrti", duration = 159)
        val abba = Track(id = "abba_master", title = "Lay All Your Love On Me", artist = "ABBA", duration = 274)
        val client = mockk<InnerTubeClient>()
        coEvery { client.search(any(), any()) } returns SearchResults(songs = listOf(abba))
        assertNull(SpotifyPlaylistImporter(innerTubeClient = client).matchToYouTube(edit))
    }
}
