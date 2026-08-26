package com.auralis.music

import com.auralis.music.data.network.SpotifyAccessToken
import com.auralis.music.data.network.SpotifyItemType
import com.auralis.music.data.network.SpotifyPlaylistImporter
import com.auralis.music.domain.model.Track
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaylistImporterTest {

    @Test
    fun extractResource_fromPlaylistUrl_returnsPlaylistIdAndType() {
        val url = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abcd1234efgh"
        val res = SpotifyPlaylistImporter.extractResource(url)
        assertNotNull(res)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", res!!.id)
        assertEquals(SpotifyItemType.PLAYLIST, res.type)
    }

    @Test
    fun extractResource_fromAlbumUrl_returnsAlbumIdAndType() {
        val url = "https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy"
        val res = SpotifyPlaylistImporter.extractResource(url)
        assertNotNull(res)
        assertEquals("4aawyAB9vmqN3uQ7FjRGTy", res!!.id)
        assertEquals(SpotifyItemType.ALBUM, res.type)
    }

    @Test
    fun extractResource_fromTrackUrl_returnsTrackIdAndType() {
        val url = "https://open.spotify.com/track/11dFghVXANMlKmJXsNCbNl"
        val res = SpotifyPlaylistImporter.extractResource(url)
        assertNotNull(res)
        assertEquals("11dFghVXANMlKmJXsNCbNl", res!!.id)
        assertEquals(SpotifyItemType.TRACK, res.type)
    }

    @Test
    fun extractResource_fromSpotifyUri_returnsResource() {
        val uri = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
        val res = SpotifyPlaylistImporter.extractResource(uri)
        assertNotNull(res)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", res!!.id)
        assertEquals(SpotifyItemType.PLAYLIST, res.type)
    }

    @Test
    fun extractResource_fromEmbedUrl_returnsResource() {
        val url = "https://open.spotify.com/embed/playlist/37i9dQZF1DXcBWIGoYBM5M"
        val res = SpotifyPlaylistImporter.extractResource(url)
        assertNotNull(res)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", res!!.id)
        assertEquals(SpotifyItemType.PLAYLIST, res.type)
    }

    @Test
    fun extractResource_fromDirect22CharId_returnsPlaylist() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        val res = SpotifyPlaylistImporter.extractResource(id)
        assertNotNull(res)
        assertEquals(id, res!!.id)
        assertEquals(SpotifyItemType.PLAYLIST, res.type)
    }

    @Test
    fun extractResource_fromInvalidUrl_returnsNull() {
        val url = "https://example.com/not-a-spotify-link"
        assertNull(SpotifyPlaylistImporter.extractResource(url))
    }

    @Test
    fun spotifyAccessToken_validityChecks() {
        val validToken = SpotifyAccessToken("test_token", System.currentTimeMillis() + 300_000L)
        assertTrue(validToken.isValid)

        val expiredToken = SpotifyAccessToken("test_token", System.currentTimeMillis() - 10_000L)
        assertFalse(expiredToken.isValid)

        val blankToken = SpotifyAccessToken("", System.currentTimeMillis() + 300_000L)
        assertFalse(blankToken.isValid)
    }

    @Test
    fun parseApiTrackItems_extractsMultipleTracksProperly() {
        val jsonArrayStr = """
            [
              {
                "track": {
                  "id": "track_1",
                  "name": "Starboy",
                  "duration_ms": 230000,
                  "artists": [{"name": "The Weeknd"}, {"name": "Daft Punk"}],
                  "album": {
                    "name": "Starboy",
                    "images": [{"url": "https://i.scdn.co/image/starboy.jpg"}]
                  },
                  "is_local": false
                }
              },
              {
                "track": {
                  "id": "track_2",
                  "name": "Blinding Lights",
                  "duration_ms": 200000,
                  "artists": [{"name": "The Weeknd"}],
                  "album": {
                    "name": "After Hours",
                    "images": [{"url": "https://i.scdn.co/image/afterhours.jpg"}]
                  },
                  "is_local": false
                }
              },
              {
                "track": {
                  "id": "local_track",
                  "name": "Local Audio",
                  "duration_ms": 120000,
                  "is_local": true
                }
              }
            ]
        """.trimIndent()

        val jsonArray = JSONArray(jsonArrayStr)
        val tracksList = mutableListOf<Track>()
        val importer = SpotifyPlaylistImporter()

        importer.parseApiTrackItems(jsonArray, "Default Playlist", tracksList)

        assertEquals(2, tracksList.size)

        val t1 = tracksList[0]
        assertEquals("sp_track_1", t1.id)
        assertEquals("Starboy", t1.title)
        assertEquals("The Weeknd, Daft Punk", t1.artist)
        assertEquals("Starboy", t1.album)
        assertEquals("https://i.scdn.co/image/starboy.jpg", t1.thumbnail)
        assertEquals(230L, t1.duration)

        val t2 = tracksList[1]
        assertEquals("sp_track_2", t2.id)
        assertEquals("Blinding Lights", t2.title)
        assertEquals("The Weeknd", t2.artist)
        assertEquals("After Hours", t2.album)
        assertEquals(200L, t2.duration)
    }

    @Test
    fun parseEmbedHtml_withNextDataScript_extractsPlaylistAndTracks() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <head>
            <script id="__NEXT_DATA__" type="application/json">
            {
              "props": {
                "pageProps": {
                  "state": {
                    "data": {
                      "entity": {
                        "title": "Today's Top Hits",
                        "subtitle": "Jung Kook is on top of the Hottest 50!",
                        "visualIdentity": {
                          "image": [{"url": "https://i.scdn.co/image/ab67706f00000002b4d3061508e50e85614927a4"}]
                        },
                        "trackList": [
                          {
                            "uri": "spotify:track:4cOdK2wGLETKBW3PvgPWqT",
                            "title": "Seven (feat. Latto)",
                            "subtitle": "Jung Kook, Latto",
                            "duration": 184400
                          },
                          {
                            "uri": "spotify:track:1BxfuPKGuaTgP7aM0XbdQA",
                            "title": "Cruel Summer",
                            "subtitle": "Taylor Swift",
                            "duration": 178000
                          }
                        ]
                      }
                    }
                  }
                }
              }
            }
            </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val importer = SpotifyPlaylistImporter()
        val playlist = importer.parseEmbedHtml(sampleHtml, "37i9dQZF1DXcBWIGoYBM5M", SpotifyItemType.PLAYLIST)
        assertNotNull(playlist)
        assertEquals("Today's Top Hits", playlist!!.title)
        assertEquals("Jung Kook is on top of the Hottest 50!", playlist.description)
        assertEquals("https://i.scdn.co/image/ab67706f00000002b4d3061508e50e85614927a4", playlist.coverUrl)
        assertEquals(2, playlist.tracks.size)

        val t0 = playlist.tracks[0]
        assertEquals("Seven", t0.title)
        assertEquals("Jung Kook, Latto", t0.artist)
        assertEquals(184L, t0.duration)
        assertEquals("sp_4cOdK2wGLETKBW3PvgPWqT", t0.id)

        val t1 = playlist.tracks[1]
        assertEquals("Cruel Summer", t1.title)
        assertEquals("Taylor Swift", t1.artist)
        assertEquals(178L, t1.duration)
        assertEquals("sp_1BxfuPKGuaTgP7aM0XbdQA", t1.id)
    }
}
