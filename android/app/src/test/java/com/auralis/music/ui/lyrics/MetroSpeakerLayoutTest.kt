package com.auralis.music.ui.lyrics

import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsAnimationMode
import com.auralis.music.domain.model.LyricsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 7.7 — MetroLyrics speaker-aware presentation model.
 *
 * The layout must come only from provider agent metadata, and must stay out of the way
 * (return null → original MetroLyrics layout) whenever that metadata is not a real
 * multi-vocalist attribution.
 */
class MetroSpeakerLayoutTest {

    private fun line(time: Long, agent: String?, text: String = "line $time", bg: Boolean = false, translation: String? = null) =
        LyricLine(time = time, text = text, agent = agent, isBackground = bg, translatedText = translation)

    // ── Fallback: no reliable multi-speaker metadata ──

    @Test
    fun `no speaker metadata falls back to the existing layout`() {
        val lines = (0 until 6).map { line(it * 1_000L, null) }
        assertNull(MetroSpeakerLayout.build(lines))
    }

    @Test
    fun `empty lyrics fall back`() {
        assertNull(MetroSpeakerLayout.build(emptyList()))
    }

    @Test
    fun `single speaker keeps the normal layout`() {
        val lines = (0 until 5).map { line(it * 1_000L, "v1") }
        assertNull(MetroSpeakerLayout.build(lines))
    }

    @Test
    fun `one singer plus the ensemble is not treated as a duet`() {
        val lines = listOf(line(0, "v1"), line(1_000, "v1000"), line(2_000, "v1"))
        assertNull(MetroSpeakerLayout.build(lines))
    }

    @Test
    fun `an ad-lib attributed to a second agent alone does not make a duet`() {
        val lines = listOf(line(0, "v1"), line(500, "v2", bg = true), line(1_000, "v1"))
        assertNull(MetroSpeakerLayout.build(lines))
    }

    @Test
    fun `blank and whitespace agent ids count as missing`() {
        val lines = listOf(line(0, "  "), line(1_000, ""), line(2_000, "v1"))
        assertNull(MetroSpeakerLayout.build(lines))
    }

    // ── Two speakers ──

    @Test
    fun `two speakers alternating get opposite sides and one run per line`() {
        val lines = listOf(line(0, "v1"), line(1_000, "v2"), line(2_000, "v1"), line(3_000, "v2"))
        val styles = MetroSpeakerLayout.build(lines)!!

        assertEquals(
            listOf(MetroSpeakerSide.START, MetroSpeakerSide.END, MetroSpeakerSide.START, MetroSpeakerSide.END),
            styles.map { it.side }
        )
        assertEquals(listOf(0, 1, 0, 1), styles.map { it.slot })
        assertEquals(4, styles.map { it.runId }.distinct().size)
        assertTrue(styles.all { it.isRunStart && it.isRunEnd })
    }

    @Test
    fun `consecutive lines from the same speaker form one run`() {
        val lines = listOf(
            line(0, "v1"), line(1_000, "v1"), line(2_000, "v1"),
            line(3_000, "v2"), line(4_000, "v2")
        )
        val styles = MetroSpeakerLayout.build(lines)!!

        assertEquals(listOf(0, 0, 0, 1, 1), styles.map { it.runId })
        assertEquals(listOf(true, false, false, true, false), styles.map { it.isRunStart })
        assertEquals(listOf(false, false, true, false, true), styles.map { it.isRunEnd })
    }

    @Test
    fun `v1 keeps the start side even when v2 opens the song`() {
        val lines = listOf(line(0, "v2"), line(1_000, "v1"), line(2_000, "v2"))
        val styles = MetroSpeakerLayout.build(lines)!!

        assertEquals(MetroSpeakerSide.END, styles[0].side)
        assertEquals(MetroSpeakerSide.START, styles[1].side)
        assertEquals(0, styles[1].slot)
        assertEquals(1, styles[0].slot)
    }

