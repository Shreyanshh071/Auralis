package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.provider.*
import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.LyricsContentFilter
import com.auralis.music.domain.model.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * "Agar Tum Saath Ho" showed "印度电影《人生闹剧》插曲" and "女：Pal bhar thahar jaao" at the top.
 *
 * Both came from KuGou's fan-made LRC for the song (a Chinese header, a "female:" duet label,
 * inline Chinese translations). They reached the screen through the gap filler: the winning
 * Unison LRC starts at 24.1 s, so KuGou's earlier lines were copied into the "intro void" —
 * including a second spelling of the first line ("thahar" vs "thehar").
 */
class ForeignLyricAnnotationTest {

    // Shape of the real KuGou LRC (lyrics.kugou.com, id 478085857), first lines.
    private val kuGouLrc = """
        [ti:Agar Tum Saath Ho]
        [00:04.12]印度电影《人生闹剧》插曲
        [00:08.49]Agar tum saath ho （若你在我身旁）
        [00:17.75]女：Pal bhar thahar jaao
        [00:28.46]Dil ye sambhal jaaye
        [00:32.89]Kaise tumhe roka karun~
        [00:40.18]Meri taraf aata har gham phisal jaaye
    """.trimIndent()

    // Shape of the real Unison LRC for video FOA9iyxsW_A (the on-device winner).
    private val unisonLrc = """
        [00:24.14]Pal bhar thehar jaao
        [00:28.09]Dil ye sambhal jaaye
        [00:32.47]Kaise tumhe roka karu
        [00:36.20]Meri taraf aata
        [00:40.10]Har gam phisal jaaye
        [00:48.30]Aankho mein tumko bharu
    """.trimIndent()

    private fun lrc(text: String, provider: LyricsProvider) =
        LrcParser.parse(text, provider).copy(trackName = "Agar Tum Saath Ho", artistName = "Arijit Singh & Alka Yagnik", durationMs = 342_000L)

    private val cjk = Regex("""[぀-ヿ㐀-䶿一-鿿가-힯]""")

    @Test
    fun `KuGou annotations are removed without touching timing`() {
        val cleaned = LyricsContentFilter.removeForeignAnnotations(lrc(kuGouLrc, LyricsProvider.KUGOU))
        assertEquals(
            listOf("Agar tum saath ho", "Pal bhar thahar jaao", "Dil ye sambhal jaaye"),
            cleaned.lines.take(3).map { it.text }
        )
        assertEquals(listOf(8_490L, 17_750L, 28_460L), cleaned.lines.take(3).map { it.time })
        assertTrue(cleaned.lines.none { cjk.containsMatchIn(it.text) })
    }

    @Test
    fun `songs that are themselves Chinese Japanese or Korean are untouched`() {
        val chinese = lrc("[00:10.00]女：我想你\n[00:14.00]男：你在哪里\n[00:18.00]合：一起走吧", LyricsProvider.NETEASE)
        assertSame(chinese, LyricsContentFilter.removeForeignAnnotations(chinese))
        val japanese = lrc("[00:10.00]君の名は\n[00:14.00]Hello my friend\n[00:18.00]夢を見た", LyricsProvider.NETEASE)
        assertSame(japanese, LyricsContentFilter.removeForeignAnnotations(japanese))
    }

    @Test
    fun `lyrics without CJK are returned as is`() {
        val plain = lrc(unisonLrc, LyricsProvider.UNISON)
        assertSame(plain, LyricsContentFilter.removeForeignAnnotations(plain))
    }

