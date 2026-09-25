package com.auralis.music.ui.lyrics

import com.auralis.music.data.network.AiLyricsTranslator
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.data.repository.LyricsRepositoryImpl
import com.auralis.music.domain.model.AiTranslationSettings
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 7.7 regression: "Tere Liye" (Veer-Zaara, Lata Mangeshkar & Roop Kumar Rathod) rendered
 * in the single-position MetroLyrics layout although its Apple Music lyrics name the singer
 * of every line.
 *
 * Fixture: the Apple Music TTML served by Paxsenix for Apple id 673583141, with the real
 * head agent declarations and every `<p>` begin/end/ttm:agent kept verbatim; each line is
 * trimmed to its first two word spans.
 *
 * Root cause: the lyrics did reach the app with `ttm:agent` intact, but every load path
 * then runs [AiLyricsTranslator.translateLyrics]. With no API key it falls back to local
 * Hinglish transliteration for Devanagari lines and rebuilt each [LyricLine] from only
 * time/text/translation/words/isInstrumental, dropping `agent` (plus `isBackground` and
 * `endTime`). [MetroSpeakerLayout] then saw zero speakers and returned null.
 */
class TereLiyeSpeakerRegressionTest {

    private val veerZaaraTtml = """
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word" xml:lang="hi"><head><metadata><ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/><ttm:agent type="group" xml:id="v1000"/></metadata></head><body dur="5:31.677"><div begin="34.181" end="5:31.677">
<p begin="34.181" end="43.471" ttm:agent="v1"><span begin="34.181" end="35.525">तेरे</span> <span begin="35.525" end="36.504">लिए</span></p>
<p begin="43.991" end="52.916" ttm:agent="v2"><span begin="43.991" end="45.270">तेरे</span> <span begin="45.270" end="46.570">लिए</span></p>
<p begin="53.471" end="1:03.420" ttm:agent="v1"><span begin="53.471" end="53.898">दिल</span> <span begin="53.898" end="54.210">में</span></p>
<p begin="1:03.420" end="1:08.590" ttm:agent="v1"><span begin="1:03.420" end="1:03.996">तेरे</span> <span begin="1:03.996" end="1:05.610">लिए</span></p>
<p begin="1:08.590" end="1:17.714" ttm:agent="v2"><span begin="1:08.590" end="1:09.731">तेरे</span> <span begin="1:09.731" end="1:10.759">लिए</span></p>
<p begin="1:18.281" end="1:27.518" ttm:agent="v1"><span begin="1:18.281" end="1:19.565">तेरे</span> <span begin="1:19.565" end="1:20.825">लिए</span></p>
<p begin="1:27.751" end="1:37.589" ttm:agent="v2"><span begin="1:27.751" end="1:28.198">दिल</span> <span begin="1:28.198" end="1:28.458">में</span></p>
<p begin="1:37.589" end="1:43.003" ttm:agent="v2"><span begin="1:37.589" end="1:38.243">तेरे</span> <span begin="1:38.243" end="1:39.848">लिए</span></p>
<p begin="1:52.621" end="2:08.224" ttm:agent="v2"><span begin="1:52.621" end="2:00.423">Aa-</span> <span begin="2:00.423" end="2:08.224">aa</span></p>
<p begin="2:07.951" end="2:12.231" ttm:agent="v1"><span begin="2:07.951" end="2:09.338">ज़िंदगी</span> <span begin="2:09.609" end="2:10.092">ले</span></p>
<p begin="2:12.801" end="2:17.381" ttm:agent="v1"><span begin="2:12.801" end="2:13.521">बीते</span> <span begin="2:13.521" end="2:14.448">दिनों</span></p>
<p begin="2:17.631" end="2:22.321" ttm:agent="v1"><span begin="2:17.631" end="2:19.093">ज़िंदगी</span> <span begin="2:19.386" end="2:19.915">ले</span></p>
<p begin="2:22.571" end="2:26.921" ttm:agent="v1"><span begin="2:22.571" end="2:23.323">बीते</span> <span begin="2:23.323" end="2:24.256">दिनों</span></p>
<p begin="2:27.481" end="2:36.569" ttm:agent="v1"><span begin="2:27.481" end="2:28.497">घेरे</span> <span begin="2:28.497" end="2:30.059">हैं</span></p>
<p begin="2:37.101" end="2:46.071" ttm:agent="v2"><span begin="2:37.101" end="2:37.947">बिन</span> <span begin="2:37.947" end="2:39.752">पूछे</span></p>
<p begin="2:46.491" end="2:51.561" ttm:agent="v2"><span begin="2:46.491" end="2:47.781">चाहा</span> <span begin="2:47.781" end="2:48.048">था</span></p>
<p begin="2:51.931" end="2:55.970" ttm:agent="v2"><span begin="2:51.931" end="2:53.629">हमने</span> <span begin="2:53.629" end="2:55.970">देखिए</span></p>
<p begin="2:55.940" end="3:05.833" ttm:agent="v1"><span begin="2:55.940" end="2:56.349">दिल</span> <span begin="2:56.349" end="2:56.667">में</span></p>
<p begin="3:05.833" end="3:11.680" ttm:agent="v1"><span begin="3:05.833" end="3:06.407">तेरे</span> <span begin="3:06.407" end="3:08.071">लिए</span></p>
<p begin="3:31.106" end="3:35.704" ttm:agent="v2"><span begin="3:31.106" end="3:31.705">क्या</span> <span begin="3:31.705" end="3:32.466">कहूँ</span></p>
<p begin="3:36.026" end="3:40.528" ttm:agent="v2"><span begin="3:36.026" end="3:36.723">मुझ</span> <span begin="3:36.723" end="3:37.896">से</span></p>
<p begin="3:40.976" end="3:45.785" ttm:agent="v2"><span begin="3:40.976" end="3:41.373">क्या</span> <span begin="3:41.373" end="3:42.478">कहूँ</span></p>
<p begin="3:45.956" end="3:50.193" ttm:agent="v2"><span begin="3:45.956" end="3:46.507">मुझ</span> <span begin="3:46.507" end="3:47.466">से</span></p>
<p begin="3:50.766" end="4:00.061" ttm:agent="v2"><span begin="3:50.766" end="3:51.773">हुकुम</span> <span begin="3:51.773" end="3:53.269">था</span></p>
<p begin="4:00.446" end="4:05.237" ttm:agent="v1"><span begin="4:00.446" end="4:01.245">नादान</span> <span begin="4:01.245" end="4:01.563">है</span></p>
<p begin="4:05.416" end="4:09.176" ttm:agent="v1"><span begin="4:05.416" end="4:06.125">मेरे</span> <span begin="4:06.125" end="4:07.059">लिए</span></p>
<p begin="4:09.746" end="4:19.205" ttm:agent="v1"><span begin="4:09.746" end="4:10.949">कितने</span> <span begin="4:10.949" end="4:12.022">सितम</span></p>
<p begin="4:19.205" end="4:29.079" ttm:agent="v2"><span begin="4:19.205" end="4:19.588">दिल</span> <span begin="4:19.588" end="4:19.923">में</span></p>
<p begin="4:29.079" end="4:34.295" ttm:agent="v2"><span begin="4:29.079" end="4:29.779">तेरे</span> <span begin="4:29.779" end="4:31.456">लिए</span></p>
<p begin="4:34.026" end="4:44.027" ttm:agent="v1"><span begin="4:34.026" end="4:35.489">तेरे</span> <span begin="4:35.489" end="4:36.526">लिए</span></p>
<p begin="4:44.027" end="4:53.221" ttm:agent="v2"><span begin="4:44.027" end="4:45.274">तेरे</span> <span begin="4:45.274" end="4:46.537">लिए</span></p>
<p begin="4:53.496" end="5:03.351" ttm:agent="v1000"><span begin="4:53.496" end="4:53.895">दिल</span> <span begin="4:53.895" end="4:54.204">में</span></p>
<p begin="5:03.351" end="5:08.474" ttm:agent="v1000"><span begin="5:03.351" end="5:03.988">तेरे</span> <span begin="5:03.988" end="5:05.593">लिए</span></p>
<p begin="5:08.274" end="5:14.095" ttm:agent="v1000"><span begin="5:08.274" end="5:09.108">तेरे</span> <span begin="5:09.108" end="5:10.688">लिए</span></p>
<p begin="5:13.126" end="5:19.287" ttm:agent="v1000"><span begin="5:13.126" end="5:14.044">तेरे</span> <span begin="5:14.044" end="5:15.675">लिए</span></p>
</div></body></tt>    """.trimIndent()

