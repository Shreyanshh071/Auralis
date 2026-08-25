package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class InnerTubeParserTest {

    @Test
    fun `parseYtMusicSearchResults parses YouTube Music shelf and card JSON correctly`() {
        val client = InnerTubeClient()
        val mockYtmJson = JSONObject().apply {
            put("contents", JSONObject().apply {
                put("tabbedSearchResultsRenderer", JSONObject().apply {
                    put("tabs", JSONArray().apply {
                        put(JSONObject().apply {
                            put("tabRenderer", JSONObject().apply {
                                put("content", JSONObject().apply {
                                    put("sectionListRenderer", JSONObject().apply {
                                        put("contents", JSONArray().apply {
                                            // 1. Top result card (Artist)
                                            put(JSONObject().apply {
                                                put("musicCardShelfRenderer", JSONObject().apply {
                                                    put("title", JSONObject().apply {
                                                        put("runs", JSONArray().apply {
                                                            put(JSONObject().apply { put("text", "The Weeknd") })
                                                        })
                                                    })
                                                    put("subtitle", JSONObject().apply {
                                                        put("runs", JSONArray().apply {
                                                            put(JSONObject().apply { put("text", "Artist • 35M subscribers") })
                                                        })
                                                    })
                                                    put("onTap", JSONObject().apply {
                                                        put("browseEndpoint", JSONObject().apply {
                                                            put("browseId", "UC0WP5P-ufpRfjbNrmOWwLBQ")
                                                        })
                                                    })
                                                })
                                            })
                                            // 2. Songs shelf
                                            put(JSONObject().apply {
                                                put("musicShelfRenderer", JSONObject().apply {
                                                    put("contents", JSONArray().apply {
                                                        put(JSONObject().apply {
                                                            put("musicResponsiveListItemRenderer", JSONObject().apply {
                                                                put("flexColumns", JSONArray().apply {
                                                                    // Column 0: Title & VideoId
                                                                    put(JSONObject().apply {
                                                                        put("musicResponsiveListItemFlexColumnRenderer", JSONObject().apply {
                                                                            put("text", JSONObject().apply {
                                                                                put("runs", JSONArray().apply {
                                                                                    put(JSONObject().apply {
                                                                                        put("text", "Blinding Lights")
                                                                                        put("navigationEndpoint", JSONObject().apply {
                                                                                            put("watchEndpoint", JSONObject().apply {
                                                                                                put("videoId", "4NRXx6U8ABQ")
                                                                                            })
                                                                                        })
                                                                                    })
                                                                                })
                                                                            })
                                                                        })
                                                                    })
                                                                    // Column 1: Artist, Album, Duration
                                                                    put(JSONObject().apply {
                                                                        put("musicResponsiveListItemFlexColumnRenderer", JSONObject().apply {
                                                                            put("text", JSONObject().apply {
                                                                                put("runs", JSONArray().apply {
                                                                                    put(JSONObject().apply {
                                                                                        put("text", "The Weeknd")
                                                                                        put("navigationEndpoint", JSONObject().apply {
                                                                                            put("browseEndpoint", JSONObject().apply {
                                                                                                put("browseId", "UC0WP5P-ufpRfjbNrmOWwLBQ")
                                                                                            })
                                                                                        })
                                                                                    })
                                                                                    put(JSONObject().apply { put("text", " • ") })
                                                                                    put(JSONObject().apply { put("text", "After Hours") })
                                                                                    put(JSONObject().apply { put("text", " • ") })
                                                                                    put(JSONObject().apply { put("text", "3:20") })
                                                                                })
                                                                            })
                                                                        })
                                                                    })
                                                                })
                                                            })
                                                        })
                                                    })
                                                })
                                            })
                                        })
                                    })
                                })
                            })
                        })
                    })
                })
            })
        }

        val results = client.parseYtMusicSearchResults(mockYtmJson)
        assertEquals(1, results.artists.size)
        assertEquals("The Weeknd", results.artists[0].name)
        assertEquals("UC0WP5P-ufpRfjbNrmOWwLBQ", results.artists[0].id)

        assertEquals(1, results.songs.size)
        val song = results.songs[0]
        assertEquals("4NRXx6U8ABQ", song.id)
        assertEquals("Blinding Lights", song.title)
        assertEquals("The Weeknd", song.artist)
        assertEquals("After Hours", song.album)
        assertEquals(200L, song.duration)
    }

    @Test
    fun `parseRadioFromNextResponse extracts radio tracks from playlistPanelRenderer JSON correctly`() {
        val client = InnerTubeClient()
        val mockNextJson = JSONObject().apply {
            put("contents", JSONObject().apply {
                put("singleColumnMusicWatchNextResultsRenderer", JSONObject().apply {
                    put("playlist", JSONObject().apply {
                        put("playlistPanelRenderer", JSONObject().apply {
                            put("contents", JSONArray().apply {
                                // 1. Seed video
                                put(JSONObject().apply {
                                    put("playlistPanelVideoRenderer", JSONObject().apply {
                                        put("videoId", "seed123")
                                        put("title", JSONObject().apply {
                                            put("runs", JSONArray().apply {
                                                put(JSONObject().apply { put("text", "Seed Track Title") })
                                            })
                                        })
                                        put("longBylineText", JSONObject().apply {
                                            put("runs", JSONArray().apply {
                                                put(JSONObject().apply { put("text", "Artist 1") })
                                                put(JSONObject().apply { put("text", " • ") })
                                                put(JSONObject().apply { put("text", "3:30") })
                                            })
                                        })
                                    })
                                })
                                // 2. Radio next track 1
                                put(JSONObject().apply {
                                    put("playlistPanelVideoRenderer", JSONObject().apply {
                                        put("videoId", "radioTrack1")
                                        put("title", JSONObject().apply {
                                            put("runs", JSONArray().apply {
                                                put(JSONObject().apply { put("text", "Radio Track 1") })
                                            })
                                        })
                                        put("shortBylineText", JSONObject().apply {
                                            put("runs", JSONArray().apply {
                                                put(JSONObject().apply { put("text", "Artist 2") })
                                            })
                                        })
                                        put("lengthText", JSONObject().apply {
                                            put("runs", JSONArray().apply {
                                                put(JSONObject().apply { put("text", "4:15") })
                                            })
                                        })
                                    })
                                })
                                // 3. Radio next track 2
                                put(JSONObject().apply {
                                    put("playlistPanelVideoRenderer", JSONObject().apply {
                                        put("videoId", "radioTrack2")
                                        put("title", JSONObject().apply {
                                            put("runs", JSONArray().apply {
                                                put(JSONObject().apply { put("text", "Radio Track 2") })
                                            })
                                        })
                                        put("longBylineText", JSONObject().apply {
                                            put("runs", JSONArray().apply {
                                                put(JSONObject().apply { put("text", "Artist 3") })
                                            })
                                        })
                                        put("lengthText", JSONObject().apply {
                                            put("runs", JSONArray().apply {
                                                put(JSONObject().apply { put("text", "2:45") })
                                            })
                                        })
                                    })
                                })
                            })
                        })
                    })
                })
            })
        }

        val radioTracks = client.parseRadioFromNextResponse(mockNextJson, seedVideoId = "seed123")
        assertEquals(2, radioTracks.size)

        val track1 = radioTracks[0]
        assertEquals("radioTrack1", track1.id)
        assertEquals("Radio Track 1", track1.title)
        assertEquals("Artist 2", track1.artist)
        assertEquals(255L, track1.duration)

        val track2 = radioTracks[1]
        assertEquals("radioTrack2", track2.id)
        assertEquals("Radio Track 2", track2.title)
        assertEquals("Artist 3", track2.artist)
        assertEquals(165L, track2.duration)
    }
}

