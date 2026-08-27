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

        assertEquals(3, tracksList.size)

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

        val t3 = tracksList[2]
        assertEquals("sp_local_track", t3.id)
        assertEquals("Local Audio", t3.title)
        assertEquals(120L, t3.duration)
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
        assertEquals("Seven (feat. Latto)", t0.title)
        assertEquals("Jung Kook, Latto", t0.artist)
        assertEquals(184L, t0.duration)
        assertEquals("sp_4cOdK2wGLETKBW3PvgPWqT", t0.id)

        val t1 = playlist.tracks[1]
        assertEquals("Cruel Summer", t1.title)
        assertEquals("Taylor Swift", t1.artist)
        assertEquals(178L, t1.duration)
        assertEquals("sp_1BxfuPKGuaTgP7aM0XbdQA", t1.id)
    }

    @Test
    fun extractSessionTokenFromEmbed_extractsValidBearerToken() {
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
                      "settings": {
                        "session": {
                          "accessToken": "BQCJBudBda9gJuuv0qBSYeMROaK3u_ZP9KXLXz3D4IzZgU7xLP8pbKcXAc_50voEgRVjDXDnLG12iHNuN_lnAY2e_d517KDDtAp9nRMESMcIJbi2OovYNb-wvDkOl6UbfCj0tqabZTdH",
                          "accessTokenExpirationTimestampMs": 1787735265511,
                          "isAnonymous": true
                        }
                      }
                    }
                  }
                }
              }
            }
            </script>
            </head>
            </html>
        """.trimIndent()

        val token = SpotifyPlaylistImporter.extractSessionTokenFromEmbed(sampleHtml)
        assertNotNull(token)
        assertEquals("BQCJBudBda9gJuuv0qBSYeMROaK3u_ZP9KXLXz3D4IzZgU7xLP8pbKcXAc_50voEgRVjDXDnLG12iHNuN_lnAY2e_d517KDDtAp9nRMESMcIJbi2OovYNb-wvDkOl6UbfCj0tqabZTdH", token)
    }

    @Test
    fun parsePathfinderPlaylistItems_extractsTracksCorrectly() {
        val sampleJsonArray = """
            [
              {
                "uid": "item_uid_1",
                "itemV2": {
                  "__typename": "TrackResponseWrapper",
                  "data": {
                    "__typename": "Track",
                    "uri": "spotify:track:3Zwu2K0Qa5sT6teCCHPShP",
                    "name": "Thnks fr th Mmrs",
                    "trackDuration": {
                      "totalMilliseconds": 203506
                    },
                    "artists": {
                      "items": [
                        {
                          "profile": {
                            "name": "Fall Out Boy"
                          }
                        }
                      ]
                    },
                    "albumOfTrack": {
                      "name": "Infinity On High",
                      "coverArt": {
                        "sources": [
                          {
                            "url": "https://i.scdn.co/image/infinity.jpg"
                          }
                        ]
                      }
                    }
                  }
                }
              },
              {
                "uid": "item_uid_2",
                "itemV2": {
                  "__typename": "TrackResponseWrapper",
                  "data": {
                    "__typename": "Track",
                    "uri": "spotify:track:4cOdK2wGLETKBW3PvgPWqT",
                    "name": "Seven (feat. Latto)",
                    "trackDuration": {
                      "totalMilliseconds": 184400
                    },
                    "artists": {
                      "items": [
                        {
                          "profile": {
                            "name": "Jung Kook"
                          }
                        },
                        {
                          "profile": {
                            "name": "Latto"
                          }
                        }
                      ]
                    },
                    "albumOfTrack": {
                      "name": "Seven",
                      "coverArt": {
                        "sources": [
                          {
                            "url": "https://i.scdn.co/image/seven.jpg"
                          }
                        ]
                      }
                    }
                  }
                }
              }
            ]
        """.trimIndent()

        val jsonArray = JSONArray(sampleJsonArray)
        val outList = mutableListOf<Track>()
        val importer = SpotifyPlaylistImporter()

        importer.parsePathfinderPlaylistItems(jsonArray, "Test Album", outList)

        assertEquals(2, outList.size)

        val t1 = outList[0]
        assertEquals("sp_3Zwu2K0Qa5sT6teCCHPShP", t1.id)
        assertEquals("Thnks fr th Mmrs", t1.title)
        assertEquals("Fall Out Boy", t1.artist)
        assertEquals("Infinity On High", t1.album)
        assertEquals("https://i.scdn.co/image/infinity.jpg", t1.thumbnail)
        assertEquals(203L, t1.duration)

        val t2 = outList[1]
        assertEquals("sp_4cOdK2wGLETKBW3PvgPWqT", t2.id)
        assertEquals("Seven (feat. Latto)", t2.title)
        assertEquals("Jung Kook, Latto", t2.artist)
        assertEquals("Seven", t2.album)
        assertEquals("https://i.scdn.co/image/seven.jpg", t2.thumbnail)
        assertEquals(184L, t2.duration)
    }

    @Test
    fun parsePathfinderPlaylistItems_handlesLocalAndUnlinkedItemsWithoutDropping() {
        val sampleJsonArray = """
            [
              {
                "uid": "item_local_1",
                "itemV2": {
                  "__typename": "LocalTrackResponseWrapper",
                  "data": {
                    "__typename": "LocalTrack",
                    "uri": "spotify:local:::Song+From+Computer:180",
                    "name": "Song From Computer",
                    "trackDuration": {
                      "totalMilliseconds": 180000
                    },
                    "artists": {
                      "items": [
                        {
                          "profile": {
                            "name": "My Band"
                          }
                        }
                      ]
                    }
                  }
                }
              }
            ]
        """.trimIndent()

        val jsonArray = JSONArray(sampleJsonArray)
        val outList = mutableListOf<Track>()
        val importer = SpotifyPlaylistImporter()

        importer.parsePathfinderPlaylistItems(jsonArray, "My Album", outList)

        assertEquals(1, outList.size)
        val t = outList[0]
        assertEquals("Song From Computer", t.title)
        assertEquals("My Band", t.artist)
        assertEquals(180L, t.duration)
    }

    @Test
    fun testRealPlaylistImport() = kotlinx.coroutines.runBlocking {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        
        // 1. Fetch Embed HTML
        val embedUrl = "https://open.spotify.com/embed/playlist/0xiIxAmrgFOSU9lYkGK7Dt"
        val req = okhttp3.Request.Builder()
            .url(embedUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
            .build()
        val resp = client.newCall(req).execute()
        val html = resp.body?.string() ?: ""
        
        val token = SpotifyPlaylistImporter.extractSessionTokenFromEmbed(html)
        println("Extracted Token: $token")
        
        if (!token.isNullOrBlank()) {
            val importer = SpotifyPlaylistImporter()
            val pl = importer.fetchPlaylistViaPathfinder("0xiIxAmrgFOSU9lYkGK7Dt", token) {
                println("PROGRESS: $it")
            }
            println("=== PATHFINDER RESULT: title=${pl?.title}, tracksCount=${pl?.tracks?.size} ===")
            assertEquals(1715, pl?.tracks?.size)
        }
    }
}
