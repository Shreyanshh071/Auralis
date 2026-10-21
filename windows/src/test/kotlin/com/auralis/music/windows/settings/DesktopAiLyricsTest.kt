package com.auralis.music.windows.settings

import com.auralis.music.domain.model.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.*

class DesktopAiLyricsTest {
    @Test fun configuredEndpointAndModelDriveRealRequestsWithoutChangingTiming() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        var request: JSONObject? = null
        var authorization: String? = null
        var responseText = "[\"Translated one\",\"Translated two\"]"
        server.createContext("/v1/models") { exchange ->
            val bytes="""{"data":[{"id":"provider-model-b"},{"id":"provider-model-a"}]}""".toByteArray()
            exchange.sendResponseHeaders(200,bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/chat/completions") { exchange ->
            request=JSONObject(exchange.requestBody.bufferedReader().readText())
            authorization=exchange.requestHeaders.getFirst("Authorization")
            val json=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("message",JSONObject().put("content",responseText))))
            val bytes=json.toString().toByteArray()
            exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val settings=AiTranslationSettings(provider="Custom",apiKey="test-key",model="provider-model-a",
                targetLanguage="Hindi",systemPrompt="Keep the imagery.",customBaseUrl="http://127.0.0.1:${server.address.port}/v1")
            val client=DesktopAiLyricsClient()
            assertEquals(listOf("provider-model-a","provider-model-b"),client.models(settings))
            val words=listOf(LyricWord("One",1000,400))
            val original=LyricsData(SyncType.RICHSYNC,listOf(LyricLine(1000,"One",words=words),LyricLine(2500,"Two")),provider=LyricsProvider.LOCAL)
            val result=client.translate(original,settings)
            assertEquals("provider-model-a",request!!.getString("model"))
            assertEquals("Bearer test-key",authorization)
            val prompt=request!!.getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(prompt.contains("Hindi") && prompt.contains("Keep the imagery."))
            assertEquals(original.lines.map { it.time },result.lines.map { it.time })
            assertEquals(words,result.lines.first().words)
            assertEquals("Translated one",result.lines.first().translatedText)
            responseText="[\"missing a line\"]"
            assertFailsWith<IllegalArgumentException> { client.translate(original,settings) }
            assertNull(original.lines.first().translatedText)
        } finally { server.stop(0) }
    }
}
