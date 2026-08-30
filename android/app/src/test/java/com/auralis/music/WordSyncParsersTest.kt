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
        assertEquals(9, line1.words!!.size)
        assertEquals(10500L, line1.words!![0].time)
        assertEquals("Never", line1.words!![0].word)
        assertEquals(12500L, line1.words!![8].time)
        assertEquals("up", line1.words!![8].word)
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
}
