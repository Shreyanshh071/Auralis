package com.auralis.music

import com.auralis.music.data.network.RecordingMatches
import com.auralis.music.data.network.discord.discordListenUrl
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscordLinksTest {
    @Test fun youtubeTrackLinksToMusic() {
        assertEquals("https://music.youtube.com/watch?v=jNQXAC9IVRw",
            discordListenUrl(Track(id = "jNQXAC9IVRw")))
    }

    @Test fun redirectedRecordingLinksToTheAudioActuallyPlayed() {
        assertEquals("https://music.youtube.com/watch?v=PvM79DJ2PmM",
            discordListenUrl(Track(id = "sBzrzS1Ag_g")))
    }

    @Test fun importedTrackUsesItsMatchedRecording() {
        val id = "sp_discord_link_test"
        RecordingMatches.matchedVideoIds[id] = "jNQXAC9IVRw"
        try {
            assertEquals("https://music.youtube.com/watch?v=jNQXAC9IVRw",
                discordListenUrl(Track(id = id, source = TrackSource.CURATED)))
        } finally {
            RecordingMatches.matchedVideoIds.remove(id)
        }
    }

    @Test fun localAndUnmatchedTracksHaveNoMisleadingListeningLink() {
        assertEquals("", discordListenUrl(Track(id = "jNQXAC9IVRw", source = TrackSource.LOCAL)))
        assertEquals("", discordListenUrl(Track(id = "sp_unmatched_song")))
        assertEquals("", discordListenUrl(Track(id = "bad&url=1234")))
        assertEquals("", discordListenUrl(Track()))
    }
}
