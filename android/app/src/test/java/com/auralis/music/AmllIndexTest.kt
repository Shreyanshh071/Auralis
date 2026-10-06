package com.auralis.music

import com.auralis.music.data.network.provider.AmllIndex
import com.auralis.music.data.network.provider.AmllLyricsSource
import com.auralis.music.data.network.provider.LyricsSearchQuery
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AmllIndexTest {

    // Lines in the shape of metadata/raw-lyrics-index.jsonl; the last line is the newest submission.
    private val indexJsonl = """
{"metadata":[["album",["Graduation"]],["artists",["Kanye West"]],["musicName",["Flashing Lights"]],["ncmMusicId",["18969075"]],["appleMusicId",["1451903882"]],["spotifyId",["5TRPicyLGbAF2LGBFbHGvO"]]],"rawLyricFile":"old-flashing.ttml"}
{"metadata":[["album",["Idol"]],["artists",["YOASOBI"]],["musicName",["Idol"]],["ncmMusicId",["2048982668"]]],"rawLyricFile":"idol.ttml"}
{"metadata":[["artists",["Charlie Puth","Selena Gomez"]],["musicName",["We Don't Talk Anymore (feat. Selena Gomez)"]],["spotifyId",["06KyNuuMOX1ROXRhj787tj"]]],"rawLyricFile":"wdta.ttml"}
{"metadata":[["album",["Graduation"]],["artists",["Kanye West","Dwele"]],["musicName",["Flashing Lights"]],["ncmMusicId",["18969075"]]],"rawLyricFile":"new-flashing.ttml"}
not json
""".trimIndent()

    private val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body dur="03:57.506"><div>
<p begin="00:10.000" end="00:12.000"><span begin="00:10.000" end="00:11.000">Flashing</span> <span begin="00:11.000" end="00:12.000">lights</span></p>
</div></body></tt>"""

    @Test
    fun `parses entries and skips malformed lines`() {
        assertEquals(4, AmllIndex.parse(indexJsonl).size)
    }

    @Test
    fun `title and artist match returns newest first`() {
        val index = AmllIndex.parse(indexJsonl)
        val q = LyricsSearchQuery(title = "Flashing Lights", artist = "Kanye West")
        val files = index.match(q, "Flashing Lights", "Kanye West").map { it.rawFile }
        assertEquals(listOf("new-flashing.ttml", "old-flashing.ttml"), files)
    }

    @Test
    fun `featured credit in index title still matches the bare title`() {
        val index = AmllIndex.parse(indexJsonl)
        val q = LyricsSearchQuery(title = "We Don't Talk Anymore", artist = "Charlie Puth")
        assertEquals(listOf("wdta.ttml"), index.match(q, "We Don't Talk Anymore", "Charlie Puth").map { it.rawFile })
    }

    @Test
    fun `platform id wins over title`() {
        val index = AmllIndex.parse(indexJsonl)
        val q = LyricsSearchQuery(title = "whatever", artist = "nobody", spotifyId = "06KyNuuMOX1ROXRhj787tj")
        assertEquals(listOf("wdta.ttml"), index.match(q, "whatever", "nobody").map { it.rawFile })
    }

    @Test
    fun `songs not in the index do not match`() {
        val index = AmllIndex.parse(indexJsonl)
        val q = LyricsSearchQuery(title = "The Less I Know The Better", artist = "Tame Impala")
        assertTrue(index.match(q, "The Less I Know The Better", "Tame Impala").isEmpty())
        val wrongArtist = LyricsSearchQuery(title = "Idol", artist = "Tame Impala")
        assertTrue(index.match(wrongArtist, "Idol", "Tame Impala").isEmpty())
    }

    @Test
    fun `source resolves via index with one ttml request and skips apple and netease on a miss`() = runBlocking {
        val otherCalls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            fun ok(body: String) = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody("text/plain".toMediaType())).build()
            when {
                url.endsWith("metadata/raw-lyrics-index.jsonl") -> ok(indexJsonl)
                url.contains("raw-lyrics/new-flashing.ttml") -> ok(ttml)
                else -> {
                    otherCalls.incrementAndGet()
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                        .code(404).message("Not Found").body("".toResponseBody(null)).build()
                }
            }
        }.build()
        val source = AmllLyricsSource(client = client)

        val hit = source.search(LyricsSearchQuery(title = "Flashing Lights", artist = "Kanye West", durationSec = 237))
        assertNotNull(hit)
        assertTrue(hit!!.lyricsData.lines.first().text.contains("Flashing"))

        val miss = source.search(LyricsSearchQuery(title = "The Less I Know The Better", artist = "Tame Impala", durationSec = 216))
        assertNull(miss)
        assertEquals("no Apple/NetEase/jsDelivr calls once the index is loaded", 0, otherCalls.get())
    }
}
