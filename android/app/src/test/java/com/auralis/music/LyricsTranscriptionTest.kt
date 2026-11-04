package com.auralis.music

import com.auralis.music.data.network.AiLyricsTranslator
import com.auralis.music.domain.model.*
import java.net.ServerSocket
import java.net.InetAddress
import java.io.DataInputStream
import kotlin.concurrent.thread
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LyricsTranscriptionTest {
    @Test fun plainLyricsCanBeTranscribedWithoutInventingSynchronizedTiming() = runBlocking {
        val plain = LyricsData(SyncType.PLAIN, emptyList(), plainLyrics = "ありがとう\n東京", provider = LyricsProvider.LOCAL)
        assertTrue(com.auralis.music.domain.lyrics.LyricsRomanizationContext.isJapanese(plain))
        val result = AiLyricsTranslator.translateLyrics("plain-japanese", plain,
            AiTranslationSettings(translationMode = "Transcription", apiKey = ""))!!
        assertEquals(listOf("arigatou", "toukyou"), result.lines.map { it.translatedText })
        assertEquals(SyncType.PLAIN, result.syncType)
        assertTrue(result.lines.all { it.time == 0L && it.words == null })
    }

    private fun lyrics() = LyricsData(
        SyncType.RICHSYNC,
        listOf(
            LyricLine(100, "नमस्ते", words = listOf(LyricWord("नमस्ते", 100, 700)),
                isBackground = true, endTime = 900, agent = "v2"),
            LyricLine(1000, "안녕"),
            LyricLine(2000, "HELLO")
        ), provider = LyricsProvider.LOCAL, durationMs = 3000, appliedOffsetMs = 120
    )

    @Test fun offlineTranscriptionCoversMultipleScriptsAndPreservesProviderTimingAndRoles() = runBlocking {
        val source = lyrics()
        val result = AiLyricsTranslator.translateLyrics("native-mixed", source,
            AiTranslationSettings(translationMode = "Transcription", apiKey = ""))!!
        assertEquals(listOf("namaste", "annyeong", null), result.lines.map { it.translatedText })
        assertEquals("namaste\nannyeong\n", result.translatedPlainLyrics)
        assertEquals("Romanized", result.translatedLanguage)
        assertEquals(source.appliedOffsetMs, result.appliedOffsetMs)
        source.lines.zip(result.lines).forEach { (before, after) ->
            assertEquals(before, after.copy(translatedText = before.translatedText))
        }
    }

    @Test fun cacheReuseRetainsTheCurrentTimingsRatherThanTheEarlierLyricsCopy() = runBlocking {
        val settings = AiTranslationSettings(translationMode = "Transcription", apiKey = "")
        val original = lyrics()
        AiLyricsTranslator.translateLyrics("retimed-native", original, settings)
        val retimed = original.copy(lines = original.lines.map { it.copy(time = it.time + 5000, agent = "updated") })
        val result = AiLyricsTranslator.translateLyrics("retimed-native", retimed, settings)!!
        assertEquals(retimed.lines.map { it.time }, result.lines.map { it.time })
        assertTrue(result.lines.all { it.agent == "updated" })
    }

    @Test fun latinLyricsAreSkippedAndMeaningTranslationDoesNotSilentlyBecomeTranscription() = runBlocking {
        val latin = lyrics().copy(lines = listOf(LyricLine(0, "Already Latin")))
        assertNull(AiLyricsTranslator.translateLyrics("latin-native", latin,
            AiTranslationSettings(translationMode = "Transcription", apiKey = "")))
        assertNull(AiLyricsTranslator.translateLyrics("meaning-native", lyrics(),
            AiTranslationSettings(translationMode = "Translation", apiKey = "")))
    }

    @Test fun aMalformedAiReplyFallsBackToAlignedNativeTranscription() = runBlocking {
        withServer("one incorrect line") { base, requests ->
            val result = AiLyricsTranslator.translateLyrics("invalid-remote-transcription", lyrics(),
                AiTranslationSettings(provider = "Custom", apiKey = "test-key", customBaseUrl = base,
                    translationMode = "Transcription"))!!
            assertEquals(listOf("namaste", "annyeong", null), result.lines.map { it.translatedText })
            val system = JSONObject(requests.single()).getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(system.contains("DO NOT translate the meaning"))
        }
    }

    @Test fun aMalformedMeaningTranslationIsRejectedWithoutShiftingTheLyrics() = runBlocking {
        withServer("one incorrect line") { base, requests ->
            assertNull(AiLyricsTranslator.translateLyrics("invalid-remote-translation", lyrics(),
                AiTranslationSettings(provider = "Custom", apiKey = "test-key", customBaseUrl = base,
                    translationMode = "Translation", targetLanguage = "English")))
            val system = JSONObject(requests.single()).getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(system.contains("Target language: English"))
            assertFalse(system.contains("DO NOT translate the meaning"))
        }
    }

    private suspend fun withServer(reply: String, action: suspend (String, List<String>) -> Unit) {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true, name = "lyrics-api-fixture") {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = DataInputStream(socket.getInputStream().buffered())
                    fun line(): String = buildString {
                        while (true) {
                            val next = input.read()
                            if (next < 0 || next == 10) break
                            if (next != 13) append(next.toChar())
                        }
                    }
                    line() // HTTP request line
                    var length = 0
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            length = header.substringAfter(':').trim().toInt()
                        }
                    }
                    val body = ByteArray(length)
                    input.readFully(body)
                    requests += body.toString(Charsets.UTF_8)
                    val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                        JSONObject().put("content", reply)))).toString().toByteArray()
                    socket.getOutputStream().use { out ->
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        out.write(response)
                    }
                }
            } catch (error: java.io.IOException) {
                if (!server.isClosed) throw error
            }
        }
        try {
            action("http://127.0.0.1:${server.localPort}/v1", requests)
        } finally {
            server.close()
            worker.join(5000)
        }
    }
}
