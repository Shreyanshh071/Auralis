package com.auralis.music

import com.auralis.music.data.network.provider.*
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MultiProviderCascadeTest {

    @Test
    fun `JioSaavnLyricsSource successfully fetches and parses Bhojpuri lyrics`() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val json = if (url.contains("search.getResults")) {
                    """
                    {
                        "results": [
                            {
                                "id": "bhojpuri_song_123",
                                "song": "Raja Ji Ke Dilwa",
                                "primary_artists": "Pawan Singh",
                                "duration": "180",
                                "has_lyrics": "true"
                            }
                        ]
                    }
                    """.trimIndent()
                } else {
                    """
                    {
                        "lyrics": "[00:10.00] राजा जी के दिलवा\n[00:15.50] दे दिहले हमके",
                        "snippet": "राजा जी के दिलवा",
                        "lyrics_copyright": "Wave Music"
                    }
                    """.trimIndent()
                }

                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val source = JioSaavnLyricsSource(client = mockClient)
        val query = LyricsSearchQuery(
            title = "Raja Ji Ke Dilwa",
            artist = "Pawan Singh",
            durationSec = 180
        )

        val candidate = source.search(query)
        assertNotNull(candidate)
        assertEquals(LyricsProvider.JIOSAAVN, candidate?.provider)
        assertEquals(SyncType.LINE_SYNC, candidate?.syncType)
        assertEquals(2, candidate?.lyricsData?.lines?.size)
        assertEquals(10000L, candidate?.lyricsData?.lines?.get(0)?.time)
        assertEquals("राजा जी के दिलवा", candidate?.lyricsData?.lines?.get(0)?.text)
        assertTrue("Confidence should be high", (candidate?.confidence ?: 0) >= 70)
    }

    @Test
    fun `NetEaseLyricsSource fetches and parses international line-synced LRC`() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val json = if (url.contains("search/get/web")) {
                    """
                    {
                        "result": {
                            "songs": [
                                {
                                    "id": 987654,
                                    "name": "Dynamite",
                                    "artists": [{"name": "BTS"}],
                                    "duration": 199000
                                }
                            ]
                        }
                    }
                    """.trimIndent()
                } else {
                    """
                    {
                        "lrc": {
                            "lyric": "[00:05.10] 'Cause I-I-I'm in the stars tonight\n[00:08.30] So watch me bring the fire"
                        }
                    }
                    """.trimIndent()
                }

                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val source = NetEaseLyricsSource(client = mockClient)
        val query = LyricsSearchQuery(
            title = "Dynamite",
            artist = "BTS",
            durationSec = 199
        )

        val candidate = source.search(query)
        assertNotNull(candidate)
        assertEquals(LyricsProvider.NETEASE, candidate?.provider)
        assertEquals(SyncType.LINE_SYNC, candidate?.syncType)
        assertEquals(2, candidate?.lyricsData?.lines?.size)
        assertEquals(5100L, candidate?.lyricsData?.lines?.get(0)?.time)
    }

    @Test
    fun `GeniusLyricsSource parses HTML lyrics container for plain text fallback`() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val responseBody = if (url.contains("api/search/multi")) {
                    """
                    {
                        "response": {
                            "sections": [
                                {
                                    "type": "song",
                                    "hits": [
                                        {
                                            "type": "song",
                                            "result": {
                                                "title": "Lose Yourself",
                                                "primary_artist": {"name": "Eminem"},
                                                "url": "https://genius.com/Eminem-lose-yourself-lyrics"
                                            }
                                        }
                                    ]
                                }
                            ]
                        }
                    }
                    """.trimIndent()
                } else {
                    """
                    <html>
                    <body>
                    <div data-lyrics-container="true">Look, if you had one shot<br/>Or one opportunity</div>
                    </body>
                    </html>
                    """.trimIndent()
                }

                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val source = GeniusLyricsSource(client = mockClient)
        val query = LyricsSearchQuery(
            title = "Lose Yourself",
            artist = "Eminem"
        )

        val candidate = source.search(query)
        assertNotNull(candidate)
        assertEquals(LyricsProvider.GENIUS, candidate?.provider)
        assertEquals(SyncType.PLAIN, candidate?.syncType)
        assertEquals(2, candidate?.lyricsData?.lines?.size)
        assertEquals("Look, if you had one shot", candidate?.lyricsData?.lines?.get(0)?.text)
    }
}
