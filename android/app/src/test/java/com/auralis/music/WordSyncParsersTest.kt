package com.auralis.music

import com.auralis.music.data.parser.MusixmatchRichsyncParser
import com.auralis.music.data.parser.YrcParser
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import org.junit.Assert.*
import org.junit.Test

class WordSyncParsersTest {

    @Test
    fun testMusixmatchRichsyncParser() {
        val jsonPayload = """
            [
              {
                "ts": 10.5,
                "te": 14.0,
                "x": "Never gonna give you up",
                "l": [
                  { "c": "Never", "o": 0.0 },
                  { "c": " ", "o": 0.4 },
                  { "c": "gonna", "o": 0.5 },
                  { "c": " ", "o": 0.9 },
                  { "c": "give", "o": 1.0 },
                  { "c": " ", "o": 1.4 },
                  { "c": "you", "o": 1.5 },
                  { "c": " ", "o": 1.9 },
                  { "c": "up", "o": 2.0 }
                ]
              },
              {
                "ts": 15.0,
                "te": 18.0,
                "x": "Never gonna let you down",
                "l": [
                  { "c": "Never", "o": 0.0 },
                  { "c": " ", "o": 0.4 },
                  { "c": "gonna", "o": 0.5 },
                  { "c": " ", "o": 0.9 },
                  { "c": "let", "o": 1.0 },
                  { "c": " ", "o": 1.4 },
                  { "c": "you", "o": 1.5 },
                  { "c": " ", "o": 1.9 },
                  { "c": "down", "o": 2.0 }
                ]
              }
            ]
        """.trimIndent()

        val parsed = MusixmatchRichsyncParser.parse(
            richsyncBody = jsonPayload,
            provider = LyricsProvider.MUSIXMATCH,
            trackName = "Never Gonna Give You Up",
            artistName = "Rick Astley"
        )

        assertNotNull(parsed)
        assertEquals(SyncType.RICHSYNC, parsed!!.syncType)
        assertEquals(2, parsed.lines.size)

        val line1 = parsed.lines[0]
        assertEquals(10500L, line1.time)
        assertEquals("Never gonna give you up", line1.text)
        assertNotNull(line1.words)
        // Musixmatch emits the gap between two sung words as its own " " token. That
        // token is not a word: it is folded into the trailing space of the word before
        // it, so the token count is the sung-word count and the space never gets a
        // karaoke sweep of its own. Its offset is still what ends the previous word.
        assertEquals(5, line1.words!!.size)
        assertEquals(10500L, line1.words!![0].time)
        assertEquals("Never ", line1.words!![0].word)
        assertEquals(400L, line1.words!![0].duration)
        assertEquals(12500L, line1.words!![4].time)
        assertEquals("up", line1.words!![4].word)
        // The final token runs to the line's own "te" (14.0s), verbatim.
        assertEquals(1500L, line1.words!![4].duration)
    }

    @Test
    fun testNetEaseYrcBracketParser() {
        val yrcContent = """
            [ti:Test Song]
            [ar:Test Artist]
            [1234,3500](1234,400,0)Hello (1634,600,0)world (2234,800,0)again
            [5000,3000](5000,500,0)Second (5500,500,0)line (6000,1000,0)here
        """.trimIndent()

        val parsed = YrcParser.parse(
            yrcContent = yrcContent,
            provider = LyricsProvider.NETEASE,
            trackName = "Test Song",
            artistName = "Test Artist"
        )

        assertNotNull(parsed)
        assertEquals(SyncType.RICHSYNC, parsed!!.syncType)
        assertEquals(2, parsed.lines.size)

        val line1 = parsed.lines[0]
        assertEquals(1234L, line1.time)
        assertEquals("Hello world again", line1.text)
        assertNotNull(line1.words)
        assertEquals(3, line1.words!!.size)
        assertEquals("Hello ", line1.words!![0].word)
        assertEquals(1234L, line1.words!![0].time)
        assertEquals(400L, line1.words!![0].duration)
    }

    @Test
    fun testBetterLyricsTtmlParser() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:10.500" end="00:14.000">
                    <span begin="00:10.500" end="00:11.200">Take </span>
                    <span begin="00:11.200" end="00:11.800">me </span>
                    <span begin="00:11.800" end="00:12.500">now</span>
                  </p>
                  <p begin="00:15.000" end="00:18.000">
                    <span begin="00:15.000" end="00:16.000">We </span>
                    <span begin="00:16.000" end="00:17.500">can try</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsed = com.auralis.music.data.parser.BetterLyricsParser.parse(
            content = ttml,
            provider = LyricsProvider.BETTER_LYRICS,
            trackName = "We Are The People",
            artistName = "Empire Of The Sun"
        )

        assertNotNull(parsed)
        assertEquals(SyncType.RICHSYNC, parsed!!.syncType)
        assertEquals(2, parsed.lines.size)

