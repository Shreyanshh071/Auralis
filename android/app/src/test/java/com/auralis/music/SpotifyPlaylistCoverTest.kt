package com.auralis.music

import com.auralis.music.data.network.SpotifyLibrary
import com.auralis.music.domain.model.Playlist
import com.auralis.music.ui.library.usesTrackCollage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaylistCoverTest {
    @Test
    fun spotifyPlaylistCoverSurvivesABlankFirstImage() {
        val data = JSONObject("""{"images":{"items":[
          {"sources":[{"url":""}]},
          {"sources":[{"url":"https://i.scdn.co/image/custom-cover"}]}
        ]}}""")

        assertEquals("https://i.scdn.co/image/custom-cover", SpotifyLibrary.playlistCover(data))
    }

    @Test
    fun spotifyImportDisplaysItsCoverInsteadOfSongCollage() {
        val spotify = Playlist("imported:spotify:remote", "Mix", coverUrl = "https://i.scdn.co/image/custom-cover")
        val local = Playlist("local-id", "Mix", coverUrl = "https://i.scdn.co/image/custom-cover")

        assertFalse(usesTrackCollage(spotify, distinctCoverCount = 4))
        assertTrue(usesTrackCollage(local, distinctCoverCount = 4))
    }
}
