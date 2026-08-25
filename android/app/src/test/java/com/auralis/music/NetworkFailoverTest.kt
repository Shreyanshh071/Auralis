package com.auralis.music

import com.auralis.music.data.remote.InvidiousApi
import com.auralis.music.data.remote.PipedApi
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkFailoverTest {

    @Test
    fun `pipedApi fails over to second instance on HTTP 429 rate limit`() = runBlocking {
        val instances = listOf("https://instance1.test", "https://instance2.test")

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("instance1.test")) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body("Rate limited".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    val json = """
                        {
                            "audioStreams": [
                                {
                                    "url": "https://stream.audio/valid.opus",
                                    "bitrate": 160000,
                                    "mimeType": "audio/webm",
                                    "codec": "opus"
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
            }
            .build()

        val pipedApi = PipedApi(client = client, instancePool = instances)
        val streamUrl = pipedApi.getAudioStreamUrl("dQw4w9WgXcQ")

        assertNotNull(streamUrl)
        assertEquals("https://stream.audio/valid.opus", streamUrl)
    }

    @Test
    fun `pipedApi fails over to next instance on HTTP 500 server error`() = runBlocking {
        val instances = listOf("https://broken.test", "https://working.test")

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("broken.test")) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Internal Server Error")
                        .body("Server Down".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    val json = """
                        {
                            "items": [
                                {
                                    "url": "/watch?v=abc12345",
                                    "title": "Starboy",
                                    "uploaderName": "The Weeknd",
                                    "duration": 230,
                                    "thumbnail": "https://img.test/thumb.jpg"
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
            }
            .build()

        val pipedApi = PipedApi(client = client, instancePool = instances)
        val songs = pipedApi.searchSongs("Starboy")

        assertEquals(1, songs.size)
        assertEquals("Starboy", songs[0].title)
        assertEquals("abc12345", songs[0].id)
    }

    @Test
    fun `invidiousApi fails over across instances on HTTP 503 and returns best audio stream`() = runBlocking {
        val instances = listOf("https://inv1.test", "https://inv2.test")

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("inv1.test")) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body("Down".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    val json = """
                        {
                            "adaptiveFormats": [
                                {
                                    "type": "audio/mp4; codecs=\"mp4a.40.2\"",
                                    "url": "https://stream.audio/low.m4a",
                                    "bitrate": 128000
                                },
                                {
                                    "type": "audio/webm; codecs=\"opus\"",
                                    "url": "https://stream.audio/high.opus",
                                    "bitrate": 256000
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
            }
            .build()

        val invidiousApi = InvidiousApi(client = client, instancePool = instances)
        val streamUrl = invidiousApi.getAudioStreamUrl("xyz987")

        assertNotNull(streamUrl)
        // Should select highest bitrate format (256kbps opus over 128kbps m4a)
        assertEquals("https://stream.audio/high.opus", streamUrl)
    }

    @Test
    fun `pipedApi returns null when all instances fail without crashing`() = runBlocking {
        val instances = listOf("https://fail1.test", "https://fail2.test")

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body("All down".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val pipedApi = PipedApi(client = client, instancePool = instances)
        val streamUrl = pipedApi.getAudioStreamUrl("nonexistent")

        assertNull(streamUrl)
    }
}