    /** Same line preparation ExperimentalLyricsView applies before building the layout. */
    private fun metroLines(data: LyricsData): List<LyricLine> = data.lines
        .map { WordTiming.splitMergedWordsInLine(it) }
        .filter { it.text.isNotBlank() || it.words?.any { w -> w.word.isNotBlank() } == true }

    private fun parsed(): LyricsData = TtmlParser.parse(veerZaaraTtml, LyricsProvider.PAXSENIX)

    @Test
    fun `apple music ttml for tere liye names the singer of every line`() {
        val lines = parsed().lines
        assertEquals(35, lines.size)
        val counts = lines.groupingBy { it.agent }.eachCount()
        assertEquals(15, counts["v1"])
        assertEquals(16, counts["v2"])
        assertEquals(4, counts["v1000"])
        assertTrue(lines.none { it.agent == null })
    }

    @Test
    fun `parsed tere liye engages the speaker-aware layout`() {
        val styles = MetroSpeakerLayout.build(metroLines(parsed()))
        assertNotNull(styles)
        assertEquals(MetroSpeakerSide.START, styles!![0].side)  // v1 Lata
        assertEquals(MetroSpeakerSide.END, styles[1].side)      // v2 Roop Kumar
        assertEquals(MetroSpeakerSide.CENTER, styles.last().side) // v1000 together
    }

