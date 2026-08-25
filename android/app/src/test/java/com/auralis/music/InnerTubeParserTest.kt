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
}
