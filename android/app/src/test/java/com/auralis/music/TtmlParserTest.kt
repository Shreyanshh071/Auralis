package com.auralis.music

import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.model.SyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
