package com.auralis.music

import com.auralis.music.data.network.provider.BetterLyricsSource
import com.auralis.music.data.network.provider.LyricsSearchQuery
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.lyrics.MasterMatchStatus
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BetterLyricsVocalWindowTest {
    private val ttml = """<tt><body dur="00:02:52.600"><div>
        <p begin="00:00:11.040" end="00:00:15.890">First line</p>
        <p begin="00:00:15.890" end="00:00:21.910">Second line</p>
        <p begin="00:02:49.000" end="00:02:52.600">Last line</p>
    </div></body></tt>""".trimIndent()

    private val query = LyricsSearchQuery(
        title = "Loving Machine",
        artist = "TV Girl",
        album = "Who Really Cares",
        durationSec = 227L,
        durationMs = 227_000L
    )

    @Test
    fun officialTtmlOutroWindowIsNotTreatedAsSongDuration() = runBlocking {
        val json = "{\"ttml\":\"${ttml.replace("\"", "\\\"").replace("\n", "\\n")}\"}"
        val source = sourceWithResponses { url ->
            if (url.contains("boidu.dev")) 200 to json else 404 to "{}"
        }

        val candidate = source.search(query)
        assertNotNull(candidate)
        assertEquals(11_040L, candidate!!.lyricsData.lines.first().time)
        assertEquals(null, candidate.lyricsData.durationMs)
        assertEquals(
            MasterMatchStatus.COMPATIBLE_OFFSET,
            LyricsAlignmentEngine.evaluateMasterMatch(candidate.lyricsData, 227_000L)
        )
    }

    @Test
    fun binimumCatalogDurationOverridesTtmlVocalWindow() = runBlocking {
        val results = """{"results":[{"lyricsUrl":"https://lrc.red/test.ttml","track_name":"Loving Machine","artist_name":"TV Girl","duration":227,"timing_type":"line"}]}"""
        val source = sourceWithResponses { url ->
            when {
                url.contains("boidu.dev") -> 404 to "{}"
                url.contains("lrc.red") -> 200 to ttml
                else -> 200 to results
            }
        }

        val candidate = source.search(query)
        assertNotNull(candidate)
        assertEquals(11_040L, candidate!!.lyricsData.lines.first().time)
        assertEquals(227_000L, candidate.lyricsData.durationMs)
    }

    @Test
    fun decodedIntroSilenceDoesNotShiftBetterLyricsLate() {
        val raw = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(LyricLine(time = 11_040L, text = "First line")),
            provider = LyricsProvider.BETTER_LYRICS,
            leadingSilenceMs = 20L
        )
        assertEquals(11_040L, LyricsAlignmentEngine.alignToPlayback(raw, 227_000L, 1_000L).lines.first().time)

        val previouslyShifted = raw.copy(
            lines = listOf(LyricLine(time = 12_020L, text = "First line")),
            appliedOffsetMs = 980L
        )
        assertEquals(11_040L, LyricsAlignmentEngine.alignToPlayback(previouslyShifted, 227_000L).lines.first().time)
    }

    private fun sourceWithResponses(response: (String) -> Pair<Int, String>): BetterLyricsSource {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = response(chain.request().url.toString())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Not Found")
                .body(body.toResponseBody())
                .build()
        }.build()
        return BetterLyricsSource(client)
    }
}