    @Test
    fun `agent ids are normalized so casing and padding do not split a speaker`() {
        val lines = listOf(line(0, "V1"), line(1_000, " v2 "), line(2_000, "v1"))
        val styles = MetroSpeakerLayout.build(lines)!!

        assertEquals("v1", styles[0].speakerKey)
        assertEquals(styles[0].slot, styles[2].slot)
        assertEquals("v2", styles[1].speakerKey)
    }

    // ── Three or more speakers ──

    @Test
    fun `three or more speakers each keep a distinct stable slot`() {
        val lines = listOf(
            line(0, "v1"), line(1_000, "v2"), line(2_000, "v3"),
            line(3_000, "v4"), line(4_000, "v3"), line(5_000, "v1")
        )
        val styles = MetroSpeakerLayout.build(lines)!!

        assertEquals(listOf(0, 1, 2, 3, 2, 0), styles.map { it.slot })
        assertEquals(
            listOf(MetroSpeakerSide.START, MetroSpeakerSide.END, MetroSpeakerSide.START, MetroSpeakerSide.END, MetroSpeakerSide.START, MetroSpeakerSide.START),
            styles.map { it.side }
        )
        assertTrue(styles.all { it.slot >= 0 })
    }

    @Test
    fun `non numbered agent ids follow first appearance order after numbered ones`() {
        val lines = listOf(line(0, "lead"), line(1_000, "feature"), line(2_000, "v1"))
        val styles = MetroSpeakerLayout.build(lines)!!

        assertEquals(0, styles[2].slot) // v1
        assertEquals(1, styles[0].slot) // lead (first seen)
        assertEquals(2, styles[1].slot) // feature
    }

    // ── Chorus / ensemble / rapid switching ──

    @Test
    fun `repeated chorus lines keep the same speaker treatment every time`() {
        val chorus = "We are the ones"
        val lines = listOf(
            line(0, "v1", text = chorus), line(1_000, "v2", text = "verse"),
            line(2_000, "v2", text = chorus), line(3_000, "v1", text = chorus)
        )
        val styles = MetroSpeakerLayout.build(lines)!!

        // Identity follows the agent, never the text: the same words sung by v2 stay v2.
        assertEquals(listOf(0, 1, 1, 0), styles.map { it.slot })
    }

    @Test
    fun `ensemble lines are centered and break speaker runs`() {
        val lines = listOf(line(0, "v1"), line(1_000, "v1000"), line(2_000, "v1000"), line(3_000, "v2"))
        val styles = MetroSpeakerLayout.build(lines)!!

        assertEquals(MetroSpeakerSide.CENTER, styles[1].side)
        assertTrue(styles[1].isEnsemble)
        assertEquals(-1, styles[1].slot)
        assertEquals(styles[1].runId, styles[2].runId)
        assertTrue(styles[1].isRunStart)
        assertTrue(styles[2].isRunEnd)
    }

    @Test
    fun `rapid speaker switching yields a run boundary on every change`() {
        val agents = listOf("v1", "v2", "v1", "v2", "v2", "v1", "v2", "v1")
        val styles = MetroSpeakerLayout.build(agents.mapIndexed { i, a -> line(i * 300L, a) })!!

        for (i in 1 until agents.size) {
            assertEquals(agents[i] != agents[i - 1], styles[i].isRunStart)
        }
    }

    // ── Missing / malformed metadata inside a duet ──

    @Test
    fun `unattributed lines inside a duet keep the regular position and break runs`() {
        val lines = listOf(line(0, "v1"), line(1_000, null), line(2_000, "v2"))
        val styles = MetroSpeakerLayout.build(lines)!!

        assertNull(styles[1].side)
        assertNull(styles[1].speakerKey)
        assertEquals(-1, styles[1].slot)
        assertEquals(-1, styles[1].slot)
        assertTrue(styles[0].isRunEnd)
        assertTrue(styles[2].isRunStart)
    }