        val line1 = parsed.lines[0]
        assertEquals(10500L, line1.time)
        assertEquals("Take me now", line1.text)
        assertNotNull(line1.words)
        assertEquals(3, line1.words!!.size)
        assertEquals("Take ", line1.words!![0].word)
        assertEquals(10500L, line1.words!![0].time)
        assertEquals(700L, line1.words!![0].duration)
    }

    @Test
    fun testBetterLyricsQrcParser() {
        val qrc = """
            [ti:Empire Song]
            [ar:Empire]
            [00:12.50](12500,500)Slow (13000,400)down, (13400,600)be (14000,500)cool
            [00:16.00](16000,400)I (16400,500)miss (16900,600)you
        """.trimIndent()

        val parsed = com.auralis.music.data.parser.BetterLyricsParser.parse(
            content = qrc,
            provider = LyricsProvider.BETTER_LYRICS,
            trackName = "Empire Song",
            artistName = "Empire"
        )

        assertNotNull(parsed)
        assertEquals(SyncType.RICHSYNC, parsed!!.syncType)
        assertEquals(2, parsed.lines.size)

        val line1 = parsed.lines[0]
        assertEquals(12500L, line1.time)
        assertEquals("Slow down, be cool", line1.text)
        assertNotNull(line1.words)
        assertEquals(4, line1.words!!.size)
        assertEquals("Slow ", line1.words!![0].word)
        assertEquals(12500L, line1.words!![0].time)
        assertEquals(500L, line1.words!![0].duration)
    }

    @Test
    fun testKrcParser() {
        val krcContent = """
            [ti:Creep]
            [ar:Radiohead]
            [offset:0]
            [16746,1144]<0,216,0>When <216,256,0>you <472,216,0>were <688,177,0>here <865,279,0>before
            [20706,1976]<0,233,0>Couldn't <233,239,0>look <472,280,0>you <752,256,0>in <1008,352,0>the <1360,616,0>eye
        """.trimIndent()

        val parsed = com.auralis.music.data.parser.KrcParser.parse(
            krcContent = krcContent,
            provider = LyricsProvider.KUGOU,
            trackName = "Creep",
            artistName = "Radiohead"
        )

        assertNotNull(parsed)
        assertEquals(SyncType.RICHSYNC, parsed!!.syncType)
        assertEquals(2, parsed.lines.size)

        val line1 = parsed.lines[0]
        assertEquals(16746L, line1.time)
        assertEquals("When you were here before", line1.text)
        assertEquals(5, line1.words!!.size)
        assertEquals("When ", line1.words!![0].word)
        assertEquals(16746L, line1.words!![0].time)
        assertEquals(216L, line1.words!![0].duration)
        assertEquals("you ", line1.words!![1].word)
        assertEquals(16746L + 216L, line1.words!![1].time)
    }

    @Test
    fun testQrcParser() {
        val qrcXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <QrcInfos>
            <LyricInfo>
            <Lyric_1 LyricType="1" LyricContent="[ti:Creep]
            [ar:Radiohead]
            [20240,1479]When (20240,170)you (20410,160)were (20570,229)here (20799,190)before(20989,730)
            [25049,1820]Couldn't (25049,210)look (25259,130)you (25389,370)in (25759,190)the (25949,320)eye(26269,600)
            "/>
            </LyricInfo>
            </QrcInfos>
        """.trimIndent()

        val parsed = com.auralis.music.data.parser.QrcParser.parse(
            qrcContent = qrcXml,
            provider = LyricsProvider.QQMUSIC,
            trackName = "Creep",
            artistName = "Radiohead"
        )

        assertNotNull(parsed)
        assertEquals(SyncType.RICHSYNC, parsed!!.syncType)
        assertEquals(2, parsed.lines.size)

        val line1 = parsed.lines[0]
        assertEquals(20240L, line1.time)
        assertEquals("When you were here before", line1.text)
        assertEquals(5, line1.words!!.size)
        assertEquals("When ", line1.words!![0].word)
        assertEquals(20240L, line1.words!![0].time)
        assertEquals(170L, line1.words!![0].duration)
    }

    @Test
    fun testMusixmatchRichsyncPacingAndTrailingDurationCap() {
        // Real-world sample with a 4-second instrumental break at the end and a 50ms quick-tap word
        val jsonPayload = """
            [
              {
                "ts": 9.61,
                "te": 19.02,
                "x": "Mujhko itna bataaye koyi",
                "l": [
                  { "c": "Mujhko", "o": 0.0 },
                  { "c": " ", "o": 1.24 },
                  { "c": "itna", "o": 3.54 },
                  { "c": " ", "o": 3.59 },
                  { "c": "bataaye", "o": 4.2 },
                  { "c": " ", "o": 5.28 },
                  { "c": "koyi", "o": 5.42 }
                ]
              }
            ]
        """.trimIndent()

        val parsed = MusixmatchRichsyncParser.parse(
            richsyncBody = jsonPayload,
            provider = LyricsProvider.MUSIXMATCH,
            trackName = "Kesariya",
            artistName = "Pritam"
        )

        assertNotNull(parsed)
        val line = parsed!!.lines[0]
        val words = line.words!!
        assertEquals(4, words.size)

        // "itna" had a 50ms space release (3.59 - 3.54), but next word "bataaye" starts at 4.20.
        // It should be normalized to at least 180ms to prevent fast-pacing glitch.
        assertEquals("itna ", words[1].word)
        assertTrue("Short word duration must be normalized", words[1].duration!! >= 180L)

        // Final token "koyi" starts at 9.61 + 5.42 = 15.03s, lineEnd is 19.02s (3.99s later).
        // Must be capped so it doesn't slowly highlight across the 4-second instrumental rest.
        val lastWord = words.last()
        assertEquals("koyi", lastWord.word)
        assertTrue("Last word duration must be capped under 1300ms", lastWord.duration!! <= 1200L)

        // Alignment engine advances Musixmatch by 150ms to eliminate curator tap latency
        val aligned = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(parsed, 200_000L)
        assertEquals(9610L - 150L, aligned.lines[0].time)
        assertEquals(-150L, aligned.appliedOffsetMs)
    }
}
