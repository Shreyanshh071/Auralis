package com.auralis.music

import com.auralis.music.domain.lyrics.TtmlParser
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.SyncType
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainTtmlParserTest {

    @Test
    fun `parseTimestamp correctly resolves varied timestamp units`() {
        assertEquals(1500L, TtmlParser.parseTimestamp("1500ms"))
        assertEquals(2500L, TtmlParser.parseTimestamp("2.5s"))
        assertEquals(65000L, TtmlParser.parseTimestamp("01:05.000"))
        assertEquals(3661000L, TtmlParser.parseTimestamp("01:01:01.000"))
    }

    @Test
    fun `ttmlParser extracts syllable-level spans with accurate word timestamps`() {
        val ttml = """
            <?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:10.000" end="00:15.000">
                    <span begin="00:10.000" end="00:11.500">Never </span>
                    <span begin="00:11.500" end="00:13.000">gonna </span>
                    <span begin="00:13.000" end="00:15.000">give</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val result = TtmlParser.parse(ttml)

        assertEquals(SyncType.RICHSYNC, result.syncType)
        assertEquals(1, result.lines.size)

        val line = result.lines[0]
        assertEquals(10000L, line.time)
        assertEquals("Never gonna give", line.text)
        assertNotNull(line.words)
        assertEquals(3, line.words?.size)

        val firstWord = line.words?.get(0)
        assertEquals("Never ", firstWord?.word)
        assertEquals(10000L, firstWord?.time)
        assertEquals(1500L, firstWord?.duration)
    }

    @Test
    fun `lyricsEngine calculates word progression at 60fps interpolation precision`() {
        val word = LyricWord(word = "Test", time = 1000L, duration = 1000L)

        // Before word starts
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(word, 500L), 0.001f)

        // Halfway through word
        assertEquals(0.5f, LyricsEngine.calculateWordProgress(word, 1500L), 0.001f)

        // After word completes
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(word, 2500L), 0.001f)
    }
}
