package com.auralis.music

import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.model.SyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlParserTest {

    @Test
    fun `parseTimestamp handles all standard time formats`() {
        // mm:ss.xx / hh:mm:ss.xxx
        assertEquals(83456L, TtmlParser.parseTimestamp("00:01:23.456"))
        assertEquals(83450L, TtmlParser.parseTimestamp("01:23.45"))
        assertEquals(120000L, TtmlParser.parseTimestamp("02:00.00"))

        // seconds suffix
        assertEquals(83456L, TtmlParser.parseTimestamp("83.456s"))

        // milliseconds suffix
        assertEquals(83456L, TtmlParser.parseTimestamp("83456ms"))
    }

    @Test
    fun `parse extracts duration and leading silence from creep ttml`() {
        val ttml = java.io.File("c:/Users/shrey/OneDrive/Desktop/Auralis/scratch/creep.ttml").readText()
        val parsed = TtmlParser.parse(ttml)
        println("PARSED DURATION: ${parsed.durationMs}")
        println("PARSED SILENCE: ${parsed.leadingSilenceMs}")
        assertEquals(238640L, parsed.durationMs)
        assertEquals(940L, parsed.leadingSilenceMs)
    }

    @Test
    fun `parse converts TTML XML with word-level spans into RichSync LyricsData`() {
        val ttml = """
            <?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:00:10.000" end="00:00:15.000">
                    <span begin="00:00:10.000" end="00:00:10.500">I </span>
                    <span begin="00:00:10.500" end="00:00:11.200">said, </span>
                    <span begin="00:00:11.200" end="00:00:12.500">ooh, </span>
                    <span begin="00:00:12.500" end="00:00:14.000">I'm </span>
                    <span begin="00:00:14.000" end="00:00:15.000">blinded</span>
                  </p>
                  <p begin="00:00:15.500" end="00:00:20.000">
                    <span begin="00:00:15.500" end="00:00:17.000">by </span>
                    <span begin="00:00:17.000" end="00:00:19.500">the </span>
                    <span begin="00:00:19.500" end="00:00:20.000">lights</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = TtmlParser.parse(ttml)
        assertEquals(SyncType.RICHSYNC, lyrics.syncType)
        assertEquals(2, lyrics.lines.size)

        val line1 = lyrics.lines[0]
        assertEquals(10000L, line1.time)
        assertEquals("I said, ooh, I'm blinded", line1.text)
        assertNotNull(line1.words)
        assertEquals(5, line1.words?.size)
        assertEquals("I ", line1.words?.get(0)?.word)
        assertEquals(10000L, line1.words?.get(0)?.time)
        assertEquals(500L, line1.words?.get(0)?.duration)

        val line2 = lyrics.lines[1]
        assertEquals(15500L, line2.time)
        assertEquals("by the lights", line2.text)
    }

    @Test
    fun `background vocals become their own line instead of corrupting the lead`() {
        val ttml = """
            <?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:00:10.000" end="00:00:12.000">
                    <span begin="00:00:10.000" end="00:00:10.800">I'm </span>
                    <span begin="00:00:10.800" end="00:00:12.000">blinded</span>
                    <span ttm:role="x-bg" begin="00:00:11.000" end="00:00:12.000">
                      <span begin="00:00:11.000" end="00:00:11.400">(Ooh </span>
                      <span begin="00:00:11.400" end="00:00:12.000">ooh)</span>
                    </span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = TtmlParser.parse(ttml)
        assertEquals(SyncType.RICHSYNC, lyrics.syncType)
        assertEquals(2, lyrics.lines.size)

        val lead = lyrics.lines.first { !it.isBackground }
        assertEquals("I'm blinded", lead.text)
        assertEquals(10000L, lead.time)
        assertEquals(2, lead.words?.size)

        val background = lyrics.lines.first { it.isBackground }
        assertEquals("(Ooh ooh)", background.text)
        assertEquals(11000L, background.time)
        assertEquals(2, background.words?.size)
        assertEquals(400L, background.words?.get(0)?.duration)
        assertTrue(background.words!!.all { it.isBackground })
    }

    @Test
    fun `a multi-word span is subdivided strictly inside its measured interval`() {
        val ttml = """
            <?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:00:16.000" end="00:00:17.500">
                    <span begin="00:00:16.000" end="00:00:17.500">can try</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = TtmlParser.parse(ttml)
        val words = lyrics.lines[0].words
        assertNotNull(words)
        assertEquals(2, words?.size)
        assertEquals("can ", words?.get(0)?.word)
        assertEquals(16000L, words?.get(0)?.time)
        assertEquals("try", words?.get(1)?.word)
        // Coverage is exact: the last piece ends where the span ended, not later.
        assertEquals(17500L, words?.get(1)?.endTime)
        assertEquals("can try", lyrics.lines[0].text)
    }
}