    @Test
    fun `background ad-lib without its own agent joins the lead line's run`() {
        val lines = listOf(
            line(0, "v1"), line(300, null, text = "(yeah)", bg = true),
            line(1_000, "v1"), line(2_000, "v2")
        )
        val styles = MetroSpeakerLayout.build(lines)!!

        assertEquals("v1", styles[1].speakerKey)
        assertEquals(MetroSpeakerSide.START, styles[1].side)
        assertEquals(styles[0].runId, styles[1].runId)
        assertEquals(styles[0].runId, styles[2].runId)
    }

    @Test
    fun `output is index aligned with the input lines`() {
        val lines = listOf(line(0, "v1"), line(1_000, "x"), line(2_000, null), line(3_000, "v2"))
        assertEquals(lines.size, MetroSpeakerLayout.build(lines)!!.size)
    }

    // ── Translations ──

    @Test
    fun `translations stay on their speaker-aware line`() {
        val lines = listOf(
            line(0, "v1", translation = "uno"),
            line(1_000, "v2", translation = "dos"),
            line(2_000, "v2")
        )
        val styles = MetroSpeakerLayout.build(lines)!!

        // Translation is rendered inside the same line item, so it inherits that side.
        assertEquals(MetroSpeakerSide.START, styles[0].side)
        assertEquals(MetroSpeakerSide.END, styles[1].side)
        assertEquals(styles[1].runId, styles[2].runId)
    }

    // ── Activation: owned by the lyric data, not by alignment settings ──

    private val duet = listOf(line(0, "v1"), line(1_000, "v2"), line(2_000, "v1"), line(3_000, "v2"))
    private val metro = AppearanceSettings(lyricsAnimation = LyricsAnimationMode.METRO_LYRICS.displayName)

    @Test
    fun `respectAgentPositioning on with two speakers engages the layout`() {
        assertNotNull(MetroSpeakerLayout.forAppearance(duet, metro.copy(respectAgentPositioning = true)))
    }

    @Test
    fun `respectAgentPositioning off with two speakers still engages the layout`() {
        val styles = MetroSpeakerLayout.forAppearance(duet, metro.copy(respectAgentPositioning = false))
        assertNotNull(styles)
        assertEquals(MetroSpeakerSide.START, styles!![0].side)
        assertEquals(MetroSpeakerSide.END, styles[1].side)
    }

    @Test
    fun `centre left and right lyric alignment neither disable nor alter the speaker sides`() {
        val expected = MetroSpeakerLayout.build(duet)
        for (position in listOf("Centre", "Left", "Right")) {
            for (respect in listOf(true, false)) {
                val styles = MetroSpeakerLayout.forAppearance(duet, metro.copy(lyricsTextPosition = position, respectAgentPositioning = respect))
                assertEquals("position=$position respect=$respect", expected, styles)
            }
        }
    }

    @Test
    fun `speaker layout is MetroLyrics only`() {
        LyricsAnimationMode.entries.filter { it != LyricsAnimationMode.METRO_LYRICS }.forEach { mode ->
            assertNull(mode.name, MetroSpeakerLayout.forAppearance(duet, AppearanceSettings(lyricsAnimation = mode.displayName, experimentalLyrics = true)))
        }
    }

    @Test
    fun `MetroLyrics without agents or with one agent keeps the old layout under any alignment`() {
        val none = (0 until 4).map { line(it * 1_000L, null) }
        val single = (0 until 4).map { line(it * 1_000L, "v1") }
        for (position in listOf("Centre", "Left", "Right")) {
            assertNull(MetroSpeakerLayout.forAppearance(none, metro.copy(lyricsTextPosition = position)))
            assertNull(MetroSpeakerLayout.forAppearance(single, metro.copy(lyricsTextPosition = position)))
        }
    }

    @Test
    fun `v1 to v4 map to left right left right`() {
        val styles = MetroSpeakerLayout.build(listOf(line(0, "v1"), line(1_000, "v2"), line(2_000, "v3"), line(3_000, "v4")))!!
        assertEquals(
            listOf(MetroSpeakerSide.START, MetroSpeakerSide.END, MetroSpeakerSide.START, MetroSpeakerSide.END),
            styles.map { it.side }
        )
    }