    @Test
    fun `word-timed lines are never rewritten, only an all-CJK line is dropped`() {
        val data = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                LyricLine(1_000, "印度电影《人生闹剧》插曲", words = listOf(LyricWord("印度电影《人生闹剧》插曲", 1_000, 500))),
                LyricLine(2_000, "女：Pal bhar", words = listOf(LyricWord("女：Pal ", 2_000, 300), LyricWord("bhar", 2_300, 300))),
                LyricLine(3_000, "Dil ye sambhal", words = listOf(LyricWord("Dil ", 3_000, 300), LyricWord("ye ", 3_300, 300), LyricWord("sambhal", 3_600, 300))),
                LyricLine(4_000, "Kaise tumhe", words = listOf(LyricWord("Kaise ", 4_000, 300), LyricWord("tumhe", 4_300, 300)))
            ),
            provider = LyricsProvider.NETEASE
        )
        val cleaned = LyricsContentFilter.removeForeignAnnotations(data)
        assertEquals(listOf(2_000L, 3_000L, 4_000L), cleaned.lines.map { it.time })
        assertEquals(data.lines[1], cleaned.lines[0])
    }

    @Test
    fun `gap filler does not insert another transcription of an existing line`() {
        val primary = lrc(unisonLrc, LyricsProvider.UNISON)
        val secondary = LyricsContentFilter.removeForeignAnnotations(lrc(kuGouLrc, LyricsProvider.KUGOU))
        val filled = LyricsClient.fillLyricsGaps(primary, listOf(secondary))
        // "Agar tum saath ho" (8.5 s) is a genuine missing intro line; "Pal bhar thahar jaao" is not.
        assertEquals(listOf("Agar tum saath ho", "Pal bhar thehar jaao"), filled.lines.take(2).map { it.text })
        assertEquals(1, filled.lines.count { it.text.startsWith("Pal bhar", ignoreCase = true) })
    }

    @Test
    fun `race result for Agar Tum Saath Ho has no Chinese lines and no duplicate first line`() = runBlocking {
        fun <T : LyricsSource> source(mock: T, provider: LyricsProvider, data: LyricsData?, exactVideo: Boolean = false): T {
            every { mock.provider } returns provider
            coEvery { mock.search(any()) } returns data?.let {
                LyricsCandidate(it, confidence = 95, syncType = it.syncType, provider = provider,
                    isExactVideoMatch = exactVideo, matchedVideoId = if (exactVideo) "FOA9iyxsW_A" else null)
            }
            return mock
        }
        val unison = lrc(unisonLrc, LyricsProvider.UNISON).copy(isExactVideoMatch = true, matchedVideoId = "FOA9iyxsW_A")
        val client = LyricsClient(
            amllSource = source(mockk(), LyricsProvider.AMLL, null),
            betterLyricsSource = source(mockk(), LyricsProvider.BETTER_LYRICS, null),
            unisonSource = source(mockk(), LyricsProvider.UNISON, unison, exactVideo = true),
            paxsenixSource = source(mockk(), LyricsProvider.PAXSENIX, null),
            lrcLibSource = source(mockk(), LyricsProvider.LRCLIB, null),
            jioSaavnSource = source(mockk(), LyricsProvider.JIOSAAVN, null),
            netEaseSource = source(mockk(), LyricsProvider.NETEASE, null),
            kuGouSource = source(mockk(), LyricsProvider.KUGOU, lrc(kuGouLrc, LyricsProvider.KUGOU)),
            musixmatchSource = source(mockk(), LyricsProvider.MUSIXMATCH, null),
            geniusSource = source(mockk(), LyricsProvider.GENIUS, null),
            ytMusicSource = source(mockk(), LyricsProvider.YOUTUBE, null),
            youLyPlusSource = source(mockk(), LyricsProvider.YOULYPLUS, null),
            simpMusicSource = source(mockk(), LyricsProvider.SIMPMUSIC, null),
            captionsSource = io.mockk.mockk<com.auralis.music.data.network.provider.YouTubeCaptionsLyricsSource>().also { io.mockk.coEvery { it.timeFromCaptions(any(), any()) } returns null }
        )
        val result = client.getLyrics("AGAR TUM SAATH HO", "Arijit Singh & Alka Yagnik", 342L, "FOA9iyxsW_A", "TAMASHA", null, 342_000L)!!

        assertTrue(result.lines.joinToString { it.text }, result.lines.none { cjk.containsMatchIn(it.text) })
        assertEquals(1, result.lines.count { it.text.startsWith("Pal bhar", ignoreCase = true) })
    }
}
