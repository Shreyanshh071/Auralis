package com.auralis.music

import com.auralis.music.data.remote.AmllApi
import com.auralis.music.data.remote.LrcLibApi
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualTierLyricsApiTest {

    @Test
    fun `lrclibApi parses synced lyrics correctly`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val json = """
                    {
                        "id": 12345,
                        "trackName": "Blinding Lights",
                        "artistName": "The Weeknd",
                        "syncedLyrics": "[00:10.50] Yeah\n[00:15.20] I've been on my own for long enough",
                        "plainLyrics": "Yeah\nI've been on my own for long enough"
                    }
                """.trimIndent()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val api = LrcLibApi(client = client)
        val lyrics = api.getExactLyrics("Blinding Lights", "The Weeknd")

        assertNotNull(lyrics)
        assertEquals(SyncType.LINE_SYNC, lyrics?.syncType)
        assertEquals(2, lyrics?.lines?.size)
        assertEquals(10500L, lyrics?.lines?.get(0)?.time)
        assertEquals("Yeah", lyrics?.lines?.get(0)?.text)
    }

    @Test
    fun `amllApi parses richsync TTML karaoke lyrics`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val ttml = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <tt xmlns="http://www.w3.org/ns/ttml">
                      <body>
                        <div>
                          <p begin="00:01.000" end="00:04.000">
                            <span begin="00:01.000" end="00:02.000">Hello </span>
                            <span begin="00:02.000" end="00:04.000">World</span>
                          </p>
                        </div>
                      </body>
                    </tt>
                """.trimIndent()

                val json = """
                    {
                        "data": [
                            {
                                "trackName": "Hello World",
                                "artistName": "Test Artist",
                                "ttml": ${org.json.JSONObject.quote(ttml)}
                            }
                        ]
                    }
                """.trimIndent()

                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val api = AmllApi(client = client)
        val lyrics = api.searchRichSyncLyrics("Hello World", "Test Artist")

        assertNotNull(lyrics)
        assertEquals(SyncType.RICHSYNC, lyrics?.syncType)
        assertTrue(lyrics?.lines?.isNotEmpty() == true)
        val firstLine = lyrics?.lines?.first()
        assertNotNull(firstLine?.words)
        assertEquals(2, firstLine?.words?.size)
    }
}