    @Test
    fun `lyrics cache round trip keeps the agents`() {
        val entity = LyricsRepositoryImpl.domainToEntity("tere_liye", parsed(), "Tere Liye", "Lata Mangeshkar")
        val restored = LyricsRepositoryImpl.entityToDomain(entity, "Tere Liye", "Lata Mangeshkar")!!
        assertEquals(parsed().lines.map { it.agent }, restored.lines.map { it.agent })
        assertNotNull(MetroSpeakerLayout.build(metroLines(restored)))
    }

    @Test
    fun `hinglish transliteration keeps the agents so the speaker layout survives`() = runBlocking {
        val source = parsed()
        // Default settings = no API key -> the local Devanagari->Hinglish fallback that ran on device.
        val translated = AiLyricsTranslator.translateLyrics("tere_liye_regression", source, AiTranslationSettings())
        assertNotNull(translated)

        assertEquals(source.lines.map { it.agent }, translated!!.lines.map { it.agent })
        assertTrue(translated.lines.any { !it.translatedText.isNullOrBlank() })
        assertNotNull(MetroSpeakerLayout.build(metroLines(translated)))
    }

    @Test
    fun `every rendered line gets the side of its own agent`() = runBlocking {
        // Device path: provider TTML -> Hinglish transliteration -> the view's line preparation,
        // then the per-index style ExperimentalLyricsView hands to ExperimentalLyricsLine.
        val translated = AiLyricsTranslator.translateLyrics("tere_liye_per_line", parsed(), AiTranslationSettings())!!
        val lines = metroLines(translated)
        val metro = com.auralis.music.domain.model.AppearanceSettings(
            lyricsAnimation = com.auralis.music.domain.model.LyricsAnimationMode.METRO_LYRICS.displayName
        )
        val styles = MetroSpeakerLayout.forAppearance(lines, metro)!!
        assertEquals(lines.size, styles.size)

        lines.forEachIndexed { i, line ->
            val expectedSide = when (line.agent) {
                "v1" -> MetroSpeakerSide.START
                "v2" -> MetroSpeakerSide.END
                "v1000" -> MetroSpeakerSide.CENTER
                else -> null
            }
            println("L${i + 1}\t${line.time}ms\tagent=${line.agent}\tside=${styles[i].side}\trunStart=${styles[i].isRunStart}\t${line.translatedText}")
            assertEquals("line $i speaker", line.agent, styles[i].speakerKey)
            assertEquals("line $i side", expectedSide, styles[i].side)
        }
        // Both singers really appear on their own sides, not just "multiple speakers detected".
        assertEquals(15, styles.count { it.side == MetroSpeakerSide.START })
        assertEquals(16, styles.count { it.side == MetroSpeakerSide.END })
        assertEquals(4, styles.count { it.side == MetroSpeakerSide.CENTER })
    }

    @Test
    fun `transliteration only adds the translation and leaves timing and roles untouched`() = runBlocking {
        val source = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(
                LyricLine(time = 1_000L, text = "तेरे लिए", endTime = 2_000L, agent = "v1"),
                LyricLine(time = 1_500L, text = "(तेरे लिए)", isBackground = true, endTime = 2_500L, agent = "v1"),
                LyricLine(time = 3_000L, text = "तेरे लिए", endTime = 4_000L, agent = "v2")
            ),
            provider = LyricsProvider.PAXSENIX
        )
        val translated = AiLyricsTranslator.translateLyrics("tere_liye_roles", source, AiTranslationSettings())!!

        source.lines.zip(translated.lines).forEach { (before, after) ->
            assertEquals(before.copy(translatedText = after.translatedText), after)
        }
    }

    @Test
    fun `two credited artists without line level agents keep the old layout`() {
        // Prince / Namaste England "Tere Liye": no TTML source exists, so the winner is
        // line-synced LRC with no per-line singer. Two credited artists prove nothing.
        val data = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = (0 until 8).map { LyricLine(time = 10_000L + it * 4_000L, text = "line $it") },
            provider = LyricsProvider.LRCLIB,
            artistName = "Atif Aslam & Shreya Ghoshal"
        )
        assertNull(MetroSpeakerLayout.build(metroLines(data)))
    }
}
