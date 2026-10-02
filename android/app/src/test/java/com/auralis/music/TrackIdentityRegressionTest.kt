package com.auralis.music

import com.auralis.music.data.network.ArtworkResolver
import com.auralis.music.data.network.AudioStreamResolver
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TrackIdentityRegressionTest {
    @Test
    fun knownMusicVideoUsesTheStudioRecording() {
        assertEquals("PvM79DJ2PmM", AudioStreamResolver.getMatchedVideoId("sBzrzS1Ag_g"))
        assertEquals(216L, AudioStreamResolver.getEffectiveDurationSec("sBzrzS1Ag_g", 342L))
    }

    @Test
    fun sourceArtworkWinsOverCachedSearchArtwork() {
        val track = Track(id = "art-source-id", title = "Same Song", artist = "Artist", thumbnail = "https://i.ytimg.com/vi/art-source-id/hqdefault.jpg")
        ArtworkResolver.cacheArtwork(track, "https://example.com/wrong-release.jpg")
        assertEquals(track.thumbnail, ArtworkResolver.getArtwork(track))
    }

    @Test
    fun aRemixCannotResolveToTheStandardRecording() {
        val target = Track(id = "sp_remix", title = "Song (Remix)", artist = "Artist", duration = 220L)
        val standard = Track(id = "standard", title = "Song", artist = "Artist", duration = 220L)
        assertNotNull(SearchQueryMatcher.recordingMismatch(target, standard))
        assertEquals(-1.0, SearchQueryMatcher.scoreTrackCandidate(target, standard), 0.0)
    }
}
