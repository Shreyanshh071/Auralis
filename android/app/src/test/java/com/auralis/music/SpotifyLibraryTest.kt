package com.auralis.music

import com.auralis.music.data.network.SpotifyLibrary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyLibraryTest {

    private val sampleJson = JSONObject(
        """
        {
          "items": [
            {
              "id": "37i9dQZF1DXcBWIGoYBM5M",
              "name": "Today's Top Hits",
              "public": true,
              "owner": { "display_name": "Spotify" },
              "images": [{ "url": "https://i.scdn.co/image/top_hits.jpg" }],
              "tracks": { "total": 50 }
            },
            {
              "id": "my_private_playlist_456",
              "name": "Late Night Vibe",
              "public": false,
              "owner": { "display_name": "Shreyanshh" },
              "images": [{ "url": "https://i.scdn.co/image/late_night.jpg" }],
              "tracks": { "total": 34 }
            }
          ],
          "next": "https://api.spotify.com/v1/me/playlists?offset=50&limit=50",
          "total": 2
        }
        """.trimIndent()
    )

    @Test
    fun `parsePlaylistsJson parses public and private playlists correctly`() {
        val (playlists, next) = SpotifyLibrary.parsePlaylistsJson(sampleJson)

        assertEquals(2, playlists.size)

        val topHits = playlists[0]
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", topHits.id)
        assertEquals("Today's Top Hits", topHits.title)
        assertEquals("Spotify • 50 tracks", topHits.subtitle)
        assertEquals("https://i.scdn.co/image/top_hits.jpg", topHits.thumbnail)
        assertEquals(50, topHits.trackCount)

        val privateList = playlists[1]
        assertEquals("my_private_playlist_456", privateList.id)
        assertEquals("Late Night Vibe", privateList.title)
        assertEquals("Shreyanshh • 34 tracks (Private)", privateList.subtitle)
        assertEquals("https://i.scdn.co/image/late_night.jpg", privateList.thumbnail)
        assertEquals(34, privateList.trackCount)

        assertEquals("https://api.spotify.com/v1/me/playlists?offset=50&limit=50", next)
    }

    @Test
    fun `parsePlaylistsJson handles empty items and null next`() {
        val emptyJson = JSONObject("""{ "items": [], "next": null, "total": 0 }""")
        val (playlists, next) = SpotifyLibrary.parsePlaylistsJson(emptyJson)

        assertEquals(0, playlists.size)
        assertNull(next)
    }
}