    // ── End to end through the real parsers ──

    @Test
    fun `ttml duet agents flow from the parser into the layout with translations`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
              <head><metadata>
                <ttm:agent type="person" xml:id="v1"/>
                <ttm:agent type="person" xml:id="v2"/>
              </metadata></head>
              <body><div>
                <p begin="00:01.000" end="00:02.000" ttm:agent="v1"><span begin="00:01.000" end="00:02.000">Hello</span><span ttm:role="x-translation">Hola</span></p>
                <p begin="00:02.000" end="00:03.000" ttm:agent="v2"><span begin="00:02.000" end="00:03.000">There</span></p>
                <p begin="00:03.000" end="00:04.000" ttm:agent="v2"><span begin="00:03.000" end="00:04.000">Friend</span></p>
              </div></body>
            </tt>
        """.trimIndent()

        val parsed = TtmlParser.parse(ttml, LyricsProvider.AMLL)
        assertEquals(listOf("v1", "v2", "v2"), parsed.lines.map { it.agent })
        assertEquals("Hola", parsed.lines[0].translatedText)

        val styles = MetroSpeakerLayout.build(parsed.lines)
        assertNotNull(styles)
        assertEquals(MetroSpeakerSide.START, styles!![0].side)
        assertEquals(MetroSpeakerSide.END, styles[1].side)
        assertEquals(styles[1].runId, styles[2].runId)
    }

    @Test
    fun `a declared group agent other than v1000 is centred, not treated as a third singer`() {
        // Shape of the real Apple TTML for "STAY" (The Kid LAROI & Justin Bieber): v4 is the group.
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head><metadata>
                <ttm:agent type="person" xml:id="v1"/>
                <ttm:agent type="person" xml:id="v2"/>
                <ttm:agent type="group" xml:id="v4"/>
              </metadata></head>
              <body><div>
                <p begin="00:01.000" end="00:02.000" ttm:agent="v1"><span begin="00:01.000" end="00:02.000">One</span></p>
                <p begin="00:02.000" end="00:03.000" ttm:agent="v2"><span begin="00:02.000" end="00:03.000">Two</span></p>
                <p begin="00:03.000" end="00:04.000" ttm:agent="v4"><span begin="00:03.000" end="00:04.000">All</span></p>
                <p begin="00:04.000" end="00:05.000" ttm:agent="v3"><span begin="00:04.000" end="00:05.000">Undeclared</span></p>
              </div></body>
            </tt>
        """.trimIndent()

        val parsed = TtmlParser.parse(ttml, LyricsProvider.PAXSENIX)
        // Only the declared group is canonicalized; person and undeclared ids are kept as given.
        assertEquals(listOf("v1", "v2", MetroSpeakerLayout.ENSEMBLE_AGENT, "v3"), parsed.lines.map { it.agent })

        val styles = MetroSpeakerLayout.build(parsed.lines)!!
        assertEquals(
            listOf(MetroSpeakerSide.START, MetroSpeakerSide.END, MetroSpeakerSide.CENTER, MetroSpeakerSide.START),
            styles.map { it.side }
        )
    }

    @Test
    fun `plain lrc without agent tags keeps the existing layout`() {
        val parsed = LrcParser.parse("[00:01.00]First\n[00:02.00]Second\n[00:03.00]Third", LyricsProvider.LRCLIB)
        assertNull(MetroSpeakerLayout.build(parsed.lines))
    }

    @Test
    fun `lrc agent tags are honoured`() {
        val parsed = LrcParser.parse(
            "[00:01.00]{agent:v1}First\n[00:02.00]{agent:v2}Second\n[00:03.00]{agent:v1}Third",
            LyricsProvider.LRCLIB
        )
        val styles = MetroSpeakerLayout.build(parsed.lines)!!
        assertEquals(listOf(MetroSpeakerSide.START, MetroSpeakerSide.END, MetroSpeakerSide.START), styles.map { it.side })
    }
}
